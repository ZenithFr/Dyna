# Dyna performance budget and baseline

**Status:** baseline **PARTIALLY MEASURED** (2026-11-11). P1, P3 and Q1 have device numbers; the
rest of the table is still a target, not a result.
**Device of record:** CMF Phone 2 Pro (Nothing OS, Android 15), 120 Hz, package `com.zenith.nothingisland`.
**Owner:** Unit 1 of [`plan-secure.md`](../../plan-secure.md) · requirements R4, R21, R23.

The README claims "zero frames at rest" and "no heat, no drain". This file exists so those claims become measurements that later units (U4–U9) can be compared against, and so a regression is caught instead of argued about.

## Budget

| # | Scenario | How long | Budget | Why this budget |
|---|---|---|---|---|
| P1 | Idle, island dormant | 60 s | **0 frames** rendered for the package | The surface is detached when dormant; any frame here is a bug |
| P2 | Settled calm card (collapsed timer, collapsed media) | 60 s | **0 frames** | Same idle policy, still no reason to draw |
| P3 | Expand + collapse morph | 20 morphs | janky frames **≤ 2 %**, **0** `GC_FOR_ALLOC` in a Perfetto trace, p95 `island.onDraw` < 4 ms | 120 Hz leaves 8.3 ms per frame; the draw path should use half of it |
| P4 | 30-minute running timer, screen on | 30 min | ≥ **50 %** fewer `island.apply` passes than baseline, no thermal-status change attributable to the app | Running activities are the only real always-on cost |
| P5 | 30-minute expanded media, playing | 30 min | **≤ 1 frame/s**, 0 frames when collapsed or screen off | The progress row needs one repaint per second, nothing more |
| P6 | RAM after 50 notification posts | — | flat `TOTAL PSS` (±5 MB), no unbounded LRU growth | Artwork/avatar caches must stay bounded |
| P7 | Battery, 1 h idle with the island enabled | 1 h | < 1 % of battery, no wakelock held | No polling, no manifest receivers |
| P8 | Battery, 1 h with a running timer | 1 h | < 2 % of battery | 1 Hz repaint only after U4 |

## Measured baseline

Fill this in from the device, then commit. Do not guess.

| # | Scenario | Date | Frames | Janky % | GC_FOR_ALLOC | p95 onDraw | Notes |
|---|---|---|---|---|---|---|---|
| P1 | idle 60 s | 2026-11-11 | **0** (30 s ×2, scaled) | 0.00 % | — | — | Island window attached (`NothingIsland`), launcher focused, empty shell. Same run repeated after closing the shade: 0 again |
| P2 | settled card 60 s | 2026-11-11 | **0** dormant; **90/s** with the ambient equalizer on | 0.00 % | — | — | See the ambient finding below — P2's "0 frames" assumes the default `ambientAnimationsEnabled = false` |
| P3 | 20 morphs | 2026-11-11 | 932 frames / **5** morphs | **0.54 %** | — | not measured | 5 janky frames. gfxinfo **frame draw** p50 5 / p90 9 / p95 11 / p99 19 ms — that is the whole draw phase, not the `island.onDraw` slice the 4 ms budget refers to; the slice needs a Perfetto trace |
| P4 | 30 min timer | — | — | — | — | — | main-thread `apply` count: |
| P5 | 30 min media | — | NOT MEASURED | — | — | — | |
| P6 | 50 notifications | — | — | — | — | — | PSS before/after: |
| P7 | 1 h idle battery | — | — | — | — | — | |
| P8 | 1 h timer battery | — | — | — | — | — | |

## How to capture

Everything is wrapped by [`scripts/perf/capture.sh`](../../scripts/perf/capture.sh). Output lands in `/tmp/opencode/perf` (override with `OUT_DIR`).

