#version 100

// The effects library in one pass. Every parameter is worked out per frame on
// the CPU (see FxParams), so this only has to apply them; at their resting
// values each stage is a no-op and the frame passes through unchanged.

// High where the driver has it: the particles' fall is the clock times a cell
// count, thousands after a minute, and a half float's steps there are whole cells.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

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
// The second set's knobs (FxParams.lensA ... glitch2) and the frame's width over its height.
uniform vec4 uLensA;
uniform vec4 uLensB;
uniform vec4 uLight;
uniform vec4 uTone;
uniform vec4 uParticles;
uniform vec4 uGlitch2;
uniform float uAspect;
// 1 on one shot of a join in the file (FxEffect.keepAlpha): straight alpha out, as it came in.
uniform float uKeepAlpha;

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

vec3 pick(vec2 uv) {
  return texture2D(uTexSampler, clamp(uv, 0.0, 1.0)).rgb;
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

// ---- The library's second set of stages (EDGE_PLAN F2) ----
// Word for word the same in squish_fx_es2.glsl and CanvasFx.kt's AGSL, but for
// the type names, which AGSL spells float2 and so on; ShaderUniformChecks compares the
// two. Each file supplies pick(uv), the picture's colour at uv, and hash().
// Coordinates are 0..1 with y running up; uAspect is the frame's width over
// its height, so a circle drawn in q = (x * uAspect, y) is round.

vec2 turnAbout(vec2 uv, float a) {
  vec2 d = uv - 0.5;
  d.x *= uAspect;
  float s = sin(a);
  float k = cos(a);
  d = vec2(d.x * k - d.y * s, d.x * s + d.y * k);
  d.x /= uAspect;
  return d + 0.5;
}

// Where each pixel reads from: turn, lens, fold, ripple, mirror, tiles,
// pixels, then the block and tear glitches.
vec2 warp(vec2 uv) {
  if (abs(uLensB.y) > 0.0001) uv = turnAbout(uv, uLensB.y);
  if (uLensA.x > 0.001) {
    vec2 d = uv - 0.5;
    d.x *= uAspect;
    float r2 = dot(d, d);
    d *= 1.0 - uLensA.x * (0.45 - 1.1 * r2);
    d.x /= uAspect;
    uv = d + 0.5;
  }
  if (uLensA.y > 2.5) {
    vec2 d = uv - 0.5;
    d.x *= uAspect;
    float r = length(d);
    float seg = 6.2831853 / uLensA.y;
    float a = mod(atan(d.y, d.x + 0.000001) + uTime * 0.2, seg);
    a = abs(a - seg * 0.5);
    d = vec2(cos(a), sin(a)) * r;
    d.x /= uAspect;
    uv = d + 0.5;
  }
  if (uLensA.z > 0.001) {
    vec2 d = uv - 0.5;
    d.x *= uAspect;
    float r = length(d);
    float w = sin(r * 48.0 - uTime * 7.0) * 0.014 * uLensA.z;
    vec2 n = d / max(r, 0.0001);
    uv += vec2(n.x / uAspect, n.y) * w;
  }
  if (uLensB.x > 0.5 && uLensB.x < 1.5) uv.x = 0.5 - abs(uv.x - 0.5);
  if (uLensB.x > 1.5) uv.y = 0.5 + abs(uv.y - 0.5);
  if (uLensA.w > 1.5) uv = fract(uv * uLensA.w);
  if (uLensB.w > 0.0001) {
    vec2 cell = vec2(uLensB.w / uAspect, uLensB.w);
    uv = (floor(uv / cell) + 0.5) * cell;
  }
  if (uGlitch2.x > 0.001) {
    float roll = floor(uTime * 10.0);
    vec2 block = floor(uv * vec2(6.0, 10.0));
    if (hash(block + roll * 7.31) > 1.0 - 0.35 * uGlitch2.x) {
      uv += (vec2(hash(block * 1.7 + roll), hash(block * 2.3 - roll)) - 0.5) * 0.16 * uGlitch2.x;
    }
  }
  if (uGlitch2.y > 0.001) {
    float at = fract(uTime * 0.23 + 0.5 * hash(vec2(floor(uTime * 0.7), 3.0)));
    float h = 0.03 + 0.05 * uGlitch2.y;
    float inside = 1.0 - smoothstep(0.0, h, abs(uv.y - at));
    uv.x += inside * (0.05 + 0.04 * sin(uv.y * 160.0 + uTime * 30.0)) * uGlitch2.y;
  }
  return uv;
}

// The stages that read the picture more than once: a zoom blur towards the
// middle, and the channels pulled apart along a wave.
vec3 resample(vec3 c, vec2 uv) {
  if (uLensB.z > 0.001) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
      sum += pick(mix(uv, vec2(0.5), float(i) / 7.0 * 0.18 * uLensB.z));
    }
    c = sum / 8.0;
  }
  if (uGlitch2.z > 0.001) {
    float o = sin(uv.y * 22.0 + uTime * 6.0) * 0.012 * uGlitch2.z;
    c.r = pick(uv + vec2(o, 0.0)).r;
    c.b = pick(uv - vec2(o, 0.0)).b;
  }
  return c;
}

