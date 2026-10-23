package com.jarvis.assistant.automation.features

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.ui.popup.AnswerPopupActivity
import com.jarvis.assistant.util.CurrencyRepository
import com.jarvis.assistant.util.WebSearchEngine
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Part 9 tools: live web research with an on-screen answer popup, notes / to-dos / expenses,
 * notification reading and navigation. Declared to Gemini so they work in any language.
 */
object MoreTools {

    private val NAMES = setOf(
        "web_search", "productivity_add", "productivity_list", "productivity_update",
        "read_notifications", "navigate_to",
        "calculate", "open_math_solver", "solve_math_visual", "set_eyes"
    )

    fun handles(name: String): Boolean = name in NAMES

    fun declarations(): List<JSONObject> = listOf(
        FeatureTools.decl(
            "web_search",
            "Searches the live internet (Google) and shows the answer in a popup on screen with sources. " +
                "Use whenever the user asks to search, research, look up, find out, or asks about news, scores, prices, " +
                "weather elsewhere, people, products, events, or anything that may have changed recently, in any language. " +
                "Use detail=detailed when the user says research, report, explain in depth.",
            mapOf(
                "query" to FeatureTools.prop("STRING", "The full search question, in the user's own words and language."),
                "detail" to FeatureTools.prop("STRING", "brief (default) or detailed.")
            ),
            listOf("query")
        ),
        FeatureTools.decl(
            "productivity_add",
            "Saves a note, a to-do item, or an expense. For expense put the description in text and the money in amount.",
            mapOf(
                "kind" to FeatureTools.prop("STRING", "note, todo or expense."),
                "text" to FeatureTools.prop("STRING", "The note, task or expense description."),
                "amount" to FeatureTools.prop("NUMBER", "Expense amount (expense only)."),
                "currency" to FeatureTools.prop("STRING", "Expense currency code such as BDT, USD. Default BDT."),
                "category" to FeatureTools.prop("STRING", "Optional expense category, e.g. food, transport.")
            ),
            listOf("kind", "text")
        ),
        FeatureTools.decl(
            "productivity_list",
            "Shows saved notes, to-dos or expenses in a popup and returns them. For expenses it also totals them.",
            mapOf("kind" to FeatureTools.prop("STRING", "note, todo or expense.")),
            listOf("kind")
        ),
        FeatureTools.decl(
            "productivity_update",
            "Marks a to-do as done or deletes an item, using its number from productivity_list.",
            mapOf(
                "kind" to FeatureTools.prop("STRING", "note, todo or expense."),
                "index" to FeatureTools.prop("INTEGER", "1-based item number."),
                "action" to FeatureTools.prop("STRING", "done (todo only) or delete.")
            ),
            listOf("kind", "index", "action")
        ),
        FeatureTools.decl(
            "read_notifications",
            "Reads recent phone notifications (messages, apps) and shows them in a popup. Needs Notification access to be enabled for JARVIS in system settings.",
            mapOf(
                "limit" to FeatureTools.prop("INTEGER", "How many to read, 1-20. Default 5."),
                "app" to FeatureTools.prop("STRING", "Optional app name filter such as WhatsApp or Telegram.")
            )
        ),
        FeatureTools.decl(
            "navigate_to",
            "Starts turn-by-turn navigation to a place in Google Maps.",
            mapOf(
                "destination" to FeatureTools.prop("STRING", "Place name or address."),
                "mode" to FeatureTools.prop("STRING", "driving (default), walking, transit or bicycling.")
            ),
            listOf("destination")
        ),
        FeatureTools.decl(
            "calculate",
            "Exact calculator for a plain calculation such as 12*(7+3)/4, sqrt(144), 2^10, sin(30 degrees), 15% style maths written as numbers. " +
                "Use it for any arithmetic so the result is exact. Understands digits in any script.",
            mapOf("expression" to FeatureTools.prop("STRING", "The expression, e.g. (3+4)*5/2.")),
            listOf("expression")
        ),
        FeatureTools.decl(
            "open_math_solver",
            "Opens the Math screen and solves a math problem step by step (algebra, equations, geometry, trigonometry, calculus, " +
                "statistics, word problems). Use when the problem is more than plain arithmetic or the user wants to see the steps.",
            mapOf("problem" to FeatureTools.prop("STRING", "The full problem text exactly as the user said it.")),
            listOf("problem")
        ),
        FeatureTools.decl(
            "solve_math_visual",
            "Solves the math problem that is visible right now: on the front camera (Eyes must be on) or on the shared screen. " +
                "Use when the user says 'solve this', 'ei ongko koro', 'dekho eta' while showing a page or screen.",
            mapOf(
                "source" to FeatureTools.prop("STRING", "camera, screen or auto (default)."),
                "hint" to FeatureTools.prop("STRING", "Optional extra instruction, e.g. which question number.")
            )
        ),
        FeatureTools.decl(
            "set_eyes",
            "Turns Jarvis Eyes on or off. When on, the front camera lets Jarvis see the user and surroundings while the JARVIS app is open on screen.",
            mapOf("enabled" to FeatureTools.prop("BOOLEAN", "true to turn the front camera on, false to turn it off.")),
            listOf("enabled")
        )
    )

