package io.github.sherazi5110.cloudstream.ui

import android.os.Looper
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The remote control: the app's own building blocks (cards, rows, bars, panels) in a simulated Android, moved with
 * D-pad key presses. Each test is a case where the selection used to jump away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalTestApi::class)
class FocusTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() { FocusKeeper.reset(); FocusKeeper.settle = 0; tvForTests = true }
    @After fun tearDown() { FocusKeeper.reset(); FocusKeeper.settle = 150; tvForTests = null }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    /** Lets FocusKeeper (it waits a moment, for screens to choose their own selection) do its work. */
    private fun settle() {
        repeat(3) {
            rule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
    }

    private fun focus(tag: String) { rule.onNodeWithTag(tag).requestFocus(); settle() }
    private fun tag(t: String): SemanticsNodeInteraction = rule.onNodeWithTag(t)

    @Composable
    private fun Card(tag: String, modifier: Modifier = Modifier, w: Int = 60) {
        Box(modifier.size(w.dp, 60.dp).card { }.testTag(tag))
    }

    /** The side bar on the left, a top bar with Search and the profile, a row of posters under it. */
    @Composable
    private fun Screen(cards: List<String>) {
        FocusKeeperRoot { keeper ->
            Row(keeper) {
                NavArea("side bar", listOf(androidx.compose.ui.focus.FocusDirection.Left)) { nav ->
                    Column(nav) { Card("home"); Card("music") }
                }
                Column {
                    NavArea("top bar", listOf(androidx.compose.ui.focus.FocusDirection.Up)) { nav ->
                        Row(nav.width(700.dp)) { Spacer(Modifier.width(560.dp)); Card("search"); Card("profile") }
                    }
                    TvRowScroll { state ->
                        LazyRow(state = state, modifier = Modifier.width(500.dp)) {
                            items(cards, key = { it }) { Card(it, w = 90) }
                        }
                    }
                }
            }
        }
    }

    @Test fun `right at the end of a row stays on the last poster`() {
        rule.setContent { Screen(listOf("p1", "p2", "p3")) }
        focus("p1")
        press(Key.DirectionRight); press(Key.DirectionRight)
        tag("p3").assertIsFocused()
        press(Key.DirectionRight)
        tag("p3").assertIsFocused()
        tag("profile").assertIsNotFocused()
        tag("search").assertIsNotFocused()
    }

    @Test fun `up from a row goes to the top bar, left from its start to the side bar`() {
        rule.setContent { Screen(listOf("p1", "p2", "p3")) }
        focus("p1")
        press(Key.DirectionLeft)
        rule.onNode(isFocused()).assertExists()
        check(listOf("home", "music").any { runCatching { tag(it).assertIsFocused() }.isSuccess }) { "← didn't reach the side bar" }
        focus("p3")
        press(Key.DirectionUp)
        check(listOf("search", "profile").any { runCatching { tag(it).assertIsFocused() }.isSuccess }) { "↑ didn't reach the top bar" }
    }

    @Test fun `a poster that disappears hands the selection to its neighbour, not the side bar`() {
        val cards = mutableStateListOf("p1", "p2", "p3", "p4")
        rule.setContent { Screen(cards) }
        focus("p3")
        cards.remove("p3")
        settle()
        val now = listOf("p2", "p4").firstOrNull { runCatching { tag(it).assertIsFocused() }.isSuccess }
        check(now != null) { "the selection didn't go to a neighbour" }
        tag("home").assertIsNotFocused()
        // And the remote goes on from there.
        press(Key.DirectionLeft)
        rule.onNode(isFocused()).assertExists()
    }

    @Test fun `a whole list replaced (songs when one starts) keeps the selection in the list`() {
        val cards = mutableStateListOf("a1", "a2", "a3")
        rule.setContent { Screen(cards) }
        focus("a2")
        cards.clear(); cards.addAll(listOf("b1", "b2", "b3"))
        settle()
        check(listOf("b1", "b2", "b3").any { runCatching { tag(it).assertIsFocused() }.isSuccess }) { "selection left the list" }
        tag("home").assertIsNotFocused(); tag("music").assertIsNotFocused()
    }

    @Test fun `the install button keeps the selection while installing and after`() {
        var busy by mutableStateOf(false)
        var installed by mutableStateOf(false)
        rule.setContent {
            Screen(emptyList())
            FocusKeeperRoot { keeper ->
                Row(keeper) {
                    Card("before")
                    Box(Modifier.testTag("action")) {
                        StateAction(if (installed) Icons.Filled.Download else Icons.Filled.Download, if (installed) "Uninstall" else "Install", busy) { }
                    }
                }
            }
        }
        rule.onNode(hasContentDescription("Install")).requestFocus(); settle()
        busy = true; settle()
        rule.onNode(isFocused()).assertExists()
        busy = false; installed = true; settle()
        rule.onNode(isFocused()).assert(hasContentDescription("Uninstall").or(hasAnyDescendant(hasContentDescription("Uninstall"))))
    }

    @Test fun `a panel keeps the remote inside and goes back to the button that opened it`() {
        var open by mutableStateOf(false)
        val first = FocusRequester()
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Column(keeper) {
                    Row { Card("sources"); Card("subtitles"); Card("audio") }
                    if (open) FocusOverlay("panel") { trap ->
                        Column(trap) { Card("off", Modifier.focusRequester(first)); Card("english"); Card("hindi") }
                    }
                    Card("below")
                }
            }
        }
        focus("subtitles")
        open = true; settle()
        first.requestFocus(); settle()
        tag("off").assertIsFocused()
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown)
        tag("hindi").assertIsFocused()
        press(Key.DirectionUp); press(Key.DirectionUp); press(Key.DirectionUp)
        tag("off").assertIsFocused()
        press(Key.DirectionRight); press(Key.DirectionLeft)
        tag("off").assertIsFocused()
        open = false; settle()
        tag("subtitles").assertIsFocused()
    }

    @Test fun `a panel opened from a panel goes back to the first button`() {
        var panel by mutableStateOf(0)
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Column(keeper) {
                    Row { Card("sources"); Card("subtitles") }
                    when (panel) {
                        1 -> FocusOverlay("subtitles") { trap -> Column(trap) { Card("sync") } }
                        2 -> FocusOverlay("sync") { trap -> Column(trap) { Card("later") } }
                    }
                }
            }
        }
        focus("subtitles")
        panel = 1; settle(); focus("sync")
        panel = 2; settle(); focus("later")
        panel = 0; settle()
        tag("subtitles").assertIsFocused()
    }

    @Test fun `in the player a vanished button returns to a button, and nothing moves while the controls are hidden`() {
        var next by mutableStateOf(true)
        var controls by mutableStateOf(true)
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Box(keeper.size(400.dp, 200.dp).focusFallback().focusable().testTag("picture")) {
                    if (controls) Row { Card("sources"); Card("speed"); if (next) Card("next") }
                }
            }
        }
        focus("next")
        next = false; settle()
        tag("speed").assertIsFocused()
        FocusKeeper.paused = true
        focus("picture")
        controls = false; settle()
        tag("picture").assertIsFocused()
    }

    @Test fun `a search box or slider chosen on purpose is left alone`() {
        var show by mutableStateOf(true)
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Column(keeper) {
                    if (show) Card("button")
                    Card("other")
                    Box(Modifier.size(80.dp).focusable().testTag("field"))
                }
            }
        }
        focus("button")
        focus("field")
        show = false; settle()
        tag("field").assertIsFocused()
    }

    @Test fun `a screen's own selection, made just after, is kept (real timing)`() {
        FocusKeeper.settle = 150
        var show by mutableStateOf(true)
        val chosen = FocusRequester()
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Column(keeper) {
                    Card("top")
                    if (show) Card("x")
                    Card("y")
                    Card("chosen", Modifier.focusRequester(chosen))
                }
            }
        }
        focus("x")
        // x goes away (Android drops the selection and may pick one by itself)…
        show = false
        rule.waitForIdle()
        // …then, a moment later (as a screen's LaunchedEffect would), the screen chooses its own selection…
        chosen.requestFocus()
        rule.waitForIdle()
        // …and FocusKeeper, looking 150 ms after the loss, leaves it there.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        settle()
        tag("chosen").assertIsFocused()
    }

    @Test fun `with real timing a vanished card still hands over to its neighbour`() {
        FocusKeeper.settle = 150
        val cards = mutableStateListOf("p1", "p2", "p3", "p4")
        rule.setContent { Screen(cards) }
        focus("p3")
        cards.remove("p3")
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        settle()
        check(listOf("p2", "p4").any { runCatching { tag(it).assertIsFocused() }.isSuccess }) { "the selection didn't go to a neighbour" }
    }

    private fun link(name: String, q: Int) = kotlinx.coroutines.runBlocking {
        com.lagradost.cloudstream3.utils.newExtractorLink("Test", name, "https://example.com/$name.m3u8") { quality = q }
    }

    @Test fun `sources - the one you're on keeps the selection when more sources arrive`() {
        var all by mutableStateOf(listOf(link("Beta 720", 720), link("Gamma 480", 480)))
        rule.setContent {
            FocusKeeperRoot { keeper -> Box(keeper) { SourcesPanel(all, null, true, onPick = { }, onClose = { }) } }
        }
        rule.mainClock.advanceTimeBy(200)
        settle()
        // Down to the second source (Gamma).
        rule.onNode(androidx.compose.ui.test.hasText("Beta 720")).assertExists()
        rule.onNode(isFocused()).assert(androidx.compose.ui.test.hasText("Beta 720"))
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(androidx.compose.ui.test.hasText("Gamma 480"))
        // A better source is found and ranked first: the selection stays on Gamma.
        all = listOf(link("Alpha 1080", 1080)) + all
        settle()
        rule.onNode(isFocused()).assert(androidx.compose.ui.test.hasText("Gamma 480"))
    }

    /** A tab with a long list (Downloads), the player on top of it, and Back, as AppRoot does it. */
    @Composable
    private fun TabWithPlayer(playing: Boolean, items: Int = 30) {
        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        FocusKeeperRoot { keeper ->
            Row(keeper) {
                if (playing) {
                    Box(Modifier.size(400.dp).focusFallback().focusable().testTag("player"))
                    return@Row
                }
                NavArea("side bar", listOf(androidx.compose.ui.focus.FocusDirection.Left)) { nav ->
                    Column(nav) { Card("home"); Card("library") }
                }
                holder.SaveableStateProvider("LIBRARY") {
                    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
                    Column {
                        Row { Card("my list", Modifier.focusProperties { }, 80); Box(Modifier.size(80.dp, 60.dp).card { tab = 1 }.testTag("downloads")) }
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.size(300.dp, 300.dp).testTag("list")) {
                            items((0 until items).map { "${if (tab == 1) "dl" else "fav"}$it" }, key = { it }) { Card(it, w = 200) }
                        }
                    }
                }
            }
        }
    }

    @Test fun `back from the player returns to the same tab and the same item`() {
        var playing by mutableStateOf(false)
        rule.setContent { TabWithPlayer(playing) }
        focus("downloads"); rule.onNodeWithTag("downloads").performClick(); settle()
        rule.onNodeWithTag("list").performScrollToKey("dl12"); settle(); focus("dl12")
        // Play it (Nav.open), then Back (Nav.back).
        FocusKeeper.leaving("LIBRARY:0"); playing = true; settle(); focus("player")
        playing = false; FocusKeeper.returning("LIBRARY:0")
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
        settle()
        // Still on Downloads (not My list), on the same download.
        tag("dl12").assertIsFocused()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    @Test fun `right from a repository reaches its delete button, and OK still opens it`() {
        var opened = 0; var deleted = 0
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Column(keeper.width(500.dp)) {
                    ActionRow(onClick = { opened++ }, modifier = Modifier.testTag("repo"),
                        actions = { Box(Modifier.size(40.dp).card { deleted++ }.testTag("delete")) }) {
                        Box(Modifier.size(200.dp, 40.dp).testTag("name"))
                    }
                    Card("next")
                }
            }
        }
        rule.onNode(androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasTestTag("name")).and(androidx.compose.ui.test.hasClickAction()), useUnmergedTree = true)
            .requestFocus(); settle()
        press(Key.DirectionCenter)
        check(opened == 1) { "OK didn't open the repository" }
        press(Key.DirectionRight)
        tag("delete").assertIsFocused()
        press(Key.DirectionCenter)
        check(deleted == 1) { "OK on delete didn't delete" }
    }

    @Test fun `back inside a settings section goes to the section list first`() {
        val selected = FocusRequester()
        var left = 0
        rule.setContent {
            androidx.activity.compose.BackHandler { left++ }   // what AppRoot does: leave the page
            FocusKeeperRoot { keeper ->
                Row(keeper) {
                    Column { Card("profiles"); Card("player", Modifier.focusRequester(selected)); Card("about") }
                    Column(backToHeader(selected)) { Card("option1"); Card("option2") }
                }
            }
        }
        focus("option2")
        back()
        tag("player").assertIsFocused()
        check(left == 0) { "Back left the page instead of going to the list" }
        back()
        check(left == 1) { "Back from the list didn't leave the page" }
    }

    /** The remote's Back key as the TV sends it: down and up through the activity (not straight to the app's code). */
    private fun backKey() {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        settle()
    }

    @Test fun `one press of back closes a player panel`() {
        var open by mutableStateOf(true)
        val first = FocusRequester()
        rule.setContent {
            FocusKeeperRoot { keeper ->
                Box(keeper.size(600.dp).focusFallback().focusable()) {
                    if (open) androidx.activity.compose.BackHandler { open = false }
                    Column {
                        Row { Card("sources"); Card("subtitles") }
                        if (open) FocusOverlay("panel") { trap ->
                            Column(trap) { Card("off", Modifier.focusRequester(first)); Card("english") }
                        }
                    }
                }
            }
        }
        focus("subtitles"); first.requestFocus(); settle(); focus("english")
        backKey()
        check(!open) { "the first Back didn't close the panel" }
    }

    @Test fun `one press of back leaves the side bar and a row`() {
        var backs = 0
        rule.setContent {
            androidx.activity.compose.BackHandler { backs++ }
            Screen(listOf("p1", "p2"))
        }
        focus("p2"); backKey()
        check(backs == 1) { "Back on a poster row was swallowed" }
        focus("music"); backKey()
        check(backs == 2) { "Back in the side bar was swallowed" }
    }

    @Test fun `a mouse click on a row's arrow doesn't send the page back to the top`() {
        val page = androidx.compose.foundation.lazy.LazyListState()
        rule.setContent {
            FocusKeeperRoot { keeper ->
                androidx.compose.foundation.lazy.LazyColumn(keeper.size(600.dp, 400.dp), state = page) {
                    items((0 until 12).toList(), key = { it }) { r ->
                        TvRowScroll { state ->
                            LazyRow(state = state, modifier = Modifier.width(600.dp).testTag("row$r")) {
                                items((0 until 20).toList(), key = { it }) { c -> Card("r${r}c$c", w = 120) }
                            }
                        }
                    }
                }
            }
        }
        rule.runOnIdle { kotlinx.coroutines.runBlocking { page.scrollToItem(6) } }
        settle()
        val before = page.firstVisibleItemIndex
        // The mouse clicks a card (selects it), then the row's › arrow, a few times (the card goes off screen).
        tag("r6c0").performMouseInput { moveTo(center); click(center) }
        settle()
        repeat(3) {
            tag("row6").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(width - 30f, height / 2f)) }
            settle()
            rule.onAllNodes(hasContentDescription("Forward"))[0].performMouseInput { moveTo(center); click(center) }
            settle()
        }
        rule.mainClock.advanceTimeBy(2_000); settle()
        check(rule.onAllNodesWithTag("r6c0").fetchSemanticsNodes().isEmpty()) { "the arrow didn't move the row" }
        check(page.firstVisibleItemIndex >= before) { "the page jumped from row $before to row ${page.firstVisibleItemIndex}" }
        check(runCatching { tag("r0c0").assertIsFocused() }.isFailure) { "the selection jumped to the first row" }
    }
}