```bash
# device + package selection
export SERIAL=adb-00196654H003076-ptc6TG\ \(2\)._adb-tls-connect._tcp
export PKG=com.zenith.nothingisland

scripts/perf/capture.sh idle 60        # P1/P2
scripts/perf/capture.sh morph 20       # P3 (taps the pill at TAP_X/TAP_Y)
scripts/perf/capture.sh timer 1800     # P4 — start the timer first, keep the screen on
scripts/perf/capture.sh media 1800     # P5 — start playback and expand the card first
scripts/perf/capture.sh mem            # P6
scripts/perf/capture.sh battery 3600   # P7/P8 (resets batterystats)
scripts/perf/capture.sh perfetto 20    # frame timeline when gfxinfo sees nothing
```

Raw commands, for the record:

```bash
adb shell dumpsys gfxinfo "$PKG" reset
# …run the scenario…
adb shell dumpsys gfxinfo "$PKG" framestats
adb shell dumpsys gfxinfo "$PKG"
adb shell dumpsys meminfo "$PKG"
adb shell dumpsys batterystats --reset && adb shell dumpsys batterystats "$PKG"
adb shell dumpsys thermalservice
```

## Open question Q1 — does `gfxinfo` see overlay windows?

The island is drawn in a `TYPE_ACCESSIBILITY_OVERLAY` window. `dumpsys gfxinfo <pkg> framestats` reports frames for the app's own windows, and it is **not established** that accessibility-overlay surfaces are attributed to the package. If they are not, P1–P3 read "0 frames" even while the island is visibly animating — a false pass.

Resolution (30-minute spike, before trusting any baseline):

1. Expand the island by hand and run `scripts/perf/capture.sh morph 5`.
2. If it reports `Total frames rendered: 0` while the island is clearly animating, gfxinfo cannot see the surface → use the Perfetto route (`scripts/perf/perfetto.cfg`, which includes `android.surfaceflinger.frametimeline`) and record that decision here.
3. Record the verdict below so later units do not re-litigate it.

**Verdict:** **yes — `gfxinfo` does attribute accessibility-overlay frames to the package.**
Measured 2026-11-11 with the phone unlocked and the island attached: `scripts/perf/capture.sh morph 5`
reported **932 frames rendered, 5 janky (0.54 %)**, and the same package reported **0 frames** in the
following 30 s once the shell settled. A metric that could not see the surface would read 0 in both
runs. The earlier "0 frames" spike was a locked phone: no island was attached, so the taps hit
nothing. The Perfetto route stays useful for slice-level attribution (`island.onDraw` vs layout),
not for counting frames.

### What the "~185 fps idle loop" turned out to be

Worth recording so it is not re-investigated as a defect. With a media card playing, `dumpsys
gfxinfo` showed ~190 frames/s for the package and stayed high after playback stopped. Instrumenting
`IslandPillView.drawFrame` for one build showed the only live term was `animating` — the decorative
playing-bars equalizer, gated on `ambientLoopsEnabled(...)` and drawn on the compact card too. This
device has `ambientAnimationsEnabled = true` and `ambientFrameRateHz = 90`, both opt-in (defaults are
`false` / 30 Hz), so 90 fps is exactly what was asked for. The extra frames came from the foreground
`MainActivity` preview drawing its own copy at the same time.

Two consequences, neither a bug:

- **P2 needs a qualifier.** "Collapsed media ⇒ 0 frames" holds only with ambient animations off (the
  default). With them on, a playing compact card draws at `ambientFrameRateHz`, which is the intended
  budget for that setting, not an idle leak.
- **Screen-off is clean.** With the display dozing the package rendered **0 frames in 5 s**, so the
  loop does not run in the background. An earlier 163 fps "screen off" reading was taken before the
  display had actually dozed and is discarded.

Throttling the equalizer below its configured rate belongs to Unit 8 (motion governor, thermal and
battery), not to a render-demand fix here.

## Trace points

`IslandPillView.onDraw`, `IslandPillView.advanceMotion` and `IslandOverlay.apply` carry `Trace.beginSection` markers (`island.onDraw`, `island.motion`, `island.apply`). They cost nothing when no trace is running, so they stay in release builds. In Perfetto, look for those slices to see how much of a frame is layout versus drawing.
