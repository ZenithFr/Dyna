# Dyna (Nothing Island) — Bugs, Efficiency, Animation & Feature Upgrade Plan

**Skill used:** [superplanning](https://github.com/lucianghinda/superplanning) — 7-phase flow, New Feature mode, Deep scope.
**Companion:** [`dyna-upgrade-requirements.md`](./docs/plans/dyna-upgrade-requirements.md) (stable IDs R1–R23).
**Date:** 2026-11-10 · **Repo:** https://github.com/ZenithFr/Dyna @ `master`
**Method:** read the full README and `docs/*`, cloned the repo, then read the files that own the timer, media, overlay window, motion math, draw loop, settings persistence and notification routing. Two web research passes covered competitors and the platform APIs this plan relies on.

> **Honesty box.** This is static analysis. No Android SDK or device was available, and nothing was compiled or run. Each finding carries a **Confidence** rating: **High** means the code path is unambiguous. **Medium** means it needs one device check. **Hypothesis** means it is a performance suspicion that Unit 1 must measure. The plan assumes **line numbers drift**, so cite file + symbol when you act on them.

Path shorthand used below: `SRC` = `app/src/main/java/com/zenith/nothingisland`, `TEST` = `app/src/test/java/com/zenith/nothingisland`.

---

## Phase 0 — Intake & Route

| Item | Result |
|------|--------|
| Mode | **New Feature** — application code exists (13.3 k lines of Kotlin) |
| Scope | **Deep** — user asked for a comprehensive plan across bugs, features, efficiency and animation |
| Prior artifacts | `docs/audit-2026-11-08.md`, `docs/FEATURE_PLAN.md`, `docs/DEVICE_VERIFICATION.md` (maintainer's own). Read, and **not duplicated**: the P1/P2 items already fixed there are treated as done. |
| Resume check | No `docs/plans/` or `docs/brainstorms/` content in the Dyna repo. |

**Gate 0→1:** passed.

## Phase 1 — Ground

### 1a. What the codebase already does well (protect these — R23)

| Strength | Where | Why it matters |
|---|---|---|
| Closed-form underdamped spring, velocity preserved on reversal | `SRC/overlay/IslandMotion.kt` | Same trajectory at 60/90/120 Hz in principle |
| Idle is a state machine (`CONTENT → LINGERING → DORMANT`) and the surface is **detached** when dormant | `IslandIdlePolicy.kt`, `IslandOverlay.hide()` | Zero frames at rest, verified by window dump in Run 1 |
| Fixed-size window envelope, so a morph never resizes the surface | `IslandOverlay.envelopeOf` | Avoids the compositor "stretch jump" |
| No polling for media, notifications, battery | `MediaSource`, `IslandNotificationListener`, `ChargingSource` | Event-driven |
| Screen-off detaches everything | `IslandOverlay` suppression `SCREEN_OFF` | Verified in Run 1 |
| Fail-open fullscreen probe with a 250 ms debounce | `IslandVisibility.kt` | Avoids "island vanished forever" |
| Lock-screen redaction, own-package and secret-notification drops | `NotificationSource`, `NotificationGate` | Privacy |
| Detached "mitosis" bubble, curl-into-lens idle | `IslandPillView` | Genuinely distinctive vs. competitors |
| Accessibility config requests **no event types** and `canRetrieveWindowContent=false` | `accessibility_service_config.xml` | The service is not woken for UI events |

### 1b. Findings — bugs and logical issues

Severity: **P0** wrong or broken as shipped · **P1** user-visible defect or meaningful rework · **P2** hidden cost or edge case · **P3** polish.

#### P0

**B1 — A finished timer is silent.** · Confidence **High**
- _Where:_ `SRC/service/timer/TimerModule.kt` → `finish()`.
- _What:_ it publishes `00:00` / "TIME'S UP", waits until the island is visible, holds 4 s, and clears. There is no vibration, sound, notification or alarm anywhere in the app: `grep -rniE "vibrat|Ringtone|AlarmManager|setExact|MediaPlayer|SoundPool|WakeLock|VibrationEffect" app/src/main/java` returns nothing.
- _Impact:_ start a 10-minute timer, lock the phone, put it in a pocket → nothing happens at the deadline. `finish()` even deliberately waits for the screen to turn on ("keep 00:00 until the user can see it").
- _Shadow paths:_ screen off at finish (silent), DND on (should still alert for a user-started timer, but respect channel settings), island disabled mid-timer (timer is stopped in `teardown`).
- _Fix:_ Unit 2.

**B2 — Timer completion is not reliable, and the timer is not durable.** · Confidence **High** (dispatcher semantics) / **Medium** (exact observed lateness)
- _Where:_ `TimerModule.scheduleHiddenCompletion()`, `startTicker()`; state lives in `object TimerModule` fields.
- _What:_ completion is a coroutine `delay(remaining)` on `Dispatchers.Main`. That dispatcher schedules with `Handler.postDelayed`, which runs on **uptime**, and uptime does not advance in deep sleep. The deadline is stored as an `elapsedRealtime` value in process memory only, so a process kill (common on aggressive OEM memory managers) or a reboot loses the timer with no trace.
- _Impact:_ with the screen off the completion fires late, after the next wake-up. The remaining-time maths stays correct, because it derives from the stored `elapsedRealtime` end stamp, but there is no alarm to wake anything. After a process kill the timer silently vanishes.
- _Fix:_ Unit 2.

#### P1

**B3 — Expanded media progress freezes between player callbacks.** · Confidence **Medium** (confirm with a player that does not emit per-second state)
- _Where:_ `IslandPillView.drawMediaExpanded` calls `media.estimatePositionMs(elapsedRealtime())`; `IslandPillView.onDraw` only re-posts frames when something is "busy" (spring, reveal, swap, ambient loops — which default **off**).
- _What:_ the estimate is correct _when drawn_, but nothing schedules a draw while the card sits expanded. `IslandRenderDemand.isMediaPositionTick` explicitly returns `false` for expanded events so callbacks do repaint, but Spotify / YouTube Music generally publish playback state on change, not every second.
- _Impact:_ elapsed/remaining text and the bar look stuck until the user pauses or skips.
- _Fix:_ Unit 3 (boundary-aligned 1 Hz tick, only while expanded + playing + screen on).

**B4 — Pausing removes the island, so the play button is unreachable.** · Confidence **High**
- _Where:_ `MediaSource.publish()`: `if (!active) { clearMedia(); return }`; `togglePlayPause()` is `if (playing) pause() else play()`.
- _What:_ `isPlayingState()` excludes `PAUSED`, so the island disappears the instant playback pauses. `playing` is `state == STATE_PLAYING`, so the `play()` branch only runs during buffering/skipping.
- _Impact:_ you cannot resume from the island — the main reason to have transport controls. The README documents the disappearance as intended; the transport UI's play icon is the inconsistency.
- _Fix:_ Unit 3 (`keepPausedSec`, default 30 s, `Media.isPaused`).

**B5 — Animation time-step is clamped and sampled off-vsync.** · Confidence **High** (code) / **Medium** (visible effect)
- _Where:_ `IslandPillView.advanceMotion`: `dt = ((now - lastStepMs)/1000f).coerceIn(0.003f, 0.032f)`, with `now = SystemClock.elapsedRealtime()` read inside `onDraw`.
- _What:_ (a) the closed-form spring is exact for any `dt`, so the 32 ms clamp is unnecessary, and it makes a hitch (GC, app launch) **slow the animation in wall-clock time** — contradicting the "same trajectory at 60/120 Hz" claim. The clamp discards `stall − 32 ms` of animation time. Simulating the shipped width spring (ω 24, ζ 0.88, 120 Hz) with one long frame at 80 ms: peak position error **13 % of the travel**, settles **50 ms late**; at 120 ms: 17 % and 92 ms. A 40 ms stall costs only 3 % / 8 ms, so it matters for real hitches, not for ordinary jitter. (60 Hz vs 120 Hz without a hitch agree to 4 decimals, so the closed form itself is sound.); (b) sampling `elapsedRealtime()` mid-`onDraw` instead of the frame's vsync time adds draw-time jitter to the spring position (micro-judder); (c) the first frame assumes `1/120 s`.
- _Fix:_ Unit 5 (use the frame time from `View.getDrawingTime()` or a `Choreographer.FrameCallback`, clamp only at ~250 ms to survive backgrounding).

**B6 — Every slider tick writes DataStore and re-lays-out the live overlay.** · Confidence **High**
- _Where:_ `ui/HomeScreen.kt` `SettingSlider.onValueChange` → `MainActivity` `onSettingsChange` → `lifecycleScope.launch { repository.update(transform) }`; `IslandSettingsRepository.update` rewrites ~60 keys inside one `edit {}`; `IslandService` collects the flow and calls `overlay.updateSettings`.
- _Impact:_ dragging a slider causes dozens of disk transactions per second plus an overlay re-apply per emission. It only happens while the settings app is open, so it is not an always-on drain, but it is the likeliest cause of a warm phone during calibration.
- _Fix:_ Unit 7.

#### P2

**B7 — Timer and Clock re-run the whole `apply()` every second.** · Confidence **High** (code) / **Hypothesis** (cost)
- _Where:_ `IslandOverlay.render`: only `newEvent == event || isMediaPositionTick(...)` short-circuits. A timer tick changes `remainingMs`, so it falls through to `pill.render(newEvent)` + `apply()`, which recomputes four footprints, the window envelope, calls `setFrames`, `setShape`, `setStroke`, `setCustomization`, and re-places the touch window.
- _Impact:_ for a 25-minute timer that is ~1,500 full layout passes, each allocating. It is exactly the "always-on running activity" case the user cares about.
- _Fix:_ Unit 4.

**B8 — Touches are rejected during a morph.** · Confidence **High**
- _Where:_ `IslandPillView.onTouchEvent`: `if (isMorphing()) return false`; also `targetAt`/accessible nodes return empty during morph.
- _Impact:_ a tap in the first ~400 ms after an expand/curl does nothing and gives no feedback. The spring already supports reversal with preserved velocity, so the guard removes a capability the math has.
- _Fix:_ Unit 9.

**B9 — Per-frame heap allocation in draw helpers.** · Confidence **High** (allocations) / **Hypothesis** (jank)
- _Where:_ `RectF(...)` constructed inside `drawTransportButton`, `drawPlayingBars`, the progress track, `drawArt` (`dst`), satellite icon rect, call and battery drawing; `drawArt` also rebuilds a `Path` and `clipPath`s every frame.
- _Impact:_ GC pressure during a 120 Hz morph (8.3 ms budget); a non-rect `clipPath` is also an anti-aliased clip, which is costly on the GPU path.
- _Fix:_ Unit 6.

**B10 — Two offscreen layers per morph frame.** · Confidence **High** / **Hypothesis** (cost)
- _Where:_ `drawContentLayer` calls `canvas.saveLayerAlpha(...)` whenever `alpha < 0.999` — during a morph both compact and expanded layers qualify.
- _Fix:_ Unit 6 (apply alpha to paints and drawables; keep a layer only for the artwork).

**B11 — Frame-rate hint is a hard number, with no thermal or battery tier.** · Confidence **High**
- _Where:_ `IslandRenderDemand.motionRateHz` → 120 / 90 / ambient Hz; `setRequestedFrameRate(rate)`.
- _What:_ the platform offers **categories** (`REQUESTED_FRAME_RATE_CATEGORY_HIGH`/`NORMAL`) so the system can choose by panel and policy. The app also already detects Battery Saver (`ChargingSource`) but does not feed it into motion.
- _Fix:_ Unit 8.

**B12 — Fullscreen probe: "calibrated" gate fails open until it has seen a visible status bar once.** · Confidence **High**
- _What:_ deliberate (it fixed a real "island vanished" bug), but the consequence is that after service start the island can draw over a fullscreen video until the status bar has been seen visible once. A second always-attached 1×1 overlay window also stays on screen while idle.
- _Fix:_ document, add a "Re-calibrate" action in Advanced; measure the probe's cost in Unit 1. Not scheduled as a unit.

**B13 — The riskiest modules have no unit tests.** · Confidence **High**
- _What:_ `TEST` covers layout, motion math, state holder, idle/visibility policy, notification gate, settings, clock parser. There is **no** `TimerModuleTest`, `MediaSourceTest`, `ChargingSourceTest`, and no draw test for the 2,643-line `IslandPillView.kt` — and B1/B2/B4 live in exactly those files.
- _Fix:_ every unit below lists tests; Unit 2 introduces a fake clock + fake scheduler seam so `TimerModule` becomes testable.

**B14 — No CI.** · Confidence **High** (no `.github/` directory in the tree)
- _Fix:_ Unit 1.

#### P3

**B15 — `IslandStateHolder.clearAll()`** calls `clearTransient()` (which may publish) and then `publishLocked()` again, so two emissions on teardown; `postNavigation` has a formatting slip (`{        navigation = …`).
**B16 — User-visible strings are hard-coded in Kotlin** ("TIME'S UP", "Unknown track", "Now playing", `"Reply to $title"`, toasts); `res/values/strings.xml` has 4 entries. → Unit 17.
**B17 — Timer UI offers only 1 / 5 / 10 / 25 min presets**, no custom duration or label (the `label` parameter exists in `TimerModule.start`). → part of Unit 2's UI.

### 1c. Competitive landscape (research, 2026)

| Layer | Finding |
|---|---|
| **L1 — Tried and true** | _Akira9021 "Dynamic Island for Android"_: timer ends with alarm + vibration, last 10 s in red; music card with synced lyrics, seeking, audio output; call card with avatar and duration; downloads as app icon + progress **ring** with percentage; flashlight card with brightness swipe; notifications with avatar + app badge, Reply / Mark as read, swipe up dismiss; navigation + rides/deliveries ETA; charging bar; upcoming alarm / sleep card; idle island with clock/date/battery/timers. _dynamicSpot_: touchable seekbar, Bluetooth headset battery, hotspot / silent mode / screenshot toggles, notification-light replacement. |
| **L2 — New and popular** | _Gesture-to-action_ cutout apps (Action Notch style): tap / double-tap / long-press / swipe mapped to screenshot, flashlight, screen off, power menu, QR scan, DND, rotation lock, brightness, ringer, music control. _Android 16 QPR1 Live Updates_ (`Notification.ProgressStyle`, promoted ongoing, status-bar chip using `shortCriticalText`) and Samsung's Now Bar — the platform is standardising the data Dyna already reads from notifications. |
| **L3 — First principles** | Dyna's real edge is **being the cheapest correct implementation**: event-driven, zero frames at rest, hole-aware layout, a distinctive detach/curl motion language. If it adds the competitors' features by polling or looping, it loses that edge. So the first-principles question for every feature is "what is the cost while nothing is happening?" |

**Gate 1→2:** research complete.

## Phase 2 — Challenge & Explore

> Anti-sycophancy rules applied. No blocking question tool exists in this environment and the user instructed "always just update… don't ask". So each forcing question below is answered with an explicit **position** and the **evidence that would change it**, and every assumption is logged in `docs/sessions/`.

**Q — Is "make animation smoother" the right problem?**
Position: partly a proxy. The spring maths is already sound; the likely sources of perceived roughness are B5 (clamp and off-vsync sampling), B9/B10 (allocations and offscreen layers) and B8 (touch dropped during morph). Tuning ω/ζ before fixing those would tune around a bug. _Would change if:_ Unit 1's trace shows a clean 120 Hz frame cadence during morphs.

**Q — Is "no battery drain, no heat while always on" achievable, and where does the cost really live?**
Position: the idle path is already near the floor by design (detached surface, no event types, no polling). The remaining always-on costs are the _running activity_ states: a 1 Hz full layout for timers/clock (B7), the settled-but-visible media card, the extra probe window, and any opt-in ambient loop. Animation cost is bursty, and small next to those. _Would change if:_ batterystats shows meaningful CPU in idle (then look at the accessibility binding and the probe).

**Q — What happens if we do nothing?**
Position: the user keeps a timer they cannot trust (B1/B2) and a perf claim nobody can verify (R4). Those two are the ones that damage credibility; everything else is polish or growth.

**Q — Highest-leverage single move?**
Position: **Unit 2 (timer alert)** for trust and **Unit 1 (baseline)** for every later decision. Both are small.

**Scope mode (CEO review): SELECTIVE EXPANSION.** Hold the baseline of "fix what's broken and make it cheap", then take expansions one at a time, only where they are cheap to run at rest and reuse existing machinery. Committed for the rest of the plan.

**Approaches considered for the efficiency work**

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| A. Optimise by inspection (do the fixes now) | Fast | Repeats the repo's unmeasured claims; can't prove a win | Rejected |
| B. **Measure first, then fix, then re-measure** | Proves the win; catches regressions in CI | Costs a day up front | **Chosen** |
| C. Rewrite renderer (Compose/GL) | Fresh start | Ocean-sized, drops the working morph | Rejected |

**Gate 2→3:** the premise holds with reframes (B5 before tuning; measure before optimising). Requirements written to `dyna-upgrade-requirements.md`.

## Phase 3 — Define

See [`dyna-upgrade-requirements.md`](./docs/plans/dyna-upgrade-requirements.md). Gate 3→4: R1–R23, success criteria and scope boundaries exist.

## Phase 4 — Structure

### Step 4.0 — Ambiguities inventoried (and resolved or deferred)

| Trigger | Resolution |
|---|---|
| Alarm mechanism / permission | `setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM`; fall back to inexact when not granted, and tell the user |
| Where to persist the timer | Small `SharedPreferences` file (end wall-clock + paused remainder + label); DataStore is overkill for 4 fields and the repo already uses SharedPreferences in the listener |
| Boot re-arm | Manifest receiver disabled until a timer exists (`PackageManager.setComponentEnabledSetting`) |
| Perf measurement route | `dumpsys gfxinfo` + Perfetto with `android.os.Trace` sections — **Q1 spike first** |
| Test depth | JVM unit tests with injected clocks; no instrumentation tests in this plan (on-device checks use the existing `DEVICE_VERIFICATION.md` procedure style) |
| Rollout | Every behaviour change that users could notice ships behind a setting with a conservative default, except bug fixes |

### Dependency graph (a DAG)

```
U1 baseline+CI ──┬─> U4 tick-only ──┐
                 ├─> U5 frame clock ─┼─> U9 animation polish ─> U16 second activity
                 ├─> U6 draw diet ───┤
                 ├─> U7 settings I/O │
                 └─> U8 governor ────┘
U2 timer alert   (independent, ship first)
U3 media tick+paused ─> U11 seek
U10 gestures     (independent)
U12 avatars      (independent)      U13 live updates (independent)
U14 system cards (independent)      U15 export/diagnostics (independent)
U17 i18n         (last; touches many files mechanically)
```

### Implementation units

Quality bar applied to every unit: requirement trace, ≥ 3 test scenarios, ≤ 8 files, ≤ 2 new abstractions, planning-time unknowns classified.

---

### Unit 1: Performance baseline, trace points and CI

**Goal:** make every later claim measurable and keep it that way.
**Requirements trace:** R4, R21, R23
**Dependencies:** none
**Files:**
- `docs/perf/PERF_BUDGET.md` — budget table, the capture commands, and a results table to fill in
- `scripts/perf/capture.sh` — wraps `gfxinfo reset/framestats`, `meminfo`, `batterystats --reset`, a Perfetto config
- `scripts/perf/perfetto.cfg` — sched, gfx, view, `atrace` categories
- `SRC/overlay/IslandPillView.kt` — `Trace.beginSection("island.onDraw" | "island.advanceMotion")` (API 29+, free when no trace is running)
- `SRC/overlay/IslandOverlay.kt` — `Trace` around `apply()`
- `.github/workflows/ci.yml` — JDK 21, `./gradlew testDebugUnitTest assembleDebug lintDebug`
- `.github/dependabot.yml`
**Approach:** run protocols A–F from `DEVICE_VERIFICATION.md` plus four new ones: (1) 60 s idle, (2) 20 morphs, (3) 30-minute timer, screen on, (4) 30-minute media expanded. Record `framestats`, janky %, `GC_FOR_ALLOC` count, `batterystats` CPU, and skin temperature via `adb shell dumpsys thermalservice`. Commit results as the baseline. CI never runs perf; it only guards build and tests.
**Patterns:** the existing "Capture procedure" sections in `docs/DEVICE_VERIFICATION.md`.
**Test scenarios:**
- [ ] Happy path: CI builds and runs all existing JVM tests green.
- [ ] Nil input: capture script exits with a clear message when no device is attached.
- [ ] Error path: `gfxinfo` shows no frames for the overlay (Q1) → script falls back to the Perfetto route and says so.
- [ ] Edge case: `Trace` calls cost nothing when no trace is active (verified by the idle 60 s run showing 0 frames).
**Verification:** `docs/perf/PERF_BUDGET.md` contains measured baseline numbers for all four scenarios, committed.
**Planning-time unknowns:** Q1 (does gfxinfo see overlay windows?) — **Resolve Before Planning** (30-min spike). Skin-temperature capture method per device — Deferred to Planning.

---

### Unit 2: Timer alert, exact scheduling and persistence

**Goal:** a timer that always fires, even after doze, process death or reboot, and tells the user.
**Requirements trace:** R1, R2, R23 (no cold-wake receiver while idle)
**Dependencies:** none — **ship first**
**Files:**
- `SRC/service/timer/TimerModule.kt` — accept an injected `Clock` and `AlarmScheduler`; persist and restore; stop relying on `delay` for completion
- `SRC/service/timer/TimerAlarmReceiver.kt` — **new**, `exported=false`; on fire → `TimerModule.onAlarm()`, post the alert notification
- `SRC/service/timer/TimerStore.kt` — **new**, `SharedPreferences` (end epoch ms, paused remainder, label, paused flag)
- `app/src/main/AndroidManifest.xml` — `SCHEDULE_EXACT_ALARM`, `POST_NOTIFICATIONS`, alarm receiver, boot receiver `enabled="false"`
- `SRC/data/IslandSettings.kt` — `timerAlertVibrate` (default on), `timerAlertSound` (default off), `timerAlertHeadsUp` (default on)
- `SRC/ui/SettingsPages.kt` — alert options + custom-duration chips and the exact-alarm permission row
- `TEST/service/timer/TimerModuleTest.kt` — **new**
**Approach:**
1. `start/resume`: compute `endElapsed` **and** `endWall = System.currentTimeMillis() + remaining`; save to `TimerStore`; arm `AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, endElapsed, pi)` when `canScheduleExactAlarms()`, else `setAndAllowWhileIdle` and surface "Timer may fire a few minutes late — allow exact alarms".
2. `pause/stop/finish`: cancel the alarm, clear or update the store.
3. Alarm fires → receiver calls `goAsync()`, posts a notification on a new `IMPORTANCE_HIGH` channel "Timers" (vibration pattern, optional sound from the settings), then `TimerModule.finish()`; the island shows `TIME'S UP` if visible.
4. Replace `finish()`'s "wait for the screen" gate: the alert is delivered immediately and independently of the island; the 00:00 hold remains only a visual.
5. On process start or `onServiceConnected`, restore from `TimerStore`: if `endWall` has passed and nothing fired, fire now (late but not lost); otherwise re-publish with `endElapsed = now + (endWall − wallNow)`.
6. Boot: enable `BootReceiver` via `setComponentEnabledSetting` only while a timer is stored; it re-arms and disables itself when done. This keeps the repo's "no cold-waking manifest receiver" rule.
7. Keep the in-process 1 Hz ticker for the _visible_ countdown only; it no longer decides completion.
**Patterns:** pure-policy + thin-Android-shell split (`IslandIdlePolicy`, `NotificationGate`); `ChargingSource` for registering/unregistering receivers.
**Test scenarios:**
- [ ] Happy path: start 60 s with a fake clock → advance 60 s → alarm callback → state `finished`, notification requested once.
- [ ] Nil/empty: duration 0 or > 24 h is rejected without arming an alarm or writing the store.
- [ ] Error path: exact alarms not permitted (`SecurityException` or `canScheduleExactAlarms()==false`) → inexact alarm scheduled and a `delayedWarning` flag set.
- [ ] Edge case: process restored with `endWall` in the past → fires exactly once; restored in the future → re-publishes the right remaining time.
- [ ] Edge case: pause then resume re-arms with the new deadline; stop cancels the alarm and clears the store.
- [ ] Edge case: reboot with a stored timer re-arms; reboot with none leaves the boot receiver disabled.
**Verification:** run `adb shell dumpsys deviceidle force-idle`, start a 1-minute timer, lock the phone → vibration and heads-up within 2 s of the deadline, 20 of 20 times; `dumpsys alarm | grep nothingisland` shows the alarm while the timer runs and none afterwards.
**Planning-time unknowns:** default sound on/off (Q3) — Deferred to Planning (ship vibration-only default). Play policy for `SCHEDULE_EXACT_ALARM` (Q4) — Deferred. Whether the receiver needs a short foreground window to post a heads-up on Android 15 — **Resolve Before Planning** (small spike on device).

---

### Unit 3: Media — progress tick, paused card, resume

**Goal:** the progress row moves, and you can resume what you paused.
**Requirements trace:** R3, R12, R23
**Dependencies:** none
**Files:**
- `SRC/overlay/IslandPillView.kt` — schedule a repaint at each second boundary while expanded + playing + screen on; invalidate only the progress rectangle
- `SRC/service/media/MediaSource.kt` — publish a paused state for `keepPausedSec`, then clear
- `SRC/service/IslandContent.kt` — `Media.isPaused`
- `SRC/service/IslandStateHolder.kt` — expiry job for the paused card
- `SRC/data/IslandSettings.kt`, `SRC/ui/SettingsPages.kt` — `mediaKeepPausedSec` (0 = old behaviour; default 30)
- `TEST/service/media/MediaPublishPolicyTest.kt` — **new**, pure policy extracted from `publish()`
**Approach:** extract `MediaPublishPolicy.decide(state, keepPausedMs)` → `PublishPlaying | PublishPaused(until) | Clear`. In the view, a single `Runnable` posted with `postDelayed(1000 − pos % 1000)` (cancelled in `onHidden`, on collapse and on pause) calls `invalidate(progressRect)`. No frame loop, no rate hint. A paused card draws a play icon and a frozen bar, and uses no tick at all.
**Patterns:** `IslandRenderDemand.isMediaPositionTick` for dedupe; `IslandIdlePolicy` for a pure policy object; `TimerModule`'s boundary-aligned delay idea.
**Test scenarios:**
- [ ] Happy path: playing → paused publishes `PublishPaused`, then `Clear` after the hold.
- [ ] Nil/empty: `keepPausedSec = 0` preserves today's immediate clear.
- [ ] Error path: session destroyed while paused → cleared immediately and no stale card.
- [ ] Edge case: collapse, screen-off or `onHidden` cancels the tick (no wake-ups: verify in the 30-min media run).
- [ ] Edge case: duration 0 (live stream) shows elapsed only and does not divide by zero.
**Verification:** expanded card advances once per second on a player that emits no per-second callbacks; pausing leaves a resumable card for the configured time; `dumpsys gfxinfo` shows ≤ 1 frame/s while expanded and 0 collapsed.
**Planning-time unknowns:** whether `invalidate(rect)` is honoured for a fixed-envelope overlay surface — Deferred to Planning (fall back to full `invalidate()` at 1 Hz; still negligible).

---

### Unit 4: Tick-only render path for Timer and Clock

**Goal:** a running timer costs one repaint per second, not one layout per second.
**Requirements trace:** R6, R23
**Dependencies:** U1 (to prove the saving)
**Files:**
- `SRC/overlay/IslandRenderDemand.kt` — generalise to `isTickOnly(prev, next)` covering `Media` position, `Timer.remainingMs` and `ClockActivity.timeText`
- `SRC/overlay/IslandOverlay.kt` — in `render()`, for a tick-only change call `pill.applyTick(newEvent)` and return
- `SRC/overlay/IslandPillView.kt` — `applyTick` updates the stored content and invalidates; the expanded clock roll animation still triggers
- `SRC/service/IslandContent.kt` — no change if the equality `copy()` trick holds; add a helper otherwise
- `TEST/overlay/IslandRenderDemandTest.kt` — extend
**Approach:** compare with `copy()` neutralising only the ticking fields (the technique `isMediaPositionTick` already uses). Accessibility: announce the timer at 10 s milestones and on completion, not every second (bump `accessibilityRevision` only on structural change).
**Test scenarios:**
- [ ] Happy path: Timer with only `remainingMs` changed → tick-only; `apply()` not called (spy on overlay).
- [ ] Nil/empty: `null → Timer` and `Timer → null` are never tick-only.
- [ ] Error path: `isRunning` flips (pause) → **not** tick-only (needs a redraw and new controls).
- [ ] Edge case: expanded vs compact change in the same emission → full path.
- [ ] Edge case: Clock `timeText` change while expanded still starts the digit roll.
**Verification:** with a 25-minute timer running, Perfetto shows `island.apply` slices only on state changes, and the 30-minute timer run's main-thread time drops against the baseline.
**Planning-time unknowns:** none blocking.

---

### Unit 5: Frame-clock-correct motion stepping

**Goal:** identical wall-clock trajectory regardless of hitches or refresh rate.
**Requirements trace:** R8
**Dependencies:** U1
**Files:**
- `SRC/overlay/IslandPillView.kt` — `advanceMotion` uses the frame time; remove the 32 ms clamp
- `SRC/overlay/IslandMotion.kt` — `frameDelta(prevNs, nowNs, maxSeconds = 0.25f)` pure helper
- `TEST/overlay/IslandMotionTest.kt` — extend
**Approach:** use `getDrawingTime()` (the frame's start time, uptime ms) or a `Choreographer.FrameCallback.frameTimeNanos`, **consistently one clock** for every spring and animation in the view. First frame uses `dt = 0` (start at rest), so there is no phantom `1/120 s`. Keep a ceiling around 250 ms so a resumed-from-background frame does not teleport a spring.
**Test scenarios:**
- [ ] Happy path: stepping 16 ms × N equals one step of the summed `dt`, within 1e-4 (closed-form invariance).
- [ ] Nil/empty: `dt ≤ 0` leaves position and velocity unchanged (already guarded in `stepSpring`).
- [ ] Error path: a 120 ms hitch lands where wall-clock says it should, not where 32 ms of progress says.
- [ ] Edge case: a 5 s gap is capped at the ceiling and does not overshoot.
- [ ] Edge case: reversing mid-flight preserves velocity (existing test stays green).
**Verification:** record the width-progress timeline for the same expand with an injected 80 ms stall; the settle time differs from the no-stall run by less than one frame.
**Planning-time unknowns:** whether `getDrawingTime()` is reliable for views in `TYPE_ACCESSIBILITY_OVERLAY` windows — Deferred to Planning (fallback: `Choreographer`).

---

### Unit 6: Draw-path diet

**Goal:** no allocation and no avoidable offscreen buffer in a frame.
**Requirements trace:** R5
**Dependencies:** U1, U5
**Files:**
- `SRC/overlay/IslandPillView.kt` — reusable `RectF`s for the transport, bars, progress and satellite; rounded artwork through a cached `BitmapShader` + `drawRoundRect` instead of `clipPath`; alpha on paints/drawables in `drawContentLayer`
- `SRC/overlay/draw/IslandDrawScratch.kt` — **new**, small holder for the scratch rects and shader cache (one abstraction)
- `TEST/overlay/IslandDrawScratchTest.kt` — **new**
**Approach:** do **not** start by splitting the 2.6 k-line file; extract only what this unit touches. Replace `saveLayerAlpha` with a `Paint.alpha` multiplier for text/shapes and `Drawable.alpha` for icons; keep a layer only where a group needs a single alpha (artwork + overlay). Measure before and after (U1): `GC_FOR_ALLOC` count and janky % over 20 morphs.
**Test scenarios:**
- [ ] Happy path: scratch rect reuse returns the same instance and resets state between uses.
- [ ] Nil/empty: `drawArt` with a null bitmap uses the placeholder without allocating a shader.
- [ ] Error path: a recycled or replaced bitmap invalidates the cached shader (identity check).
- [ ] Edge case: alpha 0 and alpha 1 paths draw identically to the layered version (golden image or pixel probe).
**Verification:** Perfetto shows no `GC_FOR_ALLOC` across 20 morphs; `island.onDraw` p95 improves against the U1 baseline.
**Planning-time unknowns:** pixel-diff harness for custom `View`s on the JVM (Paparazzi vs. Robolectric native graphics) — Deferred to Planning.

---

### Unit 7: Settings write path

**Goal:** dragging a slider is cheap and live, and writes once.
**Requirements trace:** R7
**Dependencies:** U1
**Files:**
- `SRC/data/IslandSettings.kt` — `previewSettings: StateFlow<IslandSettings?>`; `commit()` writes once
- `SRC/MainActivity.kt` — route `onSettingsChange` to preview during drags and to `commit` on release
- `SRC/ui/HomeScreen.kt` — `SettingSlider` calls `onValueChangeFinished` to commit
- `SRC/service/IslandService.kt` — collect `settings.combine(preview)`
- `TEST/data/IslandSettingsTest.kt` — extend
**Approach:** during a drag only the in-memory preview emits (so the overlay stays live), and a single DataStore `edit` runs on release or after a 250 ms debounce for non-slider controls. Process death mid-drag loses at most the last uncommitted tick.
**Test scenarios:**
- [ ] Happy path: 50 drag ticks → 50 preview emissions, 1 DataStore write.
- [ ] Nil/empty: release with no change writes nothing.
- [ ] Error path: `IOException` on write keeps the preview and surfaces a non-blocking error.
- [ ] Edge case: two sliders edited back to back both commit, in order.
**Verification:** during a 5 s slider drag, `dumpsys diskstats` / strace shows ≤ 2 file writes; the overlay still tracks the thumb.
**Planning-time unknowns:** none.

---

### Unit 8: Motion governor (thermal, battery, frame-rate categories)

**Goal:** the island backs off before the phone gets warm.
**Requirements trace:** R9, R23
**Dependencies:** U1
**Files:**
- `SRC/service/PowerGovernor.kt` — **new**, `PowerManager.addThermalStatusListener` + Battery Saver + low-battery; exposes `MotionTier { FULL, BALANCED, MINIMAL }`
- `SRC/overlay/IslandRenderDemand.kt` — `motionRateHz` becomes `frameRateRequest(tier, …)` returning a category or Hz
- `SRC/overlay/IslandPillView.kt` — `setRequestedFrameRate(category)` on API 35; keep the numeric path as the fallback
- `SRC/data/IslandSettings.kt`, `SRC/ui/SettingsPages.kt` — "Smart motion" (auto / always full / always light)
- `SRC/service/charging/ChargingSource.kt` — hand its power-save signal to the governor
- `TEST/service/PowerGovernorTest.kt` — **new**
**Approach:** a pure table. `FULL` = HIGH category during morph, ambient allowed. `BALANCED` (thermal ≥ LIGHT, Battery Saver, battery < 20 %) = NORMAL category, no blur, shorter reveals. `MINIMAL` (thermal ≥ MODERATE) = NORMAL, no ambient loops, no satellite neck animation, single-layer fades. Re-evaluated on callbacks only — no polling.
**Test scenarios:**
- [ ] Happy path: thermal `NONE` + normal battery → `FULL`.
- [ ] Nil/empty: no thermal service → `FULL`, no crash.
- [ ] Error path: listener registration throws → governor stays `FULL` and logs once.
- [ ] Edge case: flapping thermal status is debounced (hysteresis) so the tier does not oscillate.
- [ ] Edge case: user override "always full" beats Battery Saver (but not `MINIMAL` at `SEVERE`).
**Verification:** with `adb shell cmd thermalservice override-status 3`, the next morph runs at the light tier and ambient loops stop; clearing the override restores `FULL`.
**Planning-time unknowns:** Q2 (ARR/category support on the target device) — Deferred to Planning; `hasArrSupport()` decides the path at runtime.

---

### Unit 9: Animation polish, interruptible morphs, reduced motion

**Goal:** a morph that feels continuous, can be grabbed mid-flight, and degrades gracefully.
**Requirements trace:** R10, R11, R8
**Dependencies:** U5, U6, U8
**Files:**
- `SRC/overlay/IslandMotion.kt` — tuned constants, easing helpers, press-spring
- `SRC/overlay/IslandPillView.kt` — accept touches during morph; staged content timing; press scale; fling-aware swipe; reduced-motion cross-fade
- `SRC/data/IslandSettings.kt`, `SRC/ui/SettingsPages.kt` — `reducedMotionMode` (follow system / always)
- `SRC/ui/MotionLabScreen.kt` — **new, debug builds only**: live sliders for ω/ζ, a replay button, and a frame-time readout
- `TEST/overlay/IslandMotionTest.kt` — extend
**Approach — the concrete tweak list** (every number is a **starting point to A/B in the Motion Lab**, not a conclusion):

| # | Change | Today | Proposal | Why |
|---|---|---|---|---|
| 1 | Content hand-over | expanded content starts at `p = 0.06` | start at ≈ 0.30–0.35 of the shell's _width_ progress, with a 24 ms title → subtitle stagger | content never appears inside a shell that has not yet made room for it |
| 2 | Fade easing | `smoothStep` | emphasized-decelerate `cubic-bezier(0.2, 0, 0, 1)` for fade-in, accelerate for fade-out | smoothStep has zero slope at both ends, which reads as a stall at the start of a fade |
| 3 | Width / height springs | ω 24 ζ 0.88 / ω 17 ζ 0.90 — **computed overshoot is only 0.30 % (width) and 0.15 % (height), effectively critically damped**, although the code comment calls them underdamped | A/B width ζ 0.70–0.75 (≈ 4.6 % / 2.8 % overshoot, settle ≈ 0.33–0.41 s) with ω 24–26; height ζ 0.85–0.90 (≤ 0.6 %) | ζ 0.80 only reaches 1.5 %, which is still invisible; a perceptible overshoot needs ζ ≈ 0.65–0.75. Overshoot is the "alive" part of the effect, and the window envelope already reserves room for it (verify that the envelope covers the chosen peak) |
| 4 | Alert arrival | same spring as expand | small volume-preserving squash (+3 % height as width leads) on notification arrival only | distinguishes "something arrived" from "you tapped" |
| 5 | Press feedback | none | 0.97 scale spring on down, release spring on up, `VIRTUAL_KEY` haptic on commit | tactile confirmation, and it works while morphing |
| 6 | Touch during morph | rejected | accepted; a tap reverses the target and keeps velocity | the maths already supports it |
| 7 | Swipe dismiss | 72 dp threshold only | add `VelocityTracker` fling (> ~800 dp/s dismisses), rubber-band past the threshold, add swipe-up | matches how every other island behaves |
| 8 | Reduced motion | animator scale 0 → snap | cross-fade 120 ms, no scale/translate | the accessibility norm is "no spatial motion", not "no transition" |
| 9 | Frame hint | 120 / 90 / 30 Hz numbers | HIGH category while a spring is active, NORMAL for fades and ambient (U8) | lets the panel idle between morphs |
| 10 | Curl into the lens | 420 ms, width leads | keep; add a 40 ms opacity ramp on the stroke so the outline does not outlast the fill | removes a stray hairline at the end of the curl |

**Patterns:** keep every new constant in `IslandMotion` with a unit test for its monotonicity/bounds, as the existing ones have.
**Test scenarios:**
- [ ] Happy path: the new fade curves are monotonic, hit 0 and 1 exactly, and have non-zero initial slope.
- [ ] Nil/empty: reduced-motion mode never produces a non-zero translate or scale.
- [ ] Error path: a touch during a reversing morph never double-fires a tap (down → morph reversal → up).
- [ ] Edge case: a fling at 1000 dp/s dismisses; a slow drag past 72 dp dismisses; a drag released at 40 dp springs back.
- [ ] Edge case: animator scale 0 or Battery-Saver `MINIMAL` tier still ends in the correct final state.
**Verification:** side-by-side screen recordings (before/after) at 120 Hz; the Motion Lab frame readout shows no dropped frame in 20 consecutive morphs; TalkBack and animator-scale-0 checks from the audit's QA list still pass.
**Planning-time unknowns:** the exact ω/ζ values — _Deferred to Planning_ (decided in the Motion Lab on the device, by eye, with the numbers recorded here afterwards).

---

### Unit 10: Gesture → action map

**Goal:** tap, double-tap, long-press and swipe up/down are configurable, including system actions.
**Requirements trace:** R14
**Dependencies:** none
**Files:**
- `SRC/data/IslandOptions.kt` — `IslandGesture` and `IslandAction` enums
- `SRC/data/IslandSettings.kt` — five persisted action keys (extends the existing tap and long-press)
- `SRC/overlay/IslandPillView.kt` — double-tap detection and swipe-up/down classification
- `SRC/overlay/IslandInteraction.kt` — `onAction(action)`
- `SRC/service/IslandService.kt` — execute actions: `performGlobalAction` for screenshot / lock screen / power dialog / recents / notification shade; torch via `CameraManager.setTorchMode`; play/pause; open app; expand; dismiss
- `SRC/ui/SettingsPages.kt` — gesture table in Behaviour
- `TEST/data/IslandOptionsTest.kt` — **new**
**Approach:** global actions are available to an `AccessibilityService` without extra permissions, which is a structural advantage of Dyna's architecture. Double-tap adds ~`ViewConfiguration.getDoubleTapTimeout()` latency to single-tap, so make the double-tap opt-in and keep single-tap instant when double-tap is `NONE`.
**Test scenarios:**
- [ ] Happy path: each gesture resolves to its configured action.
- [ ] Nil/empty: `NONE` produces no callback and no haptic.
- [ ] Error path: `performGlobalAction` returns false (e.g. screenshot blocked) → no crash, subtle feedback.
- [ ] Edge case: double-tap enabled delays single-tap by exactly the timeout; disabled keeps tap at 0 ms.
- [ ] Edge case: a swipe while expanded still dismisses a notification (existing behaviour wins).
**Verification:** configure double-tap → screenshot and swipe-down → torch; both work from the lock screen's allowed state and from a third-party app.
**Planning-time unknowns:** Play policy disclosure wording for global actions (Q4) — Deferred.

---

### Unit 11: Media seek

**Goal:** tap or drag the progress row to seek.
**Requirements trace:** R13
**Dependencies:** U3
**Files:**
- `SRC/overlay/IslandPillView.kt` — progress `Target(Seek)`, drag handling, thumb on press
- `SRC/overlay/IslandInteraction.kt`, `SRC/service/IslandService.kt` — `onSeek(fraction)`
- `SRC/service/media/MediaSource.kt` — `seekTo(positionMs)` guarded by `ACTION_SEEK_TO`
- `SRC/service/IslandContent.kt` — `Media.canSeek`
- `TEST/service/IslandContentTest.kt` — extend
**Test scenarios:**
- [ ] Happy path: dragging to 50 % of a 4-min track calls `seekTo(120_000)`.
- [ ] Nil/empty: `canSeek = false` or duration 0 → no target, no thumb.
- [ ] Error path: `seekTo` throws → bar snaps back to the true position on the next tick.
- [ ] Edge case: a drag leaving the bar keeps tracking; a cancel does not seek.
**Verification:** seeking works in two players; the position stamp updates immediately (optimistic), then reconciles with the next callback.
**Planning-time unknowns:** whether to debounce `seekTo` while dragging (commit on release only) — Deferred to Planning (default: commit on release).

---

### Unit 12: Avatars and call detail

**Goal:** see who a message or call is from.
**Requirements trace:** R18
**Dependencies:** none
**Files:**
- `SRC/service/notification/NotificationSource.kt` — resolve `Notification.EXTRA_LARGE_ICON` / `MessagingStyle` sender `Person.icon`; chronometer for calls
- `SRC/service/IslandContent.kt` — `avatar: Drawable?` (bounded) on `Notification` and `Call`; `Call.connectedAtElapsedMs`
- `SRC/overlay/IslandPillView.kt` — circular avatar with a small app-icon badge; call duration text
- `SRC/data/IslandSettings.kt` + `SRC/ui/SettingsPages.kt` — "Show sender avatar" (default on, off when redacted)
- `TEST/service/notification/AvatarPolicyTest.kt` — **new**
**Approach:** respect redaction: on a locked keyguard with a non-public notification, show the app icon only. Downscale avatars once (≤ 96 px), keep them in the existing LRU, and never retain the full-size bitmap (the repo's rule for artwork).
**Test scenarios:**
- [ ] Happy path: a `MessagingStyle` sender icon is used before the large icon.
- [ ] Nil/empty: no avatar → falls back to the app icon without a gap in layout.
- [ ] Error path: `loadDrawable` throws → fallback, no crash (`runCatching`, as elsewhere).
- [ ] Edge case: redacted content never exposes an avatar.
**Verification:** a WhatsApp or Telegram message shows the sender's photo; RAM does not climb across 50 messages (`meminfo`, protocol F).
**Planning-time unknowns:** none blocking.

---

### Unit 13: Android 16 Live Updates and optional progress ring

**Goal:** surface what Android 16 apps already publish.
**Requirements trace:** R17
**Dependencies:** none
**Files:**
- `SRC/service/notification/NotificationSource.kt` — read `android.shortCriticalText` (string key, any SDK), `android.progressSegments` (guarded)
- `SRC/service/IslandContent.kt` — `Download.shortText`, `Download.progressFraction`
- `SRC/overlay/IslandPillView.kt` — compact text and an **opt-in** progress ring
- `SRC/data/IslandSettings.kt`, `SRC/ui/SettingsPages.kt` — "Progress ring on downloads" (default off, preserving the README's deliberate "no percentage")
- `TEST/service/notification/LiveUpdateParserTest.kt` — **new**
**Test scenarios:**
- [ ] Happy path: extras with short critical text show it in the compact pill.
- [ ] Nil/empty: missing or blank text falls back to the title.
- [ ] Error path: malformed segments bundle is ignored, no crash.
- [ ] Edge case: indeterminate progress (max 0) draws no ring.
**Verification:** a ride/delivery app on an Android 16 device shows the chip text on the island.
**Planning-time unknowns:** Q5 (extras stability across OEMs) — Resolve Before Planning (one real-device spike).

---

### Unit 14: System cards — ringer, flashlight, next alarm

**Goal:** the three cheapest "system" cards, all event-driven.
**Requirements trace:** R15 (R16 Bluetooth follows)
**Dependencies:** none
**Files:**
- `SRC/service/system/SystemEventsSource.kt` — **new**: `AudioManager.RINGER_MODE_CHANGED_ACTION`, `CameraManager.TorchCallback`, `AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED`
- `SRC/service/IslandContent.kt` — `Ringer`, `Torch`, `NextAlarm`
- `SRC/service/IslandStateHolder.kt` — new transient slots, below calls and above media
- `SRC/overlay/IslandPillView.kt`, `SRC/overlay/IslandLayout.kt` — compact and expanded drawing
- `SRC/data/IslandSettings.kt` + `SRC/ui/SettingsPages.kt` — one switch per card
- `TEST/service/system/SystemEventsPolicyTest.kt` — **new**
**Approach:** register receivers only while the screen is active (the pattern `ChargingSource` uses), and unregister on screen-off. No polling. This is more than the 2-new-abstraction guideline allows if built in one go, so **split into 14a (framework + ringer) and 14b (torch + alarm)** at implementation time.
**Test scenarios:**
- [ ] Happy path: ringer change posts a 2 s transient card.
- [ ] Nil/empty: no flashlight hardware → torch card disabled, no crash.
- [ ] Error path: receiver registration fails → logged once, source stays off.
- [ ] Edge case: screen-off unregisters; screen-on re-registers once (no duplicate callbacks).
**Verification:** flipping the ringer mode or the torch shows a card; idle run still shows 0 frames.
**Planning-time unknowns:** priority order of the new transients versus charging — Deferred to Planning.

---

### Unit 15: Settings export/import and diagnostics bundle

**Goal:** move a calibration between phones and share a bug report without `adb`.
**Requirements trace:** R20
**Dependencies:** none
**Files:**
- `SRC/data/IslandSettings.kt` — `toJson()` / `fromJson()` with schema version and clamping
- `SRC/util/DiagnosticsExporter.kt` — **new**: redacted bundle (versions, grants, settings, last N log lines)
- `SRC/MainActivity.kt` — SAF `CreateDocument` / `OpenDocument` launchers
- `SRC/ui/SettingsPages.kt` — Advanced page buttons
- `TEST/data/IslandSettingsJsonTest.kt` — **new**
**Test scenarios:**
- [ ] Happy path: export → import round-trips every field.
- [ ] Nil/empty: an empty or `{}` file imports as defaults, without an error dialog.
- [ ] Error path: malformed JSON is rejected, current settings are untouched.
- [ ] Edge case: out-of-range values are clamped by `coerced()`; unknown keys are ignored (forward compatibility).
- [ ] Edge case: the bundle contains no notification text, package names of the user's chats, or keys (matches the repo's logging rule).
**Verification:** a calibration exported from one phone applies on another; the bundle opens as plain text.
**Planning-time unknowns:** none.

---

### Unit 16: Second activity in the satellite circle (spike, then build)

**Goal:** show a second live activity (e.g. timer ring while music plays) in the detached circle.
**Requirements trace:** R19
**Dependencies:** U4, U6, U9, U14 (needs a generalised slot model)
**Files:**
- `SRC/service/IslandStateHolder.kt` — expose `secondary: IslandEvent?`
- `SRC/service/IslandContent.kt` — `IslandEvent.secondary`
- `SRC/overlay/IslandIconPlacement.kt` — decide what the circle shows
- `SRC/overlay/IslandPillView.kt` — draw a minimal glyph (timer ring, play bars) in the satellite
- `TEST/overlay/IslandIconPlacementTest.kt`, `TEST/service/IslandStateHolderTest.kt` — extend
**Approach:** a one-week spike first. This is where the plan risks an "ocean" — build only the _glyph in the existing circle_, **not** a second independent shell. The mitosis machinery, window reservation and touch targets already exist. Ship only if the spike keeps the idle frame count at zero.
**Test scenarios:**
- [ ] Happy path: timer primary + media secondary → circle shows the media glyph.
- [ ] Nil/empty: no secondary → today's app-icon bubble behaviour.
- [ ] Error path: the secondary ends mid-morph → circle retracts without a stuck icon.
- [ ] Edge case: an edge-anchored wide pill with no room omits the circle, as today.
**Verification:** idle and settled-state frame counts remain 0; no regression in the detach/re-attach motion.
**Planning-time unknowns:** which pairs are meaningful (timer + media, navigation + media, call + anything) — Deferred to Planning.

---

### Unit 17: String extraction and localisation

**Goal:** all user-facing text in resources.
**Requirements trace:** R22
**Dependencies:** all other units (avoids merge churn)
**Files:** `app/src/main/res/values/strings.xml` plus the Kotlin sites; a lint rule (`HardcodedText`) in CI. This is mechanical and touches many files; split by package at implementation time.
**Test scenarios:**
- [ ] Happy path: lint passes with zero hard-coded strings in `ui/`.
- [ ] Edge case: pseudo-locale (`en-XA`) renders without clipping in the island's expanded card.
- [ ] Edge case: RTL pseudo-locale (`ar-XB`) mirrors the compact lens-flank layout correctly.
**Verification:** the app launches in two non-English locales without clipped controls.
**Planning-time unknowns:** which locales — Deferred (community-driven).

### Quality Bar Checklist

- [x] Every unit has a requirements trace
- [x] Dependencies form a DAG (see the graph)
- [x] Every unit has ≥ 3 test scenarios
- [x] No unit touches more than 8 files (U14 and U17 explicitly split at implementation)
- [x] ≤ 2 new abstractions per unit (U2: `TimerStore` + receiver; U8: `PowerGovernor` + tier enum; U6: one scratch holder)
- [x] Every planning-time unknown is classified as a blocker or deferred
- [x] Handoff completeness test: what would an engineer still have to invent? Only the animation constants (deliberately decided on-device) and the small spikes listed as "Resolve Before Planning".

**Gate 4→5:** passed.

## Phase 5 — Validate

### Always-on review

**Coherence.** Two contradictions found and fixed in this document: (1) an early draft proposed `USE_EXACT_ALARM`, which contradicted the policy research (the timer is a secondary feature) → now `SCHEDULE_EXACT_ALARM` with a fallback; (2) an early draft proposed `setFrameContentVelocity` to adapt the refresh rate, which the platform docs say has no effect on non-scrollable views → replaced by frame-rate categories. Terminology is consistent (`tick-only`, `tier`, `paused card`).

**Feasibility — shadow-path trace of the new data flows**

| Flow | Happy | Nil | Empty | Upstream error |
|---|---|---|---|---|
| Timer alarm | exact alarm → notification | no timer stored → boot receiver stays disabled | duration 0 rejected | `SecurityException` → inexact + visible warning |
| Timer restore | store → re-publish | nothing stored → no-op | stale store older than 24 h → discard | corrupt prefs → cleared, logged |
| Paused media card | pause → hold → clear | no `keepPausedSec` → old behaviour | metadata empty → clear | session destroyed → immediate clear |
| Progress tick | 1 Hz while expanded | collapsed → cancelled | duration 0 → elapsed only | detached view → callbacks removed in `onHidden` |
| Governor | thermal callback → tier | no thermal service → FULL | n/a | registration throws → FULL + log |
| Settings preview | drag → preview → commit | no change → no write | defaults | `IOException` on write → preview kept |
| Avatar | Person icon → circular | no icon → app icon | redacted → app icon | `loadDrawable` throws → fallback |

### CEO review — SELECTIVE EXPANSION (committed)

- **Premise:** valid, with the two reframes from Phase 2.
- **Prime Directives:** (1) zero silent failures — B1 is literally a silent failure; Unit 2 also surfaces the "inexact alarm" fallback to the user. (2) Every error has a name — each unit lists the specific exception path. (3) Data flows have shadow paths — table above. (4) Interactions have edge cases — B8, double-tap latency, swipe-vs-fling. (5) Observability is scope — Unit 1 plus `Trace` points plus the diagnostics bundle (Unit 15). (6) Everything deferred is written down — see "Deferred" below.
- **Expansions accepted individually:** gestures (cheap, structural advantage), system cards (cheap, event-driven), Live Updates text (cheap, standards-aligned). **Rejected for now:** headset battery % (hidden API), camera/mic privacy dots (no public API), Shizuku (goes against the "no extra grants" promise), Bluetooth card (moved after U14).
- **Permission to scrap:** if Unit 1's baseline shows the overlay already holds a flat 120 Hz cadence with no GC, Units 5 and 6 shrink to a code-quality pass and the animation work becomes pure taste.

### Design review (0–10, current state → after plan)

| Dimension | Now | Fix-to-10 (the exact change) |
|---|---:|---|
| Clarity | 7 | Add a one-line "what the island is showing and why" in Diagnostics ("Source: Media, priority 4 of 9") |
| Hierarchy | 7 | Timer end should outrank everything except calls for 4 s; today a covering alert can hide it (the 30 s visibility timeout is a workaround) |
| Consistency | 8 | Unify press feedback (U9 item 5) across body, transport and timer controls |
| Feedback | **5** | Timer end: vibration + notification (U2). Paused media: resumable card (U3). Frozen progress bar fixed (U3) |
| Error recovery | **5** | "Timer may be late — allow exact alarms" row; "Why is my island missing?" troubleshooter reading the existing Diagnostics state |
| Empty states | 6 | The first-run Setup page exists; add an explicit "nothing to show — this is normal" state in Diagnostics |
| Accessibility | 7 | Timer announced at milestones, not every second (U4); reduced-motion cross-fade (U9) |

### Engineering review

**1. Scope challenge.** What already solves part of this: the media-tick dedupe (extend for U4), the idle policy (pattern for U3), `ChargingSource` receiver handling (pattern for U14), and `IslandMotion` (home for U9 constants). Smallest change set for the P0s: Unit 2 alone. Framework search: `AlarmManager`, `PowerManager` thermal callbacks, `View.setRequestedFrameRate` categories and `performGlobalAction` are all platform built-ins, so **no new dependencies** are required anywhere in this plan.

**2. Architecture.**
- Coupling: `IslandPillView.kt` is 2,643 lines — a renderer, hit-tester, gesture handler and animation driver in one class. Splitting is tempting; **don't do it as a prerequisite**. Extract only what each unit touches (U6 `IslandDrawScratch`), then reassess after U9.
- `IslandStateHolder` and `TimerModule` are process-global `object`s on `Dispatchers.Main.immediate` with `@Synchronized` methods. That works because every caller is main-thread. U2's injected clock/scheduler is the first step toward testability; do not turn these into DI-managed classes in this plan.
- Failure characteristics: the service is bound by the OS; a crash restarts it and `clearTransient()` + `postTimer(...)` already rehydrate. U2 extends that to a process death.

**3. Code quality.** DRY: `drawPlayingBars`, transport buttons and satellite icons repeat the "create `RectF` then draw" pattern (B9) — U6 collapses it. Error handling is generally `runCatching { … }.onFailure { Log.w }`, which is consistent; U2/U8 follow it, and add one **user-visible** surface where silence would hide a failure. Over/under-engineering: the README is long, and some of its claims duplicate what tests prove — keep the claims that U1 verifies, trim the rest as measured numbers arrive.

**4. Test review — coverage diagram**

```
Layout / geometry ........ covered   (IslandLayoutTest, HoleGeometry via settings)
Spring math .............. covered   (IslandMotionTest)  -> U5 extends
Idle policy .............. covered   (IslandIdlePolicyTest)
Visibility policy ........ covered   (IslandVisibilityTest)
Notification routing ..... covered   (NotificationGateTest, ClockParserTest)
State holder ............. covered   (IslandStateHolderTest)
Settings clamp/persist ... covered   (IslandSettingsTest, ...CategoryTest)
TimerModule .............. NOT COVERED  -> U2 adds TimerModuleTest   (home of B1/B2)
MediaSource publish ...... NOT COVERED  -> U3 adds MediaPublishPolicyTest (B4)
ChargingSource ........... NOT COVERED  -> out of plan (no known bug)
IslandPillView drawing ... NOT COVERED  -> U6 adds a pixel probe / golden test
IslandOverlay window mgmt  NOT COVERED  -> verified on device only (DEVICE_VERIFICATION.md)
```

**Gate 5→6:** no unresolved P0 findings in the _plan_. P1 items accepted with rationale: Q1/Q2 spikes and the exact animation constants are decided by measurement and by eye, not on paper.

## Phase 6 — Deepen

**Confidence-gap scoring** (+1 per gap or question, +2 per blocking unknown, +3 per critical path flagged in Phase 5):

| Section | Gaps | Blocking | Critical | Score | Deepened? |
|---|---:|---:|---:|---:|---|
| Unit 2 timer scheduling | 3 | 1 | 3 | **9** | **Yes** — exact-alarm policy researched, see below |
| Unit 1 baseline route | 2 | 1 | 3 | **8** | Partly — spike defined (Q1) |
| Unit 8 governor / frame-rate API | 2 | 0 | 3 | **5** | **Yes** — API behaviour researched |
| Unit 13 Live Updates | 2 | 1 | 0 | 4 | **Yes** — extras names researched |
| Unit 9 animation constants | 2 | 0 | 0 | 2 | No — taste, decided on device |

**What the deepening changed (named, not silent):**
1. _Unit 2:_ `USE_EXACT_ALARM` → `SCHEDULE_EXACT_ALARM`. The platform docs and Play policy limit `USE_EXACT_ALARM` to apps whose core function is an alarm or timer; Dyna's timer is a secondary feature. Options kept on the table: `setAlarmClock` needs no permission but adds a status-bar alarm icon and a "next alarm" entry.
2. _Unit 8 / 9:_ `setFrameContentVelocity` dropped (documented to have no effect on non-scrollable views). `View.setRequestedFrameRate` with `REQUESTED_FRAME_RATE_CATEGORY_NORMAL | HIGH` is the supported path; adaptive refresh needs HAL support on Android 15 QPR1+, hence `hasArrSupport()` at runtime and the numeric fallback.
3. _Unit 13:_ `android.shortCriticalText` and `android.progressSegments` are plain string extras, so the app can read them on any SDK without raising `compileSdk`. The Android 16 promotion setter itself (`requestPromotedOngoing`) is a poster-side API and does not matter for a reader.

**Gate 6→7:** passed. No new blocking unknown was introduced.

## Phase 7 — Hand Off

### Produced this session

- `docs/plans/dyna-upgrade-requirements.md` — R1–R23, success criteria, decisions, open questions
- `docs/plans/dyna-upgrade-plan.md` — this file: findings B1–B17, 17 implementation units, reviews
- `docs/sessions/2026-11-10-dyna-upgrade-planning.md` — Q&A log with the assumptions made
- A browsable version of all of the above in this project's web app (tabs: Overview, Bugs, Features, Efficiency, Motion Lab, Roadmap, Review)

### Recommended order (waves)

| Wave | Units | Outcome |
|---|---|---|
| 0 (days 0–2) | **U2**, **U1** | A timer you can trust, and a measuring stick |
| 1 (week 1) | U3, U4, U7 | Media that moves and resumes; cheap running timers; cheap settings |
| 2 (week 2) | U5, U6, U8 | Correct frame clock, allocation-free draw, thermal governor — re-measure against U1 |
| 3 (week 3) | U9, U10 | Animation polish with numbers from the Motion Lab; gestures |
| 4 (weeks 4–5) | U11, U12, U13, U14, U15 | Seek, avatars, Live Updates, system cards, export/diagnostics |
| 5 (later) | U16 spike, U17 | Second activity, localisation |

### Deferred (written down, per Prime Directive 6)

Bluetooth connect card · headset battery % (hidden API) · camera/mic privacy dots (no public API) · Shizuku module · foldable inner display · lyrics (needs a lyrics source, which means network) · baseline profile for the Compose settings app · splitting `IslandPillView.kt`.

### Outstanding questions that block implementation

- **Q1** gfxinfo vs. overlay windows — blocks Unit 1's capture route.
- **Q2** ARR/category support on the target phone — informs Unit 8's runtime path.
- **Q3** timer sound default — blocks Unit 2's default only (ship vibration-only).

### Closing recommendation

Start with **Unit 2 today**. It fixes the only P0s, needs no spike beyond one small on-device check, and is independent of everything else. Run **Unit 1 in parallel** so that Wave 2's efficiency claims are measured, not argued. Hold the animation constants until U5 and U6 land. Tuning springs on a loop that clamps `dt` and allocates per frame would tune around the bugs.

# Dyna (Nothing Island) Upgrade — Requirements

**Version:** 1.0
**Status:** Draft — ready for review
**Date:** 2026-11-10
**Mode:** New Feature (existing codebase) · **Scope:** Deep · **CEO scope mode:** SELECTIVE EXPANSION
**Target repo:** https://github.com/ZenithFr/Dyna (Kotlin + Compose, `AccessibilityService` overlay, minSdk 31, target 35)
**Evidence standard:** every finding below comes from reading the source at `master` (clone depth 1). **Nothing was run on a device.** Anything marked _hypothesis_ must be measured before it is treated as fact.

---

## Problem Frame

Dyna already has the hard parts of a Dynamic Island clone: a closed-form spring, an idle state machine that detaches the surface, event-driven media/notification/battery sources and privacy redaction. The README and `docs/DEVICE_VERIFICATION.md` are candid that **frame cost, battery soak, and RAM have never been measured**, so the "no heat, no drain" claim is currently an argument, not a result.

The review found three kinds of problem.

1. **Trust bugs.** A timer that finishes silently and can finish late or never. A media progress bar that freezes. A "play" button that can't be reached because the island disappears the moment you pause.
2. **Hidden steady-state cost.** Work that runs while the island is "idle" or showing a calm card: a full re-layout every second for timers and the clock, a settings write per slider tick, and per-frame allocations and offscreen layers during every morph.
3. **Feature gaps against comparable apps.** Seek and lyrics, avatars, an alert on timer end, gesture → action mapping, flashlight and ringer cards, and Android 16 Live Updates text.

The goal is **not** to add every competitor feature. It is to make what exists trustworthy and cheap, then add the few features that users of this category expect.

## Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| R1 | A finished timer must produce an alert the user can perceive with the screen off (vibration, sound, heads-up notification) | Must Have | Today `TimerModule.finish()` only publishes `00:00`. Grep for `Vibrator`, `AlarmManager`, `Ringtone` over `app/src/main` returns nothing. |
| R2 | Timer completion must survive doze, process death and reboot | Must Have | `delay()` on `Dispatchers.Main` is uptime-based; the end stamp is in-memory only. |
| R3 | The expanded media progress row must advance while playing, without a per-frame loop | Must Have | Position is evaluated only inside `onDraw`; nothing schedules a redraw while expanded. |
| R4 | Establish a measured performance baseline and budget before any optimisation ships | Must Have | Repo's own verification log lists frame cost, battery and RAM as "not measured". |
| R5 | Remove per-frame heap allocations and avoidable offscreen layers from the draw path | Must Have | `RectF(...)` in draw helpers, `clipPath` per art frame, two `saveLayerAlpha` per morph frame. |
| R6 | Timer/Clock second-ticks must repaint only, not re-run window layout | Must Have | `IslandRenderDemand.isMediaPositionTick` dedupes media only. |
| R7 | Settings persistence must be debounced; live preview must not depend on a disk write | Should Have | `SettingSlider` → `repository.update` on every drag tick. |
| R8 | Animation stepping must be correct against the real frame clock, including hitches | Must Have | `advanceMotion` clamps `dt` to 3–32 ms and samples `elapsedRealtime` in `onDraw`. |
| R9 | A thermal- and battery-aware motion governor | Should Have | Direct answer to "no heat issues": degrade motion before the phone warms up. |
| R10 | Morphs must be interruptible by touch | Should Have | `onTouchEvent` returns false while `isMorphing()`. |
| R11 | A reduced-motion mode that cross-fades instead of snapping | Should Have | Animator scale 0 currently snaps with no spatial motion and no fade. |
| R12 | Keep a paused media card for a configurable time; allow resume from the island | Should Have | `publish()` clears media on pause, so the play branch is effectively dead code. |
| R13 | Media seek (tap/drag the progress row) | Should Have | Present in competing apps. |
| R14 | Configurable gesture → action map (tap, double-tap, long-press, swipe up/down) | Should Have | README roadmap item 3. `performGlobalAction` gives screenshot/lock/power-dialog for free. |
| R15 | System cards: ringer mode, flashlight, next alarm | Should Have | All use public APIs. |
| R16 | Bluetooth connect/disconnect card | Nice to Have | Battery % for headsets has no public API; connect/disconnect only. |
| R17 | Android 16 Live Updates: read `android.shortCriticalText` and progress segments; optional progress ring | Should Have | The string key is readable on any SDK, so no `compileSdk` bump is needed. |
| R18 | Contact/sender avatar with app badge on message alerts; call avatar + duration | Should Have | README roadmap item 1. |
| R19 | Second simultaneous activity shown in the satellite circle | Nice to Have | Reuses mitosis machinery; README roadmap item 2. |
| R20 | Settings export/import (JSON) and a redacted diagnostics bundle | Should Have | Backup excludes DataStore on purpose, so there is no other way to move a calibration. |
| R21 | CI: build, unit tests, lint on every push; signed release artifact on tag | Must Have | No `.github/` directory exists. |
| R22 | String extraction and localisation | Nice to Have | `strings.xml` has 4 entries; user-visible text is hard-coded in Kotlin. |
| R23 | Protect existing strengths: zero frames at rest, no polling, fail-open fullscreen probe, redaction | Must Have | Regression guard on every unit. |

## Success Criteria

Targets marked _H_ are **hypotheses**; replace them with the numbers from Unit 1's baseline before sign-off.

- A 1-minute timer started, then the phone locked, vibrates and posts a heads-up within 2 s of the deadline in 20 of 20 trials, including one under `adb shell dumpsys deviceidle force-idle`. A reboot mid-timer re-arms it.
- Expanded media progress advances once a second with no additional wake-ups when the card is collapsed or the screen is off.
- `adb shell dumpsys gfxinfo com.zenith.nothingisland framestats`: **0 new frames** over 60 s with the island idle, dormant, or showing a settled calm card (media, timer collapsed). _Already claimed by the repo — Unit 1 verifies it._
- Morph (expand / retract / curl) janky-frame share ≤ 2 % _H_ on the CMF Phone 2 Pro at 120 Hz; no `GC_FOR_ALLOC` in a 20-morph Perfetto trace _H_.
- A 30-minute running timer (screen on, island visible) shows no skin-temperature rise attributable to the app _H_ and ≥ 50 % fewer main-thread layout passes than baseline _H_.
- Under `THERMAL_STATUS_MODERATE` or Battery Saver, motion drops to the BALANCED tier within 1 s and ambient loops stop.
- CI is green on `master`; every unit lands with its listed tests.

## Scope Boundaries

**In scope**
- Correctness fixes B1–B8 (see plan), the efficiency work (R4–R9), the animation pass (R8, R10, R11), and the selected feature gaps (R12–R20).
- Documentation, perf scripts and CI.

**Out of scope**
- A Shizuku module, Glyph integration, camera/mic privacy dots (no public API), screen-recording indicator (no public API), headset battery % (hidden API only).
- A network feature of any kind (the "no cloud, no telemetry" promise stays).
- Rewriting the renderer in Compose or OpenGL.
- Foldable inner-display support (single-lens model); revisit after R19.

## Key Decisions

| Decision | Chosen | Rationale | Alternatives Considered |
|----------|--------|-----------|-------------------------|
| Order of work | Measure → trust bugs → efficiency → animation → features | Optimising without a baseline repeats the unmeasured claims in the README | Features first (more visible, but ships on an unmeasured base) |
| Timer alert mechanism | `AlarmManager.setExactAndAllowWhileIdle` + a high-importance notification channel | A notification is the sanctioned way to vibrate or sound with the screen off | Foreground service (heavy); `setAlarmClock` (no permission needed, but adds a status-bar alarm icon and a "next alarm" entry) |
| Exact-alarm permission | `SCHEDULE_EXACT_ALARM` with an inexact fallback | `USE_EXACT_ALARM` is for apps whose _core_ function is alarms/timers; Dyna's timer is secondary | `USE_EXACT_ALARM` (policy mismatch) |
| Per-second repaint | Tick-only path that calls `invalidate()` and skips `apply()` | Same idea as the existing media dedupe, extended to Timer/Clock | Move timers to a separate view |
| Refresh-rate hint | Frame-rate **categories** (`HIGH` while morphing, `NORMAL` for ambient) on API 35 | Lets the platform pick; hard-coded 120/90/30 ignores panel and thermal policy. `setFrameContentVelocity` is documented to have no effect on non-scrollable views | Keep numeric Hz; velocity API |
| Boot receiver | Declared in the manifest but **disabled** until a timer is armed | Honours the repo's "no manifest receiver that cold-wakes the process" rule | Always-enabled `BOOT_COMPLETED` |
| Live Updates text | Read the `android.shortCriticalText` string extra | Works without a `compileSdk` bump | Bump to 36 |

## Outstanding Questions

| # | Question | Impact if Wrong | Owner |
|---|----------|-----------------|-------|
| Q1 | Does `dumpsys gfxinfo <pkg> framestats` include frames for `TYPE_ACCESSIBILITY_OVERLAY` windows? If not, a `Trace` + Perfetto route is needed. | The perf baseline (Unit 1) can't be captured as written | Maintainer, 30-min spike |
| Q2 | Does the CMF Phone 2 Pro honour `REQUESTED_FRAME_RATE_CATEGORY_*` (needs ARR HAL support, Android 15 QPR1+)? | Falls back to numeric Hz; Unit 8 still works but with less benefit | Maintainer, `hasArrSupport()` |
| Q3 | Do users want the timer alert sound on by default, or vibration only? | Surprise noise in meetings vs. a missed timer | Product decision |
| Q4 | Is Play Store distribution a goal? | Accessibility-service policy, `SCHEDULE_EXACT_ALARM` declaration, and gesture global actions all need a disclosure review | Maintainer |
| Q5 | Is `Notification.EXTRA_PROGRESS_SEGMENTS` stable across OEM Android 16 builds? | R17 progress ring may need per-OEM guards | Spike on a real Android 16 device |

# Session Q&A Log: Dyna upgrade planning

**Date:** 2026-11-10
**Mode:** New Feature
**Scope:** Deep
**Phases completed:** 0, 1, 2, 3, 4, 5, 6, 7
**Outcome:** Requirements document + 17-unit implementation plan produced. Session ran in escape-hatch posture: the user's standing instruction was to proceed without asking, so no blocking question was put to the user. Every assumption is recorded below with the evidence that would overturn it.

---

## Questions & Answers by Phase

### Phase 0: Intake & Route

**Q:** Is this a new product, a brainstorm, or a feature on an existing codebase?
**User:** Gave a repo URL and asked for analysis, bugs, missing features, efficiency, animation and "any other upgrade".
**AI position:** New Feature mode. A 13 k-line Kotlin codebase exists. Scope is Deep because the user asked for a comprehensive plan.
**Resolved:** Mode and scope fixed. Prior maintainer docs (`audit-2026-11-08.md`, `FEATURE_PLAN.md`, `DEVICE_VERIFICATION.md`) are treated as baseline and not re-litigated.

### Phase 1: Ground

**Q:** What does the code actually do versus what the README claims?
**User:** (no answer needed — research phase)
**AI position:** Read the source, not just the README. The README claims "zero frames at rest" and "no polling". The code supports both for the idle path. It does not support them for running-activity states (timer/clock 1 Hz full `apply()`; frozen expanded media progress).
**Resolved:** Findings B1–B17 in the plan. Confidence labelled per finding.

**Q:** What do comparable apps ship?
**AI position:** Two search passes (Android Dynamic-Island clones; Android 16 Live Updates and Now Bar). The first-principles reading is that Dyna's moat is being the cheapest correct implementation, so features must be event-driven.
**Resolved:** Feature matrix and the "cost at rest" test for every feature.

### Phase 2: Challenge & Explore

**Q:** Is "make the animation smoother" the real problem?
**User:** (assumed yes)
**AI position:** Partly a proxy. Fix B5/B8/B9/B10 (time-step clamp, touch rejection, allocations, offscreen layers) before tuning ω/ζ. _Evidence that would change this:_ a baseline trace showing a clean 120 Hz cadence during morphs.
**Resolved:** Animation work ordered after the efficiency units.

**Q:** Where does the always-on cost really live?
**AI position:** Not in animation (bursty) but in running-activity states and any opt-in ambient loop. _Evidence that would change this:_ batterystats showing meaningful idle CPU, which would send the investigation to the accessibility binding and the inset probe.
**Resolved:** Unit 1 measures first; Unit 4 removes the per-second layout.

**Q:** Scope mode?
**AI position:** SELECTIVE EXPANSION. Baseline is "fix and make cheap"; each feature is accepted individually.
**Resolved:** Committed. Rejected: Shizuku, hidden-API headset battery, privacy dots.

### Phase 4: Structure

**Q:** Which ambiguities would an engineer have to invent?
**AI position:** Alarm mechanism, persistence format, boot re-arm, measurement route, test depth, rollout. All resolved or classified in plan §4.0.
**Resolved:** See "Key Decisions".

### Phase 6: Deepen

**Q:** Which plan sections are weakest?
**AI position:** Scored: Unit 2 (9), Unit 1 (8), Unit 8 (5), Unit 13 (4). Researched exact-alarm policy, frame-rate APIs and Live Update extras.
**Resolved:** Two plan errors were found and corrected openly (see the pushback table).

---

## Key Decisions

| Decision | Chosen | Phase | Rationale |
|---|---|---|---|
| Work order | Measure → trust bugs → efficiency → animation → features | 2 | Don't optimise on an unmeasured base |
| Exact alarm permission | `SCHEDULE_EXACT_ALARM` + inexact fallback | 6 | `USE_EXACT_ALARM` is for apps whose core function is alarms/timers |
| Refresh-rate control | Frame-rate categories (API 35) with numeric fallback | 6 | `setFrameContentVelocity` has no effect on non-scrollable views |
| Boot receiver | Manifest-declared, disabled until a timer exists | 4 | Keeps the repo's "no cold-waking receiver" rule |
| Live Updates text | Read `android.shortCriticalText` extra as a string | 6 | No `compileSdk` bump |
| Refactor of the 2.6 k-line view | Not a prerequisite | 5 | Extract only what each unit touches |

## Positions Where AI Pushed Back

| Topic | AI Position | User Response | Final Resolution |
|---|---|---|---|
| Starting with animation tuning | Tune after fixing the time-step and allocation bugs, or you tune around them | none (no interaction) | Animation constants deferred to the Motion Lab in Unit 9 |
| "Timer shows 00:00 so it works" | A visual-only finish is a silent failure | none | Unit 2 makes the alert independent of island visibility |
| Competitor parity as a goal | Only add what is cheap at rest | none | Selective expansion; five items rejected |

## Assumptions made without asking (with the evidence that would overturn each)

1. **Vibration-only is the right default for the timer alert.** → Overturned if the maintainer or users want sound by default (Q3).
2. **The target is the CMF Phone 2 Pro / Nothing OS on Android 15.** → Overturned if Android 16 devices are a priority (raises Unit 13).
3. **Sideloaded GitHub distribution, not Play.** → Overturned if Play is a goal (Q4): permission and accessibility-policy disclosures become a unit.
4. **The README's "zero frames at rest" is true.** → Unit 1 checks it; the Run 1 window dump supports it.
