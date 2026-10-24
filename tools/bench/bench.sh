#!/usr/bin/env bash
# EDGE_PLAN A1: the benchmark harness. Runs the fixed benchmark edit on an
# attached phone and prints one block of numbers for docs/BENCHMARKS.md.
#
#   sh tools/bench/bench.sh <serial> <media-dir> [label]
#
# <media-dir> is the benchmedia-bench artifact (tools/native/make_bench_media.sh),
# unpacked. The phone must be free (no call), unlocked, Squish's debug build on
# it for the setup (run-as writes the project), and the release build
# (assemblePerf) is installed over it for the measuring - the numbers are
# release numbers, as CLAUDE.md says they must be.
#
# What it touches on the phone, and puts back:
#  - pushes the bench clips to /sdcard/Movies/SquishBench and deletes them by
#    MediaStore id at the end;
#  - grants Squish full video access for the run (the owner's phone has it on
#    "selected only") and revokes it at the end;
#  - makes one Squish project for the edit, and deletes it at the end;
#  - deletes each export it makes, by MediaStore id.
# Never anything else.
set -u
SERIAL=${1:?serial}; MEDIA=${2:?media dir}; LABEL=${3:-$(git rev-parse --short HEAD)}
ADB="C:/Users/vysak/AppData/Local/Android/Sdk/platform-tools/adb -s $SERIAL"
PKG=com.squish.app
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${TMPDIR:-/tmp}/squish-bench; rm -rf "$WORK"; mkdir -p "$WORK"
say() { echo "$*" | tee -a "$WORK/report.txt"; }
sh_() { MSYS_NO_PATHCONV=1 $ADB shell "$@"; }

free() { # refuse to touch a phone in use
  local call; call=$(sh_ dumpsys telephony.registry | grep -m1 mCallState | tr -dc 0-9)
  [ "$call" = "0" ] || { echo "phone is in a call; stopping"; exit 2; }
}

# Taps a node by its text or content-desc, read off a fresh accessibility dump.
tap_text() {
  sh_ rm -f /sdcard/ui.xml; sh_ uiautomator dump /sdcard/ui.xml >/dev/null
  local b; b=$(sh_ cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  [ -n "$b" ] || { say "  (could not find \"$1\" on screen)"; return 1; }
  set -- $b
  sh_ input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

perf() { sh_ logcat -d -s SquishPerf:I | grep -E " $1 " ; }

media_id() { # display name -> MediaStore id
  sh_ "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$1'\"" | grep -oE "_id=[0-9]+" | cut -d= -f2 | head -1
}

# ---- setup ---------------------------------------------------------------------
setup() {
  free
  say "== Squish benchmark $LABEL on $(sh_ getprop ro.product.model) ($(date '+%Y-%m-%d %H:%M'))"
  sh_ mkdir -p /sdcard/Movies/SquishBench
  for f in "$MEDIA"/*.mp4; do MSYS_NO_PATHCONV=1 $ADB push "$f" /sdcard/Movies/SquishBench/ >/dev/null; done
  # Files pushed through the shell are indexed when they close; give it a moment.
  for i in $(seq 20); do [ -n "$(media_id bench_6.mp4)" ] && break; sleep 2; done
  for n in 1 2 3 4 5 6 4k30_hevc; do eval "ID_$(echo $n | tr -d -)=$(media_id bench_$n.mp4)"; done
  sh_ pm grant $PKG android.permission.READ_MEDIA_VIDEO
  sh_ pm grant $PKG android.permission.READ_MEDIA_IMAGES
}

teardown() {
  for f in "$MEDIA"/*.mp4; do
    local id; id=$(media_id "$(basename "$f")")
    [ -n "$id" ] && sh_ content delete --uri "content://media/external/video/media/$id"
  done
  sh_ pm revoke $PKG android.permission.READ_MEDIA_VIDEO
  sh_ pm revoke $PKG android.permission.READ_MEDIA_IMAGES
}

"$@"
