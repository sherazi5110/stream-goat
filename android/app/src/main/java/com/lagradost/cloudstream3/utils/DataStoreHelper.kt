package com.lagradost.cloudstream3.utils

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import io.github.sherazi5110.cloudstream.Profiles

/**
 * The official app's DataStoreHelper, as far as extensions use it (Ultima, WatchParty): its "accounts" are this
 * app's profiles, and resume-watching comes from this app's library (see ui.home.HomeViewModel).
 */

// The official app's key names (utils/DataStoreHelper.kt), so extensions that read and write these records
// (SyncProvider, Wrapped, StreamCenter) find each other's data in the same places.
const val VIDEO_POS_DUR = "video_pos_dur"
const val VIDEO_WATCH_STATE = "video_watch_state"
const val RESULT_WATCH_STATE = "result_watch_state"
const val RESULT_WATCH_STATE_DATA = "result_watch_state_data"
const val RESULT_SUBSCRIBED_STATE_DATA = "result_subscribed_state_data"
const val RESULT_FAVORITES_STATE_DATA = "result_favorites_state_data"
const val RESULT_RESUME_WATCHING = "result_resume_watching_2"
const val RESULT_EPISODE = "result_episode"
const val RESULT_SEASON = "result_season"
const val RESULT_DUB = "result_dub"

object DataStoreHelper {
    data class Account(
        @JsonProperty("keyIndex") val keyIndex: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") val lockPin: String? = null,
    )

    data class PosDur(
        @JsonProperty("position") val position: Long,
        @JsonProperty("duration") val duration: Long,
    )

    data class ResumeWatchingResult(
        @JsonProperty("name") override val name: String,
        @JsonProperty("url") override val url: String,
        @JsonProperty("apiName") override val apiName: String,
        @JsonProperty("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") override var id: Int?,
        @JsonProperty("parentId") val parentId: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") override var score: Score? = null,
    ) : SearchResponse

    var accounts: Array<Account>
        get() = Profiles.all().mapIndexed { i, p -> Account(i, p.name, null, i, p.pin) }.toTypedArray()
        set(_) { /* Profiles are managed in this app's own screens. */ }

    var selectedKeyIndex: Int
        get() = Profiles.all().indexOfFirst { it.id == Profiles.current.id }.coerceAtLeast(0)
        set(value) { Profiles.all().getOrNull(value)?.let { Profiles.switchTo(it.id) } }

    val currentAccount: String get() = selectedKeyIndex.toString()

    fun getDefaultAccount(context: Context): Account =
        accounts.firstOrNull { it.keyIndex == 0 } ?: Account(keyIndex = 0, name = "Default", defaultImageIndex = 0)

    fun getAccounts(context: Context): List<Account> = accounts.toMutableList().apply {
        val item = getDefaultAccount(context)
        remove(item)
        add(0, item)
    }

