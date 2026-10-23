#!/bin/sh
# 启用仓库自带的 git 钩子（每个 clone 只需跑一次）
set -e
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
echo "✓ 已启用 .githooks（提交前会检查数据/凭据/大文件）"
