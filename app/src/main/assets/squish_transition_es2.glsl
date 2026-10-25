#version 100

// One shot's draw at a moment (ExportPlan.Draw), on its own layer before the
// compositor stacks the layers: its part in a transition, its own fade, the
// arrival of an overlay.
//
// uAlpha fades the whole frame (dissolve, dip, opacity). uShift moves the
// picture right and down by those fractions of the frame, leaving nothing
// behind it (slide, push, jitter). uScale grows it about the centre (zoom).
// Only the rectangle from uKeep.xy to uKeep.zw, as fractions of the frame, is
// drawn at all (wipe, and the old shot being cut away under a slide). uWhite
// mixes what is drawn towards white (flash, glow, dip to white) - by the
// pixel's own coverage, so the letterbox and whatever a key or mask took out
// stay as they are rather than flashing white in a file written without
// alpha. uBlur softens it (defocus), the nine taps that far apart as a
// fraction of the frame - the same ring and the same number as the effects
// library's own Blur in squish_fx_es2.glsl, so a defocus join and a placed
// blur read the same. Everything outside is fully transparent, so the layer
// underneath shows through.
//
// Y here runs up the picture, as it does in every texture Media3 hands on;
// the draw the uniforms come from runs down it, and is turned over on the way
// in (ExportPlan.Draw.shaderUniforms), so "shift down" and "keep the top" are
// already in this space by the time they arrive.
//
// Straight alpha in and out, which is what the compositor blends. uOpaque is
// for the one-sequence export, which has no compositor and writes RGB alone:
// there the fade is drawn as a mix towards black instead, which is what alpha
// over the black canvas would have come to.

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform float uAlpha;
uniform vec2 uShift;
uniform float uScale;
uniform vec4 uKeep;
uniform float uWhite;
uniform float uBlur;
uniform float uOpaque;

varying vec2 vTexSamplingCoord;

// ---- EDGE_PLAN F3: shapes, turns, squeezes -----------------------------------
// uShape is (kind, at, invert): a reveal mask, TransitionShapes.kt line for line
// (that file is the reference, executed by TransitionShapeChecks; this cannot be
// run off a phone). It is measured in the draw's own space, y down, so the row
// is turned back over to read it. uBend is (turn, squash x, squash y, shade),
// about uPivot, in this space (ExportPlan.Draw.shaderUniforms turns them over);
// uAspect is the frame's width over its height.
uniform vec3 uShape;
uniform vec4 uBend;
uniform vec2 uPivot;
uniform float uAspect;

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

bool insideHeart(vec2 q) {
  float a = q.x * q.x + q.y * q.y - 1.0;
  return a * a * a - q.x * q.x * q.y * q.y * q.y <= 0.0;
}

// The smallest heart that holds (dx, dy), y down, found by halving.
float heartScale(float dx, float dy) {
  float lo = 0.0;
  float hi = 4.0;
  for (int i = 0; i < 14; i++) {
    float s = 0.5 * (lo + hi);
    if (insideHeart(vec2(dx / s, -dy / s + 0.15))) hi = s; else lo = s;
  }
  return hi;
}

float shapeField(float kind, float x, float y) {
  float dx = (x - 0.5) * uAspect;
  float dy = y - 0.5;
  float reach = sqrt(0.25 * uAspect * uAspect + 0.25);
  float r = sqrt(dx * dx + dy * dy);
  float f = 0.0;
  if (kind < 1.5) f = r / reach;
  else if (kind < 2.5) f = 1.0 - r / reach;
  else if (kind < 3.5) f = (abs(dx) + abs(dy)) / (0.5 * uAspect + 0.5);
  else if (kind < 4.5) {
    float a = atan(dx, -dy);
    float c = abs(cos(2.5 * a));
    f = r / ((0.5 + 0.5 * c * c * c) * 2.0 * reach);
  } else if (kind < 5.5) {
    float hx = 0.5 * uAspect;
    float far = max(max(heartScale(hx, 0.5), heartScale(hx, -0.5)), max(heartScale(0.0, 0.5), heartScale(0.0, -0.5))) * 1.02;
    f = heartScale(dx, dy) / far;
  } else if (kind < 6.5) {
    float a = atan(dx, -dy);
    f = mod(a + 6.2831853, 6.2831853) / 6.2831853;
  } else if (kind < 7.5) f = fract(y * 8.0);
  else if (kind < 8.5) f = fract(x * 8.0);
  else if (kind < 9.5) f = abs(x - 0.5) * 2.0;
  else if (kind < 10.5) f = abs(y - 0.5) * 2.0;
  else if (kind < 11.5) f = (x + y) / 2.0;
  else if (kind < 12.5) {
    float cx = floor(x * 6.0 * uAspect);
    float cy = floor(y * 6.0);
    float odd = mod(cx + cy, 2.0);
    f = odd * 0.5 + 0.5 * fract(x * 6.0 * uAspect);
  } else if (kind < 13.5) f = hash(vec2(floor(x * 24.0 * uAspect), floor(y * 24.0)));
  else {
    float odd = mod(floor(y * 10.0), 2.0);
    f = odd > 0.5 ? 1.0 - x : x;
  }
  return clamp(f, 0.0, 1.0);
}

float coverage(vec2 uv) {
  if (uShape.x < 0.5) return 1.0;
  float at = uShape.y;
  float c = 1.0;
  if (at <= 0.0) c = 0.0;
  else if (at < 1.0) c = clamp((at * 1.04 - shapeField(uShape.x, uv.x, 1.0 - uv.y)) / 0.04, 0.0, 1.0);
  return uShape.z > 0.5 ? 1.0 - c : c;
}

// Where the pixel at uv reads from before the shift and scale: the squeeze and
// the turn undone about the pivot, the turn in pixels so it turns round.
vec2 bent(vec2 uv) {
  if (uBend.x == 0.0 && uBend.y == 1.0 && uBend.z == 1.0) return uv;
  vec2 d = uv - uPivot;
  d.x *= uAspect;
  float s = sin(-uBend.x);
  float k = cos(-uBend.x);
  d = vec2(d.x * k - d.y * s, d.x * s + d.y * k);
  d /= uBend.yz;
  d.x /= uAspect;
  return uPivot + d;
}

// The alpha is averaged with the colour, not taken from the middle tap: a
// masked or keyed shot softened at its edge has to soften its cut-out too, or
// the hole keeps a hard rim the picture no longer has.
vec4 sampleAt(vec2 uv) {
  if (uBlur <= 0.0001) return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
  vec4 sum = vec4(0.0);
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      sum += texture2D(uTexSampler, clamp(uv + vec2(float(i), float(j)) * uBlur, 0.0, 1.0));
    }
  }
  return sum / 9.0;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 s = (bent(uv) - uShift - 0.5) / max(uScale, 0.001) + 0.5;
  vec4 c = sampleAt(s);
  float inside = step(0.0, s.x) * step(s.x, 1.0) * step(0.0, s.y) * step(s.y, 1.0)
      * step(uKeep.x, uv.x) * step(uv.x, uKeep.z) * step(uKeep.y, uv.y) * step(uv.y, uKeep.w);
  vec3 lit = mix(c.rgb, vec3(1.0), clamp(uWhite, 0.0, 1.0) * c.a) * (1.0 - clamp(uBend.w, 0.0, 1.0));
  float a = uAlpha * inside * coverage(uv);
  gl_FragColor = mix(vec4(lit, c.a * a), vec4(lit * a, c.a), uOpaque);
}
