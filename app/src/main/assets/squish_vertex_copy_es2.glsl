#version 100
// ES 2 vertex shader that leaves the coordinates unchanged.
// Adapted from the Android Open Source Project (Apache 2.0) - see
// THIRD_PARTY_NOTICES.md.

attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;

void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
