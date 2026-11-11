# Wave 0 handoff — Unit 1 (baseline + CI) and Unit 2 (timer alert)

**Date:** 2026-11-11 · **Branch:** `master` · **Base commit:** `6298eb7`
**Plan:** [`../../plan-secure.md`](../../plan-secure.md) · **Requirements:** [`dyna-upgrade-requirements.md`](./dyna-upgrade-requirements.md)

Work was stopped mid-flight (server restarts). This file is the resume point.

## What landed

### Unit 1 — measurement and CI

| File | State |
|---|---|
| `scripts/perf/capture.sh` | Done. `idle / morph / timer / media / mem / battery / perfetto / all`, `bash -n` clean, exits 2 with a clear message when no device is attached |
| `scripts/perf/perfetto.cfg` | Done. sched + ftrace + `android.surfaceflinger.frametimeline`, 16 MB ring, 20 s |
| `docs/perf/PERF_BUDGET.md` | Done as a **budget**, results column reads NOT MEASURED (no device was attached) |
| `.github/workflows/ci.yml` | Done. JDK 21, `testDebugUnitTest assembleDebug`, `lintDebug` non-blocking until the baseline is clean, artifact upload on failure |
| `.github/dependabot.yml` | Done. gradle + github-actions, weekly, grouped minor/patch |
| `Trace.beginSection` | Done: `island.onDraw`, `island.motion` (`IslandPillView`), `island.apply` (`IslandOverlay`) |

Not done: **no measurement has been taken**. Q1 (does `gfxinfo` see `TYPE_ACCESSIBILITY_OVERLAY` windows?) is still open and blocks trusting P1–P3.

### Unit 2 — a timer that always fires

New, all under `app/src/main/java/com/zenith/nothingisland/service/timer/`:

| File | Role |
|---|---|
| `TimerCore.kt` | Pure state machine + the four seams (`TimerClock`, `TimerScheduler`, `TimerStorePort`, `TimerSink`). No Android, no coroutines |
| `TimerModule.kt` | Android shell: 1 Hz ticker, publication to `IslandStateHolder`, boot-receiver enable/disable, `attach`/`restore`/`onAlarmFired`/`onBootCompleted`/`onServiceTornDown` |
| `AlarmTimerScheduler.kt` | `setExactAndAllowWhileIdle`, falls back to `setAndAllowWhileIdle` when `SCHEDULE_EXACT_ALARM` is not granted; `isExact()` drives the UI warning |
| `TimerStore.kt` | `SharedPreferences` (label, endWallMs, remainingMs, paused), `commit()` |
| `TimerAlertNotifier.kt` | `IMPORTANCE_HIGH` channel "Timers", vibration default on / sound default off, direct-vibration fallback when notifications are blocked |
| `TimerReceivers.kt` | `TimerAlarmReceiver` (`exported=false`), `BootReceiver` (manifest `enabled=false`, enabled only while a timer exists) |
| `res/drawable/ic_timer.xml` | Notification small icon |

Modified: `AndroidManifest.xml` (SCHEDULE_EXACT_ALARM, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, two receivers), `IslandService.kt` (`attach` on connect, `onServiceTornDown` keeps the timer across a rebind), `MainActivity.kt` (`attach`, POST_NOTIFICATIONS request), `IslandSettings.kt` + `IslandOptions.kt` (`timerAlertVibrate` on, `timerAlertSound` off, keyed + reset with SOURCES), `CustomizationPanels.kt` (two switches + an exact-alarm status row that re-reads on resume).

Tests: `app/src/test/.../service/timer/TimerCoreTest.kt`, 11 cases (double alert, out-of-range, pause/resume, early alarm, restore past/future/stale/paused, inexact reporting, stop).

## Verified vs not

