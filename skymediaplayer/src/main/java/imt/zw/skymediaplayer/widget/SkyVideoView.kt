package imt.zw.skymediaplayer.widget

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.widget.FrameLayout
import imt.zw.skymediaplayer.audio.AudioFocusManager
import imt.zw.skymediaplayer.player.IMediaPlayer
import imt.zw.skymediaplayer.player.SkyMediaPlayer
import imt.zw.skymediaplayer.utils.Utils
import imt.zw.skymediaplayer.widget.control.OnSubtitleSettingsChangeListener
import imt.zw.skymediaplayer.widget.control.PlayerControl
import imt.zw.skymediaplayer.widget.control.SkyPlayerOverlay
import imt.zw.skymediaplayer.widget.control.SubtitleSettings

const val TAG: String = "SkyVideoView"

class SkyVideoView(context: Context,
                   attrs: AttributeSet ?= null,
                   defStyleAttr: Int = 0)
    : FrameLayout(context, attrs, defStyleAttr), PlayerControl {

    private var _localVideoPath: String ?= null
    private var _videoUri: Uri? = null
    private var _mediaPlayer: IMediaPlayer?= null
    private var _surfaceRenderView: SurfaceRenderView?= null

    // 播放器交互覆盖层（包含字幕和播控）
    private var _playerOverlay: SkyPlayerOverlay? = null

    // 渲染后端配置
    private var _rendererBackend: Int = 0  // 默认 OpenGL ES

    // 解码模式配置
    private var _decoderMode: Int = 3  // 默认 AUTO（自动三级回退）

    // Whisper 字幕状态
    private var _isWhisperEnabled: Boolean = false
    private var _whisperModelPath: String? = null

    // 音频焦点管理器
    private var _audioFocusManager: AudioFocusManager? = null
    private var _wasPlayingBeforeFocusLoss = false

    // Seek 状态管理
    private var _isSeekInProgress = false
    private var _seekTargetPosition = 0
    private var _lastValidPosition = 0
    private val _seekHandler = Handler(Looper.getMainLooper())
    private var _pendingSeekRunnable: Runnable? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    init {
        initVideoView(context)
        initAudioFocus(context)
    }

    private fun initVideoView(context: Context) {
        // Layer 1: 视频渲染层
        _surfaceRenderView = SurfaceRenderView(context, object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                if (null != _mediaPlayer) {
                    bindSurfaceHolder()
                } else{
                    openVideo()
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "surfaceChanged format:$format, width:$width, height:$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
                _mediaPlayer?.setDisplay(null)
            }
        })

        val surfaceLp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        addView(_surfaceRenderView, surfaceLp)

        // Layer 2: 交互覆盖层（包含字幕和播控）
        initPlayerOverlay(context)
    }

    /**
     * 初始化播放器交互覆盖层
     */
    private fun initPlayerOverlay(context: Context) {
        _playerOverlay = SkyPlayerOverlay(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            
            // 设置播放器控制接口
            setPlayerControl(this@SkyVideoView)
        }
        addView(_playerOverlay)
    }

    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * 初始化音频焦点管理
     */
    private fun initAudioFocus(context: Context) {
        _audioFocusManager = AudioFocusManager(context)
        _audioFocusManager?.setOnAudioFocusChangeListener(object : AudioFocusManager.OnAudioFocusChangeListener {
            override fun onAudioFocusGain() {
                Log.d(TAG, "Audio focus gained")
                // 恢复播放
                if (_wasPlayingBeforeFocusLoss && _mediaPlayer != null) {
                    _mediaPlayer?.start()
                    _wasPlayingBeforeFocusLoss = false
                }
            }

            override fun onAudioFocusLoss() {
                Log.d(TAG, "Audio focus lost permanently")
                // 永久失去焦点，暂停播放
                if (isPlaying()) {
                    _wasPlayingBeforeFocusLoss = true
                    _mediaPlayer?.pause()
                }
            }

            override fun onAudioFocusLossTransient() {
                Log.d(TAG, "Audio focus lost temporarily")
                // 暂时失去焦点，暂停播放
                if (isPlaying()) {
                    _wasPlayingBeforeFocusLoss = true
                    _mediaPlayer?.pause()
                }
            }

            override fun onAudioFocusLossTransientCanDuck() {
                Log.d(TAG, "Audio focus lost temporarily, can duck")
                // 可以降低音量继续播放，这里选择暂停
                if (isPlaying()) {
                    _wasPlayingBeforeFocusLoss = true
                    _mediaPlayer?.pause()
                }
            }
        })
    }

    /**
     * 设置字幕设置变更监听器
     */
    fun setOnSubtitleSettingsChangeListener(listener: OnSubtitleSettingsChangeListener?) {
        _playerOverlay?.setOnSubtitleSettingsChangeListener(listener)
    }

    /**
     * 获取当前字幕设置
     */
    fun getSubtitleSettings(): SubtitleSettings {
        return _playerOverlay?.getSubtitleSettings() ?: SubtitleSettings.DEFAULT
    }

    /**
     * 设置字幕设置
     */
    fun setSubtitleSettings(settings: SubtitleSettings) {
        _playerOverlay?.setSubtitleSettings(settings)
    }

    /**
     * 显示播控栏
     */
    fun showControl() {
        _playerOverlay?.showControl()
    }

    /**
     * 隐藏播控栏
     */
    fun hideControl() {
        _playerOverlay?.hideControl()
    }

    /**
     * 设置渲染后端（必须在视频播放之前调用）
     * @param backendValue 渲染后端值（0=OpenGL ES, 1=Vulkan, 2=Metal）
     */
    fun setRendererBackend(backendValue: Int) {
        _rendererBackend = backendValue
        Log.i(TAG, "setRendererBackend: $backendValue")
    }

    /**
     * 设置解码模式（必须在视频播放之前调用）
     * @param modeValue 解码模式值（0=硬解直渲, 1=硬解Buffer, 2=软解, 3=自动）
     */
    fun setDecoderMode(modeValue: Int) {
        _decoderMode = modeValue
        Log.i(TAG, "setDecoderMode: $modeValue")
    }

    fun setVideoPath(path: String) {
        _localVideoPath = path
        _videoUri = null
        openVideo();
    }

    fun setVideoURI(uri: Uri) {
        // 尝试将 URI 转换为本地路径
        _localVideoPath = Utils.getRealPathFromURI(context, uri)

        // 如果转换失败，复制到临时文件
        if (_localVideoPath == null) {
            Log.d(TAG, "setVideoURI: $uri, cannot convert to path, copying to temp file")
            _localVideoPath = Utils.copyUriToTempFile(context, uri)
            if (_localVideoPath != null) {
                Log.d(TAG, "setVideoURI: copied to temp file: $_localVideoPath")
            } else {
                Log.e(TAG, "setVideoURI: failed to copy URI to temp file")
            }
        } else {
            Log.d(TAG, "setVideoURI: $uri, converted to path: $_localVideoPath")
        }

        _videoUri = null

        // 检查 Surface 是否已经准备好
        if (_surfaceRenderView?.getSurfaceHolder() != null) {
            Log.d(TAG, "setVideoURI: surface is ready, opening video immediately")
            openVideo()
        } else {
            Log.d(TAG, "setVideoURI: surface not ready, will open video when surface is created")
            // Surface 还没准备好，等待 surfaceCreated 回调
        }
    }

    /**
     * 设置视频缩放模式
     */
    fun setVideoScaleType(scaleType: VideoSizeCalculator.ScaleType) {
        _surfaceRenderView?.setScaleType(scaleType)
    }

    @Synchronized
    private fun openVideo() {
        if ((TextUtils.isEmpty(_localVideoPath) && _videoUri == null)
            or (null == _surfaceRenderView?.getSurfaceHolder())) {
            Log.e(TAG, "openVideo() video source is null or surfaceHolder is null");
            return
        }

        // 释放旧的 mediaplayer
        releaseMediaPlayer()

        _mediaPlayer = SkyMediaPlayer()
        (_mediaPlayer as? SkyMediaPlayer)?.setRendererBackend(_rendererBackend)
        (_mediaPlayer as? SkyMediaPlayer)?.setDecoderMode(_decoderMode)
        _mediaPlayer!!.setOnPreparedListener(_preparedListener)
        _mediaPlayer!!.setOnCompletionListener(_onCompletionListener)
        _mediaPlayer!!.setOnBufferingUpdateListener(_onBufferingUpdateListener)
        _mediaPlayer!!.setSeekCompleteListener(_onSeekCompleteListener)
        _mediaPlayer!!.setVideoSizeChangedListener(_onVideoSizeChangedListener)
        _mediaPlayer!!.setOnErrorListener(_onErrorListener)
        _mediaPlayer!!.setOnInfoListener(_onInfoListener)
        // 注意：不再注册 OnSubtitleListener，字幕显示由外部通过 PTS 同步机制控制

        // 根据数据源类型设置不同的数据源
        if (_videoUri != null) {
            _mediaPlayer!!.setDataSource(this.context, _videoUri!!)
        } else if (!TextUtils.isEmpty(_localVideoPath)) {
            _mediaPlayer!!.setDataSource(this.context, _localVideoPath!!)
        }

        bindSurfaceHolder()

        _mediaPlayer!!.prepareAsync()

        Log.i(TAG, "openVideo() video source: ${_videoUri ?: _localVideoPath}")
    }

    /**
     * 释放媒体播放器资源
     */
    private fun releaseMediaPlayer() {
        _mediaPlayer?.let { player ->
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing media player", e)
            }
        }
        Log.i(TAG, "Released media player")
        _mediaPlayer = null
    }

    fun bindSurfaceHolder() {
        _mediaPlayer?.setDisplay(null)
        if (null != _surfaceRenderView?.getSurfaceHolder()) {
            _mediaPlayer?.setDisplay(_surfaceRenderView!!.getSurfaceHolder())
        }
    }

    /**
     * MediaController.MediaPlayerControl
     * 播控 -> 播放器路径
     */
    override fun start() {
        Log.d(TAG, "SkyVideoView start() called")
        // 请求音频焦点
        val audioFocusResult = _audioFocusManager?.requestAudioFocus()
        Log.d(TAG, "Audio focus request result: $audioFocusResult")

        if (audioFocusResult == true) {
            _mediaPlayer?.start()
            Log.d(TAG, "Started playback with audio focus, isPlaying: ${_mediaPlayer?.isPlaying()}")
        } else {
            Log.w(TAG, "Failed to get audio focus, cannot start playback")
        }
    }

    override fun pause() {
        _mediaPlayer?.pause()
        Log.d(TAG, "Paused playback")
    }

    override fun getDuration(): Int {
        return (_mediaPlayer?.getDuration() ?: 0L).toInt()
    }

    override fun getCurrentPosition(): Int {
        // 如果正在 seek 过程中，返回目标位置而不是实际位置
        if (_isSeekInProgress) {
            Log.d(TAG, "getCurrentPosition (seeking): $_seekTargetPosition，formatStr=${Utils.formatTime(_seekTargetPosition.toLong())}")
            return _seekTargetPosition
        }

        val currentPos = (_mediaPlayer?.getCurrentPosition() ?: 0L).toInt()

        // 更新最后有效位置（用于异常情况的回退）
        if (currentPos > 0) {
            _lastValidPosition = currentPos
        }

        return currentPos
    }

    override fun seekTo(position: Int) {
        // 取消之前的延迟 seek
        _pendingSeekRunnable?.let { _seekHandler.removeCallbacks(it) }

        // 设置 seek 状态
        _isSeekInProgress = true
        _seekTargetPosition = position

        // 创建延迟 seek 任务（防止频繁调用）
        _pendingSeekRunnable = Runnable {
            Log.i(TAG, "seekTo (executing): $_seekTargetPosition，formatStr=${Utils.formatTime(_seekTargetPosition.toLong())}")
            _mediaPlayer?.seekTo(_seekTargetPosition.toLong())
            _pendingSeekRunnable = null
        }

        // 延迟 50ms 执行 seek，如果期间有新的 seek 请求会被取消重新安排
        _seekHandler.postDelayed(_pendingSeekRunnable!!, 50)
    }

    override fun isPlaying(): Boolean {
        return _mediaPlayer?.isPlaying() ?: false
    }

    override fun getBufferPercentage(): Int {
        return 0
    }
    // PlayerControl 实现-end

    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "Starting complete resource release")

        // 1. 停止所有待处理的 seek 操作
        _pendingSeekRunnable?.let {
            _seekHandler.removeCallbacks(it)
            _pendingSeekRunnable = null
        }

        // 2. 释放音频焦点
        _audioFocusManager?.abandonAudioFocus()
        _audioFocusManager = null

        // 3. 释放媒体播放器（这会触发底层 C++ 资源释放）
        releaseMediaPlayer()

        // 4. 释放 Surface 渲染资源
        _surfaceRenderView?.release()
        _surfaceRenderView = null

        // 5. 清理播控覆盖层
        _playerOverlay?.release()
        _playerOverlay = null

        Log.d(TAG, "Complete resource release finished")
    }

    /**
     * 播放器提供的各种 Listener 的实现
     * 播放器 -> 播控链路
     */
    private val _preparedListener: IMediaPlayer.OnPrepareListener = object : IMediaPlayer.OnPrepareListener {
        override fun onPrepared(mp: IMediaPlayer) {
            Log.i(TAG, "onPrepared")

            // 目前只支持异步 prepared，所以在这里自动开启播放
            start()

            // 显示播控栏
            post {
                _playerOverlay?.showControl()
            }
        }
    }

    private val _onCompletionListener: IMediaPlayer.OnCompletionListener = object : IMediaPlayer.OnCompletionListener {
        override fun onCompletion(mp: IMediaPlayer) {
            Log.i(TAG, "onCompletion")
            // 播放完成后释放音频焦点
            _audioFocusManager?.abandonAudioFocus()
        }
    }

    private val _onBufferingUpdateListener: IMediaPlayer.OnBufferingUpdateListener = object : IMediaPlayer.OnBufferingUpdateListener {
        override fun onBufferingUpdate(mp: IMediaPlayer, percent: Int) {
            Log.d(TAG, "onBufferingUpdate: $percent%")
        }
    }

    // 外部 Seek 完成监听器
    private var _externalSeekCompleteListener: IMediaPlayer.OnSeekCompleteListener? = null

    private val _onSeekCompleteListener: IMediaPlayer.OnSeekCompleteListener = object : IMediaPlayer.OnSeekCompleteListener {
        override fun onSeekComplete(mp: IMediaPlayer) {
            Log.d(TAG, "onSeekComplete")
            _isSeekInProgress = false

            // 如果 Whisper 已启用，清空字幕队列
            if (_isWhisperEnabled) {
                (mp as? SkyMediaPlayer)?.clearSubtitleQueue()
                Log.d(TAG, "onSeekComplete: cleared subtitle queue (Whisper enabled)")
            }

            // 通知外部监听器
            _externalSeekCompleteListener?.onSeekComplete(mp)
        }
    }

    private val _onVideoSizeChangedListener : IMediaPlayer.OnVideoSizeChangedListener = object : IMediaPlayer.OnVideoSizeChangedListener {
        override fun onVideoSizeChanged(
            mp: IMediaPlayer,
            width: Int,
            height: Int,
            sar_num: Int,
            sar_den: Int
        ) {
            Log.i(TAG, "onVideoSizeChanged width:$width, height:$height, sar_num:$sar_num, sar_den:$sar_den")

            // 将视频尺寸信息传递给 SurfaceRenderView
            _surfaceRenderView?.setVideoSize(width, height, sar_num, sar_den)
        }
    }

    private val _onErrorListener : IMediaPlayer.OnErrorListener = object : IMediaPlayer.OnErrorListener {
        override fun onError(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
            Log.e(TAG, "onError what:$what, extra:$extra")
            // 发生错误时释放音频焦点
            _audioFocusManager?.abandonAudioFocus()
            // 发生错误时重置 seek 状态
            _isSeekInProgress = false
            return false
        }
    }

    private val _onInfoListener : IMediaPlayer.OnInfoListener = object : IMediaPlayer.OnInfoListener {
        override fun onInfo(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
            Log.i(TAG, "onInfo what:$what, extra:$extra")
            return false
        }
    }

    // ============================================================================
    // Whisper AI 字幕相关方法
    // ============================================================================

    /**
     * 启用或禁用 Whisper AI 字幕
     * @param enabled 是否启用
     * @param modelPath 模型文件路径（启用时必须提供）
     * @param language 语言代码，如 "zh"、"en"，默认 "zh"
     * @param queueSeconds 推理间隔（秒），范围 3-20，默认 10
     * @return 0 成功，负值失败
     */
    fun setWhisperEnabled(
        enabled: Boolean,
        modelPath: String? = null,
        language: String = "zh",
        queueSeconds: Int = 10
    ): Int {
        _isWhisperEnabled = enabled
        _whisperModelPath = modelPath

        val player = _mediaPlayer as? SkyMediaPlayer
        if (player == null) {
            Log.e(TAG, "setWhisperEnabled: player is not SkyMediaPlayer")
            return -1
        }

        val result = player.setWhisperEnabled(enabled, modelPath, language, queueSeconds)

        Log.i(TAG, "setWhisperEnabled: enabled=$enabled, queueSeconds=$queueSeconds, result=$result")
        return result
    }

    /**
     * 检查 Whisper 是否已启用
     */
    fun isWhisperEnabled(): Boolean = _isWhisperEnabled

    /**
     * 设置 LUT 画质滤镜（GPU 渲染）
     * @param rgba 512x512 RGBA 字节（512*512*4）；传 null 关闭
     * @param intensity 强度 0..1
     * @return 0 成功，负值失败
     */
    fun setLut(rgba: ByteArray?, intensity: Float = 1.0f): Int {
        return (_mediaPlayer as? SkyMediaPlayer)?.setLut(rgba, intensity) ?: -1
    }

    /**
     * 设置字幕文本（供外部调用，如字幕回调）
     * @param text 字幕文本，传 null 或空字符串隐藏字幕
     */
    fun setSubtitleText(text: String?) {
        post {
            _playerOverlay?.setSubtitleText(text)
        }
    }

    /**
     * 隐藏字幕
     */
    fun hideSubtitle() {
        _playerOverlay?.hideSubtitle()
    }

    /**
     * 获取内部的 SkyMediaPlayer 实例
     * 用于高级控制
     */
    fun getMediaPlayer(): SkyMediaPlayer? {
        return _mediaPlayer as? SkyMediaPlayer
    }

    /**
     * 设置外部 Seek 完成监听器
     * 用于在 Seek 完成后执行额外操作（如清空字幕队列、显示加载提示等）
     * @param listener Seek 完成监听器
     */
    fun setOnSeekCompleteListener(listener: IMediaPlayer.OnSeekCompleteListener?) {
        _externalSeekCompleteListener = listener
    }

    /**
     * 获取播放器覆盖层
     */
    fun getPlayerOverlay(): SkyPlayerOverlay? {
        return _playerOverlay
    }

    /**
     * 配置画质面板的滤镜列表
     */
    fun setQualityFilterItems(items: List<imt.zw.skymediaplayer.widget.control.SkyQualityPanel.QualityFilterItem>) {
        _playerOverlay?.setQualityFilterItems(items)
    }

    /**
     * 设置画质面板当前选中滤镜
     */
    fun setSelectedQualityFilter(id: String?) {
        _playerOverlay?.setSelectedQualityFilter(id)
    }

    /**
     * 设置画质面板滤镜强度（0-100）
     */
    fun setQualityIntensity(percent: Int) {
        _playerOverlay?.setQualityIntensity(percent)
    }

    /**
     * 设置画质面板回调（选择滤镜 / 调节强度）
     */
    fun setOnQualityPanelListener(listener: imt.zw.skymediaplayer.widget.control.SkyQualityPanel.OnQualityPanelListener?) {
        _playerOverlay?.setOnQualityPanelListener(listener)
    }

    /**
     * 设置旋转按钮点击监听器
     */
    fun setOnRotateButtonClickListener(listener: android.view.View.OnClickListener?) {
        _playerOverlay?.setOnRotateButtonClickListener(listener)
    }

    /**
     * 设置调试信息按钮点击监听器
     */
    fun setOnDebugButtonClickListener(listener: android.view.View.OnClickListener?) {
        _playerOverlay?.setOnDebugButtonClickListener(listener)
    }

    /**
     * 获取当前渲染后端值
     */
    fun getRendererBackend(): Int = _rendererBackend

    /**
     * 获取当前解码模式值
     */
    fun getDecoderMode(): Int = _decoderMode

    /**
     * 获取实际生效的解码模式（经过回退策略后的结果）
     * 当用户选择"自动"时，返回实际使用的解码器类型
     * @return 实际使用的解码模式值（0=硬解直渲, 1=硬解Buffer, 2=软解）
     */
    fun getActiveDecoderMode(): Int {
        return (_mediaPlayer as? SkyMediaPlayer)?.getActiveDecoderMode() ?: _decoderMode
    }
}