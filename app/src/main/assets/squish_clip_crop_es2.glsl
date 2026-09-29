#version 100
// A clip's own crop: the window kept of the picture, with the picture mirrored,
// turned and zoomed under it. Each output pixel reads its source through the
// inverse of that move - CropRules.sourcePoint, written out - so the preview's
// layers and this agree to the pixel about what is kept.
//
// Points are handled top-down, as the crop rectangle is drawn; GL's texture
// origin is the bottom left, so y is flipped on the way in and on the way out.

precision mediump float;

uniform sampler2D uTexSampler;
// The window on the turned picture: top-left corner and size, as fractions.
uniform vec2 uOrigin;
uniform vec2 uExtent;
uniform float uAspect;
// The straighten angle, clockwise as seen.
uniform float uCos;
uniform float uSin;
// The zoom that keeps the turned picture covering its frame.
uniform float uZoom;
// -1 on an axis that is mirrored.
uniform vec2 uFlip;

varying vec2 vTexSamplingCoord;

void main() {
  vec2 o = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
  vec2 w = uOrigin + o * uExtent;
  vec2 p = vec2((w.x - 0.5) * uAspect, w.y - 0.5);
  // Undoing a clockwise turn, in coordinates where y points down.
  vec2 r = vec2(p.x * uCos + p.y * uSin, -p.x * uSin + p.y * uCos) / uZoom;
  r *= uFlip;
  vec2 s = clamp(vec2(r.x / uAspect + 0.5, r.y + 0.5), 0.0, 1.0);
  gl_FragColor = texture2D(uTexSampler, vec2(s.x, 1.0 - s.y));
}
