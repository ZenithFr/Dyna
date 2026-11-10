# Nothing Island

An open-source **Dynamic Island overlay for Nothing OS**, built with Kotlin + Jetpack Compose.
It draws a black pill at the top of the screen using a classic `AccessibilityService` overlay
window — no root, no `SYSTEM_ALERT_WINDOW` grant, no cloud, no telemetry.

> Status: **v0.5.0+**. The island shell, onboarding and calibration work, it reacts to media
> sessions, notifications, calls, charging/battery/power-save, downloads and maps navigation,
> ships a full countdown timer, and places itself around
> the physical camera punch-hole with three calibration modes and two shell shapes (pill / notch).
> Compact content flanks the lens; expanded cards use Apple-style leading/trailing continuity and
> a full-width control region **below** the lens. Landscape and immersive fullscreen hide the
> island by default; portrait lock only governs geometry when landscape hiding is off. Tap-to-expand and single-active-source priority
> remain the documented Android behaviour; this is not a claim of pixel-identical iPhone parity.

## What works today

- Accessibility overlay window (`TYPE_ACCESSIBILITY_OVERLAY`, `TRANSLUCENT`, top-centre pill).
- **Media**: the active `MediaSession` is mirrored with album art (≤128 px) and playing bars in
  the compact pill. Expanded: artwork/title/artist and an artwork-tinted waveform above a progress
  row and unboxed prev/play-pause/next controls, all below the lens. Unsupported transport actions
  are disabled. Buttons call `MediaController.TransportControls`; updates arrive through
  `MediaController.Callback` (no polling). Artwork is downscaled and sampled for accent color only
  when its identity changes. The original full-size bitmap is not strongly retained; only the
  bounded thumbnail is kept, and decorative playing bars are opt-in.
- **Notifications**: a message alert shows the app icon **in the detached bubble** and the
  title in the shell. The bubble holds for 3 s by default (**Layout → Detached bubble → Bubble hold**, 1–10 s);
  the shell hold is 4 s. Both can be changed, and the icon can be moved back into the pill. Each alert's
  bubble retracts on the same mitosis spring when its hold expires, so nothing is left beside the lens.
  When several notifications are queued, the island rotates through all of them, from different apps
  too, until the last one is dismissed. The detached icon carries an **unread count badge** that matches
  the number of active notifications in the OS shade (seeded from the shade when the listener connects).
  Tap to expand for title, text and up to two actions below the lens (inline reply included). The
  compact pill never shows a percentage on a message. Private/secret
  notifications are redacted or skipped while the keyguard is locked. Silent/gentle notifications
  (Android importance LOW), including some muted chats, are shown by default; **Notifications & apps →
  Silent notifications** turns them off. IMPORTANCE_MIN/NONE, generic ongoing services, group summaries,
  media notifications, opted-out apps and our own package are skipped. Calls, progress and navigation
  are routed to their dedicated sources before the generic ongoing filter. Anything while Do Not Disturb
  is set to *none* is skipped. The listener accepts all four Android notification types and migrates
  the old restrictive mask once on upgrade, so silent messages and ongoing calls/downloads can reach
  the app-level gate. If the reply overlay cannot take focus, the conversation's
  content intent is opened instead of sending a result-less reply intent. When the alert expires
  the island falls back to the persistent base (timer, then maps, then downloads, then media),
  else idle.
- **Calls**: `Notification.CallStyle` incoming/ongoing calls surface above everything else, with
  answer/decline/end controls and optional auto-expand. No Shizuku or telecom role is required.
- **Charging / battery**: plug-in shows a green bolt + `xx%` (`FULL` at 100 %); crossing the
  configurable low-battery threshold shows a low card; power-save mode changes show a saver card.
  Driven by public power/battery broadcasts + sticky battery intent — no polling and no manifest
  plug receiver that cold-wakes the process while the island is disabled.
