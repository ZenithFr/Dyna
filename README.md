# Nothing Island

An open-source **Dynamic Island overlay for Nothing OS**, built with Kotlin + Jetpack Compose.
It draws a black pill at the top of the screen using a classic `AccessibilityService` overlay
window — no root, no `SYSTEM_ALERT_WINDOW` grant, no cloud, no telemetry.

> Status: **v0.4.0**. The island shell, onboarding and calibration work, it reacts to media
> sessions, notifications and charging, ships a full countdown timer, and now places itself
> around the physical camera punch-hole with three calibration modes and two shell shapes
> (pill / notch). Portrait lock keeps it pinned to the portrait top-centre anchor in
> landscape apps and fullscreen video.

## What works today

- Accessibility overlay window (`TYPE_ACCESSIBILITY_OVERLAY`, `TRANSLUCENT`, top-centre pill).
- **Media**: the active `MediaSession` is mirrored with album art (≤128 px), animated playing
  bars in the compact pill, and title/artist, prev/play-pause/next and a progress line when
  expanded. Buttons call `MediaController.TransportControls`; updates arrive through
  `MediaController.Callback` (no polling).
- **Notifications**: an alerting notification shows the app icon + title for 4 s; tap to expand
  for title, text and up to two actions (inline reply included). Filtering skips ongoing,
  group-summary, low-importance, media/call and own-package notifications, and anything while
  Do Not Disturb is set to *none*. When the alert expires the island falls back to a running
  timer, then media, else idle.
- **Charging**: plugging in shows a transient bolt + `xx%` card (and a `FULL` card at 100 %),
  driven by the power broadcast + sticky battery intent — no polling.
- **Timer**: a persistent countdown that outranks media/call on the base layer. Start 1/5/10/25
  minute presets from the app; the compact pill always shows `mm:ss` (plus a pause glyph while
  frozen), tap to expand for the label, a large `mm:ss` and pause/resume + stop buttons. Time is
  derived from `SystemClock.elapsedRealtime`, so ticks never drift; a finished timer shows
  `TIME'S UP` / `00:00` for 4 s before clearing. Notifications and charging still layer above it
  and hand it back when they expire.
- **Camera hole**: the overlay knows where the physical punch-hole is (defaults: screen centre,
  26 dp down, 8 dp radius) and splits content into the two areas beside it — media art left /
  bars right, notification icon left / title right, timer and charging centred in the wider side.
  Three placement modes: **Equal split** (hole at the body centre), **Ratio** (hole at 20–50 % of
  the width) and **One side** (hole flush with the left or right edge). A draggable circle in the
  app calibrates it, **DETECT CUTOUT** reads the platform cutout, and a red ring guide can be
  shown on the real island for lens alignment.
- **Island shape**: **pill** or **notch** (flat top, rounded bottom, hanging from the bezel).
  The notch is pinned to the top edge; the pill keeps the vertical-offset slider.
- **Motion** (system Dynamic Island behaviour, rebuilt for a punch-hole screen): expanding and
  retracting is a two-spring morph — the width leads and the height trails it by ~100 ms, so the
  shell stretches sideways first and then pours into the expanded card, and squishes back the same
  way. The compact and expanded layouts crossfade with a 6 % scale and a few dp of rise, the shell
  always leads the content, and switching activities (timer → notification) crossfades instead of
  hard-cutting. Hit targets only exist at rest. Motion asks the panel for **120 Hz** while it runs
  and drops the request to 0 when the island is still.
- **Portrait lock** (default on): geometry is always computed from the portrait display width,
  so landscape games/video never resize, rotate or move the island. The app itself is locked to
  portrait. Turn the switch off for rotation-aware behaviour.
- Onboarding checklist with live grant status for the accessibility service, notification
  listener, battery optimisation and overlay permission.
- Calibration sliders persisted with **DataStore Preferences**: horizontal/vertical offset,
  width, height, corner radius and the portrait-lock switch. Every change updates the overlay
  live because the service collects the same DataStore `Flow`.
- "Hide in fullscreen" preference persisted as a stub (no enforcement yet).
- Adaptive launcher icon: black background, white pill, red dot.

