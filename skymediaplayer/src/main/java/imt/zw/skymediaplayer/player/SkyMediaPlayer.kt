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

        // Whisper 字幕消息（与 sky_messages.h 中的 SKY_MSG_WHISPER_SUBTITLE 对应）
        private const val MEDIA_WHISPER_SUBTITLE = 30001
        private const val MEDIA_WHISPER_PREBUFFER_COMPLETE = 30002

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
                MEDIA_WHISPER_SUBTITLE -> {
                    // 处理 Whisper 字幕事件（带 PTS 时间戳）
                    val objArray = msg.obj as? Array<*>
                    if (objArray != null && objArray.size >= 3) {
                        val subtitleText = objArray[0] as? String
                        val startTimeMs = (objArray[1] as? Long) ?: -1L
                        val endTimeMs = (objArray[2] as? Long) ?: -1L
                        
                        if (!subtitleText.isNullOrEmpty()) {
                            Log.i(TAG, "handleEventFromNative MEDIA_WHISPER_SUBTITLE: $subtitleText, start=$startTimeMs, end=$endTimeMs")
                            
                            // 将字幕加入缓冲队列（用于 PTS 同步）
                            val subtitleData = SubtitleData(subtitleText, startTimeMs, endTimeMs)
                            synchronized(player._subtitleQueue) {
                                player._subtitleQueue.add(subtitleData)
                            }
                            
                            // 回调带时间戳的监听器
                            player._onSubtitleWithPtsListener?.onSubtitle(player, subtitleText, startTimeMs, endTimeMs)
                            
                            // 同时回调旧的监听器（兼容）
                            player._onSubtitleListener?.onSubtitle(player, subtitleText)
                        }
                    } else {
                        // 兼容旧格式（直接是字符串）
                        val subtitleText = msg.obj as? String
                        if (!subtitleText.isNullOrEmpty()) {
                            Log.i(TAG, "handleEventFromNative MEDIA_WHISPER_SUBTITLE (legacy): $subtitleText")
                            
                            // 将字幕加入缓冲队列（无时间戳）
                            val subtitleData = SubtitleData(subtitleText, -1L, -1L)
                            synchronized(player._subtitleQueue) {
                                player._subtitleQueue.add(subtitleData)
                            }
                            
                            player._onSubtitleWithPtsListener?.onSubtitle(player, subtitleText, -1L, -1L)
                            player._onSubtitleListener?.onSubtitle(player, subtitleText)
                        }
                    }
                }
                MEDIA_WHISPER_PREBUFFER_COMPLETE -> {
                    // 处理预缓冲完成事件
                    val subtitleCount = msg.arg1
                    Log.i(TAG, "handleEventFromNative MEDIA_WHISPER_PREBUFFER_COMPLETE: count=$subtitleCount")
                    player._onPrebufferCompleteListener?.onPrebufferComplete(player, subtitleCount)
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
    private var _onSubtitleListener: OnSubtitleListener ?= null
    private var _onSubtitleWithPtsListener: OnSubtitleWithPtsListener ?= null
    private var _onPrebufferCompleteListener: OnPrebufferCompleteListener ?= null
    
    // 字幕缓冲队列（用于 PTS 同步）
    private val _subtitleQueue = mutableListOf<SubtitleData>()

    /**
     * Whisper 字幕监听器接口
     */
    interface OnSubtitleListener {
        /**
         * 收到字幕文本时回调（不带时间戳，兼容旧接口）
         * @param mp 播放器实例
         * @param text 字幕文本
         */
        fun onSubtitle(mp: IMediaPlayer, text: String)
    }

    /**
     * 带时间戳的 Whisper 字幕监听器接口（用于 PTS 同步方案）
     */
    interface OnSubtitleWithPtsListener {
        /**
         * 收到带时间戳的字幕文本时回调
         * @param mp 播放器实例
         * @param text 字幕文本
         * @param startTimeMs 字幕开始时间（毫秒），-1 表示未知
         * @param endTimeMs 字幕结束时间（毫秒），-1 表示未知
         */
        fun onSubtitle(mp: IMediaPlayer, text: String, startTimeMs: Long, endTimeMs: Long)
    }

    /**
     * 字幕数据类（用于 PTS 同步方案）
     * @param text 字幕文本
     * @param startTimeMs 字幕开始时间（毫秒）
     * @param endTimeMs 字幕结束时间（毫秒）
     */
    data class SubtitleData(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    /**
     * 预缓冲完成监听器接口
     */
    interface OnPrebufferCompleteListener {
        /**
         * 预缓冲完成时回调
         * @param mp 播放器实例
         * @param subtitleCount 已缓冲的字幕数量
         */
        fun onPrebufferComplete(mp: IMediaPlayer, subtitleCount: Int)
    }

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
    @Keep
    private external fun _setAudioFilter(filter: String?): Int
    @Keep
    private external fun _setWhisperPrebufferMode(enabled: Boolean): Boolean
    @Keep
    private external fun _setRendererBackend(backend: Int)
    @Keep
    private external fun _setDecoderMode(mode: Int)
    @Keep
    private external fun _getActiveDecoderMode(): Int
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

    /**
     * 设置 Whisper 字幕监听器
     * @param listener 字幕监听器
     */
    fun setOnSubtitleListener(listener: OnSubtitleListener?) {
        _onSubtitleListener = listener
    }

    /**
     * 设置带时间戳的 Whisper 字幕监听器（用于 PTS 同步方案）
     * @param listener 字幕监听器
     */
    fun setOnSubtitleWithPtsListener(listener: OnSubtitleWithPtsListener?) {
        _onSubtitleWithPtsListener = listener
    }

    /**
     * 设置预缓冲完成监听器
     * @param listener 预缓冲完成监听器
     */
    fun setOnPrebufferCompleteListener(listener: OnPrebufferCompleteListener?) {
        _onPrebufferCompleteListener = listener
    }

    /**
     * 获取当前应该显示的字幕
     * 
     * 由于 Whisper 识别存在固有延迟（约 15-30 秒），字幕的 PTS 时间戳会落后于当前播放位置。
     * 因此采用 "最新可用字幕" 策略：
     * - 显示最近收到的、尚未过期的字幕
     * - 字幕显示时长为 3 秒（从收到时刻开始计算）
     * - 这样即使字幕有延迟，用户也能看到最新的识别结果
     * 
     * @return 当前应该显示的字幕数据，如果没有则返回 null
     */
    fun getCurrentSubtitle(): SubtitleData? {
        val currentPos = getCurrentPosition()
        
        synchronized(_subtitleQueue) {
            // 策略：显示最新收到的字幕，保持显示 3 秒
            // 由于 Whisper 延迟，我们不能用 PTS 精确同步，而是用 "最新可用" 策略
            
            if (_subtitleQueue.isEmpty()) {
                return null
            }
            
            // 获取最新的字幕（队列末尾）
            val latestSubtitle = _subtitleQueue.lastOrNull() ?: return null
            
            // 检查字幕是否应该显示
            // 使用字幕的 endTimeMs 作为显示截止时间的参考
            // 但由于 Whisper 延迟，我们需要更宽松的判断
            
            // 如果队列中有多个字幕，只保留最新的几个（避免队列无限增长）
            val maxQueueSize = 10
            if (_subtitleQueue.size > maxQueueSize) {
                val removeCount = _subtitleQueue.size - maxQueueSize
                repeat(removeCount) {
                    _subtitleQueue.removeAt(0)
                }
            }
            
            // 返回最新的字幕
            // 字幕的显示时长由 UI 层控制（通过 startTimeMs 和 endTimeMs 的差值）
            return latestSubtitle
        }
    }

    /**
     * 获取字幕缓冲队列的副本
     * @return 字幕队列的副本
     */
    fun getSubtitleQueue(): List<SubtitleData> {
        synchronized(_subtitleQueue) {
            return _subtitleQueue.toList()
        }
    }

    /**
     * 清空字幕缓冲队列
     */
    fun clearSubtitleQueue() {
        synchronized(_subtitleQueue) {
            _subtitleQueue.clear()
        }
    }

    /**
     * 渲染后端类型枚举
     * 与 Native 层 RendererBackend 枚举值保持一致
     */
    enum class RendererBackend(val value: Int) {
        OPENGL_ES(0),
        VULKAN(1),
        METAL(2),
        AUTO(3);

        companion object {
            fun fromValue(value: Int): RendererBackend {
                return entries.firstOrNull { it.value == value } ?: OPENGL_ES
            }
        }
    }

    /**
     * 设置渲染后端（必须在 prepareAsync 之前调用）
     * @param backend 渲染后端类型
     */
    fun setRendererBackend(backend: RendererBackend) {
        Log.i(TAG, "setRendererBackend: ${backend.name}")
        _setRendererBackend(backend.value)
    }

    /**
     * 设置渲染后端（通过 int 值，必须在 prepareAsync 之前调用）
     * @param backendValue 渲染后端类型值（0=OpenGL ES, 1=Vulkan, 2=Metal, 3=Auto）
     */
    fun setRendererBackend(backendValue: Int) {
        val backend = RendererBackend.fromValue(backendValue)
        setRendererBackend(backend)
    }

    /**
     * 解码模式枚举
     * 与 Native 层 DecoderMode 枚举值保持一致
     */
    enum class DecoderMode(val value: Int) {
        /** 硬解 + Surface 直渲染（零拷贝，性能最优） */
        HW_SURFACE(0),
        /** 硬解 + Buffer 输出（支持后处理） */
        HW_BUFFER(1),
        /** FFmpeg 纯软解 */
        SOFTWARE(2),
        /** 自动选择（三级回退：HW_SURFACE → HW_BUFFER → SOFTWARE） */
        AUTO(3);

        companion object {
            fun fromValue(value: Int): DecoderMode {
                return entries.firstOrNull { it.value == value } ?: AUTO
            }
        }
    }

    /**
     * 设置解码模式（必须在 prepareAsync 之前调用）
     * @param mode 解码模式
     */
    fun setDecoderMode(mode: DecoderMode) {
        Log.i(TAG, "setDecoderMode: ${mode.name}")
        _setDecoderMode(mode.value)
    }

    /**
     * 设置解码模式（通过 int 值，必须在 prepareAsync 之前调用）
     * @param modeValue 解码模式值（0=HW_SURFACE, 1=HW_BUFFER, 2=SOFTWARE, 3=AUTO）
     */
    fun setDecoderMode(modeValue: Int) {
        val mode = DecoderMode.fromValue(modeValue)
        setDecoderMode(mode)
    }

    /**
     * 获取实际生效的解码模式（经过回退策略后的结果）
     * @return 实际使用的解码模式值（0=HW_SURFACE, 1=HW_BUFFER, 2=SOFTWARE）
     */
    fun getActiveDecoderMode(): Int {
        return _getActiveDecoderMode()
    }

    /**
     * 设置动态音频滤镜
     * @param filter 滤镜描述字符串，如 "whisper=model=/path/to/model.bin:language=zh"，传 null 清除滤镜
     * @return 0 成功，负值失败
     */
    fun setAudioFilter(filter: String?): Int {
        Log.i(TAG, "setAudioFilter: ${filter ?: "(none)"}")
        return _setAudioFilter(filter)
    }

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
        return if (enabled) {
            if (modelPath.isNullOrEmpty()) {
                Log.e(TAG, "setWhisperEnabled: modelPath is required when enabling Whisper")
                -1
            } else {
                // 确保 queueSeconds 在有效范围内 (3-20)
                val validQueueSeconds = queueSeconds.coerceIn(3, 20)
                val filter = "whisper=model=$modelPath:language=$language:queue=${validQueueSeconds}s:use_gpu=1"
                Log.i(TAG, "setWhisperEnabled: enabling with filter=$filter")
                setAudioFilter(filter)
            }
        } else {
            Log.i(TAG, "setWhisperEnabled: disabling Whisper")
            setAudioFilter(null)
        }
    }

    /**
     * 设置 Whisper 预缓冲模式
     * 预缓冲模式下：音频解码继续但不播放，视频暂停，音频帧只送入 Whisper
     * 用于在开启 AI 字幕时预先缓冲一些字幕，避免字幕延迟
     * @param enabled 是否启用预缓冲模式
     * @return true 成功，false 失败
     */
    fun setWhisperPrebufferMode(enabled: Boolean): Boolean {
        Log.i(TAG, "setWhisperPrebufferMode: enabled=$enabled")
        return _setWhisperPrebufferMode(enabled)
    }
}