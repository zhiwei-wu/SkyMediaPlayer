package imt.zw.skymediaplayer.widget

import android.util.Log

/**
 * 视频尺寸计算器
 * 负责根据视频原始尺寸、SAR和容器尺寸计算最终显示尺寸
 */
class VideoSizeCalculator {
    
    companion object {
        private const val TAG = "VideoSizeCalculator"
    }

    // 缩放模式枚举
    enum class ScaleType {
        AR_ASPECT_FIT_CENTER,     // 保持宽高比，完整显示视频（可能有黑边）
        AR_ASPECT_CENTER_CROP,    // 保持宽高比，填满屏幕（可能裁剪）
        AR_ASPECT_FILL_SCREEN     // 拉伸填满屏幕（会变形）
    }

    // 视频尺寸信息
    data class VideoSize(
        val width: Int,
        val height: Int,
        val sarNum: Int,
        val sarDen: Int
    ) {
        /**
         * 计算实际宽高比（考虑SAR）
         */
        fun getAspectRatio(): Float {
            val sarRatio = if (sarDen != 0) sarNum.toFloat() / sarDen.toFloat() else 1f
            return if (height != 0) (width.toFloat() / height.toFloat()) * sarRatio else 0f
        }
        
        fun isValid(): Boolean = width > 0 && height > 0
    }

    // 容器尺寸信息
    data class ContainerSize(
        val width: Int,
        val height: Int
    ) {
        fun getAspectRatio(): Float = if (height != 0) width.toFloat() / height.toFloat() else 0f
        fun isValid(): Boolean = width > 0 && height > 0
    }

    // 计算结果
    data class CalculatedSize(
        val width: Int,
        val height: Int
    )

    /**
     * 计算视频在容器中的显示尺寸
     * 
     * @param videoSize 视频原始尺寸信息
     * @param containerSize 容器尺寸信息
     * @param scaleType 缩放模式
     * @return 计算后的显示尺寸
     */
    fun calculateDisplaySize(
        videoSize: VideoSize,
        containerSize: ContainerSize,
        scaleType: ScaleType
    ): CalculatedSize {
        
        Log.d(TAG, "calculateDisplaySize - video: ${videoSize.width}x${videoSize.height}, " +
                "SAR: ${videoSize.sarNum}/${videoSize.sarDen}, " +
                "container: ${containerSize.width}x${containerSize.height}, " +
                "scaleType: $scaleType")

        // 检查输入参数有效性
        if (!videoSize.isValid() || !containerSize.isValid()) {
            Log.w(TAG, "Invalid size parameters, using container size")
            return CalculatedSize(containerSize.width, containerSize.height)
        }

        val videoAspectRatio = videoSize.getAspectRatio()
        if (videoAspectRatio <= 0f) {
            Log.w(TAG, "Invalid video aspect ratio, using container size")
            return CalculatedSize(containerSize.width, containerSize.height)
        }

        Log.d(TAG, "Video aspect ratio: $videoAspectRatio")

        val result = when (scaleType) {
            ScaleType.AR_ASPECT_FIT_CENTER -> calculateFitCenter(videoAspectRatio, containerSize)
            ScaleType.AR_ASPECT_CENTER_CROP -> calculateCenterCrop(videoAspectRatio, containerSize)
            ScaleType.AR_ASPECT_FILL_SCREEN -> CalculatedSize(containerSize.width, containerSize.height)
        }

        Log.d(TAG, "Calculated size: ${result.width}x${result.height}")
        return result
    }

    /**
     * 计算 AR_ASPECT_FIT_CENTER 模式的尺寸
     * 保持宽高比，完整显示视频（可能有黑边）
     */
    private fun calculateFitCenter(videoAspectRatio: Float, containerSize: ContainerSize): CalculatedSize {
        val containerAspectRatio = containerSize.getAspectRatio()
        
        return if (videoAspectRatio > containerAspectRatio) {
            // 视频更宽，以容器宽度为准
            CalculatedSize(
                width = containerSize.width,
                height = (containerSize.width / videoAspectRatio).toInt()
            )
        } else {
            // 视频更高，以容器高度为准
            CalculatedSize(
                width = (containerSize.height * videoAspectRatio).toInt(),
                height = containerSize.height
            )
        }
    }

    /**
     * 计算 AR_ASPECT_CENTER_CROP 模式的尺寸
     * 保持宽高比，填满屏幕（可能裁剪）
     */
    private fun calculateCenterCrop(videoAspectRatio: Float, containerSize: ContainerSize): CalculatedSize {
        val containerAspectRatio = containerSize.getAspectRatio()
        
        return if (videoAspectRatio > containerAspectRatio) {
            // 视频更宽，以容器高度为准
            CalculatedSize(
                width = (containerSize.height * videoAspectRatio).toInt(),
                height = containerSize.height
            )
        } else {
            // 视频更高，以容器宽度为准
            CalculatedSize(
                width = containerSize.width,
                height = (containerSize.width / videoAspectRatio).toInt()
            )
        }
    }
}