- **Downloads / Live Update-style progress**: ongoing progress notifications (including Android 15
  `ProgressStyle`-compatible `android.progress*` extras) become a persistent activity card. The card
  shows the app's own notification action buttons (up to two) plus Open. It has no percentage and no
  progress bar, and no separate cancel control. Ordinary notifications that merely carry progress
  extras are routed as messages, not as downloads.
- **Maps / navigation**: navigation-category notifications and known maps packages become a
  turn-by-turn card with an open-maps action. This is a public-notification integration, not a
  private Google Maps/Waze SDK hook.
- **Clock**: ongoing timers from the Google Clock (`com.google.android.deskclock`) or AOSP Clock
  (`com.android.deskclock`) apps are shown as a persistent activity card with the Clock's own actions.
  Alarms stay ordinary alerts. Controlled by **Sources & timer → Clock** (on by default). Package
  names are not yet verified on hardware.
- **Timer**: a persistent countdown that outranks maps/downloads/media on the base layer; calls
  sit above all alerts. Start 1/5/10/25
  minute presets from the app; the compact pill shows an orange timer/pause glyph + `mm:ss`.
  Tap to expand for circular pause/resume + cancel controls leading and a large orange `mm:ss`
  trailing, below the lens. Time is
  derived from `SystemClock.elapsedRealtime`, so ticks never drift. While the panel is off/dozing
  the 1 Hz UI ticker is replaced by a single completion wake at the end timestamp; a finished timer shows
  `TIME'S UP` / `00:00` for 4 s before clearing. Notifications and charging still layer above it
  and hand it back when they expire.
- **Detached app icon**: a persistent live activity — music, timer, maps or downloads — keeps the
  island compact and moves the owning app's icon into its own circle a gap to the right. Notification
  and call alerts use the same bubble under their own switch (**App icon in detached bubble**): while
  the bubble carries the icon, the compact shell draws text only, never a duplicate icon. Album art and
  the timer glyph are content rather than app identity, so they always stay in the shell. Detach and
  re-attach are a mitosis-style motion: the daughter circle grows from the trailing shell edge, a
  Bézier neck thins and pinches off, and the same spring reverses the union on expand/retract. The
  circle is part of the island for touches, its surface space is reserved so an activity starting or
  ending never resizes the window, and it is omitted only when an edge-anchored wide pill leaves no
  room without overlap. Notification, charging and battery alerts never split. Diameter, gap and
  icon inset live in **Detached app icon**; icon layers are bounded and TalkBack announces the shared
  shell action once.
- **Camera hole**: the overlay knows where the physical punch-hole is (defaults: screen centre,
  26 dp down, 8 dp radius). Compact content is split into leading/trailing areas beside it — media
  art left / bars right, notification icon left / title right, timer glyph left / time right,
  charging bolt left / percentage right. Expanded content reserves a sensor-safe top row and then
  uses the full width below the lens; an unusually low/large calibrated lens grows the card instead
  of covering the lens. Three placement modes: **Equal split** (hole at the body centre), **Ratio**
  (hole at 20–50 % of the width in the UI) and **One side** (hole flush with the left or right
  edge). A draggable circle in the app calibrates it, **DETECT CUTOUT** reads the platform cutout,
  and a red ring guide can be shown on the real island for lens alignment.
- **Island shape**: **pill** or **notch** (flat top, rounded bottom, hanging from the bezel).
  The notch is pinned to the top edge; the pill keeps the vertical-offset slider.
- **Motion** (Apple Dynamic Island behaviour, rebuilt for a punch-hole screen): expanding and
  retracting is a two-spring morph — the width leads and the height trails it, so the shell
  stretches sideways first and then pours into the expanded card. The spring step is closed-form,
  so 60 Hz and 120 Hz follow the same trajectory; reversing a morph preserves velocity. Compact and
  expanded layouts crossfade with a 6 % scale and a few dp of rise, activity swaps crossfade, and
  notification text uses a whole-line replacement (no per-glyph shaping breakage). Hit targets only
  exist at rest; drags, secondary pointers and cancels cannot fire a tap. The system animator scale
  is respected (`0` snaps without spatial motion). On Android 15+ motion requests **120 Hz** (90 Hz
  when the high-quality switch is off) and drops the request to 0 at rest; Android 12–14 follows the
  panel's normal vsync because the per-view frame-rate API is unavailable. Decorative ambient loops
  (playing bars / indeterminate progress) are **off by default**; when enabled they use a separate
  30 Hz default rate and never run while dormant, hidden, or screen-off.
