package com.musky.onegesture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@Suppress("DEPRECATION")
class GestureBarService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: GestureBarService? = null

        const val DEBUG_FILE = "gb_debug"
        const val DEBUG_KEY = "fit"
        const val VIS_KEY = "vis"

        // Video apps: hidden in landscape (fullscreen playback) as a fallback signal.
        private val VIDEO_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.videos",
            "com.netflix.mediaclient",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.amazon.avod.thirdpartyclient",
            "tv.twitch.android.app"
        )

        private val FLAGS_REGEX = Regex("mLastSystemUiFlags=0x([0-9a-fA-F]+)")
        private val DUMP_CMDS = listOf(
            arrayOf("dumpsys", "window", "policy"),
            arrayOf("dumpsys", "window")
        )

        /** Called by the settings screen so slider changes apply instantly. */
        fun refreshNow() {
            instance?.refresh()
        }
    }

    private lateinit var wm: WindowManager
    private var view: PillView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()

    private var navHidden = false          // foreground app hid the navigation bar (immersive)
    private var fgPkg: String? = null      // last real foreground app
    private val gameCache = HashMap<String, Boolean>()

    private var fitAttempt = 0
    private var fitToken = 0

    private var colorCheckPending = false
    private var lastColorCheck = 0L
    @Volatile
    private var dumpIdx = 0

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) navHidden = false
            refresh()
            handler.postDelayed({ refresh() }, 400)
            scheduleColorCheck(500, 0)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // First run: size the overlay to this phone's real gesture pill (no need to open the app).
        if (!Prefs.sp(this).getBoolean(Prefs.K_AUTOFIT, false)) {
            DeviceInfo.autoFit(this)
            Prefs.sp(this).edit().putBoolean(Prefs.K_AUTOFIT, true).apply()
        }

        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        attach()
        scheduleColorCheck(300, 0)
    }

    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m
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
        // Lay out as if the navigation bar were hidden, so the window can reach the real screen bottom.
        v.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        // Android only passes low-profile / hide-navigation / fullscreen to overlays, which is
        // enough to know when a video or game hides the bar. (Pill colour is read separately.)
        v.setOnSystemUiVisibilityChangeListener { vis ->
            navHidden = (vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
            updateHide()
            scheduleColorCheck(300, 0)
        }

        try {
            wm.addView(v, lp)
            view = v
            params = lp
            refresh()
            fitToBottom()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Some ROMs stop overlay windows at the top of the navigation band. Measure where the
     * window really landed and nudge it until its bottom edge meets the physical screen bottom.
     */
    private fun fitToBottom() {
        val v = view ?: return
        val lp = params ?: return
        fitToken++
        fitAttempt = 0
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.y = 0
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
        verifyFit(fitToken)
    }

    private fun verifyFit(token: Int) {
        val v = view ?: return
        v.postDelayed({
            val lp = params
            if (token != fitToken || view !== v || lp == null) return@postDelayed

            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val gap = realMetrics().heightPixels - (loc[1] + v.height)

            if (abs(gap) <= 1 || fitAttempt >= 3) {
                recordFit(gap)
                return@postDelayed
            }

            fitAttempt++
            val topMode = (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP
            when {
                fitAttempt == 1 -> lp.y -= gap                      // bottom gravity: negative y moves down
                fitAttempt == 2 -> {                                // fall back to absolute top positioning
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.y = realMetrics().heightPixels - lp.height
                }
                topMode -> lp.y += gap
                else -> lp.y -= gap
            }
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
            verifyFit(token)
        }, 150)
    }

    private fun recordFit(gap: Int) {
        val m = realMetrics()
        val mode = if (((params?.gravity ?: 0) and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) "top" else "bottom"
        getSharedPreferences(DEBUG_FILE, Context.MODE_PRIVATE).edit()
            .putString(
                DEBUG_KEY,
                "Overlay fit: gap ${gap}px after $fitAttempt step(s), $mode-anchored, " +
                    "real screen ${m.widthPixels}x${m.heightPixels}"
            ).apply()
    }

    // ---- Pill colour: read the system's own light/dark nav bar flag --------------------------

    private fun scheduleColorCheck(delayMs: Long, minGapMs: Long) {
        if (colorCheckPending) return
        colorCheckPending = true
        val wait = maxOf(delayMs, lastColorCheck + minGapMs - SystemClock.uptimeMillis())
        handler.postDelayed({
            colorCheckPending = false
            runColorCheck()
        }, wait)
    }

    private fun runColorCheck() {
        if (view == null) return
        if (Prefs.load(this).theme != Prefs.THEME_AUTO) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return
        lastColorCheck = SystemClock.uptimeMillis()
        try {
            bg.execute {
                val r = readNavLight()
                handler.post { applyNavLight(r) }
            }
        } catch (_: Exception) {
        }
    }

    /** Reads mLastSystemUiFlags from the window policy; bit 0x10 = light navigation bar. */
    private fun readNavLight(): Pair<Boolean?, String> {
        var last = "no output"
        for (i in DUMP_CMDS.indices) {
            val idx = (dumpIdx + i) % DUMP_CMDS.size
            try {
                val p = ProcessBuilder(*DUMP_CMDS[idx]).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                p.destroy()
                val m = FLAGS_REGEX.find(text)
                if (m != null) {
                    dumpIdx = idx
                    val flags = m.groupValues[1].toLong(16)
                    return Pair((flags and 0x10L) != 0L, "flags=0x${m.groupValues[1]}")
                }
                last = text.trim().replace("\n", " ").take(110)
                if (text.contains("Permission Denial", ignoreCase = true)) break
            } catch (e: Exception) {
                last = "exec failed: ${e.javaClass.simpleName}"
                break
            }
        }
        return Pair(null, "dumpsys: $last")
    }

    private fun applyNavLight(r: Pair<Boolean?, String>) {
        val v = view ?: return
        v.systemLightNav = r.first
        val label = when (r.first) {
            true -> "light bar, dark pill"
            false -> "dark bar, white pill"
            null -> "unknown, following phone theme"
        }
        getSharedPreferences(DEBUG_FILE, Context.MODE_PRIVATE).edit()
            .putString(VIS_KEY, "System nav bar: $label [${r.second}]")
            .apply()
    }

    // ---- housekeeping ------------------------------------------------------------------------

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

    private fun isGame(pkg: String?): Boolean {
        if (pkg == null) return false
        return gameCache.getOrPut(pkg) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                info.category == ApplicationInfo.CATEGORY_GAME ||
                    (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isImePackage(pkg: String): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime?.startsWith("$pkg/") == true
    }

    /** Fade out while a video/game is fullscreen, fade back in when the system bar returns. */
    private fun updateHide() {
        val v = view ?: return
        val cfg = Prefs.load(this)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fallback = landscape && (isGame(fgPkg) || (fgPkg != null && fgPkg in VIDEO_APPS))
        val hide = cfg.autoHide && !isLocked() && (navHidden || fallback)
        v.animate().cancel()
        v.animate().alpha(if (hide) 0f else 1f).setDuration(160).start()
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
            fitToBottom()
        }
        v.invalidate()
        updateHide()
        if (cfg.theme == Prefs.THEME_AUTO) scheduleColorCheck(200, 1500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName && pkg != "com.android.systemui" && !isImePackage(pkg)) {
                fgPkg = pkg
            }
            refresh()
            // App switch / keyboard / dialog: re-read the nav bar colour now and once more after animations.
            scheduleColorCheck(300, 0)
            handler.postDelayed({ scheduleColorCheck(0, 0) }, 1000)
        } else {
            // Content changes (e.g. keyboard hiding, dark/light switch): throttled.
            scheduleColorCheck(400, 1500)
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
        fitToBottom()
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
        if (instance === this) instance = null
        fitToken++
        handler.removeCallbacksAndMessages(null)
        bg.shutdownNow()
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
