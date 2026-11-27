package io.github.sherazi5110.cloudstream.ui

import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.DnsProvider
import io.github.sherazi5110.cloudstream.LiveTv
import io.github.sherazi5110.cloudstream.Metadata
import io.github.sherazi5110.cloudstream.Network
import io.github.sherazi5110.cloudstream.PluginManager
import io.github.sherazi5110.cloudstream.Profiles
import io.github.sherazi5110.cloudstream.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Section(val title: String, val text: String, val icon: ImageVector) {
    EXTENSIONS("Extensions", "Repositories, Stremio add-ons", Icons.Filled.Extension),
    PROVIDERS("Providers", "Languages, media types, extension updates, test all", Icons.Filled.Language),
    GENERAL("General", "Downloads, site addresses, GitHub proxy", Icons.Filled.Tune),
    BACKUP("Updates and backup", "App updates, back up and restore", Icons.Filled.Backup),
    PROFILES("Profiles", "Who's watching, kids, adult content", Icons.Filled.People),
    APPEARANCE("Appearance", "Theme, accent colour, layout", Icons.Filled.Palette),
    PLAYER("Player", "Sources, quality, subtitles, audio", Icons.Filled.PlayCircle),
    LIVETV("Live TV", "M3U playlists, Xtream Codes, guides", Icons.Filled.LiveTv),
    WETRAKR("WeTrakr sync", "Mark what you watch on WeTrakr", Icons.Filled.Sync),
    HIDDEN("Hidden content", "Titles you hid, with Restore", Icons.Filled.VisibilityOff),
    METADATA("Metadata & integrations", "TMDB, IMDb ratings, AniList", Icons.Filled.Star),
    NETWORK("Network", "DNS over HTTPS, cookies", Icons.Filled.Public),
    LOGS("App logs", "What the app recorded, to copy or share", Icons.Filled.BugReport),
    ABOUT("About", "Version, source code", Icons.Filled.Info),
}

/** Settings (the "More" tab on phones): sections in a list; on wide screens the section shows beside it. */
@Composable
fun SettingsScreen() {
    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Section.PROFILES) }
    val wide = isWide
    // Back inside a section goes to the section list first (it used to leave Settings for Home at once).
    val selectedRow = remember { androidx.compose.ui.focus.FocusRequester() }
    val content = backToHeader(selectedRow)
    Row(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.then(if (wide) Modifier.width(300.dp) else Modifier.fillMaxWidth()).fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalBottomInset.current), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (!wide) ProfileButton(32.dp)
            }
            Section.entries.forEach { s ->
                val on = wide && s == selected
                Row(
                    Modifier.fillMaxWidth().then(if (on) Modifier.focusRequester(selectedRow) else Modifier).card(RoundedCornerShape(10.dp), focusScale = 1.02f) {
                        when {
                            s == Section.EXTENSIONS -> Nav.select(Tab.EXTENSIONS)
                            wide -> selected = s
                            else -> Nav.open(Screen.SettingsPage(s.name))
                        }
                    }.background(if (on) Palette.accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, tint = if (on) Palette.accent else Palette.muted)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        if (!wide) Text(s.text, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!wide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.muted)
                }
            }
        }
        if (wide) Column(content.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp)) {
            Text(selected.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            SectionContent(selected)
        }
    }
}

/** Portrait: one section on its own page. */
@Composable
fun SettingsPageScreen(screen: Screen.SettingsPage) {
    val section = Section.valueOf(screen.section)
    Column(Modifier.fillMaxSize()) {
        BackBar(section.title)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) { SectionContent(section) }
    }
}

@Composable
private fun SectionContent(s: Section) {
    when (s) {
        Section.EXTENSIONS -> Unit
        Section.PROFILES -> ProfilesSection()
        Section.HIDDEN -> HiddenTitlesSection()
        Section.APPEARANCE -> AppearanceSection()
        Section.PLAYER -> PlayerSection()
        Section.LIVETV -> LiveTvSection()
        Section.WETRAKR -> WeTrakrSection()
        Section.METADATA -> MetadataSection()
        Section.NETWORK -> NetworkSection()
        Section.LOGS -> AppLogsSection()
        Section.ABOUT -> AboutSection()
        Section.PROVIDERS -> ProvidersSection()
        Section.GENERAL -> GeneralSection()
        Section.BACKUP -> BackupSection()
    }
}

@Composable
internal fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.accent,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))

// ---------------------------------------------------------------- sections

