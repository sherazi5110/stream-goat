package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The official app's classes that big extensions use (found by scanning 101 extensions from four repositories
 * with `compat`): they must exist here with the official names and signatures.
 */
class OfficialClassesTest {
    @Test
    fun `classes from the official app that extensions use are here`() {
        // StreamPlay, StremioX, Ultima, CineStream: app events.
        val events = com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent
        var fired = false
        events += { fired = true }
        events(true)
        assertTrue(fired)
        // CineStream builds a SyncRepo around Simkl while loading; StreamPlay/TorraStream read AniList types.
        val simkl = com.lagradost.cloudstream3.syncproviders.AccountManager.simklApi
        assertEquals("simkl", simkl.idPrefix)
        assertEquals(null, com.lagradost.cloudstream3.syncproviders.SyncRepo(simkl).authUser())
        assertNotNull(Class.forName("com.lagradost.cloudstream3.syncproviders.providers.AniListApi\$CoverImage"))
        // Ultima, StreamPlay, StremioX manage other extensions through the official PluginManager.
        assertNotNull(com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline())
        // Ultima, WatchParty: player and continue-watching types.
        assertNotNull(com.lagradost.cloudstream3.ui.player.CSPlayerEvent.Play)
        // YoutubeProvider (official repository): NewPipe Extractor, set up like the official app.
        Platform.init(); Network.init()
        assertNotNull(org.schabi.newpipe.extractor.NewPipe.getDownloader())
        assertNotNull(org.schabi.newpipe.extractor.ServiceList.YouTube)
    }

    /** Found by scanning the 587 extensions of the official app's repository list (recloudstream/cs-repos). */
    @Test
    fun `official classes from the full repository scan are here`() {
        Platform.init()
        // M6+, RMC+, TF1+, StreamCenter register their own video actions while loading.
        val plugin = object : com.lagradost.cloudstream3.plugins.Plugin() {}
        plugin.filename = "/tmp/test.cs3"
        val action = object : com.lagradost.cloudstream3.actions.VideoClickAction() {
            override val name = com.lagradost.cloudstream3.utils.txt("Cast")
            override fun shouldShow(context: android.content.Context?, video: com.lagradost.cloudstream3.ui.result.ResultEpisode?) = true
            override suspend fun runAction(
                context: android.content.Context?, video: com.lagradost.cloudstream3.ui.result.ResultEpisode,
                result: com.lagradost.cloudstream3.ui.result.LinkLoadingResult, index: Int?,
            ) {}
        }
        plugin.registerVideoClickAction(action)
        val holder = com.lagradost.cloudstream3.actions.VideoClickActionHolder
        assertEquals(action, holder.getByUniqueId(action.uniqueId()))
        holder.removeFrom("/tmp/test.cs3")
        assertEquals(null, holder.getByUniqueId(action.uniqueId()))
        // They call txt(...) through the official file class name.
        val textUtil = Class.forName("com.lagradost.cloudstream3.utils.TextUtilKt")
        assertNotNull(textUtil.getMethod("txt", String::class.java))
        assertNotNull(textUtil.getMethod("txtNull", String::class.java))
        // SyncProvider, Wrapped, StreamCenter: the official saved-data records, kept like the official app.
        val store = com.lagradost.cloudstream3.utils.DataStoreHelper
        store.setResultWatchState(42, com.lagradost.cloudstream3.ui.WatchType.COMPLETED.internalId)
        assertEquals(com.lagradost.cloudstream3.ui.WatchType.COMPLETED, store.getResultWatchState(42))
        store.setLastWatched(42, 7, 3, 1)
        assertEquals(3, store.getLastWatched(42)?.episode)
        assertTrue(42 in store.getAllResumeStateIds().orEmpty())
        store.deleteAllResumeStateIds()
        assertEquals(null, store.getLastWatched(42))
        store.setResultWatchState(42, com.lagradost.cloudstream3.ui.WatchType.NONE.internalId)
        assertEquals(com.lagradost.cloudstream3.ui.WatchType.NONE, store.getResultWatchState(42))
        // MegaRepo adds repositories with the older three-argument RepositoryData.
        assertEquals("https://example.com/repo.json", com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(null, "Repo", "https://example.com/repo.json").url)
        // PrimeVideo keeps its preferred audio language in the official player's setting.
        com.lagradost.cloudstream3.ui.player.CS3IPlayer.preferredAudioTrackLanguage = "hi"
        assertEquals("hi", com.lagradost.cloudstream3.ui.player.CS3IPlayer.preferredAudioTrackLanguage)
        com.lagradost.cloudstream3.ui.player.CS3IPlayer.preferredAudioTrackLanguage = null
        // 3isk asks OpenSubtitles with the official app's headers.
        assertEquals("Cloudstream3 v0.2", com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi.headers["user-agent"])
        // Settings screens of 42 extensions use Android's XML parser types.
        assertNotNull(Class.forName("org.xmlpull.v1.XmlPullParser"))
    }
}
