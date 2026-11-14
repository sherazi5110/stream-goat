package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * The official app's Kitsu account (syncproviders/providers/KitsuApi.kt), as far as extensions use it: some build a
 * SyncRepo around it while they're created (CineStream with Simkl). No sign-in in this app, so its lists are empty.
 */
class KitsuApi : SyncAPI() {
    override val name = "Kitsu"
    override val idPrefix = "kitsu"
    override val syncIdName = "Kitsu"
}