@Composable
private fun ProfilesSection() {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = Profiles.onChange { version++ }; onDispose { off() } }
    val all = remember(version) { Profiles.all() }
    all.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).card(RoundedCornerShape(10.dp), focusScale = 1.02f) { ProfileUi.editing = p }
            .background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.avatar, 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.name + if (p.id == ProfileUi.current.id) "  (you)" else "", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull("Kids".takeIf { p.kids }, "PIN".takeIf { p.pin != null }).joinToString(" · ").ifBlank { "Grown-up" }, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.Edit, "Edit", tint = Palette.muted)
        }
    }
    PillButton("Add profile", Icons.Filled.Add, Modifier.padding(top = 8.dp), compact = true) { ProfileUi.creating = true }
    var ask by remember { mutableStateOf(Profiles.askOnStart) }
    Heading("Start")
    SwitchRow("Ask \"Who's watching?\" at start", "Only with more than one profile.", ask) { ask = it; Profiles.askOnStart = it }
    if (!ProfileUi.current.kids) {
        Heading("Adult content")
        var nsfw by remember(version) { mutableStateOf(Settings.showNsfw) }
        var confirm by remember { mutableStateOf(false) }
        SwitchRow("Show adult (NSFW) content", "For ${ProfileUi.current.name} only. Like the official app, adult (NSFW) extensions are hidden until this is on.", nsfw) { on ->
            if (!on) { Settings.showNsfw = false; nsfw = false; PluginManager.bumpVersion() } else confirm = true
        }
        if (confirm) SimpleDialog("Show adult content?", { confirm = false }, confirm = "Turn on", onConfirm = {
            confirm = false
            val enable = { Settings.showNsfw = true; nsfw = true; PluginManager.bumpVersion() }
            if (Profiles.hasParentPin) ProfileUi.pin = ProfileUi.PinRequest("Parent PIN", "Enter the parent PIN to allow adult content.", Profiles::checkParentPin) { enable() }
            else enable()
        }) { Text("Adult extensions and titles will show on Home, in Search and in Extensions for this profile.", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun AppearanceSection() {
    var intro by remember { mutableStateOf(Settings.startupAnimation) }
    SwitchRow("Startup animation", "The short Stream Goat intro when the app opens (tap to skip).", intro) { intro = it; Settings.startupAnimation = it }
    Heading("Presets")
    ChoiceChips(ThemePreset.entries, { it.label }, { ThemeState.preset == it }) { ThemeState.apply(it) }
    Heading("Theme")
    ChoiceChips(ThemeMode.entries, { it.label }, { ThemeState.mode == it }) { ThemeState.changeMode(it) }
    Heading("Accent colour")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Accent.entries.forEach { a ->
            val on = ThemeState.accentHex.equals(a.hex, true)
            Box(Modifier.size(40.dp).card(CircleShape) { ThemeState.changeAccent(a.hex) }.background(colorOf(a.hex), CircleShape)
                .then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier))
        }
    }
    Heading("Logo")
    // The goat: a rainbow, or the accent colour.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(true to "Rainbow", false to "Theme colour").forEach { (rainbow, name) ->
            val on = ThemeState.logoRainbow == rainbow
            Column(Modifier.card(RoundedCornerShape(14.dp)) { ThemeState.changeLogo(rainbow) }
                .background(if (on) Palette.accent.copy(alpha = 0.22f) else Palette.surfaceHigh, RoundedCornerShape(14.dp))
                .then(if (on) Modifier.border(2.dp, Palette.accent, RoundedCornerShape(14.dp)) else Modifier)
                .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).background(Color.Black, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    GoatMark(Modifier.size(46.dp), rainbow = rainbow)
                }
                Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    Hint("Each profile has its own look. The start animation and the offline goat follow the logo.")
    Heading("Layout")
    var layout by remember { mutableStateOf(Settings.layout) }
    val auto = if (App.detectTv()) "TV" else "Phone"
    ChoiceChips(listOf("auto", "phone", "tv"), { when (it) { "tv" -> "TV (remote)"; "phone" -> "Phone (touch)"; else -> "Automatic ($auto)" } }, { it == layout }) {
        layout = it; Settings.layout = it
    }
    Hint("Phone/TV changes how the app reacts to touch and the remote; it applies when the app restarts. Landscape screens always get the side rail.")
    Heading("Home")
    var slider by remember { mutableStateOf(Settings.heroSlider) }
    Heading("Text size")
    ChoiceChips(listOf(0.85f, 1f, 1.15f, 1.3f), { when (it) { 0.85f -> "Small"; 1f -> "Default"; 1.15f -> "Large"; else -> "Largest" } },
        { it == TextSize.scale }) { TextSize.scale = it; Settings.textScale = it }
    SwitchRow("Banner slides through the top titles", null, slider) { slider = it; Settings.heroSlider = it }
    var spoiler by remember { mutableStateOf(Settings.antiSpoiler) }
    SwitchRow("Anti-spoiler", "Hide pictures and descriptions of episodes you haven't watched.", spoiler) { spoiler = it; Settings.antiSpoiler = it }
    Heading("Tabs")
    var yt by remember { mutableStateOf(io.github.sherazi5110.cloudstream.Settings.showYouTube) }
    SwitchRow("YouTube tab", "YouTube's TV app: made for Android TV and a remote, not for touch screens. Off hides the tab.", yt) {
        yt = it; io.github.sherazi5110.cloudstream.Settings.showYouTube = it
        if (!it && Nav.tab == Tab.YOUTUBE) Nav.select(Tab.HOME)
    }
    var live by remember { mutableStateOf(io.github.sherazi5110.cloudstream.Settings.showLiveTv) }
    SwitchRow("Live TV tab", "Off hides the tab (your sources and favourites stay).", live) {
        live = it; io.github.sherazi5110.cloudstream.Settings.showLiveTv = it
        if (!it && Nav.tab == Tab.LIVE) Nav.select(Tab.HOME)
    }
    var music by remember { mutableStateOf(io.github.sherazi5110.cloudstream.Settings.showMusic) }
    SwitchRow("Music tab", "Off hides the tab (your playlists and liked songs stay).", music) {
        music = it; io.github.sherazi5110.cloudstream.Settings.showMusic = it
        if (!it && Nav.tab == Tab.MUSIC) Nav.select(Tab.HOME)
    }

    Heading("Pictures")
    var lowRes by remember { mutableStateOf(Settings.lowResImages) }
    ChoiceChips(listOf<Boolean?>(null, true, false), { when (it) { null -> "Automatic"; true -> "Low resolution"; false -> "Full resolution" } }, { it == lowRes }) {
        lowRes = it; Settings.lowResImages = it; io.github.sherazi5110.cloudstream.Images.clearCache()
    }
    Hint("Low resolution loads smaller posters and backdrops and keeps them in less memory: smoother on old or low-memory phones and TVs, and less data." +
        if (io.github.sherazi5110.cloudstream.Images.lowMemoryDevice) " Automatic uses it on this device." else " Automatic uses it only on low-memory devices.")
    var cacheMb by remember { mutableStateOf(Settings.imageCacheMb) }
    Text("Picture cache on storage", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    ChoiceChips(listOf(0, 100, 250, 500, 1000), { if (it == 0) "None" else if (it >= 1000) "1 GB" else "$it MB" }, { it == cacheMb }) {
        cacheMb = it; Settings.imageCacheMb = it; App.toast("Applies when the app restarts")
    }
    PillButton("Clear picture cache", null, primary = false, compact = true) { io.github.sherazi5110.cloudstream.Images.clearCache(); App.toast("Picture cache cleared") }
}

@Composable
private fun PlayerSection() {
    Heading("When you press Play")
    var autoPlay by remember { mutableStateOf(Settings.autoPlay) }
    ChoiceChips(listOf(true, false), { if (it) "Start the best source automatically" else "Let me choose the source" }, { it == autoPlay }) {
        autoPlay = it; Settings.autoPlay = it
    }
    Heading("Player engine")
    Hint("Which player plays each kind of source. ExoPlayer is the official CloudStream app's player (extensions are made for it); " +
        "VLC plays almost anything. If one can't play a source, the other is tried before another source. You can also switch while watching (the player's engine button).")
    var ask by remember { mutableStateOf(Settings.askEngine) }
    SwitchRow("Ask every time", "When a video starts, choose which player plays it.", ask) { ask = it; Settings.askEngine = it }
    io.github.sherazi5110.cloudstream.player.PlayerSource.entries.forEach { src ->
        var pick by remember { mutableStateOf(Settings.engineFor(src.key)) }
        Text(src.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        ChoiceChips(io.github.sherazi5110.cloudstream.player.EngineKind.entries.map { it.name }, { n ->
            io.github.sherazi5110.cloudstream.player.EngineKind.valueOf(n).label }, { it == pick }) {
            pick = it; Settings.setEngineFor(src.key, it)
        }
    }
    Heading("Buffering")
    var start by remember { mutableStateOf(Settings.startBufferMs) }
    Text("Load before playing (and after a jump)", style = MaterialTheme.typography.bodyMedium)
    ChoiceChips(listOf(1000, 1500, 3000, 5000, 10000), { if (it < 1500) "1 s (fastest start)" else "${it / 1000.0}".removeSuffix(".0") + " s" }, { it == start }) {
        start = it; Settings.startBufferMs = it
    }
    var ahead by remember { mutableStateOf(Settings.bufferAheadSec) }
    Text("Keep loading ahead", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    ChoiceChips(listOf(30, 60, 180, 300, 600), { if (it < 60) "$it s" else "${it / 60} min" }, { it == ahead }) {
        ahead = it; Settings.bufferAheadSec = it
    }
    var mem by remember { mutableStateOf(Settings.bufferMemoryMb) }
    Text("Most memory for the buffer", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    ChoiceChips(listOf(0, 32, 64, 128, 256, 512), { if (it == 0) "Automatic" else "$it MB" }, { it == mem }) {
        mem = it; Settings.bufferMemoryMb = it
    }
    Hint("On slow internet, load more ahead (3 or 5 minutes) and start a little later: the video pauses less. On an old or " +
        "low-memory device keep the memory small; the time ahead is cut to what fits (a 4K stream takes about 3 MB a second). " +
        "If a video keeps stopping to load, the player suggests a lower quality or another source.")
    Hint(if (autoPlay) "The best source (closest to your preferred quality) starts by itself; Sources in the player lists the others."
        else "The sources list opens, with a search box and language and quality filters: pick the one you want.")
    Heading("Playback")
    var next by remember { mutableStateOf(Settings.autoPlayNext) }
    SwitchRow("Play the next episode automatically", "Counts down from the credits; Cancel stays on this episode", next) { next = it; Settings.autoPlayNext = it }
    var skipBtns by remember { mutableStateOf(Settings.skipButtons) }
    SwitchRow("Skip intro buttons (anime)", "Intro, recap and credits times from AniSkip", skipBtns) { skipBtns = it; Settings.skipButtons = it }
    var autoSkip by remember { mutableStateOf(Settings.autoSkip) }
    SwitchRow("Skip intros automatically (anime)", null, autoSkip) { autoSkip = it; Settings.autoSkip = it }
    Heading("Preferred quality")
    var quality by remember { mutableStateOf(Settings.preferredQuality) }
    ChoiceChips(listOf(0, 2160, 1080, 720, 480), { if (it == 0) "Best" else "${it}p" }, { it == quality }) { quality = it; Settings.preferredQuality = it }
    var prepare by remember { mutableStateOf(Settings.prepareSources) }
    SwitchRow("Find sources before you press Play", "When a title's page opens, its sources are looked up in the background, so Play starts sooner. Found sources are kept for 20 minutes.", prepare) { prepare = it; Settings.prepareSources = it }
    Heading("HDR")
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hdrTypes = remember { io.github.sherazi5110.cloudstream.Picture.screenHdrTypes(ctx as? android.app.Activity) }
    var hdr by remember { mutableStateOf(Settings.hdrOutput) }
    ChoiceChips(listOf("AUTO", "ON", "OFF"), { mapOf("AUTO" to "Auto", "ON" to "Always", "OFF" to "Off")[it]!! }, { it == hdr }) { hdr = it; Settings.hdrOutput = it }
    Hint(if (hdrTypes.isEmpty()) "This screen doesn't report HDR support, so HDR video is shown in standard colour." else
        "This screen shows ${hdrTypes.distinct().joinToString(", ")}. Auto: sources marked HDR or Dolby Vision play in HDR (pinch-to-zoom is off for them; the zoom modes still work).")
    Heading("Wait for sources")
    var timeout by remember { mutableStateOf(Settings.sourcesTimeoutSeconds) }
    ChoiceChips(listOf(30, 60, 90, 120), { "$it s" }, { it == timeout }) { timeout = it; Settings.sourcesTimeoutSeconds = it }
    Heading("Audio language")
    var audio by remember { mutableStateOf(Settings.audioLanguage) }
    LanguageChips(audio) { audio = it; Settings.audioLanguage = it }
    OutlinedTextField(audio, { audio = it; Settings.audioLanguage = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Or type codes, in order, e.g. hi,en") })
    Hint("Sources whose names say this language (\"Hindi\", \"Dual audio [Hindi + English]\") come first, and in a source with several " +
        "audio tracks this one is picked. Leave empty for the stream's default.")
    Heading("Subtitles")
    var subs by remember { mutableStateOf(Settings.subtitleLanguage) }
    LanguageChips(subs) { subs = it; Settings.subtitleLanguage = it }
    OutlinedTextField(subs, { subs = it; Settings.subtitleLanguage = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Or type codes, in order, e.g. en or ar,en") })
    Hint("Turned on by themselves when the source has them. Online subtitles (OpenSubtitles, no account) are in the player's Subtitles menu.")
    Heading("Sound")
    var level by remember { mutableStateOf(Settings.levelAudio) }
    SwitchRow("Even out loud and quiet sounds", "Quieter music and explosions, clearer voices: good on TVs and at night. Also in the player's Audio menu.", level) {
        level = it; Settings.levelAudio = it
    }
    Heading("Picture")
    var rotation by remember { mutableStateOf(Settings.playerOrientation) }
    if (!isTv) {
        Text("Rotation", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        ChoiceChips(listOf("LANDSCAPE", "PORTRAIT", "AUTO"), { mapOf("LANDSCAPE" to "Landscape", "PORTRAIT" to "Portrait", "AUTO" to "As the device is held")[it]!! }, { it == rotation }) {
            rotation = it; Settings.playerOrientation = it
        }
    }
    var zoomMode by remember { mutableStateOf(Settings.playerZoom) }
    Text("Picture size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    ChoiceChips(io.github.sherazi5110.cloudstream.player.Zoom.entries.map { it.name }, { n -> io.github.sherazi5110.cloudstream.player.Zoom.valueOf(n).label }, { it == zoomMode }) {
        zoomMode = it; Settings.playerZoom = it
    }
    Hint("The player's resize button switches between Fit, Fill and Stretch with one tap.")
    TorrentSettings()
}

@Composable
private fun LiveTvSection() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = LiveTv.onChange { version++ }; onDispose { off() } }
    val sources = remember(version) { LiveTv.sources() }
    if (sources.isEmpty()) Hint("No sources yet. Add an M3U playlist (with an optional XMLTV guide), an Xtream Codes login, or a Stremio add-on with TV channels.")
    sources.forEach { src ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(src.name, style = MaterialTheme.typography.titleMedium)
                Text("${src.kind.label} · ${src.url}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            androidx.compose.material3.Switch(src.enabled, onCheckedChange = { LiveTv.setEnabled(src.id, it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.accent))
            IconAction(Icons.Filled.Delete, "Remove") { LiveTv.remove(src.id) }
        }
    }
    Heading("Add a source")
    var kind by remember { mutableStateOf(LiveTv.Kind.M3U) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // A playlist file on the device (downloaded .m3u / .m3u8): copied into the app, so it stays after the original moves.
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val display = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.m3u"
                    val base = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "playlist" }
                    val dir = java.io.File(context.filesDir, "livetv-files").apply { mkdirs() }
                    val file = java.io.File(dir, "$base.m3u")
                    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    LiveTv.add(LiveTv.Kind.M3U, file.toURI().toString(), name.ifBlank { base }, epg)
                }
            }
            busy = false
            r.onSuccess { (src, n) -> App.toast("${src.name}: $n channels"); name = ""; epg = ""; version++ }
                .onFailure { App.toast(it.message ?: "Couldn't read that playlist file") }
        }
    }
    ChoiceChips(LiveTv.Kind.entries, { it.label }, { it == kind }) { kind = it }
    OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = {
        Text(when (kind) { LiveTv.Kind.M3U -> "Playlist address (.m3u)"; LiveTv.Kind.XTREAM -> "Server address"; LiveTv.Kind.STREMIO -> "Add-on manifest address" })
    })
    if (kind != LiveTv.Kind.STREMIO) OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Name (optional)") })
    if (kind == LiveTv.Kind.M3U) OutlinedTextField(epg, { epg = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("XMLTV guide address (optional)") })
    if (kind == LiveTv.Kind.XTREAM) {
        OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Username") })
        OutlinedTextField(pass, { pass = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation())
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Add", Icons.Filled.Add, compact = true) {
            if (url.isBlank() || busy) return@PillButton
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { LiveTv.add(kind, url, name, epg, user, pass) } }
                busy = false
                r.onSuccess { (src, n) -> App.toast("${src.name}: $n channels"); url = ""; name = ""; epg = ""; user = ""; pass = ""; version++ }
                    .onFailure { App.toast(it.message ?: "Couldn't add that source") }
            }
        }
        if (kind == LiveTv.Kind.M3U) PillButton("Choose a file", Icons.Filled.FolderOpen, primary = false, compact = true, modifier = Modifier.padding(start = 10.dp)) {
            if (!busy) runCatching { pickFile.launch(arrayOf("*/*")) }.onFailure { App.toast("No file picker on this device") }
        }
        if (busy) Spinner(Modifier.padding(start = 12.dp).size(28.dp))
    }
    if (kind == LiveTv.Kind.M3U) Hint("Or choose a playlist file (.m3u, .m3u8) saved on this device.")
    // No typing with the remote: scan the QR code with a phone and paste the playlist link there.
    PillButton("Add from phone (scan a QR code)", Icons.Filled.PhoneAndroid, Modifier.padding(top = 10.dp), primary = false, compact = true) {
        Nav.open(Screen.AddFromPhone)
    }
    if (kind == LiveTv.Kind.M3U && LiveTv.isIptvOrgIndex(url)) Hint("That's iptv-org's whole list: 10,000+ channels from every country, and many play only in " +
        "their own country or have stopped. Live TV opens on your country's channels from it, or add just one country below (smaller and quicker).")
    // iptv-org's free channels, one country at a time (its whole list is too big and mostly region-locked elsewhere).
    Heading("Free channels by country")
    var pickCountry by remember { mutableStateOf(LiveTv.homeCountry) }
    var countryMenu by remember { mutableStateOf(false) }
    val allCountries = remember {
        java.util.Locale.getISOCountries().map { it to LiveTv.countryName(it) }.sortedBy { it.second }
    }
    Hint("From iptv-org, the open list of publicly available channels. Region-locked channels from your own country play; dead links can be hidden with Check channels on the Live TV page.")
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            PillButton(LiveTv.countryName(pickCountry) + " ▾", null, primary = false, compact = true) { countryMenu = true }
            androidx.compose.material3.DropdownMenu(countryMenu, { countryMenu = false }, Modifier.heightIn(max = 420.dp)) {
                (listOf(LiveTv.homeCountry to LiveTv.countryName(LiveTv.homeCountry) + " · your country") + allCountries).forEach { (code, label) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { pickCountry = code; countryMenu = false })
                }
            }
        }
        PillButton("Add its channels", Icons.Filled.Add, compact = true, modifier = Modifier.padding(start = 10.dp)) {
            if (busy) return@PillButton
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { LiveTv.add(LiveTv.Kind.M3U, LiveTv.iptvOrgCountryUrl(pickCountry), "${LiveTv.countryName(pickCountry)} (iptv-org)") } }
                busy = false
                r.onSuccess { (src, n) -> App.toast("${src.name}: $n channels"); version++ }
                    .onFailure { App.toast(it.message ?: "Couldn't load that country's channels") }
            }
        }
    }
    Heading("Options")
    var home by remember { mutableStateOf(LiveTv.homeCountry) }
    var homeMenu by remember { mutableStateOf(false) }
    var hideLocked by remember { mutableStateOf(LiveTv.hideRegionLocked) }
    var autoCheck by remember { mutableStateOf(LiveTv.autoCheck) }
    SwitchRow("Check channels automatically", "When Live TV opens, the channels showing are tested in the background (each once a day), " +
        "and ones with broken links are hidden. A channel whose stream works but won't play is reported as a problem in the app instead.", autoCheck) { autoCheck = it; LiveTv.autoCheck = it }
    var hideBroken by remember { mutableStateOf(LiveTv.hideNotWorking) }
    SwitchRow("Hide channels that don't work", "Broken links found by the check, or that failed to play.", hideBroken) { hideBroken = it; LiveTv.hideNotWorking = it }
    SwitchRow("Hide region-locked channels", "Channels marked as playing only in their own country are left out, except ones from " +
        "${LiveTv.countryName(home)}.", hideLocked) { hideLocked = it; LiveTv.hideRegionLocked = it }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Your country", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            PillButton(LiveTv.countryName(home) + " ▾", null, primary = false, compact = true) { homeMenu = true }
            androidx.compose.material3.DropdownMenu(homeMenu, { homeMenu = false }, Modifier.heightIn(max = 420.dp)) {
                allCountries.forEach { (code, label) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { home = code; LiveTv.homeCountry = code; homeMenu = false })
                }
            }
        }
    }
    var daily by remember { mutableStateOf(LiveTv.refreshDaily) }
    SwitchRow("Refresh channel lists daily", "Otherwise they're kept until you refresh them on the Live TV page.", daily) { daily = it; LiveTv.refreshDaily = it }
    var kids by remember { mutableStateOf(LiveTv.allowKids) }
    SwitchRow("Live TV in kids profiles", "Only channels in children's groups.", kids) { kids = it; LiveTv.allowKids = it }
    var expired by remember { mutableStateOf(LiveTv.allowExpired) }
    SwitchRow("Play channels with an expired certificate", "Many public channels' servers let their security certificate run out. " +
        "When one fails for that reason it's tried again accepting it, if the certificate is otherwise genuine (right address, " +
        "trusted issuer). Only for Live TV streams.", expired) { expired = it; LiveTv.allowExpired = it }
    var plain by remember { mutableStateOf(LiveTv.retryPlain) }
    SwitchRow("Retry without encryption", "Off by default. When a playlist channel's secure (https) address fails, for example because its " +
        "server's certificate has expired, try the same address over plain http. Never used for Xtream logins. Over plain http someone " +
        "on your network could in theory change what you see.", plain) { plain = it; LiveTv.retryPlain = it }
}

