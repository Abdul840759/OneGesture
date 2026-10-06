package com.musky.onegesture

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Purely visual view: optional solid backdrop strip + a One UI style capsule.
 * Never handles touch.
 */
class PillView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var cfg: Prefs.Config = Prefs.Config()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var locked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** What the system says about the nav bar: true = light bar (dark pill), false = dark bar (white pill), null = unknown. */
    var systemLightNav: Boolean? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (cfg.theme) {
            Prefs.THEME_DARK -> true
            Prefs.THEME_LIGHT -> false
            else -> systemLightNav?.not() ?: night
        }

        val w = width.toFloat()
        val h = height.toFloat()

        // Backdrop strip: hides the real gesture pill underneath.
        if (cfg.backdrop && (!locked || cfg.backdropOnLock)) {
            paint.color = if (dark) Color.BLACK else Color.WHITE
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        // Alignment guide: translucent red over the whole overlay area.
        if (cfg.guide) {
            paint.color = 0x55FF0000
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        // The pill itself.
        val pw = cfg.widthDp * d
        val ph = cfg.thicknessDp * d
        val left = (w - pw) / 2f
        val bottom = h - cfg.bottomMarginDp * d
        rect.set(left, bottom - ph, left + pw, bottom)

        paint.color = if (dark) Color.WHITE else 0xFF1B1B1F.toInt()
        paint.alpha = cfg.opacity * 255 / 100
        canvas.drawRoundRect(rect, ph / 2f, ph / 2f, paint)
    }
}
