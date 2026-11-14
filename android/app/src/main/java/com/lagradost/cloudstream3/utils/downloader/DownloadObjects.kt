package com.lagradost.cloudstream3.utils.downloader

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType

/** The official app's saved download and resume records (utils/downloader/DownloadObjects.kt) that extensions read. */
object DownloadObjects {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DownloadHeaderCached(
        @JsonProperty("apiName") val apiName: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("type") val type: TvType,
        @JsonProperty("name") val name: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("cacheTime") val cacheTime: Long,
        @JsonProperty("id") val id: Int,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ResumeWatching(
        @JsonProperty("parentId") val parentId: Int,
        @JsonProperty("episodeId") val episodeId: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("updateTime") val updateTime: Long,
        @JsonProperty("isFromDownload") val isFromDownload: Boolean,
    )
}
