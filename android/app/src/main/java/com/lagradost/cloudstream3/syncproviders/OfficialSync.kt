package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.utils.UiText

// The official app's sync accounts (AniList, MAL…), as far as extensions use them (StreamPlay, TorraStream read
// the signed-in AniList user's lists). This app has no account sync yet, so nobody is signed in: those
// extensions then skip their "my list" features, as they do in the official app without a login.

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthUser(
    @JsonProperty("name") val name: String?,
    @JsonProperty("id") val id: Int,
    @JsonProperty("profilePicture") val profilePicture: String? = null,
    @JsonProperty("profilePictureHeaders") val profilePictureHeaders: Map<String, String>? = null,
)

abstract class SyncAPI {
    open val name: String = ""
    open val idPrefix: String = ""
    open val syncIdName: String = ""

    data class LibraryMetadata(val allLibraryLists: List<LibraryList>, val supportedListSorting: Set<Any>)
    data class LibraryList(val name: UiText, val items: List<Any>)
}

open class AuthRepo(open val api: SyncAPI) {
    /** Nobody is signed in in this app. */
    fun authUser(): AuthUser? = null
}

class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName = api.syncIdName
    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = Result.success(null)
}

class AccountManager {
    companion object {
        val aniListApi = AniListApi()
        // CineStream creates a SyncRepo around Simkl when it loads; without it, that provider failed to load.
        val simklApi = com.lagradost.cloudstream3.syncproviders.providers.SimklApi()
        val malApi = com.lagradost.cloudstream3.syncproviders.providers.MALApi()
        val kitsuApi = com.lagradost.cloudstream3.syncproviders.providers.KitsuApi()
    }
}
