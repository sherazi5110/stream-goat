package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * The official app's AniList account (syncproviders/providers/AniListApi.kt). Extensions use its data classes to
 * read AniList answers (StreamPlay, TorraStream, Anichi): copied with the same fields. No sign-in in this app.
 */
class AniListApi : SyncAPI() {
    override val name = "AniList"
    override val idPrefix = "anilist"
    override val syncIdName = "Anilist"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Title(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        @JsonProperty("medium") val medium: String?,
        @JsonProperty("large") val large: String?,
        @JsonProperty("extraLarge") val extraLarge: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LikePageInfo(
        @JsonProperty("total") val total: Int?,
        @JsonProperty("currentPage") val currentPage: Int?,
        @JsonProperty("lastPage") val lastPage: Int?,
        @JsonProperty("perPage") val perPage: Int?,
        @JsonProperty("hasNextPage") val hasNextPage: Boolean?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String?,
        @JsonProperty("large") val large: String?,
        @JsonProperty("medium") val medium: String?,
        @JsonProperty("color") val color: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaTitle(
        @JsonProperty("romaji") val romaji: String?,
        @JsonProperty("english") val english: String?,
        @JsonProperty("native") val native: String?,
        @JsonProperty("userPreferred") val userPreferred: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Recommendation(
        @JsonProperty("mediaRecommendation") val mediaRecommendation: RecommendedMedia?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecommendationConnection(
        @JsonProperty("edges") val edges: List<RecommendationEdge> = emptyList(),
        @JsonProperty("nodes") val nodes: List<Recommendation> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecommendationEdge(
        @JsonProperty("node") val node: Recommendation,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecommendedMedia(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: MediaTitle?,
        @JsonProperty("coverImage") val coverImage: MediaCoverImage?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonNextAiringEpisode(
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("timeUntilAiring") val timeUntilAiring: Int?,
    )
}