- **Idle contraction**: with nothing to show — no notification, media, timer or charging — the
  shell curls up into the camera-hole circle after a **1 s** delay (configurable 1–10 s in
  **Behaviour → When idle**). The countdown starts **once**, when the last activity ends: opening or
  closing apps, inset changes, refresh-rate changes and settings emissions can no longer restart it
  or resurrect a retired island. **When idle → Keep pill** is the alternative for users who want an
  always-visible empty shell (static, still zero frames at rest). Pausing a track or letting an alert expire hands the island back to idle, so
  it disappears into the lens instead of lingering; new activity cancels the countdown and springs
  the shell back out. The curl is a ~420 ms spring on the same 120 Hz motion path as the expand /
  retract morph, not a one-frame pop. It is staged rather
  than uniform: over half of the width is gone before the height moves at all, then the height
  flattens while the rest of the width closes, so the shell slides up into the lens instead of
  shrinking around its own centre. Once the curl settles, the island surface is detached entirely
  instead of leaving a transparent window/buffer behind the lens. Only real content, the always-visible
  setting, lens calibration, or an actual settings edit (one preview linger) may re-attach it.
- **Display behaviour**: **Hide in landscape** and **Hide in fullscreen** default on. Suppression
  removes the island, touch shield and inline reply and stops overlay frame work; landscape/fullscreen
  keep the invisible 1 px inset probe and snap the current activity back when allowed. Screen-off/doze/AOD
  is stricter: every island/probe/touch surface is detached, media/battery observers stop, and the timer
  keeps only its end timestamp plus one completion wake. Fullscreen is detected from status-bar
  inset visibility through a 1 px non-touchable probe, not window content. A fullscreen hide must stay
  stable for 250 ms before it is committed, because app launches briefly report the status bar hidden
  during their window animation — that transient reading used to blink the island on every app switch.
  Display callbacks are filtered to state/rotation/size changes, so the overlay's own 120 Hz hint can no
  longer feed back into a relayout loop. The probe fails open:
  until it has observed a visible status bar, a hidden reading is treated as unknown and cannot hide
  the island. **Portrait lock** only
  applies when landscape hiding is off: it keeps the portrait size/top-centre anchor instead of
  re-anchoring to the rotated cutout. The settings app itself remains portrait.
- **Customization**: the app is a hub with **one page per category** — Setup, Appearance, Layout,
  Camera hole, Notifications & apps, Sources & timer, Behaviour, Motion & battery, Advanced — each with
  its own *restore defaults* scope (system back returns to the hub). Every source can be switched off;
  alert holds, battery threshold, lockscreen redaction and call auto-expand are configurable; shell
  colour + opacity, primary/secondary text colour, text scale and monochrome icons are exposed; compact
  and expanded geometry, detached-bubble diameter/gap/inset and lens clearance are sliders; idle
  behaviour, linger, tap action, long-press action and haptics are selectable; motion has a master
  switch, a 120 Hz morph hint, opt-in ambient loops with a separate rate and a speed scale; each source
  accent has swatches + hex entry. Popular apps can be opted out per package.
- Setup page with grant status, the listener's actual connected/waiting state, battery
  optimisation and overlay permission, plus a diagnostics card and an explicit re-check action. Grant
  status is read on resume (event-driven) instead of a 1.5 s polling loop. Settings controls use Material 3 with
  48 dp touch targets, wrapping chip rows and whole-row switches; the canvas island exposes its
  rest-state controls to TalkBack as virtual buttons.
