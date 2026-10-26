#!/usr/bin/env bash
# The rest of docs/EDGE_PLAN.md on the phone: the footage made by
# tools/native/make_edge_media.sh (edgemedia.yml) pushed, and the projects that
# test it made from drafts, as bench.sh makes the benchmark edit's.
#
#   sh tools/bench/edge.sh <serial> <edge-media-dir> <step> [args]
#
#   push                 the clips and the speech onto the phone (Movies/SquishEdge)
#   project NAME FILE [speed=0.25] [blend] [denoise=0.8] [deflicker=0.8] [overlays=6:FILE]
#                        a project of one clip of FILE (a name under the pushed set)
#   many N               a project of N shots cut from the benchmark clips (C6)
#   photos N             N generated photos into Pictures/SquishEdge (C6), for the picker
#   teardown             every file and project this made, by MediaStore id and by slot
#
# Needs the debug build for run-as. Nothing it did not make is touched: the
# files are deleted by the ids it recorded, the projects by the slots.
set -u
SERIAL=${1:?serial}; MEDIA=${2:?edge media dir}; STEP=${3:?step}; shift 3
ADB="C:/Users/vysak/AppData/Local/Android/Sdk/platform-tools/adb -s $SERIAL"
PKG=com.squish.app
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/build/bench"; mkdir -p "$WORK"
STATE="$WORK/edge.$SERIAL"; touch "$STATE"; . "$STATE"
sh_() { MSYS_NO_PATHCONV=1 $ADB shell "$@"; }
remember() { echo "$1=\"$2\"" >> "$STATE"; eval "$1=\"$2\""; }
free() { local c; c=$(sh_ dumpsys telephony.registry | grep -m1 mCallState | tr -dc 0-9); [ "$c" = 0 ] || { echo "in a call"; exit 2; }; }
media_id() { # display name, collection (video|images|audio)
  sh_ "content query --uri content://media/external/${2:-video}/media --projection _id --where \"_display_name='$1'\"" | grep -oE "_id=[0-9]+" | cut -d= -f2 | head -1
}
uri() { echo "content:\\/\\/media\\/external\\/${2:-video}\\/media\\/$1"; }

