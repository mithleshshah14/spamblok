package com.spamblok.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Phase 2 — SpamBlok's own small overlay banner shown while a call is ringing.
 *
 * Not an actual android.app.Service (no manifest entry needed): drawing a
 * TYPE_APPLICATION_OVERLAY window just needs the SYSTEM_ALERT_WINDOW permission,
 * which the user grants once from MainActivity. It starts with just the number +
 * offline heuristic verdict (available instantly from CallScreeningService) and
 * updates itself live as [CallerInfoStore] receives a name/label from the
 * accessibility-read banner.
 *
 * Styled as a rounded card (like a Truecaller-style caller-ID card) rather than
 * a full-width bar. Some fields in the visual design this follows (carrier/SIM)
 * aren't data we actually have, so the footer shows real info instead (where
 * the name/label came from) rather than fabricating a SIM indicator.
 */
object OverlayService {

    private const val TAG = "SpamBlokOverlay"
    private const val AUTO_DISMISS_MS = 45_000L
    private const val CARD_COLOR = "#0066FF"

    private var windowManager: WindowManager? = null
    private var view: LinearLayout? = null
    private var statusTagText: TextView? = null
    private var avatarText: TextView? = null
    private var titleText: TextView? = null
    private var subtitleText: TextView? = null
    private var sourceTagText: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context, number: String, verdict: NumberHeuristics.Result) {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "Overlay permission not granted, skipping banner for $number")
            return
        }
        mainHandler.post { showInternal(context.applicationContext, number, verdict) }
    }

    private fun showInternal(context: Context, number: String, verdict: NumberHeuristics.Result) {
        // Clear synchronously (not via dismiss(), which posts to mainHandler) — we're
        // already on the main thread here (show() posted us there), so posting would
        // queue the cleanup *behind* the addView() below, and it would then tear down
        // the view we're about to create instead of the one before it. That was the
        // actual bug behind every "attached=false" observed while debugging this.
        clearCurrent()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Root window content: full width, transparent — the visible card sits
        // inside it with side margins so it reads as a floating card, not a bar.
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(CARD_COLOR))
                cornerRadius = dp(16).toFloat()
            }
        }

        // --- Top bar: status tag + close button ---
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusTag = TextView(context).apply {
            text = "Incoming call"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill(color = "#33FFFFFF", radiusDp = dp(10))
        }
        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(12), dp(2), dp(2), dp(2))
            setOnClickListener { dismiss() }
        }
        topBar.addView(statusTag)
        topBar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) // spacer
        topBar.addView(closeButton)

        // --- Body: avatar + name/number + location/label ---
        val bodyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val avatarSize = dp(48)
        val avatar = TextView(context).apply {
            text = initialFor(number)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val title = TextView(context).apply {
            text = number
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(context).apply {
            text = verdict.label
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 13f
        }
        textColumn.addView(title)
        textColumn.addView(subtitle)
        bodyRow.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize))
        bodyRow.addView(
            textColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // --- Footer: source of the info + branding ---
        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val sourceTag = TextView(context).apply {
            text = "Looking up name…"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
        }
        val branding = TextView(context).apply {
            text = "SpamBlok"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        footerRow.addView(sourceTag, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footerRow.addView(branding)

        card.addView(topBar)
        card.addView(bodyRow)
        card.addView(footerRow)
        root.addView(
            card,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Without this, Android keeps TYPE_APPLICATION_OVERLAY windows behind
                // the lock screen — the system InCallUI shows on top of it, leaving
                // our overlay invisible underneath whenever the phone is locked.
                @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            // Positioned mid-screen (like Truecaller's own card on this device) rather
            // than pinned to the very top, which on a full-screen call UI can land
            // inside a status-bar/cutout inset region and get clipped invisible.
            gravity = Gravity.CENTER_VERTICAL
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            return
        }

        windowManager = wm
        view = root
        statusTagText = statusTag
        avatarText = avatar
        titleText = title
        subtitleText = subtitle
        sourceTagText = sourceTag

        CallerInfoStore.setListener { info -> mainHandler.post { updateFromInfo(info) } }
        updateFromInfo(CallerInfoStore.snapshot())

        val runnable = Runnable { dismiss() }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
    }

    private fun updateFromInfo(info: CallerInfoStore.CallerInfo) {
        info.callState?.let { statusTagText?.text = it }

        val name = info.name
        val label = info.label
        if (!name.isNullOrBlank()) {
            titleText?.text = name
            avatarText?.text = initialFor(name)
            subtitleText?.text = listOfNotNull(info.number, label).joinToString(" · ")
        } else if (!label.isNullOrBlank()) {
            subtitleText?.text = label
        }

        sourceTagText?.text = when {
            !name.isNullOrBlank() && info.source == "db" -> "From our DB"
            !name.isNullOrBlank() && info.source == "banner" -> "Live lookup"
            else -> "Looking up name…"
        }
    }

    private fun initialFor(text: String): String =
        text.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    private fun pill(color: String, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = radiusDp.toFloat()
    }

    /** Callable from any thread; hops to the main thread if needed. */
    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearCurrent()
        } else {
            mainHandler.post { clearCurrent() }
        }
    }

    /** Must only be called on the main thread. */
    private fun clearCurrent() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        CallerInfoStore.setListener(null)
        val wm = windowManager
        val v = view
        if (wm != null && v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed (already removed?)", e)
            }
        }
        windowManager = null
        view = null
        statusTagText = null
        avatarText = null
        titleText = null
        subtitleText = null
        sourceTagText = null
    }
}
