package com.spamblok.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Small shared building blocks for SpamBlok's programmatic UI, used by both
 * [MainActivity] and [SettingsActivity] so the two don't duplicate styling. */
object UiKit {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun card(context: Context, parent: LinearLayout): LinearLayout {
        val cardView = MaterialCardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = dp(context, 1).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = 0
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        }
        cardView.addView(content)
        parent.addView(
            cardView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 12)
            },
        )
        return content
    }

    fun sectionTitle(context: Context, parent: LinearLayout, text: String) {
        parent.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(Color.parseColor("#1A1A2E"))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
    }

    fun sectionHint(context: Context, parent: LinearLayout, text: String) {
        parent.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 13f
                setPadding(0, dp(context, 4), 0, 0)
            },
        )
    }

    fun monoText(context: Context): TextView = TextView(context).apply {
        textSize = 12f
        setTextIsSelectable(true)
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor("#374151"))
    }

    fun emptyStateText(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.GRAY)
        textSize = 13f
        setPadding(0, dp(context, 8), 0, dp(context, 8))
    }

    fun fieldBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F5F7FA"))
        cornerRadius = dp(context, 10).toFloat()
        setStroke(dp(context, 1), Color.parseColor("#E5E7EB"))
    }

    fun statusPill(context: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(context, 10).toFloat()
    }

    fun secondaryButton(context: Context, text: String, onClick: () -> Unit): MaterialButton = MaterialButton(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0066FF"))
        cornerRadius = dp(context, 10)
        setOnClickListener { onClick() }
    }

    fun textButton(context: Context, text: String, onClick: () -> Unit): MaterialButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.borderlessButtonStyle,
    ).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#0066FF"))
        setOnClickListener { onClick() }
    }

    /** A circular "initial" avatar, e.g. for a call-log/contact row. */
    fun avatar(context: Context, sizeDp: Int, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColorFor(text))
        }
    }

    fun initialFor(text: String): String =
        text.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    private val avatarPalette = listOf("#0066FF", "#7C3AED", "#059669", "#DB2777", "#D97706", "#0891B2")

    private fun avatarColorFor(seed: String): Int {
        val index = (seed.hashCode().let { if (it < 0) -it else it }) % avatarPalette.size
        return Color.parseColor(avatarPalette[index])
    }
}