    suspend fun execute(context: Context, name: String, args: JSONObject, result: JSONObject): Boolean {
        if (!handles(name)) return false
        when (name) {
            "web_search" -> webSearch(context, args, result)
            "productivity_add" -> productivityAdd(args, result)
            "productivity_list" -> productivityList(context, args, result)
            "productivity_update" -> productivityUpdate(args, result)
            "read_notifications" -> readNotifications(context, args, result)
            "navigate_to" -> navigateTo(context, args, result)
            "calculate" -> calculate(args, result)
            "open_math_solver" -> openMathSolver(context, args, result)
            "solve_math_visual" -> solveMathVisual(context, args, result)
            "set_eyes" -> setEyes(context, args, result)
        }
        return true
    }

    // ------------------------------------------------------------------ web search

    private suspend fun webSearch(context: Context, args: JSONObject, result: JSONObject) {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            FeatureTools.fail(result, "What should I search for?")
            return
        }
        val detailed = args.optString("detail", "brief").equals("detailed", ignoreCase = true)
        val r = WebSearchEngine.search(query, detailed)
        if (r.error != null) {
            // Show the technical reason on screen too, so a failure can be understood and fixed.
            AnswerPopupActivity.show(
                context,
                "Search problem",
                r.error + (if (r.detail != null) "\n\nDetails: " + r.detail else "")
            )
            FeatureTools.fail(result, r.error + (if (r.detail != null) " (" + r.detail.take(160) + ")" else ""))
            return
        }
        val shownAnswer = if (r.fallback) {
            "Gemini search was unavailable, so these are raw web results.\n\n" + r.answer
        } else {
            r.answer
        }
        AnswerPopupActivity.show(context, query, shownAnswer, r.sources.map { Pair(it.title, it.uri) })
        result.put("success", true)
        result.put("answer", r.answer.take(1500))
        result.put("source_count", r.sources.size)
        result.put("message", "The full answer with sources is now open in a popup on the user's screen.")
        result.put(
            "system_directive",
            "Speak a SHORT natural summary (2 to 4 sentences) of the answer in the user's language and mention the details are on screen. " +
                "Do not read URLs, markdown symbols or the source list aloud."
        )
    }

    // ------------------------------------------------------------------ notes / todos / expenses

    private fun productivityAdd(args: JSONObject, result: JSONObject) {
        val kind = args.optString("kind", "").lowercase(Locale.ROOT).trim()
        val text = args.optString("text", "").trim()
        if (!ProductivityStore.isValidKind(kind) || text.isEmpty()) {
            FeatureTools.fail(result, "Need kind (note, todo or expense) and text.")
            return
        }
        val item = JSONObject().put("text", text)
        if (kind == ProductivityStore.TODO) item.put("done", false)
        if (kind == ProductivityStore.EXPENSE) {
            val amount = args.optDouble("amount", Double.NaN)
            if (amount.isNaN() || amount <= 0.0) {
                FeatureTools.fail(result, "How much was the expense?")
                return
            }
            item.put("amount", amount)
            item.put("currency", args.optString("currency", "BDT").ifBlank { "BDT" }.uppercase(Locale.ROOT))
            val cat = args.optString("category", "")
            if (cat.isNotBlank()) item.put("category", cat)
        }
        ProductivityStore.add(kind, item)
        FeatureTools.ok(result, "Saved $kind.")
    }

    private fun formatItem(kind: String, index: Int, o: JSONObject): String {
        val text = o.optString("text", "")
        return when (kind) {
            ProductivityStore.TODO -> {
                val box = if (o.optBoolean("done", false)) "[x]" else "[ ]"
                "$index. $box $text"
            }
            ProductivityStore.EXPENSE -> {
                val cat = o.optString("category", "")
                val catPart = if (cat.isNotBlank()) " \u2022 $cat" else ""
                val amountText = CurrencyRepository.formatNumber(o.optDouble("amount", 0.0))
                "$index. $amountText ${o.optString("currency", "")}$catPart \u2022 $text"
            }
            else -> "$index. $text"
        }
    }

    private suspend fun productivityList(context: Context, args: JSONObject, result: JSONObject) {
        val kind = args.optString("kind", "").lowercase(Locale.ROOT).trim()
        if (!ProductivityStore.isValidKind(kind)) {
            FeatureTools.fail(result, "kind must be note, todo or expense.")
            return
        }
        val arr = ProductivityStore.load(kind)
        val title = when (kind) {
            ProductivityStore.TODO -> "To-do"
            ProductivityStore.EXPENSE -> "Expenses"
            else -> "Notes"
        }
        if (arr.length() == 0) {
            FeatureTools.ok(result, "The $kind list is empty.")
            return
        }
        val lines = ArrayList<String>()
        for (i in 0 until arr.length()) lines.add(formatItem(kind, i + 1, arr.getJSONObject(i)))

        if (kind == ProductivityStore.EXPENSE) {
            CurrencyRepository.refreshAll(false)
            val perCurrency = LinkedHashMap<String, Double>()
            var totalUsd = 0.0
            var usdOk = true
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cur = o.optString("currency", "BDT")
                val amt = o.optDouble("amount", 0.0)
                perCurrency[cur] = (perCurrency[cur] ?: 0.0) + amt
                val usd = CurrencyRepository.convert(amt, cur, "USD")
                if (usd == null) usdOk = false else totalUsd += usd
            }
            lines.add("")
            val totals = perCurrency.entries.joinToString("  |  ") {
                CurrencyRepository.formatNumber(it.value) + " " + it.key
            }
            lines.add("Total: $totals")
            if (usdOk) lines.add("\u2248 " + CurrencyRepository.formatNumber(totalUsd) + " USD")
            result.put("totals", totals)
        }
        val body = lines.joinToString("\n")
        AnswerPopupActivity.show(context, title, body)
        result.put("success", true)
        result.put("items", body)
        result.put("message", "List is open in a popup. Read out only a short summary unless asked.")
    }

    private fun productivityUpdate(args: JSONObject, result: JSONObject) {
        val kind = args.optString("kind", "").lowercase(Locale.ROOT).trim()
        val index = args.optInt("index", -1)
        val action = args.optString("action", "").lowercase(Locale.ROOT).trim()
        if (!ProductivityStore.isValidKind(kind) || index < 1) {
            FeatureTools.fail(result, "Need kind and a valid item number.")
            return
        }
        val error = ProductivityStore.update(kind, index, action)
        if (error != null) FeatureTools.fail(result, error)
        else FeatureTools.ok(result, "Item $index updated.")
    }

    // ------------------------------------------------------------------ notifications

    private fun readNotifications(context: Context, args: JSONObject, result: JSONObject) {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!enabled) {
            FeatureTools.launch(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            FeatureTools.fail(
                result,
                "Notification access is off. I opened the settings screen; ask the user to enable JARVIS there. " +
                    "On newer Android versions they may first need App info > menu > Allow restricted settings."
            )
            return
        }
        val limit = args.optInt("limit", 5)
        val filter = args.optString("app", "")
        val list = NotificationStore.recent(limit, filter)
        if (list.isEmpty()) {
            FeatureTools.ok(result, "No notifications captured yet. Only new ones arriving after access was enabled are available.")
            return
        }
        val df = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
        val lines = list.mapIndexed { i, n ->
            val head = if (n.title.isNotBlank()) n.title else n.app
            "${i + 1}. ${n.app} (${df.format(Date(n.time))})\n   $head: ${n.text}".trimEnd()
        }
        val body = lines.joinToString("\n\n")
        AnswerPopupActivity.show(context, "Notifications", body)
        result.put("success", true)
        result.put("notifications", body.take(2500))
        result.put("message", "Notifications are shown in a popup. Read them naturally, newest first, and offer to reply.")
    }

    // ------------------------------------------------------------------ navigation

    private fun navigateTo(context: Context, args: JSONObject, result: JSONObject) {
        val dest = args.optString("destination", "").trim()
        if (dest.isEmpty()) {
            FeatureTools.fail(result, "Where should I navigate to?")
            return
        }
        val modeCode = when (args.optString("mode", "driving").lowercase(Locale.ROOT)) {
            "walking" -> "w"
            "bicycling" -> "b"
            "transit" -> "r"
            else -> "d"
        }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=" + modeCode))
            .setPackage("com.google.android.apps.maps")
        var opened = FeatureTools.launch(context, nav)
        if (!opened) {
            opened = FeatureTools.launch(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
            )
        }
        if (opened) FeatureTools.ok(result, "Navigation to $dest started.")
        else FeatureTools.fail(result, "No maps app found.")
    }

    // ------------------------------------------------------------------ math and eyes (Part 10)

    private fun calculate(args: JSONObject, result: JSONObject) {
        val expression = args.optString("expression", "").trim()
        val value = if (expression.isEmpty()) null else com.jarvis.assistant.util.MathEngine.evaluate(expression)
        if (value == null) {
            FeatureTools.fail(result, "Could not calculate that. Use open_math_solver for harder problems.")
            return
        }
        result.put("success", true)
        result.put("expression", expression)
        result.put("result", com.jarvis.assistant.util.MathEngine.format(value))
        result.put("message", "Exact result computed on the device. Say it naturally in the user's language.")
    }

    private fun openMathSolver(context: Context, args: JSONObject, result: JSONObject) {
        val problem = args.optString("problem", "").trim()
        if (problem.isEmpty()) {
            FeatureTools.fail(result, "What is the problem?")
            return
        }
        val intent = Intent(context, com.jarvis.assistant.ui.math.MathActivity::class.java).apply {
            putExtra(com.jarvis.assistant.ui.math.MathActivity.EXTRA_PROBLEM, problem)
            putExtra(com.jarvis.assistant.ui.math.MathActivity.EXTRA_AUTO_SOLVE, true)
        }
        if (FeatureTools.launch(context, intent)) {
            FeatureTools.ok(result, "The Math screen is open and solving step by step. Tell the user to look at the screen.")
        } else {
            FeatureTools.fail(result, "Could not open the Math screen.")
        }
    }

    private suspend fun solveMathVisual(context: Context, args: JSONObject, result: JSONObject) {
        val source = args.optString("source", "auto").lowercase(Locale.ROOT)
        val hint = args.optString("hint", "").trim()
        val screen = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()

        var jpeg: ByteArray? = null
        var used = ""
        if (source == "camera" || source == "auto") {
            jpeg = com.jarvis.assistant.vision.CameraEyes.freshFrame()
            if (jpeg != null) used = "camera"
        }
        if (jpeg == null && (source == "screen" || source == "auto") && screen.isSharing.value) {
            jpeg = screen.captureSnapshotJpeg(85)
            if (jpeg != null) used = "screen"
        }
        if (jpeg == null) {
            FeatureTools.fail(
                result,
                "Nothing to look at. Ask the user to turn on Eyes (front camera) or start screen sharing, then try again."
            )
            return
        }
        val solution = com.jarvis.assistant.util.MathSolver.solveJpeg(hint, jpeg, "auto")
        if (solution.error != null) {
            FeatureTools.fail(result, solution.error + (if (solution.detail != null) " (" + solution.detail.take(120) + ")" else ""))
            return
        }
        AnswerPopupActivity.show(context, "Math", solution.text)
        result.put("success", true)
        result.put("looked_at", used)
        result.put("answer", solution.text.take(1500))
        result.put("message", "The full step-by-step solution is open in a popup on screen.")
        result.put(
            "system_directive",
            "Say the final answer and one short line about the method, in the user's language. " +
                "Do not read every step aloud, and do not read symbols or markdown."
        )
    }

    private fun setEyes(context: Context, args: JSONObject, result: JSONObject) {
        val enabled = args.optBoolean("enabled", true)
        if (enabled && !com.jarvis.assistant.vision.CameraEyes.hasPermission(context)) {
            FeatureTools.fail(result, "Camera permission is missing. Ask the user to tap the Eyes button on the Home screen once and allow the camera.")
            return
        }
        com.jarvis.assistant.vision.CameraEyes.setEnabled(context, enabled)
        FeatureTools.ok(
            result,
            if (enabled) "Eyes are on. Jarvis sees through the front camera while the JARVIS app is open on screen; Android stops the camera when the app is hidden."
            else "Eyes are off."
        )
    }
}
