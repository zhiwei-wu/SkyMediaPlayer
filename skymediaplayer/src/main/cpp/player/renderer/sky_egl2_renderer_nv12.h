#ifndef SKY_EGL2_RENDERER_NV12_H
#define SKY_EGL2_RENDERER_NV12_H

#include "skyrenderer.h"

class SkyEGL2RendererNV12Imp : public SkyEGL2RendererImp {
public:
    constexpr static const char NV12_FRAGMENT_SHADER[] = GLES_STRING(
            precision highp float;
            varying   highp vec2 vv2_Texcoord;
            uniform         mat3 um3_ColorConversion;
            uniform   lowp  sampler2D us2_SamplerY;  // Y plane
            uniform   lowp  sampler2D us2_SamplerUV; // UV interleaved plane
            uniform         sampler2D us2_SamplerLUT;  // 512x512 GPUImage lookup
            uniform         float     u_LutEnabled;    // 0.0=off, else intensity

            void main()
            {
                mediump vec3 yuv;
                lowp    vec3 rgb;

                // NV12: Y plane + interleaved UV plane
                yuv.x = (texture2D(us2_SamplerY, vv2_Texcoord).r - (16.0 / 255.0));
                yuv.y = (texture2D(us2_SamplerUV, vv2_Texcoord).r - 0.5);  // U (luminance)
                yuv.z = (texture2D(us2_SamplerUV, vv2_Texcoord).a - 0.5);  // V (alpha)
                rgb = um3_ColorConversion * yuv;

                if (u_LutEnabled > 0.001) {
                    highp float blueColor = clamp(rgb.b, 0.0, 1.0) * 63.0;
                    highp vec2 quad1;
                    quad1.y = floor(floor(blueColor) / 8.0);
                    quad1.x = floor(blueColor) - (quad1.y * 8.0);
                    highp vec2 quad2;
                    quad2.y = floor(ceil(blueColor) / 8.0);
                    quad2.x = ceil(blueColor) - (quad2.y * 8.0);
                    highp vec2 t1;
                    t1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.r, 0.0, 1.0));
                    t1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.g, 0.0, 1.0));
                    highp vec2 t2;
                    t2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.r, 0.0, 1.0));
                    t2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * clamp(rgb.g, 0.0, 1.0));
                    lowp vec3 nc = mix(texture2D(us2_SamplerLUT, t1).rgb,
                                       texture2D(us2_SamplerLUT, t2).rgb, fract(blueColor));
                    rgb = mix(rgb, nc, u_LutEnabled);
                }
                gl_FragColor = vec4(rgb, 1.0);
            }
    );

    explicit SkyEGL2RendererNV12Imp(AVPixelFormat format);
    ~SkyEGL2RendererNV12Imp() override = default;

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
    GLuint us2_sampler_uv = 0;  // UV plane sampler
    GLuint nv12_textures[2] = {0}; // Y and UV textures
};

#endif // SKY_EGL2_RENDERER_NV12_H