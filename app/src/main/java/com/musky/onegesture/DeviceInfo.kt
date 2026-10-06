package com.musky.onegesture

import android.content.Context
import android.content.res.Resources
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads the real gesture bar geometry from the phone itself (framework + SystemUI
 * resources) so the overlay can be placed exactly on top of the system pill.
 */
object DeviceInfo {

    data class Probe(
        val density: Float,
        val widthPx: Int,
        val heightPx: Int,
        val navFrameDp: Float?,
        val gestureDp: Float?,
        val handleWidthDp: Float?,
        val handleRadiusDp: Float?,
        val handleBottomDp: Float?
    ) {
        val hasHandle: Boolean
            get() = handleWidthDp != null && handleRadiusDp != null && handleBottomDp != null
    }

    private fun dimen(res: Resources, name: String, pkg: String, density: Float): Float? {
        return try {
            val id = res.getIdentifier(name, "dimen", pkg)
            if (id == 0) null else res.getDimension(id) / density
        } catch (_: Exception) {
            null
        }
    }

    fun probe(ctx: Context): Probe {
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val sys = Resources.getSystem()
        val nav = dimen(sys, "navigation_bar_frame_height", "android", d)
            ?: dimen(sys, "navigation_bar_height", "android", d)
        val gesture = dimen(sys, "navigation_bar_gesture_height", "android", d)

        var w: Float? = null
        var r: Float? = null
        var b: Float? = null
        try {
            val ui = ctx.packageManager.getResourcesForApplication("com.android.systemui")
            w = dimen(ui, "navigation_home_handle_width", "com.android.systemui", d)
            r = dimen(ui, "navigation_handle_radius", "com.android.systemui", d)
            b = dimen(ui, "navigation_handle_bottom", "com.android.systemui", d)
        } catch (_: Exception) {
        }
        return Probe(d, dm.widthPixels, dm.heightPixels, nav, gesture, w, r, b)
    }

    /** Sizes the overlay pill to sit exactly over the system pill (slightly larger so it fully covers it). */
    fun autoFit(ctx: Context): Boolean {
        val p = probe(ctx)
        if (!p.hasHandle) return false

        val thick = (p.handleRadiusDp!! * 2f + 2f).roundToInt().coerceIn(2, 10)
        val width = (p.handleWidthDp!! + 4f).roundToInt().coerceIn(40, 220)
        val margin = (p.handleBottomDp!! - 1f).roundToInt().coerceIn(0, 40)
        val stripBase = (p.gestureDp ?: p.navFrameDp ?: 24f).roundToInt()
        val strip = max(stripBase, margin + thick + 2).coerceIn(10, 60)

        Prefs.sp(ctx).edit()
            .putInt(Prefs.K_WIDTH, width)
            .putInt(Prefs.K_THICK, thick)
            .putInt(Prefs.K_MARGIN, margin)
            .putInt(Prefs.K_STRIP, strip)
            .putInt(Prefs.K_OPACITY, 100)
            .putBoolean(Prefs.K_BACKDROP, false)
            .apply()
        return true
    }

    private fun fmt(v: Float?): String = v?.let { "%.1f".format(it) } ?: "n/a"

    fun describe(p: Probe): String = buildString {
        appendLine(
            "Screen: ${p.widthPx}x${p.heightPx}px, density ${"%.2f".format(p.density)} " +
                "(${(p.density * 160).roundToInt()}dpi)"
        )
        appendLine("Nav bar height: ${fmt(p.navFrameDp)}dp, gesture area: ${fmt(p.gestureDp)}dp")
        append(
            if (p.hasHandle)
                "System pill: ${fmt(p.handleWidthDp)}dp wide, ${fmt(p.handleRadiusDp?.times(2f))}dp thick, " +
                    "${fmt(p.handleBottomDp)}dp from bottom"
            else
                "System pill: not found on this ROM (adjust manually with the sliders)"
        )
    }
}
