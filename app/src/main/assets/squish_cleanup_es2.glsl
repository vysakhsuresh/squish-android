#version 100

// Remove flicker and Reduce noise on one clip (EDGE_PLAN F9): CleanUp.kt's
// arithmetic, which tools/jvm/CleanUpChecks.kt executes. uMode 1 is the
// brightness measure - each of the 16 x 16 cells drawn as the average of
// sixteen taps across it - and uMode 0 the picture: lifted or lowered by
// uGain, then mixed towards uHistory, the last frame this pass drew, by how
// still the pixel is. uRule is (quiet, moved, most kept), CleanUp's numbers.

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform sampler2D uHistory;
uniform float uMode;
uniform float uGain;
uniform float uDenoise;
uniform vec3 uRule;
// One pixel, in texture coordinates: the step to the neighbours.
uniform vec2 uTexel;

varying vec2 vTexSamplingCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
  vec2 uv = vTexSamplingCoord;
  if (uMode > 0.5) {
    vec3 s = vec3(0.0);
    for (int i = 0; i < 4; i++) {
      for (int j = 0; j < 4; j++) {
        s += texture2D(uTexSampler, clamp(uv + (vec2(float(i), float(j)) - 1.5) / 64.0, 0.0, 1.0)).rgb;
      }
    }
    gl_FragColor = vec4(s / 16.0, 1.0);
    return;
  }
  vec4 c = texture2D(uTexSampler, uv);
  vec3 cur = clamp(c.rgb * uGain, 0.0, 1.0);
  if (uDenoise > 0.001) {
    vec3 last = texture2D(uHistory, uv).rgb;
    // How still the 3 x 3 round the pixel is, not the pixel alone: judged
    // alone, grain is its own difference and heavy grain read as moving
    // (CleanUp.denoiseFrame says more; CleanUpChecks runs it).
    vec3 sc = vec3(0.0);
    vec3 sl = vec3(0.0);
    for (int i = -1; i <= 1; i++) {
      for (int j = -1; j <= 1; j++) {
        vec2 o = uv + vec2(float(i), float(j)) * uTexel;
        sc += clamp(texture2D(uTexSampler, o).rgb * uGain, 0.0, 1.0);
        sl += texture2D(uHistory, o).rgb;
      }
    }
    // Each channel weighed apart: by luma alone, red moving over a teal as
    // bright read as still and left a trail (seen on the moto).
    float diff = dot(abs(sc - sl), LUMA) / 9.0;
    float t = clamp((diff - uRule.x) / (uRule.y - uRule.x), 0.0, 1.0);
    float still = 1.0 - t * t * (3.0 - 2.0 * t);
    cur = mix(cur, last, uRule.z * uDenoise * still);
  }
  gl_FragColor = vec4(cur, c.a);
}
