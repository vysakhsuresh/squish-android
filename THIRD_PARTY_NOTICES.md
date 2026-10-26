# Third-party notices

## Space Grotesk

Copyright (c) 2020 Florian Karsten. Licensed under the SIL Open Font License,
Version 1.1 — https://openfontlicense.org/

The four `.ttf` files in `app/src/main/res/font/` are the same typeface Layerlink
ships, carried over so Squish and Layerlink read as one family of products. They
are bundled rather than fetched over the network, so bold renders as the real bold
face instead of a synthetic one.

**Before a store release:** the OFL requires the full license text to travel with
the font binaries. Add the verbatim `OFL.txt` from the Space Grotesk distribution
to the repository, and surface the attribution on an in-app licences screen.

## FFmpeg, dav1d, libjxl and LibRaw — libsquishav.so

`app/src/main/jniLibs/<abi>/libsquishav.so` is built from source by
`tools/native/build.sh` (versions pinned there) and links, statically:

- **FFmpeg 8.1.3** (https://ffmpeg.org) — libavformat, libavcodec,
  libswscale, libswresample, libavutil, configured `--enable-gpl
  --enable-version3`, so the build as a whole is under the **GNU General
  Public License, version 3** — https://www.gnu.org/licenses/gpl-3.0.html.
- **dav1d** (VideoLAN), BSD 2-Clause — AV1 decoding.
- **libjxl** (the JPEG XL project), BSD 3-Clause, with **Highway** (Apache
  2.0 / BSD 3-Clause), **Brotli** (MIT) and **skcms** (BSD 3-Clause).
- **LibRaw** 0.21.4, under the GNU LGPL 2.1 or the CDDL 1.0 at the
  recipient's choice — camera RAW development.
- zlib, from the NDK's platform, zlib licence.

Used through `app/src/main/java/com/squish/app/av/` (the JNI wrapper's
source is `app/src/main/cpp/`) to read, decode and export every container
and codec FFmpeg has, and every picture format the platform refuses. It
replaced nextlib (`io.github.anilbeesetti:nextlib-media3ext`, GPL-3.0) on
18 October. The decision to take a GPL dependency was the owner's, on 15
October, after the licence was put to them.

**Before a store release:** the GPL requires that the corresponding source be
available to anyone the app is distributed to - FFmpeg's, the wrapper's and
the build script, all of which are fetched or held by this repository - and
that the app's own licence be compatible with it. Some of the codecs FFmpeg
decodes (HEVC, AAC, AC-3 and others) are under patents in some countries;
decoding them in software in a distributed app is a question for a lawyer
before a store release, not for this file.

## AndroidSVG

`com.caverock:androidsvg-aar` 1.4 (Paul LeBeau), Apache License 2.0 —
http://www.apache.org/licenses/LICENSE-2.0. Draws SVG pictures for the photo
path (`av/AvImages.kt`).

## Android Open Source Project — vertex shader

`app/src/main/assets/squish_vertex_copy_es2.glsl` is adapted from the AndroidX
Media (Media3) Transformer demo, Copyright 2022 The Android Open Source Project,
licensed under the Apache License 2.0. It is a pass-through vertex shader: it maps
normalized device coordinates to texture coordinates and does nothing else. The
fragment shader beside it (`squish_chroma_key_es2.glsl`) is original.

    http://www.apache.org/licenses/LICENSE-2.0

## DTLN - voice isolation models

`app/src/main/assets/dtln/model_1.tflite` and `model_2.tflite` are the
pretrained real-time models of DTLN, "Dual-Signal Transformation LSTM Network
for Real-Time Noise Suppression" (Nils L. Westhausen and Bernd T. Meyer,
Interspeech 2020), https://github.com/breizhn/DTLN, MIT License, Copyright (c)
2020 Nils L. Westhausen. They run Voice only and Remove voice
(`media/audio/VoiceSplit.kt`, `DtlnTflite.kt`).

## LiteRT (TensorFlow Lite)

`com.google.ai.edge.litert:litert` and `litert-gpu` 1.4.0 (Google), Apache
License 2.0 - http://www.apache.org/licenses/LICENSE-2.0. Runs the DTLN models
and the downloaded voices (the vocoder on the GPU where the phone takes it).

## whisper.cpp - captions

`libsquishwhisper.so` is built from whisper.cpp (Georgi Gerganov and
contributors, MIT License), https://github.com/ggerganov/whisper.cpp. The
speech models it runs are OpenAI's Whisper weights in ggml form (MIT),
downloaded once by the person, never bundled.

## MediaPipe - masks, faces and objects

`com.google.mediapipe:tasks-vision` (Apache License 2.0) and its bundled
models - the selfie segmenter, BlazeFace, EfficientDet-Lite0 and MagicTouch (Apache 2.0;
MagicTouch's own model card, read 26 October 2026) -
under `app/src/main/assets/`.

## CameraX - the in-app camera

`androidx.camera` 1.3.4, Apache License 2.0.
