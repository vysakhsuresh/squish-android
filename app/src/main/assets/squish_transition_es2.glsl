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
// mixes what is drawn towards white (flash, glow, dip to white). Everything
// outside is fully transparent, so the layer underneath shows through.
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
uniform float uOpaque;

varying vec2 vTexSamplingCoord;

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 s = (uv - uShift - 0.5) / max(uScale, 0.001) + 0.5;
  vec4 c = texture2D(uTexSampler, clamp(s, 0.0, 1.0));
  float inside = step(0.0, s.x) * step(s.x, 1.0) * step(0.0, s.y) * step(s.y, 1.0)
      * step(uKeep.x, uv.x) * step(uv.x, uKeep.z) * step(uKeep.y, uv.y) * step(uv.y, uKeep.w);
  vec3 lit = mix(c.rgb, vec3(1.0), clamp(uWhite, 0.0, 1.0));
  float a = uAlpha * inside;
  gl_FragColor = mix(vec4(lit, c.a * a), vec4(lit * a, c.a), uOpaque);
}
