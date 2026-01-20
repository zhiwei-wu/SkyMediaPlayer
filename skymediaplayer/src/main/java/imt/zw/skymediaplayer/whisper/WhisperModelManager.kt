package imt.zw.skymediaplayer.whisper

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Whisper 模型管理器
 * 负责从 assets 解压模型文件到应用私有目录，并提供模型路径
 */
class WhisperModelManager(private val context: Context) {

    companion object {
        private const val TAG = "WhisperModelManager"
        
        // Assets 中的模型路径（使用 tiny.en 英文专用模型，推理速度最快）
        private const val ASSETS_MODEL_PATH = "whisper/ggml-tiny.en.bin"
        
        // 模型存储目录和文件名
        private const val MODEL_DIR = "whisper"
        private const val MODEL_FILE = "ggml-tiny.en.bin"
        
        // 模型文件预期大小（用于验证完整性）
        private const val EXPECTED_SIZE = 77704715L  // 约 74MB（tiny.en 英文专用模型）
        
        // 模型文件 SHA1 校验值（tiny.en 模型）
        private const val EXPECTED_SHA1 = "c78c86eb1a8faa21b369bcd33207cc90d64ae9df"
    }

    // 模型存储目录
    private val modelDir: File = File(context.filesDir, MODEL_DIR)
    
    // 模型文件
    private val modelFile: File = File(modelDir, MODEL_FILE)

    /**
     * 检查模型是否已准备好
     * @return true 如果模型文件存在且大小正确
     */
    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() == EXPECTED_SIZE
    }

    /**
     * 获取模型文件的绝对路径
     * @return 模型文件绝对路径，如果模型未准备好则返回 null
     */
    fun getModelPath(): String? {
        return if (isModelReady()) modelFile.absolutePath else null
    }

    /**
     * 获取模型文件大小（MB）
     */
    fun getModelSizeMB(): Float {
        return EXPECTED_SIZE / (1024f * 1024f)
    }

    /**
     * 同步准备模型（阻塞调用）
     * 适用于在后台线程中调用
     * @return 模型文件绝对路径，失败返回 null
     */
    fun prepareModelSync(): String? {
        if (isModelReady()) {
            Log.i(TAG, "Model already ready: ${modelFile.absolutePath}")
            return modelFile.absolutePath
        }

        return try {
            extractModel()
            if (isModelReady()) {
                Log.i(TAG, "Model extracted successfully: ${modelFile.absolutePath}")
                modelFile.absolutePath
            } else {
                Log.e(TAG, "Model extraction failed: size mismatch")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model", e)
            null
        }
    }

    /**
     * 异步准备模型（带进度回调）
     * @param callback 回调接口
     */
    fun prepareModelAsync(callback: ModelPrepareCallback) {
        if (isModelReady()) {
            callback.onSuccess(modelFile.absolutePath)
            return
        }

        Thread {
            try {
                extractModelWithProgress(callback)
                if (isModelReady()) {
                    callback.onSuccess(modelFile.absolutePath)
                } else {
                    callback.onError(Exception("Model extraction failed: size mismatch"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract model", e)
                callback.onError(e)
            }
        }.start()
    }

    /**
     * 从 assets 解压模型文件
     */
    private fun extractModel() {
        // 确保目录存在
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        // 删除可能存在的不完整文件
        if (modelFile.exists()) {
            modelFile.delete()
        }

        Log.i(TAG, "Extracting model from assets: $ASSETS_MODEL_PATH")

        context.assets.open(ASSETS_MODEL_PATH).use { input ->
            modelFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)  // 64KB 缓冲区
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }

        Log.i(TAG, "Model extracted, size: ${modelFile.length()} bytes")
    }

    /**
     * 从 assets 解压模型文件（带进度回调）
     */
    private fun extractModelWithProgress(callback: ModelPrepareCallback) {
        // 确保目录存在
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        // 删除可能存在的不完整文件
        if (modelFile.exists()) {
            modelFile.delete()
        }

        Log.i(TAG, "Extracting model from assets with progress: $ASSETS_MODEL_PATH")

        context.assets.open(ASSETS_MODEL_PATH).use { input ->
            modelFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)  // 64KB 缓冲区
                var copiedSize = 0L
                var bytesRead: Int
                var lastProgress = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copiedSize += bytesRead

                    // 计算进度（0-100）
                    val progress = ((copiedSize * 100) / EXPECTED_SIZE).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        callback.onProgress(progress)
                    }
                }
            }
        }

        Log.i(TAG, "Model extracted, size: ${modelFile.length()} bytes")
    }

    /**
     * 删除模型文件（释放存储空间）
     * @return true 如果删除成功
     */
    fun deleteModel(): Boolean {
        return if (modelFile.exists()) {
            val result = modelFile.delete()
            Log.i(TAG, "Model deleted: $result")
            result
        } else {
            true
        }
    }

    /**
     * 获取模型文件信息
     */
    fun getModelInfo(): ModelInfo {
        return ModelInfo(
            name = "ggml-tiny.en",
            path = modelFile.absolutePath,
            exists = modelFile.exists(),
            size = if (modelFile.exists()) modelFile.length() else 0,
            expectedSize = EXPECTED_SIZE,
            isReady = isModelReady()
        )
    }
}

/**
 * 模型准备回调接口
 */
interface ModelPrepareCallback {
    /**
     * 解压进度回调
     * @param progress 进度值（0-100）
     */
    fun onProgress(progress: Int)

    /**
     * 解压成功回调
     * @param modelPath 模型文件绝对路径
     */
    fun onSuccess(modelPath: String)

    /**
     * 解压失败回调
     * @param error 错误信息
     */
    fun onError(error: Exception)
}

/**
 * 模型信息数据类
 */
data class ModelInfo(
    val name: String,
    val path: String,
    val exists: Boolean,
    val size: Long,
    val expectedSize: Long,
    val isReady: Boolean
)
