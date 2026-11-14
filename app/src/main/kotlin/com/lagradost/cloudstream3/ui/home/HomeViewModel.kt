package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.utils.DataStoreHelper

/** The official app's home view model, as far as extensions use it (Ultima, WatchParty): Continue Watching. */
class HomeViewModel {
    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? =
            Library.continueWatching().map { e ->
                DataStoreHelper.ResumeWatchingResult(
                    name = e.name, url = e.url, apiName = e.apiName, posterUrl = e.posterUrl,
                    watchPos = DataStoreHelper.PosDur((e.position * 1000).toLong(), (e.duration * 1000).toLong()),
                    id = e.url.hashCode(), parentId = null, episode = null, season = null, isFromDownload = false,
                )
            }
    }
}
