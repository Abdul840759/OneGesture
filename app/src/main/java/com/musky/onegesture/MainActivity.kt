package com.musky.onegesture

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var fitInfo: TextView
    private lateinit var preview: PillView
    private lateinit var previewBox: FrameLayout
    private val d get() = resources.displayMetrics.density
    private fun dp(x: Int) = (x * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch: size the overlay to the phone's real gesture pill.
        if (!Prefs.sp(this).getBoolean(Prefs.K_AUTOFIT, false)) {
            DeviceInfo.autoFit(this)
            Prefs.sp(this).edit().putBoolean(Prefs.K_AUTOFIT, true).apply()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "One Gesture"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "Auto-fit to this phone's gesture bar"
            setOnClickListener {
                if (DeviceInfo.autoFit(this@MainActivity)) {
                    GestureBarService.refreshNow()
                    Toast.makeText(this@MainActivity, "Fitted to system pill", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "System pill size not found on this ROM. Use the sliders.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(TextView(this).apply {
            text = DeviceInfo.describe(DeviceInfo.probe(this@MainActivity))
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        })

        fitInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(fitInfo)

        previewBox = FrameLayout(this).apply {
            setBackgroundColor(0xFF6B7280.toInt())
        }
        preview = PillView(this)
        previewBox.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(24),
                Gravity.BOTTOM
            )
        )
        root.addView(
            previewBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        )

        val c = Prefs.Config()

        toggle(root, "Overlay on", Prefs.K_ENABLED, c.enabled)
        toggle(root, "Solid backdrop behind pill (off = transparent)", Prefs.K_BACKDROP, c.backdrop)
        toggle(root, "Keep backdrop on lock screen", Prefs.K_BACKDROP_LOCK, c.backdropOnLock)
        toggle(root, "Auto-hide in fullscreen (videos, games)", Prefs.K_AUTOHIDE, c.autoHide)
        toggle(root, "Alignment guide (red tint)", Prefs.K_GUIDE, c.guide)

        label(root, "Pill color")
        themeGroup(root)

        slider(root, "Pill width (dp)", 40, 220, Prefs.K_WIDTH, c.widthDp)
        slider(root, "Pill thickness (dp)", 2, 10, Prefs.K_THICK, c.thicknessDp)
        slider(root, "Distance from bottom (dp)", 0, 40, Prefs.K_MARGIN, c.bottomMarginDp)
        slider(root, "Overlay strip height (dp)", 10, 60, Prefs.K_STRIP, c.stripDp)
        slider(root, "Pill opacity (%)", 30, 100, Prefs.K_OPACITY, c.opacity)

        setContentView(ScrollView(this).apply { addView(root) })
        updatePreview()
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        status.text = if (on) "Accessibility service: ON" else
            "Accessibility service: OFF. Enable \"One Gesture\" in accessibility settings."
        status.setTextColor(if (on) 0xFF15803D.toInt() else 0xFFB91C1C.toInt())
        val dbg = getSharedPreferences(GestureBarService.DEBUG_FILE, MODE_PRIVATE)
        fitInfo.text = dbg.getString(GestureBarService.DEBUG_KEY, "Overlay fit: not measured yet (service off?)") +
            "\n" + dbg.getString(GestureBarService.VIS_KEY, "System nav bar: no change reported yet")
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains(packageName) && s.contains("GestureBarService")
    }

    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        val e = Prefs.sp(this).edit()
        e.block()
        e.apply()
        updatePreview()
        GestureBarService.refreshNow()
    }

    private fun updatePreview() {
        val cfg = Prefs.load(this)
        preview.cfg = cfg
        val lp = preview.layoutParams
        lp.height = dp(cfg.stripDp)
        preview.layoutParams = lp
        preview.invalidate()
    }

    private fun label(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun toggle(parent: LinearLayout, text: String, key: String, def: Boolean) {
        val sp = Prefs.sp(this)
        parent.addView(Switch(this).apply {
            this.text = text
            isChecked = sp.getBoolean(key, def)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> save { putBoolean(key, checked) } }
        })
    }

    private fun themeGroup(parent: LinearLayout) {
        val current = Prefs.sp(this).getInt(Prefs.K_THEME, Prefs.THEME_AUTO)
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val names = listOf("Match system pill", "White", "Dark")
        names.forEachIndexed { i, n ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = n
                isChecked = (i == current)
            })
        }
        group.setOnCheckedChangeListener { _, id -> save { putInt(Prefs.K_THEME, id - 1000) } }
        parent.addView(group)
    }

    private fun slider(
        parent: LinearLayout, label: String, min: Int, max: Int, key: String, def: Int
    ) {
        val cur = Prefs.sp(this).getInt(key, def)
        val tv = TextView(this).apply {
            text = "$label: $cur"
            textSize = 14f
            setPadding(0, dp(14), 0, 0)
        }
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = cur - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + min
                tv.text = "$label: $v"
                if (fromUser) save { putInt(key, v) }
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        parent.addView(tv)
        parent.addView(sb)
    }
}
