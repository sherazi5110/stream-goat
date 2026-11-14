package io.github.sherazi5110.cloudstream.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.Backup
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.PluginManager
import io.github.sherazi5110.cloudstream.ProviderTest
import io.github.sherazi5110.cloudstream.RepositoryManager
import io.github.sherazi5110.cloudstream.Settings
import io.github.sherazi5110.cloudstream.SiteOverrides
import io.github.sherazi5110.cloudstream.StremioApi
import io.github.sherazi5110.cloudstream.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val languageNames = mapOf(
    "en" to "English", "hi" to "Hindi", "te" to "Telugu", "ta" to "Tamil", "ml" to "Malayalam", "bn" to "Bengali", "ur" to "Urdu",
    "es" to "Spanish", "mx" to "Spanish (Mexico)", "pt" to "Portuguese", "fr" to "French", "de" to "German", "it" to "Italian",
    "ar" to "Arabic", "tr" to "Turkish", "ru" to "Russian", "pl" to "Polish", "id" to "Indonesian", "vi" to "Vietnamese",
    "th" to "Thai", "ko" to "Korean", "ja" to "Japanese", "zh" to "Chinese", "tl" to "Filipino", "fa" to "Persian", "uni" to "Multi-language",
)

fun languageName(code: String) = languageNames[code.lowercase()] ?: code.uppercase()

/** Extension languages, media types, extension updates and the extension test (Providers in the official app). */
@Composable
internal fun ProvidersSection() {
    var langs by remember { mutableStateOf(Settings.extensionLanguages) }
    val available = remember {
        (Engine.allProviders().filter { it !is StremioApi }.mapNotNull { (it.lang as String?)?.trim()?.lowercase()?.takeIf { l -> l.isNotEmpty() } } + listOf("en", "hi")).distinct().sortedBy { languageName(it) }
    }
    Heading("Extension languages")
    Hint("Only extensions in these languages show on Home and in Search. None picked = all languages.")
    ChoiceChips(listOf("") + available, { if (it.isEmpty()) "All languages" else languageName(it) },
        { if (it.isEmpty()) langs.isEmpty() else it in langs }) { code ->
        langs = if (code.isEmpty()) emptySet() else if (code in langs) langs - code else langs + code
        Settings.extensionLanguages = langs
        PluginManager.bumpVersion()
    }

    var types by remember { mutableStateOf(Settings.mediaTypes) }
    val typeOptions = listOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary, TvType.Live)
    Heading("Preferred media")
    Hint("Only extensions that offer these types show. None picked = everything.")
    ChoiceChips(listOf<TvType?>(null) + typeOptions, { it?.let(::typeLabel) ?: "Everything" },
        { if (it == null) types.isEmpty() else it.name in types }) { t ->
        types = if (t == null) emptySet() else if (t.name in types) types - t.name else types + t.name
        Settings.mediaTypes = types
        PluginManager.bumpVersion()
    }

    Heading("Extension updates")
    var autoUpdate by remember { mutableStateOf(Settings.autoUpdateExtensions) }
    SwitchRow("Update extensions automatically", "When the app starts.", autoUpdate) { autoUpdate = it; Settings.autoUpdateExtensions = it }
    var autoInstall by remember { mutableStateOf(Settings.autoInstallExtensions) }
    SwitchRow("Install new extensions automatically", "Every extension of your repositories that isn't installed yet (in your languages), when the app starts.",
        autoInstall) { autoInstall = it; Settings.autoInstallExtensions = it }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        PillButton(if (busy) "Updating…" else "Update extensions now", Icons.Filled.Refresh, primary = false, compact = true) {
            if (busy) return@PillButton
            busy = true
            scope.launch {
                val updated = withContext(Dispatchers.IO) { PluginManager.updateAll() }
                busy = false
                App.toast(if (updated.isEmpty()) "Everything is up to date" else "Updated ${updated.joinToString()}")
            }
        }
        PillButton("Test all extensions", Icons.Filled.PlayArrow, primary = false, compact = true) { Nav.open(Screen.ProviderTests) }
    }
}

private fun typeLabel(t: TvType) = when (t) {
    TvType.Movie -> "Movies"; TvType.TvSeries -> "TV series"; TvType.Anime -> "Anime"; TvType.AsianDrama -> "Asian dramas"
    TvType.Cartoon -> "Cartoons"; TvType.Documentary -> "Documentaries"; TvType.Live -> "Live"; else -> t.name
}

