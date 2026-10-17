#!/usr/bin/env bash
# Makes a folder of short test files in formats a phone's own decoders mostly
# do not read - for checking, on a device, that Squish reads them itself
# (docs/DEVICE_FINDINGS.md says what each was for). Three seconds of a moving
# test card with a tone, in each container and codec; a still in each picture
# format; and a few camera RAWs and odd pictures fetched from public sample
# collections.
#
#   tools/native/make_test_media.sh <outdir>      # needs ffmpeg and curl
#
# The GitHub workflow .github/workflows/testmedia.yml runs it on Linux.
set -uo pipefail
OUT=${1:?outdir}
mkdir -p "$OUT/video" "$OUT/audio" "$OUT/picture"
LOG="$OUT/made.txt"
: > "$LOG"

SRC="-f lavfi -i testsrc2=size=640x360:rate=25:duration=3 -f lavfi -i sine=frequency=880:sample_rate=48000:duration=3"

make() { # name, then ffmpeg arguments after the inputs
  local name=$1; shift
  if ffmpeg -hide_banner -loglevel error -y $SRC "$@" "$OUT/video/$name" < /dev/null; then
    echo "ok   video/$name" >> "$LOG"
  else
    echo "FAIL video/$name" >> "$LOG"; rm -f "$OUT/video/$name"
  fi
}

# Pictures Media3 drops from a container it otherwise reads.
make prores_pcm24.mov        -c:v prores_ks -profile:v 2 -pix_fmt yuv422p10le -c:a pcm_s24le
make prores4444_alac.mov     -c:v prores_ks -profile:v 4 -pix_fmt yuva444p10le -c:a alac
make mjpeg_ulaw.mov          -c:v mjpeg -q:v 4 -pix_fmt yuvj420p -c:a pcm_mulaw
make dnxhr_pcm.mxf           -c:v dnxhd -profile:v dnxhr_lb -pix_fmt yuv422p -c:a pcm_s16le
make dnxhd.mov               -vf scale=1280:720 -c:v dnxhd -b:v 90M -pix_fmt yuv422p -r 25 -c:a pcm_s16le
make cinepak.avi             -c:v cinepak -c:a pcm_u8
make huffyuv.avi             -c:v huffyuv -c:a pcm_s16le
make ffv1.mkv                -c:v ffv1 -c:a flac
make utvideo.avi             -c:v utvideo -c:a pcm_s16le
make divx3.avi               -c:v msmpeg4 -vtag DIV3 -c:a libmp3lame
make msmpeg4v2.avi           -c:v msmpeg4v2 -c:a adpcm_ms
make xvid_mp3.avi            -c:v mpeg4 -vtag XVID -c:a libmp3lame
make mjpeg_pcm.avi           -c:v mjpeg -q:v 4 -pix_fmt yuvj420p -c:a pcm_s16le
make h264_ac3.avi            -c:v libx264 -preset veryfast -c:a ac3
# Containers Media3 has no reader for.
make wmv2_wma.wmv            -c:v wmv2 -c:a wmav2
make wmv1_wma1.asf           -c:v wmv1 -c:a wmav1
make rv20_ac3.rm             -c:v rv20 -c:a ac3
make rv10_ra144.rm           -vf scale=352:288 -c:v rv10 -c:a real_144 -ar 8000 -ac 1
make sorenson.flv            -c:v flv1 -c:a libmp3lame -ar 44100
make dv_pal.dv               -vf scale=720:576,setsar=16/15 -r 25 -c:v dvvideo -pix_fmt yuv420p -c:a pcm_s16le -ar 48000
make theora_vorbis.ogv       -c:v libtheora -q:v 6 -c:a libvorbis
make h264_ac3.m2ts           -c:v libx264 -preset veryfast -c:a ac3 -f mpegts -mpegts_m2ts_mode 1
make mpeg2_mp2.vob           -c:v mpeg2video -b:v 4M -c:a mp2 -f vob
make mpeg1_mp2.mpg           -c:v mpeg1video -b:v 2M -c:a mp2
make mpeg2_lpcm.vob          -c:v mpeg2video -b:v 4M -c:a pcm_dvd -ar 48000 -f vob
make h264.nut                -c:v libx264 -preset veryfast -c:a libopus
make h263_amr.3gp            -vf scale=352:288 -c:v h263 -c:a libopencore_amrnb -ar 8000 -ac 1
make y4m_raw.y4m             -an -pix_fmt yuv420p
# Codecs Media3 reads the container of, where the phone may lack the decoder.
make hevc10_dts.mkv          -c:v libx265 -pix_fmt yuv420p10le -x265-params log-level=error -c:a dca -strict -2
make hevc10_hlg.mp4          -c:v libx265 -pix_fmt yuv420p10le -x265-params log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc -tag:v hvc1 -c:a aac
make h264_wma.mkv            -c:v libx264 -preset veryfast -c:a wmav2
make h264_truehd.mkv         -c:v libx264 -preset veryfast -c:a truehd -strict -2
make vp9_opus.webm           -c:v libvpx-vp9 -b:v 1M -c:a libopus
make av1_opus.mkv            -c:v libaom-av1 -cpu-used 8 -b:v 500k -c:a libopus
make vp8_vorbis.webm         -c:v libvpx -b:v 1M -c:a libvorbis
make mpeg4_pcm24.mkv         -c:v mpeg4 -c:a pcm_s24le
make h264_444.mp4            -c:v libx264 -preset veryfast -pix_fmt yuv444p -c:a aac
make h264_10bit.mkv          -c:v libx264 -preset veryfast -pix_fmt yuv420p10le -c:a aac
make rotated_90.mp4          -c:v libx264 -preset veryfast -c:a aac -metadata:s:v:0 rotate=90 -display_rotation:v:0 90
make anamorphic.mkv          -vf scale=720:576,setsar=64/45 -c:v libx264 -preset veryfast -c:a aac