## Requirements

- Nothing OS / Android 12+ (`minSdk 31`), tested against `compileSdk`/`targetSdk` 35.
- JDK 21 (AGP 8.9 does not support the system JDK 25 here), Android SDK platform 35.
- Build-tools 35.0.0 (AGP 8.9.3 installs it automatically if only 34.0.0 is present and the
  SDK licences are accepted).

This repo assumes the SDK lives at `/home/zenith/Android/Sdk`; adjust `local.properties`
(`sdk.dir=...`) for your machine.

## Build

```bash
mise exec java@openjdk-21 -- ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Other handy tasks:

```bash
mise exec java@openjdk-21 -- ./gradlew clean assembleDebug
mise exec java@openjdk-21 -- ./gradlew installDebug      # with a device connected
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zenith.nothingisland/.MainActivity
```

## Grant permissions

Open the app and work through **Setup**. The accessibility service is the only required grant;
notification access is required for the media and notification modules.

### 1. Accessibility service (required)

In-app: tap **ENABLE** → *Settings → Accessibility → Nothing Island → On*.

On Android 13+ a sideloaded app is blocked from accessibility until you allow it:
*Settings → Apps → Nothing Island → ⋮ → Allow restricted settings*, then enable the service.

ADB (note: this replaces the existing enabled-services list, so append rather than
overwrite if you already enabled other services):

```bash
# Read the current list first
adb shell settings get secure enabled_accessibility_services

# Enable (append with ':' if the list is not empty)
adb shell settings put secure enabled_accessibility_services \
  com.zenith.nothingisland/.service.IslandService
adb shell settings put secure accessibility_enabled 1

# Verify
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility | grep -i nothingisland
```

Disable it the same way (remove the component from the list, or just toggle it off in Settings).

### 2. Battery optimisation (recommended)

In-app: tap **ALLOW**. Or:

```bash
adb shell dumpsys deviceidle whitelist +com.zenith.nothingisland
adb shell dumpsys deviceidle whitelist | grep nothingisland
```

Some Nothing OS builds also keep apps alive via *Settings → Apps → Nothing Island →
Battery → Unrestricted*.

### 3. Notification access (required for media + notifications)

In-app: tap **ENABLE** → *Settings → Notification access → Nothing Island alerts → Allow*.

ADB:

```bash
adb shell cmd notification allow_listener \
  com.zenith.nothingisland/com.zenith.nothingisland.service.notification.IslandNotificationListener

# Verify (the component should be listed)
adb shell cmd notification get_listeners | grep -i nothingisland
```

`MediaSessionManager.getActiveSessions(...)` is only readable with this grant, so the media
module stays idle (and silent) until the listener is enabled. Revoke with
`adb shell cmd notification disallow_listener <component>`.

### 4. Overlay permission (informational, not required)

`TYPE_ACCESSIBILITY_OVERLAY` does **not** need `SYSTEM_ALERT_WINDOW`. The row is shown for
completeness and to make the mental model explicit.

```bash
adb shell appops get com.zenith.nothingisland SYSTEM_ALERT_WINDOW
```

### Useful debug commands

```bash
adb logcat -s IslandOverlay IslandService
adb shell dumpsys window windows | grep -i nothingisland
adb shell pm list packages | grep nothingisland
adb uninstall com.zenith.nothingisland
```

## Calibration

Sliders write to DataStore (`island_settings`). The running service collects the flow and
calls `IslandOverlay.update(...)`, so the pill on screen moves/resizes as you drag. Values are
clamped to safe ranges:

| Knob | Range | Default |
| --- | --- | --- |
| Horizontal offset | −24 … +24 dp | 0 dp |
| Vertical offset | 0 … 24 dp | 6 dp |
| Width | 28 … 160 dp | 110 dp |
| Height | 24 … 48 dp | 32 dp |
| Corner radius | 8 … 24 dp | 16 dp |
| Portrait lock | on/off | on |
| Hide in fullscreen | on/off (stub) | on |
| Hole enabled | on/off | on |
| Hole X (from screen centre) | −80 … +80 dp | 0 dp |
| Hole Y (from screen top) | 0 … 60 dp | 26 dp |
| Hole radius | 4 … 20 dp | 8 dp |
| Hole mode | Equal split / Ratio / One side | Equal split |
| Hole ratio (ratio mode) | 20 … 50 % small side | 35 % |
| Hole side (one-side mode) | Left / Right | Left |
| Guide on screen | on/off | off |
| Island shape | Pill / Notch | Pill |

## Test

```bash
# 0. Build + install
mise exec java@openjdk-21 -- ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 1. Accessibility (required) and notification access (media + notifications)
adb shell settings put secure enabled_accessibility_services \
  com.zenith.nothingisland/.service.IslandService   # append with ':' if the list is not empty
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener \
  com.zenith.nothingisland/com.zenith.nothingisland.service.notification.IslandNotificationListener

