package imt.zw.skymediaplayer.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

object Utils {
    private const val TAG = "Utils"

    /**
     * 从content URI获取真实文件路径
     */
    fun getRealPathFromURI(context: Context, uri: Uri): String? {
        return when {
            // DocumentProvider
            DocumentsContract.isDocumentUri(context, uri) -> {
                when {
                    // ExternalStorageProvider
                    isExternalStorageDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]

                        if ("primary".equals(type, ignoreCase = true)) {
                            "${Environment.getExternalStorageDirectory()}/${split[1]}"
                        } else {
                            // Handle non-primary volumes
                            null
                        }
                    }
                    // MediaProvider
                    isMediaDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]

                        val contentUri = when (type) {
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            else -> null
                        }

                        contentUri?.let {
                            val selection = "_id=?"
                            val selectionArgs = arrayOf(split[1])
                            getDataColumn(context, it, selection, selectionArgs)
                        }
                    }
                    // DownloadsProvider
                    isDownloadsDocument(uri) -> {
                        val id = DocumentsContract.getDocumentId(uri)
                        Log.d(TAG, "DownloadsProvider document ID: $id")

                        // 处理 raw: 前缀的情况
                        if (id.startsWith("raw:")) {
                            val path = id.replaceFirst("raw:", "")
                            Log.d(TAG, "Raw path: $path")
                            return path
                        }

                        // 处理 msf: 前缀的情况（Android 10+ 的下载文件）
                        if (id.startsWith("msf:")) {
                            val msfId = id.substring(4) // 移除 "msf:" 前缀
                            Log.d(TAG, "MSF ID: $msfId")

                            // 方法1: 尝试通过 MediaStore.Downloads 查询（Android 10+）
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    val contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                                    val selection = "_id=?"
                                    val selectionArgs = arrayOf(msfId)
                                    val path = getDataColumn(context, contentUri, selection, selectionArgs)
                                    if (path != null) {
                                        Log.d(TAG, "Found path via MediaStore.Downloads: $path")
                                        return path
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error querying MediaStore.Downloads", e)
                            }

                            // 方法2: 尝试直接使用原始 URI
                            try {
                                val path = getDataColumn(context, uri, null, null)
                                if (path != null) {
                                    Log.d(TAG, "Found path via original URI: $path")
                                    return path
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error querying original URI", e)
                            }

                            // 方法3: 尝试通过 content://media/external/downloads 查询
                            try {
                                val contentUri = Uri.parse("content://media/external/downloads/$msfId")
                                val path = getDataColumn(context, contentUri, null, null)
                                if (path != null) {
                                    Log.d(TAG, "Found path via media/external/downloads: $path")
                                    return path
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error querying media/external/downloads", e)
                            }

                            Log.w(TAG, "All methods failed for msf ID: $msfId")
                            return null
                        }

                        // 处理纯数字 ID 的情况
                        val numericId = id.toLongOrNull()
                        if (numericId != null) {
                            val contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"),
                                numericId
                            )
                            return getDataColumn(context, contentUri, null, null)
                        }

                        // 如果都不匹配，返回 null
                        Log.w(TAG, "Unknown document ID format: $id")
                        null
                    }
                    else -> null
                }
            }
            // MediaStore (and general)
            "content".equals(uri.scheme, ignoreCase = true) -> {
                getDataColumn(context, uri, null, null)
            }
            // File
            "file".equals(uri.scheme, ignoreCase = true) -> {
                uri.path
            }
            else -> null
        }
    }

    /**
     * 获取数据列
     */
    private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        val column = "_data"
        val projection = arrayOf(column)

        return try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting data column", e)
            null
        }
    }

    /**
     * 检查是否为外部存储文档
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * 检查是否为下载文档
     */
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * 检查是否为媒体文档
     */
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * 将 URI 内容复制到临时文件
     * 当无法直接获取文件路径时使用此方法
     */
    fun copyUriToTempFile(context: Context, uri: Uri): String? {
        return try {
            // 获取文件扩展名
            val extension = context.contentResolver.getType(uri)?.let { mimeType ->
                when {
                    mimeType.startsWith("video/") -> ".${mimeType.substring(6)}"
                    else -> ".tmp"
                }
            } ?: ".tmp"

            // 创建临时文件
            val tempFile = java.io.File.createTempFile(
                "video_${System.currentTimeMillis()}",
                extension,
                context.cacheDir
            )

            Log.d(TAG, "Copying URI to temp file: ${tempFile.absolutePath}")

            // 复制内容
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Successfully copied URI to temp file, size: ${tempFile.length()} bytes")
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to temp file", e)
            null
        }
    }

    /**
     * 将毫秒转换为 HH:MM:SS 格式的时间字符串
     * @param milliseconds 毫秒数
     * @return 格式化后的时间字符串，如 "01:23:45"
     */
    fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}