package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * The official app's MyAnimeList account (syncproviders/providers/MALApi.kt), as far as extensions use it: some build a
 * SyncRepo around it while they're created (CineStream with Simkl). No sign-in in this app, so its lists are empty.
 */
class MALApi : SyncAPI() {
    override val name = "MAL"
    override val idPrefix = "mal"
    override val syncIdName = "MAL"
}
