#!/usr/bin/env bash
# UI smoke test, driven like a person with adb: launch, wait for the live map, open the product picker, switch to the globe,
# open the share dialog and menus, then the MRMS page (composite reflectivity, rotation track, 24 h rainfall, a meso sector). Saves a screenshot per step into $1 (default: screenshots/) and fails on a crash, an ANR or
# a screen whose middle part is empty. The app reads SPC's live data, so a slow or unreachable server shows up as a missing map.
set -u
OUT=${1:-screenshots}
mkdir -p "$OUT"
source "$(dirname "$0")/emu.sh"   # adb on PATH + emu_tap_text
APK=${APK:-androidApp/build/outputs/apk/debug/simplemodelview-debug.apk}
fail=0
PKG=io.github.intelati26.simplemodelview

adb install -r "$APK" >/dev/null || { echo "install failed"; exit 1; }
adb shell settings put global hide_error_dialogs 1       # a slow CI emulator can trip "System UI isn't responding"
adb shell pm clear io.github.intelati26.simplemodelview >/dev/null 2>&1   # a fresh install state
adb logcat -c
adb shell am start -n io.github.intelati26.simplemodelview/.MainActivity >/dev/null
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
    emu_tap_text "Retry" 2>/dev/null && echo "  tapped Retry (the first load failed)"   # a person would
  done
  return 1
}

# a fresh install asks for an (optional) contact email at launch; a user would tap "Not now"
# (a slow emulator can take a minute to draw it: poll rather than wait a fixed time)
found=0
for i in $(seq 1 24); do
  sleep 5
  if emu_tap_text "Not now" 2>/dev/null; then found=1; break; fi
done
shot email-dialog-dismissed
[ $found = 1 ] || { echo "FAIL: no email dialog on a fresh launch"; fail=1; }

echo "waiting for the map (up to 6 min)"
wait_for_picture map 360 || { echo "FAIL: the map never appeared"; echo "network check from the device:"; adb shell ping -c 2 -W 3 www.spc.noaa.gov 2>&1 | tail -3; fail=1; }

tap 40 7;  sleep 4; shot product-picker       # product chip
adb shell input keyevent 4; sleep 2
tap 77 11; sleep 3; shot sector-menu            # sector menu
tap 77 17                                       # first item: Globe
wait_for_picture globe 180 || { echo "FAIL: the globe never appeared"; fail=1; }
tap 92 11; sleep 3; shot share-dialog           # Share
adb shell input keyevent 4; sleep 2
tap 91 7;  sleep 3; shot more-menu

# the MRMS page (live radar from NOAA's AWS bucket): open it from the menu, then the three first products
adb shell input keyevent 4; sleep 2     # close the menu if it is still open
emu_tap_text "MRMS radar" 2>/dev/null || { tap 91 7; sleep 2; emu_tap_text "MRMS radar"; }
wait_for_picture mrms-reflectivity 240 || { echo "FAIL: the MRMS reflectivity map never appeared"; fail=1; }
emu_tap_text "Composite Reflectivity"; sleep 2; emu_tap_text "Rotation Track, 1 h (0-2 km)"
wait_for_picture mrms-rotation 120 || { echo "FAIL: the MRMS rotation map never appeared"; fail=1; }
emu_tap_text "Rotation Track, 1 h (0-2 km)"; sleep 2; emu_tap_text "24 h Rainfall (multi-sensor, best)"
wait_for_picture mrms-rainfall 120 || { echo "FAIL: the MRMS rainfall map never appeared"; fail=1; }
emu_tap_text "CONUS ▾"; sleep 2; emu_tap_text "South Central"
wait_for_picture mrms-meso 120 || { echo "FAIL: the MRMS meso-sector map never appeared"; fail=1; }
tap 50 48; sleep 2; shot mrms-readout
emu_tap_text "Composite Reflectivity"; sleep 3; shot mrms-product-picker; adb shell input keyevent 4; sleep 2
emu_tap_text "Share"; sleep 3; shot mrms-share-dialog; adb shell input keyevent 4; sleep 2
emu_tap_text "South Central ▾"; sleep 2; emu_tap_text "Globe"
wait_for_picture mrms-globe 120 || { echo "FAIL: the MRMS globe never appeared"; fail=1; }

# only crashes of this app count (the emulator image's own apps, e.g. Gmail, may crash too)
if adb logcat -d -s AndroidRuntime:E | grep -A2 "FATAL EXCEPTION" | grep -q "Process: $PKG"; then
  echo "FAIL: the app crashed"; adb logcat -d -s AndroidRuntime:E | grep -B1 -A12 "Process: $PKG" | head -30; fail=1
fi
if adb logcat -d | grep -q "ANR in io.github.intelati26.simplemodelview"; then
  echo "FAIL: the app was not responding"; adb logcat -d | grep "ANR in io.github.intelati26.simplemodelview" | head -3; fail=1
fi
exit $fail
