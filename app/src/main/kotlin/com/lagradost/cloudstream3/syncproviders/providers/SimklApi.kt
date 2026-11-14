package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * The official app's Simkl account (syncproviders/providers/SimklApi.kt), as far as extensions use it: some build a
 * SyncRepo around it while they're created (CineStream with Simkl). No sign-in in this app, so its lists are empty.
 */
class SimklApi : SyncAPI() {
    override val name = "Simkl"
    override val idPrefix = "simkl"
    override val syncIdName = "Simkl"
}
