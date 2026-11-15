# FlipFocus
Offline Android 16 (API 36) focus app: face-down sensing, immediate vibration monitoring, call bypass, alarm-clock schedules, utility-app vibration pauses, smart breaks, templates, interruption tags and Room analytics. No accounts, no network, no ads.

## Build the APK (GitHub)
1. Create a repo, push this folder to `main`.
2. Actions tab → "Build APK" (runs on push, or Run workflow).
3. Download artifact `FlipFocus-apk` → install `app-release.apk` (debug-signed, sideloadable).

Or open in Android Studio (JDK 17) and Run.

## First-run setup on device
Allow notifications and phone state when asked. In Schedules, allow exact alarms, grant usage access (needed to see which app is open), and use the one-tap battery-optimization exemption. In Focus, select up to **10 utility apps**.

## Behavior
- During a session, opening any app other than FlipFocus or a utility app (or leaving to the home screen) vibrates once after about 2 s, at most once every 3 s. Nothing vibrates for this while the screen is off or the phone is locked.
- Monitoring starts immediately; no initial face-down flip is needed.
- Schedules use Android alarm-clock triggers, so a session can start and vibrate while FlipFocus is closed and the phone is locked.
- Schedule times use a 12-hour clock dial. Overlapping enabled schedules are blocked, and the currently running schedule cannot be edited.
- Any session left face-up for over 5 seconds is marked failed and excluded from total time. Pressing End session itself does not create a failure.
- A successful session starts an automatic 5-minute break with vibration/failure detection paused; it can be skipped from the app or notification.
- After a session ends, each pickup can be tagged Call / Utility / Urge / Other, or skipped; tag patterns appear in Analytics.
- Focus includes built-in quick-start templates and custom saved templates. No widgets or tiles are used.
- Analytics includes total time, current streak, failed count, a 7×4 best-focus-time heatmap, and interruption reasons.
- Subtle dark-theme animations cover tab crossfades, progress/status color, ring glow, utility cards, streak dots and heatmap cells.

Fonts: Geist (SIL OFL 1.1). minSdk 33, targetSdk 36.
