#version 100

// One shot's draw at a moment (ExportPlan.Draw), on its own layer before the
// compositor stacks the layers: its part in a transition, its own fade, the
// arrival of an overlay.
//
// uAlpha fades the whole frame (dissolve, dip, opacity). uShift moves the
// picture right and down by those fractions of the frame, leaving nothing
// behind it (slide, push, jitter). uScale grows it about the centre (zoom).
// Only the rectangle from uKeep.xy to uKeep.zw, as fractions of the frame, is
// drawn at all (wipe, and the old shot being cut away under a slide). uWhite
// mixes what is drawn towards white (flash, glow, dip to white) - by the
// pixel's own coverage, so the letterbox and whatever a key or mask took out
// stay as they are rather than flashing white in a file written without
// alpha. uBlur softens it (defocus), the nine taps that far apart as a
// fraction of the frame - the same ring and the same number as the effects
// library's own Blur in squish_fx_es2.glsl, so a defocus join and a placed
// blur read the same. Everything outside is fully transparent, so the layer
// underneath shows through.
//
// Y here runs up the picture, as it does in every texture Media3 hands on;
// the draw the uniforms come from runs down it, and is turned over on the way
// in (ExportPlan.Draw.shaderUniforms), so "shift down" and "keep the top" are
// already in this space by the time they arrive.
//
// Straight alpha in and out, which is what the compositor blends. uOpaque is
// for the one-sequence export, which has no compositor and writes RGB alone:
// there the fade is drawn as a mix towards black instead, which is what alpha
// over the black canvas would have come to.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uAlpha;
uniform vec2 uShift;
uniform float uScale;
uniform vec4 uKeep;
uniform float uWhite;
uniform float uBlur;
uniform float uOpaque;

varying vec2 vTexSamplingCoord;

// The alpha is averaged with the colour, not taken from the middle tap: a
// masked or keyed shot softened at its edge has to soften its cut-out too, or
// the hole keeps a hard rim the picture no longer has.
vec4 sampleAt(vec2 uv) {
  if (uBlur <= 0.0001) return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
  vec4 sum = vec4(0.0);
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      sum += texture2D(uTexSampler, clamp(uv + vec2(float(i), float(j)) * uBlur, 0.0, 1.0));
    }
  }
  return sum / 9.0;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 s = (uv - uShift - 0.5) / max(uScale, 0.001) + 0.5;
  vec4 c = sampleAt(s);
  float inside = step(0.0, s.x) * step(s.x, 1.0) * step(0.0, s.y) * step(s.y, 1.0)
      * step(uKeep.x, uv.x) * step(uv.x, uKeep.z) * step(uKeep.y, uv.y) * step(uv.y, uKeep.w);
  vec3 lit = mix(c.rgb, vec3(1.0), clamp(uWhite, 0.0, 1.0) * c.a);
  float a = uAlpha * inside;
  gl_FragColor = mix(vec4(lit, c.a * a), vec4(lit * a, c.a), uOpaque);
}
