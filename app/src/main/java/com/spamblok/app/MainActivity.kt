package com.spamblok.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 2 UI: sets up all three pieces SpamBlok needs — the accessibility banner
 * reader (Phase 1), the overlay-draw permission, and the system call-screening
 * role — lets the user manage the prefix blocklist, and shows the on-device
 * captured caller log (refresh / clear).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var blocklistContainer: LinearLayout
    private lateinit var prefixInput: EditText

    // RoleManager's request-role intent must be launched for a RESULT (not a plain
    // startActivity) — RequestRoleActivity reads the *calling* package via
    // getCallingPackage(), which is only populated for an activity-for-result launch.
    // Launched with plain startActivity(), it silently no-ops ("Package name cannot
    // be null or empty").
    private val requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val message = if (result.resultCode == RESULT_OK) {
            "SpamBlok is now the call-screening app"
        } else {
            "Call-screening role request was cancelled"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // READ_PHONE_STATE is a dangerous runtime permission. Declaring it in the
    // manifest alone leaves it ungranted — and on this device at least, Telecom
    // silently refuses to ever bind SpamBlokCallScreeningService while it's
    // ungranted (no error, it just skips straight to the OEM's own screening).
    private val requestPhoneStateLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val message = if (granted) "Phone-state permission granted" else "Phone-state permission denied"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

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

        val requestPhoneState = Button(this).apply {
            text = getString(R.string.grant_phone_state_permission)
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(this@MainActivity, "Already granted", Toast.LENGTH_SHORT).show()
                } else {
                    requestPhoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            }
        }

        val requestScreeningRole = Button(this).apply {
            text = getString(R.string.set_as_call_screener)
            setOnClickListener { requestCallScreeningRole() }
        }

        val blocklistTitle = TextView(this).apply {
            text = getString(R.string.blocklist_title)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        }

        val blocklistHint = TextView(this).apply {
            text = getString(R.string.blocklist_hint)
            textSize = 13f
        }

        prefixInput = EditText(this).apply {
            hint = getString(R.string.blocklist_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }

        val addPrefix = Button(this).apply {
            text = getString(R.string.blocklist_add)
            setOnClickListener {
                val raw = prefixInput.text.toString()
                if (BlockedPrefixStore.add(this@MainActivity, raw)) {
                    prefixInput.text.clear()
                    reloadBlocklist()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.blocklist_invalid_prefix),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        blocklistContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        content.addView(requestPhoneState, LinearLayout.LayoutParams(mp, wc))
        content.addView(requestScreeningRole, LinearLayout.LayoutParams(mp, wc))
        content.addView(blocklistTitle)
        content.addView(blocklistHint)
        content.addView(prefixInput, LinearLayout.LayoutParams(mp, wc).apply { topMargin = pad / 2 })
        content.addView(addPrefix, LinearLayout.LayoutParams(mp, wc))
        content.addView(blocklistContainer, LinearLayout.LayoutParams(mp, wc))
        content.addView(refresh, LinearLayout.LayoutParams(mp, wc).apply { topMargin = pad })
        content.addView(clear, LinearLayout.LayoutParams(mp, wc))
        content.addView(logTitle)
        content.addView(logView)

        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        reloadLog()
        reloadBlocklist()
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
        requestRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    private fun reloadLog() {
        val text = CallLogStore.read(this)
        logView.text = if (text.isBlank()) getString(R.string.log_empty) else text
    }

    private fun reloadBlocklist() {
        blocklistContainer.removeAllViews()
        val prefixes = BlockedPrefixStore.getAll(this)
        val pad = (8 * resources.displayMetrics.density).toInt()

        if (prefixes.isEmpty()) {
            blocklistContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.blocklist_empty)
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, pad, 0, pad)
                },
            )
            return
        }

        prefixes.forEach { prefix ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val label = TextView(this).apply {
                text = prefix
                textSize = 14f
            }
            val remove = Button(this).apply {
                text = getString(R.string.blocklist_remove)
                setOnClickListener {
                    BlockedPrefixStore.remove(this@MainActivity, prefix)
                    reloadBlocklist()
                }
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                },
            )
            row.addView(remove)
            blocklistContainer.addView(row)
        }
    }
}
