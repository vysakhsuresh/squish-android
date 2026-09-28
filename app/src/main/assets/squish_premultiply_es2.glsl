#version 100
// Straight alpha to premultiplied, for a frame about to be shown in a view.
//
// The key, background and mask shaders write straight alpha - a keyed pixel keeps
// its colour and gets alpha 0 - which is what Media3's compositor blends in the
// export. A non-opaque TextureView composites its buffer as premultiplied instead,
// so the same pixel was *added* over what lay behind it: a grey haze where the
// green screen had been. Multiplied through here, a pixel at alpha 0 is nothing.

precision mediump float;

uniform sampler2D uTexSampler;

varying vec2 vTexSamplingCoord;

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4(src.rgb * src.a, src.a);
}
