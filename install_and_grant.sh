#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
adb install -r "$APK"
adb shell pm grant dev.dunno.emula android.permission.WRITE_SECURE_SETTINGS
adb shell dumpsys package dev.dunno.emula | grep -A4 -B2 WRITE_SECURE_SETTINGS || true
