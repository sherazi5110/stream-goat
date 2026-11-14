package io.github.sherazi5110.cloudstream

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide state: the context extensions receive, the network setup and extension loading. */
object App {
    lateinit var context: Context
        private set

    /** Work that outlives a screen (loading extensions, installs). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Android TV (remote) or phone/tablet (touch): decides the layout. Read once per launch. */
    val isTv: Boolean by lazy {
        when (Settings.layout) {
            "tv" -> true
            "phone" -> false
            else -> detectTv()
        }
    }

    /** A network with internet access is connected (asked only after something failed to load). */
    fun online(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun detectTv(): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun init(context: Context) {
        this.context = context
        CrashReport.install()
        Network.init()
        // NewPipe Extractor, as the official app sets it up (YouTube extensions call it directly).
        runCatching { org.schabi.newpipe.extractor.NewPipe.init(com.lagradost.cloudstream3.DownloaderTestImpl.getInstance()) }
        io.github.sherazi5110.cloudstream.ui.ThemeState.load()
        // A profile switch changes the look, the library and which extensions show.
        Profiles.onChange {
            io.github.sherazi5110.cloudstream.ui.ThemeState.load()
            io.github.sherazi5110.cloudstream.ui.ProfileUi.refresh()
            PluginManager.bumpVersion()
        }
        StremioAddons.onChange { PluginManager.bumpVersion() }
        Downloads.onChange { DownloadService.ensure(context) }
        // Extensions that add or remove providers (Ultima, StreamPlay…) fire the official app's event: refresh.
        com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent += { PluginManager.bumpVersion() }
        scope.launch {
            // Like the official app: Home's extension first (mainPluginsLoadedEvent), then the rest.
            val failures = PluginManager.loadAll(onHomeLoaded = { ok ->
                runCatching { com.lagradost.cloudstream3.MainActivity.mainPluginsLoadedEvent.invoke(ok) }
            })
            if (failures.isNotEmpty()) Log.w("plugins", "Failed: ${failures.keys}")
            // Extensions waiting for every extension to load run now.
            runCatching { com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent.invoke(true) }
            // Carry on with downloads that were running when the app closed.
            runCatching { Downloads.init() }
            // Google Drive backup: when something changed (every 15 minutes, and when the app is put away).
            CloudBackup.startAuto()
            // Updates and the update check wait until the first screens have settled.
            kotlinx.coroutines.delay(20_000)
            startupChecks()
        }
    }

    /** Extension updates / new extensions and the app update check, like the official app at start. */
    private suspend fun startupChecks() {
        if (Settings.autoUpdateExtensions) runCatching { PluginManager.updateAll() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { toast("Updated ${it.joinToString()}") }
        if (Settings.autoInstallExtensions) runCatching { PluginManager.installMissing(includeNew = true) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { toast("Installed ${it.size} new extension(s)") }
        if (Settings.checkUpdates && System.currentTimeMillis() - Updates.lastCheck > 20 * 3600_000L) {
            Updates.lastCheck = System.currentTimeMillis()
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
            runCatching { Updates.check(version) }.getOrNull()?.let { io.github.sherazi5110.cloudstream.ui.UpdateState.available = it }
        }
    }

    /** Shows a short message from anywhere, like extensions do. */
    fun toast(message: String) = CommonActivity.showToast(message)
}
