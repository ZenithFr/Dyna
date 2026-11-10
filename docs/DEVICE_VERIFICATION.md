# Device verification log

Real-hardware runs, kept separate from source-level analysis. Anything not listed here is **not**
measured yet — the sandbox used for the patch passes has no JDK/Gradle/Android SDK/adb, so every
on-device claim below comes from a run on the maintainer's phone.

---

## Run 1 — 2026-11-09 · `master` @ `498058d` (PR #5) · v0.5.0 debug

Wireless ADB device (`192.168.1.128:41417`), debug APK.

### Verified

| Check | Command | Result |
| --- | --- | --- |
| Build + JVM unit tests | `mise exec java@openjdk-21 -- ./gradlew testDebugUnitTest assembleDebug` | **BUILD SUCCESSFUL in 2m 51s**, 43 actionable tasks. First real toolchain run: the idle-policy, icon-placement, category-reset, media-dedupe and visibility tests added in PRs #4/#5 executed and passed. `app-debug.apk` produced. |
| Install | `adb install -r app/build/outputs/apk/debug/app-debug.apk` | Streamed install → **Success** |
| Idle window state | `adb shell dumpsys window windows \| grep NothingIsland` | Only `NothingIslandVisibility` (the 1×1 inset probe) is attached. **No** `NothingIsland`, `NothingIslandTouch` or `NothingIslandReply` window while idle — the settled island really releases its surface. |
| Screen-off / screen-on suppression | `adb logcat -s IslandOverlay` (Verbose logging on) | `suppression NONE -> SCREEN_OFF (screenActive=false)` at 23:14:10.993, then `suppression SCREEN_OFF -> NONE (screenActive=true)` at 23:14:40.325. Clean two-way transition, no intermediate churn. |
| Process cost (short sample) | `adb shell dumpsys batterystats com.zenith.nothingisland` | `CPU: 0ms usr + 0ms krn ; 2s 640ms fg`, `1 starts`; `IslandService` — `Starts: 0, launches: 1`. Consistent with an event-driven service, but see the caveat below. |

The remaining probe window is intentional: it is a static, non-touchable 1×1 window whose insets tell
the island when an immersive app releases the status bar. It draws nothing and requests no frames.

### Not yet verified by this run

1. **The app-switch flicker path — the actual reported bug.** The captured log contains only the two
   screen-state transitions. No content was on screen during the capture, so no attach/detach, linger
   or fullscreen-debounce behaviour was exercised.
2. **Battery drain over time.** The `batterystats` sample covers a freshly installed process
   (`1 starts`, ~2.6 s foreground). It is not an idle soak and cannot show drain per hour.
3. **Frame rate / frame cost.** No `gfxinfo framestats` or SurfaceFlinger latency capture yet, so the
   "120 Hz only while morphing, 0 at rest" claim is still source-level.
4. **Alert icon in the detached bubble** and its retraction when the hold expires — not observed.
5. **RAM retention** across media track swaps — no `meminfo` capture.

---

## Capture procedure for the remaining checks

Enable **Settings → Advanced → Verbose logging** first. The diagnostics below are gated by that
switch and allocate nothing while it is off.

### A. App-switch flicker (idle island must stay gone)

```bash
adb logcat -c
adb logcat -s IslandOverlay &

# Post an alert so the island attaches, then let the hold + linger expire.
adb shell cmd notification post -S bigtext -t "Flicker test" ni_flicker "Watch the island retire."
sleep 8

# Now open and close apps repeatedly for ~30 s (home, camera, a game, back to home).
```

Expected log shape:

```
attach island (phase=CONTENT, source=Notification)
idle -> LINGERING (1s)
linger armed remaining=1000ms
detach island (phase=DORMANT, suppression=NONE)
idle -> DORMANT (surface released)
... silence while apps are opened and closed ...
```

**Regression signal:** repeated `attach island` / `linger armed remaining=1000ms` pairs while nothing
is on screen. That is the resurrection loop PR #5 removed; if it reappears, capture the surrounding
lines and reopen the issue.

### B. Fullscreen debounce (immersive apps)

```bash
adb logcat -c && adb logcat -s IslandOverlay
# Launch a video full screen, then exit.
```

Expected: at most one `fullscreen hide pending (250ms)` per transition. A launch animation that blinks
the status bar should log `fullscreen hide pending` followed by
`fullscreen hide cancelled (status bar back)` **without** a `detach island` line.

### C. Battery soak (the number that actually matters)

```bash
adb shell dumpsys batterystats --reset
# Leave the phone screen-off and idle for 60 minutes, then:
adb shell dumpsys batterystats com.zenith.nothingisland
adb shell dumpsys deviceidle get deep
```

Compare `CPU usr/krn`, wakeups and `Wifi/Bluetooth scan` counts against a 60-minute baseline with the
accessibility service disabled. Screen-off should show no island windows at all:

```bash
adb shell dumpsys window windows | grep NothingIsland   # expect no output while the panel is off
```

### D. Frame demand

```bash
adb shell dumpsys gfxinfo com.zenith.nothingisland reset
# Expand/retract the island a few times, then:
adb shell dumpsys gfxinfo com.zenith.nothingisland framestats
# Idle for 30 s and re-read: the frame count must not keep climbing.
```

### E. Alert bubble

```bash
adb shell cmd notification post -S bigtext -t "Bubble test" ni_bubble "Icon should sit beside the pill."
```

Expected on screen: app icon in the detached circle, **text only** inside the pill, and the circle
retracting into the shell when the hold expires (3 s by default; change **Layout → Detached bubble → Bubble
hold** to check 1–10 s). Toggle **Notifications & apps → App icon in detached bubble** off to see the
icon return to the pill. Toggle **Notifications & apps → App icon in detached
bubble** off to see the icon return to the pill.