vec3 screen(vec3 c, vec3 l) {
  return 1.0 - (1.0 - c) * (1.0 - clamp(l, 0.0, 1.0));
}

// Inside where it is below nothing: (x^2 + y^2 - 1)^3 - x^2 y^3, lobes up.
float heart(vec2 q) {
  float a = q.x * q.x + q.y * q.y - 1.0;
  return a * a * a - q.x * q.x * q.y * q.y * q.y;
}

vec3 tone(vec3 c) {
  if (uTone.y > 0.001) {
    float l = dot(c, LUMA);
    vec3 t = c;
    if (uTone.z < 0.5) t = vec3(l * 1.07 + 0.05, l * 0.9 + 0.03, l * 0.68);
    else if (uTone.z < 1.5) t = mix(vec3(0.12, 0.05, 0.32), vec3(1.0, 0.55, 0.45), l);
    else if (uTone.z < 2.5) t = c * vec3(1.1, 1.0, 0.82) + vec3(0.04, 0.015, 0.0);
    else t = c * vec3(0.86, 0.98, 1.12) + vec3(0.0, 0.01, 0.03);
    c = mix(c, clamp(t, 0.0, 1.0), uTone.y);
  }
  if (uTone.w > 1.5) c = floor(c * (uTone.w - 1.0) + 0.5) / (uTone.w - 1.0);
  return c;
}

vec3 lights(vec3 c, vec2 p) {
  vec2 q = vec2(p.x * uAspect, p.y);
  if (uLight.x > 0.001) {
    vec2 at = vec2((0.2 + 0.6 * (0.5 + 0.5 * sin(uTime * 0.35))) * uAspect, 0.95);
    float g = exp(-dot(q - at, q - at) * 3.0);
    vec2 at2 = vec2((0.8 - 0.5 * (0.5 + 0.5 * sin(uTime * 0.27 + 1.3))) * uAspect, 0.05);
    float g2 = exp(-dot(q - at2, q - at2) * 5.0);
    c = screen(c, (vec3(1.0, 0.45, 0.12) * g + vec3(1.0, 0.2, 0.35) * g2 * 0.7) * uLight.x);
  }
  if (uLight.y > 0.001) {
    float edge = min(min(p.x, 1.0 - p.x), min(p.y, 1.0 - p.y));
    float n = hash(floor(p * 24.0) + floor(uTime * 8.0)) * 0.08;
    float b = 1.0 - smoothstep(0.0, 0.25 * uLight.y + n, edge);
    c = screen(c, mix(vec3(1.0, 0.35, 0.05), vec3(1.0, 0.95, 0.8), b * b) * b * uLight.y * 1.2);
  }
  if (uLight.z > 0.001) {
    vec3 add = vec3(0.0);
    for (int i = 0; i < 8; i++) {
      float fi = float(i);
      vec2 at = vec2(hash(vec2(fi, 1.0)) * uAspect + 0.05 * sin(uTime * 0.3 + fi), hash(vec2(fi, 2.0)) + 0.04 * cos(uTime * 0.25 + fi * 1.7));
      float r = 0.06 + 0.07 * hash(vec2(fi, 3.0));
      float disc = 1.0 - smoothstep(r * 0.85, r, length(q - at));
      add += disc * mix(vec3(1.0, 0.75, 0.4), vec3(0.6, 0.8, 1.0), hash(vec2(fi, 4.0))) * 0.35;
    }
    c = screen(c, add * uLight.z);
  }
  if (uLight.w > 0.001) {
    vec2 src = vec2((0.75 + 0.05 * sin(uTime * 0.4)) * uAspect, 0.82);
    vec2 mid = vec2(0.5 * uAspect, 0.5);
    vec2 d = q - src;
    float glow = exp(-dot(d, d) * 40.0) + 0.25 * exp(-dot(d, d) * 6.0);
    float streak = exp(-abs(d.y) * 160.0) * exp(-abs(d.x) * 2.5);
    vec3 l = vec3(1.0, 0.9, 0.75) * glow + vec3(0.6, 0.75, 1.0) * streak * 0.8;
    for (int i = 1; i < 4; i++) {
      vec2 ghost = mix(src, mid, 1.0 + float(i) * 0.45);
      float gr = 0.03 + 0.025 * float(i);
      float ring = 1.0 - smoothstep(gr * 0.7, gr, length(q - ghost));
      l += ring * vec3(0.3, 0.5, 0.35) * 0.35;
    }
    c = screen(c, l * uLight.w);
  }
  return c;
}

