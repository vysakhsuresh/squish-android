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
#   moving               a picture moving slowly over a busy ground, to cut out and track (F7, F8)
#   transitions          every transition kind once, one per join (F3)
#   effects              every placed effect once, a second each (F2)
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
  # adb push leaves MediaStore without a duration (a NULL the project step then
  # built a broken draft from); the scanner fills it in.
  for f in "$MEDIA"/motion/*.mp4 "$MEDIA"/proxy/*.mp4 "$MEDIA"/speech/*.mp4; do
    [ -f "$f" ] && sh_ am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Movies/SquishEdge/$(basename "$f")" >/dev/null
  done
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

moving() { # a thing moving slowly across a busy ground, to cut out and to track (F7, F8)
  free
  . "$WORK/state.$SERIAL" 2>/dev/null
  [ -n "${IDS:-}" ] || { echo "the benchmark clips are not on the phone (bench.sh setup)"; return 1; }
  # The fractal zoom under, and the colour bars over it as a 30% picture
  # keyed from left to right, a little down and growing from 25% to 40% over
  # nine seconds (a tracker must follow its size, F8): one distinct
  # textured thing, at a speed a hand or a car moves, on a ground that moves too.
  local ground thing; ground=$(echo $IDS | cut -d" " -f1); thing=$(echo $IDS | cut -d" " -f6)
  local keys='"keyframes":[{"atMs":0,"scale":0.25,"offsetXFraction":-0.32,"offsetYFraction":-0.1,"rotationDegrees":0,"easing":"Linear"},{"atMs":9000,"scale":0.4,"offsetXFraction":0.28,"offsetYFraction":0.12,"rotationDegrees":0,"easing":"Linear"}]'
  # "moving hide": the thing gone for a second in the middle (3.6-4.6 s), for a
  # tracker to lose it and find it again (F8).
  local name="Edge moving" hide=""
  if [ "${1:-}" = hide ]; then
    name="Edge moving hidden"
    hide=',"opacityKeys":[{"atMs":3500,"value":1,"easing":"Linear"},{"atMs":3600,"value":0,"easing":"Linear"},{"atMs":4600,"value":0,"easing":"Linear"},{"atMs":4700,"value":1,"easing":"Linear"}]'
  fi
  local clips; clips="$(clip_json main0 "$(uri $ground)" bench_1.mp4 0 9000 0 0 ""),$(clip_json pip1 "$(uri $thing)" bench_6.mp4 0 9000 0 1 ",\"scale\":0.3,\"offsetXFraction\":-0.32,\"offsetYFraction\":-0.1$hide" | sed 's/"scale":1,//; s/"keyframes":\[\]/'"$keys"'/')"
  draft_json "$name" "$(uri $ground)" 9000 "$clips" > "$WORK/edge_draft.json"
  sed -i 's/"sourceDurationMs":[0-9]*/"sourceDurationMs":10000/g' "$WORK/edge_draft.json"
  slot_for "$ground" "$name" "$WORK/edge_draft.json"
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

transitions() { # every transition kind once, on the joins of 2 s shots that alternate between two benchmark clips (F3)
  free
  . "$WORK/state.$SERIAL" 2>/dev/null
  [ -n "${IDS:-}" ] || { echo "the benchmark clips are not on the phone (bench.sh setup)"; return 1; }
  # In TransitionType's order, Cut left out. 800 ms each into 2 s shots: shot k
  # starts at 1200k and its join's middle is at 1200k + 400, an even 1.2 s apart,
  # so frames.ps1 -Count 51 -FromSec 1.6 -ToSec 61.6 reads every middle.
  local kinds="CrossFade DipToBlack DipToWhite SlideLeft SlideRight SlideUp SlideDown Push WipeRight WipeLeft ZoomIn Jitter Flicker Flash Glow PushRight PushUp PushDown Whip WipeUp WipeDown ZoomOut PopIn Blackout Defocus BurnOut Iris IrisClose Diamond Star Heart Clock Blinds BlindsSide Doors Split Diagonal Checker PixelDissolve Stripes Flip FlipUp Cube CubeUp Spin ZoomBlur Ripple Pixelate MirrorFold GlitchCut RgbSplit"
  local a b; a=$(echo $IDS | cut -d" " -f1); b=$(echo $IDS | cut -d" " -f2)
  local clips; clips=$(clip_json shot0 "$(uri $a)" bench_1.mp4 0 2000 0 0 "")
  local k=0 kind id label
  for kind in $kinds; do
    k=$((k + 1))
    if [ $((k % 2)) = 1 ]; then id=$b; label=bench_2.mp4; else id=$a; label=bench_1.mp4; fi
    clips="$clips,$(clip_json shot$k "$(uri $id)" $label $(( (k % 4) * 1000 )) $(( (k % 4) * 1000 + 2000 )) $(( k * 1200 )) 0 "" | sed "s/\"transitionType\":\"None\",\"transitionMs\":500/\"transitionType\":\"$kind\",\"transitionMs\":800/")"
  done
  draft_json "Edge transitions" "$(uri $a)" $(( k * 1200 + 2000 )) "$clips" > "$WORK/edge_draft.json"
  sed -i 's/"sourceDurationMs":[0-9]*/"sourceDurationMs":10000/g' "$WORK/edge_draft.json"
  echo "$k joins"
  slot_for "$a" "Edge transitions" "$WORK/edge_draft.json"
}

