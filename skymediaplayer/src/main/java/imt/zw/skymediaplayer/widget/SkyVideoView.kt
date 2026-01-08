package imt.zw.skymediaplayer.widget

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.widget.MediaController
import imt.zw.skymediaplayer.audio.AudioFocusManager
import imt.zw.skymediaplayer.player.IMediaPlayer
import imt.zw.skymediaplayer.player.SkyMediaPlayer
import imt.zw.skymediaplayer.utils.Utils

const val TAG: String = "SkyVideoView"

class SkyVideoView(context: Context,
                   attrs: AttributeSet ?= null,
                   defStyleAttr: Int = 0)
    : FrameLayout(context, attrs, defStyleAttr), MediaController.MediaPlayerControl {

    private var _localVideoPath: String ?= null
    private var _videoUri: Uri? = null
    private var _mediaController: MediaController ?= null
    private var _mediaPlayer: IMediaPlayer?= null
    private var _surfaceRenderView: SurfaceRenderView?= null

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
                // Surface被销毁时，需要通知底层释放渲染资源
                // 但不要释放整个MediaPlayer，因为我们需要保持播放状态
                _mediaPlayer?.setDisplay(null)
            }
        })

        val lp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        addView(_surfaceRenderView, lp)
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

    fun setMediaController(mediaController: MediaController) {
        _mediaController = mediaController;
    }

    private fun attachMediaController() {
        if (null != _mediaPlayer && null != _mediaController) {
            _mediaController!!.setMediaPlayer(this);
            val anchorView = (this.parent as? View) ?: this
            _mediaController!!.setAnchorView(anchorView)
        }

        _mediaController?.show(5000)
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

    /**
     * 添加触摸事件处理，点击时切换播控显示/隐藏
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        /**
         * 系统播控有bug:
         * show(timeOut)的时候抛了fakeOut定时任务，下次在Show()的时候，会在剩余倒计时到的时候移除播控
         */
        if (_mediaController != null && event.action == MotionEvent.ACTION_DOWN) {
            if (_mediaController!!.isShowing) {
                _mediaController!!.hide()
                Log.d(TAG, "MediaController hidden by touch")
            } else {
                _mediaController!!.show(5000)
                Log.d(TAG, "MediaController shown by touch for 5 seconds")
            }
            return true
        }
        return super.onTouchEvent(event)
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
        _mediaPlayer!!.setOnPreparedListener(_preparedListener)
        _mediaPlayer!!.setOnCompletionListener(_onCompletionListener)
        _mediaPlayer!!.setOnBufferingUpdateListener(_onBufferingUpdateListener)
        _mediaPlayer!!.setSeekCompleteListener(_onSeekCompleteListener)
        _mediaPlayer!!.setVideoSizeChangedListener(_onVideoSizeChangedListener)
        _mediaPlayer!!.setOnErrorListener(_onErrorListener)
        _mediaPlayer!!.setOnInfoListener(_onInfoListener)

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
        // 返回缓冲百分比，如果没有缓冲信息则返回0
        return 0
    }

    override fun canPause(): Boolean {
        return _mediaPlayer != null
    }

    override fun canSeekBackward(): Boolean {
        return _mediaPlayer != null
    }

    override fun canSeekForward(): Boolean {
        return _mediaPlayer != null
    }

    override fun getAudioSessionId(): Int {
        return _mediaPlayer?.getAudioSessionId() ?: 0
    }
    // MediaController.MediaPlayerControl 实现-end

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

        // 5. 清理播控
        _mediaController?.hide()
        _mediaController = null

        Log.d(TAG, "Complete resource release finished")
    }

    /**
     * 播放器提供的各种 Listener 的实现
     * 播放器 -> 播控链路
     */
    private val _preparedListener: IMediaPlayer.OnPrepareListener = object : IMediaPlayer.OnPrepareListener {
        override fun onPrepared(mp: IMediaPlayer) {
            Log.i(TAG, "onPrepared")

            // 在视频准备好后再附加播控
            attachMediaController()

            // 目前只支持异步 prepared，所以在这里自动开启播放
            start()

            // 确保 MediaController 状态同步
            post {
                _mediaController?.let { controller ->
                    // 强制刷新 MediaController 的状态
                    if (controller.isShowing) {
                        controller.hide()
                        controller.show(5000)
                    }
                }
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

    private val _onSeekCompleteListener: IMediaPlayer.OnSeekCompleteListener = object : IMediaPlayer.OnSeekCompleteListener {
        override fun onSeekComplete(mp: IMediaPlayer) {
            Log.d(TAG, "onSeekComplete")
            _isSeekInProgress = false
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
}