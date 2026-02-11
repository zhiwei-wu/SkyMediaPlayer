package imt.skymediaplayer.demo

import android.content.Context

/**
 * 渲染后端设置持久化工具类
 * 使用 SharedPreferences 存储用户选择的渲染后端，下次启动时自动恢复
 */
object RendererPreferences {
    private const val PREF_NAME = "sky_player_settings"
    private const val KEY_RENDERER_BACKEND = "renderer_backend"
    private const val DEFAULT_BACKEND = 0  // OpenGL ES

    /**
     * 获取保存的渲染后端值
     * @return 渲染后端值（0=OpenGL ES, 1=Vulkan, 2=Metal）
     */
    fun getRendererBackend(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RENDERER_BACKEND, DEFAULT_BACKEND)
    }

    /**
     * 保存渲染后端选择
     * @param backend 渲染后端值（0=OpenGL ES, 1=Vulkan, 2=Metal）
     */
    fun setRendererBackend(context: Context, backend: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RENDERER_BACKEND, backend)
            .apply()
    }

    /**
     * 获取渲染后端的显示名称
     * @param backend 渲染后端值
     * @return 可读的后端名称
     */
    fun getBackendDisplayName(backend: Int): String {
        return when (backend) {
            0 -> "OpenGL ES"
            1 -> "Vulkan"
            2 -> "Metal"
            else -> "OpenGL ES"
        }
    }
}
