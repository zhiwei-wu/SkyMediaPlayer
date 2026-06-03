#ifndef SKY_EGL2_RENDERER_RGBA_H
#define SKY_EGL2_RENDERER_RGBA_H

#include "skyrenderer.h"

class SkyEGL2RendererRGBAImp : public SkyEGL2RendererImp {
public:
    constexpr static const char RGBA_FRAGMENT_SHADER[] = GLES_STRING(
            precision highp float;
            varying   highp vec2 vv2_Texcoord;
            uniform   lowp  sampler2D us2_SamplerRGBA;  // RGBA texture
            uniform         sampler2D us2_SamplerLUT;   // 512x512 GPUImage lookup
            uniform         float     u_LutEnabled;     // 0.0=off, else intensity

            void main()
            {
                // Direct RGBA texture sampling - no color conversion needed
                lowp vec3 rgb = texture2D(us2_SamplerRGBA, vv2_Texcoord).rgb;

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

    explicit SkyEGL2RendererRGBAImp(AVPixelFormat format);
    ~SkyEGL2RendererRGBAImp() override = default;

    // 重写基类虚函数
    const char* getFragmentShaderSource() override;
    void init() override;
    GLboolean use() override;
    GLboolean isValid() override;
    GLsizei getBufferWidth(AVFrame* avFrame) override;
    GLboolean uploadTexture(AVFrame* avFrame) override;
    void reset() override;

private:
    GLuint us2_sampler_rgba = 0;   // RGBA texture sampler
    GLuint rgba_texture = 0;       // RGBA texture
};

#endif // SKY_EGL2_RENDERER_RGBA_H