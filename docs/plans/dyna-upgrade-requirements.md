# Dyna (Nothing Island) Upgrade — Requirements

**Version:** 1.0
**Status:** Draft — ready for review
**Date:** 2026-11-10
**Mode:** New Feature (existing codebase) · **Scope:** Deep · **CEO scope mode:** SELECTIVE EXPANSION
**Target repo:** https://github.com/ZenithFr/Dyna (Kotlin + Compose, `AccessibilityService` overlay, minSdk 31, target 35)
**Evidence standard:** every finding comes from reading the source at `master`. **Nothing was run on a device.** Anything marked _hypothesis_ must be measured before it is treated as fact.

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