effects() { # every placed effect once, a second each, over seven benchmark shots end to end (F2)
  free
  . "$WORK/state.$SERIAL" 2>/dev/null
  [ -n "${IDS:-}" ] || { echo "the benchmark clips are not on the phone (bench.sh setup)"; return 1; }
  # In EffectKind's order. Effect k runs from 1000k to 1000k + 1000, so the
  # frame at 1000k + 500 is it: frames.ps1 -Count 61 -FromSec 0.5 -ToSec 60.5.
  local kinds="Shake Punch ZoomIn Glitch Flash Vhs Mono Invert Blur Rainbow RgbSplit Strobe Earthquake Heartbeat Static OldFilm Dream NegativePulse Trippy Sway ZoomOut LightLeak FilmBurn Bokeh LensFlare GlowPulse Vignette Snow Rain Sparkles Hearts Confetti Dust ZoomBlur Fisheye Kaleidoscope Mirror MirrorUp Split2 Split3 Split4 Ripple Pixelate VhsTracking Super8 EightBit Camcorder Sepia WashedOut BlockGlitch Tear ChromaWave Scramble Shake3D Spin Pendulum BounceZoom Duotone Posterize Warm Cool"
  local clips="" i id
  for i in 0 1 2 3 4 5 6; do
    id=$(echo $IDS | cut -d" " -f$(( i % 6 + 1 )))
    [ -n "$clips" ] && clips="$clips,"
    clips="$clips$(clip_json shot$i "$(uri $id)" "bench_$(( i % 6 + 1 )).mp4" 0 9000 $(( i * 9000 )) 0 "")"
  done
  local fx="" k=0 kind
  for kind in $kinds; do
    [ -n "$fx" ] && fx="$fx,"
    fx="$fx{\"id\":\"fx$k\",\"kind\":\"$kind\",\"startMs\":$(( k * 1000 )),\"endMs\":$(( k * 1000 + 1000 )),\"intensity\":0.8,\"amount\":0.5}"
    k=$((k + 1))
  done
  local first; first=$(echo $IDS | cut -d" " -f1)
  draft_json "Edge effects" "$(uri $first)" 63000 "$clips" | sed "s/\"effects\":\[\]/\"effects\":[$fx]/" > "$WORK/edge_draft.json"
  sed -i 's/"sourceDurationMs":[0-9]*/"sourceDurationMs":10000/g' "$WORK/edge_draft.json"
  echo "$k effects"
  slot_for "$first" "Edge effects" "$WORK/edge_draft.json"
}

