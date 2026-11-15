# FlipFocus
Offline Android 16 (API 36) focus app: face-down sensing, vibration loop, call bypass, schedules, app blocking, Room analytics. No accounts, no network, no ads.

## Build the APK (GitHub)
1. Create a repo, push this folder to `main`.
2. Actions tab → "Build APK" (runs on push, or Run workflow).
3. Download artifact `FlipFocus-apk` → install `app-release.apk` (debug-signed, sideloadable).

Or open in Android Studio (JDK 17) and Run.

## First-run setup on device
Allow notifications and phone state when asked; Schedules tab → allow exact alarms; Schedules tab → enable the accessibility service.
Fonts: Geist (SIL OFL 1.1). minSdk 33, targetSdk 36.
