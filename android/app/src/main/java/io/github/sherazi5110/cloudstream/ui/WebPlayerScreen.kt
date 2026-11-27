package io.github.sherazi5110.cloudstream.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.LiveTv
import kotlinx.coroutines.delay

/**
 * The page the embed players are shown from. Twitch only plays inside a page whose address matches its `parent`
 * setting, and YouTube wants to know which site embeds it, so the page gets the app's own website's address.
 */
private const val PAGE_ORIGIN = "https://sherazi5110.github.io"
private const val PAGE_URL = "$PAGE_ORIGIN/stream-goat/player/"

/**
 * A Live TV channel that's a YouTube, Twitch or Dailymotion channel, playing full screen in that service's own
 * embed player (how they let other apps and sites show their streams). For a YouTube channel given by name
 * (@name, /c/, /user/), its page is opened first to see which video is live. Back leaves; CH+ / CH− change channel.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayerScreen(screen: Screen.WebPlayer) {
    val c = screen.channel
    val scope = rememberCoroutineScope()
    var embed by remember(c.key) { mutableStateOf(LiveTv.webEmbed(c)) }
    /** Shown over the player: what's happening, or why it can't play. */
    var message by remember(c.key) { mutableStateOf<String?>(if (embed?.kind == "youtube-page") "Finding ${c.name}'s live stream…" else "Loading ${c.name}…") }
    var failed by remember(c.key) { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { Nav.back() }
    // Full screen, as the video player: the status bar hid the YouTube player's settings and captions buttons.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    fun openOutside() {
        runCatching {
            App.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { App.toast("No app can open ${c.url}") }
    }

    fun change(step: Int) {
        val next = LivePlayback.neighbour(screen.channels, c, step) ?: return
        if (next.key != c.key) LivePlayback.play(scope, next, screen.channels, replace = true)
    }

    fun show(view: WebView, e: LiveTv.WebEmbed) {
        val html = LiveTv.embedHtml(e, PAGE_ORIGIN) ?: return
        view.loadDataWithBaseURL(PAGE_URL, html, "text/html", "utf-8", null)
    }

    // Clear of a camera notch, so the player's corner buttons can be touched.
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout)) {
        val created = remember(c.key) {
            runCatching {
                WebView(App.context).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun error(code: String) {
                            post {
                                failed = true
                                message = when (code) {
                                    "101", "150" -> "${c.name} doesn't allow its videos to play in other apps."
                                    "100" -> "${c.name} isn't live on YouTube right now."
                                    else -> "YouTube couldn't play ${c.name} here (error $code)."
                                }
                            }
                        }
                    }, "StreamGoat")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // Links inside the player (the channel name, "Watch on YouTube") open outside, not over the player.
                            if (request.isForMainFrame && embed?.kind != "youtube-page") {
                                runCatching {
                                    App.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (embed?.kind != "youtube-page") message = null
                        }
                    }
                    // CH+ / CH− on the remote (and page up / down on a keyboard) change channel.
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { change(-1); true }
                            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { change(1); true }
                            else -> false
                        }
                    }
                }
            }.getOrNull()
        }
        if (created == null) {
            // No WebView on this device (some TV boxes): the service's app or the browser instead.
            LaunchedEffect(c.key) { openOutside(); Nav.back() }
            return@Box
        }
        DisposableEffect(created) {
            web = created
            when (val e = embed) {
                null -> Unit
                // A channel's page: opened as it is, to read which video is live (its address, or its canonical link).
                else -> if (e.kind == "youtube-page") created.loadUrl(e.id) else show(created, e)
            }
            onDispose { created.stopLoading(); created.loadUrl("about:blank"); created.destroy() }
        }
        // While a YouTube channel's page is read for its live video, the page itself stays hidden.
        AndroidView({ created }, Modifier.fillMaxSize().alpha(if (embed?.kind == "youtube-page" && message != null) 0f else 1f))
        // A YouTube channel by name: look for its live video, then switch to the embed player.
        if (embed?.kind == "youtube-page") LaunchedEffect(c.key) {
            val js = "(function(){var a=[location.href];var l=document.querySelector('link[rel=\"canonical\"]');if(l)a.push(l.href);" +
                "var o=document.querySelector('meta[property=\"og:url\"]');if(o)a.push(o.content);return a.join(' ');})()"
            repeat(30) {
                delay(700)
                var found: LiveTv.WebEmbed? = null
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                created.evaluateJavascript(js) { text -> found = text?.let { LiveTv.youtubeLiveVideo(it) }; done.complete(Unit) }
                kotlinx.coroutines.withTimeoutOrNull(2000) { done.await() }
                found?.let { e ->
                    embed = e
                    message = "Loading ${c.name}…"
                    show(created, e)
                    return@LaunchedEffect
                }
            }
            // Nothing found (a consent page, a sign-in, an unusual page): show the page itself.
            message = null
        }
        LaunchedEffect(created) { created.requestFocus() }
        message?.let { text ->
            Column(Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC0B0B10), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!failed) Spinner(Modifier.size(36.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                if (failed) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Open in ${LiveTv.webPage(c) ?: "browser"}", Icons.AutoMirrored.Filled.OpenInNew, compact = true) { openOutside() }
                    PillButton("Back", Icons.AutoMirrored.Filled.ArrowBack, primary = false, compact = true) { Nav.back() }
                }
            }
        }
    }
}
