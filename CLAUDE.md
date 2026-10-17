# Bio-Focus — notes for Claude Code

Standalone Wear OS app, package `com.biofocus.wear`. Original product brief: `brief.md`.

## Hard platform constraints
- Device: TicWatch Pro 3 GPS, Wear OS 2.x = **Android 9, API 28**. minSdk 28, targetSdk 28 (on purpose; sideload only).
- **No Jetpack Compose.** XML Views + `androidx.wear:wear:1.2.0` (BoxInsetLayout, WearableRecyclerView, AmbientModeSupport).
  Activities extend `FragmentActivity` (AmbientModeSupport needs it). Theme parent: `@android:style/Theme.DeviceDefault`.
- Don't use APIs above 28 without a version check (no `foregroundServiceType`, no POST_NOTIFICATIONS, no Health Services).
- Only `BODY_SENSORS` is a runtime permission. `FOREGROUND_SERVICE` is a normal permission on 28.
- HRV: real RMSSD only if `Sensor.TYPE_HEART_BEAT` exists; otherwise HR-proxy mode. SpO2: vendor sensor probe only.
  The FSTN display has no public API. Never claim otherwise in UI text.
- Notifications are local only (`setLocalOnly(true)`), posted via the platform `NotificationManager`.
- Manual vibration must respect DND (`Alerts.interruptionsAllowed`).

## Architecture
- `BioTrackingService` (sticky foreground) owns everything. Its actions are in the companion object; call `BioTrackingService.send(ctx, ACTION)`.
- Sedentary: no polling. `SedentaryRule.dueAt()` → one `setExactAndAllowWhileIdle` alarm → `NotificationReceiver` → service flushes the step FIFO → `shouldAlert()`.
- Focus session: `HeartMonitor` feeds `SessionAnalyzer` (pure Kotlin), evaluated every 10 s, under a session-bounded wake lock.
- UI reads `LiveState` (in-process) plus `Prefs`.
- Keep detection logic in `utils/` free of Android imports so it stays unit-testable.

## Commands
- Build: `gradlew.bat assembleDebug`
- Tests: `gradlew.bat test`
- Install: `adb connect <ip>:5555` then `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Logs: `adb logcat -s BioFocus`
