# Bio-Focus — Wear OS 2 (API 28) productivity bio-assistant

Standalone watch app for the **TicWatch Pro 3 GPS** on Wear OS 2.x (Android 9 / API 28).
Kotlin, classic XML Views, androidx.wear. No Compose, no phone app.

## What it does

| Trigger | When it runs | Logic | Intervention |
|---|---|---|---|
| **Sedentary** | Always while tracking is on, inside *active hours* | Hardware step counter (batched, CPU sleeps). One exact alarm is set for the moment "45 min without a step" would be reached. If you walk, the alarm moves. | *"You've been sitting too long. Stand up and stretch!"* with Snooze 30 min / Done. Auto-dismissed once you walk. |
| **Stress** | During a focus session | 3-min personal baseline, then every 10 s: RMSSD drop ≥ 40 % + HR rise (beat-to-beat mode) **or** HR ≥ baseline + max(12 bpm, 2.5 SD) (HR mode). Must hold 45 s, suppressed while/after walking, 15-min cooldown. | Double vibration + *"Stress detected. Breathe with me for 1 minute."* → 1-min guided breathing (4 s in / 6 s out). |
| **Fatigue** | During a focus session | 2-min mean HR ≤ baseline − max(8 bpm, 2 SD) while very still (no steps for 5 min, quiet wrist), held 3 min. Or vendor SpO2 < 92 % if the watch exposes one. 20-min cooldown. | Full-strength long vibration + *"Focus dropping. Stand up or drink water!"* |

The focus screen shows a countdown, live HR and a 0–100 stress index (70 = alert threshold). It
stays visible in ambient mode (white-on-black, minute updates, burn-in shift).

## Where the brief meets reality (read this)

These are platform facts, not shortcuts. The code handles each one gracefully.

1. **HRV is not a public API on Wear OS 2.** `TYPE_HEART_RATE` gives BPM only. Real HRV needs beat
   timestamps from `Sensor.TYPE_HEART_BEAT`, which most watches (likely including yours) don't expose.
   - If it exists, the app computes real **RMSSD** with artifact rejection → mode "HRV (beat-to-beat)".
   - If not, it falls back to **HR elevation at rest** vs. your session baseline → mode "HR-based estimate".
     That's a reasonable stress proxy, but it is not HRV. The screen says which mode is active.
2. **SpO2 has no public API.** Mobvoi measures it in its own app. The app looks for a vendor sensor
   whose name contains "spo2"/"oxygen" and uses it if readable. Expect "SpO2 ✗" on the Sensors screen; fatigue then
   relies on HR drop + stillness.
3. **The FSTN (second display) is owned by Mobvoi's firmware.** There is no public API to draw on it.
   The app implements standard ambient mode (`AmbientModeSupport`), which renders on the AMOLED in its
   low-power state. If the watch is set to switch to the FSTN layer when idle, the system replaces
   any app's ambient screen.
4. **`FOREGROUND_SERVICE` is not a runtime permission** on API 28. It's a normal permission, granted at
   install. Only `BODY_SENSORS` is requested at runtime.
5. **Battery:** continuous optical HR is the main cost, so it only runs during focus sessions (5–240 min).
   A bounded partial wake lock keeps the 10-s evaluation real-time with the screen off. Outside
   sessions the app only uses the batched step counter and one alarm, which costs next to nothing.

## Build and install

**Requirements:** Android Studio (any 2024+ version) or JDK 17 + Android SDK 34.

**Android Studio:** *Open* this folder, let Gradle sync, pick the watch, then Run.

**Command line (Windows):**

```bat
gradlew.bat assembleDebug
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```

The watch IP is shown under *Settings → Developer options → Debug over Wi-Fi* on the watch.

**Unit tests (no watch needed):** `gradlew.bat test` runs the detection maths:
RMSSD, artifact rejection, calibration, stress/fatigue firing and cooldowns, and active-hours edge cases.

**Logs:** `adb logcat -s BioFocus`

## First run on the watch

1. Open **Bio-Focus** and allow *Body sensors*.
2. Tap **Sensors on this watch** to see whether beat-to-beat (real HRV) or SpO2 exist on your unit.
3. Tap **Test the 3 alerts** to feel each vibration pattern (they arrive 5 s apart).
4. Tap **Start tracking** to turn on the sedentary alerts. Set *active hours* with − / + (default 09:00–19:00).
5. Tap **Start focus** to begin a session. Sit still for the first 3 minutes while it learns your baseline.

Tracking restarts automatically after a reboot or reinstall.

## Tuning

- Stress and fatigue thresholds live in `DetectorConfig` (`utils/Detectors.kt`). Each one is documented.
- The sedentary window lives in `SedentaryRule.WINDOW_MS` (45 min).
- Watch the `BioFocus` logcat during a few real sessions. Every alert logs HR, baseline, RMSSD and mode.

## Project layout

```
app/src/main/java/com/biofocus/wear/
├── service/
│   ├── BioTrackingService.kt   sticky foreground service: lifecycle, session, sedentary scheduling
│   ├── StepMonitor.kt          batched TYPE_STEP_COUNTER + flush-on-alarm
│   ├── HeartMonitor.kt         HR / HEART_BEAT / accelerometer / vendor SpO2 → SessionAnalyzer
│   ├── NotificationReceiver.kt sedentary alarm + notification actions (snooze, dismiss)
│   ├── BootReceiver.kt         restart after reboot / app update
│   ├── Alerts.kt               channels, local notifications, vibration patterns (DND-aware)
│   ├── SensorCatalog.kt        what this watch exposes
│   └── LiveState.kt            service → UI state
├── ui/
│   ├── MainActivity.kt         setup, permissions, session length, active hours
│   ├── FocusAmbientActivity.kt live session screen with ambient mode
│   ├── BreathingActivity.kt    1-minute guided breathing
│   └── SensorsActivity.kt      sensor diagnostics (WearableRecyclerView)
└── utils/
    ├── BioMath.kt              RMSSD, stats, motion, sensor clock conversion
    ├── Detectors.kt            SessionAnalyzer: calibration, stress & fatigue rules
    ├── SedentaryRule.kt        45-min rule, active hours (incl. overnight)
    └── Prefs.kt                persisted settings + state
```

## Status

The code was written against the API 28 / androidx.wear 1.2.0 APIs, and the detection logic was
simulated against the unit-test scenarios. It has **not yet been compiled or run on a watch**: the
build environment it was written in could not reach Google's Maven. The first Gradle sync may need a small fix.
