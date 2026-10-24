#version 100

// Smooth slow motion (EDGE_PLAN F1): an in-between frame uMix of the way from
// the earlier frame (uTexA) to the later one (uTexB), each taken from where
// the motion field (uField) says this pixel was and will be. FlowInterp.kt is
// this on the CPU, and tools/jvm/FlowInterpChecks.kt executes it.
//
// uField is a texel a block, nearest-sampled: x in red and green, y in blue
// and alpha, sixteen bits each over +-uRangeUv (FlowInterp.pack). Its vectors
// are in UV: the field was measured on the analysis frame and scaled here.
// uFieldSize is its columns and rows; uCellUv a block's size in UV; uPatchUv
// how far round the pixel the two moved frames are compared.
//
// Per pixel: of the blended vector and the four nearest blocks' own, the one
// that makes the two moved frames agree best round the pixel; then, where
// even that one disagrees - something uncovered - back towards the plain
// cross-fade rather than a smear.

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform sampler2D uField;
uniform float uMix;
uniform vec2 uFieldSize;
uniform vec2 uCellUv;
uniform vec2 uRangeUv;
uniform vec2 uPatchUv;

varying vec2 vTexSamplingCoord;

const float DISAGREE_FROM = 0.12;
const float DISAGREE_TO = 0.3;

vec2 vectorAtCell(vec2 cell) {
  vec4 t = texture2D(uField, (cell + 0.5) / uFieldSize);
  float x = (t.r * 65280.0 + t.g * 255.0) / 65535.0;
  float y = (t.b * 65280.0 + t.a * 255.0) / 65535.0;
  return (vec2(x, y) - 0.5) * 2.0 * uRangeUv;
}

float diff(vec2 uv, vec2 v, float t) {
  vec3 a = texture2D(uTexA, uv - t * v).rgb;
  vec3 b = texture2D(uTexB, uv + (1.0 - t) * v).rgb;
  vec3 d = abs(a - b);
  return (d.r + d.g + d.b) / 3.0;
}

float cost(vec2 uv, vec2 v, float t) {
  return diff(uv, v, t)
    + diff(uv + vec2(uPatchUv.x, 0.0), v, t)
    + diff(uv - vec2(uPatchUv.x, 0.0), v, t)
    + diff(uv + vec2(0.0, uPatchUv.y), v, t)
    + diff(uv - vec2(0.0, uPatchUv.y), v, t);
}

void main() {
  vec2 uv = vTexSamplingCoord;
  float t = clamp(uMix, 0.0, 1.0);

  vec2 g = clamp(uv / uCellUv - 0.5, vec2(0.0), uFieldSize - 1.0);
  vec2 c0 = floor(g);
  vec2 c1 = min(c0 + 1.0, uFieldSize - 1.0);
  vec2 f = g - c0;
  vec2 v00 = vectorAtCell(c0);
  vec2 v10 = vectorAtCell(vec2(c1.x, c0.y));
  vec2 v01 = vectorAtCell(vec2(c0.x, c1.y));
  vec2 v11 = vectorAtCell(c1);
  vec2 blended = mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);

  // The blend first, so it wins a tie, as FlowInterp.candidates orders them.
  vec2 best = blended;
  float bestCost = cost(uv, blended, t);
  float c;
  c = cost(uv, v00, t); if (c < bestCost - 0.000001) { bestCost = c; best = v00; }
  c = cost(uv, v10, t); if (c < bestCost - 0.000001) { bestCost = c; best = v10; }
  c = cost(uv, v01, t); if (c < bestCost - 0.000001) { bestCost = c; best = v01; }
  c = cost(uv, v11, t); if (c < bestCost - 0.000001) { bestCost = c; best = v11; }

  vec4 a = texture2D(uTexA, uv - t * best);
  vec4 b = texture2D(uTexB, uv + (1.0 - t) * best);
  vec4 moved = mix(a, b, t);
  vec4 fade = mix(texture2D(uTexA, uv), texture2D(uTexB, uv), t);
  vec3 d = abs(a.rgb - b.rgb);
  float back = smoothstep(DISAGREE_FROM, DISAGREE_TO, (d.r + d.g + d.b) / 3.0);
  gl_FragColor = mix(moved, fade, back);
}
