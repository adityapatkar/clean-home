#!/bin/bash
# Builds Clean Home and sets it up on a Fire TV over ADB:
#   install, pre-compile, grant secure-settings, enable the Home button service,
#   and make it the screensaver.
# The TV comes from ANDROID_SERIAL or TV_ADDRESS in config.properties.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${ANDROID_SERIAL:-}" ] && [ -f config.properties ]; then
    ANDROID_SERIAL=$(grep -E '^TV_ADDRESS=' config.properties | cut -d= -f2-)
fi
export ANDROID_SERIAL="${ANDROID_SERIAL:?set TV_ADDRESS in config.properties or ANDROID_SERIAL}"

PKG=com.aditya.cleanhome
SERVICE=$PKG/$PKG.HomeInterceptService

./build.sh
adb connect "$ANDROID_SERIAL" >/dev/null || true
adb wait-for-device
# Earlier builds used the package com.aditya.tvhome; remove it so only one launcher runs.
adb uninstall com.aditya.tvhome >/dev/null 2>&1 || true
adb install -r build/cleanhome.apk

# Ahead-of-time compile so the first launch is as fast as later ones.
adb shell cmd package compile -m speed -f $PKG >/dev/null
# Lets the launcher keep its own Home service enabled and own the screensaver choice.
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS

# Enable the Home button service (re-enabling forces a rebind after reinstall).
adb shell settings put secure enabled_accessibility_services null
sleep 1
adb shell settings put secure enabled_accessibility_services $SERVICE
adb shell settings put secure accessibility_enabled 1

adb shell settings put secure screensaver_components $PKG/$PKG.ClockDream
adb shell am start -n $PKG/.HomeActivity >/dev/null

cat <<'MSG'

Installed. One manual step for an instant Home button:
  In Amazon's home: More Apps (the grid icon) > highlight "Clean Home" >
  Menu (three lines) > Move to front.
MSG
