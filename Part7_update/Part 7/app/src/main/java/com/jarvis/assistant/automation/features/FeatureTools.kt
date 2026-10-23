package com.jarvis.assistant.automation.features

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.contacts.ContactResolutionResult
import com.jarvis.assistant.util.CurrencyRepository
import com.jarvis.assistant.util.LocationWeatherHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extra voice tools. They are exposed to Gemini as function declarations, so the model
 * understands the user's request in ANY language and calls these with structured arguments.
 */
object FeatureTools {

    private const val TAG = "FeatureTools"

    private val NAMES = setOf(
        "set_alarm", "set_timer", "create_calendar_event", "compose_sms",
        "get_device_status", "open_system_panel", "get_crypto_price",
        "convert_currency", "daily_briefing", "export_memories"
    )

    fun handles(name: String): Boolean = name in NAMES

    // ---------------------------------------------------------------- declarations

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)

    private fun decl(
        name: String,
        description: String,
        properties: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList()
    ): JSONObject {
        val props = JSONObject()
        for ((k, v) in properties) props.put(k, v)
        val params = JSONObject().put("type", "OBJECT").put("properties", props)
        if (required.isNotEmpty()) {
            val req = JSONArray()
            required.forEach { req.put(it) }
            params.put("required", req)
        }
        return JSONObject().put("name", name).put("description", description).put("parameters", params)
    }

    fun declarations(): List<JSONObject> = listOf(
        decl(
            "set_alarm",
            "Sets an alarm on the phone. Use when the user asks to wake them or set an alarm, in any language. Convert spoken time to 24-hour hour and minute.",
            mapOf(
                "hour" to prop("INTEGER", "Hour in 24-hour format, 0-23."),
                "minute" to prop("INTEGER", "Minute, 0-59."),
                "label" to prop("STRING", "Optional alarm label."),
                "days" to JSONObject().put("type", "ARRAY")
                    .put("description", "Optional repeat days, 1=Sunday ... 7=Saturday. Omit for one-time alarm.")
                    .put("items", JSONObject().put("type", "INTEGER"))
            ),
            listOf("hour", "minute")
        ),
        decl(
            "set_timer",
            "Starts a countdown timer. Use for 'timer for 10 minutes' style requests in any language.",
            mapOf(
                "seconds" to prop("INTEGER", "Total duration in seconds."),
                "label" to prop("STRING", "Optional timer label.")
            ),
            listOf("seconds")
        ),
        decl(
            "create_calendar_event",
            "Opens the calendar with a new event prefilled so the user can confirm and save it. Use for meetings, appointments, reminders with a date.",
            mapOf(
                "title" to prop("STRING", "Event title."),
                "start_time" to prop("STRING", "Local start time as yyyy-MM-dd'T'HH:mm, e.g. 2026-11-03T15:30. Resolve words like tomorrow using the current date from get_device_status."),
                "duration_minutes" to prop("INTEGER", "Duration in minutes. Default 60."),
                "location" to prop("STRING", "Optional location."),
                "description" to prop("STRING", "Optional notes.")
            ),
            listOf("title", "start_time")
        ),
        decl(
            "compose_sms",
            "Opens the SMS app with the recipient and message prefilled. It does NOT send automatically; the user taps send. Recipient can be a contact name or a phone number.",
            mapOf(
                "recipient" to prop("STRING", "Contact name or phone number."),
                "message" to prop("STRING", "Message text, written in the language the user wants.")
            ),
            listOf("recipient", "message")
        ),
        decl(
            "get_device_status",
            "Returns battery level and charging state, network type, free storage, and the current local date, time and timezone. Use for 'how much battery', 'what time/date is it', or before resolving relative dates.",
        ),
        decl(
            "open_system_panel",
            "Opens a system settings screen. Android does not allow apps to flip these switches silently, so open the right screen and tell the user to toggle it.",
            mapOf(
                "panel" to prop(
                    "STRING",
                    "One of: wifi, bluetooth, internet, volume, nfc, airplane, dnd, hotspot, display, battery, location, sound, storage, language, accessibility."
                )
            ),
            listOf("panel")
        ),
        decl(
            "get_crypto_price",
            "Gets the live price of a cryptocurrency (CoinGecko), optionally in another currency. Use for 'bitcoin price', 'ETH koto', etc. in any language.",
            mapOf(
                "coin" to prop("STRING", "Coin name or symbol, e.g. bitcoin, BTC, ethereum, SOL."),
                "currency" to prop("STRING", "ISO fiat code or coin symbol to price in. Default USD.")
            ),
            listOf("coin")
        ),
        decl(
            "convert_currency",
            "Converts an amount between two currencies using live rates. Works for fiat codes (USD, BDT, EUR, INR...) and crypto symbols (BTC, ETH...).",
            mapOf(
                "amount" to prop("NUMBER", "Amount to convert."),
                "from" to prop("STRING", "Source currency code or coin symbol."),
                "to" to prop("STRING", "Target currency code or coin symbol.")
            ),
            listOf("amount", "from", "to")
        ),
        decl(
            "daily_briefing",
            "Collects data for a morning briefing: date/time, battery, weather, Bitcoin and Ethereum prices and the user's saved routines. Then summarise it warmly in the user's language.",
        ),
        decl(
            "export_memories",
            "Opens the share sheet with all of the user's saved memories as JSON text so they can back them up."
        )
    )

    // ---------------------------------------------------------------- execution

    suspend fun execute(context: Context, name: String, args: JSONObject, result: JSONObject): Boolean {
        if (!handles(name)) return false
        try {
            when (name) {
                "set_alarm" -> setAlarm(context, args, result)
                "set_timer" -> setTimer(context, args, result)
                "create_calendar_event" -> createCalendarEvent(context, args, result)
                "compose_sms" -> composeSms(context, args, result)
                "get_device_status" -> deviceStatus(context, result)
                "open_system_panel" -> openPanel(context, args, result)
                "get_crypto_price" -> cryptoPrice(args, result)
                "convert_currency" -> convertCurrency(args, result)
                "daily_briefing" -> dailyBriefing(context, result)
                "export_memories" -> exportMemories(context, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool $name failed: ${e.message}", e)
            result.put("success", false)
            result.put("message", "Error: ${e.message}")
        }
        return true
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "launch failed: ${e.message}")
            false
        }
    }

    private fun fail(result: JSONObject, message: String) {
        result.put("success", false)
        result.put("message", message)
    }

    private fun ok(result: JSONObject, message: String) {
        result.put("success", true)
        result.put("message", message)
    }

    private fun setAlarm(context: Context, args: JSONObject, result: JSONObject) {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) {
            fail(result, "Invalid time. Ask the user for a valid hour and minute.")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            val label = args.optString("label", "")
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            val daysJson = args.optJSONArray("days")
            if (daysJson != null && daysJson.length() > 0) {
                val days = ArrayList<Int>()
                for (i in 0 until daysJson.length()) {
                    val d = daysJson.optInt(i, -1)
                    if (d in 1..7) days.add(d)
                }
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, days)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(context, intent)) {
            ok(result, String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute))
        } else {
            fail(result, "No clock app could set the alarm.")
        }
    }

    private fun setTimer(context: Context, args: JSONObject, result: JSONObject) {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0 || seconds > 86400) {
            fail(result, "Invalid timer length. Ask the user for the duration.")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            val label = args.optString("label", "")
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(context, intent)) ok(result, "Timer started for $seconds seconds.")
        else fail(result, "No clock app could start the timer.")
    }

    private fun createCalendarEvent(context: Context, args: JSONObject, result: JSONObject) {
        val title = args.optString("title", "").ifBlank { "Event" }
        val startRaw = args.optString("start_time", "")
        val start = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(startRaw)
        } catch (e: Exception) {
            null
        }
        if (start == null) {
            fail(result, "Could not read start_time. Provide it as yyyy-MM-dd'T'HH:mm in local time.")
            return
        }
        val duration = args.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
        val begin = start.time
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + duration * 60_000L)
            val loc = args.optString("location", "")
            if (loc.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
            val desc = args.optString("description", "")
            if (desc.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, desc)
        }
        if (launch(context, intent)) ok(result, "Calendar event opened for the user to confirm and save.")
        else fail(result, "No calendar app found.")
    }

    private fun composeSms(context: Context, args: JSONObject, result: JSONObject) {
        val recipient = args.optString("recipient", "").trim()
        val message = args.optString("message", "")
        if (recipient.isEmpty()) {
            fail(result, "Who should the message go to?")
            return
        }
        var number = recipient
        val looksLikeNumber = recipient.any { it.isDigit() } &&
            recipient.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
        if (!looksLikeNumber) {
            val resolver = JarvisApp.instance.contactResolver
            when (val r = resolver.resolveContact(recipient)) {
                is ContactResolutionResult.SingleMatch -> number = r.contact.phoneNumber
                is ContactResolutionResult.MultipleMatches -> {
                    val names = r.candidates.take(4).joinToString(", ") { it.name }
                    fail(result, "Several contacts match: $names. Ask the user which one.")
                    return
                }
                ContactResolutionResult.PermissionDenied -> {
                    fail(result, "Contacts permission is not granted. Ask for a phone number instead.")
                    return
                }
                ContactResolutionResult.NoMatch -> {
                    fail(result, "No contact found for '$recipient'. Ask for the phone number.")
                    return
                }
            }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
            putExtra("sms_body", message)
        }
        if (launch(context, intent)) {
            ok(result, "SMS app opened with the message ready. The user must tap send; tell them so.")
        } else {
            fail(result, "No SMS app found.")
        }
    }

    private fun deviceStatus(context: Context, result: JSONObject) {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "connected"
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_073_741_824.0
        val totalGb = stat.totalBytes / 1_073_741_824.0

        val now = Date()
        result.put("success", true)
        result.put("battery_percent", pct)
        result.put("charging", charging)
        result.put("network", network)
        result.put("storage_free_gb", String.format(Locale.US, "%.1f", freeGb))
        result.put("storage_total_gb", String.format(Locale.US, "%.1f", totalGb))
        result.put("local_date", SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.US).format(now))
        result.put("local_time", SimpleDateFormat("HH:mm", Locale.US).format(now))
        result.put("timezone", java.util.TimeZone.getDefault().id)
        result.put("message", "Device status read.")
    }

    private fun openPanel(context: Context, args: JSONObject, result: JSONObject) {
        val panel = args.optString("panel", "").lowercase(Locale.ROOT).trim()
        val action: String? = when (panel) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "dnd" -> "android.settings.ZEN_MODE_PRIORITY_SETTINGS"
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> null
        }
        if (action == null) {
            fail(result, "Unknown panel '$panel'.")
            return
        }
        var opened = launch(context, Intent(action))
        if (!opened) opened = launch(context, Intent(Settings.ACTION_SETTINGS))
        if (opened) ok(result, "Opened the $panel screen. Ask the user to toggle it; Android blocks silent changes.")
        else fail(result, "Could not open settings.")
    }

    private suspend fun cryptoPrice(args: JSONObject, result: JSONObject) {
        val coin = args.optString("coin", "").trim()
        val currency = args.optString("currency", "USD").ifBlank { "USD" }
        CurrencyRepository.refreshAll(false)
        val value = CurrencyRepository.convert(1.0, coin, currency)
        if (value == null) {
            fail(result, "Could not find a live price for '$coin' in '$currency'.")
            return
        }
        val change = CurrencyRepository.cachedCoins()
            .firstOrNull { it.symbol.equals(CurrencyRepository.symbolOf(coin), ignoreCase = true) }
            ?.change24hPercent
        result.put("success", true)
        result.put("coin", CurrencyRepository.symbolOf(coin))
        result.put("currency", CurrencyRepository.symbolOf(currency))
        result.put("price", CurrencyRepository.formatNumber(value))
        if (change != null) result.put("change_24h_percent", String.format(Locale.US, "%.2f", change))
        result.put("message", "Live price fetched. Say it naturally in the user's language.")
    }

    private suspend fun convertCurrency(args: JSONObject, result: JSONObject) {
        val amount = args.optDouble("amount", Double.NaN)
        val from = args.optString("from", "").trim()
        val to = args.optString("to", "").trim()
        if (amount.isNaN() || from.isEmpty() || to.isEmpty()) {
            fail(result, "Need amount, from and to.")
            return
        }
        CurrencyRepository.refreshAll(false)
        val converted = CurrencyRepository.convert(amount, from, to)
        if (converted == null) {
            fail(result, "Could not convert $from to $to (unknown code or rates unavailable).")
            return
        }
        result.put("success", true)
        result.put("amount", CurrencyRepository.formatNumber(amount))
        result.put("from", CurrencyRepository.symbolOf(from))
        result.put("to", CurrencyRepository.symbolOf(to))
        result.put("converted", CurrencyRepository.formatNumber(converted))
        result.put("message", "Converted with live rates. Fiat rates update daily; crypto updates about every 30 seconds.")
    }

    private suspend fun dailyBriefing(context: Context, result: JSONObject) {
        deviceStatus(context, result)
        try {
            val w = LocationWeatherHelper.fetchWeather(context)
            if (w != null) {
                result.put("weather_city", w.cityName)
                result.put("weather_temperature", w.temperature)
                result.put("weather_condition", w.condition)
            } else {
                result.put("weather", "unavailable (location permission or signal missing)")
            }
        } catch (e: Exception) {
            result.put("weather", "unavailable")
        }
        try {
            CurrencyRepository.refreshAll(false)
            val btc = CurrencyRepository.convert(1.0, "BTC", "USD")
            val eth = CurrencyRepository.convert(1.0, "ETH", "USD")
            if (btc != null) result.put("btc_usd", CurrencyRepository.formatNumber(btc))
            if (eth != null) result.put("eth_usd", CurrencyRepository.formatNumber(eth))
        } catch (e: Exception) {
            Log.w(TAG, "briefing prices failed")
        }
        try {
            val routines = JarvisApp.instance.memoryManager.getRoutineResponse()
            if (routines.isNotBlank()) result.put("saved_routines", routines)
        } catch (e: Exception) {
            Log.w(TAG, "briefing routines failed")
        }
        result.put("success", true)
        result.put("message", "Briefing data ready. Give a short, warm spoken summary in the user's language.")
    }

    private suspend fun exportMemories(context: Context, result: JSONObject) {
        val memories = JarvisApp.instance.memoryRepository.getAllMemories()
        val arr = JSONArray()
        for (m in memories) {
            arr.put(
                JSONObject()
                    .put("category", m.category)
                    .put("key", m.key)
                    .put("value", m.value)
                    .put("saved_at", m.formattedDate)
            )
        }
        val text = JSONObject().put("app", "JARVIS").put("memories", arr).toString(2)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JARVIS memories backup")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "JARVIS memories")
        if (launch(context, chooser)) ok(result, "Share sheet opened with ${memories.size} memories.")
        else fail(result, "Could not open the share sheet.")
    }
}
