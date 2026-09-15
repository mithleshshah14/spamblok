package com.spamblok.app

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
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
    private lateinit var linksPage: View
    private lateinit var callsPermissionCard: View
    private lateinit var callsPermissionText: TextView
    private lateinit var recentsRow: LinearLayout
    private lateinit var callsListContainer: LinearLayout
    private lateinit var messagesPermissionCard: View
    private lateinit var messagesCategoryRow: LinearLayout
    private lateinit var messagesListContainer: LinearLayout
    private lateinit var linksSetupCard: View
    private lateinit var linksCheckCard: View
    private lateinit var linkUrlInput: EditText
    private lateinit var linkResultView: TextView

    private var callLogEntries: List<CallLogRepository.Entry> = emptyList()
    private var callSearchFilter: String = ""
    private var messageCategoryFilter: MessageClassifier.Category? = null // null = All

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

        // One-time cleanup for rows the pre-fix BannerReaderService mistakenly wrote
        // (a carrier spam-warning label like "Suspected Spam" stored as if it were the
        // caller's real name) — a no-op on every launch after the first.
        CallerRepository.purgeSpamLikeNames(this)

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
        linksPage = buildLinksPage()
        pageContainer.addView(callsPage)
        pageContainer.addView(messagesPage)
        pageContainer.addView(linksPage)
        root.addView(pageContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val navCallsId = View.generateViewId()
        val navMessagesId = View.generateViewId()
        val navLinksId = View.generateViewId()
        val bottomNav = BottomNavigationView(this).apply {
            menu.add(0, navCallsId, 0, "Calls")
            menu.add(0, navMessagesId, 1, "Messages")
            menu.add(0, navLinksId, 2, "Links")
            selectedItemId = navCallsId
            setOnItemSelectedListener { item ->
                showPage(
                    when (item.itemId) {
                        navMessagesId -> messagesPage
                        navLinksId -> linksPage
                        else -> callsPage
                    },
                )
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
        linksPage.visibility = if (page === linksPage) View.VISIBLE else View.GONE
        if (page === linksPage) updateLinksSetupCard()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) reloadCallLog()
        if (hasPermission(Manifest.permission.READ_SMS)) reloadMessages()
        updateCallsPermissionCard()
        updateMessagesPermissionCard()
        updateLinksSetupCard()
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
                    setTextColor(Color.parseColor("#6B7280"))
                    textSize = 13f
                }.also { callsPermissionText = it },
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
        val callLogGranted = hasPermission(Manifest.permission.READ_CALL_LOG)
        val contactsGranted = hasPermission(Manifest.permission.READ_CONTACTS)
        // Keep prompting for READ_CONTACTS even once READ_CALL_LOG is granted —
        // e.g. a user who granted call-log access before this permission was
        // added would otherwise never see this card (and never get asked)
        // again, silently losing live Contacts-name resolution.
        callsPermissionCard.visibility = if (callLogGranted && contactsGranted) View.GONE else View.VISIBLE
        callsListContainer.visibility = if (callLogGranted) View.VISIBLE else View.GONE
        callsPermissionText.text = if (!callLogGranted) {
            "SpamBlok needs access to your call log to show call history here. Nothing leaves the device."
        } else {
            "SpamBlok needs Contacts access to show saved names for callers instead of just numbers. Nothing leaves the device."
        }
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
        // The call log's own CACHED_NAME can itself be carrier/OEM non-name text
        // (a spam-warning label like "Suspected Spam", or the bare number echoed
        // back) rather than a real contact name — the system writes that into the
        // same column. Don't let it outrank a real name we actually resolved (our
        // own DB, or a live Contacts/Truecaller lookup).
        val trustedCachedName = cachedSystemName?.takeIf {
            !SpamLabelHeuristics.looksLikeSpamWarning(it) && !SpamLabelHeuristics.looksLikeBareNumber(it)
        }
        val systemName = liveContactName ?: trustedCachedName
        val ours = CallerRepository.lookup(this, number)
        val ourName = ours?.name?.takeIf { it.isNotBlank() } ?: ours?.label?.takeIf { it.isNotBlank() }
        val display = systemName?.takeIf { it.isNotBlank() } ?: ourName ?: number.ifBlank { "Unknown" }
        return ResolvedCaller(display, isSystemContact = !systemName.isNullOrBlank(), ourName = ourName)
    }

    /** On-demand lookup via the Truecaller app already installed on this device: briefly
     * opens Truecaller's own dialer screen for [number] (it shows a caller-ID card there
     * even without placing a call), reads the name/label off it via [BannerReaderService]'s
     * accessibility access, records it into our own DB like any other observed name, then
     * snaps back to SpamBlok. Requires Truecaller installed and our accessibility service
     * enabled; on-screen for a brief moment (a few hundred ms) since Android can only expose
     * a window's content once it's actually rendered. */
    private fun searchTruecaller(number: String) {
        Toast.makeText(this, "Checking Truecaller…", Toast.LENGTH_SHORT).show()
        TruecallerSearchBridge.startSearch(number) { name, subtitle ->
            bringToFront()
            if (name != null) {
                CallerRepository.observe(this, number, name, subtitle, source = "truecaller-search")
                Toast.makeText(this, "Found: $name", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No result from Truecaller", Toast.LENGTH_SHORT).show()
            }
            renderCallLog()
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tel:$number")).apply {
            component = ComponentName("com.truecaller", "com.truecaller.DialerActivityAlias")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            TruecallerSearchBridge.deliver(null, null)
            Toast.makeText(this, "Truecaller isn't installed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Brings SpamBlok back to the foreground after a "Search Truecaller" round trip
     * and actually closes Truecaller's screen — CLEAR_TOP finishes whatever ended up
     * on top of this activity in the back stack (Truecaller's dialer, in this case),
     * not just reorder past it. REORDER_TO_FRONT alone left it sitting behind us in
     * the stack instead of gone, so Back landed back on it. */
    private fun bringToFront() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
    }

    /** Tapping a number: dial, or — for a number that isn't a real system
     * contact — add/edit the name SpamBlok itself remembers for it. */
    private fun showCallOptions(number: String, resolved: ResolvedCaller) {
        val canEditName = !resolved.isSystemContact
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Call" to { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) },
        )
        if (canEditName) {
            actions.add((if (resolved.ourName != null) "Edit name" else "Add name") to { showEditNameDialog(number, resolved.ourName) })
            actions.add("Search Truecaller" to { searchTruecaller(number) })
        }
        AlertDialog.Builder(this)
            .setTitle(resolved.displayName)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
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

    /** A search term that's a phone number rather than a name search — enough
     * digits that a direct lookup (rather than call-log filtering) makes sense,
     * even for a number that's never been called before. */
    private fun looksLikeNumberSearch(raw: String): Boolean {
        val digits = raw.count { it.isDigit() }
        val nonDialChars = raw.count { !it.isDigit() && it !in "+ -()" }
        return digits >= 4 && nonDialChars == 0
    }

    private fun renderCallLog() {
        callsListContainer.removeAllViews()
        val rawFilter = callSearchFilter.trim()
        val filter = rawFilter.lowercase()

        if (looksLikeNumberSearch(rawFilter)) {
            val resolved = resolveCaller(rawFilter, null)
            val hasCallHistory = callLogEntries.any { CallerRepository.normalize(it.number) == CallerRepository.normalize(rawFilter) }
            // Only worth a dedicated card when we actually resolved to a real
            // name (not just echoing the number back) or there's no matching
            // call-log row for it to appear alongside below.
            if (resolved.displayName != rawFilter || !hasCallHistory) {
                val content = UiKit.card(this, callsListContainer)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener { showCallOptions(rawFilter, resolved) }
                }
                row.addView(UiKit.avatar(this, dp(40), UiKit.initialFor(resolved.displayName)), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                textColumn.addView(
                    TextView(this).apply {
                        text = resolved.displayName
                        setTextColor(Color.parseColor("#1A1A2E"))
                        textSize = 15f
                    },
                )
                textColumn.addView(
                    TextView(this).apply {
                        text = if (resolved.displayName != rawFilter) {
                            "$rawFilter · ${if (resolved.isSystemContact) "From Contacts" else "From our DB"}"
                        } else {
                            "Not found — tap to call or add a name"
                        }
                        setTextColor(Color.parseColor("#6B7280"))
                        textSize = 12f
                    },
                )
                row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(row)
                if (resolved.displayName == rawFilter) {
                    content.addView(
                        UiKit.secondaryButton(this, "Search Truecaller") { searchTruecaller(rawFilter) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
                    )
                }
            }
        }

        val entries = callLogEntries.filter { e ->
            if (filter.isEmpty()) return@filter true
            val resolved = resolveCaller(e.number, e.displayName)
            resolved.displayName.lowercase().contains(filter) || e.number.lowercase().contains(filter)
        }
        if (entries.isEmpty()) {
            if (!looksLikeNumberSearch(rawFilter)) {
                callsListContainer.addView(UiKit.emptyStateText(this, if (callSearchFilter.isEmpty()) "No calls yet." else "No matches."))
            }
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

        messagesCategoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(messagesCategoryRow)
            },
            LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) },
        )
        renderMessagesCategoryRow()

        messagesListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(messagesListContainer, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        return ScrollView(this).apply { addView(body) }
    }

    private fun renderMessagesCategoryRow() {
        messagesCategoryRow.removeAllViews()
        val options = listOf(
            null to "All",
            MessageClassifier.Category.PERSONAL to "Personal",
            MessageClassifier.Category.OTP to "OTP",
            MessageClassifier.Category.BANK to "Bank",
            MessageClassifier.Category.ORGANIZATION to "Other",
            MessageClassifier.Category.SPAM to "Spam",
        )
        options.forEach { (category, label) ->
            messagesCategoryRow.addView(
                UiKit.chip(this, label, selected = messageCategoryFilter == category) {
                    messageCategoryFilter = category
                    renderMessagesCategoryRow()
                    reloadMessages()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) },
            )
        }
    }

    private fun updateMessagesPermissionCard() {
        val granted = hasPermission(Manifest.permission.READ_SMS)
        messagesPermissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        messagesListContainer.visibility = if (granted) View.VISIBLE else View.GONE
    }

    /** Tapping a message row: the list truncates the body to 2 lines, so this shows
     * the full text (selectable — handy for copying an OTP), a Copy button, and a
     * Reply button. SpamBlok is read-only (no SEND_SMS/default-SMS-app status, by
     * design — see Messages tab's permission card), so Reply hands off to the
     * phone's own SMS compose screen via ACTION_SENDTO rather than sending
     * in-app — same pattern as the Calls tab's "Call" action handing off to the
     * dialer instead of placing a call directly. */
    private fun showMessageDetail(address: String, sender: String, body: String, timestamp: String) {
        val bodyView = TextView(this).apply {
            text = body
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#1A1A2E"))
            textSize = 14f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle(sender)
            .setMessage(timestamp)
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(4), dp(20), dp(4))
                    addView(bodyView)
                },
            )
            .setPositiveButton("Reply") { _, _ ->
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$address")))
            }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", body))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Category -> (icon glyph, icon background, "Other"/"Bank"/"Spam" footer tag).
     * Personal senders use a name-initial avatar instead (see [reloadMessages]). */
    private fun categoryIconFor(category: MessageClassifier.Category): Pair<String, String> = when (category) {
        MessageClassifier.Category.OTP -> "🔐" to "#7C3AED"
        MessageClassifier.Category.BANK -> "🏦" to "#059669"
        MessageClassifier.Category.ORGANIZATION -> "🏢" to "#0891B2"
        MessageClassifier.Category.SPAM -> "⚠️" to "#DC2626"
        MessageClassifier.Category.PERSONAL -> "" to "#0066FF" // unused — PERSONAL uses UiKit.avatar
    }

    private fun reloadMessages() {
        messagesListContainer.removeAllViews()
        val allEntries = SmsRepository.getRecent(this)
        val classified = allEntries.map { e ->
            e to MessageClassifier.classify(e.address, e.body, SpamNumberListStore.contains(this, e.address))
        }
        val filter = messageCategoryFilter
        val entries = if (filter == null) classified else classified.filter { it.second == filter }
        if (entries.isEmpty()) {
            messagesListContainer.addView(UiKit.emptyStateText(this, if (allEntries.isEmpty()) "No messages yet." else "No messages in this category."))
            return
        }
        val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
        entries.forEach { (e, category) ->
            val senderLabel = if (e.type == SmsRepository.MessageType.SENT) "To ${e.address}" else e.address.ifBlank { "Unknown" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener { showMessageDetail(e.address, senderLabel, e.body, fmt.format(java.util.Date(e.timestampMillis))) }
            }
            val avatarSize = dp(36)
            val avatar = if (category == MessageClassifier.Category.PERSONAL) {
                UiKit.avatar(this, avatarSize, UiKit.initialFor(senderLabel))
            } else {
                val (glyph, color) = categoryIconFor(category)
                UiKit.iconAvatar(this, avatarSize, glyph, color)
            }
            row.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = dp(10) })

            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(
                TextView(this).apply {
                    text = senderLabel
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
            textColumn.addView(topRow)
            textColumn.addView(
                TextView(this).apply {
                    text = e.body
                    setTextColor(Color.parseColor("#374151"))
                    textSize = 13f
                    maxLines = 2
                },
            )
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            messagesListContainer.addView(row)
        }
    }

    // ---------------------------------------------------------------------
    // Links page — paste a URL, check it against Google Safe Browsing.
    // ---------------------------------------------------------------------

    private fun buildLinksPage(): View {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }
        body.addView(
            TextView(this).apply {
                text = "Link safety check"
                setTextColor(Color.parseColor("#1A1A2E"))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            },
        )

        linksSetupCard = UiKit.card(this, body).let { content ->
            content.addView(
                TextView(this).apply {
                    text = "Paste a suspicious link from a message and check it against Google " +
                        "Safe Browsing before you tap it. Needs a free API key — set one up in Settings."
                    setTextColor(Color.parseColor("#6B7280"))
                    textSize = 13f
                },
            )
            content.addView(
                UiKit.secondaryButton(this, "Open Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
                LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) },
            )
            content.parent as View
        }

        linksCheckCard = UiKit.card(this, body).let { content ->
            linkUrlInput = EditText(this).apply {
                hint = "Paste a URL to check"
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = UiKit.fieldBackground(this@MainActivity)
                textSize = 14f
            }
            content.addView(linkUrlInput)
            content.addView(
                UiKit.secondaryButton(this, "Check") { checkPastedLink() },
                LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) },
            )
            linkResultView = TextView(this).apply {
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            content.addView(linkResultView, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) })
            content.parent as View
        }

        return ScrollView(this).apply { addView(body) }
    }

    private fun updateLinksSetupCard() {
        val hasKey = SafeBrowsingKeyStore.get(this) != null
        linksSetupCard.visibility = if (hasKey) View.GONE else View.VISIBLE
        linksCheckCard.visibility = if (hasKey) View.VISIBLE else View.GONE
    }

    private fun checkPastedLink() {
        val apiKey = SafeBrowsingKeyStore.get(this) ?: return
        val raw = linkUrlInput.text.toString()
        if (LinkSafetyChecker.normalize(raw) == null) {
            Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
            return
        }
        linkResultView.text = "Checking…"
        linkResultView.setTextColor(Color.parseColor("#6B7280"))
        linkResultView.setBackgroundColor(Color.TRANSPARENT)
        LinkSafetyChecker.check(apiKey, raw) { verdict -> showLinkVerdict(verdict) }
    }

    private fun showLinkVerdict(verdict: LinkSafetyChecker.Verdict) {
        val (bg, fg, text) = when (verdict.status) {
            LinkSafetyChecker.Status.SAFE -> Triple("#DCFCE7", "#166534", "✅ ${verdict.message}")
            LinkSafetyChecker.Status.UNSAFE -> Triple(
                "#FEE2E2",
                "#991B1B",
                "⚠️ ${verdict.message}\nThreats: ${verdict.threatTypes.joinToString(", ")}",
            )
            LinkSafetyChecker.Status.ERROR -> Triple("#FEF3C7", "#92400E", verdict.message)
        }
        linkResultView.text = text
        linkResultView.setTextColor(Color.parseColor(fg))
        linkResultView.background = UiKit.statusPill(this).apply { setColor(Color.parseColor(bg)) }
    }
}
