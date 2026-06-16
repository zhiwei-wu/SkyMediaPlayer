#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D rgbaTexture;
layout(binding = 3) uniform sampler2D lutTexture;
layout(push_constant) uniform PushConstants {
    float lutEnabled;
    float sharpness;
    float deband;
} pc;

#include "enhance.glsl"

vec3 sampleRGB(vec2 uv) {
    return texture(rgbaTexture, uv).rgb;
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(rgbaTexture, 0));
    fragColor = vec4(applyEnhance(sampleRGB(vTexCoord), vTexCoord, texel), 1.0);
}
