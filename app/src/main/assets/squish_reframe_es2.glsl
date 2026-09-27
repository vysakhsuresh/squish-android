#version 100

// Auto-reframe: the output is the window [uOrigin, uOrigin + uExtent] of the
// input, in texture coordinates, stretched to fill the frame. The window's shape
// matches the output's, so nothing is distorted.

precision mediump float;

uniform sampler2D uTexSampler;
uniform vec2 uOrigin;
uniform vec2 uExtent;

varying vec2 vTexSamplingCoord;

void main() {
  gl_FragColor = texture2D(uTexSampler, uOrigin + vTexSamplingCoord * uExtent);
}