| Claim | Status |
|---|---|
| Compiles, 131 unit tests pass, debug APK builds | **Verified** (`testDebugUnitTest assembleDebug`, BUILD SUCCESSFUL) |
| Lint has zero errors | **Verified** at `1fad371`: 0 errors / 75 warnings (`lintDebug` BUILD SUCCESSFUL) |
| Timer fires on time in Doze | **Verified** 2026-11-11: deadline `10:25:41.000`, alarm fired `10:25:41.289` (**289 ms**) under `dumpsys deviceidle force-idle` with the screen off and `SCHEDULE_EXACT_ALARM` granted (`window=0 exactAllowReason=permission`) |
| The alert is perceivable with the screen off | **Verified**: vibration waveform played at `10:25:41.332` (`dumpsys vibrator_manager`, 0/350/180/350 ms) and the notification (`id 31250`, `category=alarm`, `SILENT`, `VIS=PUBLIC`) was still present minutes later |
| Inexact fallback still fires | **Verified**: with the appop at `default`, the same test fired **46 s late** — the fallback works, and the settings row explains it |
| No alarm left armed after completion | **Verified**: `dumpsys alarm` has no `TIMER_ALARM` batch after the fire |
| Boot receiver is enabled only while a timer exists | **Verified**: `dumpsys package` shows `enabledComponents: …BootReceiver` while a timer runs, and the component returns to disabled after completion |
| Process kill then reopen restores the countdown | **Verified**: `am force-stop` → reopen logged `Restored timer (paused=false, finished=false)` and re-armed an exact alarm for the remaining time |
| Reboot re-arms a stored timer | **Verified** 2026-11-11: the device rebooted with a stored deadline; `events` log shows `am_proc_start … nothingisland,service` at `10:31:20` and `notification_enqueue … 31250 … timer_alerts` at `10:31:47`, i.e. the boot restore fired a late alert without the activity being opened |
| Notification survives the 4 s finished hold | **Verified** after `1fad371`: alert fired `10:46:10.054` (deadline `10:46:10.000`, **54 ms**) and the notification was still posted 23 s later (also seen on the lockscreen: "Timer finished / HOLDTEST") |
| Heads-up appears on Android 15 with target 35 | **Not verified** — the screen was locked, so the shade was never observed |
| CI runs green on GitHub | **Not verified** — the workflow has never executed |
| Q1: does `gfxinfo` see the overlay? | **Inconclusive** — `capture.sh morph 5` reported 0 frames, but the phone was locked so the island was not attached and the taps hit nothing. Re-run unlocked before recording a verdict |

## Resume checklist

```bash
# 0. device
adb devices -l                      # wireless pairing may need `adb connect` again
# 1. install
cd ~/Work/Dyna && mise exec java@openjdk-21 -- ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 2. the P0 acceptance test (plan Unit 2 "Verification")
adb shell cmd notification allow_listener com.zenith.nothingisland/.service.notification.IslandNotificationListener
#    start a 1-minute timer from the app, lock the phone, put it down
adb shell dumpsys deviceidle force-idle
#    expect vibration + heads-up within 2 s of 00:00; repeat 20x
adb shell dumpsys alarm | grep -i nothingisland
adb shell dumpsys deviceidle unforce
# 3. durability
adb shell am force-stop com.zenith.nothingisland     # then reopen the app: countdown must reappear
adb reboot                                            # timer must re-arm and fire
# 4. Q1 spike (blocks the perf baseline)
scripts/perf/capture.sh morph 5                       # 0 frames while the island animates => gfxinfo cannot see the overlay
```

Then: fill `docs/perf/PERF_BUDGET.md`, record the Q1 verdict there, and only then start Wave 1 (U3, U4, U7).

## Known risks / review notes

An independent AGY review of the Unit 2 diff was **started but interrupted**; its report never landed. Re-run it before trusting the diff, with these as the review targets:

1. **Double alert** — `attach()` → `restore()` and `onAlarmFired()` can both run in the same process; `TimerCore` guards with `finished`, but confirm no path calls `finish()` twice (the test covers tick-then-alarm, not attach-then-alarm).
2. **Wall clock vs uptime** — `TimerStore` keeps `endWallMs`; a manual clock change moves the deadline. `restore()` handles past deadlines but not a clock moved *forward* past `STALE_AFTER_MS` (that discards the timer instead of firing).
3. **Alarm left armed** — every `stop`/`finish` path calls `scheduler.cancel()`; the boot receiver is disabled on finish and stop, but not if the process is killed mid-finish.
4. **`runBlocking` in `TimerAlertNotifier.readSettings`** — up to 1 s on the receiver's main thread, by design. Confirm no ANR risk on a cold start.
5. **Channel recreation** — toggling vibration deletes and recreates the channel, which resets any per-channel user settings. Intentional, but worth a second opinion.
6. **UI** — the finished hold still waits for the island to be visible (30 s cap) and the ticker still stops on screen-off; both are deliberate, but the interaction with the new alarm path deserves a device test.

## Open questions carried over

- **Q1** gfxinfo vs overlay windows — blocks the perf baseline (spike above).
- **Q3** timer sound default — shipped as vibration-only.
- Custom duration / label (B17) was **not** added; only the 1/5/10/25-minute presets exist.
