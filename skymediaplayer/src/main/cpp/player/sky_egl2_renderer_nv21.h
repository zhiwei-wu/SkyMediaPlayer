#ifndef SKY_EGL2_RENDERER_NV21_H
#define SKY_EGL2_RENDERER_NV21_H

#include "skyrenderer.h"

class SkyEGL2RendererNV21Imp : public SkyEGL2RendererImp {
public:
    constexpr static const char NV21_FRAGMENT_SHADER[] = GLES_STRING(
            precision highp float;
            varying   highp vec2 vv2_Texcoord;
            uniform         mat3 um3_ColorConversion;
            uniform   lowp  sampler2D us2_SamplerY;  // Y plane
            uniform   lowp  sampler2D us2_SamplerUV; // VU interleaved plane (NV21)

            void main()
            {
                mediump vec3 yuv;
                lowp    vec3 rgb;

                // NV21: Y plane + interleaved VU plane (swapped compared to NV12)
                yuv.x = (texture2D(us2_SamplerY, vv2_Texcoord).r - (16.0 / 255.0));
                yuv.y = (texture2D(us2_SamplerUV, vv2_Texcoord).g - 0.5);  // U (swapped)
                yuv.z = (texture2D(us2_SamplerUV, vv2_Texcoord).r - 0.5);  // V (swapped)
                rgb = um3_ColorConversion * yuv;
                gl_FragColor = vec4(rgb, 1);
            }
    );

    explicit SkyEGL2RendererNV21Imp(AVPixelFormat format);
    ~SkyEGL2RendererNV21Imp() override = default;

    // 重写基类虚函数
    const char* getFragmentShaderSource() override;
    void init() override;
    GLboolean use() override;
    GLboolean isValid() override;
    GLsizei getBufferWidth(AVFrame* avFrame) override;
    GLboolean uploadTexture(AVFrame* avFrame) override;
    void reset() override;

private:
    GLuint us2_sampler_y = 0;   // Y plane sampler
    GLuint us2_sampler_uv = 0;  // VU plane sampler
    GLuint nv21_textures[2] = {0}; // Y and VU textures
};

#endif // SKY_EGL2_RENDERER_NV21_H