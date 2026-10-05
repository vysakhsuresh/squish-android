#version 100
// Straight alpha to premultiplied, for a frame about to be shown in a view.
//
// The key, background and mask shaders write straight alpha - a keyed pixel keeps
// its colour and gets alpha 0 - which is what Media3's compositor blends in the
// export. A non-opaque TextureView composites its buffer as premultiplied instead,
// so the same pixel was *added* over what lay behind it: a grey haze where the
// green screen had been. Multiplied through here, a pixel at alpha 0 is nothing.
//
// uBlur is an overlay's share of a Defocus join, the nine taps that far apart
// as a fraction of the frame - the same ring and the same number the file's
// TransitionEffect runs, and the same the base surfaces get from their effects
// pass. An overlay's chain ends here instead of there, because a layer keeps
// its transparency all the way to the screen, so this is where its softness
// has to go. Averaged with the alpha rather than taken from the middle tap:
// a keyed layer softened at its edge has to soften its hole too.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uBlur;

varying vec2 vTexSamplingCoord;

vec4 sampleAt(vec2 uv) {
  if (uBlur <= 0.0001) return texture2D(uTexSampler, uv);
  vec4 sum = vec4(0.0);
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      sum += texture2D(uTexSampler, clamp(uv + vec2(float(i), float(j)) * uBlur, 0.0, 1.0));
    }
  }
  return sum / 9.0;
}

void main() {
  vec4 src = sampleAt(vTexSamplingCoord);
  gl_FragColor = vec4(src.rgb * src.a, src.a);
}
