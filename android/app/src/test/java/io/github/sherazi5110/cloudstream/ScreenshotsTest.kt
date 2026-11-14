package io.github.sherazi5110.cloudstream

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.sherazi5110.cloudstream.ui.AppRoot
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.Screen
import io.github.sherazi5110.cloudstream.ui.Tab
import io.github.sherazi5110.cloudstream.ui.ThemeState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import io.github.sherazi5110.cloudstream.ui.GoatArt
import com.lagradost.cloudstream3.newMovieSearchResponse

/**
 * Pictures of the real Android screens, drawn on the computer (no phone or emulator):
 * ./gradlew :app:recordPaparazziDebug writes them to app/src/test/snapshots/images.
 */
class ScreenshotsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
        Nav.tab = Tab.HOME
    }

    private fun shot(name: String) = paparazzi.snapshot(name) { CloudStreamTheme { AppRoot() } }

    @Test fun phoneHome() { shot("phone-home") }

    @Test fun phoneSettings() { Nav.tab = Tab.MORE; shot("phone-settings") }

    @Test fun phoneAbout() { Nav.tab = Tab.MORE; Nav.stack.add(Screen.SettingsPage("ABOUT")); shot("phone-about") }

    @Test fun phonePlayerSettings() { Nav.tab = Tab.MORE; Nav.stack.add(Screen.SettingsPage("PLAYER")); shot("phone-player-settings") }

    @Test fun phoneSearch() { Nav.tab = Tab.SEARCH; Nav.searchQuery = ""; Nav.searchSubmitted = ""; shot("phone-search") }

    /** Typed but not searched yet: the box must show the typed text (7.2.0 showed the last search instead). */
    @Test fun phoneSearchTyping() { Nav.tab = Tab.SEARCH; Nav.searchQuery = "pixel"; Nav.searchSubmitted = ""; shot("phone-search-typing") }
}

/** Android TV: the icon rail and the settings beside it. */
class TvScreenshotsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
    }

    private fun shot(name: String) = paparazzi.snapshot(name) { CloudStreamTheme { AppRoot() } }

    @Test fun tabletHome() { Nav.tab = Tab.HOME; shot("tablet-home") }

    @Test fun tabletSettings() { Nav.tab = Tab.MORE; shot("tablet-settings") }
}

/** Android TV at 1080p (960x540 to the app): the home banner must fit its title, plot and buttons below the top bar. */
class TvHomeBannerTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(screenWidth = 1920, screenHeight = 1080,
        density = com.android.resources.Density.XHIGH, orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
    }

    @Test
    fun banner() {
        val api = object : com.lagradost.cloudstream3.MainAPI() { override var name = "Eclipsia"; override val hasMainPage = true }
        val items = (1..6).map { i ->
            kotlinx.coroutines.runBlocking {
                api.newMovieSearchResponse("Resident Evil: The Final Chapter of a Long Title $i", "https://x/$i", com.lagradost.cloudstream3.TvType.Movie) { year = 2026 }
            }
        }
        val state = io.github.sherazi5110.cloudstream.ui.HomeState().apply {
            providers = listOf(api); this.api = api
            sections += Engine.HomeSection(0, api, com.lagradost.cloudstream3.MainPageData("Trending", ""),
                com.lagradost.cloudstream3.HomePageList("Trending", items))
        }
        paparazzi.snapshot("tv-home-banner") { CloudStreamTheme { io.github.sherazi5110.cloudstream.ui.WideHome(state) } }
    }
}

/** Stream Goat's profile pictures, as the profile editor shows them. */
class AvatarPicturesTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Test
    fun pictures() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        paparazzi.snapshot("avatar-pictures") {
            CloudStreamTheme {
                androidx.compose.foundation.layout.FlowRow(
                    androidx.compose.ui.Modifier.background(io.github.sherazi5110.cloudstream.ui.Palette.background).padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    io.github.sherazi5110.cloudstream.ui.Avatars.pictures.forEach { io.github.sherazi5110.cloudstream.ui.Avatar("art:$it", 64.dp) }
                    io.github.sherazi5110.cloudstream.ui.Avatar("cat:green", 64.dp)
                }
            }
        }
    }
}

