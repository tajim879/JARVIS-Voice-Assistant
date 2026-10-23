package com.jarvis.assistant.ui.advanced

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.features.ProductivityStore
import com.jarvis.assistant.pro.CodeWorkspace
import com.jarvis.assistant.pro.OfflineAssistant
import com.jarvis.assistant.pro.Planner
import com.jarvis.assistant.pro.PhoneLink
import com.jarvis.assistant.pro.PluginManager
import com.jarvis.assistant.pro.ScheduleManager
import com.jarvis.assistant.pro.ScheduleStore
import com.jarvis.assistant.pro.VisionAssistant
import com.jarvis.assistant.security.SecurityGate
import com.jarvis.assistant.ui.popup.AnswerPopupActivity
import com.jarvis.assistant.util.MathSolver
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Pro Capabilities: Vision, Coding Agent, Automation, Phone Link, Security,
 * Offline Mode, Smart Planning and the Plugin System, all in one themed screen.
 */
class AdvancedActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var tvCoding: TextView
    private lateinit var tvAuto: TextView
    private lateinit var tvLink: TextView
    private lateinit var tvPlugins: TextView
    private lateinit var chipStrict: TextView
    private lateinit var chipNormal: TextView
    private lateinit var chipOffOn: TextView
    private lateinit var chipOffOff: TextView
    private lateinit var etPairing: EditText

    private var textPrimary = Color.WHITE
    private var textMuted = Color.GRAY
    private var textSecondary = Color.GRAY
    private var accent = Color.parseColor("#2F6BFF")

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) askAboutImage(uri)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        textPrimary = themeColor(R.attr.themeTextPrimary, Color.WHITE)
        textMuted = themeColor(R.attr.themeTextMuted, Color.GRAY)
        textSecondary = themeColor(R.attr.themeTextSecondary, Color.GRAY)
        accent = themeColor(R.attr.themeAccent, accent)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header
        val header = FrameLayout(this).apply { setPadding(dp(16), dp(16), dp(16), dp(10)) }
        val back = TextView(this).apply {
            text = "\u2190"
            textSize = 24f
            setTextColor(textPrimary)
            setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener { finish() }
        }
        header.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        val title = TextView(this).apply {
            setText(R.string.pro_title)
            textSize = 17f
            setTextColor(textPrimary)
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        }
        header.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(header)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ThemeManager.applyDynamicBackground(root)

        buildVision()
        buildCoding()
        buildAutomation()
        buildPhoneLink()
        buildSecurity()
        buildOffline()
        buildPlanning()
        buildPlugins()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ building blocks

    private fun card(icon: String, titleRes: Int, descRes: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_jarvis_action_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = icon + "  " + getString(titleRes)
            textSize = 16f
            setTextColor(textPrimary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(
            TextView(this).apply {
                setText(descRes)
                textSize = 12.5f
                setTextColor(textMuted)
                setLineSpacing(0f, 1.15f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        content.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
        return card
    }

    private fun status(card: LinearLayout): TextView {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        card.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return tv
    }

    private fun buttonRow(card: LinearLayout): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return row
    }

    private fun button(row: LinearLayout, textRes: Int, primary: Boolean = false, onClick: () -> Unit): TextView {
        val b = TextView(this).apply {
            setText(textRes)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (primary) accent else textPrimary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(if (primary) R.drawable.bg_chip_selected else R.drawable.bg_jarvis_chip)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (row.childCount > 0) lp.marginStart = dp(8)
        row.addView(b, lp)
        return b
    }

    private fun styleChips(selected: TextView, others: List<TextView>) {
        selected.setBackgroundResource(R.drawable.bg_chip_selected)
        selected.setTextColor(accent)
        for (o in others) {
            o.setBackgroundResource(R.drawable.bg_chip_unselected)
            o.setTextColor(textSecondary)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun askText(titleRes: Int, hintRes: Int, multiline: Boolean, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setHint(hintRes)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
            } else {
                setSingleLine(true)
            }
        }
        val frame = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        frame.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 1. Vision

    private fun buildVision() {
        val c = card("\uD83D\uDC41", R.string.pro_vision_title, R.string.pro_vision_desc)
        val row = buttonRow(c)
        button(row, R.string.pro_vision_pick, true) { pickImage.launch("image/*") }
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / s > maxSide) s *= 2
        return s
    }

    private fun askAboutImage(uri: Uri) {
        askText(R.string.pro_vision_ask_title, R.string.pro_vision_hint, false) { question ->
            toast(getString(R.string.pro_working))
            lifecycleScope.launch {
                val jpeg = withContext(Dispatchers.IO) {
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                        var rotation = 0
                        try {
                            val exif = ExifInterface(ByteArrayInputStream(bytes))
                            rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }
                        } catch (e: Exception) {
                            rotation = 0
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2000) }
                        var bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
                        if (rotation != 0) {
                            val m = Matrix()
                            m.postRotate(rotation.toFloat())
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        }
                        MathSolver.encodeJpeg(bmp)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (jpeg == null) {
                    toast("Could not open that image.")
                    return@launch
                }
                val answer = VisionAssistant.answer(question, listOf(jpeg))
                AnswerPopupActivity.show(this@AdvancedActivity, "Vision", if (answer.error == null) answer.text else answer.error)
            }
        }
    }

    // ------------------------------------------------------------------ 2. Coding Agent

    private fun buildCoding() {
        val c = card("\uD83D\uDCBB", R.string.pro_coding_title, R.string.pro_coding_desc)
        tvCoding = status(c)
        val row = buttonRow(c)
        button(row, R.string.pro_coding_view) {
            val files = CodeWorkspace.list(this)
            if (files.isEmpty()) {
                toast(getString(R.string.pro_coding_empty))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.pro_coding_title)
                    .setItems(files.toTypedArray()) { _, which ->
                        val text = CodeWorkspace.read(this, files[which]) ?: ""
                        AnswerPopupActivity.show(this, files[which], text)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ 3. Automation

    private fun buildAutomation() {
        val c = card("\u23F0", R.string.pro_auto_title, R.string.pro_auto_desc)
        tvAuto = status(c)
        val row = buttonRow(c)
        button(row, R.string.pro_auto_add, true) { addReminderDialog() }
        button(row, R.string.pro_auto_view) { viewScheduled() }
    }

    private fun addReminderDialog() {
        val text = EditText(this).apply { setHint(R.string.pro_auto_text_hint); setSingleLine(true) }
        val minutes = EditText(this).apply {
            setHint(R.string.pro_auto_minutes_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("10")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(text)
            addView(minutes)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pro_auto_add)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val msg = text.text.toString().trim()
                val min = minutes.text.toString().trim().toIntOrNull() ?: 0
                if (msg.isNotEmpty() && min > 0) {
                    ScheduleManager.create(this, "reminder", msg, System.currentTimeMillis() + min * 60_000L, "none")
                    toast(getString(R.string.pro_auto_added))
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun viewScheduled() {
        val items = ScheduleStore.load(this)
        if (items.isEmpty()) {
            toast(getString(R.string.pro_auto_none))
            return
        }
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val lines = items.map { "[" + it.type + "] " + it.text.take(50) + "\n" + fmt.format(Date(it.atMillis)) + (if (it.repeat != "none") " (" + it.repeat + ")" else "") }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pro_auto_view)
            .setItems(lines.toTypedArray()) { _, which ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.pro_remove_q)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        ScheduleManager.cancel(this, items[which].id)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 4. Phone Link

    private fun buildPhoneLink() {
        val c = card("\uD83D\uDDA5", R.string.pro_link_title, R.string.pro_link_desc)
        tvLink = status(c)
        etPairing = EditText(this).apply {
            setHint(R.string.pro_link_code_hint)
            setSingleLine(true)
            textSize = 13f
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
        }
        c.addView(etPairing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val row = buttonRow(c)
        button(row, R.string.pro_link_save, true) {
            val error = PhoneLink.applyPairingCode(this, etPairing.text.toString())
            if (error != null) {
                toast(error)
            } else {
                etPairing.setText("")
                toast(getString(R.string.pro_link_saved))
                refresh()
            }
        }
        button(row, R.string.pro_link_test) {
            lifecycleScope.launch {
                val reply = PhoneLink.call(this@AdvancedActivity, "/info", JSONObject(), 12L)
                toast(if (reply.ok) getString(R.string.pro_link_ok) else reply.text)
            }
        }
        button(row, R.string.pro_link_unpair) {
            PhoneLink.clear(this)
            refresh()
        }
    }

    // ------------------------------------------------------------------ 5. Security

    private fun buildSecurity() {
        val c = card("\uD83D\uDD12", R.string.pro_sec_title, R.string.pro_sec_desc)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipStrict = TextView(this).apply {
            setText(R.string.pro_sec_strict)
            textSize = 13f
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { SecurityGate.setLevel(this@AdvancedActivity, SecurityGate.LEVEL_STRICT); refresh() }
        }
        chipNormal = TextView(this).apply {
            setText(R.string.pro_sec_normal)
            textSize = 13f
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { SecurityGate.setLevel(this@AdvancedActivity, SecurityGate.LEVEL_NORMAL); refresh() }
        }
        chips.addView(chipStrict)
        chips.addView(chipNormal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        c.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val row = buttonRow(c)
        button(row, R.string.pro_sec_log) {
            val log = SecurityGate.recentLog(this)
            AnswerPopupActivity.show(
                this, getString(R.string.pro_sec_log),
                if (log.isEmpty()) getString(R.string.pro_sec_log_empty) else log.joinToString("\n")
            )
        }
        button(row, R.string.pro_sec_clear) {
            SecurityGate.clearLog(this)
            toast("OK")
        }
    }

    // ------------------------------------------------------------------ 6. Offline

    private fun buildOffline() {
        val c = card("\uD83D\uDCF4", R.string.pro_off_title, R.string.pro_off_desc)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipOffOn = TextView(this).apply {
            setText(R.string.pro_off_on)
            textSize = 13f
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { OfflineAssistant.setEnabled(this@AdvancedActivity, true); refresh() }
        }
        chipOffOff = TextView(this).apply {
            setText(R.string.pro_off_off)
            textSize = 13f
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { OfflineAssistant.setEnabled(this@AdvancedActivity, false); refresh() }
        }
        chips.addView(chipOffOn)
        chips.addView(chipOffOff, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        c.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val row = buttonRow(c)
        button(row, R.string.pro_off_try, true) {
            toast(getString(R.string.pro_off_say))
            OfflineAssistant.listenOnce(this) { }
        }
    }

    // ------------------------------------------------------------------ 7. Smart Planning

    private fun buildPlanning() {
        val c = card("\uD83E\uDDE0", R.string.pro_plan_title, R.string.pro_plan_desc)
        val row = buttonRow(c)
        button(row, R.string.pro_plan_btn, true) {
            askText(R.string.pro_plan_btn, R.string.pro_plan_hint, true) { goal ->
                toast(getString(R.string.pro_working))
                lifecycleScope.launch {
                    val plan = Planner.plan(goal)
                    if (plan.error != null) {
                        AnswerPopupActivity.show(this@AdvancedActivity, "Plan", plan.error)
                        return@launch
                    }
                    val sb = StringBuilder()
                    plan.steps.forEachIndexed { i, (step, minutes) ->
                        sb.append(i + 1).append(". ").append(step)
                        if (minutes > 0) sb.append(" (~").append(minutes).append(" min)")
                        sb.append("\n")
                        ProductivityStore.add(
                            ProductivityStore.TODO,
                            JSONObject().put("text", "[" + plan.title.take(40) + "] " + step).put("done", false)
                        )
                    }
                    AnswerPopupActivity.show(this@AdvancedActivity, plan.title, sb.toString().trim())
                }
            }
        }
    }

    // ------------------------------------------------------------------ 8. Plugins

    private fun buildPlugins() {
        val c = card("\uD83E\uDDE9", R.string.pro_plug_title, R.string.pro_plug_desc)
        tvPlugins = status(c)
        val row = buttonRow(c)
        button(row, R.string.pro_plug_add, true) {
            askText(R.string.pro_plug_add, R.string.pro_plug_hint, true) { json ->
                val error = PluginManager.install(this, json)
                toast(error ?: getString(R.string.pro_plug_added))
                refresh()
            }
        }
        val row2 = buttonRow(c)
        button(row2, R.string.pro_plug_examples) {
            val n = PluginManager.installExamples(this)
            toast(getString(R.string.pro_plug_added) + " (" + n + ")")
            refresh()
        }
        button(row2, R.string.pro_plug_manage) {
            val plugins = PluginManager.list(this)
            if (plugins.isEmpty()) {
                toast(getString(R.string.pro_plug_none))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.pro_plug_manage)
                    .setItems(plugins.map { it.name + " - " + it.description.take(50) }.toTypedArray()) { _, which ->
                        MaterialAlertDialogBuilder(this)
                            .setMessage(R.string.pro_remove_q)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                PluginManager.remove(this, plugins[which].name)
                                refresh()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ status

    private fun refresh() {
        if (!::tvCoding.isInitialized) return
        tvCoding.text = getString(R.string.pro_coding_files, CodeWorkspace.list(this).size)
        tvAuto.text = getString(R.string.pro_auto_count, ScheduleStore.load(this).size)
        tvPlugins.text = getString(R.string.pro_plug_count, PluginManager.list(this).size)
        tvLink.text = if (PhoneLink.isConfigured(this)) {
            getString(R.string.pro_link_paired, PhoneLink.host(this))
        } else {
            getString(R.string.pro_link_not_paired)
        }
        if (SecurityGate.level(this) == SecurityGate.LEVEL_STRICT) styleChips(chipStrict, listOf(chipNormal))
        else styleChips(chipNormal, listOf(chipStrict))
        if (OfflineAssistant.isEnabled(this)) styleChips(chipOffOn, listOf(chipOffOff))
        else styleChips(chipOffOff, listOf(chipOffOn))
    }
}
