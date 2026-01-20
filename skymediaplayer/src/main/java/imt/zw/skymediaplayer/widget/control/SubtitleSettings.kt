package imt.zw.skymediaplayer.widget.control

/**
 * AI 字幕设置数据类
 */
data class SubtitleSettings(
    /**
     * 是否启用 AI 字幕
     */
    val enabled: Boolean = false,
    
    /**
     * 推理设备
     */
    val inferenceDevice: InferenceDevice = InferenceDevice.CPU,
    
    /**
     * 翻译目标语言
     */
    val targetLanguage: TargetLanguage = TargetLanguage.ORIGINAL,
    
    /**
     * 推理间隔（秒）
     * Whisper 每次处理的音频时长，范围 3-20 秒，默认 10 秒
     */
    val processingInterval: Int = 10,
    
    /**
     * 是否启用调试模式
     * 开启后字幕会显示时间信息：[字幕时间 | 播放时间 | 延迟]
     */
    val debugMode: Boolean = false
) {
    companion object {
        /**
         * 默认设置
         */
        val DEFAULT = SubtitleSettings()
        
        /**
         * 处理间隔最小值（秒）
         */
        const val MIN_PROCESSING_INTERVAL = 3
        
        /**
         * 处理间隔最大值（秒）
         */
        const val MAX_PROCESSING_INTERVAL = 20
        
        /**
         * 处理间隔默认值（秒）
         */
        const val DEFAULT_PROCESSING_INTERVAL = 10
    }
}

/**
 * 推理设备枚举
 */
enum class InferenceDevice(val displayName: String, val code: String) {
    CPU("CPU (默认)", "cpu"),
    GPU("GPU (更快)", "gpu");
    
    companion object {
        fun fromCode(code: String): InferenceDevice {
            return entries.find { it.code == code } ?: CPU
        }
    }
}

/**
 * 识别语言枚举
 * 注意：这是告诉 Whisper 视频中说的是什么语言，不是翻译目标语言
 * 例如：视频是中文对话，选择"中文"；视频是英文对话，选择"英文"
 */
enum class TargetLanguage(val displayName: String, val code: String) {
    ORIGINAL("自动检测", ""),
    CHINESE("中文", "zh"),
    ENGLISH("英文", "en"),
    JAPANESE("日文", "ja"),
    KOREAN("韩文", "ko");
    
    companion object {
        fun fromCode(code: String): TargetLanguage {
            return entries.find { it.code == code } ?: ORIGINAL
        }
    }
}
