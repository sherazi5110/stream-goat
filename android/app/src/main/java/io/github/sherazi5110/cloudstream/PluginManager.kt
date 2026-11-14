package io.github.sherazi5110.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.extractorApis
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import dalvik.system.PathClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Same fields as upstream PluginData, plus the repository it came from. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
    val repositoryUrl: String? = null,
    val name: String? = null,
)

class LoadedPlugin(
    val data: PluginData,
    val manifest: BasePlugin.Manifest,
    val instance: BasePlugin,
    val loader: ClassLoader,
) {
    val providers: List<MainAPI> get() = APIHolder.allProviders.filter { it.sourcePlugin == instance.filename }
    val extractorCount: Int get() = extractorApis.count { it.sourcePlugin == instance.filename }
}

data class LoadProgress(val done: Int, val total: Int) {
    val running: Boolean get() = done < total
}

object PluginManager {
    private const val PLUGINS_KEY = "PLUGINS_KEY"
    private val prefs get() = App.context.getSharedPrefs()

    val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    val failures = ConcurrentHashMap<String, Throwable>()

    private val _progress = MutableStateFlow(LoadProgress(0, 0))
    val progress: StateFlow<LoadProgress> = _progress

    /** Bumped whenever the set of providers changes, so screens can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private fun changed() {
        _version.value++
    }

    /** For other provider sources (Stremio add-ons, profile switches) to refresh screens too. */
    fun bumpVersion() = changed()

    /**
     * Loading (class verification, each extension's setup) is CPU work: as many at once as the device has cores,
     * at low priority, so the screen stays responsive on TV boxes (it once ran 64 at once).
     */
    private suspend fun loadOne(data: PluginData) = withContext(loading) {
        runCatching { android.os.Process.setThreadPriority(LOAD_PRIORITY) }
        try {
            val file = File(data.filePath)
            if (!file.exists()) failures[data.internalName] = IllegalStateException("Missing file ${data.filePath}")
            else loadPlugin(file, data)
        } finally {
            runCatching { android.os.Process.setThreadPriority(Engine.EXTENSION_PRIORITY) }
        }
        _progress.value = _progress.value.let { it.copy(done = it.done + 1) }
    }

    /** Extensions loading at start: one per core (2 to 4), see loadAll. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val loading = Engine.dispatcher.limitedParallelism(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))

    /** Below the screen's threads; Android 10+ keeps such threads in the foreground group, older ones get 5. */
    private val LOAD_PRIORITY = if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Process.THREAD_PRIORITY_BACKGROUND else 5

    /** register* calls in Plugin.load mutate global lists; translation and class loading stay parallel. */
    private val registerLock = Any()

    fun installed(): List<PluginData> =
        prefs.getString(PLUGINS_KEY, null)?.let { mapper.readValue<List<PluginData>>(it) } ?: emptyList()

    private fun saveInstalled(list: List<PluginData>) = synchronized(this) {
        prefs.edit().putString(PLUGINS_KEY, mapper.writeValueAsString(list)).apply()
    }

    private fun record(data: PluginData) = synchronized(this) {
        saveInstalled(installed().filter { it.internalName != data.internalName } + data)
    }

    /**
     * Loads every installed plugin, in the official app's order (MainActivity): first the extension Home shows,
     * so Home appears at once ([onHomeLoaded], the official mainPluginsLoadedEvent), then all the others.
     */
    suspend fun loadAll(onHomeLoaded: (Boolean) -> Unit = {}): Map<String, Throwable> = coroutineScope {
        val all = installed()
        _progress.value = LoadProgress(0, all.size)
        val home = Settings.homePluginFile?.let { path -> all.find { it.filePath == path } }
        if (home != null) {
            loadOne(home)
            changed()
        }
        onHomeLoaded(home != null && loaded.containsKey(File(home.filePath).absolutePath))
        all.filter { it !== home }.map { data -> async(loading) { loadOne(data) } }.awaitAll()
        Log.i("plugins", "loaded ${loaded.size}/${all.size} extensions")
        changed()
        failures
    }

    /**
     * Port of upstream PluginManager.loadPlugin (app/.../plugins/PluginManager.kt): a .cs3 is a dex file
     * with a manifest.json, loaded natively with its own class loader whose parent is the app,
     * so the extension links against this app's copy of the CloudStream library.
     */
    fun loadPlugin(file: File, data: PluginData): Result<LoadedPlugin> = runCatching {
        loaded[file.absolutePath]?.let { return@runCatching it }
        // Android 14+ refuses to load writable dex files.
        if (!file.setReadOnly()) Log.w("plugins", "Couldn't make ${file.name} read-only")

        val loader = PathClassLoader(file.absolutePath, App.context.classLoader)
        val manifest = loader.getResourceAsStream("manifest.json")?.use {
            mapper.readValue<BasePlugin.Manifest>(it.readBytes())
        } ?: error("No manifest.json in ${file.name}")
        val className = manifest.pluginClassName ?: error("No plugin class in ${file.name}")

        val instance = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
        instance.filename = file.absolutePath
        if (manifest.requiresResources) (instance as? Plugin)?.resources = resourcesOf(file)

        synchronized(registerLock) {
            if (instance is Plugin) instance.load(App.context) else instance.load()
        }

        LoadedPlugin(data.copy(version = manifest.version ?: data.version), manifest, instance, loader).also {
            loaded[file.absolutePath] = it
            failures.remove(data.internalName)
            changed()
        }
    }.onFailure {
        failures[data.internalName] = it
        Log.e("plugins", "Failed to load ${file.name}", it)
    }

