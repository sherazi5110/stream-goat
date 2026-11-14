package com.lagradost.cloudstream3.utils

/** The official app's AppContextUtils, as far as extensions use it: open a title page. */
object AppContextUtils {
    fun loadResult(url: String, apiName: String, name: String, startAction: Int = 0, startValue: Int = 0) {
        javafx.application.Platform.runLater { com.lagradost.cloudstream3.linux.ui.Nav.open(apiName, url, name, null) }
    }
}
