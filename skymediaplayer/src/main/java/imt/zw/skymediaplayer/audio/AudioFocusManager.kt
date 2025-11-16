package imt.zw.skymediaplayer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 音频焦点管理器
 * 负责处理音频焦点的请求、释放和状态变化
 */
class AudioFocusManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioFocusManager"
    }
    
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    // 音频焦点变化监听器
    private var onAudioFocusChangeListener: OnAudioFocusChangeListener? = null
    
    /**
     * 音频焦点变化回调接口
     */
    interface OnAudioFocusChangeListener {
        fun onAudioFocusGain()
        fun onAudioFocusLoss()
        fun onAudioFocusLossTransient()
        fun onAudioFocusLossTransientCanDuck()
    }
    
    /**
     * 设置音频焦点变化监听器
     */
    fun setOnAudioFocusChangeListener(listener: OnAudioFocusChangeListener) {
        this.onAudioFocusChangeListener = listener
    }
    
    /**
     * 请求音频焦点
     * @return true 如果成功获得焦点，false 否则
     */
    fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Requesting audio focus, current hasAudioFocus: $hasAudioFocus")

        if (hasAudioFocus) {
            Log.d(TAG, "Already has audio focus")
            return true
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Using Android 8.0+ audio focus API")
            requestAudioFocusApi26()
        } else {
            Log.d(TAG, "Using legacy audio focus API")
            requestAudioFocusLegacy()
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        Log.d(TAG, "Request audio focus result: $result (${getAudioFocusResultString(result)}), hasAudioFocus: $hasAudioFocus")
        return hasAudioFocus
    }

    private fun getAudioFocusResultString(result: Int): String {
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
            else -> "UNKNOWN($result)"
        }
    }
    
    /**
     * 释放音频焦点
     */
    fun abandonAudioFocus() {
        if (!hasAudioFocus) {
            Log.d(TAG, "No audio focus to abandon")
            return
        }
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            abandonAudioFocusApi26()
        } else {
            abandonAudioFocusLegacy()
        }
        
        hasAudioFocus = false
        Log.d(TAG, "Abandon audio focus result: $result")
    }
    
    /**
     * Android 8.0+ 的音频焦点请求方式
     */
    private fun requestAudioFocusApi26(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            return audioManager.requestAudioFocus(audioFocusRequest!!)
        }
        return AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }
    
    /**
     * Android 8.0 以下的音频焦点请求方式
     */
    @Suppress("DEPRECATION")
    private fun requestAudioFocusLegacy(): Int {
        return audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }
    
    /**
     * Android 8.0+ 的音频焦点释放方式
     */
    private fun abandonAudioFocusApi26(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }
    
    /**
     * Android 8.0 以下的音频焦点释放方式
     */
    @Suppress("DEPRECATION")
    private fun abandonAudioFocusLegacy(): Int {
        return audioManager.abandonAudioFocus(audioFocusChangeListener)
    }
    
    /**
     * 音频焦点变化监听器
     */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus changed: $focusChange")
        
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 获得音频焦点
                hasAudioFocus = true
                onAudioFocusChangeListener?.onAudioFocusGain()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去音频焦点
                hasAudioFocus = false
                onAudioFocusChangeListener?.onAudioFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 暂时失去音频焦点
                hasAudioFocus = false
                onAudioFocusChangeListener?.onAudioFocusLossTransient()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 暂时失去音频焦点，但可以降低音量继续播放
                onAudioFocusChangeListener?.onAudioFocusLossTransientCanDuck()
            }
        }
    }
    
    /**
     * 检查是否拥有音频焦点
     */
    fun hasAudioFocus(): Boolean {
        return hasAudioFocus
    }
}