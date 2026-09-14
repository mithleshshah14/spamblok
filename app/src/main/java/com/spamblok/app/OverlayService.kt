package com.spamblok.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
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
 */
object OverlayService {

    private const val TAG = "SpamBlokOverlay"
    private const val AUTO_DISMISS_MS = 45_000L

    private var windowManager: WindowManager? = null
    private var view: LinearLayout? = null
    private var numberText: TextView? = null
    private var nameText: TextView? = null
    private var verdictText: TextView? = null
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
        dismiss()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#DD222222"))
        }

        val numberView = TextView(context).apply {
            text = number
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val nameView = TextView(context).apply {
            text = "Looking up name…"
            setTextColor(Color.LTGRAY)
            textSize = 14f
        }
        val verdictColor = when (verdict.verdict) {
            NumberHeuristics.Verdict.LIKELY_SPAM -> Color.parseColor("#FF5252")
            NumberHeuristics.Verdict.SUSPICIOUS -> Color.parseColor("#FFB300")
            NumberHeuristics.Verdict.LIKELY_SERVICE -> Color.parseColor("#4CAF50")
            NumberHeuristics.Verdict.NEUTRAL -> Color.LTGRAY
        }
        val verdictView = TextView(context).apply {
            text = "${verdict.label} · SpamBlok"
            setTextColor(verdictColor)
            textSize = 13f
        }

        container.addView(numberView)
        container.addView(nameView)
        container.addView(verdictView)

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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(48)
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            return
        }

        windowManager = wm
        view = container
        numberText = numberView
        nameText = nameView
        verdictText = verdictView

        CallerInfoStore.setListener { info -> mainHandler.post { updateFromInfo(info) } }
        updateFromInfo(CallerInfoStore.snapshot())

        val runnable = Runnable { dismiss() }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
    }

    private fun updateFromInfo(info: CallerInfoStore.CallerInfo) {
        val name = info.name
        val label = info.label
        val base = when {
            !name.isNullOrBlank() && !label.isNullOrBlank() -> "$name — $label"
            !name.isNullOrBlank() -> name
            !label.isNullOrBlank() -> label
            else -> null
        }
        nameText?.text = when {
            base == null -> "Looking up name…"
            info.source == "db" -> "$base (from our DB)"
            else -> base
        }
    }

    fun dismiss() {
        mainHandler.post {
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
            numberText = null
            nameText = null
            verdictText = null
        }
    }
}
