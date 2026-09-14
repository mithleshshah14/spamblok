package com.spamblok.app

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
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
 * Phase 2 UI: sets up all three pieces SpamBlok needs — the accessibility banner
 * reader (Phase 1), the overlay-draw permission, and the system call-screening
 * role — then shows the on-device captured caller log (refresh / clear).
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
            text = getString(R.string.phase2_instructions)
            textSize = 14f
        }

        val openAccessibility = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val openOverlay = Button(this).apply {
            text = getString(R.string.grant_overlay_permission)
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                startActivity(intent)
            }
        }

        val requestScreeningRole = Button(this).apply {
            text = getString(R.string.set_as_call_screener)
            setOnClickListener { requestCallScreeningRole() }
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
        content.addView(openAccessibility, LinearLayout.LayoutParams(mp, wc).apply { topMargin = pad })
        content.addView(openOverlay, LinearLayout.LayoutParams(mp, wc))
        content.addView(requestScreeningRole, LinearLayout.LayoutParams(mp, wc))
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

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Call screening role needs Android 10+", Toast.LENGTH_LONG).show()
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "Call screening role not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "SpamBlok is already the call-screening app", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    private fun reloadLog() {
        val text = CallLogStore.read(this)
        logView.text = if (text.isBlank()) getString(R.string.log_empty) else text
    }
}
