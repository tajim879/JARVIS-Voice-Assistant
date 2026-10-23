package com.jarvis.assistant.ui.security

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.security.SecurityGate
import com.jarvis.assistant.util.ThemeManager

/**
 * "Allow JARVIS to do this?" card. Back, outside tap or Deny = denied. Closing it = denied.
 */
class ConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "confirm_id"
        const val EXTRA_ACTION = "confirm_action"
        const val EXTRA_DETAIL = "confirm_detail"
        const val EXTRA_DESTRUCTIVE = "confirm_destructive"
    }

    private var requestId: String = ""
    private var answered = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundRect(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A newer request replaces the old one; the old one is denied.
        if (!answered && requestId.isNotEmpty()) SecurityGate.resolve(requestId, false)
        setIntent(intent)
        answered = false
        render(intent)
    }

    private fun answer(allowed: Boolean) {
        if (!answered && requestId.isNotEmpty()) {
            answered = true
            SecurityGate.resolve(requestId, allowed)
        }
        finish()
    }

    private fun render(source: Intent) {
        requestId = source.getStringExtra(EXTRA_ID).orEmpty()
        val action = source.getStringExtra(EXTRA_ACTION).orEmpty()
        val detail = source.getStringExtra(EXTRA_DETAIL).orEmpty()
        val destructive = source.getBooleanExtra(EXTRA_DESTRUCTIVE, false)

        val prefs = try { JarvisApp.instance.preferences } catch (e: Exception) { null }
        val bg = ThemeManager.getThemeBgColor(prefs?.appTheme ?: "dark", prefs?.customThemeColor)
        val light = ThemeManager.isLight(bg)
        val textPrimary = if (light) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val textMuted = if (light) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")
        val accent = if (light) Color.parseColor("#2F6BFF") else Color.parseColor("#5B8CFF")
        val danger = Color.parseColor("#EF4444")
        val tone = if (destructive) danger else accent
        val cardFill = Color.argb(0xF8, Color.red(bg), Color.green(bg), Color.blue(bg))
        val cardStroke = Color.argb(0x88, Color.red(tone), Color.green(tone), Color.blue(tone))

        val root = FrameLayout(this)
        root.setOnClickListener { answer(false) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(cardFill, cardStroke, 28)
            setPadding(dp(20), dp(18), dp(20), dp(16))
            elevation = dp(16).toFloat()
            isClickable = true
        }

        val title = TextView(this).apply {
            text = (if (destructive) "\u26A0 " else "\uD83D\uDD12 ") + "Allow JARVIS to do this?"
            setTextColor(tone)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        card.addView(title)

        val actionView = TextView(this).apply {
            text = action
            setTextColor(textPrimary)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(
            actionView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )

        val detailView = TextView(this).apply {
            text = detail
            setTextColor(textMuted)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(detailView)
        card.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun button(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                setTextColor(textColor)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundRect(fill, cardStroke, 18)
                setPadding(dp(22), dp(11), dp(22), dp(11))
                setOnClickListener { onClick() }
            }
        buttons.addView(button("Deny", Color.TRANSPARENT, textPrimary) { answer(false) })
        buttons.addView(
            button("Allow", Color.argb(0x33, Color.red(tone), Color.green(tone), Color.blue(tone)), tone) { answer(true) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(10) }
        )
        card.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        )

        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(16)) }
        )
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        answer(false)
    }

    override fun onDestroy() {
        if (!answered && requestId.isNotEmpty()) SecurityGate.resolve(requestId, false)
        super.onDestroy()
    }
}
