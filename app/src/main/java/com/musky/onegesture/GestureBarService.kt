package com.musky.onegesture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class GestureBarService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var view: PillView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
            // Keyguard state settles a moment after these broadcasts.
            handler.postDelayed({ refresh() }, 400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        attach()
    }

    private fun attach() {
        if (view != null) return
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (Prefs.load(this).stripDp * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val v = PillView(this)
        try {
            wm.addView(v, lp)
            view = v
            params = lp
            refresh()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun detach() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
        params = null
    }

    private fun isLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    private fun refresh() {
        val v = view ?: return
        val lp = params ?: return
        val cfg = Prefs.load(this)
        val h = (cfg.stripDp * resources.displayMetrics.density).toInt()

        v.cfg = cfg
        v.locked = isLocked()
        v.visibility = if (cfg.enabled) View.VISIBLE else View.GONE

        if (lp.height != h) {
            lp.height = h
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Cheap re-sync (lock state, theme) whenever the foreground window changes.
        refresh()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
        view?.invalidate()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        detach()
    }
}
