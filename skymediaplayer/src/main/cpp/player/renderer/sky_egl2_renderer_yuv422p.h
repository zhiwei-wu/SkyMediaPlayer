#ifndef SKY_EGL2_RENDERER_YUV422P_H
#define SKY_EGL2_RENDERER_YUV422P_H

#include "skyrenderer.h"

class SkyEGL2RendererYUV422pImp : public SkyEGL2RendererImp {
public:
    constexpr static const char YUV422P_FRAGMENT_SHADER[] =
            GLES_STRING(
            precision highp float;
            varying   highp vec2 vv2_Texcoord;
            uniform         mat3 um3_ColorConversion;
            uniform   lowp  sampler2D us2_SamplerX;     // Y plane
            uniform   lowp  sampler2D us2_SamplerY;     // U plane
            uniform   lowp  sampler2D us2_SamplerZ;     // V plane
            )
            SKY_ENHANCE_GLSL
            GLES_STRING(
            // YUV422P: Y plane + separate U/V planes with 4:2:2 subsampling
            vec3 sampleRGB(highp vec2 uv)
            {
                mediump vec3 yuv;
                yuv.x = (texture2D(us2_SamplerX, uv).r - (16.0 / 255.0));
                yuv.y = (texture2D(us2_SamplerY, uv).r - 0.5);
                yuv.z = (texture2D(us2_SamplerZ, uv).r - 0.5);
                return clamp(um3_ColorConversion * yuv, 0.0, 1.0);
            }

            void main()
            {
                gl_FragColor = vec4(applyEnhance(sampleRGB(vv2_Texcoord), vv2_Texcoord), 1.0);
            }
    );

    explicit SkyEGL2RendererYUV422pImp(AVPixelFormat format);
    ~SkyEGL2RendererYUV422pImp() override = default;

    // 重写基类虚函数
    const char* getFragmentShaderSource() override;
    void init() override;
    GLboolean use() override;
    GLboolean isValid() override;
    GLsizei getBufferWidth(AVFrame* avFrame) override;
    GLboolean uploadTexture(AVFrame* avFrame) override;
    void reset() override;
};

#endif // SKY_EGL2_RENDERER_YUV422P_H