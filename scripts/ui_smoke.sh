#!/usr/bin/env bash
# UI smoke test, driven like a person with adb: launch, wait for the live map, open the product picker, switch to the globe,
# open the share dialog and menus. Saves a screenshot per step into $1 (default: screenshots/) and fails on a crash, an ANR or
# a screen whose middle part is empty. The app reads SPC's live data, so a slow or unreachable server shows up as a missing map.
set -u
OUT=${1:-screenshots}
mkdir -p "$OUT"
APK=${APK:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}
fail=0

adb install -r "$APK" >/dev/null || { echo "install failed"; exit 1; }
adb shell settings put global hide_error_dialogs 1       # a slow CI emulator can trip "System UI isn't responding"
adb shell am force-stop org.spcrefs.app
adb logcat -c
adb shell am start -n org.spcrefs.app/.MainActivity >/dev/null
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap()  { adb shell input tap "$(( W * $1 / 100 ))" "$(( H * $2 / 100 ))"; }

# distinct colours in the middle of the screen (rows 30-65 %): the map, or the globe, has hundreds; a spinner or empty
# screen has a handful
middle() { python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
crop = im.crop((0, int(h * 0.30), w, int(h * 0.65))).resize((270, 210))
print(len(set(crop.getdata())))
PY
}

wait_for_picture() {   # $1 = name, $2 = seconds to wait
  local name=$1 deadline=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    sleep 4
    shot "$name"
    if [ "$(middle "$OUT/$name.png")" -gt 150 ]; then return 0; fi
  done
  return 1
}

echo "waiting for the map (up to 6 min)"
wait_for_picture map 360 || { echo "FAIL: the map never appeared"; fail=1; }

tap 40 7;  sleep 4; shot product-picker       # product chip
adb shell input keyevent 4; sleep 2
tap 77 11; sleep 3; shot sector-menu            # sector menu
tap 77 17                                       # first item: Globe
wait_for_picture globe 180 || { echo "FAIL: the globe never appeared"; fail=1; }
tap 92 11; sleep 3; shot share-dialog           # Share
adb shell input keyevent 4; sleep 2
tap 91 7;  sleep 3; shot more-menu

if adb logcat -d -s AndroidRuntime:E | grep -q FATAL; then
  echo "FAIL: the app crashed"; adb logcat -d -s AndroidRuntime:E | head -30; fail=1
fi
if adb logcat -d | grep -q "ANR in org.spcrefs.app"; then
  echo "FAIL: the app was not responding"; adb logcat -d | grep "ANR in org.spcrefs.app" | head -3; fail=1
fi
exit $fail
