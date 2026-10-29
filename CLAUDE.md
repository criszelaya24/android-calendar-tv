# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read-only Google Calendar app for Android TV (package `palacios.top.calendartv`): agenda screens plus reminder banners drawn over whatever app is on screen. Single Gradle module `:app`, Kotlin, Compose for TV (`androidx.tv:tv-material`), Room, WorkManager, DataStore, Play Services Auth. No Hilt: dependencies are hand-wired in `AppContainer` (`CalendarTvApp.kt`).

## Commands

The system Java is not linked and system Gradle (9.x) is incompatible with the Android plugin. Always use the wrapper with the Homebrew JDK 17:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest assembleDebug --no-daemon      # tests + APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew testDebugUnitTest --tests 'palacios.top.calendartv.TodayModelTest'   # one test class
./gradlew testDebugUnitTest --tests '*DescriptionTextTest.longTextIsEllipsized' # one test
```

No linter is configured. `local.properties` holds `sdk.dir` (gitignored); SDK is `~/Library/Android/sdk` (platform 35). `adb` is not on PATH: `~/Library/Android/sdk/platform-tools/adb`.

### Running on the test TV

The Haier TV (Android 11, API 30) is reached over network adb, `192.168.1.27:5555` (DHCP, may change; the TV must be awake or `adb connect` times out).

```bash
adb connect 192.168.1.27:5555
adb -s 192.168.1.27:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.27:5555 shell appops set palacios.top.calendartv SYSTEM_ALERT_WINDOW allow   # needed for the banner
adb -s 192.168.1.27:5555 shell am start -n palacios.top.calendartv/.ui.MainActivity
```

- Screenshots: use `shell screencap -p /sdcard/x.png` then `pull`. Never `exec-out screencap`: the TV's vendor code prints two junk lines before the PNG bytes.
- Debug builds only: `am start ... --es add_account <email>` starts authorization for that TV account (used to add an account the account picker cannot see).
- Remote-control quirk when scripting key events: the first D-pad press after a screen change is often swallowed by initial focus; check with a screenshot instead of sending blind sequences.
- Inspect the app DB: `adb exec-out run-as palacios.top.calendartv cat databases/calendar-tv.db` (also copy `-wal`/`-shm` or the copy looks stale).

## Architecture

Data flow: **Google Calendar API → `Syncer` → Room → (a) UI flows, (b) `ReminderScheduler` → alarm → `ReminderReceiver` → `ReminderFirer` → `BannerPresenter` → `OverlayService`**.

- **One alarm at a time.** `ReminderScheduler.reschedule()` arms a single exact alarm for the earliest unfired `reminders` row (or "now+1s" if one is already due). It is called after every sync, boot, time/timezone change and every fire; never schedule per-reminder alarms. `ReminderFirer` drains all due rows, marks them fired, shows one banner entry per event (closest offset wins) and skips events already over.
- **Sync model** (`sync/Syncer.kt`): every 15 min via WorkManager (`SyncWorker`, `SyncScheduler`), plus on demand. Per account: authorize silently, upsert calendars (preserving the user's `enabled` choice), then **delete and re-insert** the next 30 days of events per enabled calendar inside a transaction. `ReminderPlanner.plan` rebuilds reminder rows while keeping `fired` flags (keyed by event id + offset + trigger time) and creates reminders more than 10 min in the past already fired, so a first sync does not replay history.
- **Reminder times** come from the event: `reminders.overrides`, or the calendar's `defaultReminders` when `useDefault`. Only `popup` reminders are used. "Days before" is just minutes (`EventMapper`).
- **Multi-account.** Calendar ids are `"<accountEmail>|<remoteId>"` (the same Google calendar can be visible from two accounts); `CalendarEntity.remoteId` is what the API is called with. An account's email is derived from its `primary` calendar id (`Syncer.addAccount`).
- **Two authorization levels** (`auth/GoogleAuth.kt`): sync uses `authorize(email)` = calendar scope only; `authorizeWithProfile(email)` adds the `profile` scope and is used only when adding an account and in Settings → "Update account photos". Do not make sync depend on the profile scope: accounts connected before it was granted would look disconnected. Access tokens are short-lived and re-requested each sync (silent after the first consent); there is no refresh-token handling.
- **Account visibility.** Android only shows the app accounts it has been granted; `AccountManager` returns only already-authorized accounts, so adding a new TV account may need the Google chooser or the debug `add_account` extra.
- **Room**: `AppDatabase` (version 3) with a real `MIGRATION_2_3`; older versions fall back to destructive migration because everything but settings is a re-syncable cache. New schema changes need a migration to keep `fired` flags.
- **Banner** (`banner/`): `OverlayService` adds a `TYPE_APPLICATION_OVERLAY` view with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` (remote keeps controlling the app underneath), plain `View`s rather than Compose. `BannerItem`/`BannerRequest` are JSON-serialized into the service intent. `BannerPresenter` also posts a normal notification for the TV panel. `AvatarStyle` is shared by the banner and Compose rows (photo file, else colored initial).
- **UI** (`ui/`): `MainViewModel` owns navigation (`Screen`: Today is the landing screen, Agenda = "Upcoming", Detail returns to its origin tab via `back()`), account/consent flows and the minute tickers that keep Today/Upcoming from going stale. `TodayModel` and `DescriptionText` are pure and unit-tested. Selected tab buttons request focus on composition so the remote keeps its place after switching tabs.

## Device-specific gotchas (learned the hard way)

- **Standby bucket**: a freshly installed app that was never opened sits in bucket `NEVER` and its alarms are deferred ~24 h. Open the app once after installing (`adb shell am get-standby-bucket palacios.top.calendartv` should be 10).
- **Sound uses `USAGE_MEDIA` on purpose** (`SoundPlayer` in `banner/Sounds.kt`): on this TV the notification/alarm/system streams are at volume 0 (single-volume device), so reminders using notification usage were silent. Check with `adb shell dumpsys audio`.
- **Exact alarms**: API 30 needs no permission; on API 31+ the scheduler falls back to inexact alarms if `canScheduleExactAlarms()` is false.
- **OAuth**: sign-in only works when the Cloud project has an Android OAuth client for `palacios.top.calendartv` plus the signing key's SHA-1 (debug keystore `~/.android/debug.keystore`; a different build machine or release key needs its own client). The consent screen is External/Testing, so only listed test users can sign in and consent expires every 7 days. No client secret, API key or `google-services.json` is used.
- A local `.gradle/`, `.kotlin/` and `build/` exist; `.kotlin/` is not in `.gitignore` and the directory is not a git repository yet.
