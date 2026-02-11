#ifndef SKY_EGL2_RENDERER_YUV420P_H
#define SKY_EGL2_RENDERER_YUV420P_H

#include "skyrenderer.h"

class SkyEGL2RendererYUV420pImp : public SkyEGL2RendererImp {
public:
    constexpr static const char YUV420P_FRAGMENT_SHADER[] = GLES_STRING(
            precision highp float;
            varying   highp vec2 vv2_Texcoord;
            uniform         mat3 um3_ColorConversion;
            uniform   lowp  sampler2D us2_SamplerX;
            uniform   lowp  sampler2D us2_SamplerY;
            uniform   lowp  sampler2D us2_SamplerZ;

            void main()
            {
                mediump vec3 yuv;
                lowp    vec3 rgb;

                yuv.x = (texture2D(us2_SamplerX, vv2_Texcoord).r - (16.0 / 255.0));
                yuv.y = (texture2D(us2_SamplerY, vv2_Texcoord).r - 0.5);
                yuv.z = (texture2D(us2_SamplerZ, vv2_Texcoord).r - 0.5);
                rgb = um3_ColorConversion * yuv;
                gl_FragColor = vec4(rgb, 1);
            }
    );

    explicit SkyEGL2RendererYUV420pImp(AVPixelFormat format);
    ~SkyEGL2RendererYUV420pImp() override = default;

    // 重写基类虚函数
    const char* getFragmentShaderSource() override;
    void init() override;
    GLboolean use() override;
    GLboolean isValid() override;
    GLsizei getBufferWidth(AVFrame* avFrame) override;
    GLboolean uploadTexture(AVFrame* avFrame) override;
    void reset() override;
};

#endif // SKY_EGL2_RENDERER_YUV420P_H