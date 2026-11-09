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
4. **Alert icon in the detached bubble** and its retraction when the 4 s hold expires — not observed.
5. **RAM retention** across media track swaps — no `meminfo` capture.

---

## Capture procedure for the remaining checks

Enable **Settings → Advanced → Verbose logging** first. The diagnostics below are gated by that
switch and allocate nothing while it is off.

### A. App-switch flicker (idle island must stay gone)

```bash
adb logcat -c
adb logcat -s IslandOverlay &

# Post an alert so the island attaches, then let the 4 s hold + linger expire.
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
retracting into the shell when the hold expires. Toggle **Notifications & apps → App icon in detached
bubble** off to see the icon return to the pill.

### F. RAM

```bash
adb shell dumpsys meminfo com.zenith.nothingisland
# Swap tracks 10 times in a music app, then re-read; Java heap / graphics should not climb steadily.
```
