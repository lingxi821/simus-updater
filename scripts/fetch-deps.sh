#!/bin/bash
# 下载体积较大的构建期依赖（r8/D8，dex 生成用）到 updater/libs/
set -e
P="$(cd "$(dirname "$0")/.." && pwd)"
V=8.3.37
OUT="$P/updater/libs/r8-$V.jar"
if [ -f "$OUT" ]; then echo "已存在: $OUT"; exit 0; fi
URL="https://maven.google.com/com/android/tools/r8/$V/r8-$V.jar"
echo "下载 $URL"
curl -fL "$URL" -o "$OUT"
ls -la "$OUT"
