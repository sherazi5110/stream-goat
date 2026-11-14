package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink

// Parts of the official app's player classes that extensions touch from outside the player.

const val PREFERRED_AUDIO_LANGUAGE_KEY = "preferred_audio_language"

/** The official ExoPlayer wrapper. Extensions (PrimeVideo) read and set its saved preferred audio language. */
class CS3IPlayer private constructor() {
    companion object {
        /** Kept per profile, like the official app. */
        var preferredAudioTrackLanguage: String?
            get() = CloudStreamApp.getKey<String>("${DataStoreHelper.currentAccount}/$PREFERRED_AUDIO_LANGUAGE_KEY")
            set(value) {
                val key = "${DataStoreHelper.currentAccount}/$PREFERRED_AUDIO_LANGUAGE_KEY"
                if (value == null) CloudStreamApp.removeKey(key) else CloudStreamApp.setKey(key, value)
            }
    }
}

/** The official app's link cache entry (ui/player/RepoLinkGenerator.kt). */
data class Cache(
    val linkCache: MutableSet<ExtractorLink>,
    val subtitleCache: MutableSet<SubtitleData>,
    /** When it was last updated */
    var lastCachedTimestamp: Long = System.currentTimeMillis() / 1000,
    /** If it has fully loaded */
    var saturated: Boolean,
)

/**
 * The official app's link generator. Extensions (M6+, RMC+, TF1+) clear its cache when their links expire;
 * this app keeps its own source cache (Engine), which expires links by itself.
 */
class RepoLinkGenerator private constructor() {
    companion object {
        const val TAG = "RepoLink"
        val cache: HashMap<Pair<String, Int>, Cache> = hashMapOf()
    }
}
