package imt.zw.skymediaplayer.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder

interface IMediaPlayer {
    fun setDisplay(sh: SurfaceHolder?)
    fun setDataSource(context: Context, localVideoPath: String)
    fun setDataSource(context: Context, uri: Uri)
    fun prepareAsync()
    fun prepare()
    fun start()
    fun stop()
    fun pause()
    fun seekTo(milliSec: Long)
    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun release()
    fun reset()
    fun setVolume(leftVolume: Float, rightVolume: Float)
    fun isPlaying(): Boolean
    fun getAudioSessionId(): Int

    /**
     * 下面这么多的接口，为什么不合并到一个大的接口中呢？
     */
    interface OnPrepareListener {
        fun onPrepared(mp: IMediaPlayer)
    }

    interface OnCompletionListener {
        fun onCompletion(mp: IMediaPlayer)
    }

    interface OnBufferingUpdateListener {
        fun onBufferingUpdate(mp: IMediaPlayer, percent: Int)
    }

    interface OnSeekCompleteListener {
        fun onSeekComplete(mp: IMediaPlayer)
    }

    interface OnVideoSizeChangedListener {
        fun onVideoSizeChanged(mp: IMediaPlayer, width: Int, height: Int, sar_num: Int, sar_den: Int)
    }

    interface OnErrorListener {
        fun onError(mp: IMediaPlayer, what: Int, extra: Int): Boolean
    }

    interface OnInfoListener {
        fun onInfo(mp: IMediaPlayer, what: Int, extra: Int): Boolean
    }

    fun setOnPreparedListener(listener: OnPrepareListener) {
    }

    fun setOnCompletionListener(listener: OnCompletionListener) {
    }

    fun setOnBufferingUpdateListener(listener: OnBufferingUpdateListener) {
    }

    fun setSeekCompleteListener(listener: OnSeekCompleteListener) {
    }

    fun setVideoSizeChangedListener(listener: OnVideoSizeChangedListener) {
    }

    fun setOnErrorListener(listener: OnErrorListener) {
    }

    fun setOnInfoListener(listener: OnInfoListener) {
    }
    //

    fun setSurface(surface: Surface)
}