#!/usr/bin/env bash
# Emulator helpers for driving the app like a person. Usage: source scripts/emu.sh
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
APK=${APK:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}
emu_install() { adb install -r "$APK" | tail -1; }
emu_launch()  { adb logcat -c; adb shell am force-stop org.spcrefs.app; adb shell am start -n org.spcrefs.app/.MainActivity >/dev/null; }
emu_tap()     { adb shell input tap "$1" "$2"; }
emu_shot()    { adb exec-out screencap -p > "$1"; }
emu_crashes() { adb logcat -d -s AndroidRuntime:E | head -20; }
