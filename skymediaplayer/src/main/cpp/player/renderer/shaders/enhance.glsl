// 画质增强共享管线（被各片段着色器 #include，与 GLES2 侧 sky_enhance_glsl.h 算法一致）
// 依赖：各 .frag 实现 vec3 sampleRGB(vec2 uv)（采样 + 转 RGB，[0,1]）；
//       push_constant pc.sharpness / pc.deband（各 0..1，0=关闭）。
// 效果顺序：deband -> CAS 锐化（增量叠加）-> LUT。

vec3 sampleRGB(vec2 uv);

#include "lut.glsl"

// 无 sin 伪随机 hash，输入像素坐标
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, vec3(p3.y + 33.33, p3.z + 33.33, p3.x + 33.33));
    return fract((p3.x + p3.y) * p3.z);
}

// 去色带：旋转十字 4 taps（随机角度+距离 3..12 纹素）均值，阈值内向均值靠拢
vec3 applyDeband(vec3 c0, vec2 uv, vec2 texel) {
    vec2 pix = uv / texel;
    float h1 = hash12(pix);
    float h2 = hash12(pix + vec2(37.0, 17.0));
    float ang = 6.2831853 * h1;
    float dist = (0.25 + 0.75 * h2) * 12.0;
    vec2 o1 = vec2(cos(ang), sin(ang)) * dist * texel;
    vec2 o2 = vec2(-o1.y, o1.x);
    vec3 avg = (sampleRGB(uv + o1) + sampleRGB(uv - o1)
              + sampleRGB(uv + o2) + sampleRGB(uv - o2)) * 0.25;
    vec3 diff = avg - c0;
    float maxDiff = max(abs(diff.r), max(abs(diff.g), abs(diff.b)));
    float thr = (1.5 + 2.5 * pc.deband) / 255.0;
    float w = (1.0 - smoothstep(0.5 * thr, thr, maxDiff)) * pc.deband;
    vec3 res = mix(c0, avg, w);
    // 静态抖动 +-1/255*强度，打散残余台阶
    float noise = hash12(pix + vec2(113.0, 71.0)) - 0.5;
    return res + noise * (2.0 / 255.0) * pc.deband;
}

// CAS 锐化增量：十字 4+1 taps，邻域 min/max 自适应抑制过冲，固定 peak=-1/5
vec3 casDelta(vec3 c0, vec2 uv, vec2 texel) {
    vec3 a = sampleRGB(uv + vec2(0.0, -texel.y));
    vec3 b = sampleRGB(uv + vec2(-texel.x, 0.0));
    vec3 d = sampleRGB(uv + vec2(texel.x, 0.0));
    vec3 e = sampleRGB(uv + vec2(0.0, texel.y));
    vec3 mn = min(c0, min(min(a, b), min(d, e)));
    vec3 mx = max(c0, max(max(a, b), max(d, e)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
    vec3 w = amp * (-0.2);
    vec3 sharpened = (c0 + (a + b + d + e) * w) / (1.0 + 4.0 * w);
    return (clamp(sharpened, 0.0, 1.0) - c0) * pc.sharpness;
}

// 增强主管线：c0 为原始 sampleRGB(uv)，强度为 0 的效果整体跳过
// 对比模式：uv.x < pc.split 返回原图（split<=0 关闭对比，全画面增强）
vec3 applyEnhance(vec3 c0, vec2 uv, vec2 texel) {
    if (uv.x < pc.split) { return c0; }
    vec3 rgb = c0;
    if (pc.deband > 0.001) {
        rgb = applyDeband(c0, uv, texel);
    }
    if (pc.sharpness > 0.001) {
        rgb += casDelta(c0, uv, texel);
    }
    rgb = clamp(rgb, 0.0, 1.0);
    return applyLut(rgb);
}
