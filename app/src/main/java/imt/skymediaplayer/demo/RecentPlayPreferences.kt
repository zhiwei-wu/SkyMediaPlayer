package imt.skymediaplayer.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近播放历史持久化工具类
 * 使用 SharedPreferences 存 JSON 列表，重启后仍可点开播放
 */
object RecentPlayPreferences {
    private const val PREF_NAME = "sky_player_settings"
    private const val KEY_RECENT = "recent_plays"
    private const val MAX_COUNT = 20

    const val TYPE_LOCAL = "local"
    const val TYPE_URL = "url"

    data class Item(val uri: String, val title: String, val type: String)

    /** 获取最近播放列表（按时间倒序，最新在前） */
    fun getAll(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("uri"), o.getString("title"), o.getString("type"))
        }
    }

    /** 加入一条记录：同 uri 去重后置顶，超出上限截断 */
    fun add(context: Context, uri: String, title: String, type: String) {
        val list = getAll(context).filter { it.uri != uri }.toMutableList()
        list.add(0, Item(uri, title, type))
        val trimmed = list.take(MAX_COUNT)
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().apply {
                put("uri", it.uri)
                put("title", it.title)
                put("type", it.type)
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT, arr.toString()).apply()
    }
}
