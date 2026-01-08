package imt.zw.skymediaplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.lang.ref.WeakReference
import androidx.annotation.Keep
import imt.zw.skymediaplayer.utils.Utils

@Keep
class SkyMediaPlayer() : IMediaPlayer {

    // 事件类型常量定义
    companion object {
        private const val TAG = "SkyMediaPlayer"

        // 播放器事件
        private const val MEDIA_NOP = 0
        private const val MEDIA_PREPARED = 1
        private const val MEDIA_PLAYBACK_COMPLETE = 2
        private const val MEDIA_BUFFERING_UPDATE = 3
        private const val MEDIA_SEEK_COMPLETE = 4
        private const val MEDIA_SET_VIDEO_SIZE = 5
        private const val MEDIA_TIMED_TEXT = 99
        private const val MEDIA_ERROR = 100
        private const val MEDIA_INFO = 200
        private const val MEDIA_SET_VIDEO_SAR = 10001

        init {
            try {
                // 按依赖顺序加载库：先加载依赖库，再加载主库
                Log.d(TAG, "Loading SDL3 library...")
                System.loadLibrary("SDL3")
                Log.d(TAG, "SDL3 library loaded successfully")

                Log.d(TAG, "Loading skyffmpeg library...")
                System.loadLibrary("skyffmpeg")
                Log.d(TAG, "skyffmpeg library loaded successfully")

                Log.d(TAG, "Loading skymediaplayer library...")
                System.loadLibrary("skymediaplayer")
                Log.d(TAG, "skymediaplayer library loaded successfully")

                Log.i(TAG, "All native libraries loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native libraries", e)
                throw RuntimeException("Failed to load native libraries: ${e.message}", e)
            }
        }

        // 供native层调用的静态事件发送方法
        @Keep
        @JvmStatic
        private fun postEventFromNative(
            player: SkyMediaPlayer?,
            what: Int,
            arg1: Int,
            arg2: Int,
            obj: Any?
        ) {
            Log.i(TAG, "postEventFromNative what:$what arg1:$arg1 arg2:$arg2 obj:$obj")
            // 对播放器实例进行合法性校验
            if (player == null) {
                return
            }

            player.handleEventFromNative(what, arg1, arg2, obj)
        }
    }

    private var _videoWidth: Int = 0
    private var _videoHeight: Int = 0
    private var _videoSarNum: Int = 0
    private var _videoSarDen: Int = 0

    // 处理从native层发送的事件
    private fun handleEventFromNative(what: Int, arg1: Int, arg2: Int, obj: Any?) {
        val msg = Message.obtain()
        msg.what = what
        msg.arg1 = arg1
        msg.arg2 = arg2
        msg.obj = obj
        mEventHandler.sendMessage(msg)
    }

    // MediaEventHandler静态内部类
    private class MediaEventHandler(player: SkyMediaPlayer) : Handler(Looper.getMainLooper()) {
        private val mWeakPlayer: WeakReference<SkyMediaPlayer> = WeakReference(player)

