#!/usr/bin/env bash
#
# 构建 -> 安装 -> 重启快手极速版 -> 流式抓取模块日志
#
# 用法: scripts/run-test.sh [采集秒数]
#
set -uo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

PKG=com.kuaishou.nebula
GRADLE="$(command -v gradle || echo /opt/homebrew/bin/gradle)"
SECONDS_TO_CAPTURE="${1:-25}"
LOG=/tmp/ks-immerse.log

echo "==> 构建"
"$GRADLE" :app:assembleRelease --console=plain -q || { echo "构建失败"; exit 1; }

APK=app/build/outputs/apk/release/app-release.apk
echo "==> 安装 $(basename "$APK")"
adb install -r "$APK" | tail -2

echo "==> 开始采集日志（${SECONDS_TO_CAPTURE}s）"
rm -f "$LOG"
adb logcat -c
adb logcat -v time KSImmerse:V VectorModuleManager:V '*:S' > "$LOG" 2>&1 &
LC_PID=$!
sleep 1

adb shell am force-stop "$PKG"
sleep 2
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

sleep "$SECONDS_TO_CAPTURE"
kill "$LC_PID" 2>/dev/null
wait "$LC_PID" 2>/dev/null

echo "==> 模块日志"
grep -E 'KSImmerse' "$LOG" | grep -v 'com\.yytest\.ksimmerse:' || echo "(无)"
echo
echo "==> 完整日志已保存到 $LOG"
