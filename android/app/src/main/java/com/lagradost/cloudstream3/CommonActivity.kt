package com.lagradost.cloudstream3

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.utils.UiText

/** The toast helpers of upstream's CommonActivity that extensions call. */
object CommonActivity {
    var activity: Activity? = null

    fun showToast(message: Int, duration: Int? = null) {
        activity?.let { showToast(it, it.getString(message), duration) }
    }

    fun showToast(message: String?, duration: Int? = null) = showToast(activity, message, duration)

    fun showToast(act: Activity?, message: Int, duration: Int? = null) {
        showToast(act, act?.getString(message), duration)
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (message == null) return
        val context = act ?: CloudStreamApp.context ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, duration ?: Toast.LENGTH_SHORT).show()
        }
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        if (message == null) return
        showToast(activity, message.asStringNull(activity), duration)
    }

    fun showToast(act: Activity?, text: UiText, duration: Int) {
        showToast(act, text.asStringNull(act ?: activity), duration)
    }
}
