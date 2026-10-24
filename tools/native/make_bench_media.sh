#!/usr/bin/env bash
# The footage for docs/EDGE_PLAN.md: A1's benchmark edit (bench/) and B1's
# footage matrix (matrix/). Deterministic - the same files every run - so a
# number in docs/BENCHMARKS.md can be compared with the next one.
#
#   tools/native/make_bench_media.sh <outdir>      # needs ffmpeg
#
# .github/workflows/benchmedia.yml runs it on Linux. Phone-shot footage is not
# here and cannot be: what this cannot make (a real camera's noise, Dolby
# Vision with its RPU, an iPhone's own files) BENCHMARKS.md says was not
# measured, never guesses.
#
# The picture: generated, but busy - moving fractals, gradients and cellular
# patterns under temporal noise - because a flat test card compresses to
# nothing and would flatter every encoder number.
set -uo pipefail
OUT=${1:?outdir}
mkdir -p "$OUT/bench" "$OUT/matrix"
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

# Sound a phone records: a room (pink noise) with something in it (a tone).
AUD="-f lavfi -i anoisesrc=color=pink:amplitude=0.03:sample_rate=48000:duration=DUR -f lavfi -i sine=frequency=FREQ:sample_rate=48000:duration=DUR"
aud() { echo "$AUD" | sed "s/DUR/$1/g; s/FREQ/$2/g"; }
MIX='[1:a][2:a]amix=inputs=2:duration=shortest[a]'

# A phone's own encoding: H.264 High at about 17 Mb/s for 1080p30.
PHONE="-c:v libx264 -preset veryfast -profile:v high -b:v 17M -maxrate 20M -bufsize 34M -g 30 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart"
GRAIN="noise=alls=14:allf=t+u"

# ---- A1: the benchmark edit's footage -------------------------------------------
# Six 1080p30 "phone clips" of ten seconds, each a different picture.
clip() { # n, lavfi video source, tone
  run "bench/bench_$1.mp4" -f lavfi -i "$2" $(aud 10 $3) \
    -filter_complex "[0:v]$GRAIN,format=yuv420p[v];$MIX" -map "[v]" -map "[a]" -t 10 $PHONE
}
clip 1 "mandelbrot=size=1920x1080:rate=30:start_scale=3:end_scale=0.3" 220
clip 2 "gradients=size=1920x1080:rate=30:speed=0.03:n=6:seed=7" 330
clip 3 "testsrc2=size=1920x1080:rate=30,hue=H=t*40" 440
clip 4 "cellauto=size=480x270:rate=30:rule=110:seed=3,scale=1920:1080:flags=neighbor,hue=s=3" 550
clip 5 "life=size=480x270:rate=30:seed=11:mold=10:life_color=#ff8040:death_color=#102040,scale=1920:1080" 660
# The colour chart, still, for checking colour through the pipeline (crop.ps1).
clip 6 "smptehdbars=size=1920x1080:rate=30" 770

# One 4K clip, as a phone shoots it: HEVC 3840x2160 30 fps at about 45 Mb/s.
run bench/bench_4k30_hevc.mp4 -f lavfi -i "mandelbrot=size=3840x2160:rate=30:start_scale=2:end_scale=0.5" $(aud 10 880) \
  -filter_complex "[0:v]$GRAIN,format=yuv420p[v];$MIX" -map "[v]" -map "[a]" -t 10 \
  -c:v libx265 -preset ultrafast -b:v 45M -x265-params log-level=error -tag:v hvc1 -c:a aac -b:a 192k -movflags +faststart

# For the import timings: 4K60, HEVC 10-bit, HLG and HDR10.
run bench/import_4k60_h264.mp4 -f lavfi -i "gradients=size=3840x2160:rate=60:speed=0.02:n=5:seed=3" $(aud 5 440) \
  -filter_complex "[0:v]$GRAIN,format=yuv420p[v];$MIX" -map "[v]" -map "[a]" -t 5 \
  -c:v libx264 -preset superfast -b:v 70M -pix_fmt yuv420p -c:a aac -movflags +faststart
run bench/import_hevc10.mp4 -f lavfi -i "testsrc2=size=1920x1080:rate=30" $(aud 5 440) \
  -filter_complex "[0:v]$GRAIN[v];$MIX" -map "[v]" -map "[a]" -t 5 \
  -c:v libx265 -preset ultrafast -pix_fmt yuv420p10le -x265-params log-level=error -tag:v hvc1 -c:a aac
run bench/import_hlg.mp4 -f lavfi -i "smptehdbars=size=1920x1080:rate=30" $(aud 5 440) \
  -filter_complex "[0:v]$GRAIN[v];$MIX" -map "[v]" -map "[a]" -t 5 \
  -c:v libx265 -preset ultrafast -pix_fmt yuv420p10le \
  -x265-params log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc -tag:v hvc1 -c:a aac
run bench/import_hdr10.mp4 -f lavfi -i "smptehdbars=size=1920x1080:rate=30" $(aud 5 440) \
  -filter_complex "[0:v]$GRAIN[v];$MIX" -map "[v]" -map "[a]" -t 5 \
  -c:v libx265 -preset ultrafast -pix_fmt yuv420p10le \
  -x265-params "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
  -tag:v hvc1 -c:a aac
