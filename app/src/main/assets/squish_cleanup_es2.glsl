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
    float diff = dot(abs(cur - last), LUMA);
    float t = clamp((diff - uRule.x) / (uRule.y - uRule.x), 0.0, 1.0);
    float still = 1.0 - t * t * (3.0 - 2.0 * t);
    cur = mix(cur, last, uRule.z * uDenoise * still);
  }
  gl_FragColor = vec4(cur, c.a);
}