push() {
  free
  sh_ mkdir -p /sdcard/Movies/SquishEdge /sdcard/Music/SquishEdge
  local f
  for f in "$MEDIA"/motion/*.mp4 "$MEDIA"/proxy/*.mp4 "$MEDIA"/speech/*.mp4; do
    [ -f "$f" ] && MSYS_NO_PATHCONV=1 $ADB push "$f" /sdcard/Movies/SquishEdge/ >/dev/null
  done
  for f in "$MEDIA"/speech/*.wav; do MSYS_NO_PATHCONV=1 $ADB push "$f" /sdcard/Music/SquishEdge/ >/dev/null; done
  sleep 6
  local ids=""
  for f in "$MEDIA"/motion/*.mp4 "$MEDIA"/proxy/*.mp4 "$MEDIA"/speech/*.mp4; do
    local n; n=$(basename "$f"); local id; id=$(media_id "$n")
    [ -n "$id" ] && ids="$ids video:$id" && echo "$n -> $id"
  done
  for f in "$MEDIA"/speech/*.wav; do
    local n; n=$(basename "$f"); local id; id=$(media_id "$n" audio)
    [ -n "$id" ] && ids="$ids audio:$id" && echo "$n -> $id"
  done
  remember PUSHED "${PUSHED:-}$ids"
  sh_ pm grant $PKG android.permission.READ_MEDIA_VIDEO
  sh_ pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null
  sh_ pm grant $PKG android.permission.READ_MEDIA_IMAGES
}

clip_json() { # id uri label inMs outMs startMs layer extra
  echo "{\"id\":\"$1\",\"uri\":\"$2\",\"label\":\"$3\",\"sourceInMs\":$4,\"sourceOutMs\":$5,\"timelineStartMs\":$6,\"sourceDurationMs\":$5,\"volume\":1,\"speedPoints\":[],\"transitionType\":\"None\",\"transitionMs\":500,\"layer\":$7,\"opacity\":1,\"scale\":1,\"offsetXFraction\":0,\"offsetYFraction\":0,\"rotation\":0,\"keyframes\":[],\"stabilizer\":[],\"arrivalMs\":500,\"leavingMs\":500,\"loopMs\":1200$8}"
}

draft_json() { # name sourceUri durationMs clipsJson
  echo "{\"version\":14,\"name\":\"$1\",\"sourceUri\":\"$2\",\"durationMs\":$3,\"playheadMs\":0,\"outputP\":0,\"fitToSize\":false,\"targetSizeMb\":16,\"audioOnly\":false,\"outputFps\":0,\"exportQuality\":\"Recommended\",\"hevc\":false,\"keepHdr\":false,\"muteOriginal\":false,\"originalVolume\":1,\"rotationDegrees\":0,\"cropAspect\":\"Original\",\"snapToMarkers\":true,\"stabilizeStrength\":0.5,\"pixelsPerSecond\":12.4,\"markers\":[],\"clips\":[$4],\"textOverlays\":[],\"effects\":[],\"audioClips\":[],\"savedAtMillis\":0}"
}

# A slot made by Squish itself (a VIEW of the clip), its draft then replaced and its card named.
slot_for() { # media id, name, draft file
  local id=$1 name=$2 draft=$3
  sh_ am start -a android.intent.action.VIEW -d "content://media/external/video/media/$id" -t video/mp4 --grant-read-uri-permission -n $PKG/.MainActivity >/dev/null
  sleep 8
  sh_ am force-stop $PKG
  local slot; slot=$(sh_ run-as $PKG ls -t files/projects | grep -E '\.meta\.json$' | head -1 | sed 's/\.meta\.json//' | tr -d '\r')
  for s in bak.json pending.snap.json; do sh_ run-as $PKG rm -f "files/projects/$slot.$s"; done
  MSYS_NO_PATHCONV=1 $ADB shell "run-as $PKG sh -c 'cat > files/projects/$slot.json'" < "$draft"
  MSYS_NO_PATHCONV=1 $ADB exec-out run-as $PKG cat "files/projects/$slot.meta.json" | sed "s/\"title\":\"[^\"]*\"/\"title\":\"$name\"/" > "$WORK/edge_meta.json"
  MSYS_NO_PATHCONV=1 $ADB shell "run-as $PKG sh -c 'cat > files/projects/$slot.meta.json'" < "$WORK/edge_meta.json"
  remember SLOTS "${SLOTS:-} $slot"
  echo "project $name -> $slot"
}

project() { # NAME FILE [options]
  free
  local name=$1 file=$2; shift 2
  local id; id=$(media_id "$file"); [ -n "$id" ] || { echo "$file is not on the phone (push first)"; return 1; }
  local dur; dur=$(sh_ "content query --uri content://media/external/video/media --projection duration --where \"_id=$id\"" | grep -oE "duration=[0-9]+" | cut -d= -f2)
  local extra="" speed=1 overlays="" o
  for o in "$@"; do
    case $o in
      speed=*) speed=${o#speed=}; extra="$extra,\"speed\":$speed" ;;
      blend) extra="$extra,\"frameBlend\":true" ;;
      denoise=*) extra="$extra,\"denoise\":${o#denoise=}" ;;
      deflicker=*) extra="$extra,\"deflicker\":${o#deflicker=}" ;;
      overlays=*) overlays=${o#overlays=} ;;
    esac
  done
  local played; played=$(awk "BEGIN{printf \"%d\", $dur / $speed}")
  local clips; clips=$(clip_json main0 "$(uri $id)" "$file" 0 "$dur" 0 0 "$extra")
  if [ -n "$overlays" ]; then
    # Six (or N) copies of FILE over the shot, a 3 x 2 grid of 30% pictures (C4).
    local n=${overlays%%:*} of=${overlays#*:} oid; oid=$(media_id "$of")
    local cx=("-0.33" "0" "0.33" "-0.33" "0" "0.33") cy=("-0.25" "-0.25" "-0.25" "0.25" "0.25" "0.25") k
    for k in $(seq 1 $n); do
      clips="$clips,$(clip_json pip$k "$(uri $oid)" "$of" 0 "$dur" 0 $k ",\"scale\":0.3,\"offsetXFraction\":${cx[$((k-1))]},\"offsetYFraction\":${cy[$((k-1))]}" | sed 's/"scale":1,//')"
    done
  fi
  draft_json "$name" "$(uri $id)" "$played" "$clips" > "$WORK/edge_draft.json"
  slot_for "$id" "$name" "$WORK/edge_draft.json"
}

many() { # N shots cut from the benchmark clips, half a second each (C6)
  free
  local n=${1:-200}; . "$WORK/state.$SERIAL" 2>/dev/null
  [ -n "${IDS:-}" ] || { echo "the benchmark clips are not on the phone (bench.sh setup)"; return 1; }
  local clips="" i t=0
  for i in $(seq 0 $((n - 1))); do
    local id; id=$(echo $IDS | cut -d" " -f$(( i % 6 + 1 )))
    local in=$(( (i * 700) % 8500 ))
    [ -n "$clips" ] && clips="$clips,"
    clips="$clips$(clip_json shot$i "$(uri $id)" "bench_$(( i % 6 + 1 )).mp4" $in $(( in + 500 )) $t 0 "")"
    t=$(( t + 500 ))
  done
  local first; first=$(echo $IDS | cut -d" " -f1)
  draft_json "Edge $n shots" "$(uri $first)" $t "$clips" > "$WORK/edge_draft.json"
  # Each shot's own window, not the file's whole length.
  sed -i 's/"sourceDurationMs":[0-9]*/"sourceDurationMs":10000/g' "$WORK/edge_draft.json"
  slot_for "$first" "Edge $n shots" "$WORK/edge_draft.json"
}