# 2. Media: play a track, then tap the pill
#    compact = album art + animated bars, expanded = title/artist + transport + progress
adb shell am start -a android.intent.action.VIEW -d https://music.youtube.com

# 3. Notification: post a synthetic alert
#    compact for 4s, tap to expand (text + up to 2 actions), replies send RemoteInput
adb shell cmd notification post -S bigtext -t "Nothing Island" ni_test "Tap the island to expand."

# 4. Charging: plug/unplug. Expect a 4s bolt + percentage card; at 100% it reads FULL.

# 5. Timer: open the app, pick a preset (1/5/10/25 min) and press START.
#    Compact pill counts down mm:ss; tap it to expand (label, big mm:ss, pause/resume, stop).
#    Pause holds the remaining time; resume continues. Let it finish: TIME'S UP / 00:00 for ~4s,
#    then it clears and falls back to media, else idle. A notification during the countdown must
#    cover the timer for 4s and then hand it back still running.

# 6. Camera hole: open the app -> Camera hole. Drag the circle onto the lens, or press
#    DETECT CUTOUT, then enable "Show guide on screen" and align the red ring with the lens.
#    Check Equal split / Ratio / One side: no text or icon may sit under the hole.
#    Switch Island shape to Notch: it hangs from the top edge (Y slider disabled).
#    adb logcat -s IslandHole   # raw cutout rects from DETECT CUTOUT

# 7. Portrait lock: rotate into a landscape game/video. The pill must keep its size and
#    top-centre anchor (no jump, no rotation), and must not re-anchor to the cutout.

# Logs
adb logcat -s IslandOverlay IslandService IslandMedia IslandNotifications IslandCharging IslandReply IslandHole
```

## Project layout

```
app/src/main/java/com/zenith/nothingisland/
├── MainActivity.kt                       # Compose host, intents, grant polling
├── data/IslandSettings.kt                # settings model + DataStore repository
├── data/HoleGeometry.kt                  # hole model, mode placement, island shape paths
├── overlay/IslandOverlay.kt              # window, hole-aware geometry, portrait lock
├── overlay/IslandPillView.kt             # canvas renderer, hole avoidance, tap targets
├── overlay/IslandInteraction.kt          # tap callbacks / transport commands
├── overlay/IslandReplyWindow.kt          # focusable inline-reply overlay
├── service/IslandService.kt              # AccessibilityService, renders the holder
├── service/IslandContent.kt              # event/content models + source priority
├── service/IslandStateHolder.kt          # single source of truth, persistent base + 4 s alerts
├── service/media/MediaSource.kt          # active MediaSession → island
├── service/notification/                 # listener + filtering/parsing
├── service/charging/                     # power receiver + sticky battery
├── service/timer/TimerModule.kt          # countdown controller (tick, pause/resume/stop)
├── ui/HomeScreen.kt                      # onboarding + timer + calibration UI
├── ui/theme/                             # black/white/red Nothing palette
└── util/ServiceStatus.kt                 # grant checks
```

## Roadmap

1. **Fullscreen rule** — honour `hideInFullscreen` with `TYPE_WINDOW_STATE_CHANGED`.
2. **Call module** — reserve the `Call` priority slot with an ongoing-call state.
3. **Glyph / Nothing integrations** — optional, keep them behind feature flags.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 Zenith.
