#!/usr/bin/env bash
# UI smoke test, driven like a person with adb: launch, wait for the live map, open the product picker, switch to the globe,
# open the share dialog. Saves a screenshot per step into $1 (default: screenshots/) and fails on a crash or a blank screen.
# The app reads SPC's live data, so a slow or unreachable server shows up here as a missing map.
set -u
OUT=${1:-screenshots}
mkdir -p "$OUT"
APK=${APK:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}
fail=0

adb install -r "$APK" >/dev/null || { echo "install failed"; exit 1; }
adb shell pm grant org.spcrefs.app android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c
adb shell am start -n org.spcrefs.app/.MainActivity >/dev/null
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap()  { adb shell input tap "$(( W * $1 / 100 ))" "$(( H * $2 / 100 ))"; }

# distinct colours in a screenshot: a blank or error-only screen has very few
colours() { python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB").resize((200, 400))
print(len(set(im.getdata())))
PY
}

echo "waiting for the map (up to 90 s)"
ok=0
for i in $(seq 1 30); do
  sleep 3
  shot map
  if [ "$(colours "$OUT/map.png")" -gt 400 ]; then ok=1; break; fi
done
[ $ok = 1 ] || { echo "FAIL: the map never appeared"; fail=1; }

tap 40 12; sleep 3; shot product-picker       # product chip
adb shell input keyevent 4; sleep 1
tap 62 6; sleep 2; shot sector-menu             # sector menu
tap 62 14; sleep 8; shot globe                  # first item: Globe
tap 78 6; sleep 2; shot share-dialog            # Share
adb shell input keyevent 4; sleep 1
tap 92 6; sleep 2; shot more-menu

if adb logcat -d -s AndroidRuntime:E | grep -q FATAL; then
  echo "FAIL: the app crashed"; adb logcat -d -s AndroidRuntime:E | head -30; fail=1
fi
for f in globe share-dialog; do
  [ "$(colours "$OUT/$f.png")" -gt 100 ] || { echo "FAIL: $f looks blank"; fail=1; }
done
exit $fail
