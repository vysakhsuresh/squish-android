#!/usr/bin/env bash
# The benchmark edit as a Squish draft (EDGE_PLAN A1), written from MediaStore
# ids so every run edits the same thing.
#
#   sh tools/bench/fixture.sh <overlays 0|1|3|6> <id1> .. <id6> <id4k> <songUri> [plain]
#
# Sixty seconds: six 1080p30 clips trimmed to 9 s and 6 s of a 4K30 clip; the
# Vivid look on every shot; a Dissolve into shot 2 and a Slide left into shot
# 4; a title over the first five seconds; a Squish original under it all; and
# 0, 1, 3 or 6 picture-in-picture overlays. "plain" writes the quality edit
# instead: shot 1 alone, untrimmed, nothing on it.
set -eu
N=$1; shift
IDS=("$1" "$2" "$3" "$4" "$5" "$6"); K4=$7; SONG=$8; PLAIN=${9:-}
uri() { echo "content:\\/\\/media\\/external\\/video\\/media\\/$1"; }

clip() { # id uri label in out start layer transition scale ox oy
  cat <<EOF
{"id":"$1","uri":"$2","label":"$3","sourceInMs":$4,"sourceOutMs":$5,"timelineStartMs":$6,"sourceDurationMs":${12},"volume":1,"speedPoints":[],"transitionType":"$8","transitionMs":500,"layer":$7,"opacity":1,"scale":$9,"offsetXFraction":${10},"offsetYFraction":${11},"rotation":0,"keyframes":[],"stabilizer":[],"arrivalMs":500,"leavingMs":500,"loopMs":1200,"lookId":"vivid","lookIntensity":1}
EOF
}

if [ "$PLAIN" = plain ]; then
  CLIPS=$(clip plain0 "$(uri ${IDS[0]})" bench_1.mp4 0 10000 0 0 None 1 0 0 10000 | sed 's/,"lookId":"vivid","lookIntensity":1//')
  TEXTS=""; AUDIO=""; DUR=10000; SRC=$(uri ${IDS[0]})
else
  CLIPS=""
  for i in 0 1 2 3 4 5; do
    t=None; [ $i = 1 ] && t=CrossFade; [ $i = 3 ] && t=SlideLeft
    CLIPS="$CLIPS$(clip main$i "$(uri ${IDS[$i]})" bench_$((i+1)).mp4 0 9000 $((i*9000)) 0 $t 1 0 0 10000),"
  done
  CLIPS="$CLIPS$(clip main6 "$(uri $K4)" bench_4k30_hevc.mp4 0 6000 54000 0 None 1 0 0 10000)"
  # Overlays: a 40% picture in a corner, on layers 1..N, for eight seconds each.
  corners=("0.25 -0.25" "-0.25 -0.25" "0.25 0.25")
  # Six (EDGE_PLAN C4): a 3 x 2 grid of 30% pictures, every overlay row taking footage.
  [ $N -gt 3 ] && corners=("-0.33 -0.25" "0 -0.25" "0.33 -0.25" "-0.33 0.25" "0 0.25" "0.33 0.25")
  SCALE=0.4; [ $N -gt 3 ] && SCALE=0.3
  for k in $(seq 1 $N); do
    set -- ${corners[$((k-1))]}
    CLIPS="$CLIPS,$(clip pip$k "$(uri ${IDS[$(( (k+1) % 6 ))]})" bench_$(( (k+1) % 6 + 1 )).mp4 0 8000 20000 $k None $SCALE $1 $2 10000 | sed 's/,"lookId":"vivid","lookIntensity":1//')"
  done
  TEXTS='{"id":"title1","text":"Squish benchmark","startMs":0,"endMs":5000,"xFraction":0.5,"yFraction":0.2,"sizeSp":40,"colorArgb":-1,"bold":true}'
  AUDIO="{\"id\":\"song1\",\"uri\":\"$SONG\",\"label\":\"Squish original\",\"sourceInMs\":0,\"sourceOutMs\":46000,\"timelineStartMs\":0,\"sourceDurationMs\":46000,\"volume\":0.8,\"speedPoints\":[],\"layer\":0}"
  DUR=60000; SRC=$(uri ${IDS[0]})
fi

cat <<EOF
{"version":14,"sourceUri":"$SRC","durationMs":$DUR,"playheadMs":0,"outputP":0,"fitToSize":false,"targetSizeMb":16,"audioOnly":false,"outputFps":0,"exportQuality":"Recommended","hevc":false,"keepHdr":false,"muteOriginal":false,"originalVolume":1,"rotationDegrees":0,"cropAspect":"Original","snapToMarkers":true,"stabilizeStrength":0.5,"pixelsPerSecond":12.4,"markers":[],"clips":[$CLIPS],"textOverlays":[$TEXTS],"effects":[],"audioClips":[$AUDIO],"savedAtMillis":0}
EOF
