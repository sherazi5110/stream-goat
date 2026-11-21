package io.github.sherazi5110.cloudstream

import androidx.compose.runtime.mutableStateListOf
import com.lagradost.cloudstream3.SearchResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * Titles the person hid (issue #64): long-press a poster → Hide, and it disappears from Home, catalogs, search and
 * suggestions. Settings → Profiles → Hidden titles lists them with Restore and Restore all. Kept in its own prefs file,
 * so it survives restarts and is part of backups; My list and history aren't touched.
 */
object Hidden {
    data class Item(val url: String, val name: String, val poster: String?)

    private val prefs get() = App.context.getSharedPreferences("hidden_titles", 0)
    val items = mutableStateListOf<Item>().apply { addAll(load()) }

    private fun load(): List<Item> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Item(o.getString("url"), o.optString("name"), o.optString("poster").ifBlank { null }) } }
    }.getOrDefault(emptyList())

    private fun save() = prefs.edit().putString("items", JSONArray(items.map {
        JSONObject().put("url", it.url).put("name", it.name).put("poster", it.poster ?: "")
    }).toString()).apply()

    fun isHidden(url: String): Boolean = items.any { it.url == url }
    fun hide(r: SearchResponse) { if (!isHidden(r.url)) { items += Item(r.url, r.name, r.posterUrl); save() } }
    fun restore(url: String) { items.removeAll { it.url == url }; save() }
    fun restoreAll() { items.clear(); save() }
}
