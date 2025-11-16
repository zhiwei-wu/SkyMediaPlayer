package imt.zw.skymediaplayer.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

class SurfaceRenderView(
    context: Context,
    attrs: AttributeSet? = null,
    defaultStyleAttr: Int = 0,
    defaultResAttr: Int = 0,
    private var _surfaceCallback: SurfaceHolder.Callback
)
    : SurfaceView(context, attrs, defaultStyleAttr, defaultResAttr) {

    companion object {
        private const val TAG = "SurfaceRenderView"
    }

    private var _surfaceHolder: SurfaceHolder ?= null
    private var _format: Int ?= 0
    private var _width: Int ?= 0
    private var _height: Int ?= 0

    // 视频尺寸相关属性
    private var _videoSize: VideoSizeCalculator.VideoSize? = null
    private var _scaleType: VideoSizeCalculator.ScaleType = VideoSizeCalculator.ScaleType.AR_ASPECT_FIT_CENTER

    // 视频尺寸计算器
    private val _sizeCalculator = VideoSizeCalculator()

    private inner class SurfaceCallback : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated()")
            _surfaceHolder = holder

            _surfaceCallback.surfaceCreated(holder)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged()")
            _surfaceHolder = holder
            _format = format
            _width = width
            _height = height

            _surfaceCallback.surfaceChanged(holder, format, width, height)
        }

        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            _surfaceHolder = null
            _format = 0
            _width = 0
            _height = 0

            _surfaceCallback.surfaceDestroyed(surfaceHolder)
        }
    }

    constructor(context: Context, surfaceCallback: SurfaceHolder.Callback) : this(
        context,
        null,
        0,
        0,
        surfaceCallback
    )

    init {
        initView()
    }

    fun getSurfaceHolder(): SurfaceHolder? {
        return _surfaceHolder
    }

    private fun initView() {
        holder.addCallback(SurfaceCallback())
        holder.setType(SurfaceHolder.SURFACE_TYPE_NORMAL)
    }

    /**
     * 设置视频尺寸和SAR
     */
    fun setVideoSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
        Log.i(TAG, "setVideoSize width:$width, height:$height, sarNum:$sarNum, sarDen:$sarDen")

        _videoSize = VideoSizeCalculator.VideoSize(
            width = width,
            height = height,
            sarNum = sarNum,
            sarDen = sarDen
        )

        Log.i(TAG, "Video aspect ratio: ${_videoSize?.getAspectRatio()}")

        // 触发布局重新计算
        requestLayout()
    }

    /**
     * 设置缩放模式
     */
    fun setScaleType(scaleType: VideoSizeCalculator.ScaleType) {
        if (_scaleType != scaleType) {
            _scaleType = scaleType
            Log.i(TAG, "Scale type changed to: $scaleType")
            requestLayout()
        }
    }

    /**
     * 重写 onMeasure 实现宽高比保持
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val videoSize = _videoSize
        if (videoSize == null || !videoSize.isValid()) {
            // 没有有效的视频尺寸信息时，使用默认测量
            Log.d(TAG, "No valid video size available, using default measure")
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        Log.d(TAG, "onMeasure container size: ${widthSize}x${heightSize}, scale type: $_scaleType")

        val containerSize = VideoSizeCalculator.ContainerSize(widthSize, heightSize)
        val calculatedSize = _sizeCalculator.calculateDisplaySize(
            videoSize = videoSize,
            containerSize = containerSize,
            scaleType = _scaleType
        )

        Log.d(TAG, "Final size: ${calculatedSize.width}x${calculatedSize.height}")
        setMeasuredDimension(calculatedSize.width, calculatedSize.height)
    }

    /**
     * 释放 SurfaceRenderView 资源
     */
    fun release() {
        Log.d(TAG, "Releasing SurfaceRenderView resources")

        // 清理 Surface 回调
        holder.removeCallback(SurfaceCallback())

        // 清理视频尺寸信息
        _videoSize = null

        // 重置状态
        _surfaceHolder = null
        _format = 0
        _width = 0
        _height = 0

        Log.d(TAG, "SurfaceRenderView resources released")
    }
}