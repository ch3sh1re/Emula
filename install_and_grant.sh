#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE_ID="${2:-dev.dunno.emula.debug}"
adb install -r "$APK"
adb shell pm grant "$PACKAGE_ID" android.permission.WRITE_SECURE_SETTINGS
adb shell dumpsys package "$PACKAGE_ID" | grep -A4 -B2 WRITE_SECURE_SETTINGS || true
