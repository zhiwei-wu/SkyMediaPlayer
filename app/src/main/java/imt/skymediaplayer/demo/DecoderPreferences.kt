package imt.skymediaplayer.demo

import android.content.Context

/**
 * 解码模式设置持久化工具类
 * 使用 SharedPreferences 存储用户选择的解码模式，下次启动时自动恢复
 */
object DecoderPreferences {
    private const val PREF_NAME = "sky_player_settings"
    private const val KEY_DECODER_MODE = "decoder_mode"
    private const val DEFAULT_MODE = 1  // 硬解Buffer

    /**
     * 获取保存的解码模式值
     * @return 解码模式值（0=硬解直渲, 1=硬解Buffer, 2=软解, 3=自动）
     */
    fun getDecoderMode(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DECODER_MODE, DEFAULT_MODE)
    }

    /**
     * 保存解码模式选择
     * @param mode 解码模式值（0=硬解直渲, 1=硬解Buffer, 2=软解, 3=自动）
     */
    fun setDecoderMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DECODER_MODE, mode)
            .apply()
    }

    /**
     * 获取解码模式的显示名称
     * @param mode 解码模式值
     * @return 可读的模式名称
     */
    fun getModeDisplayName(mode: Int): String {
        return when (mode) {
            0 -> "硬解直渲"
            1 -> "硬解Buffer"
            2 -> "软解"
            3 -> "自动"
            else -> "自动"
        }
    }
}