photos() { # N generated photos (PNG) into Pictures/SquishEdge, for the photo picker (C6)
  free
  local n=${1:-50} i
  sh_ mkdir -p /sdcard/Pictures/SquishEdge
  local dir="$WORK/edge_photos"; mkdir -p "$dir"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.Drawing; for (\$i = 1; \$i -le $n; \$i++) { \$b = New-Object System.Drawing.Bitmap 1600, 1200; \$g = [System.Drawing.Graphics]::FromImage(\$b); \$g.Clear([System.Drawing.Color]::FromArgb(255, (\$i * 37) % 256, (\$i * 91) % 256, (\$i * 53) % 256)); \$g.DrawString(\"Squish \$i\", (New-Object System.Drawing.Font 'Arial', 120), [System.Drawing.Brushes]::White, 100, 450); \$b.Save(\"$(cygpath -w "$dir")\\edge_photo_\$i.jpg\", [System.Drawing.Imaging.ImageFormat]::Jpeg); \$g.Dispose(); \$b.Dispose() }"
  for i in $(seq 1 $n); do MSYS_NO_PATHCONV=1 $ADB push "$(cygpath -m "$dir/edge_photo_$i.jpg")" /sdcard/Pictures/SquishEdge/ >/dev/null; done
  sleep 5
  local ids=""
  for i in $(seq 1 $n); do local id; id=$(media_id "edge_photo_$i.jpg" images); [ -n "$id" ] && ids="$ids images:$id"; done
  remember PUSHED "${PUSHED:-}$ids"
  echo "$(echo $ids | wc -w) photos on the phone"
}

# E1: Whisper on the phone over a speech file, scored against the words spoken.
#   whisper NAME [lang]     NAME is english, hindi or malayalam (speech/NAME.wav and .txt)
whisper() {
  free
  local name=$1 lang=${2:-}
  local wav="$MEDIA/speech/$name.wav" ref="$MEDIA/speech/$name.txt"
  [ -f "$wav" ] || { echo "no $wav"; return 1; }
  MSYS_NO_PATHCONV=1 $ADB push "$wav" /data/local/tmp/sq_speech.wav >/dev/null
  sh_ run-as $PKG cp /data/local/tmp/sq_speech.wav files/sq_speech.wav
  # A probe still open from the last run would only come to the front.
  sh_ am force-stop $PKG
  sh_ logcat -c
  local extra=""; [ -n "$lang" ] && extra="--es lang $lang"
  sh_ am start -n $PKG/.debug.WhisperProbeActivity --es path /data/data/$PKG/files/sq_speech.wav $extra >/dev/null
  local i text=""
  for i in $(seq 120); do
    text=$(sh_ logcat -d -s SquishWhisper:I | grep -m1 "text: " | sed 's/.*text: //')
    [ -n "$text" ] && break
    sh_ logcat -d -s SquishWhisper:I | grep -q "gave nothing\|could not" && break
    sleep 5
  done
  sh_ logcat -d -s SquishWhisper:I | grep -E "library|sound:|whisper:" | sed 's/^.*SquishWhisper: //'
  # The whole text from the probe's own file: logcat cuts a line at about 4 KB, and two
  # minutes of speech is longer than that, so the logged "text:" is only its start.
  [ -n "$text" ] && MSYS_NO_PATHCONV=1 $ADB exec-out run-as $PKG cat files/whisper_report.txt | tail -1 > "$WORK/whisper_$name.txt" || : > "$WORK/whisper_$name.txt"
  local std; std=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/*/kotlin-stdlib-2.0.20.jar | head -1)
  (cd "$ROOT" && sh tools/jvm/jc.sh Wer tools/jvm/Wer.kt >/dev/null 2>&1)
  local out="$(cygpath -m "${TMPDIR:-/tmp}")/squish-jvm/out-Wer"
  echo "$name: $("$HOME/.jdks/jbr-21.0.11/bin/java" -cp "$out;$(cygpath -m "$std")" WerKt "$(cygpath -m "$ref")" "$(cygpath -m "$WORK/whisper_$name.txt")")"
  sh_ run-as $PKG rm -f files/sq_speech.wav
  sh_ rm -f /data/local/tmp/sq_speech.wav
}

# E5: a converted voice put on the phone without the download (the release is the
# owner's to publish), made to speak the opening of the reference text, and what
# it said read back by Whisper and scored against what it was given.
#   voice CODE NAME [speaker]    CODE ml|hi|en, NAME malayalam|hindi|english
voice() {
  free
  local code=$1 name=$2 speaker=${3:-female}
  local zip="C:/Users/vysak/AppData/Local/Temp/voice-$code/voice-$code.zip"
  [ -f "$zip" ] || { echo "no $zip"; return 1; }
  local dir="$WORK/voice_$code"; rm -rf "${dir:?}"; mkdir -p "$dir"
  (cd "$dir" && unzip -q -o "$zip")
  sh_ run-as $PKG mkdir -p files/voices/$code
  local f
  for f in encoder.tflite decoder.tflite vocoder.tflite voice.json; do
    # A Windows path: MSYS_NO_PATHCONV keeps Git Bash off the local side too.
    MSYS_NO_PATHCONV=1 $ADB push "$(cygpath -m "$dir/$f")" /data/local/tmp/sq_$f >/dev/null
    sh_ run-as $PKG cp /data/local/tmp/sq_$f files/voices/$code/$f
    sh_ rm -f /data/local/tmp/sq_$f
  done
  # The first three sentences of the reference: long enough to score, short enough to be quick.
  local text; text=$(perl "$ROOT/tools/bench/first_sentences.pl" "$MEDIA/speech/$name.txt" 3)
  printf '%s' "$text" > "$WORK/voice_$code.txt"
  MSYS_NO_PATHCONV=1 $ADB push "$(cygpath -m "$WORK/voice_$code.txt")" /data/local/tmp/sq_voice.txt >/dev/null
  sh_ run-as $PKG cp /data/local/tmp/sq_voice.txt files/sq_voice.txt
  sh_ am force-stop $PKG
  sh_ logcat -c
  sh_ am start -n $PKG/.debug.VoiceProbeActivity --es lang $code --es speaker $speaker --es textFile /data/data/$PKG/files/sq_voice.txt >/dev/null
  local i; for i in $(seq 60); do sh_ logcat -d -s SquishVoice:I | grep -q "wrote\|failed" && break; sleep 3; done
  sh_ logcat -d -s SquishVoice:I | sed 's/^.*SquishVoice: //'
  # Whisper over what the voice said, scored against the words it was given.
  sh_ logcat -c
  sh_ am start -n $PKG/.debug.WhisperProbeActivity --es path /data/data/$PKG/files/voice_probe.wav --es lang $code >/dev/null
  local heard=""
  for i in $(seq 90); do heard=$(sh_ logcat -d -s SquishWhisper:I | grep -m1 "text: " | sed 's/.*text: //'); [ -n "$heard" ] && break; sleep 4; done
  printf '%s' "$heard" > "$WORK/voice_${code}_heard.txt"
  local std; std=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/*/kotlin-stdlib-2.0.20.jar | head -1)
  (cd "$ROOT" && sh tools/jvm/jc.sh Wer tools/jvm/Wer.kt >/dev/null 2>&1)
  local out="$(cygpath -m "${TMPDIR:-/tmp}")/squish-jvm/out-Wer"
  echo "voice $code ($speaker), heard back by Whisper: $("$HOME/.jdks/jbr-21.0.11/bin/java" -cp "$out;$(cygpath -m "$std")" WerKt "$(cygpath -m "$WORK/voice_$code.txt")" "$(cygpath -m "$WORK/voice_${code}_heard.txt")")"
  MSYS_NO_PATHCONV=1 $ADB exec-out run-as $PKG cat files/voice_probe.wav > "$WORK/voice_${code}_probe.wav"
  sh_ run-as $PKG rm -f files/sq_voice.txt
}

teardown() {
  sh_ am force-stop $PKG
  local e
  for e in ${PUSHED:-}; do sh_ content delete --uri "content://media/external/${e%%:*}/media/${e#*:}"; done
  # The files the tests rendered, by the ids recorded as each landed - never by name.
  for e in ${EXPORT_IDS:-}; do sh_ content delete --uri "content://media/external/video/media/$e"; done
  # A test render makes its size the next project's default (480p for most of
  # them); put the factory start back, as Settings' "Start new projects at
  # Original again" does.
  [ -n "${EXPORT_IDS:-}" ] && sh_ run-as $PKG rm -f shared_prefs/export_defaults.xml
  local slot
  for slot in ${SLOTS:-}; do
    for s in json bak.json meta.json pending.snap.json snap.json; do sh_ run-as $PKG rm -f "files/projects/$slot.$s" 2>/dev/null; done
  done
  : > "$STATE"
  echo "teardown done"
}

case "$STEP" in
  push) push ;; project) project "$@" ;; many) many "$@" ;; moving) moving "$@" ;; transitions) transitions ;; effects) effects ;; photos) photos "$@" ;; whisper) whisper "$@" ;; voice) voice "$@" ;; teardown) teardown ;;
  *) echo "unknown step $STEP"; exit 1 ;;
esac
