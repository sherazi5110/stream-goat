package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

// The official app's video actions (actions/VideoClickAction.kt): extensions register their own with
// Plugin.registerVideoClickAction (M6+, RMC+, TF1+, StreamCenter) while loading. Without this class those
// extensions don't load at all. The actions are kept here like in the official app.

object VideoClickActionHolder {
    val allVideoClickActions = atomicListOf<VideoClickAction>()

    private const val ACTION_ID_OFFSET = 1000

    fun makeOptionMap(activity: Activity?, video: ResultEpisode) = allVideoClickActions
        .mapIndexed { id, it -> it to id + ACTION_ID_OFFSET }
        .filter { it.first.shouldShowSafe(activity, video) }
        .map { it.first.name to it.second }

    fun getActionById(id: Int): VideoClickAction? = allVideoClickActions.getOrNull(id - ACTION_ID_OFFSET)

    fun getByUniqueId(uniqueId: String): VideoClickAction? = allVideoClickActions.firstOrNull { it.uniqueId() == uniqueId }

    fun uniqueIdToId(uniqueId: String?): Int? {
        if (uniqueId == null) return null
        return allVideoClickActions
            .mapIndexed { id, it -> it to id + ACTION_ID_OFFSET }
            .firstOrNull { it.first.uniqueId() == uniqueId }
            ?.second
    }

    fun getPlayers(context: Context? = null) = allVideoClickActions.filter { it.isPlayer && it.shouldShowSafe(context, null) }

    /** Called when an extension is removed, like the official unloadPlugin. */
    fun removeFrom(sourcePlugin: String?) {
        allVideoClickActions.withLock { allVideoClickActions.removeAll { it.sourcePlugin == sourcePlugin } }
    }
}

abstract class VideoClickAction {
    abstract val name: UiText

    /** if true, the app will show dialog to select source - result.links[index] */
    open val oneSource: Boolean = false

    /** if true, this action could be selected as default player (one press action) in settings */
    open val isPlayer: Boolean = false

    /** Which type of sources this action can handle. */
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    /** Determines which plugin a given provider is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null

    /** Runs [callable] on the UI thread and returns its result, like the official app. */
    @Throws
    suspend fun <T> uiThread(callable: Callable<T>): T? {
        val future = FutureTask {
            try {
                Result.success(callable.call())
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        CommonActivity.activity?.runOnUiThread(future) ?: throw ErrorLoadingException("No UI Activity, this should never happened")
        val result = withContext(Dispatchers.IO) { future.get() }
        return result.getOrThrow()
    }

    fun uniqueId() = "$sourcePlugin:${this::class.qualifiedName}"

    @Throws
    abstract fun shouldShow(context: Context?, video: ResultEpisode?): Boolean

    fun shouldShowSafe(context: Context?, video: ResultEpisode?): Boolean = try {
        shouldShow(context, video)
    } catch (t: Throwable) {
        false
    }

    @Throws
    abstract suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?)

    fun runActionSafe(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?): Job =
        scope.launch {
            try {
                runAction(context, video, result, index)
            } catch (_: NotImplementedError) {
                CommonActivity.showToast("runAction has not been implemented for ${name.asStringNull(context)}, please contact the extension developer of $sourcePlugin", 1)
            } catch (error: ErrorLoadingException) {
                CommonActivity.showToast(error.message, 1)
            } catch (t: Throwable) {
                CommonActivity.showToast(t.toString(), 1)
            }
        }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
