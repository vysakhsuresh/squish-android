#version 100
// One layer blended onto the picture under it.
//
// The separable blend functions of the W3C compositing spec, mirroring
// timeline/LayerBlend.kt line for line - that file is where they are executed
// and checked, and LayerBlendChecks also makes sure every mode named there has
// a branch named here, since a forgotten one would fall through to Normal in
// the file while the preview blended it properly.
//
// The layer is a still - a photo overlay or a sticker - handed over as a
// texture with its placement already worked out into uLayerMatrix, which maps
// a point of the base frame into the layer's own 0..1 space. Outside that
// space there is no layer and the base passes through untouched.

precision mediump float;

uniform sampler2D uTexSampler;
uniform sampler2D uLayer;
// Base frame coordinates to layer coordinates: the inverse of where the layer
// is placed, as a 3x3 laid out in columns.
uniform mat3 uLayerMatrix;
uniform float uMode;
uniform float uLayerAlpha;

varying vec2 vTexSamplingCoord;

const float MODE_MULTIPLY = 1.0;
const float MODE_SCREEN = 2.0;
const float MODE_OVERLAY = 3.0;
const float MODE_DARKEN = 4.0;
const float MODE_LIGHTEN = 5.0;
const float MODE_HARDLIGHT = 6.0;
const float MODE_SOFTLIGHT = 7.0;
const float MODE_DIFFERENCE = 8.0;
const float MODE_ADD = 9.0;

vec3 multiply(vec3 b, vec3 s) { return b * s; }
vec3 screenOf(vec3 b, vec3 s) { return b + s - b * s; }

vec3 hardLight(vec3 b, vec3 s) {
  return mix(multiply(b, 2.0 * s), screenOf(b, 2.0 * s - 1.0), step(0.5, s));
}

vec3 softLight(vec3 b, vec3 s) {
  vec3 d = mix(((16.0 * b - 12.0) * b + 4.0) * b, sqrt(b), step(0.25, b));
  vec3 lo = b - (1.0 - 2.0 * s) * b * (1.0 - b);
  vec3 hi = b + (2.0 * s - 1.0) * (d - b);
  return mix(lo, hi, step(0.5, s));
}

vec3 blended(vec3 b, vec3 s, float mode) {
  if (mode == MODE_MULTIPLY) return multiply(b, s);
  if (mode == MODE_SCREEN) return screenOf(b, s);
  // Overlay is Hard light with the two swapped, as LayerBlend says.
  if (mode == MODE_OVERLAY) return hardLight(s, b);
  if (mode == MODE_DARKEN) return min(b, s);
  if (mode == MODE_LIGHTEN) return max(b, s);
  if (mode == MODE_HARDLIGHT) return hardLight(b, s);
  if (mode == MODE_SOFTLIGHT) return softLight(b, s);
  if (mode == MODE_DIFFERENCE) return abs(b - s);
  if (mode == MODE_ADD) return min(vec3(1.0), b + s);
  return s;
}

void main() {
  vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 at = uLayerMatrix * vec3(vTexSamplingCoord, 1.0);
  vec2 uv = at.xy / at.z;

  // Outside the layer, the picture is untouched. Tested on both axes at once so
  // a layer hanging off the frame does not smear its edge across the rest.
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
    gl_FragColor = base;
    return;
  }

  // v flipped: GLUtils.texImage2D uploads a bitmap with its first row at the
  // top, and GL's v runs from the bottom - without this the still is a water
  // reflection of itself.
  vec4 layer = texture2D(uLayer, vec2(uv.x, 1.0 - uv.y));
  // The still is kept premultiplied nowhere in this app, so its own alpha is
  // straight - a transparent PNG blends by how opaque it is at that pixel.
  float a = layer.a * uLayerAlpha;
  vec3 mixed = blended(clamp(base.rgb, 0.0, 1.0), clamp(layer.rgb, 0.0, 1.0), uMode);
  gl_FragColor = vec4(mix(base.rgb, clamp(mixed, 0.0, 1.0), a), base.a);
}