- Calibration sliders persisted with **DataStore Preferences**: horizontal/vertical offset,
  width, height, corner radius, compact icon size/edge padding, detached-circle diameter/gap/inset
  and the display-behaviour switches. Every change updates the overlay live because the service
  collects the same DataStore `Flow`. The service keeps `canRetrieveWindowContent=false`; fullscreen
  detection reads only its own probe's `WindowInsets` visibility.
- Adaptive launcher icon: black background, white pill, red dot.

## Target device

Primary target: **CMF Phone 2 Pro** — 1080×2392 AMOLED, 120 Hz, centered punch-hole, Android 15.
Use **DETECT CUTOUT** and the red guide to calibrate the physical lens; geometry is dp-based and
clamped to the display. See `docs/FEATURE_PLAN.md` for the Apple parity matrix and performance budget.

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

### 2. Battery optimisation (optional reliability override)

Leave the app optimized for the lowest background battery use. Exempt it only if Nothing OS kills
the accessibility service on your build. In-app: tap **REVIEW**. Or:

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

**Grant is not connection.** Setup → Diagnostics separately shows `Notification access: YES/NO` and
`Notification listener: CONNECTED/WAITING/OFF`; Advanced shows the listener state too. Use **Re-check
now** after changing access. Both the listener and the accessibility overlay must be live for messages
to appear. If access stays granted but the listener stays WAITING, toggle notification access off/on.
**Show test alert** posts synthetic content directly through the running overlay, bypassing the
listener's own-package guard. It tests the pill/bubble only, not notification access or WhatsApp/Discord.

After upgrading, keep notification access enabled and open the app once so the listener can connect.
Its one-time filter migration restores conversations, alerting, silent and ongoing delivery; later
system type choices are not reset on every reconnect. No app-data clear or reinstall is needed.

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
| Width | 28 … 300 dp (clamped to screen) | 110 dp |
| Height | 24 … 48 dp | 32 dp |
| Corner radius | 8 … 24 dp | 16 dp |
| Hide in landscape | on/off | on |
| Hide in fullscreen | on/off | on |
| Portrait lock (when landscape hiding is off) | on/off | on |
| Compact icon size | 12 … 28 dp | 20 dp |
| Compact shell edge padding | 4 … 16 dp | 8 dp |
| Detached circle diameter | 24 … 48 dp | 32 dp |
| Detached circle gap | 4 … 20 dp | 8 dp |
| Detached icon inset | 0 … 8 dp | 3 dp |
| Hole enabled | on/off | on |
| Hole X (from screen centre) | −80 … +80 dp | 0 dp |
| Hole Y (from screen top) | 0 … 60 dp | 26 dp |
| Hole radius | 4 … 20 dp | 8 dp |
| Hole mode | Equal split / Ratio / One side | Equal split |
| Hole ratio (ratio mode) | 20 … 50 % UI (20 … 80 % stored) | 35 % |
| Hole side (one-side mode) | Left / Right | Left |
| Guide on screen | on/off | off |
| Island shape | Pill / Notch | Pill |
| Idle contraction delay | 1 … 10 s | 1 s |
| Animations (master) | on/off | on |
| Motion at 120 Hz | on/off (Android 15+ hint) | on |
| When idle | Hide in lens / Keep pill | Hide in lens |
| Tap shell | Expand / Open app / Nothing | Expand |
| Long press shell | Open app / Expand / Nothing | Open app |
| Haptic feedback | on/off | on |
| Shell colour | RRGGBB / swatches | black |
| Shell opacity | 40 … 100 % | 100 % |
| Primary / secondary text | RRGGBB / swatches | white / 67 % white |
| Monochrome icons | on/off | off |
| App icon in detached bubble | on/off (notifications + calls) | on |
| Bubble hold | 1 … 10 s | 3 s |
| Clock (Google / AOSP) timers | on/off | on |
| Show title in pill | on/off | on |
| Silent notifications | on/off | on |
| Verbose logging | on/off | off |
| Ambient animations | on/off (visible decorative loops only) | off |
| Ambient loop rate | 30 … 120 Hz | 30 Hz |
| Outline | on/off | on |
| Stroke width | 1 … 5 dp | 1 dp |
| Stroke color | RRGGBB / swatches | 15 % white |

