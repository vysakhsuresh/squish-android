#version 100
// Shape masks: rectangle, ellipse, linear and mirror, as signed distance fields -
// and three things to do with the result.
//
// One shader rather than several because the shapes differ only in how the distance
// is measured. Once you have a signed distance, feathering and inverting are
// identical for all of them, and writing that four times is four places for the
// edges to stop matching.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uShape;
uniform vec2 uCenter;
uniform vec2 uHalfSize;
uniform float uRotation;
uniform float uFeather;
uniform float uCornerRadius;
uniform float uInvert;
uniform float uAspect;

// 0 cut the shape out, 1 pixelate inside it, 2 blur inside it.
uniform float uMode;
uniform float uPixelSize;
uniform float uBlurRadius;

varying vec2 vTexSamplingCoord;

/**
 * Twenty-four taps on a golden-angle spiral, plus the center.
 *
 * The first version used two rings of six and was far too weak to be a privacy
 * tool - a face came through it perfectly recognisable, which is worse than no blur
 * at all because it looks like the job was done. Two things fix that: a radius that
 * actually approaches the size of the thing being hidden, and enough taps to fill
 * it. Rings leave ghosting at large radii because every tap sits at the same few
 * angles; a spiral spreads them evenly and reads as a blur rather than a smear.
 */
vec3 blurred(vec2 uv, float r) {
  vec3 sum = texture2D(uTexSampler, uv).rgb;
  for (int i = 0; i < 24; i++) {
    float f = (float(i) + 0.5) / 24.0;
    float a = float(i) * 2.39996;                 // golden angle
    // sqrt keeps the samples evenly spread over the disc rather than bunched in
    // the middle, which is what a linear radius would do.
    vec2 o = vec2(cos(a), sin(a)) * sqrt(f) * r;
    sum += texture2D(uTexSampler, uv + o).rgb;
  }
  return sum / 25.0;
}

/**
 * Averaged over the block rather than taking its center pixel. Point-sampling makes
 * a whole block whatever single pixel happened to land in the middle, so a dark eye
 * either vanishes or becomes a solid black square - artefacts that read as a glitch
 * instead of a censor.
 */
vec3 pixelated(vec2 uv, float size) {
  vec2 corner = floor(uv / vec2(size)) * vec2(size);
  vec3 sum = vec3(0.0);
  for (int y = 0; y < 3; y++) {
    for (int x = 0; x < 3; x++) {
      vec2 at = corner + vec2((float(x) + 0.5) / 3.0, (float(y) + 0.5) / 3.0) * size;
      sum += texture2D(uTexSampler, at).rgb;
    }
  }
  return sum / 9.0;
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);

  // Frame fractions, -1 to 1, then offset to the mask's center.
  vec2 p = vTexSamplingCoord * 2.0 - 1.0;
  p -= uCenter;

  // Into pixel-isotropic units: one unit of x is now the same number of pixels as
  // one unit of y. Rotating before this step shears the shape on any frame that is
  // not square, which is every frame anyone actually shoots.
  vec2 q = vec2(p.x * uAspect, p.y);
  float c = cos(-uRotation);
  float s = sin(-uRotation);
  q = vec2(q.x * c - q.y * s, q.x * s + q.y * c);

  vec2 r = vec2(uHalfSize.x * uAspect, uHalfSize.y);

  // Signed distance: negative inside the shape, positive outside.
  float sd;
  if (uShape < 0.5) {
    vec2 d = abs(q) - r + vec2(uCornerRadius);
    sd = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - uCornerRadius;
  } else if (uShape < 1.5) {
    vec2 n = q / max(r, vec2(0.0001));
    sd = (length(n) - 1.0) * min(r.x, r.y);
  } else if (uShape < 2.5) {
    sd = q.y;
  } else {
    sd = abs(q.y) - r.y;
  }

  float mask = 1.0 - smoothstep(-uFeather, uFeather, sd);
  mask = mix(mask, 1.0 - mask, uInvert);

  if (uMode < 0.5) {
    // Cut out. Multiplied into whatever alpha arrived, so a mask composes with a
    // chroma key on the same clip instead of overwriting its matte.
    gl_FragColor = vec4(src.rgb, src.a * mask);
  } else {
    // Obscure. The picture stays; what is inside the shape is destroyed. Alpha is
    // left alone - hiding a face must not also punch a hole in the frame.
    vec3 hidden;
    if (uMode < 1.5) {
      hidden = pixelated(vTexSamplingCoord, uPixelSize);
    } else {
      hidden = blurred(vTexSamplingCoord, uBlurRadius);
    }
    gl_FragColor = vec4(mix(src.rgb, hidden, mask), src.a);
  }
}
