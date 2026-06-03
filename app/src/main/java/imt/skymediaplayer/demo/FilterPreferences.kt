package imt.skymediaplayer.demo

import android.content.Context

/**
 * 画质滤镜设置持久化工具类
 * 保存用户在设置页选择的「滤镜文件」路径，供播放器「滤镜」菜单的「默认」项加载。
 */
object FilterPreferences {
    private const val PREF_NAME = "sky_player_settings"
    private const val KEY_FILTER_FILE_PATH = "filter_file_path"

    /**
     * 获取用户设置的滤镜文件路径（已拷贝到 app 私有目录的本地路径）
     * @return 文件路径；未设置时返回 null
     */
    fun getFilterFilePath(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FILTER_FILE_PATH, null)
    }

    /**
     * 保存滤镜文件路径
     * @param path 本地文件路径；传 null 清除
     */
    fun setFilterFilePath(context: Context, path: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (path.isNullOrEmpty()) remove(KEY_FILTER_FILE_PATH) else putString(KEY_FILTER_FILE_PATH, path)
            }
            .apply()
    }
}