@Composable
private fun MetadataSection() {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Metadata.tmdbKey) }
    var status by remember { mutableStateOf<String?>(null) }
    Hint("Add your free TMDB key (themoviedb.org → Settings → API) for ratings on posters, wide backdrops and title logos, cast with photos, trailers and similar titles. IMDb ratings (Cinemeta) and AniList scores need no key.")
    OutlinedTextField(key, { key = it.trim(); Metadata.tmdbKey = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("TMDB API key (v3) or read access token (v4)") })
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Test key", null, primary = false, compact = true) {
            status = "Testing…"
            scope.launch { status = Metadata.testKey(key) ?: "The key works." }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp)) }
    }
    Heading("Use")
    var tmdb by remember { mutableStateOf(Metadata.tmdbEnabled) }
    SwitchRow("TMDB", null, tmdb) { tmdb = it; Metadata.tmdbEnabled = it }
    var posters by remember { mutableStateOf(Metadata.posterRatings) }
    SwitchRow("Ratings on posters", null, posters) { posters = it; Metadata.posterRatings = it }
    var art by remember { mutableStateOf(Metadata.artwork) }
    SwitchRow("Backdrops and title logos", null, art) { art = it; Metadata.artwork = it }
    var extras by remember { mutableStateOf(Metadata.extras) }
    SwitchRow("Cast, trailers and similar titles", null, extras) { extras = it; Metadata.extras = it }
    var cinemeta by remember { mutableStateOf(Metadata.cinemeta) }
    SwitchRow("IMDb ratings (Cinemeta)", null, cinemeta) { cinemeta = it; Metadata.cinemeta = it }
    var anilist by remember { mutableStateOf(Metadata.anilist) }
    SwitchRow("AniList scores for anime", null, anilist) { anilist = it; Metadata.anilist = it }
    var lang by remember { mutableStateOf(Metadata.language) }
    OutlinedTextField(lang, { lang = it.trim(); Metadata.language = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Language, e.g. en-US") })
    Heading("Cache")
    var stats by remember { mutableStateOf(Metadata.cacheStats()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${stats.first} entries · ${stats.second / 1024} KB", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillButton("Clear", Icons.Filled.Delete, primary = false, compact = true) { Metadata.clearCache(); stats = Metadata.cacheStats() }
    }
}

@Composable
private fun NetworkSection() {
    Heading("DNS over HTTPS")
    var dns by remember { mutableStateOf(Settings.dns) }
    ChoiceChips(DnsProvider.entries, { it.label }, { it == dns }) { dns = it; Settings.dns = it }
    var fallback by remember { mutableStateOf(Settings.dnsFallback) }
    SwitchRow("Fall back to system DNS", "When the resolver is slow or blocked.", fallback) { fallback = it; Settings.dnsFallback = it }
    Hint("Applies when the app restarts. Helps when your network blocks some sites.")
    Heading("Cookies")
    var cookies by remember { mutableStateOf(Network.cookies.size) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$cookies cookies stored", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillButton("Clear", Icons.Filled.Delete, primary = false, compact = true) { Network.cookies.clear(); cookies = 0 }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: io.github.sherazi5110.cloudstream.BuildConfig.VERSION_NAME }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Logo(48.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text("Stream Goat", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${versionName ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Text("Created by Syed Ali Hamza", style = MaterialTheme.typography.bodyMedium, color = Palette.accent)
        }
    }
    PillButton("Source code on GitHub", null, primary = false, compact = true) {
        com.lagradost.cloudstream3.CloudStreamApp.openBrowser("https://github.com/sherazi5110/stream-goat", context.findActivity())
    }
    // A problem without a crash: GitHub's new-issue page with the device and version filled in (a QR code on TVs).
    PillButton("Report a glitch or problem", null, primary = false, compact = true, modifier = Modifier.padding(top = 8.dp)) {
        // From Settings there's no screen to picture: the last steps say where it happened.
        io.github.sherazi5110.cloudstream.GlitchReport.draft = io.github.sherazi5110.cloudstream.GlitchReport.Draft(
            "Settings (reported from Settings → About)", io.github.sherazi5110.cloudstream.GlitchReport.steps(), null)
    }
    Hint(if (App.isTv) "Tip: to include a picture of a screen, choose Report a glitch in the profile menu (top right) or the player's top bar."
        else "Tip: to include a picture of a screen, shake the phone there, choose Report a glitch in the profile menu, or tap the bug button in the player.")
    if (!App.isTv) {
        var shake by remember { mutableStateOf(io.github.sherazi5110.cloudstream.Settings.shakeToReport) }
        SwitchRow("Shake to report a glitch", "A firm shake opens Report a glitch with a picture of the screen", shake) {
            shake = it; io.github.sherazi5110.cloudstream.Settings.shakeToReport = it
        }
    }
    Hint("GPL-3.0. Extensions and their content come from third parties; this app hosts nothing.")
}

/** Settings → Player → Torrents: off until the person has read what it means. */
@Composable
private fun TorrentSettings() {
    Heading("Torrents")
    var on by remember { mutableStateOf(io.github.sherazi5110.cloudstream.Torrents.enabled) }
    var asking by remember { mutableStateOf(false) }
    SwitchRow("Torrent streaming", "Plays torrent sources (Torrentio and other add-ons, magnet links) while they download. Off by default.", on) {
        if (it) asking = true else { on = false; io.github.sherazi5110.cloudstream.Torrents.enabled = false }
    }
    if (on) {
        var limit by remember { mutableStateOf(io.github.sherazi5110.cloudstream.Torrents.uploadLimitKb) }
        Text("Upload limit while watching", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        ChoiceChips(listOf(50, 100, 500, 0), { if (it == 0) "No limit" else if (it >= 1000) "${it / 1000} MB/s" else "$it KB/s" }, { it == limit }) {
            limit = it; io.github.sherazi5110.cloudstream.Torrents.uploadLimitKb = it
        }
    }
    Hint("Faster and private: set Torrentio up with a debrid service (Real-Debrid, AllDebrid, Premiumize, TorBox…) at " +
        "torrentio.strem.fun/configure and install that link instead. Its streams are then ordinary links, no torrenting on this device.")
    if (asking) SimpleDialog("Turn on torrent streaming?", { asking = false }, confirm = "I understand, turn it on", onConfirm = {
        on = true; io.github.sherazi5110.cloudstream.Torrents.enabled = true; asking = false
    }) {
        Hint("While a torrent plays, this device downloads it from other people and also uploads parts of it to them; that's " +
            "how BitTorrent works. Your IP address is visible to everyone sharing the same torrent, and in many countries " +
            "copyright holders watch torrents for that. Many people use a VPN.")
        Hint("Only stream what you're allowed to. Uploading is limited (Upload limit, below), and the downloaded file is " +
            "deleted when you stop watching. A debrid service avoids all this (see below the switch).")
    }
}

/** Common languages as chips that add or remove their code in a "hi,en" setting (order kept). */
@Composable
private fun LanguageChips(value: String, onChange: (String) -> Unit) {
    val codes = listOf("en", "hi", "ur", "ta", "te", "ml", "bn", "pa", "ja", "ko", "zh", "es", "fr", "de", "ar", "tr", "pt", "ru", "id")
    val chosen = value.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
        items(codes.size) { i ->
            val code = codes[i]
            val on = code in chosen || io.github.sherazi5110.cloudstream.Engine.languageName(code).lowercase() in chosen
            Text(io.github.sherazi5110.cloudstream.Engine.languageName(code), style = MaterialTheme.typography.labelLarge, modifier = Modifier
                .card(RoundedCornerShape(50)) { onChange((if (on) chosen.filterNot { it == code } else chosen + code).joinToString(",")) }
                .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}


/**
 * Sync with WeTrakr: a code to enter on wetrakr.com/activate (a QR code on TVs), then what's watched here is
 * marked watched there. Each profile connects its own account.
 */
@Composable
private fun WeTrakrSection() {
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(io.github.sherazi5110.cloudstream.WeTrakr.connected) }
    var code by remember { mutableStateOf<io.github.sherazi5110.cloudstream.WeTrakr.DeviceCode?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    Hint("WeTrakr (wetrakr.com) is a free tracker for movies, shows and anime. Connected, what you watch here shows as now watching " +
        "there and is marked watched once you've seen 80% of it. Each profile connects its own account; only a sign-in token is kept on this device.")
    when {
        !io.github.sherazi5110.cloudstream.WeTrakr.available -> Hint("Not available in this build yet.")
        connected -> {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Connected to WeTrakr", style = MaterialTheme.typography.titleSmall, color = Palette.accent, modifier = Modifier.weight(1f))
                PillButton("Disconnect", null, primary = false, compact = true) {
                    io.github.sherazi5110.cloudstream.WeTrakr.disconnect(); connected = false; status = null
                }
            }
            var share by remember { mutableStateOf(io.github.sherazi5110.cloudstream.WeTrakr.shareNowPlaying) }
            SwitchRow("Show what I'm watching", "WeTrakr shows the title as playing, with its progress. Off: titles are only marked watched when you finish them.", share) {
                share = it; io.github.sherazi5110.cloudstream.WeTrakr.shareNowPlaying = it
            }
            val syncScope = rememberCoroutineScope()
            var syncing by remember { mutableStateOf(false) }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Two-way sync: what you watch in other apps on WeTrakr (and on the website) shows as watched here, " +
                    "and what you watch or mark watched here is sent there. Checked when the app opens or comes back, every 5 minutes, " +
                    "and when you open a title. Sync now imports your whole WeTrakr history again.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.weight(1f))
                PillButton(if (syncing) "Syncing…" else "Sync now", null, primary = false, compact = true) {
                    if (!syncing) {
                        syncing = true
                        syncScope.launch {
                            val sum = runCatching { io.github.sherazi5110.cloudstream.WeTrakrSync.pull(full = true) }.getOrNull()
                            syncing = false
                            App.toast(if (sum == null) (io.github.sherazi5110.cloudstream.WeTrakrSync.lastError ?: "Couldn't reach WeTrakr. Try again in a moment.")
                                else "WeTrakr: ${sum.films} films and ${sum.episodes} episodes watched, marked here too")
                        }
                    }
                }
            }
        }
        code != null -> {
            val c = code!!
            Text("Go to ${c.url.removePrefix("https://")} on your phone or computer and enter:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(c.userCode, style = MaterialTheme.typography.displaySmall, color = Palette.accent, modifier = Modifier.padding(vertical = 6.dp))
            if (App.isTv) {
                val qr = remember(c.url) { qrBitmap(c.url + "?code=" + c.userCode, 480).asImageBitmap() }
                androidx.compose.foundation.Image(qr, "QR code for WeTrakr", Modifier.size(180.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(Modifier.size(22.dp))
                Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp).weight(1f))
                PillButton("Cancel", null, primary = false, compact = true) { job?.cancel(); code = null }
            }
        }
        else -> PillButton("Connect WeTrakr", Icons.Filled.Link, compact = true, modifier = Modifier.padding(top = 6.dp)) {
            status = null
            job = scope.launch {
                try {
                    val c = io.github.sherazi5110.cloudstream.WeTrakr.start()
                    code = c
                    val until = System.currentTimeMillis() + c.expiresIn * 1000L
                    while (System.currentTimeMillis() < until) {
                        kotlinx.coroutines.delay(c.interval * 1000L)
                        if (io.github.sherazi5110.cloudstream.WeTrakr.poll(c)) { connected = true; code = null; App.toast("WeTrakr connected"); return@launch }
                    }
                    code = null; status = "The code expired. Try again."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { code = null; status = "Couldn't connect: ${e.message}" }
            }
        }
    }
    status?.let { Hint(it) }
}


/** Titles hidden with long-press → Hide (issue #64), each with Restore, and Restore all. */
@Composable
private fun HiddenTitlesSection() {
    val items = io.github.sherazi5110.cloudstream.Hidden.items
    Heading("Hidden titles")
    if (items.isEmpty()) {
        Text("None. Long-press a poster (or hold OK on a TV remote) and choose Hide to keep a title out of Home, catalogs and search.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        return
    }
    items.toList().forEach { h ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NetImage(h.poster, Modifier.width(36.dp).aspectRatio(2f / 3f))
            Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.padding(start = 12.dp).weight(1f))
            PillButton("Restore", null, primary = false, compact = true) { io.github.sherazi5110.cloudstream.Hidden.restore(h.url) }
        }
    }
    PillButton("Restore all (${items.size})", null, Modifier.padding(top = 8.dp), primary = false, compact = true) {
        io.github.sherazi5110.cloudstream.Hidden.restoreAll()
    }
}