        override fun handleMessage(msg: Message) {
            val player = mWeakPlayer.get() ?: return


            when (msg.what) {
                MEDIA_NOP -> {
                    // TODO: 处理MEDIA_NOP事件
                }
                MEDIA_PREPARED -> {
                    Log.i(TAG, "handleEventFromNative MEDIA_PREPARED")
                    player._onPreparedListener?.onPrepared(player)
                }
                MEDIA_PLAYBACK_COMPLETE -> {
                    Log.i(TAG, "handleEventFromNative MEDIA_PLAYBACK_COMPLETE")
                    player._onCompletionListener?.onCompletion(player)
                }
                MEDIA_BUFFERING_UPDATE -> {
                    // 处理缓冲更新事件 (方案A)
                    // arg1: 缓冲百分比 (0-100)
                    // arg2: 缓冲时长 (毫秒)
                    Log.d(TAG, "handleEventFromNative MEDIA_BUFFERING_UPDATE percent=${msg.arg1}% duration=${msg.arg2}ms")
                    player._onBufferingUpdateListener?.onBufferingUpdate(player, msg.arg1)
                }
                MEDIA_SEEK_COMPLETE -> {
                    Log.i(TAG, "handleEventFromNative MEDIA_SEEK_COMPLETE")
                    player._onSeekCompleteListener?.onSeekComplete(player)
                }
                MEDIA_SET_VIDEO_SIZE -> {
                    Log.i(TAG, "handleEventFromNative MEDIA_SET_VIDEO_SIZE")
                    player._videoWidth = msg.arg1
                    player._videoHeight = msg.arg2
                    player._onVideoSizeChangedListener?.onVideoSizeChanged(
                        player, player._videoWidth, player._videoHeight, player._videoSarNum, player._videoSarDen)
                }
                MEDIA_SET_VIDEO_SAR -> {
                    Log.i(TAG, "handleEventFromNative MEDIA_SET_VIDEO_SAR")
                    player._videoSarNum = msg.arg1
                    player._videoSarDen = msg.arg2
                    player._onVideoSizeChangedListener?.onVideoSizeChanged(
                        player, player._videoWidth, player._videoHeight, player._videoSarNum, player._videoSarDen)
                }
                MEDIA_TIMED_TEXT -> {
                    // TODO: 处理MEDIA_TIMED_TEXT事件
                }
                MEDIA_ERROR -> {
                    Log.e(TAG, "handleEventFromNative MEDIA_ERROR arg1:${msg.arg1} arg2:${msg.arg2}")
                    player._onErrorListener?.onError(player, msg.arg1, msg.arg2)
                }
                MEDIA_INFO -> {
                    // TODO: 处理MEDIA_INFO事件
                }
                else -> {
                    // TODO: 处理未知事件
                }
            }
        }
    }

    private var _onPreparedListener: IMediaPlayer.OnPrepareListener ?= null
    private var _onCompletionListener: IMediaPlayer.OnCompletionListener ?= null
    private var _onBufferingUpdateListener: IMediaPlayer.OnBufferingUpdateListener ?= null
    private var _onSeekCompleteListener: IMediaPlayer.OnSeekCompleteListener ?= null
    private var _onVideoSizeChangedListener: IMediaPlayer.OnVideoSizeChangedListener ?= null
    private var _onErrorListener: IMediaPlayer.OnErrorListener ?= null
    private var _onInfoListener: IMediaPlayer.OnInfoListener ?= null

    private var _surfaceHolder:SurfaceHolder ?= null
    private var _nativeMediaPlayer: Long = 0

    // 事件处理器实例
    private val mEventHandler: MediaEventHandler

    @Keep
    private external fun _native_setup()
    @Keep
    private external fun _setDataSource(path: String)
    @Keep
    private external fun _prepare()
    @Keep
    private external fun _prepareAsync()
    @Keep
    private external fun _setVideoSurface(surface: Surface)
    @Keep
    private external fun _start()
    @Keep
    private external fun _pause()
    @Keep
    private external fun _seekTo(pos: Long)
    @Keep
    private external fun _release()
    @Keep
    private external fun _getCurrentPosition(): Long
    @Keep
    private external fun _getDuration(): Long
    @Keep
    private external fun _isPlaying(): Boolean
    // private external fun _reset()
    // private external fun _setVolume(leftVolume: Float, rightVolume: Float)
    // private external fun _getAudioSessionId(): Int

    init {
        // 实例化事件处理器
        mEventHandler = MediaEventHandler(this)
        _native_setup()
    }

    override fun setDisplay(sh: SurfaceHolder ?) {
        _surfaceHolder = sh;
        val surface = sh?.surface
        if (null != surface) {
            _setVideoSurface(surface)
        }
    }

    override fun setDataSource(context: Context, localVideoPath: String) {
        _setDataSource(localVideoPath)
    }

