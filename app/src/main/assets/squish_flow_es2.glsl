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
// Each block's trust (FlowInterp.Field.trust), a texel a block, linearly filtered:
// 0 where the block measured differently each way round, and the frame is the
// cross-fade there.
uniform sampler2D uTrust;
// Each block's second move (FlowInterp.Field.dx2), packed as uField is: what a
// small fast thing in it did - a ball, a distant hand - or the block's own move
// where nothing small moved.
uniform sampler2D uField2;
uniform float uMix;
uniform vec2 uFieldSize;
uniform vec2 uCellUv;
uniform vec2 uRangeUv;
uniform vec2 uPatchUv;

varying vec2 vTexSamplingCoord;

const float DISAGREE_FROM = 0.12;
const float DISAGREE_TO = 0.3;
// FlowInterp.CHANGED and SMALL_AGREES.
const float CHANGED = 0.1;
const float SMALL_AGREES = 0.04;
// FlowInterp.BLOCK and MATCH_SLACK: the small move may be an analysis pixel out.
const float BLOCK = 16.0;
const float MATCH_SLACK = 1.0;

vec2 vectorAtCell(vec2 cell) {
  vec4 t = texture2D(uField, (cell + 0.5) / uFieldSize);
  float x = (t.r * 65280.0 + t.g * 255.0) / 65535.0;
  float y = (t.b * 65280.0 + t.a * 255.0) / 65535.0;
  return (vec2(x, y) - 0.5) * 2.0 * uRangeUv;
}

vec2 secondAtCell(vec2 cell) {
  vec4 t = texture2D(uField2, (cell + 0.5) / uFieldSize);
  float x = (t.r * 65280.0 + t.g * 255.0) / 65535.0;
  float y = (t.b * 65280.0 + t.a * 255.0) / 65535.0;
  return (vec2(x, y) - 0.5) * 2.0 * uRangeUv;
}

float changedBy(vec2 uv) {
  vec3 d = abs(texture2D(uTexA, uv).rgb - texture2D(uTexB, uv).rgb);
  return (d.r + d.g + d.b) / 3.0;
}

