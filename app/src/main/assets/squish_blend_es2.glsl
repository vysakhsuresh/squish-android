#version 100

// Two frames mixed: uMix of the later one (uTexB) over the earlier (uTexA).
// The frame blender's in-between frames, and with uMix at one a plain copy.

precision mediump float;

uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform float uMix;

varying vec2 vTexSamplingCoord;

void main() {
  vec4 a = texture2D(uTexA, vTexSamplingCoord);
  vec4 b = texture2D(uTexB, vTexSamplingCoord);
  gl_FragColor = mix(a, b, clamp(uMix, 0.0, 1.0));
}
