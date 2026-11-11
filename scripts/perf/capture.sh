#!/usr/bin/env bash
# Dyna performance capture helper (Unit 1).
#
# Wraps the adb commands from docs/perf/PERF_BUDGET.md so a baseline run is one
# command per scenario. It never edits the device state beyond gfxinfo/battery
# counters and never requires root.
#
#   scripts/perf/capture.sh idle [seconds]
#   scripts/perf/capture.sh morph [morphs]
#   scripts/perf/capture.sh timer [seconds]
#   scripts/perf/capture.sh media [seconds]
#   scripts/perf/capture.sh mem
#   scripts/perf/capture.sh battery [seconds]
#   scripts/perf/capture.sh perfetto [seconds]
#   scripts/perf/capture.sh all
#
# Environment: SERIAL (device serial), PKG (package), OUT_DIR (output folder),
# TAP_X / TAP_Y (pill centre used by the morph scenario).
set -euo pipefail

PKG="${PKG:-com.zenith.nothingisland}"
SERIAL="${SERIAL:-}"
OUT_DIR="${OUT_DIR:-/tmp/opencode/perf}"
TAP_X="${TAP_X:-596}"
TAP_Y="${TAP_Y:-68}"
SETTLE_MS="${SETTLE_MS:-1200}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
}

say() { printf '%s\n' "$*" >&2; }
die() { say "error: $*"; exit 2; }

require_device() {
    command -v adb >/dev/null 2>&1 || die "adb not found in PATH"
    local args=()
    [ -n "$SERIAL" ] && args=(-s "$SERIAL")
    local devices
    devices=$(adb "${args[@]}" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
    [ -n "$devices" ] || die "no device attached (SERIAL='${SERIAL}'). Connect the phone and retry."
    if [ -z "$SERIAL" ] && [ "$(printf '%s\n' "$devices" | wc -l)" -gt 1 ]; then
        say "warning: multiple devices attached, using $(printf '%s' "$devices" | head -1); set SERIAL to choose"
    fi
    ADB=(adb "${args[@]}")
}

# gfxinfo only sees frames the app's own windows render. The island lives in a
# TYPE_ACCESSIBILITY_OVERLAY window, which some builds omit from the app's
# frame stats (open question Q1 in docs/plans). Detect it and say so loudly
# instead of reporting a fake "0 janky frames".
check_frames() {
    local file="$1"
    local rendered
    rendered=$(grep -m1 -oE 'Total frames rendered: [0-9]+' "$file" 2>/dev/null | grep -oE '[0-9]+$' || true)
    if [ -z "$rendered" ] || [ "$rendered" -eq 0 ]; then
        say ""
        say "WARNING: gfxinfo reported no frames for $PKG."
        say "The island may be rendering in a TYPE_ACCESSIBILITY_OVERLAY window that"
        say "gfxinfo does not attribute to the package (plan question Q1)."
        say "Use the Perfetto route instead:  $0 perfetto 20"
        say ""
        return 1
    fi
    say "frames rendered: $rendered"
    grep -E 'Janky frames|50th percentile|90th percentile|95th percentile|99th percentile' "$file" | head -6 >&2 || true
}

capture_frames() {
    local scenario="$1"
    local file="$OUT_DIR/${scenario}-${STAMP}.txt"
    say "collecting gfxinfo for '$scenario' -> $file"
    "${ADB[@]}" shell dumpsys gfxinfo "$PKG" framestats >"$file" 2>&1 || true
    "${ADB[@]}" shell dumpsys gfxinfo "$PKG" >>"$file" 2>&1 || true
    check_frames "$file" || true
    printf '%s\n' "$file"
}

run_frames() {
    local scenario="$1" duration="$2"
    "${ADB[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
    say "recording '$scenario' for ${duration}s — do not touch the phone"
    sleep "$duration"
    capture_frames "$scenario"
}

# The morph scenario needs the island settled before the first tap: a tap that
# lands mid-morph is ignored (see docs/DEVICE_VERIFICATION.md).
morph_loop() {
    local count="$1"
    "${ADB[@]}" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
    say "running $count expand/collapse morphs at ${TAP_X},${TAP_Y}"
    local i
    for ((i = 1; i <= count; i++)); do
        "${ADB[@]}" shell input tap "$TAP_X" "$TAP_Y"
        sleep "$(awk -v ms="$SETTLE_MS" 'BEGIN { printf "%.2f", ms / 1000 }')"
        "${ADB[@]}" shell input tap "$TAP_X" "$TAP_Y"
        sleep 1
    done
    capture_frames "morph-${count}"
}

mem_capture() {
    local file="$OUT_DIR/mem-${STAMP}.txt"
    "${ADB[@]}" shell dumpsys meminfo "$PKG" >"$file" 2>&1
    say "meminfo -> $file"
    grep -E 'TOTAL PSS|Java Heap|Native Heap' "$file" | head -4 >&2 || true
}

battery_capture() {
    local seconds="$1"
    local bat="$OUT_DIR/battery-${STAMP}.txt"
    local thermal="$OUT_DIR/thermal-${STAMP}.txt"
    "${ADB[@]}" shell dumpsys batterystats --reset >/dev/null 2>&1 || true
    say "soaking for ${seconds}s with batterystats reset"
    sleep "$seconds"
    "${ADB[@]}" shell dumpsys batterystats "$PKG" >"$bat" 2>&1
    "${ADB[@]}" shell dumpsys thermalservice >"$thermal" 2>&1
    say "batterystats -> $bat"
    say "thermal      -> $thermal"
}

perfetto_capture() {
    local cfg="$HERE/perfetto.cfg"
    [ -f "$cfg" ] || die "perfetto config missing: $cfg"
    local trace="$OUT_DIR/perfetto-${STAMP}.perfetto-trace"
    # Trace length comes from duration_ms in perfetto.cfg (20 s); perfetto blocks
    # until it is done, so no attach/kill dance is needed.
    "${ADB[@]}" push "$cfg" /data/local/tmp/dyna-perfetto.cfg >/dev/null
    say "tracing with Perfetto (duration from perfetto.cfg)"
    "${ADB[@]}" shell perfetto -c /data/local/tmp/dyna-perfetto.cfg -o /data/local/tmp/dyna-trace.perfetto-trace
    "${ADB[@]}" pull /data/local/tmp/dyna-trace.perfetto-trace "$trace" >/dev/null
    say "trace -> $trace  (open in https://ui.perfetto.dev)"
}

[ $# -ge 1 ] || usage
CMD="$1"
ARG="${2:-}"
STAMP="$(date +%Y%m%d-%H%M%S)"
require_device
mkdir -p "$OUT_DIR"

case "$CMD" in
    idle)      run_frames idle "${ARG:-60}" ;;
    morph)     morph_loop "${ARG:-20}" ;;
    timer)
        say "Start a 30-minute timer from the island, keep the screen on, then leave the phone alone."
        run_frames timer "${ARG:-1800}"
        ;;
    media)
        say "Start playback, expand the media card, then leave the phone alone."
        run_frames media "${ARG:-1800}"
        ;;
    mem)       mem_capture ;;
    battery)   battery_capture "${ARG:-3600}" ;;
    perfetto)  perfetto_capture "${ARG:-20}" ;;
    all)
        run_frames idle 60
        morph_loop 20
        mem_capture
        battery_capture 600
        ;;
    -h|--help) usage ;;
    *)         usage ;;
esac
