#ifndef SKY_ENHANCE_GLSL_H
#define SKY_ENHANCE_GLSL_H

/**
 * 画质增强共享 GLSL 片段（GLES2 各格式 fragment shader 通过字符串字面量拼接复用）。
 *
 * 用法：FRAGMENT_SHADER = GLES_STRING(声明段) SKY_ENHANCE_GLSL GLES_STRING(格式段)
 *  - 声明段：precision / varying / 各格式采样器与色彩转换矩阵声明；
 *  - 格式段：实现 vec3 sampleRGB(highp vec2 uv)（纹理采样 + 转 RGB，输出 clamp 到 [0,1]），
 *    main 中调用 applyEnhance(sampleRGB(vv2_Texcoord), vv2_Texcoord)。
 *
 * 效果管线（单 pass）：deband -> CAS 锐化（增量叠加）-> LUT。
 *
 * 宏体约束（GLES_STRING 字符串化所致）：
 *  - 不能出现预处理指令；注释只能用块注释（行注释会吞掉续行拼接后的整行内容）；
 *  - 括号外不能有顶层逗号（如 "vec3 a, b;" 会被当作两个宏参数）。
 *
 * 依赖 GLES_STRING 宏，由 skyrenderer.h 在该宏定义之后包含本头文件。
 */
#define SKY_ENHANCE_GLSL GLES_STRING(                                              \
    uniform       sampler2D us2_SamplerLUT;   /* 512x512 GPUImage lookup */        \
    uniform       float     u_LutEnabled;     /* 0.0=off, else intensity */        \
    uniform       float     u_Sharpness;      /* CAS 锐化强度 0..1 */               \
    uniform       float     u_Deband;         /* 去色带强度 0..1 */                 \
    uniform highp vec2      u_TexelSize;      /* (1/视频宽, 1/视频高) */            \
                                                                                    \
    vec3 sampleRGB(highp vec2 uv);  /* 各格式 shader 实现 */                        \
                                                                                    \
    /* 无 sin 伪随机 hash（规避移动 GPU sin 大参数精度问题），输入像素坐标 */          \
    highp float hash12(highp vec2 p)                                               \
    {                                                                               \
        highp vec3 p3 = fract(vec3(p.x, p.y, p.x) * 0.1031);                        \
        p3 += dot(p3, vec3(p3.y + 33.33, p3.z + 33.33, p3.x + 33.33));              \
        return fract((p3.x + p3.y) * p3.z);                                         \
    }                                                                               \
                                                                                    \
    /* 去色带：旋转十字 4 taps（随机角度+距离 3..12 纹素）均值，阈值内向均值靠拢 */    \
    vec3 applyDeband(vec3 c0, highp vec2 uv)                                        \
    {                                                                               \
        highp vec2 pix = uv / u_TexelSize;                                          \
        highp float h1 = hash12(pix);                                               \
        highp float h2 = hash12(pix + vec2(37.0, 17.0));                            \
        highp float ang = 6.2831853 * h1;                                           \
        highp float dist = (0.25 + 0.75 * h2) * 12.0;                               \
        highp vec2 o1 = vec2(cos(ang), sin(ang)) * dist * u_TexelSize;              \
        highp vec2 o2 = vec2(-o1.y, o1.x);                                          \
        vec3 avg = (sampleRGB(uv + o1) + sampleRGB(uv - o1)                         \
                  + sampleRGB(uv + o2) + sampleRGB(uv - o2)) * 0.25;                \
        vec3 diff = avg - c0;                                                       \
        float maxDiff = max(abs(diff.r), max(abs(diff.g), abs(diff.b)));            \
        float thr = (1.5 + 2.5 * u_Deband) / 255.0;                                 \
        float w = (1.0 - smoothstep(0.5 * thr, thr, maxDiff)) * u_Deband;           \
        vec3 res = mix(c0, avg, w);                                                 \
        /* 静态抖动 +-1/255*强度，打散残余台阶 */                                    \
        highp float noise = hash12(pix + vec2(113.0, 71.0)) - 0.5;                  \
        return res + noise * (2.0 / 255.0) * u_Deband;                              \
    }                                                                               \
                                                                                    \
    /* CAS 锐化增量：十字 4+1 taps，邻域 min/max 自适应抑制过冲，固定 peak=-1/5 */    \
    vec3 casDelta(vec3 c0, highp vec2 uv)                                           \
    {                                                                               \
        vec3 a = sampleRGB(uv + vec2(0.0, -u_TexelSize.y));                         \
        vec3 b = sampleRGB(uv + vec2(-u_TexelSize.x, 0.0));                         \
        vec3 d = sampleRGB(uv + vec2(u_TexelSize.x, 0.0));                          \
        vec3 e = sampleRGB(uv + vec2(0.0, u_TexelSize.y));                          \
        vec3 mn = min(c0, min(min(a, b), min(d, e)));                               \
        vec3 mx = max(c0, max(max(a, b), max(d, e)));                               \
        vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 0.0001), 0.0, 1.0));      \
        vec3 w = amp * (-0.2);                                                      \
        vec3 sharpened = (c0 + (a + b + d + e) * w) / (1.0 + 4.0 * w);              \
        return (clamp(sharpened, 0.0, 1.0) - c0) * u_Sharpness;                     \
    }                                                                               \
                                                                                    \
    /* LUT 查表（GPUImage 512x512），原各格式 shader 内联逻辑收编于此 */              \
    vec3 applyLut(vec3 rgb)                                                         \
    {                                                                               \
        if (u_LutEnabled > 0.001) {                                                 \
            highp float blueColor = clamp(rgb.b, 0.0, 1.0) * 63.0;                  \
            highp vec2 quad1;                                                       \
            quad1.y = floor(floor(blueColor) / 8.0);                                \
            quad1.x = floor(blueColor) - (quad1.y * 8.0);                           \
            highp vec2 quad2;                                                       \
            quad2.y = floor(ceil(blueColor) / 8.0);                                 \
            quad2.x = ceil(blueColor) - (quad2.y * 8.0);                            \
            highp vec2 t1;                                                          \
            t1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.r, 0.0, 1.0)); \
            t1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.g, 0.0, 1.0)); \
            highp vec2 t2;                                                          \
            t2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.r, 0.0, 1.0)); \
            t2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.g, 0.0, 1.0)); \
            lowp vec3 nc = mix(texture2D(us2_SamplerLUT, t1).rgb,                   \
                               texture2D(us2_SamplerLUT, t2).rgb, fract(blueColor)); \
            rgb = mix(rgb, nc, u_LutEnabled);                                       \
        }                                                                           \
        return rgb;                                                                 \
    }                                                                               \
                                                                                    \
    /* 增强主管线：c0 为原始 sampleRGB(uv)，强度为 0 的效果整体跳过 */                \
    vec3 applyEnhance(vec3 c0, highp vec2 uv)                                       \
    {                                                                               \
        vec3 rgb = c0;                                                              \
        if (u_Deband > 0.001) {                                                     \
            rgb = applyDeband(c0, uv);                                              \
        }                                                                           \
        if (u_Sharpness > 0.001) {                                                  \
            rgb += casDelta(c0, uv);                                                \
        }                                                                           \
        rgb = clamp(rgb, 0.0, 1.0);                                                 \
        return applyLut(rgb);                                                       \
    }                                                                               \
)

#endif // SKY_ENHANCE_GLSL_H
