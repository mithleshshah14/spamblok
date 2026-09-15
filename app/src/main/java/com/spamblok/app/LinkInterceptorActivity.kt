package com.spamblok.app

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
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

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(Color.parseColor("#F5F7FA"))
                addView(
                    TextView(this@LinkInterceptorActivity).apply {
                        text = "Checking link…\n$uri"
                        setTextColor(Color.parseColor("#374151"))
                        textSize = 14f
                    },
                )
            },
        )

        LinkSafetyChecker.check(apiKey, uri.toString()) { verdict -> handleVerdict(uri, verdict) }
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
