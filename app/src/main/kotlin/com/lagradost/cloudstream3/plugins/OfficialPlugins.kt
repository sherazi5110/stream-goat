package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.lagradost.cloudstream3.linux.PluginManager as AppPlugins
import com.lagradost.cloudstream3.linux.RepositoryManager as AppRepos

// The official app's plugin classes, with its names and signatures, for extensions that manage other
// extensions (Ultima, StreamPlay, StremioX). They work on Stream Goat's own extension manager (as on Android).

@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginData(
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("url") val url: String?,
    @JsonProperty("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") val filePath: String,
    @JsonProperty("version") val version: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Repository(
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String?,
    @JsonProperty("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") val pluginLists: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SitePlugin(
    @JsonProperty("url") val url: String,
    @JsonProperty("status") val status: Int,
    @JsonProperty("version") val version: Int,
    @JsonProperty("apiVersion") val apiVersion: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("authors") val authors: List<String>,
    @JsonProperty("description") val description: String?,
    @JsonProperty("repositoryUrl") val repositoryUrl: String?,
    @JsonProperty("tvTypes") val tvTypes: List<String>?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("fileSize") val fileSize: Long?,
    @JsonProperty("fileHash") val fileHash: String?,
)

data class PluginWrapper(
    @JsonProperty("repository") val repository: Repository,
    @JsonProperty("repositoryData") val repositoryData: RepositoryData,
    @JsonProperty("plugin") val plugin: SitePlugin,
)

object PluginManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    /** Installs an extension from its link, like the official app (AutoExtensions, StreamCenter install others). */
    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean,
    ): Boolean = AppPlugins.installFile(pluginUrl, pluginHash, internalName, AppRepos.pluginPath(internalName, repositoryUrl, "cs3"), repositoryUrl, loadPlugin)

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
    ): Boolean = AppPlugins.installFile(pluginUrl, pluginHash, internalName, file, null, loadPlugin)

    suspend fun deletePlugin(file: File): Boolean = AppPlugins.deleteFile(file)

    /** Loaded extensions by file path, like the official app. */
    val plugins: MutableMap<String, BasePlugin>
        get() = AppPlugins.loaded.mapValues { it.value.instance }.toMutableMap()

    fun getPluginsOnline(): Array<PluginData> =
        AppPlugins.installed().filter { it.isOnline }.map { PluginData(it.internalName, it.url, true, it.filePath, it.version) }.toTypedArray()

    fun getPluginsLocal(): Array<PluginData> =
        AppPlugins.installed().filter { !it.isOnline }.map { PluginData(it.internalName, it.url, false, it.filePath, it.version) }.toTypedArray()

    fun unloadPlugin(absolutePath: String) {
        AppPlugins.unloadPlugin(absolutePath)
        AppPlugins.bumpVersion()
    }

    fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File =
        AppRepos.pluginPath(internalName, repositoryUrl, "cs3")

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean = withContext(Dispatchers.IO) {
        val data = AppPlugins.installed().firstOrNull { it.internalName.replace("provider", "", ignoreCase = true) == apiName }
            ?: return@withContext false
        AppPlugins.loadPlugin(File(data.filePath), data).isSuccess.also { if (it) AppPlugins.bumpVersion() }
    }
}

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    /** Repositories the official app suggests (it downloads the list); extensions read it. */
    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {
        com.lagradost.cloudstream3.CloudStreamApp.getKey<Array<RepositoryData>>("PREBUILT_REPOSITORIES") ?: emptyArray()
    }

    fun convertRawGitUrl(url: String): String = AppRepos.convertRawGitUrl(url)

    suspend fun parseRepository(url: String): Repository? = AppRepos.parseRepository(url)?.let {
        Repository(it.iconUrl, it.name, it.description, it.manifestVersion, it.pluginLists)
    }

    /**
     * Adds a repository like the official app: stored as given, nothing fetched (MegaRepo and StreamCenter call this
     * for each of their repositories on every start). Screens refresh only if it is new.
     */
    suspend fun addRepository(repository: RepositoryData) {
        val added = AppRepos.addRepositoryData(
            com.lagradost.cloudstream3.linux.RepositoryData(repository.iconUrl, repository.name, repository.url, repository.description)
        )
        if (added) AppPlugins.bumpVersion()
    }

    suspend fun removeRepository(context: Context, repository: RepositoryData) {
        AppRepos.removeRepository(repository.url)
        AppPlugins.bumpVersion()
    }

    fun getRepositories(): Array<RepositoryData> =
        AppRepos.getRepositories().map { RepositoryData(it.iconUrl, it.name, it.url) }.toTypedArray()

    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? {
        val mine = AppRepos.getRepositories().firstOrNull { it.url == repositoryData.url } ?: return null
        return AppRepos.getRepoPlugins(mine)?.map { w ->
            val p = w.plugin
            PluginWrapper(
                Repository(w.repository.iconUrl, w.repository.name, w.repository.description, w.repository.manifestVersion, w.repository.pluginLists),
                repositoryData,
                SitePlugin(p.url, p.status, p.version, p.apiVersion, p.name, p.internalName, p.authors, p.description, p.repositoryUrl,
                    p.tvTypes, p.language, p.iconUrl, p.fileSize, p.fileHash),
            )
        }
    }
}
