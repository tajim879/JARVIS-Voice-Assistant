package com.jarvis.assistant.automation.features

import android.content.Context
import com.jarvis.assistant.JarvisApp
import org.json.JSONArray
import org.json.JSONObject

/** Tiny on-device store for voice notes, to-dos and expenses (kept in SharedPreferences). */
object ProductivityStore {

    const val NOTE = "note"
    const val TODO = "todo"
    const val EXPENSE = "expense"

    fun isValidKind(kind: String): Boolean = kind == NOTE || kind == TODO || kind == EXPENSE

    private fun prefs() =
        JarvisApp.instance.getSharedPreferences("jarvis_productivity", Context.MODE_PRIVATE)

    fun load(kind: String): JSONArray {
        return try {
            JSONArray(prefs().getString(kind, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun save(kind: String, arr: JSONArray) {
        prefs().edit().putString(kind, arr.toString()).apply()
    }

    fun add(kind: String, item: JSONObject) {
        val arr = load(kind)
        item.put("ts", System.currentTimeMillis())
        arr.put(item)
        save(kind, arr)
    }

    /** index1 is 1-based as shown to the user. Returns an error message or null on success. */
    fun update(kind: String, index1: Int, action: String): String? {
        val arr = load(kind)
        val i = index1 - 1
        if (i < 0 || i >= arr.length()) return "There is no item number $index1."
        when (action) {
            "delete" -> arr.remove(i)
            "done" -> {
                if (kind != TODO) return "Only to-do items can be marked done."
                arr.getJSONObject(i).put("done", true)
            }
            else -> return "Unknown action '$action'."
        }
        save(kind, arr)
        return null
    }
}
