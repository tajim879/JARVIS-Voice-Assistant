package com.jarvis.assistant.pro

import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.GeminiRest
import org.json.JSONArray
import org.json.JSONObject

/** Smart Planning: breaks one big goal into small ordered steps (with rough minutes) using Gemini. */
object Planner {

    class Plan(val title: String, val steps: List<Pair<String, Int>>, val error: String?)

    suspend fun plan(goal: String): Plan {
        val apiKey = try { JarvisApp.instance.preferences.apiKey } catch (e: Exception) { "" }
        val instruction =
            "You are a planning assistant. Break the user's goal into 4 to 10 small, concrete, ordered steps, each " +
                "doable in one sitting. Reply with ONLY valid JSON, no markdown: " +
                "{\"title\":\"short title\",\"steps\":[{\"step\":\"what to do\",\"minutes\":15}]}. " +
                "Write in the same language as the goal."
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", goal)))
            ))
            put("generationConfig", JSONObject().put("maxOutputTokens", 4096).put("responseMimeType", "application/json"))
        }
        val reply = GeminiRest.generate(apiKey, body, budgetMs = 30_000L, strong = false)
        val json = reply.json ?: return Plan(goal, emptyList(), reply.detail ?: "The planner could not be reached.")
        val text = GeminiRest.extractText(json).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<Pair<String, Int>>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val label = s.optString("step", "").trim()
                if (label.isNotEmpty()) steps.add(Pair(label, s.optInt("minutes", 0)))
            }
            if (steps.isEmpty()) Plan(goal, emptyList(), "The plan came back empty.")
            else Plan(o.optString("title", goal).ifBlank { goal }, steps, null)
        } catch (e: Exception) {
            val lines = text.lines().map { it.trim().trimStart('-', '*', ' ') }.filter { it.length > 3 }.take(10)
            if (lines.isEmpty()) Plan(goal, emptyList(), "Could not read the plan.")
            else Plan(goal, lines.map { Pair(it, 0) }, null)
        }
    }
}
