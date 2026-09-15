package com.spamblok.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * SpamBlok's protection settings: setup/permission status, the number-prefix
 * blocklist, the imported spam-number list, and the on-device known-callers
 * DB. Reached via the gear icon on [MainActivity] — kept out of the main
 * Calls/Messages flow so that screen can read like a normal caller app.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var blocklistContainer: LinearLayout
    private lateinit var prefixInput: EditText
    private lateinit var knownCallersView: TextView
    private lateinit var spamListStatusView: TextView

    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var phoneStateStatus: TextView
    private lateinit var roleStatus: TextView

    private val importSpamListLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val lines = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readLines()
        } catch (e: Exception) {
            null
        }
        if (lines == null) {
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val numbers = SpamNumberListStore.parseLines(lines)
        val imported = SpamNumberListStore.importNumbers(this, numbers)
        Toast.makeText(this, "Imported $imported number(s)", Toast.LENGTH_SHORT).show()
        reloadSpamListStatus()
    }

    private val requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val message = if (result.resultCode == RESULT_OK) "SpamBlok is now the call-screening app" else "Call-screening role request was cancelled"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        reloadSetupStatus()
    }

    private val requestPhoneStateLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Phone-state permission granted" else "Phone-state permission denied", Toast.LENGTH_SHORT).show()
        reloadSetupStatus()
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0066FF"))
            setPadding(dp(12), dp(24), dp(20), dp(16))
        }
        header.addView(
            UiKit.textButton(this, "←") { finish() }.apply { setTextColor(Color.WHITE); textSize = 20f },
        )
        header.addView(
            TextView(this).apply {
                text = "Protection settings"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), 0, 0, 0)
            },
        )
        root.addView(header, LinearLayout.LayoutParams(mp, wc))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val setupCard = UiKit.card(this, body)
        UiKit.sectionTitle(this, setupCard, getString(R.string.setup_title))
        UiKit.sectionHint(this, setupCard, getString(R.string.setup_hint))

        accessibilityStatus = setupRow(setupCard, getString(R.string.setup_step_accessibility), getString(R.string.setup_action_open)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        overlayStatus = setupRow(setupCard, getString(R.string.setup_step_overlay), getString(R.string.setup_action_open)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        phoneStateStatus = setupRow(setupCard, getString(R.string.setup_step_phone_state), getString(R.string.setup_action_grant)) {
            if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            } else {
                requestPhoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
        }
        roleStatus = setupRow(setupCard, getString(R.string.setup_step_role), getString(R.string.setup_action_set)) {
            requestCallScreeningRole()
        }

        val blocklistCard = UiKit.card(this, body)
        UiKit.sectionTitle(this, blocklistCard, getString(R.string.blocklist_title))
        UiKit.sectionHint(this, blocklistCard, getString(R.string.blocklist_hint))
        prefixInput = EditText(this).apply {
            hint = getString(R.string.blocklist_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = UiKit.fieldBackground(this@SettingsActivity)
        }
        blocklistCard.addView(prefixInput, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(12) })
        blocklistCard.addView(
            UiKit.secondaryButton(this, getString(R.string.blocklist_add)) {
                val raw = prefixInput.text.toString()
                if (BlockedPrefixStore.add(this, raw)) {
                    prefixInput.text.clear()
                    reloadBlocklist()
                } else {
                    Toast.makeText(this, getString(R.string.blocklist_invalid_prefix), Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) },
        )
        blocklistContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blocklistCard.addView(blocklistContainer, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        val spamListCard = UiKit.card(this, body)
        UiKit.sectionTitle(this, spamListCard, getString(R.string.spam_list_title))
        UiKit.sectionHint(this, spamListCard, getString(R.string.spam_list_hint))
        spamListStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1A1A2E"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        }
        spamListCard.addView(spamListStatusView)
        val spamListButtonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        spamListButtonRow.addView(
            UiKit.secondaryButton(this, getString(R.string.spam_list_import)) { importSpamListLauncher.launch("*/*") },
            LinearLayout.LayoutParams(0, wc, 1f).apply { marginEnd = dp(8) },
        )
        spamListButtonRow.addView(
            UiKit.textButton(this, getString(R.string.spam_list_clear)) {
                SpamNumberListStore.clear(this)
                reloadSpamListStatus()
                Toast.makeText(this, "Imported list cleared", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(0, wc, 1f),
        )
        spamListCard.addView(spamListButtonRow, LinearLayout.LayoutParams(mp, wc))

        val linkSafetyCard = UiKit.card(this, body)
        UiKit.sectionTitle(this, linkSafetyCard, "Link safety check (Links tab)")
        UiKit.sectionHint(
            this,
            linkSafetyCard,
            "Optional. Checks a URL you paste against Google Safe Browsing — the only " +
                "feature in SpamBlok that sends anything off your device (the URL itself, " +
                "to Google, using your own free API key). Get one free at " +
                "console.cloud.google.com → enable \"Safe Browsing API\" → Credentials → " +
                "Create API key.",
        )
        val apiKeyInput = EditText(this).apply {
            hint = "Paste your Safe Browsing API key"
            setText(SafeBrowsingKeyStore.get(this@SettingsActivity) ?: "")
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = UiKit.fieldBackground(this@SettingsActivity)
            textSize = 13f
        }
        linkSafetyCard.addView(apiKeyInput, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })
        val apiKeyButtonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        apiKeyButtonRow.addView(
            UiKit.secondaryButton(this, "Save") {
                val key = apiKeyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this, "Enter a key first", Toast.LENGTH_SHORT).show()
                } else {
                    SafeBrowsingKeyStore.set(this, key)
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, wc, 1f).apply { marginEnd = dp(8); topMargin = dp(8) },
        )
        apiKeyButtonRow.addView(
            UiKit.textButton(this, "Clear") {
                SafeBrowsingKeyStore.clear(this)
                apiKeyInput.setText("")
                Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(0, wc, 1f).apply { topMargin = dp(8) },
        )
        linkSafetyCard.addView(apiKeyButtonRow, LinearLayout.LayoutParams(mp, wc))

        val knownCallersCard = UiKit.card(this, body)
        UiKit.sectionTitle(this, knownCallersCard, getString(R.string.known_callers_title))
        UiKit.sectionHint(this, knownCallersCard, getString(R.string.known_callers_hint))
        knownCallersView = UiKit.monoText(this).apply { setPadding(0, dp(8), 0, 0) }
        knownCallersCard.addView(knownCallersView)

        body.addView(
            UiKit.textButton(this, getString(R.string.debug_show_test_overlay)) {
                OverlayService.show(this, "+911234567890", NumberHeuristics.classify("+911234567890"))
                Toast.makeText(this, "Triggered overlay", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) },
        )
        body.addView(
            UiKit.textButton(this, "Debug: view captured caller log") {
                val log = CallLogStore.read(this)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Captured caller log")
                    .setMessage(log.ifBlank { "(empty)" })
                    .setPositiveButton("Close", null)
                    .show()
            },
            LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) },
        )

        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(mp, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reloadBlocklist()
        reloadKnownCallers()
        reloadSpamListStatus()
        reloadSetupStatus()
    }

    private fun setupRow(parent: LinearLayout, title: String, buttonText: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#1A1A2E"))
                textSize = 14f
            },
        )
        val status = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val statusHolder = LinearLayout(this).apply { setPadding(0, dp(4), 0, 0) }
        statusHolder.addView(status)
        textColumn.addView(statusHolder)

        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(UiKit.secondaryButton(this, buttonText, onClick))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return status
    }

    private fun setStatus(view: TextView, done: Boolean) {
        if (done) {
            view.text = getString(R.string.setup_status_done)
            view.setTextColor(Color.parseColor("#166534"))
            view.background = UiKit.statusPill(this).apply { setColor(Color.parseColor("#DCFCE7")) }
        } else {
            view.text = getString(R.string.setup_status_needed)
            view.setTextColor(Color.parseColor("#92400E"))
            view.background = UiKit.statusPill(this).apply { setColor(Color.parseColor("#FEF3C7")) }
        }
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

    private fun reloadSetupStatus() {
        val accessibilityEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/$packageName.BannerReaderService") == true
        setStatus(accessibilityStatus, accessibilityEnabled)
        setStatus(overlayStatus, OverlayService.hasOverlayPermission(this))
        setStatus(phoneStateStatus, hasPermission(Manifest.permission.READ_PHONE_STATE))
        val roleHeld = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        setStatus(roleStatus, roleHeld)
    }

    private fun reloadSpamListStatus() {
        val count = SpamNumberListStore.count(this)
        spamListStatusView.text = if (count == 0) getString(R.string.spam_list_empty) else getString(R.string.spam_list_count, count)
    }

    private fun reloadKnownCallers() {
        val records = CallerRepository.getRecent(this)
        knownCallersView.text = if (records.isEmpty()) {
            getString(R.string.known_callers_empty)
        } else {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            records.joinToString("\n\n") { r ->
                val what = listOfNotNull(r.name, r.label).joinToString(" — ").ifBlank { "(no name/label)" }
                val mismatch = if (r.mismatchCount > 0) " ⚠ ${r.mismatchCount} mismatch(es)" else ""
                "${r.number}\n  $what$mismatch\n  source: ${r.source ?: "?"} · last seen ${fmt.format(java.util.Date(r.lastSeenMillis))}"
            }
        }
    }

    private fun reloadBlocklist() {
        blocklistContainer.removeAllViews()
        val prefixes = BlockedPrefixStore.getAll(this)
        if (prefixes.isEmpty()) {
            blocklistContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.blocklist_empty)
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(4), 0, dp(4))
                },
            )
            return
        }
        prefixes.forEach { prefix ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(
                TextView(this).apply {
                    text = prefix
                    textSize = 14f
                    setTextColor(Color.parseColor("#1A1A2E"))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                UiKit.textButton(this, getString(R.string.blocklist_remove)) {
                    BlockedPrefixStore.remove(this, prefix)
                    reloadBlocklist()
                },
            )
            blocklistContainer.addView(row)
        }
    }
}
