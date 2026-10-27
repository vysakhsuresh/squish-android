#!/usr/bin/env sh
# Every GLSL ES 2 shader in assets, compiled on this machine before a phone sees it.
#
#   sh tools/desktop/glsl_check.sh [shader.glsl ...]
#
# The NDK's glslc (shaderc) will not take ES 100 - it compiles for SPIR-V - so
# each shader is turned into desktop GLSL 330 first (varying -> in/out,
# texture2D -> texture, gl_FragColor -> an out) and compiled for the OpenGL
# target. That catches what breaks a shader on the device: a typo, a type that
# does not match, a function used before it is declared, a missing semicolon.
# It does not catch the limits ES 2 has and 330 does not (a loop bound that is
# not a constant, an array indexed by a variable in a fragment shader), so a
# pass here is "it parses and types", not "it runs on every driver".
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NDK=${ANDROID_NDK:-$(ls -d "${ANDROID_HOME:-C:/Users/vysak/AppData/Local/Android/Sdk}"/ndk/* 2>/dev/null | tail -1)}
GLSLC=$(ls "$NDK"/shader-tools/*/glslc* 2>/dev/null | head -1)
[ -n "$GLSLC" ] || { echo "no glslc under $NDK/shader-tools"; exit 2; }
WORK="$ROOT/build/glsl"; mkdir -p "$WORK"
[ $# -gt 0 ] || set -- "$ROOT"/app/src/main/assets/*.glsl
fails=0; n=0
for f in "$@"; do
  name=$(basename "$f" .glsl)
  case "$name" in *vertex*) stage=vert ;; *) stage=frag ;; esac
  out="$WORK/$name.$stage"
  {
    echo "#version 330 core"
    [ $stage = frag ] && echo "out vec4 squishFragColor;"
    sed -e '/^#version/d' -e 's/\btexture2D\b/texture/g' -e 's/\bgl_FragColor\b/squishFragColor/g' \
        -e 's/^\(\s*\)attribute\b/\1in/' \
        -e "s/^\(\s*\)varying\b/\1$( [ $stage = frag ] && echo in || echo out )/" "$f"
  } > "$out"
  n=$((n + 1))
  msg=$("$GLSLC" --target-env=opengl -fauto-map-locations -fauto-bind-uniforms -fshader-stage=$stage -o /dev/null "$(cygpath -m "$out" 2>/dev/null || echo "$out")" 2>&1)
  if [ -n "$msg" ]; then fails=$((fails + 1)); echo "FAIL $name"; echo "$msg" | sed "s#.*$name\.$stage:#  line #" | head -6; fi
done
[ $fails = 0 ] && echo "PASS - $n shaders parse and type" || { echo "$fails of $n shaders failed"; exit 1; }
