package io.github.sherazi5110.cloudstream

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Something the user can press play on: a movie, or one episode of a series. */
data class Playable(
    val label: String,
    val data: String,
    val season: Int? = null,
    val episode: Int? = null,
    val name: String? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val dub: String? = null,
)

data class Links(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>)

sealed interface LinkEvent {
    data class Link(val link: ExtractorLink) : LinkEvent
    data class Subtitle(val subtitle: SubtitleFile) : LinkEvent
    data class Done(val error: Throwable?, val timedOut: Boolean) : LinkEvent
}

data class SearchBatch(val api: MainAPI, val results: List<SearchResponse>, val error: Throwable?)

/**
 * The provider call sequence the Android UI drives through APIRepository:
 * getMainPage / search -> load -> loadLinks. Everything streams: results reach the
 * UI as each provider, section or extractor finishes, and slow ones never block fast ones.
 */
object Engine {
    private val threads = AtomicInteger()

    /**
     * Extension code runs on its own pool so a misbehaving extension cannot starve the UI or IO. Its threads run
     * a little below normal priority, so the screen stays smooth while extensions parse pages.
     */
    val dispatcher = Executors.newFixedThreadPool(64) { r ->
        Thread({ runCatching { android.os.Process.setThreadPriority(EXTENSION_PRIORITY) }; r.run() },
            "extension-${threads.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Slightly below normal (0); 10 would be "background". */
    const val EXTENSION_PRIORITY = 3

    private const val DEFAULT_TIMEOUT = 45_000L

    /** Sorted by name: extensions load in parallel, so registration order isn't stable. */
    /** Extensions and Stremio add-ons the current profile may use: a kids profile sees only its allowed ones. */
    fun providers(): List<MainAPI> = allProviders().filter { ContentFilter.allows(it) }

    /** Every loaded extension and add-on, whatever the profile. */
    fun allProviders(): List<MainAPI> = (APIHolder.allProviders.toList() + StremioAddons.apis())
        .onEach { SiteOverrides.apply(it) }
        .sortedBy { it.name.lowercase() }

    fun provider(name: String): MainAPI? = providers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun log(api: MainAPI, what: String, t: Throwable) {
        if (t !is CancellationException) android.util.Log.w(api.name, "$what failed: $t")
    }

    /** Home sections in mainPage order; each one is emitted with its index as soon as it loads. */
    /** One loaded home row. [order] sorts rows: provider position, then the user's catalog order. */
    data class HomeSection(val order: Int, val api: MainAPI, val section: MainPageData, val list: HomePageList)

    /**
     * Home rows for [apis] (one, or several for the combined feed), using each extension's
     * enabled catalogs in the user's order. Rows arrive as soon as each catalog loads.
     */
    fun home(apis: List<MainAPI>): Flow<HomeSection> = channelFlow {
        apis.forEachIndexed { p, api ->
            Catalogs.enabled(api).forEachIndexed { position, entry ->
                launch(dispatcher) {
                    val response = homePage(api, entry.section, 1)
                    response?.items?.map { it.copy(list = it.list.filter(ContentFilter::allows)) }
                        ?.filter { it.list.isNotEmpty() }?.forEachIndexed { i, list ->
                        send(HomeSection(p * 1_000_000 + position * 100 + i, api, entry.section, list))
                    }
                }
            }
        }
    }

    suspend fun homePage(api: MainAPI, section: MainPageData, page: Int): HomePageResponse? = withContext(dispatcher) {
        try {
            withTimeoutOrNull(api.getMainPageTimeoutMs ?: DEFAULT_TIMEOUT) {
                api.getMainPage(page, MainPageRequest(section.name, section.data, section.horizontalImages))
            }
        } catch (t: Throwable) {
            log(api, "home '${section.name}'", t)
            null
        }
    }

    /** One batch per provider, in completion order. */
    fun search(query: String, apis: List<MainAPI> = providers()): Flow<SearchBatch> = channelFlow {
        apis.forEach { api ->
            launch(dispatcher) {
                val batch = try {
                    val results = withTimeout(api.searchTimeoutMs ?: DEFAULT_TIMEOUT) { api.search(query, 1)?.items.orEmpty() }.filter(ContentFilter::allows)
                    SearchBatch(api, results, null)
                } catch (t: Throwable) {
                    log(api, "search", t)
                    SearchBatch(api, emptyList(), t)
                }
                send(batch)
            }
        }
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? = withContext(dispatcher) {
        withTimeout(api.loadTimeoutMs ?: DEFAULT_TIMEOUT) { api.load(url) }
    }

    fun playables(response: LoadResponse): List<Playable> = when (response) {
        is MovieLoadResponse -> listOf(Playable(response.name, response.dataUrl))
        is LiveStreamLoadResponse -> listOf(Playable(response.name, response.dataUrl))
        is TorrentLoadResponse -> listOfNotNull(response.magnet ?: response.torrent).map { Playable(response.name, it) }
        is TvSeriesLoadResponse -> response.episodes.map { it.toPlayable(null) }
        is AnimeLoadResponse -> response.episodes.flatMap { (dub, eps) -> eps.map { it.toPlayable(dub.name) } }
        else -> emptyList()
    }

    private fun com.lagradost.cloudstream3.Episode.toPlayable(dub: String?): Playable {
        val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString("")
        val label = listOf(code, name ?: "").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Episode" }
        return Playable(label, data, season, episode, name, posterUrl, description, dub)
    }

    /**
     * Streams links and subtitles as the extension reports them. The call is bounded by
     * the extension's own loadLinksTimeoutMs or the user's setting; links found before
     * the deadline are kept, which is how the Android player behaves too.
     */
    /**
     * One search for an episode's sources, shared by everyone who asks for them: the player, the search started
     * when the title's page opened ([prefetch]) and the download chooser read the same results. A complete search
     * is kept for [CACHE_MS] (like the official app's 20-minute link cache), so playing the same episode again
     * starts at once.
     */
    private class Session(val started: Long) {
        val events: MutableList<LinkEvent> = java.util.Collections.synchronizedList(mutableListOf())
        val updates = kotlinx.coroutines.flow.MutableStateFlow(0)
        @Volatile var done = false
        /** Finished without an error or a timeout, with sources: worth keeping. */
        @Volatile var complete = false
        var job: Job? = null
    }

    private const val CACHE_MS = 20 * 60_000L
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)

    private fun key(api: MainAPI, data: String) = api.name + "\u0000" + data

    private fun session(api: MainAPI, data: String): Session = synchronized(sessions) {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, s) -> s.done && now - s.started > CACHE_MS }
        sessions[key(api, data)]?.let { s -> if (!s.done || s.complete) return s }
        val s = Session(now)
        sessions[key(api, data)] = s
        fun add(e: LinkEvent) { s.events += e; s.updates.update { it + 1 } }
        s.job = sessionScope.launch {
            var error: Throwable? = null
            var found = 0
            val timeout = api.loadLinksTimeoutMs ?: (Settings.sourcesTimeoutSeconds * 1000L)
            val finished = try {
                withTimeoutOrNull(timeout) {
                    api.loadLinks(data, false, { add(LinkEvent.Subtitle(it)) }, { found++; add(LinkEvent.Link(it)) })
                    true
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log(api, "loadLinks", t)
                error = t
                true
            }
            s.complete = error == null && finished != null && found > 0
            s.done = true
            add(LinkEvent.Done(error, timedOut = finished == null))
        }
        s
    }

    /** Starts finding an episode's sources in the background (when its title page opens), if that isn't done yet. */
    fun prefetch(api: MainAPI, data: String) {
        if (Settings.prepareSources) session(api, data)
    }

    /** Whether an episode's sources are already known (cached or being found), so playing it starts sooner. */
    fun cached(api: MainAPI, data: String): Boolean = sessions[key(api, data)]?.let { it.complete } == true

    /** Forgets an episode's cached sources (they all failed: probably expired), so the next search asks again. */
    fun forget(api: MainAPI, data: String) {
        synchronized(sessions) { sessions.remove(key(api, data)) }
    }

    fun links(api: MainAPI, data: String): Flow<LinkEvent> = channelFlow {
        // A downloaded copy plays first (and works offline).
        Downloads.localFile(api.name, data)?.let { send(LinkEvent.Link(OfflineApi.local(it))) }
        val s = session(api, data)
        var i = 0
        s.updates.first {
            while (true) {
                val e = synchronized(s.events) { s.events.getOrNull(i) } ?: break
                i++
                send(e)
                if (e is LinkEvent.Done) return@first true
            }
            false
        }
    }

    /** Collects everything at once.*/
    suspend fun allLinks(api: MainAPI, data: String): Links {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        links(api, data).collect {
            when (it) {
                is LinkEvent.Link -> links += it.link
                is LinkEvent.Subtitle -> subtitles += it.subtitle
                is LinkEvent.Done -> Unit
            }
        }
        return Links(rank(links.distinctBy { it.url }), subtitles.distinctBy { it.url })
    }

    /** Best first: closest to the preferred quality without exceeding it, then higher. */
    fun rank(links: List<ExtractorLink>): List<ExtractorLink> {
        val preferred = Settings.preferredQuality.takeIf { it > 0 } ?: Int.MAX_VALUE
        return links.sortedWith(compareBy<ExtractorLink> { if (it.source == OfflineApi.SOURCE) 0 else 1 }
            .thenBy { if (it.quality <= preferred) 0 else 1 }
            .thenByDescending { if (it.quality <= preferred) it.quality else -it.quality })
    }
}