Expanded cards are content-aware, not a fixed 84 dp box: media uses a two-row Now Playing layout,
timer uses a short control row, and notifications grow when actions are present. Width follows the
Apple screen classes where they fit (371 dp on a 393 dp screen, capped at 408 dp) and is clamped on
narrow displays. The compact width remains the calibrated value above.

## Test

Real-hardware results and the remaining capture procedure are recorded in
[`docs/DEVICE_VERIFICATION.md`](docs/DEVICE_VERIFICATION.md). The 2026-11-09 run on `master` @ `498058d`
confirmed the build + unit tests, install, idle surface detachment and the screen-off/on suppression
cycle; frame cost, battery soak, alert-bubble behaviour and RAM are still open.

For on-device debugging turn on **Settings → Advanced → Verbose logging**, then watch
`adb logcat -s IslandOverlay`. It reports `attach island`, `detach island`,
`linger armed remaining=…ms`, `preview wake`, `fullscreen hide pending/cancelled` and suppression
transitions. An idle island should log one `detach island` and then stay silent while you open and
close apps; repeated `attach island` + full-delay `linger armed` pairs mean the old flicker loop is back.

For missing WhatsApp/Discord alerts, also watch `IslandNotificationListener` and
`IslandNotifications`. Verbose mode logs the package, accepted source or explicit drop reason,
importance and ongoing/category facts — never message text, custom category text or notification keys. Listener
connect/disconnect and the one-time migration log at INFO even with verbose off. See procedure G in
the device-verification guide for an upgrade-preserving real-app check.

```bash
# Local regression tests (layout, motion, state, notification routing, settings)
mise exec java@openjdk-21 -- ./gradlew testDebugUnitTest

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
#    compact = album art + animated bars; expanded = artwork/title/waveform + progress + transport
#    below the lens. Unsupported transport buttons must be disabled.
adb shell am start -a android.intent.action.VIEW -d https://music.youtube.com

# 3a. Overlay-only test: Setup -> Diagnostics -> Show test alert.
#     This bypasses notification access; it is not a listener-delivery test.
# 3b. External synthetic notification through the real listener:
#     compact for the configured hold, tap to expand (text + up to 2 actions), replies send RemoteInput
adb shell cmd notification post -S bigtext -t "Nothing Island" ni_test "Tap the island to expand."
# 3c. Send real WhatsApp/Discord messages, including a Silent/gentle channel; see procedure G in
#     docs/DEVICE_VERIFICATION.md. Expected: show pkg=com.whatsapp/com.discord source=Notification.

# 3c. Multi-alert: post two notifications from different apps. Expect the detached icon to show an
#     unread count of 2 (matching the shade), the island to rotate between them, and the badge to fall
#     as each is dismissed. Each bubble holds 3 s by default. There must be no percentage on any card.
# 4. Charging: plug/unplug. Expect a 4s bolt + percentage card; at 100% it reads FULL.

# 5a. Clock: start a timer in the Google or AOSP Clock app. Expect an activity card with the Clock's
#     actions and no progress bar. Turn Clock off in Sources & timer and it must not appear.
# 5. Timer: open the app, pick a preset (1/5/10/25 min) and press START.
#    Compact pill shows timer/pause glyph + mm:ss; tap for circular pause/resume + cancel and big time.
#    Pause holds the remaining time; resume continues. Let it finish: TIME'S UP / 00:00 for ~4s,
#    then it clears and falls back to media, else idle. A notification during the countdown must
#    cover the timer for 4s and then hand it back still running.

# 6. Camera hole: open the app -> Camera hole. Drag the circle onto the lens, or press
#    DETECT CUTOUT, then enable "Show guide on screen" and align the red ring with the lens.
#    Check Equal split / Ratio / One side: no text or icon may sit under the hole.
#    Switch Island shape to Notch: it hangs from the top edge (Y slider disabled).
#    adb logcat -s IslandHole   # raw cutout rects from DETECT CUTOUT

# 7. Display behaviour: rotate into landscape or open immersive video/a game. With the defaults
#    the island, touch window and reply window must disappear; returning to portrait/non-fullscreen
#    restores the current activity. Turn Hide in landscape off to verify portrait lock instead.
#    adb shell dumpsys window windows | grep -E 'NothingIsland|NothingIslandTouch|NothingIslandReply' | grep -v Visibility
#    adb logcat -s IslandOverlay IslandVisibility   # suppression transitions + probe calibration

# 8. Idle contraction: pause the music / let the alert expire so the island has nothing to show.
#    After the configured delay (1s by default) the shell curls into the camera hole. A new
#    notification or resuming music must spring it straight back out. Check the log:
#    adb logcat -s IslandOverlay   # "apply content=null" then the contraction

# 9. Gesture/accessibility: press and drag off a control, add a second finger, cancel the gesture:
#    no action may fire. With TalkBack, focus the island and activate transport/timer/notification
#    buttons. Set animator duration scale to 0 and verify state changes snap without spatial motion.
#    Check long titles, RTL/emoji titles, font scale 1.3, and a low/large calibrated lens.

# Logs
adb logcat -s IslandOverlay IslandVisibility IslandService IslandMedia IslandNotificationListener IslandNotifications IslandCharging IslandReply IslandHole
```

