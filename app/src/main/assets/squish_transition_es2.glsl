#version 100

// One base shot's part in a transition, drawn on its own layer before the
// compositor stacks the layers.
//
// uAlpha fades the whole frame (dissolve, dip). uShift moves the picture right
// by that fraction of the frame, leaving nothing behind it (slide). Only the
// columns from uKeep.x to uKeep.y, as fractions of the width, are drawn at all
// (wipe, and the old shot being cut away under a slide). Everything else is
// fully transparent, so the layer underneath shows through.
//
// Straight alpha in and out, which is what the compositor blends.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uAlpha;
uniform float uShift;
uniform vec2 uKeep;

varying vec2 vTexSamplingCoord;

void main() {
  vec2 uv = vTexSamplingCoord;
  float x = uv.x - uShift;
  vec4 c = texture2D(uTexSampler, vec2(clamp(x, 0.0, 1.0), uv.y));
  float inside = step(0.0, x) * step(x, 1.0) * step(uKeep.x, uv.x) * step(uv.x, uKeep.y);
  gl_FragColor = vec4(c.rgb, c.a * uAlpha * inside);
}
