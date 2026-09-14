package com.spamblok.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Owns SpamBlok's overlay window as a real, foreground-visible Android
 * component instead of a transient [android.telecom.CallScreeningService]
 * callback, so the banner has a natural place to live for as long as the call
 * is ringing (own ongoing notification) rather than being torn down as soon
 * as the screening callback returns.
 */
class OverlayForegroundService : Service() {

    companion object {
        private const val TAG = "SpamBlokOverlayFg"
        private const val CHANNEL_ID = "spamblok_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_AFTER_MS = 50_000L // a little past OverlayService's own auto-dismiss

        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_VERDICT = "verdict"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_CONFIDENCE = "confidence"

        fun start(context: Context, number: String, verdict: NumberHeuristics.Result) {
            val intent = Intent(context, OverlayForegroundService::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_VERDICT, verdict.verdict.name)
                putExtra(EXTRA_LABEL, verdict.label)
                putExtra(EXTRA_REASON, verdict.reason)
                putExtra(EXTRA_CONFIDENCE, verdict.confidence)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed for $number", e)
            }
        }
    }

    private val stopHandler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SpamBlok caller banner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra(EXTRA_NUMBER)
        if (number == null) {
            Log.w(TAG, "onStartCommand without number; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val verdict = NumberHeuristics.Result(
            verdict = NumberHeuristics.Verdict.valueOf(
                intent.getStringExtra(EXTRA_VERDICT) ?: NumberHeuristics.Verdict.NEUTRAL.name,
            ),
            label = intent.getStringExtra(EXTRA_LABEL) ?: "",
            reason = intent.getStringExtra(EXTRA_REASON) ?: "",
            confidence = intent.getIntExtra(EXTRA_CONFIDENCE, 0),
        )

        startForeground(NOTIFICATION_ID, buildNotification(number))
        OverlayService.show(this, number, verdict)

        stopHandler.removeCallbacks(stopRunnable)
        stopHandler.postDelayed(stopRunnable, STOP_AFTER_MS)
        return START_NOT_STICKY
    }

    private fun buildNotification(number: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpamBlok")
            .setContentText("Showing caller info for $number")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopHandler.removeCallbacks(stopRunnable)
        OverlayService.dismiss()
        super.onDestroy()
    }
}
