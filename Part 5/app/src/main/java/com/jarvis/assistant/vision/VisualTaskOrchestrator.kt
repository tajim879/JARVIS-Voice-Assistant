package com.jarvis.assistant.vision

import android.graphics.Rect
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class TaskActionResult(
    val success: Boolean,
    val message: String,
    val currentStateSummary: String = "",
    val requiresUserConfirmation: Boolean = false
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
            if (currentStateSummary.isNotBlank()) {
                put("current_state", currentStateSummary)
            }
            if (requiresUserConfirmation) {
                put("requires_confirmation", true)
            }
        }
    }
}

/**
 * Visual Task Orchestrator.
 * Connects Gemini Live's visual tool calls to the Dual-Anchor Grounding Engine
 * and executes precise, closed-loop verified actions on the Android OS.
 */
class VisualTaskOrchestrator(
    private val groundingEngine: UiGroundingEngine = UiGroundingEngine()
) {
    companion object {
        private const val TAG = "VisualTaskOrchestrator"

        @Volatile
        private var instance: VisualTaskOrchestrator? = null

        fun getInstance(): VisualTaskOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: VisualTaskOrchestrator().also { instance = it }
            }
        }
    }

    private val screenCaptureManager = ScreenCaptureManager.getInstance()
    private var consecutiveScrollCount = 0
    private var lastScreenSignature = ""
    private var duplicateSignatureCount = 0

    private fun resetScrollCount() {
        consecutiveScrollCount = 0
        lastScreenSignature = ""
        duplicateSignatureCount = 0
    }

    private fun dispatchImmediateScreenFrame() {
        try {
            val snapshot = screenCaptureManager.captureSnapshotJpeg(70)
            if (snapshot != null) {
                JarvisConversationService.sendImmediateVideoFrame(snapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed dispatching immediate frame: ${e.message}")
        }
    }

    /**
     * Executes a precise visual click on the requested UI element.
     * Snaps to the exact hardware bounding box Rect from the accessibility tree.
     */
    suspend fun handleClick(
        targetName: String,
        elementId: Int? = null,
        description: String? = null,
        isToggle: Boolean = false
    ): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(
                success = false,
                message = "Accessibility Service is not enabled. Please enable it in Settings."
            )
        }

        // Safety gate check
        if (isSensitiveTarget(targetName, description)) {
            return@withContext TaskActionResult(
                success = false,
                message = "Sensitive action detected ('$targetName'). Please ask the user for explicit confirmation before proceeding.",
                requiresUserConfirmation = true
            )
        }
        // Anti-Clear-Search Guard: prevent AI from reflexively clicking "Clear search" in the middle of a search task
        if (isClearSearchButton(targetName, description)) {
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to click '$targetName'. Do not clear or erase the search input! Instead, click a matching result/folder from the list below, or submit.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        val rootBefore = service.getActiveRootNode()
        val elementsBefore = groundingEngine.scanScreen(rootBefore)

        val target = groundingEngine.findTargetElement(
            elements = elementsBefore,
            targetName = targetName,
            isToggleIntent = isToggle || isTogglePhrase(targetName) || isTogglePhrase(description ?: ""),
            elementId = elementId
        )

        if (target == null) {
            // Self-correction attempt: auto-scroll down to see if target is slightly offscreen
            Log.d(TAG, "Target '$targetName' not visible on current screen. Trying auto-scroll down...")
            val scrolled = service.performSmartScroll("down")
            if (scrolled) {
                delay(600)
                val rootAfterScroll = service.getActiveRootNode()
                val elementsAfterScroll = groundingEngine.scanScreen(rootAfterScroll)
                val targetAfterScroll = groundingEngine.findTargetElement(
                    elements = elementsAfterScroll,
                    targetName = targetName,
                    isToggleIntent = isToggle,
                    elementId = elementId
                )
                if (targetAfterScroll != null) {
                    val clicked = service.performClickAtRect(targetAfterScroll.bounds)
                    delay(500)
                    return@withContext TaskActionResult(
                        success = clicked,
                        message = "Found '$targetName' after scroll and clicked.",
                        currentStateSummary = captureCurrentStateSummary(service)
                    )
                }
            }

            val (pkg, title) = groundingEngine.extractScreenContext(rootBefore, elementsBefore)
            return@withContext TaskActionResult(
                success = false,
                message = "Could not find element '$targetName' on current screen ('$title' in $pkg). Check screen title or navigate to the correct section.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        val initialCheckedState = target.isChecked
        val clickOk = service.performClickAtRect(target.bounds)

        if (!clickOk) {
            return@withContext TaskActionResult(
                success = false,
                message = "Failed to tap coordinates for '${target.primaryLabel}'.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        // Closed-loop verification: wait 450ms for UI animation and verify effect
        delay(450)
        dispatchImmediateScreenFrame()
        val rootAfter = service.getActiveRootNode()
        val elementsAfter = groundingEngine.scanScreen(rootAfter)

        // Verify if toggle state changed
        if (target.isSwitchOrToggle()) {
            val updatedTarget = elementsAfter.firstOrNull { it.bounds == target.bounds || it.id == target.id }
            val nowChecked = updatedTarget?.isChecked
            Log.i(TAG, "Toggle verified: was=$initialCheckedState, now=$nowChecked")
        }

        val summary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
        return@withContext TaskActionResult(
            success = true,
            message = "Successfully clicked '${target.primaryLabel}' at exact screen bounds.",
            currentStateSummary = summary
        )
    }

    /**
     * Types text into a search bar or input field with zero typos.
     */
    suspend fun handleType(
        text: String,
        targetInputName: String? = null,
        submit: Boolean = false
    ): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service is not running.")
        }

        var targetRect: Rect? = null
        if (!targetInputName.isNullOrBlank()) {
            val root = service.getActiveRootNode()
            val elements = groundingEngine.scanScreen(root)
            val inputElement = groundingEngine.findTargetElement(elements, targetInputName)
                ?: elements.firstOrNull { it.isEditable }
            targetRect = inputElement?.bounds
        }

        val success = service.smartTypeText(text, targetRect, submit)
        delay(650)
        dispatchImmediateScreenFrame()

        val rootAfter = service.getActiveRootNode()
        val elementsAfter = groundingEngine.scanScreen(rootAfter)
        return@withContext TaskActionResult(
            success = success,
            message = if (success) {
                "Typed '$text' into input. Results are now displayed below. NEXT ACTION: Call click_ui_element on the matching result from the visible elements list to open it and finish the task! DO NOT call task_completed yet."
            } else {
                "Could not find active input field to type."
            },
            currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
        )
    }

    /**
     * Performs a directional scroll with loop guard.
     */
    suspend fun handleScroll(direction: String, distance: String = "small"): TaskActionResult = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service not running.")
        }

        consecutiveScrollCount++
        if (consecutiveScrollCount > 15) {
            resetScrollCount()
            val root = service.getActiveRootNode()
            val elements = groundingEngine.scanScreen(root)
            val (pkg, title) = groundingEngine.extractScreenContext(root, elements)
            return@withContext TaskActionResult(
                success = false,
                message = "Scrolled 15 times in a row without finding target. You are currently in app '$pkg' on screen '$title'. If the item is not here, try navigating to another section or folder.",
                currentStateSummary = groundingEngine.formatElementsSummary(elements, root)
            )
        }

        val success = service.performSmartScroll(direction, distance)
        delay(600) // allow scroll momentum to settle
        dispatchImmediateScreenFrame()

        val rootAfter = service.getActiveRootNode()
        val elementsAfter = groundingEngine.scanScreen(rootAfter)

        // Only compute signature from dynamic content (elements below fixed header y > 350)
        // Taking the top 6 elements was matching fixed header tabs (All, Camera, etc.) and prematurely stopping scrolls!
        val dynamicElements = elementsAfter.filter { it.bounds.top > 350 }
        val currentSignature = if (dynamicElements.isNotEmpty()) {
            dynamicElements.takeLast(6).joinToString("|") { "${it.id}:${it.primaryLabel}@${it.bounds.top}" }
        } else {
            elementsAfter.takeLast(6).joinToString("|") { "${it.id}:${it.primaryLabel}@${it.bounds.top}" }
        }

        if (currentSignature.isNotBlank() && currentSignature == lastScreenSignature) {
            duplicateSignatureCount++
            if (duplicateSignatureCount >= 2) {
                resetScrollCount()
                val (pkg, title) = groundingEngine.extractScreenContext(rootAfter, elementsAfter)
                return@withContext TaskActionResult(
                    success = false,
                    message = "Reached end of screen in direction '$direction'. Cannot scroll further in this view. Current screen: '$title' in app '$pkg'. If looking for a photo/document, try opening another folder like 'WhatsApp Images', 'Screenshots', or 'Downloads'.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
                )
            }
        } else {
            duplicateSignatureCount = 0
        }
        lastScreenSignature = currentSignature

        return@withContext TaskActionResult(
            success = success,
            message = "Scrolled screen $direction (scroll #$consecutiveScrollCount, distance: $distance).",
            currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
        )
    }

    /**
     * Dispatches system navigation keys (back, home, recents).
     */
    suspend fun handleKey(key: String): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service not running.")
        }

        val success = service.performSystemGesture(key)
        delay(400)
        dispatchImmediateScreenFrame()
        return@withContext TaskActionResult(
            success = success,
            message = "Pressed system key '$key'.",
            currentStateSummary = captureCurrentStateSummary(service)
        )
    }

    /**
     * Inspects the current screen state in real time and returns a detailed summary
     * of the active foreground app, screen title, and visible content/elements.
     */
    suspend fun inspectScreen(waitForContent: Boolean = false): String = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
            ?: return@withContext "Screen context unavailable: Accessibility Service is not active."
        var root = service.getActiveRootNode()
        var elements = if (root != null) groundingEngine.scanScreen(root) else emptyList()

        if (waitForContent) {
            var attempts = 0
            while (attempts < 4 && elements.none { it.text.isNotBlank() || it.contentDescription.isNotBlank() }) {
                delay(250)
                root = service.getActiveRootNode()
                elements = if (root != null) groundingEngine.scanScreen(root) else emptyList()
                attempts++
            }
        }

        if (root == null && elements.isEmpty()) {
            return@withContext "Screen context unavailable: Screen is blank or inaccessible."
        }

        dispatchImmediateScreenFrame()
        val summary = groundingEngine.formatElementsSummary(elements, root)
        Log.i(TAG, "Screen inspection generated:\n$summary")
        return@withContext summary
    }

    private fun captureCurrentStateSummary(service: JarvisAccessibilityService): String {
        val root = service.getActiveRootNode() ?: return "Screen empty or not accessible."
        val elements = groundingEngine.scanScreen(root)
        return groundingEngine.formatElementsSummary(elements, root)
    }

    private fun isTogglePhrase(phrase: String): Boolean {
        val lower = phrase.lowercase(Locale.ROOT)
        return lower.contains("switch") || lower.contains("toggle") ||
                lower.contains("enable") || lower.contains("disable") ||
                lower.contains("turn on") || lower.contains("turn off")
    }

    private fun isSensitiveTarget(target: String, desc: String?): Boolean {
        val combined = "$target ${desc ?: ""}".lowercase(Locale.ROOT)
        return combined.contains("pay") || combined.contains("payment") ||
                combined.contains("delete all") || combined.contains("factory reset") ||
                combined.contains("format") || combined.contains("purchase") ||
                combined.contains("transfer money") || combined.contains("send money")
    }

    private fun isClearSearchButton(target: String, desc: String?): Boolean {
        val combined = "$target ${desc ?: ""}".lowercase(Locale.ROOT)
        return combined.contains("clear search") || combined.contains("clear query") ||
                combined.contains("clear text") || combined.contains("clear input")
    }
}
