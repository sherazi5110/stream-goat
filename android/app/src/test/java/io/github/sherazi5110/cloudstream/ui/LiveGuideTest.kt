package io.github.sherazi5110.cloudstream.ui

import io.github.sherazi5110.cloudstream.LiveTv
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Live TV guide's filters (country, category, language, source), their widening, the order and languages. */
class LiveGuideTest {
    private fun ch(name: String, country: String, group: String?, source: String = "s1", langs: List<String> = emptyList()) =
        LiveTv.Channel("$source:$name", source, name, null, group, "https://example.com/$name.m3u8", country = country, languages = langs)

    private val all = listOf(
        ch("Geo News", "PK", "News", langs = listOf("Urdu")),
        ch("Hum TV", "PK", "Entertainment", langs = listOf("Urdu")),
        ch("PTV Sports", "PK", "Sports", source = "s2", langs = listOf("Urdu", "English")),
        ch("BBC News", "GB", "News", langs = listOf("English")),
    )
    private val langs = { c: LiveTv.Channel -> c.languages }
    private val none = { _: LiveTv.Channel -> false }

    @Test fun `country, category, language and source together`() {
        val (rows, used) = LiveFilters.apply(all, LiveFilters.Choice("PK", "News", "Urdu", LiveFilters.WORKING), emptySet(), langs, none)
        assertEquals(listOf("Geo News"), rows.map { it.name })
        assertEquals("News", used.category)
        assertEquals(listOf("PTV Sports"), LiveFilters.apply(all, LiveFilters.Choice("PK", LiveFilters.ALL, "English", "s2"), emptySet(), langs, none).first.map { it.name })
    }

    @Test fun `working leaves out broken links`() {
        val broken = { c: LiveTv.Channel -> c.name == "Hum TV" }
        val rows = LiveFilters.apply(all, LiveFilters.Choice("PK", LiveFilters.ALL, LiveFilters.ALL, LiveFilters.WORKING), emptySet(), langs, broken).first
        assertEquals(listOf("Geo News", "PTV Sports"), rows.map { it.name })
    }

    @Test fun `nothing matches - source, then language, then category widen (country last)`() {
        // No Hindi channel in Pakistan: the language widens, the country and category stay.
        val (rows, used) = LiveFilters.apply(all, LiveFilters.Choice("PK", "News", "Hindi", LiveFilters.WORKING), emptySet(), langs, none)
        assertEquals(listOf("Geo News"), rows.map { it.name })
        assertEquals(LiveFilters.ALL, used.language)
        assertEquals("News", used.category)
        assertEquals("PK", used.country)
        // Favourites never widen: an empty list says "no favourites yet".
        assertEquals(0, LiveFilters.apply(all, LiveFilters.Choice("PK", LiveFilters.FAV, LiveFilters.ALL, LiveFilters.ALL), emptySet(), langs, none).first.size)
    }

    @Test fun `working first, then with a guide, then by name`() {
        val broken = { c: LiveTv.Channel -> c.name == "Geo News" }
        val guide = { c: LiveTv.Channel -> c.name == "PTV Sports" }
        assertEquals(listOf("PTV Sports", "BBC News", "Hum TV", "Geo News"), LiveFilters.order(all, broken, guide).map { it.name })
    }

    @Test fun `iptv-org's language list gives each stream its languages`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="GeoNews.pk" group-title="Urdu",Geo News
            https://example.com/geo.m3u8
            #EXTINF:-1 tvg-id="PTVSports.pk" group-title="English",PTV Sports
            https://example.com/ptv.m3u8
            #EXTINF:-1 tvg-id="PTVSports.pk" group-title="Urdu",PTV Sports
            https://example.com/ptv.m3u8
            #EXTINF:-1 tvg-id="X.pk" group-title="Undefined",X
            https://example.com/x.m3u8
        """.trimIndent()
        val map = LiveTv.parseLanguageM3u(m3u)
        assertEquals(listOf("Urdu"), map["https://example.com/geo.m3u8"])
        assertEquals(listOf("English", "Urdu"), map["https://example.com/ptv.m3u8"])
        assertEquals(null, map["https://example.com/x.m3u8"])
    }

    @Test fun `a playlist's own tvg-language is read`() {
        val list = LiveTv.parseM3u("s", "#EXTM3U\n#EXTINF:-1 tvg-language=\"Hindi;English\" group-title=\"News\",Aaj Tak\nhttps://example.com/a.m3u8\n")
        assertEquals(listOf("Hindi", "English"), list.single().languages)
    }
}
