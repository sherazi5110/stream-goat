package com.lagradost.cloudstream3.linux

import java.util.Properties

/**
 * The Google sign-in client for Drive backup: from the build (see app/build.gradle.kts, cloudConfig), or for
 * development from -Dstreamgoat.google_client_id=… / the STREAMGOAT_GOOGLE_CLIENT_ID environment variable.
 */
object CloudConfig {
    private val built: Properties by lazy {
        Properties().apply { CloudConfig::class.java.getResourceAsStream("/stream-goat-cloud.properties")?.use { load(it) } }
    }

    fun value(name: String): String =
        System.getProperty("streamgoat." + name.lowercase())?.takeIf { it.isNotBlank() }
            ?: System.getenv("STREAMGOAT_$name")?.takeIf { it.isNotBlank() }
            ?: built.getProperty(name).orEmpty()
}
