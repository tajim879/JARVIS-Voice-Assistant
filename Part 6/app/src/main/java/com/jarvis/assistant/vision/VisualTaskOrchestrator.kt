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

    private suspend fun dispatchImmediateScreenFrame() {
        try {
            val snapshot = JarvisAccessibilityService.captureScreenJpeg(75)
                ?: screenCaptureManager.captureSnapshotJpeg(70)
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

        // Anti-Search-Loop on Web Search Pages: prevent model from re-tapping search bar when results are visible
        if (isBrowserSearchScreen(service) && (targetName.equals("search", ignoreCase = true) || targetName.contains("search bar", ignoreCase = true) || targetName.contains("url_bar", ignoreCase = true))) {
            val root = service.getActiveRootNode()
            val elements = groundingEngine.scanScreen(root)
            val resultLinks = elements.filter { it.isClickable && !it.isEditable && it.bounds.top > 250 && it.text.isNotBlank() }
            if (resultLinks.isNotEmpty()) {
                val bestLink = resultLinks.first()
                Log.i(TAG, "Re-routing search bar click on search results page to top result link: '${bestLink.primaryLabel}'")
                val clicked = service.performClickAtRect(bestLink.bounds)
                delay(1200)
                dispatchImmediateScreenFrame()
                val rootAfter = service.getActiveRootNode()
                val elementsAfter = groundingEngine.scanScreen(rootAfter)
                return@withContext TaskActionResult(
                    success = clicked,
                    message = "Navigating to website: automatically clicked search result link '${bestLink.primaryLabel}'. Wait for page to load and locate generation prompt input.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
                )
            }
        }

        val rootBefore = service.getActiveRootNode()
        val elementsBefore = groundingEngine.scanScreen(rootBefore)

        val initialTarget = groundingEngine.findTargetElement(
            elements = elementsBefore,
            targetName = targetName,
            isToggleIntent = isToggle || isTogglePhrase(targetName) || isTogglePhrase(description ?: ""),
            elementId = elementId
        )

        // Search-Result Loop Guard:
        // If on a browser search page and the target resolved was an editable search bar,
        // NEVER re-tap the search bar when search result links are visible below!
        val target = if (isBrowserSearchScreen(service) && (initialTarget?.isEditable == true || initialTarget?.viewId?.contains("search", ignoreCase = true) == true || initialTarget?.viewId?.contains("url_bar", ignoreCase = true) == true) && elementId == null) {
            val resultLinks = elementsBefore.filter { !it.isEditable && it.bounds.top > 250 && (it.isClickable || it.bounds.top > 280) && (it.text.isNotBlank() || it.contentDescription.isNotBlank()) }
            if (resultLinks.isNotEmpty()) {
                val qTokens = targetName.lowercase(Locale.ROOT).split("\\s+".toRegex()).filter { it.length > 2 }
                val bestResult = resultLinks.filter { el ->
                    qTokens.any { t -> el.primaryLabel.lowercase(Locale.ROOT).contains(t) }
                }.firstOrNull() ?: resultLinks.first()
                Log.i(TAG, "Prevented search bar re-tap: re-routing target from search bar to search result '${bestResult.primaryLabel}' (id=${bestResult.id})")
                bestResult
            } else {
                initialTarget
            }
        } else {
            initialTarget
        }

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

        val rootPkg = rootBefore?.packageName?.toString() ?: ""
        val isBrowserApp = rootPkg.contains("chrome", ignoreCase = true) || rootPkg.contains("browser", ignoreCase = true)
        val isExplicitMenuRequest = targetName.lowercase(Locale.ROOT).contains("menu") || (description ?: "").lowercase(Locale.ROOT).contains("menu")

        if (isBrowserApp && !isExplicitMenuRequest && isBrowserOverflowMenu(target)) {
            Log.w(TAG, "Blocked clicking browser overflow menu ('More options' / 3 dots). Target: ${target.primaryLabel}")
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to click Chrome's browser menu ('More options' / three dots). Please interact directly with the website content, search results, or prompt input instead of the browser menu.",
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

        // Closed-loop verification: wait for UI transition (adaptive delay for browser/network links)
        val isWebOrLink = target.isClickable && (target.primaryLabel.contains("http", ignoreCase = true) ||
                target.primaryLabel.contains(".com", ignoreCase = true) ||
                target.primaryLabel.contains("www.", ignoreCase = true) ||
                target.primaryLabel.contains("search", ignoreCase = true) ||
                target.primaryLabel.contains("flow", ignoreCase = true))
        val waitTime = if (isWebOrLink) 1100L else 500L
        delay(waitTime)
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

        // Autonomous Web Navigation & Creative Execution:
        // If we are genuinely on Google Search results and a creative prompt is passed,
        // automatically click the top search result link, wait for website to load, and report true progress.
        if (isBrowserSearchScreen(service)) {
            val isPromptLike = text.length > 20 || listOf("anime", "video", "create", "generate", "prompt", "girl", "boy", "forest", "pink", "cinematic", "make").any {
                text.contains(it, ignoreCase = true)
            }
            if (isPromptLike) {
                Log.i(TAG, "On search results with creation prompt. Automatically entering website...")
                val root = service.getAppRootNode() ?: service.getActiveRootNode()
                val elements = if (root != null) groundingEngine.scanScreen(root) else emptyList()
                val resultLinks = elements.filter { it.isClickable && !it.isEditable && it.bounds.top > 250 && it.text.isNotBlank() }
                if (resultLinks.isNotEmpty()) {
                    val topLink = resultLinks.first()
                    service.performClickAtRect(topLink.bounds)
                    delay(2500)
                    dispatchImmediateScreenFrame()
                    val rootAfterNav = service.getAppRootNode() ?: service.getActiveRootNode()
                    val elementsAfterNav = if (rootAfterNav != null) groundingEngine.scanScreen(rootAfterNav) else emptyList()
                    return@withContext TaskActionResult(
                        success = true,
                        message = "Navigated into website '${topLink.primaryLabel}'. The destination platform is now loaded on screen. NEXT ACTION: Call type_text to type your prompt into the creation box, then click the Generate button.",
                        currentStateSummary = groundingEngine.formatElementsSummary(elementsAfterNav, rootAfterNav)
                    )
                }
            }
        }

        // Resolve target input element
        var targetRect: Rect? = null
        val root = service.getAppRootNode() ?: service.getActiveRootNode()
        val elements = if (root != null) groundingEngine.scanScreen(root) else emptyList()

        if (!targetInputName.isNullOrBlank()) {
            val inputElement = groundingEngine.findTargetElement(elements, targetInputName)
                ?: elements.firstOrNull { (it.isEditable || it.className.contains("EditText", ignoreCase = true)) && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }
                ?: elements.firstOrNull { it.isEditable }
            targetRect = inputElement?.bounds
        } else {
            // Pick the best candidate input field (prefer non-URL-bar inputs when on web pages)
            val nonUrlInputs = elements.filter { (it.isEditable || it.className.contains("EditText", ignoreCase = true)) && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }
            val inputElement = nonUrlInputs.firstOrNull() ?: elements.firstOrNull { it.isEditable }
            targetRect = inputElement?.bounds
        }

        val success = service.smartTypeText(text, targetRect, submit)
        delay(650)
        dispatchImmediateScreenFrame()

        val rootAfter = service.getAppRootNode() ?: service.getActiveRootNode()
        val elementsAfter = if (rootAfter != null) groundingEngine.scanScreen(rootAfter) else emptyList()

        val isSearch = isBrowserSearchScreen(service)
        val responseMessage = if (success) {
            if (isSearch) {
                "Typed '$text' into search bar. Search results are now displayed below. NEXT ACTION: Call click_ui_element on the matching search result link to open it."
            } else {
                "Typed '$text' into input field. The text is now entered on screen. NEXT ACTION: Look at visible elements and tap the Generate, Create, or Submit button to start processing."
            }
        } else {
            "Could not type into input field. Please tap the text field first using click_ui_element, then call type_text."
        }

        return@withContext TaskActionResult(
            success = success,
            message = responseMessage,
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

    private fun isBrowserSearchScreen(service: JarvisAccessibilityService): Boolean {
        val root = service.getAppRootNode() ?: service.getActiveRootNode() ?: return false
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("chrome", ignoreCase = true) && !pkg.contains("browser", ignoreCase = true)) {
            return false
        }
        val elements = groundingEngine.scanScreen(root)
        val urlText = elements.firstOrNull { it.viewId.contains("url_bar", ignoreCase = true) }?.text?.lowercase(Locale.ROOT) ?: ""

        // If the URL is explicitly an AI creation platform or destination website, it is NEVER a search results screen!
        if (urlText.contains("flow.google") || urlText.contains("labs.google") || urlText.contains("canva") || urlText.contains("runway")) {
            return false
        }

        val textDump = elements.joinToString(" ") { "${it.text.lowercase(Locale.ROOT)} ${it.contentDescription.lowercase(Locale.ROOT)}" }
        val isSearchUrl = urlText.contains("/search") || urlText.contains("search?") || urlText.contains("&q=")
        return isSearchUrl ||
               textDump.contains("google.com/search") ||
               (textDump.contains("search results") && !textDump.contains("prompt") && !textDump.contains("create") && !textDump.contains("generate"))
    }

    private fun isBrowserOverflowMenu(target: UiElementInfo?): Boolean {
        if (target == null) return false
        val text = target.primaryLabel.lowercase(Locale.ROOT)
        val vid = target.viewId.lowercase(Locale.ROOT)
        val desc = target.contentDescription.lowercase(Locale.ROOT)
        return desc.contains("more options") ||
               desc.contains("customize and control") ||
               text.contains("more options") ||
               vid.contains("menu_button") ||
               vid.contains("more_menu") ||
               vid.contains("overflow")
    }
}
