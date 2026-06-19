package com.spamblok.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 1 UI: explains the test, lets the user enable the accessibility service,
 * and shows the on-device captured caller log (refresh / clear).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val info = TextView(this).apply {
            text = getString(R.string.phase1_instructions)
            textSize = 14f
        }

        val openSettings = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val refresh = Button(this).apply {
            text = getString(R.string.refresh_log)
            setOnClickListener { reloadLog() }
        }

        val clear = Button(this).apply {
            text = getString(R.string.clear_log)
            setOnClickListener {
                CallLogStore.clear(this@MainActivity)
                reloadLog()
                Toast.makeText(this@MainActivity, "Log cleared", Toast.LENGTH_SHORT).show()
            }
        }

        val logTitle = TextView(this).apply {
            text = getString(R.string.captured_log_title)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 2)
        }

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }

        content.addView(info)
        content.addView(openSettings, LinearLayout.LayoutParams(mp, wc).apply { topMargin = pad })
        content.addView(refresh, LinearLayout.LayoutParams(mp, wc))
        content.addView(clear, LinearLayout.LayoutParams(mp, wc))
        content.addView(logTitle)
        content.addView(logView)

        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        reloadLog()
    }

    private fun reloadLog() {
        val text = CallLogStore.read(this)
        logView.text = if (text.isBlank()) getString(R.string.log_empty) else text
    }
}
