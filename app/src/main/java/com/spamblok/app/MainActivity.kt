package com.spamblok.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 1 UI: nothing fancy. Explains the test and offers a shortcut to the
 * Accessibility settings screen so the user can enable the banner reader.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val info = TextView(this).apply {
            text = getString(R.string.phase1_instructions)
            textSize = 16f
        }

        val openSettings = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        content.addView(info)
        content.addView(openSettings, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        setContentView(ScrollView(this).apply { addView(content) })
    }
}