note "not made bench/import_dolby_vision - ffmpeg writes no RPU; needs a real iPhone file"

# ---- B1: the footage matrix ------------------------------------------------------
SMALL="-c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac"
# Variable frame rate the way phones write it: frame times wobbling round 30 fps.
VFR="setpts='(N+0.35*sin(N*0.7))/(30*TB)'"
run matrix/vfr_phone.mp4 -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 60 440) \
  -filter_complex "[0:v]$VFR,$GRAIN[v];$MIX" -map "[v]" -map "[a]" -t 60 -fps_mode vfr $SMALL

# A/V sync clips (B3): a white frame and a beep together on every second, so
# drift is read off the file - flash time against beep time.
sync() { # name, minutes, size, vfr?
  local secs=$(( $2 * 60 )); local vf="drawbox=c=white:t=fill:enable='lt(mod(t,1),0.034)'"
  [ "$4" = vfr ] && vf="$VFR,$vf"
  run "matrix/$1" -f lavfi -i "testsrc2=size=$3:rate=30" \
    -f lavfi -i "sine=frequency=1000:sample_rate=48000:duration=$secs,volume='if(lt(mod(t,1),0.05),1,0)':eval=frame" \
    -filter_complex "[0:v]$vf[v]" -map "[v]" -map 1:a -t "$secs" -fps_mode "$([ "$4" = vfr ] && echo vfr || echo cfr)" \
    -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 64k
}
sync sync_1min_vfr.mp4 1 640x360 vfr
sync sync_10min_vfr.mp4 10 640x360 vfr
sync sync_60min_vfr.mp4 60 320x180 vfr

# High frame rate, as a phone's slow-motion mode writes it.
run matrix/hfr_120.mp4 -f lavfi -i "testsrc2=size=1920x1080:rate=120" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL
run matrix/hfr_240.mp4 -f lavfi -i "testsrc2=size=1280x720:rate=240" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL

# Rotation as a phone stores it: landscape pixels and a display matrix.
for deg in 90 180 270; do
  run "matrix/rotated_$deg.mp4" -display_rotation:v:0 "$deg" -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 5 440) \
    -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL
done

# Screen recordings at the sizes phones really use.
run matrix/screen_1080x2400.mp4 -f lavfi -i "testsrc2=size=1080x2400:rate=60" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL
run matrix/screen_886x1920.mp4 -f lavfi -i "testsrc2=size=886x1920:rate=30" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL

# What WhatsApp sends on: 480x848 baseline at about 1 Mb/s.
run matrix/whatsapp.mp4 -f lavfi -i "testsrc2=size=480x848:rate=30" $(aud 8 440) -filter_complex "[0:v]$GRAIN[v];$MIX" -map "[v]" -map "[a]" -t 8 \
  -c:v libx264 -profile:v baseline -preset veryfast -b:v 1M -pix_fmt yuv420p -c:a aac -b:a 64k

# Containers.
run matrix/xvid.avi -f lavfi -i "testsrc2=size=640x480:rate=25" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 -c:v mpeg4 -vtag XVID -q:v 4 -c:a libmp3lame
run matrix/h264.flv -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL
run matrix/h264.mkv -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL
run matrix/vp9.webm -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 -c:v libvpx-vp9 -b:v 2M -deadline realtime -c:a libopus
run matrix/h264.mov -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 5 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 5 $SMALL
run matrix/h263.3gp -f lavfi -i "testsrc2=size=352x288:rate=15" -f lavfi -i "sine=frequency=440:sample_rate=16000:duration=5" -map 0:v -map 1:a -t 5 -c:v h263 -c:a aac -ar 16000 -ac 1 -b:a 32k

# Sound: six channels, and none at all.
run matrix/audio_6ch.mp4 -f lavfi -i "testsrc2=size=1280x720:rate=30" -f lavfi -i "sine=frequency=300:sample_rate=48000:duration=5" \
  -filter_complex "[1:a]pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0[a]" -map 0:v -map "[a]" -t 5 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -ac 6
run matrix/no_audio.mp4 -f lavfi -i "testsrc2=size=1280x720:rate=30" -t 5 -an -c:v libx264 -preset veryfast -pix_fmt yuv420p

# Lengths: one second, and three hours.
run matrix/one_second.mp4 -f lavfi -i "testsrc2=size=1280x720:rate=30" $(aud 1 440) -filter_complex "$MIX" -map 0:v -map "[a]" -t 1 $SMALL
run matrix/three_hours.mp4 -f lavfi -i "testsrc2=size=256x144:rate=15" -f lavfi -i "sine=frequency=440:sample_rate=22050:duration=10800" \
  -map 0:v -map 1:a -t 10800 -c:v libx264 -preset ultrafast -crf 35 -g 150 -pix_fmt yuv420p -c:a aac -ac 1 -b:a 24k

ls -la "$OUT"/*
cat "$LOG"
