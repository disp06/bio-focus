#!/usr/bin/env bash
# Runs inside the Wear OS emulator job. Drives the debug APK through DemoMode and saves raw PNGs.
set -x
PKG=com.biofocus.wear
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=shots
mkdir -p "$OUT"

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; ls -la "$OUT/$1.png"; }
demo() { adb shell am start -n "$PKG/.ui.MainActivity" --es demo "$1"; }
home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 25
adb shell wm size
adb shell wm density
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
shot 00_home 3

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.BODY_SENSORS

# Main screen with tracking on
demo track
shot 01_main 4
adb shell input swipe 227 400 227 60 400
shot 02_main_settings 2
adb shell input swipe 227 400 227 60 400
shot 03_main_bottom 2

# Focus session screens (seeded with demo data)
demo focus_calm
shot 04_focus_calm 3
demo focus_stress
shot 05_focus_stress 3
demo focus_calibrating
shot 06_focus_calibrating 3
adb shell input keyevent KEYCODE_BACK

# Breathing guide: catch it mid-inhale
demo breathing
shot 07_breathing_in 1.8
sleep 3
shot 08_breathing_out 1.5
adb shell input keyevent KEYCODE_BACK

# Sensor diagnostics (emulator sensors, not the TicWatch's)
demo sensors
shot 09_sensors 3
adb shell input keyevent KEYCODE_BACK
home

# Notifications: peek on the watch face, then the expanded card in the stream
for kind in stress fatigue sedentary; do
  demo "alert_$kind"
  shot "10_${kind}_peek" 2
  adb shell input swipe 227 430 227 80 300
  shot "11_${kind}_stream" 2
  adb shell input tap 227 227
  shot "12_${kind}_expanded" 2
  home
  home
done

adb shell dumpsys notification | grep -A3 com.biofocus | head -40
adb logcat -d -s BioFocus AndroidRuntime:E | tail -60
ls -la "$OUT"
