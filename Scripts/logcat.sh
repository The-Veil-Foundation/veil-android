#!/usr/bin/env bash
# Живой лог приложения: логи Kotlin/Compose + сообщения ядра Xray.
#
#   ./Scripts/logcat.sh            # фильтр по процессу приложения
#   ./Scripts/logcat.sh all        # вообще всё (шумно)
#   ./Scripts/logcat.sh crash      # только падения
#   ./Scripts/logcat.sh core       # только строки Go/Xray
set -uo pipefail
ADB="$HOME/Android/platform-tools/adb"
PKG="dev.local.veil"

case "${1:-app}" in
  all)
    "$ADB" logcat -v color
    ;;
  crash)
    "$ADB" logcat -v color -b crash '*:E'
    ;;
  core)
    "$ADB" logcat -v color -s GoLog:* xray:* VeilXray:*
    ;;
  *)
    PID=$("$ADB" shell pidof -s "$PKG" | tr -d '\r')
    if [ -z "$PID" ]; then
      echo "Приложение не запущено. Ставлю и открываю…"
      APK="$(dirname "$0")/../composeApp/build/outputs/apk/debug/composeApp-debug.apk"
      "$ADB" install -r "$APK" >/dev/null 2>&1
      "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      sleep 2
      PID=$("$ADB" shell pidof -s "$PKG" | tr -d '\r')
    fi
    if [ -z "$PID" ]; then
      echo "Не удалось запустить $PKG — смотрите ./Scripts/logcat.sh all"
      exit 1
    fi
    echo "PID $PID — Ctrl+C чтобы выйти"
    "$ADB" logcat -v color --pid="$PID"
    ;;
esac
