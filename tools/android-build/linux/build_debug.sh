#!/bin/bash
# 构建 debug APK（不混淆），产物：app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT"
LOG="$AB_BASE/logs/build_debug_$(date +%Y%m%d-%H%M%S).log"
echo "log -> $LOG"
gradle --console=plain --no-daemon :app:assembleDebug 2>&1 | tee "$LOG"
APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "!! 未产出 APK"; exit 1; }
ls -la "$APK"; sha256sum "$APK"
