package com.musky.onegesture

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "gb_prefs"

    const val K_ENABLED = "enabled"
    const val K_WIDTH = "width_dp"
    const val K_THICK = "thickness_dp"
    const val K_MARGIN = "bottom_margin_dp"
    const val K_STRIP = "strip_dp"
    const val K_OPACITY = "opacity"
    const val K_THEME = "theme"
    const val K_BACKDROP = "backdrop_v2"
    const val K_BACKDROP_LOCK = "backdrop_lock_v2"
    const val K_GUIDE = "guide"
    const val K_AUTOFIT = "autofit_done_v2"
    const val K_AUTOHIDE = "autohide"
    const val K_GRAY = "gray"

    const val THEME_AUTO = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2
    const val THEME_GRAY = 3

    data class Config(
        val enabled: Boolean = true,
        val widthDp: Int = 112,
        val thicknessDp: Int = 5,
        val bottomMarginDp: Int = 5,
        val stripDp: Int = 24,
        val opacity: Int = 100,
        val theme: Int = THEME_GRAY,
        val backdrop: Boolean = false,
        val backdropOnLock: Boolean = false,
        val guide: Boolean = false,
        val autoHide: Boolean = true,
        val gray: Int = 140
    )

    fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): Config {
        val s = sp(c)
        val d = Config()
        return Config(
            enabled = s.getBoolean(K_ENABLED, d.enabled),
            widthDp = s.getInt(K_WIDTH, d.widthDp),
            thicknessDp = s.getInt(K_THICK, d.thicknessDp),
            bottomMarginDp = s.getInt(K_MARGIN, d.bottomMarginDp),
            stripDp = s.getInt(K_STRIP, d.stripDp),
            opacity = s.getInt(K_OPACITY, d.opacity),
            theme = s.getInt(K_THEME, d.theme),
            backdrop = s.getBoolean(K_BACKDROP, d.backdrop),
            backdropOnLock = s.getBoolean(K_BACKDROP_LOCK, d.backdropOnLock),
            guide = s.getBoolean(K_GUIDE, d.guide),
            autoHide = s.getBoolean(K_AUTOHIDE, d.autoHide),
            gray = s.getInt(K_GRAY, d.gray)
        )
    }
}
