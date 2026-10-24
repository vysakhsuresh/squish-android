#version 100
// An HDR clip's picture after Media3's own HDR-to-SDR, lifted to the white
// Squish's own pictures use (EDGE_PLAN B4). Media3 puts an HDR picture's peak
// at white, so HLG's reference white - skin, a white shirt, 203 nits - came
// out at half brightness, in the preview and in the file alike, while the
// filmstrip beside it was right. ToneMap.liftMedia3 is this, line for line,
// and tools/jvm/ToneMapChecks.kt executes it against the measured bars.
//
// uOn is 0 for an SDR clip, which passes untouched; uTop is the peak in units
// of SDR white (ToneMap.top), uGamma how Media3's output reads back to light.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uOn;
uniform float uTop;
uniform float uGamma;

varying vec2 vTexSamplingCoord;

const float KNEE = 0.75;

// ToneMap.curve: the identity to the knee, then the excess rolled into white at uTop.
float curve(float x) {
  if (x <= KNEE) return max(x, 0.0);
  float span = 1.0 - KNEE;
  float u = (x - KNEE) / span;
  float um = (uTop - KNEE) / span;
  return min(1.0, KNEE + span * u * (1.0 + u / (um * um)) / (1.0 + u));
}

float oetf709(float l) {
  float x = clamp(l, 0.0, 1.0);
  return x < 0.018 ? 4.5 * x : 1.099 * pow(x, 0.45) - 0.099;
}

void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float m = clamp(max(c.r, max(c.g, c.b)), 0.0, 1.0);
  if (uOn < 0.5 || m <= 0.0) {
    gl_FragColor = c;
    return;
  }
  float lm = pow(m, uGamma) * uTop;
  float ratio = curve(lm) / lm;
  vec3 lin = pow(clamp(c.rgb, 0.0, 1.0), vec3(uGamma)) * uTop * ratio;
  gl_FragColor = vec4(oetf709(lin.r), oetf709(lin.g), oetf709(lin.b), c.a);
}
