package imt.zw.skymediaplayer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log

/**
 * LUT 加载工具：把 GPUImage 风格的 512x512 查找表 PNG 解码为 RGBA 字节数组，
 * 供 [imt.zw.skymediaplayer.player.SkyMediaPlayer.setLut] 使用。
 *
 * 约定：LUT 必须是 512x512（8x8 个 64x64 小块，块内 x→R/y→G，块索引=Blue）。
 */
object LutLoader {

    private const val TAG = "LutLoader"
    const val LUT_SIZE = 512

    /**
     * 把 Bitmap 转成 512*512*4 的 RGBA 字节数组。
     * @return RGBA 字节数组；尺寸不是 512x512 时返回 null
     */
    fun toRgba(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        if (bitmap.width != LUT_SIZE || bitmap.height != LUT_SIZE) {
            Log.e(TAG, "toRgba: invalid LUT size ${bitmap.width}x${bitmap.height}, expected ${LUT_SIZE}x$LUT_SIZE")
            return null
        }
        val pixels = IntArray(LUT_SIZE * LUT_SIZE)
        bitmap.getPixels(pixels, 0, LUT_SIZE, 0, 0, LUT_SIZE, LUT_SIZE)
        val out = ByteArray(LUT_SIZE * LUT_SIZE * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val o = i * 4
            out[o] = (p ushr 16).toByte()      // R
            out[o + 1] = (p ushr 8).toByte()   // G
            out[o + 2] = p.toByte()            // B
            out[o + 3] = (p ushr 24).toByte()  // A
        }
        return out
    }

    /** 从 assets 加载，如 "lut/1001.png" */
    fun fromAsset(context: Context, assetPath: String): ByteArray? {
        return try {
            context.assets.open(assetPath).use { toRgba(BitmapFactory.decodeStream(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "fromAsset failed: $assetPath", e)
            null
        }
    }

    /** 从本地文件路径加载 */
    fun fromFile(path: String): ByteArray? {
        return try {
            toRgba(BitmapFactory.decodeFile(path))
        } catch (e: Exception) {
            Log.e(TAG, "fromFile failed: $path", e)
            null
        }
    }

    /** 从 content Uri 加载 */
    fun fromUri(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { toRgba(BitmapFactory.decodeStream(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "fromUri failed: $uri", e)
            null
        }
    }
}
