package com.jarvis.assistant.ui.popup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/**
 * Floating answer card that Jarvis opens on top of any app: web answers, lists, notifications.
 * Colors follow the user's chosen app theme.
 */
class AnswerPopupActivity : AppCompatActivity() {

    companion object {
        private const val EX_TITLE = "popup_title"
        private const val EX_BODY = "popup_body"
        private const val EX_SRC_TITLES = "popup_src_titles"
        private const val EX_SRC_URIS = "popup_src_uris"

        fun show(
            context: Context,
            title: String,
            body: String,
            sources: List<Pair<String, String>> = emptyList()
        ) {
            val intent = Intent(context, AnswerPopupActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EX_TITLE, title)
                putExtra(EX_BODY, body)
                putStringArrayListExtra(EX_SRC_TITLES, ArrayList(sources.map { it.first }))
                putStringArrayListExtra(EX_SRC_URIS, ArrayList(sources.map { it.second }))
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        build(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundRect(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun build(source: Intent) {
        val title = source.getStringExtra(EX_TITLE).orEmpty()
        val body = source.getStringExtra(EX_BODY).orEmpty()
        val srcTitles = source.getStringArrayListExtra(EX_SRC_TITLES) ?: arrayListOf()
        val srcUris = source.getStringArrayListExtra(EX_SRC_URIS) ?: arrayListOf()

        val prefs = try { JarvisApp.instance.preferences } catch (e: Exception) { null }
        val themeKey = prefs?.appTheme ?: "dark"
        val bg = ThemeManager.getThemeBgColor(themeKey, prefs?.customThemeColor)
        val light = ThemeManager.isLight(bg)
        val textPrimary = if (light) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val textMuted = if (light) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")
        val accent = if (light) Color.parseColor("#2F6BFF") else Color.parseColor("#5B8CFF")
        val cardFill = Color.argb(0xF6, Color.red(bg), Color.green(bg), Color.blue(bg))
        val cardStroke = Color.argb(0x66, Color.red(accent), Color.green(accent), Color.blue(accent))

        val root = FrameLayout(this)
        root.setOnClickListener { finish() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(cardFill, cardStroke, 28)
            setPadding(dp(20), dp(18), dp(20), dp(16))
            elevation = dp(16).toFloat()
            isClickable = true
        }
        val screenH = resources.displayMetrics.heightPixels
        val tall = body.length > 320 || srcTitles.isNotEmpty()
        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (tall) (screenH * 0.74).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins(dp(12), dp(12), dp(12), dp(16)) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(textPrimary)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeX = TextView(this).apply {
            text = "\u2715"
            setTextColor(textMuted)
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        header.addView(closeX, LinearLayout.LayoutParams(dp(36), dp(36)))
        card.addView(header)

        // Body (scrolls)
        val bodyView = TextView(this).apply {
            text = renderMarkdown(body)
            setTextColor(textPrimary)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(bodyView)
        val scrollParams = if (tall) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollParams.topMargin = dp(10)
        card.addView(scroll, scrollParams)

        // Sources
        if (srcTitles.isNotEmpty()) {
            val label = TextView(this).apply {
                text = getString(R.string.popup_sources)
                setTextColor(textMuted)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            card.addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
            )
            for (i in srcTitles.indices.take(5)) {
                val uri = srcUris.getOrNull(i) ?: continue
                val link = TextView(this).apply {
                    text = "\u2197 " + srcTitles[i]
                    setTextColor(accent)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, dp(5))
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        } catch (e: Exception) {
                            // no browser available
                        }
                    }
                }
                card.addView(
                    link,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
        }

        // Buttons
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun chip(textRes: Int, onClick: () -> Unit): TextView = TextView(this).apply {
            text = getString(textRes)
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundRect(Color.TRANSPARENT, cardStroke, 18)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onClick() }
        }
        val chipParams = {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) }
        }
        buttons.addView(chip(R.string.popup_copy) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JARVIS", body))
            Toast.makeText(this, R.string.popup_copied, Toast.LENGTH_SHORT).show()
        }, chipParams())
        buttons.addView(chip(R.string.popup_share) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) body else title + "\n\n" + body)
            }
            startActivity(Intent.createChooser(send, null))
        }, chipParams())
        buttons.addView(chip(R.string.popup_close) { finish() }, chipParams())
        card.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        )

        root.addView(card, cardParams)
        setContentView(root)
    }

    /** Tiny markdown renderer: headings, **bold** and bullets. */
    private fun renderMarkdown(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (rawLine in text.split("\n")) {
            var line = rawLine.trimEnd()
            var wholeBold = false
            if (line.startsWith("#")) {
                line = line.trimStart('#').trim()
                wholeBold = true
            }
            if (line.startsWith("* ") || line.startsWith("- ")) {
                line = "\u2022 " + line.substring(2)
            }
            val pieces = line.split("**")
            for ((idx, piece) in pieces.withIndex()) {
                val start = sb.length
                sb.append(piece)
                if (idx % 2 == 1 || wholeBold) {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            sb.append("\n")
        }
        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)
        return sb
    }
}