/** Syed Ali Hamza's goat artwork: the logo in both styles, intro frames and offline frames in the rainbow and an accent. */
class GoatArtTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Test
    fun artwork() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        val green = 0xFF22C55E.toInt()
        val rows = listOf(true to green, false to green, false to 0xFFE8194F.toInt())
        paparazzi.snapshot("goat-art") {
            CloudStreamTheme {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.background(io.github.sherazi5110.cloudstream.ui.Palette.background).padding(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    rows.forEach { (rainbow, accent) ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                            val pics = listOf(GoatArt.logo(240, rainbow, accent)) +
                                listOf(0, 5, 8).map { GoatArt.frame("eat", it, 240, rainbow, accent) } +
                                listOf(2, 5).map { GoatArt.frame("offline", it, 240, rainbow, accent) }
                            pics.forEach { b ->
                                androidx.compose.foundation.Image(b.asImageBitmap(), null, androidx.compose.ui.Modifier.size(62.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Add from phone": how links are sorted, and the phone page, code check and sending over HTTP. */
class PhoneLinkTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun linksAreSortedAndSentWithTheCode() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        val k = PhoneLink.Kind.entries.associateBy { it }
        // Stremio add-ons, CloudStream repositories (link, cloudstreamrepo://, shortcode), playlists.
        org.junit.Assert.assertEquals(PhoneLink.Kind.STREMIO, PhoneLink.classify("https://torrentio.strem.fun/providers=yts/manifest.json"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.STREMIO, PhoneLink.classify("stremio://v3-cinemeta.strem.io/manifest.json"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.REPO, PhoneLink.classify("https://raw.githubusercontent.com/user/repo/builds/repo.json"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.REPO, PhoneLink.classify("cloudstreamrepo://raw.githubusercontent.com/user/repo/builds/repo.json"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.REPO, PhoneLink.classify("759"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.REPO, PhoneLink.classify("!abc"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.PLAYLIST, PhoneLink.classify("https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"))
        org.junit.Assert.assertEquals(PhoneLink.Kind.UNKNOWN, PhoneLink.classify("https://example.org/some/addon/"))
        k.size

        PhoneLink.testAddress = java.net.InetAddress.getByName("127.0.0.1")
        try {
            org.junit.Assert.assertTrue(PhoneLink.start())
            val base = PhoneLink.address!!
            org.junit.Assert.assertTrue(PhoneLink.qrUrl!!.endsWith("/?c=" + PhoneLink.code))
            fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
                val c = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
                c.requestMethod = method
                if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes().decodeToString() }
                return code to text
            }
            // The phone's page.
            val (pageCode, page) = call("GET", "/?c=" + PhoneLink.code)
            org.junit.Assert.assertEquals(200, pageCode)
            org.junit.Assert.assertTrue(page.contains("Send to Stream Goat"))
            // A wrong code is refused; the right one sends every line, sorted.
            val wrong = if (PhoneLink.code == "0000") "1111" else "0000"
            org.junit.Assert.assertEquals(403, call("POST", "/send", """{"code":"$wrong","text":"759"}""").first)
            val (ok, sent) = call("POST", "/send", """{"code":"${PhoneLink.code}","text":"https://a.example/manifest.json\n759\n"}""")
            org.junit.Assert.assertEquals(200, ok)
            val items = org.json.JSONObject(sent).getJSONArray("items")
            org.junit.Assert.assertEquals(2, items.length())
            org.junit.Assert.assertEquals("Stremio add-on", items.getJSONObject(0).getString("kind"))
            org.junit.Assert.assertEquals("CloudStream repository", items.getJSONObject(1).getString("kind"))
            org.junit.Assert.assertEquals(404, call("GET", "/other").first)
            // Five wrong codes in a row change the code (guessing isn't practical).
            val before = PhoneLink.code
            repeat(5) { call("POST", "/send", """{"code":"x","text":"759"}""") }
            org.junit.Assert.assertNotEquals(before, PhoneLink.code)
        } finally {
            PhoneLink.stop()
            PhoneLink.testAddress = null
        }
    }
}

/** "Add from phone" on a 1080p TV: the QR code, the address and code, and a link waiting to be added. */
class AddFromPhoneScreenTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(screenWidth = 1920, screenHeight = 1080,
        density = com.android.resources.Density.XHIGH, orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Test
    fun tv() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        PhoneLink.testAddress = java.net.InetAddress.getByName("127.0.0.1")
        PhoneLink.fixedCode = "4821"
        PhoneLink.received.clear()
        PhoneLink.received += PhoneLink.Received(1, "https://torrentio.strem.fun/lite/manifest.json", PhoneLink.Kind.STREMIO)
        PhoneLink.received += PhoneLink.Received(2, "https://raw.githubusercontent.com/user/repo/builds/repo.json", PhoneLink.Kind.REPO,
            PhoneLink.ADDED, "Example repo: open it to install its extensions", "https://raw.githubusercontent.com/user/repo/builds/repo.json", "Example repo")
        try {
            paparazzi.snapshot("add-from-phone") {
                CloudStreamTheme {
                    androidx.compose.runtime.CompositionLocalProvider(io.github.sherazi5110.cloudstream.ui.LocalWide provides true) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(io.github.sherazi5110.cloudstream.ui.Palette.background)) {
                            io.github.sherazi5110.cloudstream.ui.AddFromPhoneScreen()
                        }
                    }
                }
            }
        } finally {
            PhoneLink.stop(); PhoneLink.testAddress = null; PhoneLink.fixedCode = null; PhoneLink.received.clear()
        }
    }
}

/** After a crash, the next start shows what went wrong (Copy / Share), readable on a TV. */
class CrashDialogTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(screenWidth = 1920, screenHeight = 1080,
        orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        Nav.stack.clear()
    }

    @Test
    fun crashReport() {
        CrashReport.pending = """
            Stream Goat closed because of an error
            Stream Goat 8.0.1 on Amazon AFTMM, Android 9 (API 28), armeabi-v7a
            When: 2026-11-14 10:58:12
            Was playing: AniList: Frieren (Episode 1); source Gravix [M3U8] from 127.0.0.1
            Thread: main

            java.lang.IllegalStateException: example
            	at org.videolan.libvlc.AWindow.attachViews(AWindow.java:224)
            	at io.github.sherazi5110.cloudstream.player.PlayerController.play(PlayerController.kt:222)
        """.trimIndent()
        paparazzi.snapshot("crash-dialog") { CloudStreamTheme { io.github.sherazi5110.cloudstream.ui.CrashDialog() } }
        CrashReport.pending = null
    }
}

/** Google Drive sign-in on a 1080p TV: the code to enter at google.com/device, readable from the sofa, with a QR code. */
class CloudSignInTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(screenWidth = 1920, screenHeight = 1080,
        orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
    }

    @Test
    fun signInCode() {
        val signIn = CloudBackup.SignIn("GQV-XHF-RNL", "https://www.google.com/device", "dev", 5, Long.MAX_VALUE)
        paparazzi.snapshot("cloud-sign-in") {
            CloudStreamTheme {
                io.github.sherazi5110.cloudstream.ui.SimpleDialog("Sign in with Google", {}) {
                    io.github.sherazi5110.cloudstream.ui.SignInBody(signIn, null)
                }
            }
        }
    }
}
