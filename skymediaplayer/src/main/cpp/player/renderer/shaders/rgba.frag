#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D rgbaTexture;
layout(binding = 3) uniform sampler2D lutTexture;
layout(push_constant) uniform PushConstants { float lutEnabled; } pc;

#include "lut.glsl"

void main() {
    vec3 rgb = texture(rgbaTexture, vTexCoord).rgb;
    fragColor = vec4(applyLut(rgb), 1.0);
}
