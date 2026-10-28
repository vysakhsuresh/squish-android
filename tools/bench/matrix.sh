#!/usr/bin/env bash
# EDGE_PLAN B1: every file of the footage matrix through Squish on a phone.
#
#   sh tools/bench/matrix.sh <serial> <matrix-dir> [name-filter]
#
# <matrix-dir> is the benchmedia-matrix artifact (tools/native/make_bench_media.sh).
# Squish's DEBUG build must be installed (run-as, and the sync probe). For each
# file: pushed, opened as "Open with" opens it, first frame waited for, played
# three seconds, exported at 480p, the export probed (tools/jvm/Mp4Probe.kt)
# and, for the sync clips, the source and the export read by
# debug/SyncProbeActivity. A screenshot of the editor is kept for each. Every
# file, export and project it makes is removed at the end, the access it
# grants revoked. One line per file in build/matrix/report.txt.
set -u
SERIAL=${1:?serial}; DIR=${2:?matrix dir}; ONLY=${3:-}
ADB="C:/Users/vysak/AppData/Local/Android/Sdk/platform-tools/adb -s $SERIAL"
PKG=com.squish.app
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/build/matrix"; mkdir -p "$WORK"
REPORT="$WORK/report.txt"
say() { echo "$*" | tee -a "$REPORT"; }
sh_() { MSYS_NO_PATHCONV=1 $ADB shell "$@"; }
free() { local c; c=$(sh_ dumpsys telephony.registry | grep -m1 mCallState | tr -dc 0-9); [ "$c" = "0" ] || { echo "phone not free (call state '$c'); stopping"; exit 2; }; }
media_id() { sh_ "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$1'\"" | grep -oE "_id=[0-9]+" | cut -d= -f2 | head -1; }
newest_export() { sh_ "content query --uri content://media/external/video/media --projection _id:_display_name --sort 'date_added DESC'" | grep -m1 "squish_" | grep -oE "_id=[0-9]+" | cut -d= -f2; }
dump() { sh_ rm -f /sdcard/ui.xml; sh_ uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; sh_ cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n' > "$WORK/ui.txt"; }
tap_text() {
  dump
  local b; b=$(grep -E "(text|content-desc)=\"$1\"" "$WORK/ui.txt" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  [ -n "$b" ] || return 1
  set -- $b; sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
probe() {
  local cp="$WORK/probe"; mkdir -p "$cp"
  [ -f "$cp/Mp4ProbeKt.class" ] || { (cd "$ROOT" && sh tools/jvm/jc.sh Mp4Probe tools/jvm/Mp4Probe.kt >/dev/null 2>&1); cp -r "${TMPDIR:-/tmp}"/squish-jvm/out-Mp4Probe/* "$cp/" 2>/dev/null; }
  local std; std=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/*/kotlin-stdlib-2.0.20.jar | head -1)
  "$HOME/.jdks/jbr-21.0.11/bin/java" -cp "$(cygpath -m "$cp");$(cygpath -m "$std")" Mp4ProbeKt "$(cygpath -m "$1")" 2>&1 | grep -E "track|frame durations" | tr -s ' ' | tr '\n' ' '
}
sync_probe() { # content uri -> report line
  sh_ logcat -c
  sh_ am start -n $PKG/.debug.SyncProbeActivity --es uri "$1" >/dev/null
  local i line; for i in $(seq 120); do line=$(sh_ logcat -d -s SquishSync:I | grep -E "flashes [0-9]+|could not be read" | tail -1); [ -n "$line" ] && break; sleep 3; done
  echo "$line" | sed 's/.*SquishSync: //'
}

free
: > "$REPORT"
say "== footage matrix on $(sh_ getprop ro.product.model | tr -d '\r') at $(git -C "$ROOT" rev-parse --short HEAD), $(date '+%Y-%m-%d %H:%M')"
PRE=$(sh_ run-as $PKG ls files/projects | grep -oE '^j[0-9a-f]+' | sort -u | tr '\n' ' ')
sh_ mkdir -p /sdcard/Movies/SquishMatrix
sh_ pm grant $PKG android.permission.READ_MEDIA_VIDEO

for f in "$DIR"/*; do
  name=$(basename "$f")
  [ -n "$ONLY" ] && ! echo "$name" | grep -q "$ONLY" && continue
  free
  MSYS_NO_PATHCONV=1 $ADB push "$f" /sdcard/Movies/SquishMatrix/ >/dev/null
  id=""; for i in $(seq 30); do id=$(media_id "$name"); [ -n "$id" ] && break; sleep 2; done
  [ -n "$id" ] || { say "$name: not indexed by MediaStore - skipped"; continue; }
  sh_ am force-stop $PKG; sleep 1; sh_ logcat -c
  sh_ am start -a android.intent.action.VIEW -d "content://media/external/video/media/$id" --grant-read-uri-permission -n $PKG/.MainActivity >/dev/null
  first=""; for i in $(seq 45); do first=$(sh_ logcat -d -s SquishPerf:I | grep -oE 'open [0-9]+ ms first frame' | head -1); [ -n "$first" ] && break; sleep 2; done
  MSYS_NO_PATHCONV=1 $ADB exec-out screencap -p > "$WORK/$name.png"
  crash=$(sh_ logcat -d | grep -cE "FATAL EXCEPTION|ANR in $PKG")
  play="not played"
  if tap_text "Play"; then
    xy=$(grep -E '(text|content-desc)="Play"' "$WORK/ui.txt" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
    sleep 3; set -- $xy; sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2
    play="played $(sh_ logcat -d -s SquishPerf:I | grep -oE 'pause [0-9]+ ms at=[0-9]+' | tail -1 | grep -oE 'at=[0-9]+')"
  fi
  # A scrub along the strip and a cut where it stops (B1: "scrubs, cuts"), so
  # the export below is of the cut edit. The strip is dragged on the main
  # track's row - the row the "Mute audio" button heads.
  cut="not cut"
  # A moment for the screen to settle: an HDR file's preview copy animates its
  # notice while it is made, and uiautomator cannot read a screen that never idles.
  for i in 1 2 3 4 5 6; do dump; grep -q 'Mute audio' "$WORK/ui.txt" && break; sleep 3; done
  mute=$(grep -E '(text|content-desc)="Mute audio"' "$WORK/ui.txt" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
  if [ -n "$mute" ]; then
    set -- $mute; row=$(( ($2 + $4) / 2 )); wpx=$(sh_ wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)
    sh_ input swipe $(( wpx * 62 / 100 )) $row $(( wpx * 52 / 100 )) $row 600; sleep 1.5
    dump
    at=$(grep -oE 'content-desc="Playhead at [^"]*"' "$WORK/ui.txt" | head -1 | sed 's/.*Playhead at //; s/"$//')
    tap_text "Split at the playhead" || true; sleep 1.5
    dump
    clips=$(grep -oE 'text="[0-9]+ clips? · [^"]*"' "$WORK/ui.txt" | head -1 | sed 's/text="//; s/ · .*//')
    crash=$(( crash + $(sh_ logcat -d | grep -cE "FATAL EXCEPTION|ANR in $PKG") ))
    cut="scrubbed to ${at:-?}, cut there: ${clips:-?}"
  fi
  exported="no export"
  if tap_text "Export"; then
    sleep 2; tap_text "480p" || true; sleep 1; sh_ logcat -c
    # Only a file this render made is read and deleted (bench.sh export_ says why).
    before=$(newest_export)
    if tap_text "Render and save"; then
      done_=""; for i in $(seq 150); do done_=$(sh_ logcat -d | grep -m1 -oE "SquishPerf: export [0-9]+ ms .*|SquishExport: failed.*"); [ -n "$done_" ] && break; sleep 2; done
      xid=$(newest_export)
      if echo "$done_" | grep -q "SquishPerf: export" && [ -n "$xid" ] && [ "$xid" != "$before" ]; then
        MSYS_NO_PATHCONV=1 $ADB exec-out "content read --uri content://media/external/video/media/$xid" > "$WORK/$name.export.mp4"
        exported="exported (${done_#SquishPerf: }); probe: $(probe "$WORK/$name.export.mp4")"
        if echo "$name" | grep -q "^sync_"; then
          exported="$exported; sync source: $(sync_probe "content://media/external/video/media/$id"); sync export: $(sync_probe "content://media/external/video/media/$xid")"
        fi
        sh_ content delete --uri "content://media/external/video/media/$xid"
      else
        exported="export FAILED: ${done_:-no result within 300 s}"
      fi
    fi
  fi
  say "$name: ${first:-NO FIRST FRAME within 90 s}; crashes $crash; $play; $cut; $exported"
  sh_ content delete --uri "content://media/external/video/media/$id"
done

# Every project this made, the folder, the access.
sh_ am force-stop $PKG
for slot in $(sh_ run-as $PKG ls files/projects | grep -oE '^j[0-9a-f]+' | sort -u); do
  echo " $PRE " | grep -q " $slot " && continue
  for s in json bak.json meta.json pending.snap.json snap.json; do sh_ run-as $PKG rm -f "files/projects/$slot.$s" 2>/dev/null; done
done
sh_ rmdir /sdcard/Movies/SquishMatrix 2>/dev/null
sh_ pm revoke $PKG android.permission.READ_MEDIA_VIDEO
say "== done; screenshots and exports in build/matrix"