## Project layout

```
app/src/main/java/com/zenith/nothingisland/
├── MainActivity.kt                       # Compose host, intents, grant polling
├── data/IslandSettings.kt                # settings model + DataStore repository
├── data/IslandOptions.kt                 # idle/tap enums + per-category reset scopes
├── data/HoleGeometry.kt                  # hole model, mode placement, island shape paths
├── overlay/IslandOverlay.kt              # window, hole-aware geometry, suppression, a11y nodes
├── overlay/IslandVisibility.kt           # landscape/fullscreen policy + privacy-safe inset probe
├── overlay/IslandLayout.kt               # Apple-style compact/expanded layout rules
├── overlay/IslandMotion.kt               # frame-rate-independent spring/easing math
├── overlay/IslandRenderDemand.kt         # pure frame-demand + media-tick dedupe rules
├── overlay/IslandIdlePolicy.kt           # CONTENT/LINGERING/DORMANT idle lifecycle
├── overlay/IslandIconPlacement.kt        # which icon the detached bubble takes
├── overlay/IslandPillView.kt             # canvas renderer, hole avoidance, gesture targets
├── overlay/IslandInteraction.kt          # tap callbacks / transport commands
├── overlay/IslandReplyWindow.kt          # focusable inline-reply overlay
├── service/IslandService.kt              # AccessibilityService, renders the holder
├── service/IslandContent.kt              # event/content models + source priority
├── service/IslandStateHolder.kt          # single source of truth, persistent base + alert queue/rotation
├── service/media/MediaSource.kt          # active MediaSession → island
├── service/notification/                 # listener + filtering/parsing
├── service/charging/                     # dynamic power-save + sticky battery receiver
├── service/timer/TimerModule.kt          # countdown controller (tick, pause/resume/stop)
├── ui/HomeScreen.kt                      # hub + page host + shared panels
├── ui/SettingsPages.kt                   # one page per settings category
├── ui/theme/                             # black/white/red Nothing palette
├── util/ServiceStatus.kt                 # grant checks
app/src/test/java/com/zenith/nothingisland/ # local layout/motion/state regression tests
```

## Roadmap

1. **Richer call UI** — missed-call history and video-call artwork beyond CallStyle extras.
2. **Minimal/detached presentation** — Apple's two-activity minimal layout; the current holder
   intentionally publishes one active source using the documented priority order.
3. **Gesture mode** — optional Apple-style tap-to-open / long-press-to-expand.
4. **Optional Shizuku module** — only for OS state with no public API; never required for core features.
5. **Glyph / Nothing integrations** — optional, keep them behind feature flags.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 Zenith.
