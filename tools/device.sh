#!/usr/bin/env bash
# Device loop for MioNav. Everything the agent needs to see the app without asking the user.
#
#   tools/device.sh connect            # pair over Wi-Fi (prints the steps), or `adb tcpip 5555`
#   tools/device.sh install [APK]      # default: android/app/build/outputs/apk/debug/app-debug.apk
#   tools/device.sh launch
#   tools/device.sh share "<text>"     # simulates a share sheet hit, no Amap needed
#   tools/device.sh paste "<text>"     # puts text on the clipboard for the paste button
#   tools/device.sh shot [OUT.png]     # screenshot to a file (default /tmp/mionav-shot.png)
#   tools/device.sh dump                # UI hierarchy XML
#   tools/device.sh log [extra adb logcat args…]
#   tools/device.sh save                # logcat snapshot to /tmp/mionav-log.txt
#   tools/device.sh query [MIME]       # which activities resolve for SEND
set -euo pipefail

ADB="${ADB:-/home/mio/android-sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=io.mionav.app
DEFAULT_APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

need_device() {
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "no device: run 'tools/device.sh connect' once, or plug in USB with debugging on" >&2
    exit 1
  fi
}

cmd="${1:-help}"
shift || true

case "$cmd" in
  connect)
    cat <<'TXT'
无线调试（Android 11+，一次性）：
  1. 手机：设置 → 关于手机 → 连点版本号 7 次，打开开发者选项
  2. 开发者选项 → 无线调试 → 打开，点「使用配对码配对设备」
  3. 屏幕上会给出 IP:端口 和 6 位配对码，然后在电脑上：
       adb pair <配对IP:端口>      # 输入配对码
       adb connect <手机IP>:<无线调试端口>
USB（更快）：
  adb tcpip 5555 && adb connect <手机IP>:5555
TXT
    "$ADB" devices -l
    ;;
  install)
    need_device
    "$ADB" install -r "${1:-$DEFAULT_APK}"
    ;;
  launch)
    need_device
    "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
    echo "launched"
    ;;
  share)
    need_device
    text="${1:?share needs the text to send}"
    "$ADB" shell am start -a android.intent.action.SEND -t text/plain \
      --es android.intent.extra.TEXT "$text" -n "$PKG/.ConfirmActivity" >/dev/null
    echo "sent: $text"
    ;;
  paste)
    need_device
    text="${1:?paste needs the text}"
    # Nothing to install: the shell can put text straight on the clipboard.
    "$ADB" shell am broadcast -a clipper.set -e text "$text" >/dev/null 2>&1 ||
      echo "clipboard broadcast refused (needs the Clipper app); copy it by hand instead" >&2
    ;;
  shot)
    need_device
    out="${1:-/tmp/mionav-shot.png}"
    "$ADB" exec-out screencap -p > "$out"
    echo "$out"
    ;;
  dump)
    need_device
    "$ADB" shell uiautomator dump /sdcard/mionav-ui.xml >/dev/null
    "$ADB" shell cat /sdcard/mionav-ui.xml
    ;;
  log)
    need_device
    "$ADB" logcat -v time -s MioNav:V "${@:-}"
    ;;
  save)
    need_device
    out=/tmp/mionav-log.txt
    "$ADB" logcat -d -v time -s MioNav:V > "$out"
    echo "$out"
    ;;
  query)
    need_device
    mime="${1:-text/plain}"
    "$ADB" shell cmd package query-activities -a android.intent.action.SEND -t "$mime"
    ;;
  *)
    sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
