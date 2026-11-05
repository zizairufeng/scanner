#!/bin/bash
# 构建 release APK（R8 混淆 + 资源压缩）并手工签名，产出可安装成品。
#
# AGP 在没有 signingConfig 时只会产出 app-release-unsigned.apk（不可安装），
# 所以这里走「assembleRelease -> zipalign -> apksigner」链路，
# 避免把 keystore 口令写进 build.gradle.kts（那会把密钥信息留在交付代码里）。
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT"

BT="$ANDROID_HOME/build-tools/35.0.0"
KS="$AB_BASE/release.keystore"
KS_PASS="scanstock"
KS_ALIAS="scanstock"
OUT="$AB_BASE/out"
mkdir -p "$OUT"
TS="$(date +%Y%m%d-%H%M%S)"

echo "== [1/4] assembleRelease（R8）"
gradle --console=plain --no-daemon :app:assembleRelease \
  -x lintVitalRelease -x lintVitalAnalyzeRelease \
  2>&1 | tee "$AB_BASE/logs/build_release_$TS.log"

SRC="$PROJECT/app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$SRC" ] || SRC="$PROJECT/app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "!! 未找到 release 产物"; exit 1; }

ALIGNED="$OUT/app-release-aligned.apk"
FINAL="$OUT/ScanStock-release.apk"

echo "== [2/4] zipalign -p -f 4"
"$BT/zipalign" -p -f 4 "$SRC" "$ALIGNED"

echo "== [3/4] apksigner sign（v1+v2+v3）"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$FINAL" "$ALIGNED"

echo "== [4/4] apksigner verify"
"$BT/apksigner" verify --verbose --print-certs "$FINAL"
"$BT/zipalign" -c -v 4 "$FINAL" | tail -2
ls -la "$FINAL"; sha256sum "$FINAL"
echo "mapping: $PROJECT/app/build/outputs/mapping/release/mapping.txt"
