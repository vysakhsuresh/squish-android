# Third-party notices

## Space Grotesk

Copyright (c) 2020 Florian Karsten. Licensed under the SIL Open Font License,
Version 1.1 — https://openfontlicense.org/

The four `.ttf` files in `app/src/main/res/font/` are the same typeface Layerlink
ships, carried over so Squish and Layerlink read as one family of products. They
are bundled rather than fetched over the network, so bold renders as the real bold
face instead of a synthetic one.

**Before a store release:** the OFL requires the full licence text to travel with
the font binaries. Add the verbatim `OFL.txt` from the Space Grotesk distribution
to the repository, and surface the attribution on an in-app licences screen.

## Android Open Source Project — vertex shader

`app/src/main/assets/squish_vertex_copy_es2.glsl` is adapted from the AndroidX
Media (Media3) Transformer demo, Copyright 2022 The Android Open Source Project,
licensed under the Apache License 2.0. It is a pass-through vertex shader: it maps
normalised device coordinates to texture coordinates and does nothing else. The
fragment shader beside it (`squish_chroma_key_es2.glsl`) is original.

    http://www.apache.org/licenses/LICENSE-2.0
