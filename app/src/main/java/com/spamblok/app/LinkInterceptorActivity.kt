package com.spamblok.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Opt-in link protection: once the user sets SpamBlok as their link-opening app in
 * Android's "Open by default" settings, tapping an http/https link anywhere on the
 * device (WhatsApp, Messages, any app) lands here first instead of going straight
 * to a browser. Checks the link via [LinkSafetyChecker], then forwards to the
 * user's actual browser — either automatically (safe / couldn't verify) or after
 * an explicit "Open anyway" on a flagged link.
 *
 * Fails open by design: no Safe Browsing API key configured, or a network/API
 * error, just forwards the link rather than blocking all browsing on the device.
 */
class LinkInterceptorActivity : AppCompatActivity() {

    private var pulseAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        val apiKey = SafeBrowsingKeyStore.get(this)
        if (apiKey == null) {
            forwardToBrowser(uri)
            finish()
            return
        }

        setContentView(buildCheckingView(uri))
        LinkSafetyChecker.check(apiKey, uri.toString()) { verdict -> handleVerdict(uri, verdict) }
    }

    /** A centered card with a pulsing shield behind a spinner — replaces what was a
     * plain white "Checking link…" screen with something that reads as active
     * protection working, not a stalled/frozen app. */
    private fun buildCheckingView(uri: Uri): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#0066FF"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
        }

        val iconStack = FrameLayout(this)
        val spinnerSize = dp(72)
        val spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0066FF"))
        }
        iconStack.addView(spinner, FrameLayout.LayoutParams(spinnerSize, spinnerSize))
        val shield = TextView(this).apply {
            text = "🛡️"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        iconStack.addView(shield, FrameLayout.LayoutParams(spinnerSize, spinnerSize))
        card.addView(iconStack, LinearLayout.LayoutParams(spinnerSize, spinnerSize))

        pulseAnimator = ObjectAnimator.ofFloat(shield, "scaleX", 0.85f, 1.15f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        ObjectAnimator.ofFloat(shield, "scaleY", 0.85f, 1.15f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        card.addView(
            TextView(this).apply {
                text = "Checking link safety…"
                setTextColor(Color.parseColor("#1A1A2E"))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(6))
            },
        )
        card.addView(
            TextView(this).apply {
                text = uri.toString()
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 3
            },
        )
        card.addView(
            TextView(this).apply {
                text = "Powered by Google Safe Browsing"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            },
        )

        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        super.onDestroy()
    }

    private fun handleVerdict(uri: Uri, verdict: LinkSafetyChecker.Verdict) {
        when (verdict.status) {
            LinkSafetyChecker.Status.SAFE -> {
                forwardToBrowser(uri)
                finish()
            }
            LinkSafetyChecker.Status.ERROR -> {
                Toast.makeText(this, "Couldn't verify link, opening anyway", Toast.LENGTH_SHORT).show()
                forwardToBrowser(uri)
                finish()
            }
            LinkSafetyChecker.Status.UNSAFE -> {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Dangerous link")
                    .setMessage("SpamBlok's link check flagged this as unsafe:\n${verdict.threatTypes.joinToString(", ")}\n\n$uri")
                    .setPositiveButton("Open anyway") { _, _ -> forwardToBrowser(uri); finish() }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    /** Opens [uri] in the user's actual browser. Once SpamBlok holds the Browser
     * role (required to get here at all), a generic ACTION_VIEW http/https query
     * resolves to SpamBlok ONLY — Android suppresses other candidates from that
     * query once a role holder is assigned, so excluding "self" from the results
     * leaves nothing. Instead, look up real browsers via CATEGORY_APP_BROWSER
     * (how launchers identify actual browser apps, independent of the current
     * default-handler resolution) and target one explicitly with setPackage —
     * each candidate intent is unambiguous, so SpamBlok never appears as an option
     * and there's no risk of looping back into this activity. */
    private fun forwardToBrowser(uri: Uri) {
        val browsers = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }

        if (browsers.isEmpty()) {
            Toast.makeText(this, "No other browser installed to open this link", Toast.LENGTH_LONG).show()
            return
        }

        try {
            if (browsers.size == 1) {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(browsers[0].activityInfo.packageName))
            } else {
                val targeted = browsers.map { info -> Intent(Intent.ACTION_VIEW, uri).setPackage(info.activityInfo.packageName) }
                val chooser = Intent.createChooser(targeted.first(), null).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
                }
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
}
