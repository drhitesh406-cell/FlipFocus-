# FlipFocus update notes

## Choices asked before editing
You selected:
- **Smart Break:** automatic 5-minute break after a successful session, with Skip.
- **Interruption tags:** post-session, one tag per pickup.
- **Overlapping schedules:** block save and name the conflicting schedule.
- **Streak / heatmap:** non-failed day streak; 7 days × 4 dayparts.
- **Templates / animations:** built-in + custom templates; subtle dark-theme animations; no widgets or tiles.

No additional feature suggestions were introduced before editing.

## Applied in this pass
- **Streak:** consecutive days with at least one non-failed session; animated count and 7-day dots in Analytics.
- **Best-focus-time heatmap:** 7 rows × Night / Morning / Afternoon / Evening, based on successful session minutes; animated cell intensity.
- **Templates + quick start:** Deep Work / Study / Reading built-ins plus Room-backed custom templates. Focus has a quick-start row; the management screen saves the current duration, blocked apps and utility apps, starts a template, and deletes custom templates. **No widget or Quick Settings tile.**
- **Smart Break:** after a successful, non-failed session, the foreground service enters a 5-minute `BREAK` phase. Vibration, sensors, call monitoring and failure detection are paused/unregistered; the timer and notification offer **Skip break**. Break time is not counted as focus time.
- **Interruption tags:** each `PICKUP` event now has a `tag`. After a session or break ends, a tagging screen walks through every pickup with Call / Utility / Urge / Other, or **Skip all** (`Skipped`). Analytics shows top reasons. Untagged pickups are restored on app launch.
- **Overlap detection:** `Scheduler.conflict` compares two-week minute segments, including schedules crossing Sunday midnight. The editor blocks save and names the conflict; the schedule list shows `Overlaps <name>`.
- **Running schedule lock:** `FocusState.runningScheduleId` is set for scheduled sessions. The Schedules list disables edit/switch for that item, shows `Running · editing locked`, and replaces the editor with a locked screen if the session starts while editing. The lock persists through the smart break.
- **10 utility apps:** picker limit, UI counter, template storage and docs changed from 5 to 10.
- **Graphics / animations:** same OLED dark theme. Added subtle gradient tile/background fills, animated tab tint and crossfade, animated focus-ring progress/status color with a soft glow, animated utility-card visibility, animated streak dots, animated heatmap cells, animated adherence ring, and animated tag bars.

## Retained from earlier passes
- Immediate vibration monitoring without an initial face-down flip.
- Alarm-clock scheduled starts while the app is closed / phone is locked.
- Utility apps shown on the running clock; vibration pauses on open and resumes within 3s on close.
- 12-hour dial schedule picker and 12-hour time labels.
- Custom tab graphics instead of dots.
- 5-second face-up failure for **manual and scheduled** sessions; failed sessions excluded from total time and shown as FAILED.
- Total time and failed count in Analytics.
- Battery-optimization exemption card in Schedules.
- Room migrations preserve analytics through schema changes.

## Files changed
- `app/src/main/java/com/flipfocus/app/Data.kt`
- `app/src/main/java/com/flipfocus/app/FocusService.kt`
- `app/src/main/java/com/flipfocus/app/Scheduling.kt`
- `app/src/main/java/com/flipfocus/app/Theme.kt`
- `app/src/main/java/com/flipfocus/app/Ui.kt`
- `app/src/main/java/com/flipfocus/app/Features.kt` (new)
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`
- `README.md`
- `UPDATE_NOTES.md`
- `FlipFocus-update.patch`

## Validation
Kotlin files pass a syntax parse, all XML parses, and `git diff --check` is clean. No local APK build was run because the sandbox has no Android SDK/Gradle; the existing GitHub Actions workflow still builds `assembleRelease assembleDebug`. **No git push was performed.**
