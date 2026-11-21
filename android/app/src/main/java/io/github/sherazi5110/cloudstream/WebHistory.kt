package io.github.sherazi5110.cloudstream

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small history kept on the device (newest first, capped): songs played in Music. Each list has its own prefs file, so it's in backups and survives restarts.
 */
class HistoryStore(private val file: String, private val cap: Int) {
    data class Entry(val id: String, val title: String, val subtitle: String = "", val image: String? = null, val at: Long = System.currentTimeMillis())

    private val prefs get() = App.context.getSharedPreferences(file, 0)
    val items = mutableStateListOf<Entry>().apply { addAll(load()) }

    private fun load(): List<Entry> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Entry(o.getString("id"), o.optString("title"), o.optString("subtitle"), o.optString("image").ifBlank { null }, o.optLong("at"))
        } }
    }.getOrDefault(emptyList())

    private fun save() = prefs.edit().putString("items", JSONArray(items.map {
        JSONObject().put("id", it.id).put("title", it.title).put("subtitle", it.subtitle).put("image", it.image ?: "").put("at", it.at)
    }).toString()).apply()

    /** Adds [e] at the top (moving it there when it's already in the list). */
    fun add(e: Entry) {
        items.removeAll { it.id == e.id }
        items.add(0, e)
        while (items.size > cap) items.removeAt(items.lastIndex)
        save()
    }
    /** Replaces the entry with the same id, keeping its place. */
    fun update(e: Entry) { val i = items.indexOfFirst { it.id == e.id }; if (i >= 0) { items[i] = e; save() } }
    fun remove(id: String) { items.removeAll { it.id == id }; save() }
    fun contains(id: String) = items.any { it.id == id }
    fun clear() { items.clear(); save() }
}

object WebHistory {
    val songs = HistoryStore("music_history", 300)
}
