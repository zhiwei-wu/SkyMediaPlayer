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
                        if (id.startsWith("raw:")) {
                            return id.replaceFirst("raw:", "")
                        }

                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            id.toLongOrNull() ?: 0L
                        )
                        getDataColumn(context, contentUri, null, null)
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