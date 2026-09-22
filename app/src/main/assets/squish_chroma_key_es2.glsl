#version 100
// Chroma key with spill suppression.
//
// The comparison happens in chroma only - the UV plane of YCbCr, with luma thrown
// away. That is the whole trick: a green screen is never evenly lit, and a key that
// compares full RGB punches holes in the shadowed folds of the cloth while leaving
// the hot spots solid. Chroma barely moves under a lighting change, so one setting
// holds across the whole frame.

precision mediump float;

uniform sampler2D uTexSampler;
uniform vec2 uKeyUV;
uniform float uSimilarity;
uniform float uSmoothness;
uniform float uSpill;

varying vec2 vTexSamplingCoord;

vec2 rgbToUV(vec3 rgb) {
  return vec2(
    rgb.r * -0.169 + rgb.g * -0.331 + rgb.b *  0.500 + 0.5,
    rgb.r *  0.500 + rgb.g * -0.419 + rgb.b * -0.081 + 0.5
  );
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);

  float chromaDistance = distance(rgbToUV(src.rgb), uKeyUV);
  float base = chromaDistance - uSimilarity;

  // Raised to 1.5 rather than left linear: a straight ramp across the feather band
  // leaves a visible grey halo on hair, where a slightly concave one does not.
  float mask = pow(clamp(base / uSmoothness, 0.0, 1.0), 1.5);
  float spillMask = pow(clamp(base / uSpill, 0.0, 1.0), 1.5);

  // Spill suppression. The screen bounces its colour onto shoulders and hair, and
  // those pixels are not close enough to the key to be cut - they just look wrong.
  // Pulling them toward their own luminance removes the fringe without touching
  // anything far from the key colour.
  float luma = dot(src.rgb, vec3(0.2126, 0.7152, 0.0722));
  vec3 colour = mix(vec3(luma), src.rgb, spillMask);

  // Straight alpha, matching the convention AlphaScale already uses in this app.
  gl_FragColor = vec4(colour, mask);
}