    override fun setDataSource(context: Context, uri: Uri) {
        try {
            // 使用ContentResolver打开Uri并获取文件描述符
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // 对于Android 13+，我们需要使用文件描述符
                // 但由于当前native层只支持路径，我们尝试获取真实路径
                val cursor = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val columnIndex = it.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                        if (columnIndex >= 0) {
                            val path = it.getString(columnIndex)
                            if (!path.isNullOrEmpty()) {
                                _setDataSource(path)
                                return
                            }
                        }
                    }
                }

                // 如果无法获取路径，使用Uri的toString作为fallback
                // 注意：这可能不适用于所有情况，但对于content://media/external/file/类型的Uri可能有效
                _setDataSource(uri.toString())
            } ?: throw IllegalArgumentException("Cannot open Uri: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set data source from Uri: $uri", e)
            throw e
        }
    }

    override fun prepareAsync() {
        _prepareAsync()
    }

    override fun prepare() {
        _prepare()
    }

    override fun start() {
        _start()
    }

    override fun stop() {
        // TODO: 实现 JNI 方法后替换为 _stop()
    }

    override fun pause() {
        _pause()
    }

    override fun seekTo(milliSec: Long) {
        Log.i(TAG, "seekTo: $milliSec，formatStr=${Utils.formatTime(milliSec)}")
        _seekTo(milliSec)
    }

    override fun getCurrentPosition(): Long {
        val curPos = _getCurrentPosition()
        Log.i(TAG, "getCurrentPosition: $curPos，formatStr=${Utils.formatTime(curPos)}")
        return curPos
    }

    override fun getDuration(): Long {
        return _getDuration()
    }

    override fun release() {
        Log.d(TAG, "Starting SkyMediaPlayer release")

        // 1. 停止事件处理器
        mEventHandler.removeCallbacksAndMessages(null)

        // 2. 清理监听器引用
        _onPreparedListener = null
        _onCompletionListener = null
        _onBufferingUpdateListener = null
        _onSeekCompleteListener = null
        _onVideoSizeChangedListener = null
        _onErrorListener = null
        _onInfoListener = null

        // 3. 清理 Surface 引用
        _surfaceHolder = null

        // 4. 调用 native 释放方法
        if (_nativeMediaPlayer != 0L) {
            _release()
            _nativeMediaPlayer = 0L
        }

        Log.d(TAG, "SkyMediaPlayer release completed")
    }

    override fun reset() {
        // TODO: 实现 JNI 方法后替换为 _reset()
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        // TODO: 实现 JNI 方法后替换为 _setVolume(leftVolume, rightVolume)
        Log.d(TAG, "setVolume: left=$leftVolume, right=$rightVolume")
    }

    override fun isPlaying(): Boolean {
        return _isPlaying()
    }

    override fun getAudioSessionId(): Int {
        // TODO: 实现 JNI 方法后替换为 _getAudioSessionId()
        return 0 // 返回默认音频会话ID
    }

    override fun setSurface(surface: Surface) {
        _setVideoSurface(surface)
    }

    override fun setOnPreparedListener(listener: IMediaPlayer.OnPrepareListener) {
        _onPreparedListener = listener
    }

    override fun setOnCompletionListener(listener: IMediaPlayer.OnCompletionListener) {
        _onCompletionListener = listener
    }

    override fun setOnBufferingUpdateListener(listener: IMediaPlayer.OnBufferingUpdateListener) {
        _onBufferingUpdateListener = listener
    }

    override fun setSeekCompleteListener(listener: IMediaPlayer.OnSeekCompleteListener) {
        _onSeekCompleteListener = listener
    }

    override fun setVideoSizeChangedListener(listener: IMediaPlayer.OnVideoSizeChangedListener) {
        _onVideoSizeChangedListener = listener
    }

    override fun setOnErrorListener(listener: IMediaPlayer.OnErrorListener) {
        _onErrorListener = listener
    }

    override fun setOnInfoListener(listener: IMediaPlayer.OnInfoListener) {
        _onInfoListener = listener
    }
}