// How far colour [c] is from uTexB (or uTexA) at [p] or [r] either way along
// each axis - the small move's slack (FlowInterp.nearest).
float off3(vec3 c, vec3 o) { vec3 d = abs(c - o); return (d.r + d.g + d.b) / 3.0; }
float nearestB(vec2 p, vec2 r, vec3 c) {
  return min(off3(c, texture2D(uTexB, p).rgb),
    min(min(off3(c, texture2D(uTexB, p + vec2(r.x, 0.0)).rgb), off3(c, texture2D(uTexB, p - vec2(r.x, 0.0)).rgb)),
        min(off3(c, texture2D(uTexB, p + vec2(0.0, r.y)).rgb), off3(c, texture2D(uTexB, p - vec2(0.0, r.y)).rgb))));
}
float nearestA(vec2 p, vec2 r, vec3 c) {
  return min(off3(c, texture2D(uTexA, p).rgb),
    min(min(off3(c, texture2D(uTexA, p + vec2(r.x, 0.0)).rgb), off3(c, texture2D(uTexA, p - vec2(r.x, 0.0)).rgb)),
        min(off3(c, texture2D(uTexA, p + vec2(0.0, r.y)).rgb), off3(c, texture2D(uTexA, p - vec2(0.0, r.y)).rgb))));
}
// FlowInterp.changedNear.
bool changedNear(vec2 p, vec2 r) {
  return changedBy(p) > CHANGED
    || changedBy(p + vec2(r.x, 0.0)) > CHANGED || changedBy(p - vec2(r.x, 0.0)) > CHANGED
    || changedBy(p + vec2(0.0, r.y)) > CHANGED || changedBy(p - vec2(0.0, r.y)) > CHANGED;
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

  // Standing still first, so it wins a tie (FlowInterp.interpolate): over
  // smooth ground a block's move can be any shift along it. Then the blend,
  // which wins a tie among the blocks' own, as FlowInterp.candidates orders them.
  vec2 best = vec2(0.0);
  float bestCost = cost(uv, vec2(0.0), t);
  float c;
  c = cost(uv, blended, t); if (c < bestCost - 0.000001) { bestCost = c; best = blended; }
  c = cost(uv, v00, t); if (c < bestCost - 0.000001) { bestCost = c; best = v00; }
  c = cost(uv, v10, t); if (c < bestCost - 0.000001) { bestCost = c; best = v10; }
  c = cost(uv, v01, t); if (c < bestCost - 0.000001) { bestCost = c; best = v01; }
  c = cost(uv, v11, t); if (c < bestCost - 0.000001) { bestCost = c; best = v11; }

  // The four blocks' second moves, as FlowInterp.interpolate weighs them: one
  // only counts where it is not the block's own move again, and it wins only
  // where the pixel itself changed and it is clearly better, or where what it
  // brings in is the thing in motion and the two frames agree along it.
  vec2 s00 = secondAtCell(c0);
  vec2 s10 = secondAtCell(vec2(c1.x, c0.y));
  vec2 s01 = secondAtCell(vec2(c0.x, c1.y));
  vec2 s11 = secondAtCell(c1);
  float smallCost = 1.0e9;
  vec2 small = vec2(0.0);
  if (any(notEqual(s00, v00))) { c = cost(uv, s00, t); if (c < smallCost - 0.000001) { smallCost = c; small = s00; } }
  if (any(notEqual(s10, v10))) { c = cost(uv, s10, t); if (c < smallCost - 0.000001) { smallCost = c; small = s10; } }
  if (any(notEqual(s01, v01))) { c = cost(uv, s01, t); if (c < smallCost - 0.000001) { smallCost = c; small = s01; } }
  if (any(notEqual(s11, v11))) { c = cost(uv, s11, t); if (c < smallCost - 0.000001) { smallCost = c; small = s11; } }
  float smallWins = 0.0;
  if (smallCost < 1.0e8) {
    bool passing = changedBy(uv - t * small) > CHANGED && changedBy(uv + (1.0 - t) * small) > CHANGED;
    bool changedHere = changedBy(uv) > CHANGED;
    if ((changedHere && smallCost < bestCost * 0.5) || (passing && smallCost <= SMALL_AGREES * 5.0)) {
      best = small;
      smallWins = 1.0;
    }
  }

  vec4 a = texture2D(uTexA, uv - t * best);
  vec4 b = texture2D(uTexB, uv + (1.0 - t) * best);
  vec4 moved = mix(a, b, t);
  vec4 fade = mix(texture2D(uTexA, uv), texture2D(uTexB, uv), t);
  vec3 d = abs(a.rgb - b.rgb);
  // A block's trust is about its own move; a small thing's move chosen is tested by the agreement alone.
  float distrust = (1.0 - smallWins) * (1.0 - texture2D(uTrust, clamp(uv / uCellUv / uFieldSize, 0.0, 1.0)).r);
  float back = max(smoothstep(DISAGREE_FROM, DISAGREE_TO, (d.r + d.g + d.b) / 3.0), distrust);
  vec4 outColour = mix(moved, fade, back);
  vec2 r = uCellUv / BLOCK * MATCH_SLACK;
  // The small thing itself, from whichever frame has it here: its move is a
  // few pixels out at full size, and where only one copy covers the pixel it
  // is that copy, not the ground (FlowInterp.interpolate).
  if (smallCost < 1.0e8) {
    vec2 pa = uv - t * small;
    vec2 pb = uv + (1.0 - t) * small;
    vec4 sa = texture2D(uTexA, pa);
    vec4 sb = texture2D(uTexB, pb);
    bool aThing = changedBy(pa) > CHANGED && changedNear(pb, r) && nearestB(pb, r, sa.rgb) < CHANGED;
    bool bThing = changedBy(pb) > CHANGED && changedNear(pa, r) && nearestA(pa, r, sb.rgb) < CHANGED;
    // Where the two copies overlap, both ends of a rim pixel can lie where it
    // covers both frames; this pixel changed, and the ends agreeing is the
    // thing passing (or the ground, which is right too).
    bool through = !aThing && !bThing && changedBy(uv) > CHANGED
      && nearestB(pb, r, sa.rgb) < CHANGED && nearestA(pa, r, sb.rgb) < CHANGED;
    if ((aThing && bThing) || through) { outColour = mix(sa, sb, t); }
    else if (aThing) { outColour = sa; }
    else if (bThing) { outColour = sb; }
  }
  gl_FragColor = outColour;
}
