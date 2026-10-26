#!/usr/bin/env bash
# Footage the rest of docs/EDGE_PLAN.md needs and the phone does not have:
#
#   motion/   F1 (smooth slow motion: fast, steady movement to judge against
#             blur) and F9 (flicker and noise to clean up)
#   proxy/    C5 (1080p at 60 fps and 8-bit HEVC at 1080p, which Auto proxies)
#   speech/   E1 (captions in English, Hindi and Malayalam, scored against the
#             words spoken - each WAV/MP4 has its .txt beside it) and E5
#
#   tools/native/make_edge_media.sh <outdir>      # needs ffmpeg and espeak-ng
#
# The speech is espeak-ng's: a robotic voice, which is harder for a recogniser
# than a person, not easier - an accuracy read off it is a floor. espeak-ng is
# GPL and is only a tool on the runner here; nothing of it ships in the app.
set -uo pipefail
OUT=${1:?outdir}
mkdir -p "$OUT/motion" "$OUT/proxy" "$OUT/speech"
LOG="$OUT/made.txt"
: > "$LOG"
note() { echo "$1" >> "$LOG"; }
run() { # dir/name, then the whole ffmpeg argument list
  local name=$1; shift
  if ffmpeg -hide_banner -loglevel error -y "$@" "$OUT/$name" < /dev/null; then
    note "ok   $name $(ffprobe -v error -show_entries format=duration,size -of csv=p=0 "$OUT/$name")"
  else
    note "FAIL $name"; rm -f "$OUT/$name"
  fi
}
PHONE="-c:v libx264 -preset veryfast -profile:v high -b:v 17M -maxrate 20M -bufsize 34M -g 30 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart"
TONE="-f lavfi -i sine=frequency=440:sample_rate=48000"

# ---- F1: fast, steady motion ----------------------------------------------------
# A textured card crossing the frame in a second and a ball bouncing across
# it: steady motion whose in-between frames are known, so a blend shows as a
# double image and a good interpolation as one sharp object. (A turning bar
# through ffmpeg's rotate came out a filled disc over everything; left out.)
run motion/fast_motion_1080p30.mp4 \
  -f lavfi -i "gradients=size=1920x1080:rate=30:speed=0.01:seed=4" \
  -f lavfi -i "testsrc2=size=260x260:rate=30" \
  -f lavfi -i "color=c=white:size=60x60:rate=30,format=rgba,geq=r=255:g=180:b=40:a='if(lt(hypot(X-30,Y-30),29),255,0)'" \
  $TONE \
  -filter_complex "[0:v][1:v]overlay=x='mod(t*1600,2180)-260':y=140[a];[a][2:v]overlay=x='200+t*260':y='960-abs(sin(t*3.2))*520',format=yuv420p[v]" \
  -map "[v]" -map 3:a -t 6 $PHONE

# ---- F9: flicker and noise -------------------------------------------------------
# Brightness swinging 8% at 7 Hz, as under mains light shot at the wrong shutter.
run motion/flicker_720p30.mp4 -f lavfi -i "testsrc2=size=1280x720:rate=30" $TONE \
  -vf "eq=brightness='0.08*sin(2*PI*t*7)':eval=frame,format=yuv420p" -map 0:v -map 1:a -t 6 $PHONE
# A dim, still scene under heavy grain: low light on a small sensor.
run motion/grain_720p30.mp4 -f lavfi -i "smptehdbars=size=1280x720:rate=30" $TONE \
  -vf "eq=brightness=-0.25:contrast=0.7,noise=alls=38:allf=t+u,format=yuv420p" -map 0:v -map 1:a -t 6 $PHONE

# ---- C5: what Auto gives a light copy at 1080p -------------------------------------
run proxy/h264_1080p60.mp4 -f lavfi -i "mandelbrot=size=1920x1080:rate=60:start_scale=3:end_scale=0.5" $TONE \
  -vf "noise=alls=10:allf=t,format=yuv420p" -map 0:v -map 1:a -t 8 \
  -c:v libx264 -preset veryfast -profile:v high -b:v 30M -g 60 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart
run proxy/hevc_1080p30.mp4 -f lavfi -i "mandelbrot=size=1920x1080:rate=30:start_scale=3:end_scale=0.5" $TONE \
  -vf "noise=alls=10:allf=t,format=yuv420p" -map 0:v -map 1:a -t 8 \
  -c:v libx265 -preset ultrafast -b:v 15M -x265-params log-level=error -tag:v hvc1 -c:a aac -b:a 192k -movflags +faststart

# ---- E1 / E5: speech in three languages --------------------------------------------
speak() { # name, espeak voice, text file
  local name=$1 voice=$2 text=$3
  if espeak-ng -v "$voice" -s 135 -f "$text" -w "$OUT/speech/$name.wav" 2>/dev/null; then
    cp "$text" "$OUT/speech/$name.txt"
    note "ok   speech/$name.wav $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/speech/$name.wav")"
    # And as a talking-head clip: the words over a still card, for the editor's Auto-captions.
    run "speech/$name.mp4" -f lavfi -i "smptehdbars=size=1280x720:rate=30" -i "$OUT/speech/$name.wav" \
      -map 0:v -map 1:a -shortest -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 128k -ar 48000 -movflags +faststart
  else
    note "FAIL speech/$name ($voice)"
  fi
}
HERE=$(cd "$(dirname "$0")" && pwd)
speak english en "$HERE/speech/english.txt"
speak hindi hi "$HERE/speech/hindi.txt"
speak malayalam ml "$HERE/speech/malayalam.txt"

ls -laR "$OUT"
cat "$LOG"
