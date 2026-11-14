package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink

// The official app's episode and link types (ui/result/ResultFragment.kt, ResultViewModel2.kt) that extension
// actions receive (see actions.VideoClickAction).

enum class VideoWatchState {
    /** Default value when no key is set */
    None,
    Watched,
}

data class ResultEpisode(
    val headerName: String,
    val name: String?,
    val poster: String?,
    val episode: Int,
    val seasonIndex: Int?,
    val season: Int?,
    val data: String,
    val apiName: String,
    val id: Int,
    val index: Int,
    val position: Long,
    val duration: Long,
    val score: Score?,
    val description: String?,
    val isFiller: Boolean?,
    val tvType: TvType,
    val parentId: Int,
    val videoWatchState: VideoWatchState,
    val totalEpisodeIndex: Int? = null,
    val airDate: Long? = null,
    val runTime: Int? = null,
    val seasonData: SeasonData? = null,
)

data class LinkLoadingResult(
    val links: List<ExtractorLink>,
    val subs: List<SubtitleData>,
    val syncData: HashMap<String, String>,
)
