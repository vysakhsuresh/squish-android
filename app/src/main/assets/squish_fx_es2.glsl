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
// 1 in the preview's surfaces: straight alpha in (a mask's cut), put over black here. 0 in the export.
uniform float uOverBlack;

varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// The alpha comes back with the colour, averaged over the same ring: a base
// surface carries a mask's cut and a key's hole as alpha until this pass puts
// it over black, and reading the alpha from the middle tap alone left a
// hard-edged hole in a picture that had been softened round it.
vec4 sampleAt(vec2 uv) {
  if (uBlur <= 0.0001) return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
  vec4 sum = vec4(0.0);
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      // Clamped, as the three other copies of these nine taps are
      // (squish_transition_es2 and squish_premultiply_es2 here, CanvasFx's AGSL
      // inside its own `texel`, which is where that one turns y over). A tap
      // at the frame's edge reaches past it, and what it reads then is the
      // sampler's wrap mode - clamp-to-edge as Media3 makes its textures, but
      // that is the library's choice and not this shader's to lean on. Four
      // copies of one blur is already one more than anyone can hold; they may
      // at least be the same four lines.
      sum += texture2D(uTexSampler, clamp(uv + vec2(float(i), float(j)) * uBlur, 0.0, 1.0));
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

  // The middle sample carries the alpha whichever way the colour is read: an
  // RGB split moves the channels and not the shape.
  vec4 centre = sampleAt(uv);
  vec3 c = centre.rgb;
  if (uSplit > 0.0001) {
    c.r = sampleAt(clamp(uv + vec2(uSplit, 0.0), 0.0, 1.0)).r;
    c.b = sampleAt(clamp(uv - vec2(uSplit, 0.0), 0.0, 1.0)).b;
  }

  // On the size of it, not its sign: Dream drifts the hue by a sine, so the
  // negative half of every swing was skipped and the colour turned one way and
  // then sat flat. The other two producers (Rainbow, Trippy) only ever climb.
  if (abs(uHue) > 0.001) c = hueRotate(c, uHue);
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

  // Opaque out, as the file is - but what came in see-through (a mask's cut,
  // outside its shape) goes to black first, which is what the file composites it
  // over. Written opaque as it was, the preview showed the whole picture under a
  // Cutout mask while the file showed the shape alone.
  // Read where the colour was read, so a cut-out hole moves with a shake or a
  // zoom - and softened with it, so a blurred picture does not keep a hard hole.
  float a = mix(1.0, centre.a, uOverBlack);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0) * a, 1.0);
}
