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

## nextlib — FFmpeg decoders for Media3

`io.github.anilbeesetti:nextlib-media3ext` (Anil Kumar Beesetti), a build of
FFmpeg's decoders — libavcodec, libavutil, libswscale, libswresample, with
dav1d for AV1 — as Media3 renderers, licensed under the **GNU General Public
License, version 3** — https://www.gnu.org/licenses/gpl-3.0.html. FFmpeg itself
is LGPL 2.1 or later, with GPL parts enabled in this build.

Used by `app/src/main/java/com/squish/app/media/SoftwareDecoders.kt` and the
adapter in `app/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/`
to play, convert and export files no decoder on the phone takes. The decision
to take a GPL dependency was the owner's, on 15 October, after the licence was
put to them.

**Before a store release:** the GPL requires that the corresponding source of
the library be available to anyone the app is distributed to, and that the
app's own licence be compatible with it. The library's source is at
https://github.com/anilbeesetti/nextlib; the app's licence has to be settled
with that in mind, or the dependency removed before publishing.

## Android Open Source Project — vertex shader

`app/src/main/assets/squish_vertex_copy_es2.glsl` is adapted from the AndroidX
Media (Media3) Transformer demo, Copyright 2022 The Android Open Source Project,
licensed under the Apache License 2.0. It is a pass-through vertex shader: it maps
normalized device coordinates to texture coordinates and does nothing else. The
fragment shader beside it (`squish_chroma_key_es2.glsl`) is original.

    http://www.apache.org/licenses/LICENSE-2.0
