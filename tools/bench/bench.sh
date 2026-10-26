#!/usr/bin/env bash
# EDGE_PLAN A1: the benchmark harness. Runs the fixed benchmark edit on an
# attached phone and writes one block of numbers for docs/BENCHMARKS.md.
#
#   sh tools/bench/bench.sh <serial> <bench-media-dir> <step> [label]
#
# Steps, in the order "all" runs them:
#   setup     push the clips, grant video access, make the four bench projects
#             (needs Squish's DEBUG build installed: run-as writes the drafts)
#   perf      build and install the release build (assemblePerf) over it
#   coldstart three cold starts, median of am start -W
#   open      open the 1-overlay edit: editor shown and first frame (SquishPerf)
#   scrub     scripted swipes on the strip; gfxinfo median/p90/janky
#   playback  20 s of play with 0, 1 and 3 overlays: frames dropped
#   export    the 1-overlay edit at 1080p30: seconds, x real time, size, probe;
#             thermal status before and after
#   quality   the plain edit exported, ten frames against the source: SSIM
#   teardown  delete every file and project it made, revoke the access
#
# Nothing on the phone is touched but what this makes, and it is all removed
# by teardown: the clips and exports by MediaStore id, the projects by slot.
set -u
SERIAL=${1:?serial}; MEDIA=${2:?bench media dir}; STEP=${3:?step}; LABEL=${4:-$(git rev-parse --short HEAD)}
ADB="C:/Users/vysak/AppData/Local/Android/Sdk/platform-tools/adb -s $SERIAL"
PKG=com.squish.app
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/build/bench"; mkdir -p "$WORK"
STATE="$WORK/state.$SERIAL"; touch "$STATE"; . "$STATE"
REPORT="$WORK/report.$SERIAL.txt"
say() { echo "$*" | tee -a "$REPORT"; }
sh_() { MSYS_NO_PATHCONV=1 $ADB shell "$@"; }
remember() { echo "$1=\"$2\"" >> "$STATE"; eval "$1=\"$2\""; }

free() {
  local call; call=$(sh_ dumpsys telephony.registry | grep -m1 mCallState | tr -dc 0-9)
  [ "$call" = "0" ] || { echo "phone is in a call; stopping"; exit 2; }
}

dump() { sh_ rm -f /sdcard/ui.xml; sh_ uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; sh_ cat /sdcard/ui.xml | tr '>' '\n' > "$WORK/ui.txt"; }

