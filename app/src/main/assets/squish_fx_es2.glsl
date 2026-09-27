#version 100

// The effects library in one pass. Every parameter is worked out per frame on
// the CPU (see FxParams), so this only has to apply them; at their resting
// values each stage is a no-op and the frame passes through unchanged.

precision mediump float;

uniform sampler2D uTexSampler;
uniform vec2 uOffset;
uniform float uZoom;
uniform float uSplit;
uniform float uGlitch;
uniform float uFlash;
uniform float uMono;
uniform float uInvert;
uniform float uScan;
uniform float uNoise;
uniform float uBlur;
uniform float uHue;
uniform float uTime;

varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

vec3 sampleAt(vec2 uv) {
  if (uBlur <= 0.0001) return texture2D(uTexSampler, uv).rgb;
  vec3 sum = vec3(0.0);
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      sum += texture2D(uTexSampler, uv + vec2(float(i), float(j)) * uBlur).rgb;
    }
  }
  return sum / 9.0;
}

vec3 hueRotate(vec3 c, float a) {
  float s = sin(a);
  float k = cos(a);
  mat3 m = mat3(
    0.299 + 0.701 * k + 0.168 * s, 0.587 - 0.587 * k + 0.330 * s, 0.114 - 0.114 * k - 0.497 * s,
    0.299 - 0.299 * k - 0.328 * s, 0.587 + 0.413 * k + 0.035 * s, 0.114 - 0.114 * k + 0.292 * s,
    0.299 - 0.300 * k + 1.250 * s, 0.587 - 0.588 * k - 1.050 * s, 0.114 + 0.886 * k - 0.203 * s
  );
  return clamp(c * m, 0.0, 1.0);
}

void main() {
  vec2 uv = (vTexSamplingCoord - 0.5) / uZoom + 0.5 + uOffset;

  if (uGlitch > 0.001) {
    // Horizontal bands that jump sideways, re-rolled a dozen times a second.
    float band = floor(uv.y * 18.0);
    float roll = floor(uTime * 12.0);
    float r = hash(vec2(band, roll));
    if (r > 0.72) uv.x += (hash(vec2(roll, band)) - 0.5) * 0.12 * uGlitch;
  }

  uv = clamp(uv, 0.0, 1.0);

  vec3 c;
  if (uSplit > 0.0001) {
    c.r = sampleAt(clamp(uv + vec2(uSplit, 0.0), 0.0, 1.0)).r;
    c.g = sampleAt(uv).g;
    c.b = sampleAt(clamp(uv - vec2(uSplit, 0.0), 0.0, 1.0)).b;
  } else {
    c = sampleAt(uv);
  }

  if (uHue > 0.001) c = hueRotate(c, uHue);
  if (uMono > 0.001) c = mix(c, vec3(dot(c, LUMA)), uMono);
  if (uInvert > 0.001) c = mix(c, 1.0 - c, uInvert);
  if (uScan > 0.001) {
    float line = 0.5 + 0.5 * sin(vTexSamplingCoord.y * 900.0);
    c *= 1.0 - uScan * 0.22 * line;
  }
  if (uNoise > 0.001) {
    c += (hash(vTexSamplingCoord * 1000.0 + uTime * 60.0) - 0.5) * uNoise;
  }
  if (uFlash > 0.001) c = mix(c, vec3(1.0), clamp(uFlash, 0.0, 1.0));

  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
