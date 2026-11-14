@file:JvmName("TextUtilKt")

package com.lagradost.cloudstream3.utils

import android.content.Context

// The official app's UI text (utils/TextUtil.kt). The file keeps the official class name, TextUtilKt, because
// extensions call txt(...) through it (Aniyomi, M6+, RMC+, TF1+, StreamCenter).

/** A plain string or a string resource, like the official app. */
sealed class UiText {
    companion object {
        const val TAG = "UiText"
    }

    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value
    }

    class StringResource(val resId: Int, val args: List<Any>) : UiText() {
        override fun toString(): String = "resId = $resId\nargs = $args"
        override fun equals(other: Any?): Boolean = other is StringResource && resId == other.resId && args == other.args
        override fun hashCode(): Int = 31 * resId + args.hashCode()
    }

    fun asStringNull(context: Context?): String? = runCatching { asString(context ?: return null) }.getOrNull()

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> {
            val str = context.getString(resId)
            if (args.isEmpty()) str
            else str.format(*args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
        }
    }
}

fun txt(value: String): UiText = UiText.DynamicString(value)

@JvmName("txtNull")
fun txt(value: String?): UiText? = UiText.DynamicString(value ?: return null)

fun txt(resId: Int, vararg args: Any): UiText = UiText.StringResource(resId, args.toList())

@JvmName("txtNull")
fun txt(resId: Int?, vararg args: Any?): UiText? {
    if (resId == null || args.any { it == null }) return null
    return UiText.StringResource(resId, args.filterNotNull().toList())
}
