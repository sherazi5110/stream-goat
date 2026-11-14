package com.lagradost.cloudstream3.ui.settings.extensions

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** The official app's repository entry (ExtensionsViewModel.kt), for extensions that list repositories. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryData(
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("description") val description: String? = null,
) {
    constructor(name: String, url: String) : this(null, name, url)

    /** Older official versions had this one; MegaRepo still calls it. */
    constructor(iconUrl: String?, name: String, url: String) : this(iconUrl, name, url, null)
}
