package com.spamblok.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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

    /** Opens [uri] in the user's actual browser — explicitly excluding SpamBlok
     * itself from the candidates/chooser, since we're also registered as a handler
     * for http/https (that's how we got this tap in the first place) and would
     * otherwise show up as an option or, worse, loop back into this activity. */
    private fun forwardToBrowser(uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
        val candidates = packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val others = candidates.filter { it.activityInfo.packageName != packageName }

        if (others.isEmpty()) {
            Toast.makeText(this, "No browser found to open this link", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (others.size == 1) {
                viewIntent.setClassName(others[0].activityInfo.packageName, others[0].activityInfo.name)
                startActivity(viewIntent)
            } else {
                val chooser = Intent.createChooser(viewIntent, null)
                val excluded = candidates
                    .filter { it.activityInfo.packageName == packageName }
                    .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                    .toTypedArray()
                if (excluded.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded)
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)
}
