#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D yTexture;
layout(binding = 1) uniform sampler2D uTexture;
layout(binding = 2) uniform sampler2D vTexture;
layout(binding = 3) uniform sampler2D lutTexture;
layout(push_constant) uniform PushConstants { float lutEnabled; } pc;

#include "lut.glsl"

void main() {
    float y = (texture(yTexture, vTexCoord).r - 0.0627451) * 1.164384;
    float u = texture(uTexture, vTexCoord).r - 0.5;
    float v = texture(vTexture, vTexCoord).r - 0.5;

    vec3 rgb = clamp(vec3(y + 1.596027 * v,
                          y - 0.391762 * u - 0.812968 * v,
                          y + 2.017232 * u), 0.0, 1.0);
    fragColor = vec4(applyLut(rgb), 1.0);
}
