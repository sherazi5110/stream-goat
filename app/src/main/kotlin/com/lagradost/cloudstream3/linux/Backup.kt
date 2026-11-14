package com.lagradost.cloudstream3.linux

import android.content.SharedPreferences
import com.lagradost.cloudstream3.linux.ui.AppInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Back up and restore: every settings store (app settings, profiles, library and history, repositories, the
 * list of installed extensions, extension settings, Live TV sources, Stremio add-ons) as one JSON file.
 * The file works on both apps (Linux and Android). Extensions themselves aren't in it: after a restore,
 * the ones that are missing are downloaded again from their repositories.
 */
object Backup {
    const val FORMAT = "cloudstream-linxdroid-backup"

    /**
     * Settings stores that hold nothing worth moving (caches, this device's paths), or that belong to this device
     * only: its Google Drive sign-in (cloud_backup) and its last crash report (crash).
     */
    private val skip = Regex("^(WebView|androidx|com\\.google)|cache|^(cloud_backup|crash)$", RegexOption.IGNORE_CASE)

    private fun names(): List<String> = File(AppDirs.data, "prefs").listFiles { f -> f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }.orEmpty().filterNot { skip.containsMatchIn(it) }.sorted()

    private fun prefs(name: String): SharedPreferences = Platform.context.getSharedPreferences(name, 0)

    fun export(): String {
        val stores = JSONObject()
        for (name in names()) {
            val values = JSONObject()
            prefs(name).getAll().forEach { (k, v) ->
                values.put(k, when (v) {
                    is String -> JSONObject().put("s", v)
                    is Boolean -> JSONObject().put("b", v)
                    is Int -> JSONObject().put("i", v)
                    is Long -> JSONObject().put("l", v)
                    is Float -> JSONObject().put("f", v.toDouble())
                    is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                    else -> return@forEach
                })
            }
            if (values.length() > 0) stores.put(name, values)
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("app", AppInfo.VERSION)
            .put("created", System.currentTimeMillis()).put("stores", stores).toString(1)
    }

    /** Replaces the settings stores found in [json]. Returns how many stores were restored. */
    fun import(json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This isn't a Stream Goat backup file" }
        val stores = root.getJSONObject("stores")
        var n = 0
        for (name in stores.keys()) {
            if (skip.containsMatchIn(name)) continue
            val values = stores.getJSONObject(name)
            val edit = prefs(name).edit().clear()
            for (k in values.keys()) {
                val o = values.getJSONObject(k)
                when {
                    o.has("s") -> edit.putString(k, o.getString("s"))
                    o.has("b") -> edit.putBoolean(k, o.getBoolean("b"))
                    o.has("i") -> edit.putInt(k, o.getInt("i"))
                    o.has("l") -> edit.putLong(k, o.getLong("l"))
                    o.has("f") -> edit.putFloat(k, o.getDouble("f").toFloat())
                    o.has("set") -> o.getJSONArray("set").let { a -> edit.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            edit.commit()
            n++
        }
        return n
    }

    fun fileName(): String = "cloudstream-backup-" + java.time.LocalDate.now() + ".json"
}
