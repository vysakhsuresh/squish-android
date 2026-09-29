#version 100
// The full grade in one pass: sharpening, bloom, gain, brightness, contrast,
// highlights and shadows, saturation, hue, the HSL bands, fade, split-tone,
// vignette and grain.
//
// One shader rather than a chain because a premium look uses most of these at
// once, and Media3's built-in colour effects are one pass each. A dozen passes
// over every frame of a 4K clip is a real cost on a mid-range phone; this is one.
// The order is Grade.applyTo's, which is the CPU copy a swatch is drawn with;
// the two must not drift apart.

precision mediump float;

uniform sampler2D uTexSampler;

uniform vec3 uGain;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;

// Lifting the bright part of the picture and the dark part separately.
uniform float uHighlights;
uniform float uShadows;

// A turn of the whole wheel, in radians.
uniform float uHue;

// One vec3 per band of the wheel - hue nudge, saturation, luminance - and a
// switch, so a grade with no band moved never converts to HSV at all.
uniform float uHslOn;
uniform vec3 uHsl0;
uniform vec3 uHsl1;
uniform vec3 uHsl2;
uniform vec3 uHsl3;
uniform vec3 uHsl4;
uniform vec3 uHsl5;
uniform vec3 uHsl6;
uniform vec3 uHsl7;

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
uniform float uSharpen;

// Seconds, so grain moves between frames. Still grain reads as a dirty lens.
uniform float uTime;
uniform float uAspect;
// The step the sharpening taps take, as a fraction of the frame each way.
uniform vec2 uTexel;

varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float TONE_REACH = 0.3;
const float HSL_LUMA_REACH = 0.5;
const float HUE_SWING = 30.0;
const float BAND_REACH = 45.0;

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

vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

/** How much of a pixel at hue h (degrees) a band centred on d owns; see HslBand.weight. */
float bandWeight(float h, float d) {
  float dist = abs(h - d);
  dist = min(dist, 360.0 - dist);
  return clamp(1.0 - dist / BAND_REACH, 0.0, 1.0);
}

/** One band's pull on the pixel: (hue degrees, saturation, luminance), weighted. */
vec3 bandPull(float h, float d, vec3 band) {
  float w = bandWeight(h, d);
  return vec3(w * band.x * HUE_SWING, w * band.y, w * band.z);
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;

  // Sharpening first, on the untouched picture: an unsharp mask against the
  // four neighbours a step away. Done after the grade it would sharpen the
  // grain and the vignette's edge along with the picture.
  if (uSharpen > 0.001) {
    vec3 around = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexel.x, 0.0)).rgb +
      texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexel.x, 0.0)).rgb +
      texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexel.y)).rgb +
      texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexel.y)).rgb;
    c += (c - around * 0.25) * uSharpen * 1.5;
  }

  // Bloom is taken from the untouched picture. Doing it after the grade would
  // glow whatever the contrast boost happened to push over the threshold, which
  // makes the effect jump around as the look is dialled.
  if (uBloom > 0.001) {
    vec3 soft = ringBlur(vTexSamplingCoord, 0.014);
    vec3 highlights = max(soft - 0.62, 0.0) * 2.6;
    c += highlights * uBloom;
  }

  c *= uGain;
  c += vec3(uBrightness);

  // Media3's Contrast curve, so a look built on the built-in path and the same
  // look built here land on the same picture.
  float f = (1.0 + uContrast) / (1.0001 - uContrast);
  c = f * (c - 0.5) + 0.5;

  if (abs(uHighlights) > 0.001 || abs(uShadows) > 0.001) {
    float l = clamp(dot(c, LUMA), 0.0, 1.0);
    float lift = uHighlights * TONE_REACH * smoothstep(0.45, 1.0, l) +
      uShadows * TONE_REACH * (1.0 - smoothstep(0.0, 0.55, l));
    c += vec3(lift);
  }

  float lum = dot(c, LUMA);
  c = mix(vec3(lum), c, max(0.0, 1.0 + uSaturation));

  if (abs(uHue) > 0.0001 || uHslOn > 0.5) {
    vec3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));
    float h = fract(hsv.x + uHue / 6.2831853) * 360.0;
    if (uHslOn > 0.5) {
      // Greys have no hue to speak of, so a band leaves them alone.
      float owned = smoothstep(0.05, 0.3, hsv.y);
      vec3 pull = bandPull(h, 0.0, uHsl0) + bandPull(h, 30.0, uHsl1) + bandPull(h, 60.0, uHsl2) +
        bandPull(h, 120.0, uHsl3) + bandPull(h, 180.0, uHsl4) + bandPull(h, 240.0, uHsl5) +
        bandPull(h, 270.0, uHsl6) + bandPull(h, 300.0, uHsl7);
      pull *= owned;
      h = h + pull.x;
      hsv.y = clamp(hsv.y * (1.0 + pull.y), 0.0, 1.0);
      hsv.z = clamp(hsv.z * (1.0 + pull.z * HSL_LUMA_REACH), 0.0, 1.0);
    }
    hsv.x = fract(h / 360.0);
    c = hsv2rgb(hsv);
  }

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
