package com.squish.app.editor

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The effects library over the whole composed picture, as the file draws it.
 *
 * The export runs FxEffect once on the finished frame (VideoProcessor
 * .compositionEffects): after every shot's crop, turn and fit, over every
 * picture-in-picture, the padded backdrop and the gaps. The preview ran it in
 * each base player's chain instead - before the view turned and cropped the
 * picture, and on nothing else - so a Shake left a PiP still, an Invert over a
 * gap showed black where the file is white, a rotated edit shook the wrong way
 * and a crop measured the zoom and the split on the wrong frame.
 *
 * From Android 13 the same pass runs here, on the layer that holds everything
 * the file composites, as a runtime shader: squish_fx_es2.glsl in AGSL, with
 * the same parameters worked out the same way (FxParams.at, on the edit's own
 * clock) and measured on the frame the file keeps ([kept]). Below 13 there is
 * no runtime shader, and the base chains keep carrying the library as before
 * (PreviewRules.fxOnCanvas).
 */
fun Modifier.canvasFx(effects: List<TimedEffect>, timeMs: Long, kept: PreviewBox.Frame): Modifier {
    if (effects.isEmpty() || !CanvasFx.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val params = FxParams.at(effects, timeMs)
    if (params.isIdentity) return this
    return graphicsLayer {
        renderEffect = CanvasFxShader.effect(
            params,
            originX = kept.left * size.width,
            originY = kept.top * size.height,
            width = (kept.right - kept.left) * size.width,
            height = (kept.bottom - kept.top) * size.height
        )
    }
}

/**
 * The same pass over a whole small picture, its moment read in the layer
 * rather than in composition: the Effects sheet's tiles, which animate every
 * frame and would otherwise recompose every frame. Nothing below Android 13,
 * where the tile draws its own approximation instead.
 */
fun Modifier.canvasFxTile(effects: List<TimedEffect>, timeMs: () -> Long): Modifier {
    if (effects.isEmpty() || !CanvasFx.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return graphicsLayer {
        val params = FxParams.at(effects, timeMs())
        renderEffect = if (params.isIdentity) null else CanvasFxShader.effect(params, 0f, 0f, size.width, size.height)
    }
}

/** The same pass over a small picture from numbers made each frame: a warp transition's tile. */
fun Modifier.canvasFxParams(params: () -> FxParams?): Modifier {
    if (!CanvasFx.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return graphicsLayer {
        val p = params()
        renderEffect = if (p == null || p.isIdentity) null else CanvasFxShader.effect(p, 0f, 0f, size.width, size.height)
    }
}

object CanvasFx {
    /**
     * Whether the library is drawn over the composed canvas: Android 13 or
     * later (PreviewRules.fxOnCanvas), and the shader compiled on this phone.
     * Asked once. When it is not, the base players' chains carry the library
     * as they did before, so an effect is never lost from the preview.
     */
    val enabled: Boolean by lazy {
        PreviewRules.fxOnCanvas(Build.VERSION.SDK_INT) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && CanvasFxShader.compiles()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object CanvasFxShader {

    // One shader, its uniforms set per frame; the effect made from it copies them.
    private val shader by lazy { RuntimeShader(AGSL) }

    fun compiles(): Boolean = runCatching { shader }.isSuccess

    fun effect(p: FxParams, originX: Float, originY: Float, width: Float, height: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (width <= 0f || height <= 0f) return null
        val s = shader
        s.setFloatUniform("uOrigin", originX, originY)
        s.setFloatUniform("uSize", width, height)
        s.setFloatUniform("uOffset", p.offsetX, p.offsetY)
        s.setFloatUniform("uZoom", p.zoom.coerceAtLeast(0.1f))
        s.setFloatUniform("uSplit", p.split)
        s.setFloatUniform("uGlitch", p.glitch)
        s.setFloatUniform("uFlash", p.flash)
        s.setFloatUniform("uMono", p.mono)
        s.setFloatUniform("uInvert", p.invert)
        s.setFloatUniform("uScan", p.scan)
        s.setFloatUniform("uNoise", p.noise)
        s.setFloatUniform("uBlur", p.blur)
        s.setFloatUniform("uHue", p.hue)
        s.setFloatUniform("uTime", p.timeSec)
        s.setFloatUniform("uLensA", p.lensA.toArray())
        s.setFloatUniform("uLensB", p.lensB.toArray())
        s.setFloatUniform("uLight", p.light.toArray())
        s.setFloatUniform("uTone", p.tone.toArray())
        s.setFloatUniform("uParticles", p.particles.toArray())
        s.setFloatUniform("uGlitch2", p.glitch2.toArray())
        s.setFloatUniform("uAspect", width / height)
        return RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }

    /**
     * squish_fx_es2.glsl, stage for stage. Coordinates are the kept frame's
     * own 0..1 (base), as the file's are its output frame's; the picture is
     * read through [content] in the layer's pixels, clamped to that frame as
     * the file's texture clamps to its edge. Opaque out, as the file writes.
     */
    private const val AGSL = """
uniform shader content;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float2 uOffset;
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
uniform float4 uLensA;
uniform float4 uLensB;
uniform float4 uLight;
uniform float4 uTone;
uniform float4 uParticles;
uniform float4 uGlitch2;
uniform float uAspect;

const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

float hash(float2 p) {
  float3 p3 = fract(float3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// uv is in the FILE's space: 0..1 with y running UP, as a texture's does.
// The layer underneath runs y DOWN, so the lookup turns it back over. The two
// copies of this shader - this one and squish_fx_es2.glsl - used to measure y
// on opposite axes, so uOffset.y moved the picture the other way on screen and
// the glitch bands came out mirrored against the file.
float3 texel(float2 uv) {
  float2 flipped = float2(clamp(uv.x, 0.0, 1.0), 1.0 - clamp(uv.y, 0.0, 1.0));
  float2 px = uOrigin + flipped * uSize;
  return float3(content.eval(px).rgb);
}

float3 pick(float2 uv) {
  return texel(uv);
}

float3 sampleAt(float2 uv) {
  if (uBlur <= 0.0001) return texel(uv);
  float3 sum = float3(0.0);
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      sum += texel(uv + float2(float(i), float(j)) * uBlur);
    }
  }
  return sum / 9.0;
}

float3 hueRotate(float3 c, float a) {
  float s = sin(a);
  float k = cos(a);
  float3x3 m = float3x3(
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

float2 turnAbout(float2 uv, float a) {
  float2 d = uv - 0.5;
  d.x *= uAspect;
  float s = sin(a);
  float k = cos(a);
  d = float2(d.x * k - d.y * s, d.x * s + d.y * k);
  d.x /= uAspect;
  return d + 0.5;
}

// Where each pixel reads from: turn, lens, fold, ripple, mirror, tiles,
// pixels, then the block and tear glitches.
float2 warp(float2 uv) {
  if (abs(uLensB.y) > 0.0001) uv = turnAbout(uv, uLensB.y);
  if (uLensA.x > 0.001) {
    float2 d = uv - 0.5;
    d.x *= uAspect;
    float r2 = dot(d, d);
    d *= 1.0 - uLensA.x * (0.45 - 1.1 * r2);
    d.x /= uAspect;
    uv = d + 0.5;
  }
  if (uLensA.y > 2.5) {
    float2 d = uv - 0.5;
    d.x *= uAspect;
    float r = length(d);
    float seg = 6.2831853 / uLensA.y;
    float a = mod(atan(d.y, d.x + 0.000001) + uTime * 0.2, seg);
    a = abs(a - seg * 0.5);
    d = float2(cos(a), sin(a)) * r;
    d.x /= uAspect;
    uv = d + 0.5;
  }
  if (uLensA.z > 0.001) {
    float2 d = uv - 0.5;
    d.x *= uAspect;
    float r = length(d);
    float w = sin(r * 48.0 - uTime * 7.0) * 0.014 * uLensA.z;
    float2 n = d / max(r, 0.0001);
    uv += float2(n.x / uAspect, n.y) * w;
  }
  if (uLensB.x > 0.5 && uLensB.x < 1.5) uv.x = 0.5 - abs(uv.x - 0.5);
  if (uLensB.x > 1.5) uv.y = 0.5 + abs(uv.y - 0.5);
  if (uLensA.w > 1.5) uv = fract(uv * uLensA.w);
  if (uLensB.w > 0.0001) {
    float2 cell = float2(uLensB.w / uAspect, uLensB.w);
    uv = (floor(uv / cell) + 0.5) * cell;
  }
  if (uGlitch2.x > 0.001) {
    float roll = floor(uTime * 10.0);
    float2 block = floor(uv * float2(6.0, 10.0));
    if (hash(block + roll * 7.31) > 1.0 - 0.35 * uGlitch2.x) {
      uv += (float2(hash(block * 1.7 + roll), hash(block * 2.3 - roll)) - 0.5) * 0.16 * uGlitch2.x;
    }
  }
  if (uGlitch2.y > 0.001) {
    float at = fract(uTime * 0.23 + 0.5 * hash(float2(floor(uTime * 0.7), 3.0)));
    float h = 0.03 + 0.05 * uGlitch2.y;
    float inside = 1.0 - smoothstep(0.0, h, abs(uv.y - at));
    uv.x += inside * (0.05 + 0.04 * sin(uv.y * 160.0 + uTime * 30.0)) * uGlitch2.y;
  }
  return uv;
}

// The stages that read the picture more than once: a zoom blur towards the
// middle, and the channels pulled apart along a wave.
float3 resample(float3 c, float2 uv) {
  if (uLensB.z > 0.001) {
    float3 sum = float3(0.0);
    for (int i = 0; i < 8; i++) {
      sum += pick(mix(uv, float2(0.5), float(i) / 7.0 * 0.18 * uLensB.z));
    }
    c = sum / 8.0;
  }
  if (uGlitch2.z > 0.001) {
    float o = sin(uv.y * 22.0 + uTime * 6.0) * 0.012 * uGlitch2.z;
    c.r = pick(uv + float2(o, 0.0)).r;
    c.b = pick(uv - float2(o, 0.0)).b;
  }
  return c;
}

float3 screen(float3 c, float3 l) {
  return 1.0 - (1.0 - c) * (1.0 - clamp(l, 0.0, 1.0));
}

// Inside where it is below nothing: (x^2 + y^2 - 1)^3 - x^2 y^3, lobes up.
float heart(float2 q) {
  float a = q.x * q.x + q.y * q.y - 1.0;
  return a * a * a - q.x * q.x * q.y * q.y * q.y;
}

float3 tone(float3 c) {
  if (uTone.y > 0.001) {
    float l = dot(c, LUMA);
    float3 t = c;
    if (uTone.z < 0.5) t = float3(l * 1.07 + 0.05, l * 0.9 + 0.03, l * 0.68);
    else if (uTone.z < 1.5) t = mix(float3(0.12, 0.05, 0.32), float3(1.0, 0.55, 0.45), l);
    else if (uTone.z < 2.5) t = c * float3(1.1, 1.0, 0.82) + float3(0.04, 0.015, 0.0);
    else t = c * float3(0.86, 0.98, 1.12) + float3(0.0, 0.01, 0.03);
    c = mix(c, clamp(t, 0.0, 1.0), uTone.y);
  }
  if (uTone.w > 1.5) c = floor(c * (uTone.w - 1.0) + 0.5) / (uTone.w - 1.0);
  return c;
}

float3 lights(float3 c, float2 p) {
  float2 q = float2(p.x * uAspect, p.y);
  if (uLight.x > 0.001) {
    float2 at = float2((0.2 + 0.6 * (0.5 + 0.5 * sin(uTime * 0.35))) * uAspect, 0.95);
    float g = exp(-dot(q - at, q - at) * 3.0);
    float2 at2 = float2((0.8 - 0.5 * (0.5 + 0.5 * sin(uTime * 0.27 + 1.3))) * uAspect, 0.05);
    float g2 = exp(-dot(q - at2, q - at2) * 5.0);
    c = screen(c, (float3(1.0, 0.45, 0.12) * g + float3(1.0, 0.2, 0.35) * g2 * 0.7) * uLight.x);
  }
  if (uLight.y > 0.001) {
    float edge = min(min(p.x, 1.0 - p.x), min(p.y, 1.0 - p.y));
    float n = hash(floor(p * 24.0) + floor(uTime * 8.0)) * 0.08;
    float b = 1.0 - smoothstep(0.0, 0.25 * uLight.y + n, edge);
    c = screen(c, mix(float3(1.0, 0.35, 0.05), float3(1.0, 0.95, 0.8), b * b) * b * uLight.y * 1.2);
  }
  if (uLight.z > 0.001) {
    float3 add = float3(0.0);
    for (int i = 0; i < 8; i++) {
      float fi = float(i);
      float2 at = float2(hash(float2(fi, 1.0)) * uAspect + 0.05 * sin(uTime * 0.3 + fi), hash(float2(fi, 2.0)) + 0.04 * cos(uTime * 0.25 + fi * 1.7));
      float r = 0.06 + 0.07 * hash(float2(fi, 3.0));
      float disc = 1.0 - smoothstep(r * 0.85, r, length(q - at));
      add += disc * mix(float3(1.0, 0.75, 0.4), float3(0.6, 0.8, 1.0), hash(float2(fi, 4.0))) * 0.35;
    }
    c = screen(c, add * uLight.z);
  }
  if (uLight.w > 0.001) {
    float2 src = float2((0.75 + 0.05 * sin(uTime * 0.4)) * uAspect, 0.82);
    float2 mid = float2(0.5 * uAspect, 0.5);
    float2 d = q - src;
    float glow = exp(-dot(d, d) * 40.0) + 0.25 * exp(-dot(d, d) * 6.0);
    float streak = exp(-abs(d.y) * 160.0) * exp(-abs(d.x) * 2.5);
    float3 l = float3(1.0, 0.9, 0.75) * glow + float3(0.6, 0.75, 1.0) * streak * 0.8;
    for (int i = 1; i < 4; i++) {
      float2 ghost = mix(src, mid, 1.0 + float(i) * 0.45);
      float gr = 0.03 + 0.025 * float(i);
      float ring = 1.0 - smoothstep(gr * 0.7, gr, length(q - ghost));
      l += ring * float3(0.3, 0.5, 0.35) * 0.35;
    }
    c = screen(c, l * uLight.w);
  }
  return c;
}

// Snow 1, rain 2, sparkles 3, hearts 4, confetti 5, dust 6: three layers of a
// grid, a particle or none in each cell, the far layers smaller and slower.
float3 particles(float3 c, float2 p) {
  float kind = uParticles.x;
  float2 q = float2(p.x * uAspect, p.y);
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
    float2 g = q * scale + float2(0.0, uTime * drift * near * scale) + fl * 17.0;
    float2 cell = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash(cell + fl * 31.0);
    float on = step(h, uParticles.y);
    float2 at = (float2(hash(cell * 1.3 + 2.1), hash(cell * 2.7 + 5.3)) - 0.5) * 0.6;
    if (kind < 1.5) at.x += 0.15 * sin(uTime * 1.3 + h * 6.2831);
    float2 d = f - at;
    float size = (0.06 + 0.08 * hash(cell + 9.1)) * near;
    float a = 0.0;
    float3 col = float3(1.0);
    if (kind < 1.5) {
      a = 1.0 - smoothstep(size * 0.5, size, length(d));
    } else if (kind < 2.5) {
      a = (1.0 - smoothstep(0.012, 0.03, abs(d.x))) * (1.0 - smoothstep(size * 2.0, size * 3.5, abs(d.y))) * 0.6;
      col = float3(0.8, 0.85, 0.95);
    } else if (kind < 3.5) {
      float tw = pow(max(sin(uTime * 3.0 + h * 40.0), 0.0), 6.0);
      float star = max(1.0 - smoothstep(0.0, 0.02, abs(d.x)), 1.0 - smoothstep(0.0, 0.02, abs(d.y))) * (1.0 - smoothstep(0.0, size * 1.6, length(d)));
      a = tw * max(star, 1.0 - smoothstep(0.0, size * 0.4, length(d)));
      col = float3(1.0, 0.95, 0.8);
    } else if (kind < 4.5) {
      a = 1.0 - smoothstep(-0.05, 0.05, heart(d / (size * 1.4)));
      col = float3(1.0, 0.25 + 0.2 * h, 0.45);
    } else if (kind < 5.5) {
      float s = abs(sin(uTime * 4.0 + h * 30.0));
      float2 box = abs(d) / float2(size * (0.3 + 0.7 * s), size * 0.6);
      a = 1.0 - smoothstep(0.85, 1.0, max(box.x, box.y));
      col = float3(hash(cell + 1.0), hash(cell + 2.0), hash(cell + 3.0)) * 0.7 + 0.3;
    } else {
      a = (1.0 - smoothstep(size * 0.1, size * 0.3, length(d))) * 0.5;
      col = float3(1.0, 0.95, 0.85);
    }
    c = mix(c, col, clamp(a * on, 0.0, 1.0) * uParticles.z);
  }
  return c;
}

// A camcorder's viewfinder: a red dot blinking top left, and the four corners.
float3 rec(float3 c, float2 p) {
  float2 q = float2(p.x * uAspect, p.y);
  float blink = step(0.5, fract(uTime));
  float r = length(q - float2(0.07, 0.93));
  c = mix(c, float3(0.95, 0.1, 0.1), (1.0 - smoothstep(0.016, 0.022, r)) * blink);
  float2 e = float2(min(q.x, uAspect - q.x), min(p.y, 1.0 - p.y)) - 0.04;
  float bx = step(abs(e.x), 0.003) * step(-0.003, e.y) * step(e.y, 0.08);
  float by = step(abs(e.y), 0.003) * step(-0.003, e.x) * step(e.x, 0.08);
  return mix(c, float3(1.0), max(bx, by) * 0.85);
}

float3 lightAndTone(float3 c, float2 p) {
  c = tone(c);
  c = lights(c, p);
  if (uParticles.z > 0.001 && uParticles.x > 0.5) c = particles(c, p);
  if (uParticles.w > 0.5) c = rec(c, p);
  if (uTone.x > 0.001) c *= 1.0 - 0.85 * uTone.x * smoothstep(0.3, 0.75, length(p - 0.5));
  return c;
}
// ---- end of the second set ----

half4 main(float2 coord) {
  // Turned over into the file's axis at once, so every line below reads the
  // same way as its twin in squish_fx_es2.glsl; texel() turns it back.
  float2 layer = (coord - uOrigin) / uSize;
  float2 base = float2(layer.x, 1.0 - layer.y);
  float2 uv = (base - 0.5) / uZoom + 0.5 + uOffset;
  uv = warp(uv);

  if (uGlitch > 0.001) {
    float band = floor(uv.y * 18.0);
    float roll = floor(uTime * 12.0);
    float r = hash(float2(band, roll));
    if (r > 0.72) uv.x += (hash(float2(roll, band)) - 0.5) * 0.12 * uGlitch;
  }

  uv = clamp(uv, 0.0, 1.0);

  float3 c = float3(0.0);
  if (uSplit > 0.0001) {
    c.r = sampleAt(clamp(uv + float2(uSplit, 0.0), 0.0, 1.0)).r;
    c.g = sampleAt(uv).g;
    c.b = sampleAt(clamp(uv - float2(uSplit, 0.0), 0.0, 1.0)).b;
  } else {
    c = sampleAt(uv);
  }
  c = resample(c, uv);

  // On the size of it, not its sign: Dream drifts the hue by a sine, so the
  // negative half of every swing was skipped and the colour turned one way and
  // then sat flat. The other two producers (Rainbow, Trippy) only ever climb.
  if (abs(uHue) > 0.001) c = hueRotate(c, uHue);
  if (uMono > 0.001) c = mix(c, float3(dot(c, LUMA)), uMono);
  if (uInvert > 0.001) c = mix(c, 1.0 - c, uInvert);
  c = lightAndTone(c, base);
  if (uScan > 0.001) {
    float line = 0.5 + 0.5 * sin(base.y * 900.0);
    c *= 1.0 - uScan * 0.22 * line;
  }
  if (uNoise > 0.001) {
    c += (hash(base * 1000.0 + uTime * 60.0) - 0.5) * uNoise;
  }
  if (uFlash > 0.001) c = mix(c, float3(1.0), clamp(uFlash, 0.0, 1.0));

  return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""
}
