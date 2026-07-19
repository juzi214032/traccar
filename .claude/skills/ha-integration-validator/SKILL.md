---
name: ha-integration-validator
description: Validate Home Assistant custom integration structure. Check manifest.json validity, domain/directory matching, version field, and required files.
---

# HA Integration Validator

Validate Home Assistant custom integrations against HA loading rules.

## Usage

Run the validation script against a single integration or a directory of integrations:

```bash
bash .claude/skills/ha-integration-validator/validate.sh <path>
```

## Check Rules

| # | Rule | Severity |
|---|---|---|
| 1 | `manifest.json` is valid JSON | Error |
| 2 | Directory name matches `domain` in manifest.json | Error |
| 3 | `version` field present in manifest.json | Error (custom_components) / Warn (built-in) |
| 4 | `config_flow.py` exists if manifest declares `config_flow: true` | Error |
| 5 | `__init__.py` and `const.py` exist | Error |
| 6 | Domain follows naming convention `[a-z][a-z0-9_]*` | Error |

## Background

HA imposes specific requirements for loading custom integrations:

- **dir name = domain**: `custom_components/<dir>` must match the `domain` field in `manifest.json`. Mismatch causes the integration to be silently skipped.
- **version field**: Since HA 2021.2, custom integrations MUST declare `version` in manifest.json. Without it, HA blocks loading with: `The custom integration '<name>' does not have a version key in the manifest file and was blocked from loading.`
- **config_flow**: Integrations with `"config_flow": true` must have `config_flow.py`. Without it, the integration won't appear in the "Add Integration" UI.
- **Built-in vs custom**: Built-in integrations under `homeassistant/components/` don't need `version` (tied to HA core). Custom integrations under `custom_components/` require it.

## Example Output

```
━━━ Validating: juzi_traccar_server ━━━
✓ [juzi_traccar_server] manifest.json valid JSON
✓ [juzi_traccar_server] dir name matches domain (juzi_traccar_server)
✓ [juzi_traccar_server] version field present (1.0.0)
✓ [juzi_traccar_server] config_flow.py exists
✓ [juzi_traccar_server] __init__.py exists
✓ [juzi_traccar_server] const.py exists
✓ [juzi_traccar_server] domain naming convention

═══════════════════════════════════════
✅ All checks passed
```

## Adding New Rules

Edit `validate.sh` and add a new `rule` + `check` block in the `validate_integration()` function. Keep the existing rule numbering and add new rules at the end.