    /** The extension's own resources (strings, layouts of its settings screen), as upstream loads them. */
    @Suppress("DEPRECATION")
    private fun resourcesOf(file: File): Resources? = runCatching {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, file.absolutePath)
        val res = App.context.resources
        Resources(assets, res.displayMetrics, res.configuration)
    }.getOrNull()

    /** For the official PluginManager.downloadPlugin: extensions that install other extensions. */
    suspend fun installFile(url: String, hash: String?, internalName: String, target: File, repositoryUrl: String?, load: Boolean): Boolean =
        runCatching {
            unloadPlugin(target.absolutePath)
            RepositoryManager.download(url, target, hash)
            val previous = installed().find { it.internalName == internalName }
            val data = PluginData(internalName, url, true, target.absolutePath, previous?.version ?: 0, repositoryUrl ?: previous?.repositoryUrl, previous?.name ?: internalName)
            record(data)
            if (load) loadPlugin(target, data).getOrThrow()
            changed()
        }.isSuccess

    /** For the official PluginManager.deletePlugin. */
    fun deleteFile(file: File): Boolean {
        unloadPlugin(file.absolutePath)
        saveInstalled(installed().filter { File(it.filePath).absolutePath != file.absolutePath })
        val ok = file.delete() || !file.exists()
        changed()
        return ok
    }

    /** Port of upstream unloadPlugin: removes everything the plugin registered. */
    fun unloadPlugin(absolutePath: String) {
        val plugin = loaded.remove(absolutePath) ?: return
        runCatching { plugin.instance.beforeUnload() }
        val owner = plugin.instance.filename
        APIHolder.apis.filter { it.sourcePlugin == owner }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == owner } }
        extractorApis.withLock { extractorApis.removeAll { it.sourcePlugin == owner } }
        com.lagradost.cloudstream3.actions.VideoClickActionHolder.removeFrom(owner)
        changed()
    }

    /** Downloads the .cs3 (Dalvik bytecode; the cross-platform .jar is for desktop JVMs) and loads it. */
    suspend fun install(wrapper: PluginWrapper): Result<LoadedPlugin> {
        val p = wrapper.plugin
        val target = RepositoryManager.pluginPath(p.internalName, wrapper.repositoryData.url, "cs3")
        installed().find { it.internalName == p.internalName }?.let { uninstall(it.internalName) }
        return runCatching {
            RepositoryManager.download(p.url, target, p.fileHash)
        }.mapCatching { file ->
            val data = PluginData(p.internalName, p.url, true, file.absolutePath, p.version, wrapper.repositoryData.url, p.name)
            record(data)
            loadPlugin(file, data).getOrThrow()
        }
    }

    /** Installs a local .cs3 file (e.g. picked from Downloads), like the official app's local plugins. */
    fun sideload(name: String, input: java.io.InputStream): Result<LoadedPlugin> = runCatching {
        require(name.endsWith(".cs3")) { "Expected a .cs3 file" }
        val target = File(RepositoryManager.extensionsDir, "local/$name")
        target.parentFile!!.mkdirs()
        unloadPlugin(target.absolutePath)
        target.delete()
        target.outputStream().use { input.copyTo(it) }
        val base = name.removeSuffix(".cs3")
        val data = PluginData(base, null, false, target.absolutePath, 0, null, base)
        record(data)
        loadPlugin(target, data).getOrThrow()
    }

    fun uninstall(internalName: String) {
        val data = installed().find { it.internalName == internalName } ?: return
        unloadPlugin(File(data.filePath).absolutePath)
        File(data.filePath).delete()
        saveInstalled(installed().filter { it.internalName != internalName })
    }

    /**
     * After a restore (or with "Install new extensions automatically" on): downloads installed extensions whose
     * file is missing, and with [includeNew] every extension of the added repositories that isn't installed yet
     * (not broken ones, not adult ones unless allowed, only the chosen languages). Returns their names.
     */
    suspend fun installMissing(includeNew: Boolean): List<String> {
        val have = installed().associateBy { it.internalName }
        val done = mutableListOf<String>()
        val langs = Settings.extensionLanguages
        for (repo in RepositoryManager.getRepositories()) {
            val available = RepositoryManager.getRepoPlugins(repo) ?: continue
            for (w in available) {
                val p = w.plugin
                val local = have[p.internalName]
                val missingFile = local != null && !File(local.filePath).exists()
                val wanted = local == null && includeNew && p.status != 0 &&
                    (p.tvTypes.orEmpty().none { it.equals("NSFW", true) } || ContentFilter.nsfwAllowed) &&
                    (langs.isEmpty() || p.language == null || p.language!!.lowercase() in langs)
                if ((missingFile || wanted) && install(w).isSuccess) done += p.name
            }
        }
        return done
    }

    /** Returns the plugins whose repository advertises a higher version, and installs them. */
    suspend fun updateAll(): List<String> {
        val byRepo = installed().filter { it.isOnline && it.repositoryUrl != null }.groupBy { it.repositoryUrl!! }
        val updated = mutableListOf<String>()
        for ((repoUrl, plugins) in byRepo) {
            val repoData = RepositoryManager.getRepositories().find { it.url == repoUrl } ?: continue
            val available = RepositoryManager.getRepoPlugins(repoData) ?: continue
            for (local in plugins) {
                val remote = available.find { it.plugin.internalName == local.internalName } ?: continue
                if (remote.plugin.version > local.version && install(remote).isSuccess) updated += local.internalName
            }
        }
        return updated
    }
}
