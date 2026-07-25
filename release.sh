#!/usr/bin/env bash
#
# 触发 Build Release 发布流水线
#
# 用法:
#   ./release.sh            # 版本号自动生成 (yyyyMMddHHmmss)
#   ./release.sh preview    # 指定版本号 (preview 走 S3 预览通道)

set -euo pipefail

VERSION="${1:-$(date +%Y%m%d%H%M%S)}"
REPO="$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "WARN: 存在未提交的改动，发布内容以远端 master 为准" >&2
fi

if [ -n "$(git log origin/master..HEAD --oneline 2>/dev/null)" ]; then
    echo "ERROR: 本地有未推送的提交，请先 git push" >&2
    exit 1
fi

echo "触发 Build Release, repo=$REPO version=$VERSION"
gh workflow run release.yml -R "$REPO" -f version="$VERSION"

sleep 3
gh run list -R "$REPO" --workflow=release.yml --limit 1

echo "版本号：$VERSION"
