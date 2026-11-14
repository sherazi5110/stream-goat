package com.lagradost.cloudstream3.utils

/** The official app's UIHelper, as far as extensions use it outside Android views (sizes). */
object UIHelper {
    val Int.toPx: Int get() = this
    val Float.toPx: Float get() = this
    val Int.toDp: Int get() = this
    val Float.toDp: Float get() = this
}
