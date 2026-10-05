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

half4 main(float2 coord) {
  // Turned over into the file's axis at once, so every line below reads the
  // same way as its twin in squish_fx_es2.glsl; texel() turns it back.
  float2 layer = (coord - uOrigin) / uSize;
  float2 base = float2(layer.x, 1.0 - layer.y);
  float2 uv = (base - 0.5) / uZoom + 0.5 + uOffset;

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

  // On the size of it, not its sign: Dream drifts the hue by a sine, so the
  // negative half of every swing was skipped and the colour turned one way and
  // then sat flat. The other two producers (Rainbow, Trippy) only ever climb.
  if (abs(uHue) > 0.001) c = hueRotate(c, uHue);
  if (uMono > 0.001) c = mix(c, float3(dot(c, LUMA)), uMono);
  if (uInvert > 0.001) c = mix(c, 1.0 - c, uInvert);
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