sound() { # name, then arguments
  local name=$1; shift
  if ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=frequency=660:sample_rate=44100:duration=4 "$@" "$OUT/audio/$name" < /dev/null; then
    echo "ok   audio/$name" >> "$LOG"
  else
    echo "FAIL audio/$name" >> "$LOG"; rm -f "$OUT/audio/$name"
  fi
}
sound wma.wma            -c:a wmav2
sound tta.tta            -c:a tta
sound wavpack.wv         -c:a wavpack
sound aiff.aiff          -c:a pcm_s16be
sound alac.caf           -c:a alac
sound dts.dts            -c:a dca -strict -2
sound ac3.ac3            -c:a ac3
sound mp2.mp2            -c:a mp2
sound sun.au             -c:a pcm_mulaw
sound voc.voc            -c:a pcm_u8
sound real.ra            -c:a real_144 -ar 8000 -ac 1
sound truehd.mka         -c:a truehd -strict -2
sound pcm24.w64          -c:a pcm_s24le
sound adpcm.wav          -c:a adpcm_ms
sound gsm.wav            -c:a libgsm_ms -ar 8000 -ac 1
sound speex.spx          -c:a libspeex -ar 16000 -ac 1
sound opus.opus          -c:a libopus

picture() { # name, then arguments
  local name=$1; shift
  if ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=1200x800:rate=1:duration=1" -frames:v 1 "$@" "$OUT/picture/$name" < /dev/null; then
    echo "ok   picture/$name" >> "$LOG"
  else
    echo "FAIL picture/$name" >> "$LOG"; rm -f "$OUT/picture/$name"
  fi
}
picture tiff16.tif       -pix_fmt rgb48le
picture tiff_lzw.tiff    -compression_algo lzw
picture exr_half.exr     -pix_fmt gbrpf32le
picture targa.tga        -c:v targa
picture pcx.pcx
picture sgi.sgi
picture sunrast.ras
picture xbm.xbm          -pix_fmt monow
picture xwd.xwd
picture portable.ppm
picture portable.pam
picture float.pfm        -pix_fmt gbrpf32le
picture radiance.hdr
picture qoi.qoi
picture jpeg2000.jp2     -c:v libopenjpeg
picture dpx.dpx
picture jpegxl.jxl       -c:v libjxl
picture avif.avif        -c:v libaom-av1 -still-picture 1
picture bmp32.bmp        -pix_fmt bgra
picture webp_lossless.webp -c:v libwebp -lossless 1
picture png16.png        -pix_fmt rgb48be
picture gif.gif
cat > "$OUT/picture/drawing.svg" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300" width="400" height="300">
  <rect width="400" height="300" fill="#1e88e5"/>
  <circle cx="200" cy="150" r="90" fill="#ffb300"/>
  <text x="200" y="165" font-size="44" text-anchor="middle" fill="#212121">SVG</text>
</svg>
SVG
echo "ok   picture/drawing.svg" >> "$LOG"

fetch() { # url, name
  if curl -fsSL --retry 3 -o "$OUT/picture/$2" "$1"; then echo "ok   picture/$2" >> "$LOG"; else echo "FAIL picture/$2 ($1)" >> "$LOG"; rm -f "$OUT/picture/$2"; fi
}
# Camera RAWs from raw.pixls.us (CC0) and LibRaw's own samples: small ones.
fetch "https://raw.pixls.us/getfile.php/1031/nice/Canon%20-%20EOS%20100D%20-%20RAW%20(3%3A2).CR2" canon_100d.cr2
fetch "https://raw.pixls.us/getfile.php/2894/nice/Nikon%20-%20D3500%20-%2012bit%20compressed%20(Lossy%201).NEF" nikon_d3500.nef
fetch "https://raw.pixls.us/getfile.php/3048/nice/Sony%20-%20ILCE-6400%20-%2014bit%20compressed%20(3%3A2).ARW" sony_a6400.arw
fetch "https://raw.pixls.us/getfile.php/3170/nice/Fujifilm%20-%20X-T30%20-%20Compressed%20(3%3A2).RAF" fuji_xt30.raf

ls -la "$OUT"/*
cat "$LOG"
