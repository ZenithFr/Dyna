# Nothing Island → Apple-class island on CMF Phone 2 Pro

## Target hardware

CMF Phone 2 Pro: 6.77" AMOLED, 1080×2392, ~388 ppi, 120 Hz, punch-hole, Android 15 / Nothing OS.
The logical width lands close to the 393 dp Apple screen class, so the expanded card uses the
371 dp available width (408 dp cap) rather than a fixed small box.
[2](https://www.mysmartprice.com/mobile/cmf-phone-2-pro-msp619840)
[3](https://www.pinoytechnoguide.com/smartphones/cmf-phone-2-pro)

## Apple parity matrix

| Apple surface | Android contract used | Status |
| --- | --- | --- |
| Now Playing compact/expanded | MediaSession + MediaController | Implemented |
| Timer compact/expanded | Local elapsedRealtime countdown | Implemented |
| Messages / third-party alerts | NotificationListener + MessagingStyle | Implemented |
| Incoming / ongoing calls | Notification.CallStyle extras (answer/decline/hang-up) | Implemented |
| Charging / battery low / power saver | Sticky battery + PowerManager broadcasts | Implemented |
| Downloads / transfers / Live Update-style progress | EXTRA_PROGRESS / ProgressStyle-compatible extras | Implemented |
| Maps / turn-by-turn | CATEGORY_NAVIGATION + maps package hints | Implemented (generic, no private SDK) |
| Detached app-icon circle beside the compact shell | Persistent live activity + launcher icon + mitosis spring | Implemented |
| Alert app icon in the detached bubble, text-only shell | Notification/CallStyle icon + transient content slot | Implemented |
| Detached minimal *second* activity (two at once) | Needs multi-slot renderer/window policy | Roadmap |
| Tap opens app / long-press expands | Configurable tap + long-press actions | Implemented (defaults keep tap-to-expand, long-press opens the app) |
| Landscape / fullscreen hiding | Display metrics + fail-open own-probe status-bar insets | Implemented; no window-content access |

## Shizuku decision

No Shizuku dependency was added. Calls, battery low, power-save, downloads and maps status are all
available through public notification/battery/power APIs once the user grants notification access.
Shizuku remains an optional future escape hatch for OS state that has no public contract; it should
be feature-flagged and never required for the core island.

## Customization shipped

- Source switches: media, notifications, calls, charging, battery low, power saver, downloads, maps.
- Alert holds: notification / charging / battery; battery-low threshold; lockscreen redaction;
  auto-expand calls.
- Per-app opt-outs for WhatsApp, Instagram, Snapchat, Telegram, Messenger, Discord, Spotify,
  YouTube Music, Google Maps, Waze, Nothing Camera, Chrome, Play Store, system downloads.
- Geometry: compact width/height/corner/offsets, hole calibration, expanded max width/corner,
  content margin, lens clearance, island text scale, compact icon size/edge padding, detached
  circle on/off + diameter/gap/icon inset, landscape/fullscreen hiding.
- Motion: master animation switch, 120 Hz morph hint, opt-in ambient animations + loop rate, motion
  speed, idle behaviour + linger, portrait lock.
- Appearance: shell colour/opacity, primary/secondary text colour, text scale, monochrome icons.
- Interaction: idle behaviour (hide in lens / keep pill), tap action, long-press action, haptics.
- Alert content: app icon in the detached bubble, title in the compact pill.
- Pages: Setup, Appearance, Layout, Camera hole, Notifications & apps, Sources & timer, Behaviour,
  Motion & battery, Advanced — each with its own reset scope; Advanced adds verbose logging.
- Colour: shell stroke plus timer/charging/battery-low/power-saver/call answer/decline/download/maps accents.

## Performance budget (CMF 120 Hz)

- Frame deadline: 8.3 ms. Morphs request 120 Hz on Android 15+; decorative ambient loops are off by
  default and, when enabled, default to 30 Hz. Idle/dormant/hidden/screen-off demand is 0 Hz.
- Fixed accessibility-overlay envelope: no WindowManager resize during a morph.
- Closed-form springs: same trajectory at 60/120 Hz, velocity preserved on reversal.
- Whole-line text replacement; no per-glyph shaping/substring allocation.
- Artwork downscaled/accent-sampled only when bitmap identity changes; the original bitmap is held
  weakly and the accent pixel buffer is reused. Notification parsing happens once per accepted post;
  package icons are LRU-bounded and listener caches clear on disconnect.
- Hit targets only at rest; input window removed for non-interactive battery confirmations and suppression.
- Mitosis reuses shell/circle/neck paths; icon crossfade layers are bounded to the icon box.
- Idle is a state machine (CONTENT / LINGERING / DORMANT): the linger starts once when content ends,
  and a dormant island is never re-attached by a refresh, inset dispatch, display callback or settings
  emission. Only content, the always-visible setting, calibration or an explicit settings edit wakes it.
- Display callbacks are filtered to state/rotation/size, so the overlay's own frame-rate hint cannot
  feed back into a relayout loop; app-transition bursts are coalesced.
- Fullscreen hides are debounced 250 ms so launch animations do not blink the island.
- Compact media position ticks are deduped: no re-measure, relayout or accessibility event per second.
- Grant status in the app is read on resume, not polled.
- No polling: MediaController callbacks, notification listener, sticky battery/power broadcasts.
  No manifest plug receiver cold-wake path. Screen-off/doze detaches all surfaces, stops media/battery
  observers, and reduces a running timer to one completion wake. Idle contraction detaches the settled
  dormant surface; listener reconnects cancel the previous settings collector.
- Fullscreen probe fails open until calibrated by a visible status-bar reading; suppression transitions are logged.

Device verification: the first real run (2026-11-09, v0.5.0 debug) confirmed the build + JVM tests,
install, idle surface detachment and the screen-off/on suppression cycle. Frame cost, battery soak,
alert-bubble behaviour and RAM are still open. Evidence and the exact capture procedure live in
[`DEVICE_VERIFICATION.md`](DEVICE_VERIFICATION.md).

Still to capture:
```bash
adb shell dumpsys gfxinfo com.zenith.nothingisland framestats
adb shell dumpsys meminfo com.zenith.nothingisland
adb shell dumpsys SurfaceFlinger --latency NothingIsland
adb shell settings put system animator_duration_scale 0   # restore afterwards
```

## Next backlog

1. Detached minimal bubble for a *second* simultaneous activity (timer + media). The
   single-activity app-icon bubble already shipped on `master` and is reconciled here.
2. Long-press expand / tap-open gesture mode, optional and documented.
3. Richer maps maneuver icons via public notification large icons / ProgressStyle tracker icons.
4. Optional Shizuku module for OS elements without public APIs.
