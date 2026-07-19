#!/usr/bin/env bash
# Home Assistant Integration Validator
# Validate custom_components integrations against HA loading rules.
#
# Usage:
#   bash validate.sh <path>
#
#   <path> can be:
#     - A single integration directory (e.g., custom_components/my_integration)
#     - A custom_components directory containing multiple integrations

set -euo pipefail

PASS=0
FAIL=0

check() {
  local integration="$1"
  local rule_name="$2"
  local passed="$3"
  local detail="$4"

  if [[ "$passed" == "true" ]]; then
    echo "  ✓ [$integration] $rule_name"
    PASS=$((PASS + 1))
  elif [[ "$passed" == "warn" ]]; then
    echo "  ⚠ [$integration] $rule_name — $detail"
    PASS=$((PASS + 1))
  else
    echo "  ✗ [$integration] $rule_name — $detail"
    FAIL=$((FAIL + 1))
  fi
}

validate_integration() {
  local dir="$1"

  [[ -d "$dir" ]] || return
  [[ -f "$dir/manifest.json" ]] || return

  local dirname
  dirname=$(basename "$dir")

  echo ""
  echo "━━━ Validating: $dirname ━━━"

  # --- Rule 1: manifest.json is valid JSON ---
  if python3 -c "import json; json.load(open('$dir/manifest.json'))" 2>/dev/null; then
    check "$dirname" "manifest.json valid JSON" true ""
  else
    check "$dirname" "manifest.json valid JSON" false "invalid JSON, cannot parse"
    return  # skip remaining rules if JSON is broken
  fi

  # --- Rule 2: directory name matches domain ---
  local domain
  domain=$(python3 -c "import json; print(json.load(open('$dir/manifest.json')).get('domain',''))" 2>/dev/null)
  if [[ "$dir" == *"/components/"* ]]; then
    # Built-in integration — just check consistency
    check "$dirname" "dir name matches domain ($domain)" true ""
  elif [[ "$dirname" == "$domain" ]]; then
    check "$dirname" "dir name matches domain ($domain)" true ""
  else
    check "$dirname" "dir name matches domain" false "dir='$dirname' domain='$domain'"
  fi

  # --- Rule 3: version field present (required for custom_components) ---
  local version
  version=$(python3 -c "import json; print(json.load(open('$dir/manifest.json')).get('version',''))" 2>/dev/null)
  if [[ -n "$version" ]]; then
    check "$dirname" "version field present" true "$version"
  else
    if [[ "$dir" == *"custom_components"* ]]; then
      check "$dirname" "version field present" false "missing — HA blocks loading without version"
    else
      check "$dirname" "version field present" "warn" "missing (OK for built-in, required for custom_components)"
    fi
  fi

  # --- Rule 4: config_flow.py exists if declared ---
  local config_flow
  config_flow=$(python3 -c "import json; print(json.load(open('$dir/manifest.json')).get('config_flow',False))" 2>/dev/null)
  if [[ "$config_flow" == "True" ]]; then
    if [[ -f "$dir/config_flow.py" ]]; then
      check "$dirname" "config_flow.py exists" true ""
    else
      check "$dirname" "config_flow.py exists" false "manifest declares config_flow but file missing"
    fi
  fi

  # --- Rule 5: required files exist ---
  for required_file in "__init__.py" "const.py"; do
    if [[ -f "$dir/$required_file" ]]; then
      check "$dirname" "$required_file exists" true ""
    else
      check "$dirname" "$required_file exists" false "missing required file"
    fi
  done

  # --- Rule 6: domain naming convention ---
  if [[ "$domain" =~ ^[a-z][a-z0-9_]*[a-z0-9]$ ]]; then
    check "$dirname" "domain naming convention" true "$domain"
  else
    check "$dirname" "domain naming convention" false "$domain — must be lowercase, start with letter, only [a-z0-9_]"
  fi

  # --- Rule 7: icon files present (required for custom/brand display) ---
  # Built-in integrations get brand icons from HA CDN; custom ones need local files
  if [[ "$dir" != *"/homeassistant/components/"* ]]; then
    local has_icon=false
    if [[ -f "$dir/icon.png" ]] || [[ -f "$dir/logo.png" ]] || [[ -f "$dir/icons.json" ]]; then
      has_icon=true
    fi
    if [[ "$has_icon" == "true" ]]; then
      check "$dirname" "brand icon present (icon.png/logo.png/icons.json)" true ""
    else
      check "$dirname" "brand icon present (icon.png/logo.png/icons.json)" false \
        "missing — custom integrations don't get brand CDN icons; add icon.png (256x256) and logo.png (512x512)"
    fi
  fi
}

# ===== Main =====
if [[ $# -lt 1 ]]; then
  echo "Usage: bash validate.sh <path>"
  echo ""
  echo "  <path>  Integration directory or custom_components directory"
  echo ""
  echo "Examples:"
  echo "  bash validate.sh custom_components/my_integration"
  echo "  bash validate.sh /config/custom_components"
  exit 1
fi

TARGET="$1"

if [[ -d "$TARGET" ]]; then
  if [[ -f "$TARGET/manifest.json" ]]; then
    # Single integration
    validate_integration "$TARGET"
  else
    # Directory of integrations — scan subdirectories
    echo "Scanning: $TARGET"
    FOUND=0
    for subdir in "$TARGET"/*/; do
      if [[ -f "$subdir/manifest.json" ]]; then
        validate_integration "$(dirname "$subdir")/$(basename "$subdir")"
        ((FOUND++)) || true
      fi
    done
    if [[ $FOUND -eq 0 ]]; then
      echo "No integrations found (no subdirectories with manifest.json)"
    fi
  fi
else
  echo "Error: '$TARGET' is not a valid directory"
  exit 1
fi

# ===== Summary =====
echo ""
echo "═══════════════════════════════════════"
TOTAL=$((PASS + FAIL))
echo "Results: $PASS passed, $FAIL failed, $TOTAL total"
if [[ $FAIL -gt 0 ]]; then
  echo "❌ Validation FAILED"
  exit 1
else
  echo "✅ All checks passed"
fi