/** Downloads, site addresses, GitHub proxy, battery (General in the official app). */
@Composable
internal fun GeneralSection() {
    val context = LocalContext.current
    Heading("Downloads")
    var parallel by remember { mutableIntStateOf(Settings.parallelDownloads) }
    Hint("Downloads at the same time.")
    ChoiceChips((1..5).toList(), { it.toString() }, { it == parallel }) { parallel = it; Settings.parallelDownloads = it }
    Heading("Connections per download")
    var connections by remember { mutableIntStateOf(Settings.downloadConnections) }
    ChoiceChips(listOf(1, 2, 4, 8, 12, 16), { it.toString() }, { it == connections }) { connections = it; Settings.downloadConnections = it }
    Hint("Like a download manager (IDM): a file is fetched in 4 MB pieces, or a stream in segments, this many at the same time. " +
        "More is usually faster; some sites limit connections (then try 4).")
    var ask by remember { mutableStateOf(Settings.downloadAsk) }
    SwitchRow("Ask which source and quality", "When you press Download, choose the source and, for streams, the quality. Off: the best one is picked.", ask) { ask = it; Settings.downloadAsk = it }
    Hint("Saved in the app's own folder (Android/data/io.github.sherazi5110.cloudstream/files/Movies/Stream Goat).")
    if (Build.VERSION.SDK_INT >= 23) PillButton("Allow running in the background", Icons.Filled.BatteryFull, primary = false, compact = true,
        modifier = Modifier.padding(top = 6.dp)) {
        runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { App.toast("Open Android Settings → Apps → Stream Goat → Battery → Unrestricted") }
    }

    Heading("Network blocks")
    var proxy by remember { mutableStateOf(RepositoryManager.useJsDelivr) }
    SwitchRow("GitHub proxy", "Load repositories and extensions through jsDelivr when GitHub is blocked. Updates can arrive a few days late.", proxy) {
        proxy = it; RepositoryManager.useJsDelivr = it
    }
    Hint("DNS over HTTPS is under Network.")

    Heading("Site addresses (clone site)")
    Hint("When a site moved to a new address before its extension was updated, give the new address here.")
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<MainAPI?>(null) }
    val apis = remember(version) { Engine.allProviders().filter { it !is StremioApi } }
    apis.forEach { api ->
        val custom = SiteOverrides.get(api)
        Row(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f) { editing = api }.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(api.name, style = MaterialTheme.typography.titleSmall)
                Text(api.mainUrl, style = MaterialTheme.typography.bodySmall, color = if (custom != null) Palette.accent else Palette.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (custom != null) Text("changed", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
        }
    }
    editing?.let { api ->
        var url by remember(api) { mutableStateOf(SiteOverrides.get(api) ?: api.mainUrl) }
        SimpleDialog("Address of ${api.name}", { editing = null }, confirm = "Save", onConfirm = {
            SiteOverrides.set(api, url); editing = null; version++
        }, extra = {
            PillButton("Original", null, primary = false, compact = true) { SiteOverrides.set(api, null); editing = null; version++ }
        }) {
            Hint("Original: ${SiteOverrides.original(api)}")
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** App updates and backup (Updates and Backup in the official app). */
@Composable
internal fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0" }

    Heading("App updates")
    var check by remember { mutableStateOf(Settings.checkUpdates) }
    SwitchRow("Check for updates", "Once a day, from this project's GitHub releases.", check) { check = it; Settings.checkUpdates = it }
    var checking by remember { mutableStateOf(false) }
    PillButton(if (checking) "Checking…" else "Check now (version $versionName)", Icons.Filled.SystemUpdate, primary = false, compact = true,
        modifier = Modifier.padding(top = 6.dp)) {
        if (checking) return@PillButton
        checking = true
        scope.launch {
            val r = runCatching { Updates.check(versionName) }
            checking = false
            r.onSuccess { rel -> if (rel == null) App.toast("You have the latest version") else UpdateState.available = rel }
                .onFailure { App.toast("Couldn't check: ${it.message}") }
        }
    }

    CloudBackupSection()

    Heading("Backup file")
    Hint("Settings, profiles, My list, history and progress, repositories, the list of extensions and their settings, Live TV " +
        "sources and Stremio add-ons, in one file. It also works with the Linux app. Downloads and extension files aren't in it.")
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export().toByteArray()) } }
            }
            App.toast(r.fold({ "Backup saved" }, { "Couldn't save: ${it.message}" }))
        }
    }
    var restored by remember { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Backup.import(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
            }
            r.onSuccess {
                App.toast("Restored. Downloading the extensions…")
                val got = withContext(Dispatchers.IO) { runCatching { PluginManager.installMissing(includeNew = false) }.getOrDefault(emptyList()) }
                App.toast(if (got.isEmpty()) "Restored" else "Restored, with ${got.size} extension(s)")
                restored = true
            }.onFailure { App.toast("Couldn't restore: ${it.message}") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        PillButton("Back up", Icons.Filled.Backup, compact = true) { save.launch(Backup.fileName()) }
        PillButton("Restore", Icons.Filled.Restore, primary = false, compact = true) { open.launch(arrayOf("application/json", "text/plain", "*/*")) }
    }
    if (restored) {
        Hint("Restart the app to finish.")
        PillButton("Restart now", Icons.Filled.Refresh, compact = true) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }
}