    /** The official app's saved-library entry types (bookmarks, subscriptions, favourites). */
    abstract class LibrarySearchResponse : SearchResponse {
        abstract val latestUpdatedTime: Long
        abstract val year: Int?
        abstract val syncData: Map<String, String>?
        abstract val plot: String?
        abstract val tags: List<String>?
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubscribedData(
        @JsonProperty("subscribedTime") val subscribedTime: Long,
        @JsonProperty("lastSeenEpisodeCount") val lastSeenEpisodeCount: Map<DubStatus, Int?>,
        @JsonProperty("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") override val name: String,
        @JsonProperty("url") override val url: String,
        @JsonProperty("apiName") override val apiName: String,
        @JsonProperty("type") override var type: TvType?,
        @JsonProperty("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") override val year: Int?,
        @JsonProperty("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") override val plot: String? = null,
        @JsonProperty("score") override var score: Score? = null,
        @JsonProperty("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BookmarkedData(
        @JsonProperty("bookmarkedTime") val bookmarkedTime: Long,
        @JsonProperty("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") override val name: String,
        @JsonProperty("url") override val url: String,
        @JsonProperty("apiName") override val apiName: String,
        @JsonProperty("type") override var type: TvType?,
        @JsonProperty("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") override val year: Int?,
        @JsonProperty("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") override val plot: String? = null,
        @JsonProperty("score") override var score: Score? = null,
        @JsonProperty("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FavoritesData(
        @JsonProperty("favoritesTime") val favoritesTime: Long,
        @JsonProperty("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") override val name: String,
        @JsonProperty("url") override val url: String,
        @JsonProperty("apiName") override val apiName: String,
        @JsonProperty("type") override var type: TvType?,
        @JsonProperty("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") override val year: Int?,
        @JsonProperty("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") override val plot: String? = null,
        @JsonProperty("score") override var score: Score? = null,
        @JsonProperty("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse()

    private fun ids(folder: String): List<Int>? {
        val path = "$currentAccount/$folder"
        return CloudStreamApp.getKeys(path)?.mapNotNull { it.removePrefix("$path/").toIntOrNull() }
    }

    fun getAllWatchStateIds(): List<Int>? = ids(RESULT_WATCH_STATE)

    fun getAllResumeStateIds(): List<Int>? = ids(RESULT_RESUME_WATCHING)

    fun deleteAllResumeStateIds() {
        CloudStreamApp.removeKeys("$currentAccount/$RESULT_RESUME_WATCHING")
    }

    fun setLastWatched(parentId: Int?, episodeId: Int?, episode: Int?, season: Int?, isFromDownload: Boolean = false, updateTime: Long? = null) {
        if (parentId == null) return
        CloudStreamApp.setKey(
            "$currentAccount/$RESULT_RESUME_WATCHING", parentId.toString(),
            DownloadObjects.ResumeWatching(parentId, episodeId, episode, season, updateTime ?: System.currentTimeMillis(), isFromDownload),
        )
    }

    fun removeLastWatched(parentId: Int?) {
        if (parentId == null) return
        CloudStreamApp.removeKey("$currentAccount/$RESULT_RESUME_WATCHING", parentId.toString())
    }

    fun getLastWatched(id: Int?): DownloadObjects.ResumeWatching? {
        if (id == null) return null
        return CloudStreamApp.getKey<DownloadObjects.ResumeWatching>("$currentAccount/$RESULT_RESUME_WATCHING", id.toString())
    }

    fun setBookmarkedData(id: Int?, data: BookmarkedData) {
        if (id == null) return
        CloudStreamApp.setKey("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString(), data)
    }

    fun deleteBookmarkedData(id: Int?) {
        if (id == null) return
        CloudStreamApp.removeKey("$currentAccount/$RESULT_WATCH_STATE", id.toString())
        CloudStreamApp.removeKey("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString())
    }

    fun getBookmarkedData(id: Int?): BookmarkedData? {
        if (id == null) return null
        return CloudStreamApp.getKey<BookmarkedData>("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString())
    }

    fun getAllBookmarkedData(): List<BookmarkedData> =
        CloudStreamApp.getKeys("$currentAccount/$RESULT_WATCH_STATE_DATA")?.mapNotNull { CloudStreamApp.getKey<BookmarkedData>(it) } ?: emptyList()

    fun getAllSubscriptions(): List<SubscribedData> =
        CloudStreamApp.getKeys("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA")?.mapNotNull { CloudStreamApp.getKey<SubscribedData>(it) } ?: emptyList()

    fun getAllFavorites(): List<FavoritesData> =
        CloudStreamApp.getKeys("$currentAccount/$RESULT_FAVORITES_STATE_DATA")?.mapNotNull { CloudStreamApp.getKey<FavoritesData>(it) } ?: emptyList()

    fun setViewPos(id: Int?, pos: Long, dur: Long) {
        if (id == null) return
        if (dur < 30_000) return // Too short to remember, like the official app.
        CloudStreamApp.setKey("$currentAccount/$VIDEO_POS_DUR", id.toString(), PosDur(pos, dur))
    }

    fun getViewPos(id: Int?): PosDur? {
        if (id == null) return null
        return CloudStreamApp.getKey<PosDur>("$currentAccount/$VIDEO_POS_DUR", id.toString(), null)
    }

    fun getVideoWatchState(id: Int?): VideoWatchState? {
        if (id == null) return null
        return CloudStreamApp.getKey<VideoWatchState>("$currentAccount/$VIDEO_WATCH_STATE", id.toString(), null)
    }

    fun setVideoWatchState(id: Int?, watchState: VideoWatchState) {
        if (id == null) return
        if (watchState == VideoWatchState.None) CloudStreamApp.removeKey("$currentAccount/$VIDEO_WATCH_STATE", id.toString())
        else CloudStreamApp.setKey("$currentAccount/$VIDEO_WATCH_STATE", id.toString(), watchState)
    }

    fun getDub(id: Int): DubStatus? =
        DubStatus.entries.getOrNull(CloudStreamApp.getKey<Int>("$currentAccount/$RESULT_DUB", id.toString(), -1) ?: -1)

    fun setDub(id: Int, status: DubStatus) {
        CloudStreamApp.setKey("$currentAccount/$RESULT_DUB", id.toString(), status.ordinal)
    }

    fun setResultWatchState(id: Int?, status: Int) {
        if (id == null) return
        if (status == WatchType.NONE.internalId) deleteBookmarkedData(id)
        else CloudStreamApp.setKey("$currentAccount/$RESULT_WATCH_STATE", id.toString(), status)
    }

    fun getResultWatchState(id: Int): WatchType =
        WatchType.fromInternalId(CloudStreamApp.getKey<Int>("$currentAccount/$RESULT_WATCH_STATE", id.toString(), null))

    fun getResultSeason(id: Int): Int? = CloudStreamApp.getKey<Int>("$currentAccount/$RESULT_SEASON", id.toString(), null)

    fun setResultSeason(id: Int, value: Int?) {
        CloudStreamApp.setKey("$currentAccount/$RESULT_SEASON", id.toString(), value)
    }

    fun getResultEpisode(id: Int): Int? = CloudStreamApp.getKey<Int>("$currentAccount/$RESULT_EPISODE", id.toString(), null)

    fun setResultEpisode(id: Int, value: Int?) {
        CloudStreamApp.setKey("$currentAccount/$RESULT_EPISODE", id.toString(), value)
    }
}
