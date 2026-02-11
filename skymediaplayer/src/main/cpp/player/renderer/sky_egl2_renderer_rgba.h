#ifndef SKY_EGL2_RENDERER_RGBA_H
#define SKY_EGL2_RENDERER_RGBA_H

#include "skyrenderer.h"

class SkyEGL2RendererRGBAImp : public SkyEGL2RendererImp {
public:
    constexpr static const char RGBA_FRAGMENT_SHADER[] = GLES_STRING(
            precision highp float;
            varying   highp vec2 vv2_Texcoord;
            uniform   lowp  sampler2D us2_SamplerRGBA;  // RGBA texture

            void main()
            {
                // Direct RGBA texture sampling - no color conversion needed
                gl_FragColor = texture2D(us2_SamplerRGBA, vv2_Texcoord);
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