package imt.skymediaplayer.demo

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import imt.zw.skymediaplayer.widget.SkyVideoView

class SkyVideoActivity : AppCompatActivity() {
    private lateinit var mSkyVideoView : SkyVideoView
    private lateinit var mMediaController: MediaController
    private var wasPlayingBeforePause = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全沉浸式全屏
        setupFullscreenMode()

        setContentView(R.layout.activity_layout)

        // ========== 支持本地文件和在线视频 (方案A) ==========
        mSkyVideoView = findViewById(R.id.sky_video_view)
        mMediaController = MediaController(this, false)
        mSkyVideoView.setMediaController(mMediaController)

        // 检查是否传递了在线视频 URL
        val videoUrl = intent.getStringExtra("video_url")
        if (videoUrl != null) {
            // 在线视频：直接使用 URL
            Log.d("SkyVideoActivity", "Playing online video: $videoUrl")
            mSkyVideoView.setVideoPath(videoUrl)
            Toast.makeText(this, "正在连接服务器...", Toast.LENGTH_SHORT).show()
        } else {
            // 本地视频：使用 URI
            val videoUriString = intent.getStringExtra("video_uri")
            if (videoUriString == null) {
                Toast.makeText(this, "未选择视频文件", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val videoUri = Uri.parse(videoUriString)
            Log.d("SkyVideoActivity", "Playing local video URI: $videoUri")
            mSkyVideoView.setVideoURI(videoUri)
        }

        // 注意：SkyVideoView 内部已经实现了缓冲和错误监听器
        // 缓冲进度会通过 Log 输出，错误会自动处理
        // 如需自定义处理，可以修改 SkyVideoView 内部的监听器实现

        mSkyVideoView.start()
        // ========== 支持本地文件和在线视频结束 ==========
    }

    private fun setupFullscreenMode() {
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 使用 WindowCompat 和 WindowInsetsControllerCompat 实现全沉浸式
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController?.let { controller ->
            // 隐藏状态栏和导航栏
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 设置沉浸式模式，用户滑动时系统栏会暂时显示然后自动隐藏
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 设置全屏标志（兼容旧版本）
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 当窗口重新获得焦点时，重新设置全屏模式
            setupFullscreenMode()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("SkyVideoActivity", "onPause() called")

        // 记录暂停前的播放状态
        if (::mSkyVideoView.isInitialized) {
            wasPlayingBeforePause = mSkyVideoView.isPlaying
            if (wasPlayingBeforePause) {
                mSkyVideoView.pause()
                Log.d("SkyVideoActivity", "Video paused in onPause()")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("SkyVideoActivity", "onResume() called")

        // 如果之前在播放，则恢复播放
        if (::mSkyVideoView.isInitialized && wasPlayingBeforePause) {
            // 使用post确保Surface已经完全重建后再恢复播放
            mSkyVideoView.post {
                mSkyVideoView.start()
                wasPlayingBeforePause = false
                Log.d("SkyVideoActivity", "Video resumed in onResume() after Surface ready")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SkyVideoActivity", "onDestroy() called - releasing all resources")

        // 释放所有资源，确保音频完全停止
        if (::mSkyVideoView.isInitialized) {
            mSkyVideoView.release()
            Log.d("SkyVideoActivity", "SkyVideoView resources released")
        }

        // 清理 MediaController
        if (::mMediaController.isInitialized) {
            mMediaController.hide()
            Log.d("SkyVideoActivity", "MediaController hidden")
        }
    }

    override fun onBackPressed() {
        Log.d("SkyVideoActivity", "onBackPressed() called")
        // 确保按返回键时也能正确释放资源
        super.onBackPressed()
    }
}