### F. RAM

```bash
adb shell dumpsys meminfo com.zenith.nothingisland
# Swap tracks 10 times in a music app, then re-read; Java heap / graphics should not climb steadily.
```

### G. WhatsApp / Discord delivery, including silent notifications

**Status: pending on-device verification.** Run 1 above used `498058d`, before this repair. Do not
record the new routing tests or source inspection as a real WhatsApp/Discord result.

The old manifest requested only `conversations|alerting`. Android therefore withheld SILENT and
ONGOING posts before our listener ran. The app then independently dropped importance LOW. The repair
removes the manifest restriction, migrates an existing listener's saved type mask once, and shows
silent messages by default with a user opt-out. Generic ongoing services remain filtered in app;
calls, progress and maps are classified first.

1. Build and install **over the existing app**; do not clear data or uninstall:

   ```bash
   mise exec java@openjdk-21 -- ./gradlew testDebugUnitTest assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.zenith.nothingisland/.MainActivity
   ```

2. Enable **Advanced → Verbose logging**. In **Setup → Diagnostics**, require `Overlay service:
   RUNNING`, `Notification access: YES`, and `Notification listener: CONNECTED`. Use **Re-check now**
   after changing a grant. If the listener stays WAITING, toggle its system notification access off/on.
   **Show test alert** should show the synthetic pill/bubble even if notification access is off; that
   proves only the overlay path.
3. Start a clean log capture, then send a new message from another account/device to WhatsApp and
   Discord. Leave the phone unlocked and outside fullscreen/landscape hiding for this check.

   ```bash
   adb logcat -c
   adb logcat -s IslandNotificationListener:I IslandNotifications:D IslandOverlay:D IslandService:I
   ```

   Expected after the upgrade's first listener connection:

   ```text
   listener connected
   all notification types migration requested
   show pkg=com.whatsapp source=Notification
   show pkg=com.discord source=Notification
   attach island (phase=CONTENT, source=Notification)
   ```

   INFO bind/migration lines appear even with verbose off; package/source/drop and overlay lines need
   verbose on. The migration line means the request was issued, not that a particular app has posted.
4. Mark a test conversation/channel **Silent** (or use Android's gentle notification control), send a
   new message, and repeat. With **Notifications & apps → Silent notifications** on, it should still
   show. Turn that setting off: expect `drop pkg=… reason=IMPORTANCE importance=2` for the silent post,
   while an alerting message still shows. Turn it back on afterwards. App-internal mute alone does not
   guarantee Android importance LOW; use the OS silent/gentle classification for this regression.
5. Reconnect the listener once more. The migration should not be requested again; later system type
   choices must not be overwritten on every bind. A reinstall with cleared app data is a separate
   fresh-install case, not the upgrade migration test.

Drop reasons and the next check:

| Log reason | Meaning / action |
| --- | --- |
| `OVERLAY_STOPPED` | Listener received the post, but accessibility overlay was not running. Enable it and send a **new** message; old shade entries are not replayed. |
| `IMPORTANCE` | Importance MIN/NONE is always ignored; LOW is ignored only when Silent notifications is off. The numeric importance is included. |
| `DISABLED_PACKAGE` / `NOTIFICATIONS_OFF` | Check the per-app opt-out and Sources → Notifications switch. |
| `DO_NOT_DISTURB` | Total-silence DND blocks island alerts. Retry outside that mode. |
| `GROUP_SUMMARY` | Summary intentionally skipped; the individual child message should produce its own accepted line. |
| `MEDIA` / `SERVICE` / `ONGOING` | Media goes through MediaSession; a plain foreground-service status is not a message. Calls/downloads/maps should use their own source instead. |
| `CALLS_OFF` / `DOWNLOADS_OFF` / `NAVIGATION_OFF` | Enable the corresponding source. |
| `SECRET` / `OWN_PACKAGE` | Hidden notification / our own system notification intentionally skipped. The in-app test alert bypasses this listener path. |
| `UNCHANGED` | Same key and rendered signature already accepted; not a new alert. A changed message should produce a new signature. |
| `Dropped malformed notification` | Always-on warning with exception; capture the stack trace. |
| No listener lines at all | Check live bind state, system notification-type/package filters and whether the sending app actually posted a notification. An overlay-only test cannot diagnose this. |

Logs contain package names and routing facts, not titles, bodies, custom category text or notification
keys. Unrecognized categories appear as `other`. Turn verbose logging off after capture. Record the build commit, grant/connection rows, normal/silent results and
relevant log lines here only after running them on the phone.

---

### H. Multi-alert rotation, unread badge, Clock timers, notification controls

Not yet run on a device. Source tests only (`IslandStateHolderTest`, `NotificationGateTest`,
`AlertStackTest`).

1. Post two messages from different apps (for example two `adb shell cmd notification post` calls
   with different tags). Expected: the island rotates between the two, and the detached icon shows an
   unread badge of `2`. Dismiss one from the shade: the badge falls to `1`. Dismiss the last: the
   island returns to its base layer and the badge clears.
2. No card may show a percentage. A message with `EXTRA_PROGRESS` and no ongoing flag must render as a
   message, not a progress card.
3. Start a timer in the Google Clock app (`com.google.android.deskclock`) or AOSP Clock
   (`com.android.deskclock`). Expected: an activity card with the Clock's own actions and no progress
   bar. Record the real package name seen in logcat (`IslandNotificationListener`, verbose logging).
   Turn **Sources & timer → Clock** off and confirm the card no longer appears.
4. Post a progress notification that has notification actions. Expected: those buttons plus Open, and
   no progress bar or cancel control.

Record the build commit and the results here only after running them on the phone.

---
