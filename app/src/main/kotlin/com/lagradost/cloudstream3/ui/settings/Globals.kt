package com.lagradost.cloudstream3.ui.settings

/** The official app's layout flags (ui/settings/Globals.kt). Windows and Linux count as the phone layout. */
object Globals {
    const val PHONE: Int = 0b00001
    const val TV: Int = 0b00010
    const val EMULATOR: Int = 0b00100

    fun isLayout(flags: Int): Boolean = (PHONE and flags) != 0

    fun isLandscape(): Boolean = true
}
