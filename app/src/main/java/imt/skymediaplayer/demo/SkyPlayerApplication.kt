package imt.skymediaplayer.demo

import android.app.Application
import android.util.Log
import imt.zw.skymediaplayer.whisper.WhisperModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SkyPlayer 应用程序类
 * 负责在应用启动时预解压 Whisper 模型文件
 */
class SkyPlayerApplication : Application() {

    companion object {
        private const val TAG = "SkyPlayerApplication"
        
        // 单例访问
        private var instance: SkyPlayerApplication? = null
        
        fun getInstance(): SkyPlayerApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    // 应用级协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Whisper 模型管理器
    lateinit var whisperModelManager: WhisperModelManager
        private set

    // 模型准备状态
    var isModelReady: Boolean = false
        private set

    // 模型路径（准备好后可用）
    var modelPath: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "SkyPlayerApplication onCreate")

        // 初始化 Whisper 模型管理器
        whisperModelManager = WhisperModelManager(this)

        // 在后台预解压模型
        prepareWhisperModel()
    }

    /**
     * 在后台预解压 Whisper 模型
     */
    private fun prepareWhisperModel() {
        // 检查模型是否已准备好
        if (whisperModelManager.isModelReady()) {
            isModelReady = true
            modelPath = whisperModelManager.getModelPath()
            Log.i(TAG, "Whisper model already ready: $modelPath")
            return
        }

        // 在后台线程解压模型
        applicationScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting Whisper model extraction in background...")
            val startTime = System.currentTimeMillis()

            try {
                val path = whisperModelManager.prepareModelSync()
                val elapsed = System.currentTimeMillis() - startTime

                if (path != null) {
                    isModelReady = true
                    modelPath = path
                    Log.i(TAG, "Whisper model ready in ${elapsed}ms: $path")
                } else {
                    Log.e(TAG, "Failed to prepare Whisper model after ${elapsed}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing Whisper model", e)
            }
        }
    }

    /**
     * 获取 Whisper 模型路径
     * @return 模型路径，如果未准备好则返回 null
     */
    fun getWhisperModelPath(): String? {
        return if (isModelReady) modelPath else null
    }

    /**
     * 检查 Whisper 模型是否准备好
     */
    fun isWhisperModelReady(): Boolean {
        return isModelReady && modelPath != null
    }
}