photos() { # N generated photos (PNG) into Pictures/SquishEdge, for the photo picker (C6)
  free
  local n=${1:-50} i
  sh_ mkdir -p /sdcard/Pictures/SquishEdge
  local dir="$WORK/edge_photos"; mkdir -p "$dir"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.Drawing; for (\$i = 1; \$i -le $n; \$i++) { \$b = New-Object System.Drawing.Bitmap 1600, 1200; \$g = [System.Drawing.Graphics]::FromImage(\$b); \$g.Clear([System.Drawing.Color]::FromArgb(255, (\$i * 37) % 256, (\$i * 91) % 256, (\$i * 53) % 256)); \$g.DrawString(\"Squish \$i\", (New-Object System.Drawing.Font 'Arial', 120), [System.Drawing.Brushes]::White, 100, 450); \$b.Save(\"$(cygpath -w "$dir")\\edge_photo_\$i.jpg\", [System.Drawing.Imaging.ImageFormat]::Jpeg); \$g.Dispose(); \$b.Dispose() }"
  for i in $(seq 1 $n); do MSYS_NO_PATHCONV=1 $ADB push "$dir/edge_photo_$i.jpg" /sdcard/Pictures/SquishEdge/ >/dev/null; done
  sleep 5
  local ids=""
  for i in $(seq 1 $n); do local id; id=$(media_id "edge_photo_$i.jpg" images); [ -n "$id" ] && ids="$ids images:$id"; done
  remember PUSHED "${PUSHED:-}$ids"
  echo "$(echo $ids | wc -w) photos on the phone"
}

teardown() {
  sh_ am force-stop $PKG
  local e
  for e in ${PUSHED:-}; do sh_ content delete --uri "content://media/external/${e%%:*}/media/${e#*:}"; done
  local slot
  for slot in ${SLOTS:-}; do
    for s in json bak.json meta.json pending.snap.json snap.json; do sh_ run-as $PKG rm -f "files/projects/$slot.$s" 2>/dev/null; done
  done
  : > "$STATE"
  echo "teardown done"
}

case "$STEP" in
  push) push ;; project) project "$@" ;; many) many "$@" ;; photos) photos "$@" ;; teardown) teardown ;;
  *) echo "unknown step $STEP"; exit 1 ;;
esac
