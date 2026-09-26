#version 100

// Background removal. uMask holds the person (1) against the background (0) for
// this frame, at low resolution; bilinear sampling and a soft threshold give an
// edge that follows hair and shoulders without stair-steps.

precision mediump float;

uniform sampler2D uTexSampler;
uniform sampler2D uMask;
// 0 = blur, 1 = colour, 2 = transparent
uniform int uFill;
uniform vec3 uColour;
uniform vec2 uTexel;

varying vec2 vTexSamplingCoord;

vec3 blurred(vec2 uv) {
  vec3 sum = vec3(0.0);
  float total = 0.0;
  for (int i = -3; i <= 3; i++) {
    for (int j = -3; j <= 3; j++) {
      vec2 o = vec2(float(i), float(j)) * uTexel * 4.0;
      sum += texture2D(uTexSampler, clamp(uv + o, 0.0, 1.0)).rgb;
      total += 1.0;
    }
  }
  return sum / total;
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  if (uFill == 3) {
    gl_FragColor = src;
    return;
  }
  // The mask is stored top-down; the frame's texture is bottom-up.
  float person = texture2D(uMask, vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y)).r;
  float keep = smoothstep(0.35, 0.65, person);

  if (uFill == 2) {
    gl_FragColor = vec4(src.rgb, src.a * keep);
  } else if (uFill == 1) {
    gl_FragColor = vec4(mix(uColour, src.rgb, keep), src.a);
  } else {
    // Only the pixels that show background pay for the blur.
    vec3 back = keep < 0.999 ? blurred(vTexSamplingCoord) : src.rgb;
    gl_FragColor = vec4(mix(back, src.rgb, keep), src.a);
  }
}
