#!/usr/bin/env bash
# Emulator helpers for driving the app like a person. Usage: source scripts/emu.sh
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
APK=${APK:-androidApp/build/outputs/apk/debug/simplemodelview-debug.apk}
emu_install() { adb install -r "$APK" | tail -1; }
emu_launch()  { adb logcat -c; adb shell am force-stop io.github.intelati26.simplemodelview; adb shell am start -n io.github.intelati26.simplemodelview/.MainActivity >/dev/null; }
emu_tap()     { adb shell input tap "$1" "$2"; }
emu_shot()    { adb exec-out screencap -p > "$1"; }
emu_crashes() { adb logcat -d -s AndroidRuntime:E | head -20; }

# Taps the on-screen element with this text (or content description), found through uiautomator: robust against layout changes.
emu_tap_text() {
  local xml; xml=$(mktemp)
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$xml"
  python3 - "$1" "$xml" <<'PY'
import re, subprocess, sys
want, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8", errors="replace").read()
for m in re.finditer(r'<node [^>]*?(?:text|content-desc)="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1) == want:
        x = (int(m.group(2)) + int(m.group(4))) // 2
        y = (int(m.group(3)) + int(m.group(5))) // 2
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        sys.exit(0)
print("no element with text:", want, file=sys.stderr)
sys.exit(1)
PY
  local rc=$?; rm -f "$xml"; return $rc
}
