package com.spamblok.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * SpamBlok's main screen: a Calls tab styled like a normal caller-ID app
 * (search, recent contacts, call history, dial) and a Messages tab
 * (read-only SMS). Protection settings (setup status, blocklist, imported
 * spam list, known-callers DB) live behind the gear icon — see
 * [SettingsActivity] — so this screen reads as an app, not a settings list.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var callsPage: View
    private lateinit var messagesPage: View
    private lateinit var callsPermissionCard: View
    private lateinit var recentsRow: LinearLayout
    private lateinit var callsListContainer: LinearLayout
    private lateinit var messagesPermissionCard: View
    private lateinit var messagesListContainer: LinearLayout

    private var callLogEntries: List<CallLogRepository.Entry> = emptyList()
    private var callSearchFilter: String = ""

    // Bundled together: call history needs READ_CALL_LOG, and resolving names
    // for it live (rather than the call log's own stale CACHED_NAME snapshot)
    // needs READ_CONTACTS. Both are asked for from the same "Grant access" tap.
    private val requestCallsPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) reloadCallLog()
        updateCallsPermissionCard()
    }

    private val requestSmsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) reloadMessages()
        updateMessagesPermissionCard()
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0066FF"))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(
            TextView(this).apply {
                text = getString(R.string.app_display_name)
                setTextColor(Color.WHITE)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        titleColumn.addView(
            TextView(this).apply {
                text = getString(R.string.app_tagline)
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            },
        )
        header.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            UiKit.textButton(this, "⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }
                .apply { setTextColor(Color.WHITE); textSize = 20f },
        )
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pageContainer = FrameLayout(this)
        callsPage = buildCallsPage()
        messagesPage = buildMessagesPage()
        pageContainer.addView(callsPage)
        pageContainer.addView(messagesPage)
        root.addView(pageContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val navCallsId = View.generateViewId()
        val navMessagesId = View.generateViewId()
        val bottomNav = BottomNavigationView(this).apply {
            menu.add(0, navCallsId, 0, "Calls")
            menu.add(0, navMessagesId, 1, "Messages")
            selectedItemId = navCallsId
            setOnItemSelectedListener { item ->
                showPage(if (item.itemId == navMessagesId) messagesPage else callsPage)
                true
            }
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        showPage(callsPage)
        setContentView(root)
    }

    private fun showPage(page: View) {
        callsPage.visibility = if (page === callsPage) View.VISIBLE else View.GONE
        messagesPage.visibility = if (page === messagesPage) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) reloadCallLog()
        if (hasPermission(Manifest.permission.READ_SMS)) reloadMessages()
        updateCallsPermissionCard()
        updateMessagesPermissionCard()
    }

    // ---------------------------------------------------------------------
    // Calls page — search bar, recent contacts strip, call history
    // ---------------------------------------------------------------------

    private fun buildCallsPage(): View {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        // Search bar + dial button.
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        val searchInput = EditText(this).apply {
            hint = "Search numbers, names & more"
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = UiKit.fieldBackground(this@MainActivity)
            textSize = 14f
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        callSearchFilter = s?.toString().orEmpty()
                        renderCallLog()
                    }
                },
            )
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, wc, 1f))
        searchRow.addView(
            UiKit.secondaryButton(this, "Dial") { startActivity(Intent(Intent.ACTION_DIAL)) },
            LinearLayout.LayoutParams(wc, wc).apply { marginStart = dp(8) },
        )
        body.addView(searchRow, LinearLayout.LayoutParams(mp, wc))

        // Recent contacts strip.
        recentsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val recentsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), 0, dp(16), dp(8))
            addView(recentsRow)
        }
        body.addView(recentsScroll, LinearLayout.LayoutParams(mp, wc))

        val listSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        callsPermissionCard = UiKit.card(this, listSection).let { content ->
            content.addView(
                TextView(this).apply {
                    text = "SpamBlok needs access to your call log to show call history here. Nothing leaves the device."
                    setTextColor(Color.parseColor("#6B7280"))
                    textSize = 13f
                },
            )
            content.addView(
                UiKit.secondaryButton(this, "Grant access") {
                    requestCallsPermissionsLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                },
                LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) },
            )
            content.parent as View
        }
        callsListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listSection.addView(callsListContainer, LinearLayout.LayoutParams(mp, wc))

        body.addView(ScrollView(this).apply { addView(listSection) }, LinearLayout.LayoutParams(mp, 0, 1f))

        return body
    }

    private fun updateCallsPermissionCard() {
        val granted = hasPermission(Manifest.permission.READ_CALL_LOG)
        callsPermissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        callsListContainer.visibility = if (granted) View.VISIBLE else View.GONE
    }

    private fun reloadCallLog() {
        callLogEntries = CallLogRepository.getRecent(this)
        renderRecents()
        renderCallLog()
    }

    /** A resolved display name for a raw call-log entry, plus enough context to
     * decide whether "Add/edit name" makes sense (never for a real system
     * contact — that's Contacts' job, not ours). */
    private data class ResolvedCaller(val displayName: String, val isSystemContact: Boolean, val ourName: String?)

    /** Resolves a display name for a call-log entry: system contact name first,
     * then whatever SpamBlok itself has learned for this number (CallerRepository,
     * Phase 3 — including a name the user typed in manually), then the raw number. */
    private fun resolveCaller(number: String, cachedSystemName: String?): ResolvedCaller {
        // A *live* Contacts lookup, not the call log's own CACHED_NAME: that
        // column is a snapshot from when the call happened, so it stays stale
        // (or blank) if a contact for this number gets saved afterwards.
        val liveContactName = if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            ContactsRepository.lookupName(this, number)
        } else {
            null
        }
        val systemName = liveContactName ?: cachedSystemName
        val ours = CallerRepository.lookup(this, number)
        val ourName = ours?.name?.takeIf { it.isNotBlank() } ?: ours?.label?.takeIf { it.isNotBlank() }
        val display = systemName?.takeIf { it.isNotBlank() } ?: ourName ?: number.ifBlank { "Unknown" }
        return ResolvedCaller(display, isSystemContact = !systemName.isNullOrBlank(), ourName = ourName)
    }

    /** Tapping a number: dial, or — for a number that isn't a real system
     * contact — add/edit the name SpamBlok itself remembers for it. */
    private fun showCallOptions(number: String, resolved: ResolvedCaller) {
        val canEditName = !resolved.isSystemContact
        val options = if (canEditName) {
            arrayOf("Call", if (resolved.ourName != null) "Edit name" else "Add name")
        } else {
            arrayOf("Call")
        }
        AlertDialog.Builder(this)
            .setTitle(resolved.displayName)
            .setItems(options) { _, which ->
                if (which == 0) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                } else {
                    showEditNameDialog(number, resolved.ourName)
                }
            }
            .show()
    }

    private fun showEditNameDialog(number: String, existingName: String?) {
        val input = EditText(this).apply {
            hint = "Name"
            setText(existingName ?: "")
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(number)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    CallerRepository.setManualName(this, number, name)
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    reloadCallLog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderRecents() {
        recentsRow.removeAllViews()
        val seen = LinkedHashSet<String>()
        val recents = callLogEntries.filter { it.number.isNotBlank() && seen.add(it.number) }.take(8)
        recents.forEach { e ->
            val resolved = resolveCaller(e.number, e.displayName)
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                setOnClickListener { showCallOptions(e.number, resolved) }
            }
            column.addView(UiKit.avatar(this, dp(48), UiKit.initialFor(resolved.displayName)), LinearLayout.LayoutParams(dp(48), dp(48)))
            column.addView(
                TextView(this).apply {
                    text = resolved.displayName
                    textSize = 11f
                    setTextColor(Color.parseColor("#1A1A2E"))
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                    width = dp(56)
                    gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            recentsRow.addView(column, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun renderCallLog() {
        callsListContainer.removeAllViews()
        val filter = callSearchFilter.trim().lowercase()
        val entries = callLogEntries.filter { e ->
            if (filter.isEmpty()) return@filter true
            val resolved = resolveCaller(e.number, e.displayName)
            resolved.displayName.lowercase().contains(filter) || e.number.lowercase().contains(filter)
        }
        if (entries.isEmpty()) {
            callsListContainer.addView(UiKit.emptyStateText(this, if (callSearchFilter.isEmpty()) "No calls yet." else "No matches."))
            return
        }
        val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
        entries.forEach { e ->
            val resolved = resolveCaller(e.number, e.displayName)
            val displayName = resolved.displayName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener { showCallOptions(e.number, resolved) }
            }
            row.addView(UiKit.avatar(this, dp(40), UiKit.initialFor(displayName)), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

            val typeColor = when (e.type) {
                CallLogRepository.CallType.MISSED -> "#EF4444"
                CallLogRepository.CallType.INCOMING -> "#22C55E"
                CallLogRepository.CallType.OUTGOING -> "#0066FF"
                else -> "#6B7280"
            }
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(
                TextView(this).apply {
                    text = displayName
                    setTextColor(Color.parseColor("#1A1A2E"))
                    textSize = 15f
                },
            )
            textColumn.addView(
                TextView(this).apply {
                    val duration = if (e.durationSeconds > 0) " · ${e.durationSeconds}s" else ""
                    val numberPart = if (displayName != e.number) "${e.number} · " else ""
                    text = "$numberPart${e.type.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                        "${fmt.format(java.util.Date(e.timestampMillis))}$duration"
                    setTextColor(Color.parseColor(typeColor))
                    textSize = 12f
                },
            )
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            callsListContainer.addView(row)
        }
    }

    // ---------------------------------------------------------------------
    // Messages page
    // ---------------------------------------------------------------------

    private fun buildMessagesPage(): View {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }
        body.addView(
            TextView(this).apply {
                text = "Messages"
                setTextColor(Color.parseColor("#1A1A2E"))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        messagesPermissionCard = UiKit.card(this, body).let { content ->
            content.addView(
                TextView(this).apply {
                    text = "SpamBlok needs access to your messages to show them here (read-only). Nothing leaves the device."
                    setTextColor(Color.parseColor("#6B7280"))
                    textSize = 13f
                },
            )
            content.addView(
                UiKit.secondaryButton(this, "Grant access") { requestSmsLauncher.launch(Manifest.permission.READ_SMS) },
                LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) },
            )
            content.parent as View
        }
        messagesListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(messagesListContainer, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        return ScrollView(this).apply { addView(body) }
    }

    private fun updateMessagesPermissionCard() {
        val granted = hasPermission(Manifest.permission.READ_SMS)
        messagesPermissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        messagesListContainer.visibility = if (granted) View.VISIBLE else View.GONE
    }

    private fun reloadMessages() {
        messagesListContainer.removeAllViews()
        val entries = SmsRepository.getRecent(this)
        if (entries.isEmpty()) {
            messagesListContainer.addView(UiKit.emptyStateText(this, "No messages yet."))
            return
        }
        val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
        entries.forEach { e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(
                TextView(this).apply {
                    text = if (e.type == SmsRepository.MessageType.SENT) "To ${e.address}" else e.address.ifBlank { "Unknown" }
                    setTextColor(Color.parseColor("#1A1A2E"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            topRow.addView(
                TextView(this).apply {
                    text = fmt.format(java.util.Date(e.timestampMillis))
                    setTextColor(Color.parseColor("#6B7280"))
                    textSize = 11f
                },
            )
            row.addView(topRow)
            row.addView(
                TextView(this).apply {
                    text = e.body
                    setTextColor(Color.parseColor("#374151"))
                    textSize = 13f
                    maxLines = 2
                },
            )
            messagesListContainer.addView(row)
        }
    }
}
