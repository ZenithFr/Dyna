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
| Detached minimal *second* activity (two at once) | Needs multi-slot renderer/window policy | Roadmap |
| Tap opens app / long-press expands | README currently tap-to-expand | Preserved deliberately |
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
- Motion: 120 Hz morph hint, ambient loop rate, motion speed, idle contraction delay, portrait lock.
- Colour: shell stroke plus timer/charging/battery-low/power-saver/call answer/decline/download/maps accents.

## Performance budget (CMF 120 Hz)

- Frame deadline: 8.3 ms. Morphs request 120 Hz on Android 15+; ambient loops default to 60 Hz.
- Fixed accessibility-overlay envelope: no WindowManager resize during a morph.
- Closed-form springs: same trajectory at 60/120 Hz, velocity preserved on reversal.
- Whole-line text replacement; no per-glyph shaping/substring allocation.
- Artwork downscaled/accent-sampled only when bitmap identity changes.
- Hit targets only at rest; input window removed for non-interactive battery confirmations and suppression.
- Mitosis reuses shell/circle/neck paths; icon crossfade layers are bounded to the icon box.
- No polling: MediaController callbacks, notification listener, sticky battery/power broadcasts.
- Fullscreen probe fails open until calibrated by a visible status-bar reading; suppression transitions are logged.

Device verification still required:
```bash
adb shell dumpsys gfxinfo com.zenith.nothingisland framestats
adb shell dumpsys SurfaceFlinger --latency NothingIsland
adb shell settings put system animator_duration_scale 0   # restore afterwards
```

## Next backlog

1. Detached minimal bubble for a *second* simultaneous activity (timer + media). The
   single-activity app-icon bubble already shipped on `master` and is reconciled here.
2. Long-press expand / tap-open gesture mode, optional and documented.
3. Richer maps maneuver icons via public notification large icons / ProgressStyle tracker icons.
4. Optional Shizuku module for OS elements without public APIs.
