#version 100
// The full grade in one pass: gain, contrast, saturation, fade, split-tone,
// bloom, vignette and grain.
//
// One shader rather than a chain because a premium look uses most of these at
// once, and Media3's built-in colour effects are one pass each. Six passes over
// every frame of a 4K clip is a real cost on a mid-range phone; this is one.
//
// Looks that need none of the film moves never reach here - they still run on the
// built-in hardware effects, which are cheaper than anything written by hand.

precision mediump float;

uniform sampler2D uTexSampler;

uniform vec3 uGain;
uniform float uContrast;
uniform float uSaturation;

// Lifted blacks. The single move that most says "film" rather than "phone".
uniform float uFade;

// Split tone: shadows pulled toward one colour, highlights toward another. Each
// is centred on 0.5, so a neutral grey tint is a no-op at any strength.
uniform vec3 uShadowTint;
uniform vec3 uHighlightTint;
uniform float uSplit;

uniform float uBloom;
uniform float uVignette;
uniform float uGrain;

// Seconds, so grain moves between frames. Still grain reads as a dirty lens.
uniform float uTime;
uniform float uAspect;

varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

/**
 * Hash without a sine.
 *
 * The usual fract(sin(dot(p, k)) * 43758.5) needs highp to be random at all, and
 * ES 2 fragment shaders are not required to have highp. Every constant here stays
 * small enough to survive mediump, so the grain is grain on every device rather
 * than diagonal banding on some of them.
 */
float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

/** Eight taps on a ring, for the glow. Cheap, and blur quality hardly matters here. */
vec3 ringBlur(vec2 uv, float r) {
  vec3 sum = texture2D(uTexSampler, uv).rgb;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 0.7853981;
    vec2 o = vec2(cos(a), sin(a)) * r;
    sum += texture2D(uTexSampler, uv + o).rgb;
  }
  return sum / 9.0;
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;

  // Bloom is taken from the untouched picture. Doing it after the grade would
  // glow whatever the contrast boost happened to push over the threshold, which
  // makes the effect jump around as the look is dialled.
  if (uBloom > 0.001) {
    vec3 soft = ringBlur(vTexSamplingCoord, 0.014);
    vec3 highlights = max(soft - 0.62, 0.0) * 2.6;
    c += highlights * uBloom;
  }

  c *= uGain;

  // Media3's Contrast curve, so a look built on the built-in path and the same
  // look built here land on the same picture.
  float f = (1.0 + uContrast) / (1.0001 - uContrast);
  c = f * (c - 0.5) + 0.5;

  float lum = dot(c, LUMA);
  c = mix(vec3(lum), c, max(0.0, 1.0 + uSaturation));

  if (uSplit > 0.001) {
    float l = clamp(dot(c, LUMA), 0.0, 1.0);
    vec3 tint = mix(uShadowTint, uHighlightTint, l);
    c += (tint - 0.5) * uSplit * 0.55;
  }

  // Compressing toward a raised floor rather than adding a flat offset: adding
  // would wash the highlights out too and the picture would just look faint.
  c = c * (1.0 - uFade * 0.55) + vec3(uFade * 0.16);

  if (uVignette > 0.001) {
    vec2 p = (vTexSamplingCoord - 0.5) * vec2(uAspect, 1.0);
    float d = length(p) / length(vec2(uAspect * 0.5, 0.5));
    c *= 1.0 - uVignette * smoothstep(0.42, 1.06, d);
  }

  if (uGrain > 0.001) {
    float n = hash(vTexSamplingCoord * 512.0 + uTime) - 0.5;
    // Strongest in the midtones, the way real film grain behaves: clean blacks,
    // clean highlights, texture in between.
    float l = clamp(dot(c, LUMA), 0.0, 1.0);
    c += n * uGrain * 0.17 * (1.0 - abs(l * 2.0 - 1.0));
  }

  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
