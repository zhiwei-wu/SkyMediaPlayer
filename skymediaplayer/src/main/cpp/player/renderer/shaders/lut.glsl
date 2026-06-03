// GPUImage 512x512 lookup-table 应用（被各片段着色器 #include）
// 输入/输出：vec3 rgb（[0,1]）；依赖 binding=3 的 lutTexture 与 push_constant pc.lutEnabled
vec3 applyLut(vec3 rgb) {
    if (pc.lutEnabled > 0.001) {
        float blueColor = clamp(rgb.b, 0.0, 1.0) * 63.0;
        vec2 quad1;
        quad1.y = floor(floor(blueColor) / 8.0);
        quad1.x = floor(blueColor) - (quad1.y * 8.0);
        vec2 quad2;
        quad2.y = floor(ceil(blueColor) / 8.0);
        quad2.x = ceil(blueColor) - (quad2.y * 8.0);
        vec2 t1;
        t1.x = (quad1.x * 0.125) + 0.5 / 512.0 + ((0.125 - 1.0 / 512.0) * clamp(rgb.r, 0.0, 1.0));
        t1.y = (quad1.y * 0.125) + 0.5 / 512.0 + ((0.125 - 1.0 / 512.0) * clamp(rgb.g, 0.0, 1.0));
        vec2 t2;
        t2.x = (quad2.x * 0.125) + 0.5 / 512.0 + ((0.125 - 1.0 / 512.0) * clamp(rgb.r, 0.0, 1.0));
        t2.y = (quad2.y * 0.125) + 0.5 / 512.0 + ((0.125 - 1.0 / 512.0) * clamp(rgb.g, 0.0, 1.0));
        vec3 nc = mix(texture(lutTexture, t1).rgb, texture(lutTexture, t2).rgb, fract(blueColor));
        rgb = mix(rgb, nc, pc.lutEnabled);
    }
    return rgb;
}
