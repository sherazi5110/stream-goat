package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.Event

/**
 * The official app's main activity class, for extensions that use its app events (StreamPlay, StremioX,
 * Ultima, CineStream fire afterPluginsLoadedEvent after changing providers). There's no activity on Windows and
 * Linux; the events work (Stream Goat refreshes its screens when they fire).
 */
abstract class MainActivity(dataDir: java.io.File) : AppCompatActivity(dataDir) {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEventTv = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()

        init {
            // An extension that changed the providers asks the app to refresh, as in the official app.
            afterPluginsLoadedEvent += { com.lagradost.cloudstream3.linux.PluginManager.bumpVersion() }
            reloadHomeEvent += { com.lagradost.cloudstream3.linux.PluginManager.bumpVersion() }
        }
    }
}