# Taps the first node whose text or content-desc matches $1 (an extended regex).
tap_text() {
  dump
  local b; b=$(grep -E "(text|content-desc)=\"$1\"" "$WORK/ui.txt" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  [ -n "$b" ] || { say "  (could not find \"$1\" on screen)"; return 1; }
  set -- $b
  sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

perf_lines() { sh_ logcat -d -s SquishPerf:I; }
media_id() { sh_ "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$1'\"" | grep -oE "_id=[0-9]+" | cut -d= -f2 | head -1; }
newest_export() { sh_ "content query --uri content://media/external/video/media --projection _id:_display_name --sort 'date_added DESC'" | grep -m1 "squish_" | grep -oE "_id=[0-9]+" | cut -d= -f2; }
screen_h() { sh_ wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2; }
screen_w() { sh_ wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1; }
home() { sh_ am force-stop $PKG; sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 4; }

median() { sort -n | awk '{a[NR]=$1} END {print (NR%2 ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2)}'; }

# ---- setup ---------------------------------------------------------------------
setup() {
  free
  say ""
  say "== Squish benchmark $LABEL on $(sh_ getprop ro.product.model | tr -d '\r') ($(date '+%Y-%m-%d %H:%M'))"
  sh_ mkdir -p /sdcard/Movies/SquishBench
  for f in "$MEDIA"/*.mp4; do MSYS_NO_PATHCONV=1 $ADB push "$f" /sdcard/Movies/SquishBench/ >/dev/null; done
  for i in $(seq 30); do [ -n "$(media_id bench_4k30_hevc.mp4)" ] && break; sleep 2; done
  local ids=""; for n in 1 2 3 4 5 6; do ids="$ids $(media_id bench_$n.mp4)"; done
  remember IDS "$ids"; remember ID4K "$(media_id bench_4k30_hevc.mp4)"
  remember PRE_SLOTS "$(sh_ run-as $PKG ls files/projects | grep -oE '^j[0-9a-f]+' | sort -u | tr '\n' ' ')"
  sh_ pm grant $PKG android.permission.READ_MEDIA_VIDEO
  sh_ pm grant $PKG android.permission.READ_MEDIA_IMAGES
  # The song: a Squish original already made on this phone, if there is one.
  local song; song=$(sh_ run-as $PKG ls files/music 2>/dev/null | grep -m1 -E '\.m4a$' | tr -d '\r')
  [ -n "$song" ] && remember SONG "file:\\/\\/\\/data\\/user\\/0\\/$PKG\\/files\\/music\\/$song" || say "  (no Squish original on this phone yet: the edit has no song)"
  local k=1; for v in plain n0 n1 n3; do make_project $v $k; k=$((k+1)); done
}

# A project slot made by Squish itself (a VIEW of the first clip), its draft
# then replaced by the fixture and its name set, so the card says which it is.
make_project() {
  # A different clip opened for each: Squish reopens the project a video is
  # already in, so opening one clip four times made two slots for four edits.
  local v=$1 k=$2 first; first=$(echo $IDS | cut -d" " -f$k)
  sh_ am start -a android.intent.action.VIEW -d "content://media/external/video/media/$first" -t video/mp4 --grant-read-uri-permission -n $PKG/.MainActivity >/dev/null
  sleep 8
  sh_ am force-stop $PKG
  local slot; slot=$(sh_ run-as $PKG ls -t files/projects | grep -E '\.meta\.json$' | head -1 | sed 's/\.meta\.json//' | tr -d '\r')
  local n=${v#n}; [ "$v" = plain ] && n=0
  sh "$ROOT/tools/bench/fixture.sh" $n $IDS $ID4K "${SONG:-}" $([ $v = plain ] && echo plain) \
    | sed "s/^{\"version\":14,/{\"version\":14,\"name\":\"Bench $v\",/" > "$WORK/$v.json"
  [ -z "${SONG:-}" ] && sed -i 's/"audioClips":\[[^]]*\]/"audioClips":[]/' "$WORK/$v.json"
  for s in bak.json pending.snap.json; do sh_ run-as $PKG rm -f "files/projects/$slot.$s"; done
  MSYS_NO_PATHCONV=1 $ADB shell "run-as $PKG sh -c 'cat > files/projects/$slot.json'" < "$WORK/$v.json"
  # The grid reads the sidecar, not the draft, for its card's title.
  # Read out, edited here, written back: a sed through adb, run-as and the
  # phone's shell loses its quotes on the way.
  MSYS_NO_PATHCONV=1 $ADB exec-out run-as $PKG cat "files/projects/$slot.meta.json" \
    | sed "s/\"title\":\"[^\"]*\"/\"title\":\"Bench $v\"/" > "$WORK/meta.json"
  MSYS_NO_PATHCONV=1 $ADB shell "run-as $PKG sh -c 'cat > files/projects/$slot.meta.json'" < "$WORK/meta.json"
  remember "SLOT_$v" "$slot"
  say "  project Bench $v -> slot $slot"
}

perf_install() {
  (cd "$ROOT" && JAVA_HOME=$HOME/.jdks/jbr-21.0.11 ./gradlew assemblePerf -q 2>&1 | grep -E "^e:|FAIL" )
  local apk; apk=$(ls "$ROOT"/app/build/outputs/apk/perf/*.apk | head -1)
  $ADB install -r "$apk" | tail -1
}

# ---- measurements ----------------------------------------------------------------
coldstart() {
  free
  local t=""
  for i in 1 2 3; do
    sh_ am force-stop $PKG; sleep 2
    t="$t $(sh_ am start -W -n $PKG/.MainActivity | grep -E '^TotalTime' | tr -dc 0-9)"
    sleep 3
  done
  say "cold start (am start -W, ms):$t  median $(echo $t | tr ' ' '\n' | median)"
}

open_project() { # variant
  home; sh_ logcat -c
  # The grid lists the newest first, so a bench project made days ago is
  # further down: scrolled a screen at a time until its card is in sight.
  local tries=0 h w; h=$(screen_h); w=$(screen_w)
  until tap_text "Bench $1(, [^\"]*)?" >/dev/null; do
    tries=$((tries + 1)); [ $tries -gt 12 ] && { say "  (no project \"Bench $1\" on the grid)"; return 1; }
    sh_ input swipe $(( w / 2 )) $(( h * 80 / 100 )) $(( w / 2 )) $(( h * 30 / 100 )) 300; sleep 1
  done
  sleep 10
}

open() {
  free
  open_project n1 || return
  local l; l=$(perf_lines)
  say "open project: $(echo "$l" | grep -oE 'open-editor [0-9]+ ms' | head -1) ; $(echo "$l" | grep -oE 'open [0-9]+ ms first frame' | head -1)"
}

scrub() {
  free
  open_project n1 || return
  local h w y; h=$(screen_h); w=$(screen_w); y=$(( h * 58 / 100 ))
  sh_ dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4 5; do
    sh_ input swipe $(( w * 85 / 100 )) $y $(( w * 15 / 100 )) $y 600; sleep 1
    sh_ input swipe $(( w * 15 / 100 )) $y $(( w * 85 / 100 )) $y 600; sleep 1
  done
  sh_ dumpsys gfxinfo $PKG > "$WORK/gfx_scrub.txt"
  say "scrub (10 swipes): $(grep -E 'Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile' "$WORK/gfx_scrub.txt" | head -5 | sed 's/^ *//' | tr '\n' ';')"
}

# The same swipes read through framestats, which keeps only the last ~120
# frames: so reset, one swipe, dump, ten times, and the blocks parsed together
# (tools/bench/framestats.awk) - the whole frame, and the app's part apart
# from the GPU's, which a bare gfxinfo percentile does not say.
scrubfs() {
  free
  open_project ${SCRUB_PROJECT:-n1} || return
  local h w y i; h=$(screen_h); w=$(screen_w); y=$(( h * 58 / 100 ))
  : > "$WORK/fs_scrub.txt"
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sh_ dumpsys gfxinfo $PKG reset >/dev/null
    if [ $((i % 2)) = 1 ]; then sh_ input swipe $(( w * 85 / 100 )) $y $(( w * 15 / 100 )) $y 600
    else sh_ input swipe $(( w * 15 / 100 )) $y $(( w * 85 / 100 )) $y 600; fi
    sleep 1
    sh_ dumpsys gfxinfo $PKG framestats >> "$WORK/fs_scrub.txt"
  done
  say "scrub framestats (10 swipes): $(awk -f "$ROOT/tools/bench/framestats.awk" "$WORK/fs_scrub.txt" | tr '\n' ';')"
}

# EDGE_PLAN C7: every screen and sheet, each step measured on its own -
# framestats reset, the gesture, the summary - so the report says which
# screen drops frames, not only that something did. A step whose control is
# not on screen says so and the tour goes on.
tour_step() { # label, then a command
  local label=$1; shift
  sh_ dumpsys gfxinfo $PKG reset >/dev/null
  "$@" || { say "tour $label: (skipped - not on screen)"; return; }
  sleep 1
  sh_ dumpsys gfxinfo $PKG framestats > "$WORK/fs_tour.txt"
  say "tour $label: $(awk -f "$ROOT/tools/bench/framestats.awk" "$WORK/fs_tour.txt" | tr '\n' ';')"
}
fling_up() { local h w; h=$(screen_h); w=$(screen_w); sh_ input swipe $((w/2)) $((h*75/100)) $((w/2)) $((h*25/100)) 250; sleep 0.6; sh_ input swipe $((w/2)) $((h*25/100)) $((w/2)) $((h*75/100)) 250; }
sheet_scroll() { local h w; h=$(screen_h); w=$(screen_w); sh_ input swipe $((w/2)) $((h*88/100)) $((w/2)) $((h*70/100)) 300; sleep 0.4; sh_ input swipe $((w/2)) $((h*70/100)) $((w/2)) $((h*88/100)) 300; }
open_tool() { tap_text "$1" && sleep 1.2; }
close_sheet() { tap_text "Done" || sh_ input keyevent KEYCODE_BACK; sleep 0.8; }
tour() {
  free
  home
  tour_step "home grid scroll" fling_up
  tap_text "Settings" && sleep 1.5 && tour_step "settings scroll" fling_up; sh_ input keyevent KEYCODE_BACK; sleep 1
  open_project ${SCRUB_PROJECT:-n1} || return
  local t
  for t in Edit Sound Text Stickers Overlay Effects Look; do
    tour_step "open $t" open_tool "$t"
    tour_step "$t sheet scroll" sheet_scroll
    close_sheet
  done
  tour_step "export sheet" open_tool "Export"
  sh_ input keyevent KEYCODE_BACK; sleep 1
  local xy
  tap_text "Play" >/dev/null; xy=$(grep -E '(text|content-desc)="Play"' "$WORK/ui.txt" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
  sh_ dumpsys gfxinfo $PKG reset >/dev/null; sleep 5
  sh_ dumpsys gfxinfo $PKG framestats > "$WORK/fs_tour.txt"
  say "tour playback 5 s: $(awk -f "$ROOT/tools/bench/framestats.awk" "$WORK/fs_tour.txt" | tr '\n' ';')"
  [ -n "$xy" ] && { set -- $xy; sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); }
}

playback() {
  free
  for v in n0 n1 n3; do
    open_project $v || continue
    sh_ logcat -c
    sh_ dumpsys gfxinfo $PKG reset >/dev/null
    # Paused by tapping where Play was: a playing editor never goes idle, so
    # uiautomator cannot dump it to find Pause (CLAUDE.md).
    tap_text "Play" || continue
    local xy; xy=$(grep -E '(text|content-desc)="Play"' "$WORK/ui.txt" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
    sleep 20
    set -- $xy; sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 3
    local d; d=$(perf_lines | grep -oE 'dropped [0-9]+ ms frames=[0-9]+' | grep -oE 'frames=[0-9]+' | cut -d= -f2 | awk '{s+=$1} END {print s+0}')
    sh_ dumpsys gfxinfo $PKG > "$WORK/gfx_play_$v.txt"
    say "playback 20 s, ${v#n} overlays: $d frames dropped by the decoder; screen: $(grep -E "Janky frames:|50th percentile|90th percentile" "$WORK/gfx_play_$v.txt" | head -3 | sed "s/^ *//" | tr '\n' ';')"
  done
}

thermal() { sh_ dumpsys thermalservice | grep -E "Thermal Status|mValue=" | grep -iE "status|cpu|skin|battery" | head -6 | sed 's/^ *//' | tr '\n' ' '; }

export_() { # variant, size chip label, tag
  local v=$1 chip=$2 tag=$3
  open_project $v || return 1
  tap_text "Export" || return 1; sleep 2
  tap_text "$chip" || return 1; sleep 1
  sh_ logcat -c
  tap_text "Render and save" || return 1
  local i; for i in $(seq 300); do perf_lines | grep -q " export " && break; sleep 2; done
  local line; line=$(perf_lines | grep -oE 'export [0-9]+ ms .*' | head -1)
  local id; id=$(newest_export)
  MSYS_NO_PATHCONV=1 $ADB exec-out "content read --uri content://media/external/video/media/$id" > "$WORK/$tag.mp4"
  sh_ content delete --uri "content://media/external/video/media/$id"
  say "export $tag: $line"
}

do_export() {
  free
  say "thermal before: $(thermal)"
  export_ n1 "1080p" export1080 && probe "$WORK/export1080.mp4"
  say "thermal after:  $(thermal)"
  say "export 4K30: not measured on this phone if its encoder stops at 1080p (the sheet greys 4K)"
}

probe() {
  local cp="$WORK/probe"; mkdir -p "$cp"
  [ -f "$cp/Mp4ProbeKt.class" ] || (cd "$ROOT" && SUITE=x sh tools/jvm/jc.sh Mp4Probe tools/jvm/Mp4Probe.kt >/dev/null 2>&1; cp -r "${TMPDIR:-/tmp}"/squish-jvm/out-Mp4Probe/* "$cp/" 2>/dev/null)
  local std; std=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/*/kotlin-stdlib-2.0.20.jar | head -1)
  say "  probe: $("$HOME/.jdks/jbr-21.0.11/bin/java" -cp "$(cygpath -m "$cp");$(cygpath -m "$std")" Mp4ProbeKt "$(cygpath -m "$1")" 2>&1 | head -6 | tr '\n' ' ')"
}

quality() {
  free
  export_ plain "Original" quality || return
  local W; W=$(cygpath -w "$WORK")
  for x in quality source; do rm -rf "$WORK/frames_$x"; done
  powershell -NoProfile -ExecutionPolicy Bypass -File "$ROOT/tools/desktop/frames.ps1" -Video "$W\\quality.mp4" -OutDir "$W\\frames_quality" -Count 10 -Width 960 >/dev/null
  powershell -NoProfile -ExecutionPolicy Bypass -File "$ROOT/tools/desktop/frames.ps1" -Video "$(cygpath -w "$MEDIA/bench_1.mp4")" -OutDir "$W\\frames_source" -Count 10 -Width 960 >/dev/null
  local std; std=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/*/kotlin-stdlib-2.0.20.jar | head -1)
  (cd "$ROOT" && sh tools/jvm/jc.sh FrameCompare tools/jvm/FrameCompare.kt >/dev/null 2>&1)
  local out="$(cygpath -m "${TMPDIR:-/tmp}")/squish-jvm/out-FrameCompare"
  say "quality (plain 10 s, Standard): $("$HOME/.jdks/jbr-21.0.11/bin/java" -cp "$out;$(cygpath -m "$std")" FrameCompareKt "$(cygpath -m "$WORK/frames_source")" "$(cygpath -m "$WORK/frames_quality")" | tail -1), $(stat -c %s "$WORK/quality.mp4") bytes"
}

# Pick to playable: each file opened as "Open with" opens it, timed from the
# intent to the editor's first frame (SquishPerf "open N ms first frame").
import_() {
  free
  local f id t line
  for f in bench_1.mp4 import_4k60_h264.mp4 import_hevc10.mp4 import_hlg.mp4 import_hdr10.mp4; do
    id=$(media_id "$f"); [ -n "$id" ] || { say "import $f: not on the phone"; continue; }
    sh_ am force-stop $PKG; sleep 2; sh_ logcat -c
    t=$(sh_ am start -W -a android.intent.action.VIEW -d "content://media/external/video/media/$id" -t video/mp4 \
        --grant-read-uri-permission -n $PKG/.MainActivity | grep -E '^TotalTime' | tr -dc 0-9)
    line=""
    for i in $(seq 90); do line=$(perf_lines | grep -oE 'open [0-9]+ ms first frame' | head -1); [ -n "$line" ] && break; sleep 2; done
    local conv; conv=$(sh_ logcat -d | grep -m1 -iE "Converting|MediaConversion" | cut -c1-120)
    say "import $f: activity ${t:-?} ms; ${line:-no first frame within 180 s}${conv:+; conversion: $conv}"
  done
}

teardown() {
  sh_ am force-stop $PKG
  # Every project slot that was not there before setup: the four bench edits,
  # and the ones each import made.
  local slot
  for slot in $(sh_ run-as $PKG ls files/projects | grep -oE '^j[0-9a-f]+' | sort -u); do
    echo " ${PRE_SLOTS:-} " | grep -q " $slot " && continue
    for s in json bak.json meta.json pending.snap.json snap.json; do sh_ run-as $PKG rm -f "files/projects/$slot.$s" 2>/dev/null; done
  done
  for f in "$MEDIA"/*.mp4; do
    local id; id=$(media_id "$(basename "$f")")
    [ -n "$id" ] && sh_ content delete --uri "content://media/external/video/media/$id"
  done
  sh_ pm revoke $PKG android.permission.READ_MEDIA_VIDEO
  sh_ pm revoke $PKG android.permission.READ_MEDIA_IMAGES
  : > "$STATE"
  say "teardown done"
}

case "$STEP" in
  setup) setup ;; perf) perf_install ;; coldstart) coldstart ;; open) open ;; scrub) scrub ;; scrubfs) scrubfs ;; tour) tour ;;
  playback) playback ;; export) do_export ;; quality) quality ;; import) import_ ;; teardown) teardown ;;
  measure) coldstart; open; scrub; playback; do_export; quality; import_ ;;
  projects) k=1; for v in plain n0 n1 n3; do make_project $v $k; k=$((k+1)); done ;;
  *) echo "unknown step $STEP"; exit 1 ;;
esac
