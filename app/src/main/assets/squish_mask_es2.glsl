#version 100
// Shape masks: rectangle, ellipse, linear and mirror, as signed distance fields.
//
// One shader rather than four because the shapes differ only in how the distance
// is measured - once you have a signed distance, feathering, inverting and
// compositing are identical for all of them, and writing that four times is four
// places for the edges to stop matching.

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

varying vec2 vTexSamplingCoord;

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);

  // Frame fractions, -1 to 1, then offset to the mask's centre.
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
    // Rectangle, with rounded corners.
    vec2 d = abs(q) - r + vec2(uCornerRadius);
    sd = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - uCornerRadius;
  } else if (uShape < 1.5) {
    // Ellipse. An approximate distance, which is all feathering needs.
    vec2 n = q / max(r, vec2(0.0001));
    sd = (length(n) - 1.0) * min(r.x, r.y);
  } else if (uShape < 2.5) {
    // Linear: a half-plane through the centre.
    sd = q.y;
  } else {
    // Mirror: the band between two parallel lines.
    sd = abs(q.y) - r.y;
  }

  float mask = 1.0 - smoothstep(-uFeather, uFeather, sd);
  mask = mix(mask, 1.0 - mask, uInvert);

  // Multiplied into whatever alpha arrived, so a mask composes with a chroma key
  // on the same clip instead of overwriting its matte.
  gl_FragColor = vec4(src.rgb, src.a * mask);
}