// Snow 1, rain 2, sparkles 3, hearts 4, confetti 5, dust 6: three layers of a
// grid, a particle or none in each cell, the far layers smaller and slower.
vec3 particles(vec3 c, vec2 p) {
  float kind = uParticles.x;
  vec2 q = vec2(p.x * uAspect, p.y);
  float drift = 0.1;
  if (kind > 1.5 && kind < 2.5) drift = 1.6;
  if (kind > 2.5 && kind < 3.5) drift = 0.0;
  if (kind > 3.5 && kind < 4.5) drift = -0.08;
  if (kind > 4.5 && kind < 5.5) drift = 0.22;
  if (kind > 5.5) drift = 0.02;
  for (int layer = 0; layer < 3; layer++) {
    float fl = float(layer);
    float scale = 6.0 + fl * 4.0;
    float near = 1.0 - fl * 0.25;
    vec2 g = q * scale + vec2(0.0, uTime * drift * near * scale) + fl * 17.0;
    vec2 cell = floor(g);
    vec2 f = fract(g) - 0.5;
    float h = hash(cell + fl * 31.0);
    float on = step(h, uParticles.y);
    vec2 at = (vec2(hash(cell * 1.3 + 2.1), hash(cell * 2.7 + 5.3)) - 0.5) * 0.6;
    if (kind < 1.5) at.x += 0.15 * sin(uTime * 1.3 + h * 6.2831);
    vec2 d = f - at;
    float size = (0.06 + 0.08 * hash(cell + 9.1)) * near;
    float a = 0.0;
    vec3 col = vec3(1.0);
    if (kind < 1.5) {
      a = 1.0 - smoothstep(size * 0.5, size, length(d));
    } else if (kind < 2.5) {
      a = (1.0 - smoothstep(0.012, 0.03, abs(d.x))) * (1.0 - smoothstep(size * 2.0, size * 3.5, abs(d.y))) * 0.6;
      col = vec3(0.8, 0.85, 0.95);
    } else if (kind < 3.5) {
      float tw = pow(max(sin(uTime * 3.0 + h * 40.0), 0.0), 6.0);
      float star = max(1.0 - smoothstep(0.0, 0.02, abs(d.x)), 1.0 - smoothstep(0.0, 0.02, abs(d.y))) * (1.0 - smoothstep(0.0, size * 1.6, length(d)));
      a = tw * max(star, 1.0 - smoothstep(0.0, size * 0.4, length(d)));
      col = vec3(1.0, 0.95, 0.8);
    } else if (kind < 4.5) {
      a = 1.0 - smoothstep(-0.05, 0.05, heart(d / (size * 1.4)));
      col = vec3(1.0, 0.25 + 0.2 * h, 0.45);
    } else if (kind < 5.5) {
      float s = abs(sin(uTime * 4.0 + h * 30.0));
      vec2 box = abs(d) / vec2(size * (0.3 + 0.7 * s), size * 0.6);
      a = 1.0 - smoothstep(0.85, 1.0, max(box.x, box.y));
      col = vec3(hash(cell + 1.0), hash(cell + 2.0), hash(cell + 3.0)) * 0.7 + 0.3;
    } else {
      a = (1.0 - smoothstep(size * 0.1, size * 0.3, length(d))) * 0.5;
      col = vec3(1.0, 0.95, 0.85);
    }
    c = mix(c, col, clamp(a * on, 0.0, 1.0) * uParticles.z);
  }
  return c;
}

// A camcorder's viewfinder: a red dot blinking top left, and the four corners.
vec3 rec(vec3 c, vec2 p) {
  vec2 q = vec2(p.x * uAspect, p.y);
  float blink = step(0.5, fract(uTime));
  float r = length(q - vec2(0.07, 0.93));
  c = mix(c, vec3(0.95, 0.1, 0.1), (1.0 - smoothstep(0.016, 0.022, r)) * blink);
  vec2 e = vec2(min(q.x, uAspect - q.x), min(p.y, 1.0 - p.y)) - 0.04;
  float bx = step(abs(e.x), 0.003) * step(-0.003, e.y) * step(e.y, 0.08);
  float by = step(abs(e.y), 0.003) * step(-0.003, e.x) * step(e.x, 0.08);
  return mix(c, vec3(1.0), max(bx, by) * 0.85);
}

vec3 lightAndTone(vec3 c, vec2 p) {
  c = tone(c);
  c = lights(c, p);
  if (uParticles.z > 0.001 && uParticles.x > 0.5) c = particles(c, p);
  if (uParticles.w > 0.5) c = rec(c, p);
  if (uTone.x > 0.001) c *= 1.0 - 0.85 * uTone.x * smoothstep(0.3, 0.75, length(p - 0.5));
  return c;
}
// ---- end of the second set ----

void main() {
  vec2 uv = (vTexSamplingCoord - 0.5) / uZoom + 0.5 + uOffset;
  uv = warp(uv);

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
  c = resample(c, uv);

  // On the size of it, not its sign: Dream drifts the hue by a sine, so the
  // negative half of every swing was skipped and the colour turned one way and
  // then sat flat. The other two producers (Rainbow, Trippy) only ever climb.
  if (abs(uHue) > 0.001) c = hueRotate(c, uHue);
  if (uMono > 0.001) c = mix(c, vec3(dot(c, LUMA)), uMono);
  if (uInvert > 0.001) c = mix(c, 1.0 - c, uInvert);
  c = lightAndTone(c, vTexSamplingCoord);
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
  if (uKeepAlpha > 0.5) gl_FragColor = vec4(clamp(c, 0.0, 1.0), centre.a);
  else gl_FragColor = vec4(clamp(c, 0.0, 1.0) * a, 1.0);
}