/** A newer release found by the update check: shown as a dialog over the app. */
object UpdateState {
    var available by mutableStateOf<Updates.Release?>(null)
}

@Composable
fun UpdateDialog() {
    val rel = UpdateState.available ?: return
    val context = LocalContext.current
    SimpleDialog("Version ${rel.version} is available", { UpdateState.available = null }, confirm = "Download", onConfirm = {
        UpdateState.available = null
        com.lagradost.cloudstream3.CloudStreamApp.openBrowser(rel.url, context.findActivity())
    }) {
        Hint("The release page opens; download the .apk for your device and install it over this one. Your data is kept.")
    }
}

/** "Test all extensions": home, search, details and sources for every extension, a few at a time. */
@Composable
fun ProviderTestsScreen() {
    data class Row(val api: MainAPI, var state: Int = 0, var detail: String = "Waiting") // 0 waiting, 1 running, 2 passed, 3 failed
    val rows = remember { mutableStateListOf<Row>().apply { addAll(Engine.providers().filter { it !is StremioApi }.map { Row(it) }) } }
    var tick by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun start() {
        rows.forEach { it.state = 0; it.detail = "Waiting" }
        tick++
        job = scope.launch {
            val gate = Semaphore(3)
            rows.map { r ->
                launch {
                    gate.withPermit {
                        r.state = 1; r.detail = "Testing…"; tick++
                        var failed: String? = null
                        var last = ""
                        runCatching {
                            ProviderTest.run(r.api).collect { step ->
                                last = step.name + ": " + step.detail
                                if (!step.ok && failed == null) failed = last
                            }
                        }.onFailure { failed = it.message ?: it.toString() }
                        r.state = if (failed == null) 2 else 3
                        r.detail = failed ?: last.ifBlank { "Passed" }
                        tick++
                    }
                }
            }.forEach { it.join() }
            job = null
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val done = rows.count { it.state >= 2 }
    Column(Modifier.fillMaxSize()) {
        BackBar("Test all extensions")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("$done / ${rows.size} · passed ${rows.count { it.state == 2 }} · failed ${rows.count { it.state == 3 }}",
                style = MaterialTheme.typography.titleMedium)
            if (rows.isNotEmpty()) LinearProgressIndicator(progress = { done.toFloat() / rows.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = Palette.accent, trackColor = Palette.surfaceHigh)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (job != null) PillButton("Stop", Icons.Filled.Stop, primary = false, compact = true) { job?.cancel(); job = null }
                else PillButton(if (done == 0) "Start" else "Run again", Icons.Filled.PlayArrow, compact = true) { start() }
            }
            Hint("Each extension: home page, a search, a title page and its sources, like when you use it. Developers' tool: failures often mean the site is down or moved.")
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.api.name }) { r ->
                val t = tick
                Row(Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (r.state) {
                        2 -> Icon(Icons.Filled.CheckCircle, null, tint = Palette.watched)
                        3 -> Icon(Icons.Filled.Error, null, tint = Color(0xFFFF6B6B))
                        1 -> Spinner(Modifier.size(24.dp))
                        else -> Box(Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(r.api.name + "  ·  " + languageName((r.api.lang as String?) ?: "?"), style = MaterialTheme.typography.titleSmall)
                        Text(r.detail + if (t < 0) "" else "", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (r.state == 3) Color(0xFFFF6B6B) else Palette.muted)
                    }
                }
            }
        }
    }
}
