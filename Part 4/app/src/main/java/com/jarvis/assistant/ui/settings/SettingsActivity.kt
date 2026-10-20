package com.jarvis.assistant.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivitySettingsBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val youTubePrefs by lazy { JarvisApp.instance.youTubePreferences }
    private val youTubeManager by lazy { JarvisApp.instance.youTubeAutomationManager }
    private val memoryRepo by lazy { JarvisApp.instance.memoryRepository }

    private var isWebPageLoaded = false

    private val requestMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsInWeb()
    }

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsInWeb()
    }

    private val requestContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsInWeb()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        setupWebView()
    }

    override fun onResume() {
        super.onResume()
        if (isWebPageLoaded) {
            updatePermissionsInWeb()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webViewSettings
        webView.setBackgroundColor(Color.parseColor("#04070a"))
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isWebPageLoaded = true
                val initialData = getInitialSettingsJson()
                val escaped = initialData.replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript("if(window.initSettings) { window.initSettings('$escaped'); }", null)
            }
        }

        webView.addJavascriptInterface(SettingsWebBridge(), "JarvisSettingsBridge")
        webView.loadUrl("file:///android_asset/settings/settings.html")
    }

    private fun getPermissionsJsonObject(): JSONObject {
        return JSONObject().apply {
            put("mic", PermissionHelper.hasRecordAudioPermission(this@SettingsActivity))
            put("notification", PermissionHelper.hasNotificationPermission(this@SettingsActivity))
            put("battery", PermissionHelper.hasBatteryOptimizationPermission(this@SettingsActivity))
            put("contacts", PermissionHelper.hasContactsPermission(this@SettingsActivity))
            put("settings", PermissionHelper.hasWriteSettingsPermission(this@SettingsActivity))
            put("accessibility", PermissionHelper.isAccessibilityServiceEnabled(this@SettingsActivity))
        }
    }

    private fun getInitialSettingsJson(): String {
        val root = JSONObject().apply {
            put("apiKey", preferences.apiKey)
            put("model", preferences.aiModel)
            put("voice", preferences.voice)
            put("personality", preferences.personality)
            put("userName", preferences.userName)
            put("youtubeEnabled", youTubePrefs.isYouTubeEnabled)
            put("youtubeApiKey", youTubePrefs.youtubeApiKey)
            put("permissions", getPermissionsJsonObject())
        }
        return root.toString()
    }

    private fun updatePermissionsInWeb() {
        val perms = getPermissionsJsonObject().toString()
        val escaped = perms.replace("\\", "\\\\").replace("'", "\\'")
        binding.webViewSettings.post {
            binding.webViewSettings.evaluateJavascript("if(window.updatePermissions) { window.updatePermissions('$escaped'); }", null)
        }
    }

    private fun handleRequestPermission(type: String) {
        when (type.lowercase()) {
            "mic" -> {
                if (PermissionHelper.hasRecordAudioPermission(this)) {
                    Toast.makeText(this, "Microphone access is already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            "notification", "notifications" -> {
                if (PermissionHelper.hasNotificationPermission(this)) {
                    Toast.makeText(this, "Notification service is already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Toast.makeText(this, "Notifications are automatically granted on this device", Toast.LENGTH_SHORT).show()
                        updatePermissionsInWeb()
                    }
                }
            }
            "battery" -> {
                if (PermissionHelper.hasBatteryOptimizationPermission(this)) {
                    Toast.makeText(this, "Unrestricted battery access is already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    PermissionHelper.requestBatteryOptimization(this)
                }
            }
            "contacts" -> {
                if (PermissionHelper.hasContactsPermission(this)) {
                    Toast.makeText(this, "Contacts access is already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    requestContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            "settings", "writesettings" -> {
                if (PermissionHelper.hasWriteSettingsPermission(this)) {
                    Toast.makeText(this, "System Settings permission is already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    PermissionHelper.requestWriteSettings(this)
                }
            }
            "accessibility" -> {
                if (PermissionHelper.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(this, "Accessibility automation is active", Toast.LENGTH_SHORT).show()
                } else {
                    PermissionHelper.openAccessibilitySettings(this)
                }
            }
        }
    }

    private fun handleRequestAllPermissions() {
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (!PermissionHelper.hasContactsPermission(this)) {
            requestContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            PermissionHelper.requestBatteryOptimization(this)
            return
        }
        if (!PermissionHelper.hasWriteSettingsPermission(this)) {
            PermissionHelper.requestWriteSettings(this)
            return
        }
        if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        Toast.makeText(this, "All permissions are already granted", Toast.LENGTH_SHORT).show()
        updatePermissionsInWeb()
    }

    private fun getClipboardText(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        return if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString()?.trim() ?: ""
        } else {
            ""
        }
    }

    private fun showClearMemoriesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All Memories")
            .setMessage("Are you sure you want to permanently erase all facts, routines, and preferences from JARVIS?")
            .setPositiveButton("CLEAR ALL") { _, _ ->
                lifecycleScope.launch {
                    memoryRepo.clearAll()
                    Toast.makeText(this@SettingsActivity, "Memory database cleared", Toast.LENGTH_SHORT).show()
                    binding.webViewSettings.evaluateJavascript("if(window.showToast){ window.showToast('Memory database cleared'); }", null)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    inner class SettingsWebBridge {

        @JavascriptInterface
        fun getInitialSettings(): String {
            return getInitialSettingsJson()
        }

        @JavascriptInterface
        fun onBackClicked() {
            runOnUiThread {
                finish()
            }
        }

        @JavascriptInterface
        fun pasteApiKey() {
            runOnUiThread {
                val clipText = getClipboardText()
                if (clipText.isNotEmpty()) {
                    val escaped = clipText.replace("\\", "\\\\").replace("'", "\\'")
                    binding.webViewSettings.evaluateJavascript("setApiKey('$escaped')", null)
                } else {
                    Toast.makeText(this@SettingsActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun pasteYouTubeKey() {
            runOnUiThread {
                val clipText = getClipboardText()
                if (clipText.isNotEmpty()) {
                    val escaped = clipText.replace("\\", "\\\\").replace("'", "\\'")
                    binding.webViewSettings.evaluateJavascript("setYouTubeKey('$escaped')", null)
                } else {
                    Toast.makeText(this@SettingsActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun requestPermission(type: String) {
            runOnUiThread {
                handleRequestPermission(type)
            }
        }

        @JavascriptInterface
        fun requestAllPermissions() {
            runOnUiThread {
                handleRequestAllPermissions()
            }
        }

        @JavascriptInterface
        fun clearMemories() {
            runOnUiThread {
                showClearMemoriesDialog()
            }
        }

        @JavascriptInterface
        fun viewMemories() {
            runOnUiThread {
                val intent = Intent(this@SettingsActivity, MemoryActivity::class.java)
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun validateYouTubeKey(key: String) {
            lifecycleScope.launch {
                val (isValid, msg) = youTubeManager.validateApiKey(key)
                if (isValid) {
                    youTubePrefs.youtubeApiKey = key
                }
                val escapedMsg = msg.replace("\\", "\\\\").replace("'", "\\'")
                binding.webViewSettings.post {
                    binding.webViewSettings.evaluateJavascript(
                        "onYouTubeKeyValidated($isValid, '$escapedMsg')",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun saveSettings(payloadJson: String) {
            try {
                val json = JSONObject(payloadJson)
                val apiKey = json.optString("apiKey", "").trim()
                val model = json.optString("model", GeminiConstants.DEFAULT_MODEL)
                val voice = json.optString("voice", GeminiConstants.DEFAULT_VOICE)
                val personality = json.optString("personality", GeminiConstants.PERSONALITY_ASSISTANT)
                val userName = json.optString("userName", "").trim()
                val youtubeEnabled = json.optBoolean("youtubeEnabled", true)
                val youtubeApiKey = json.optString("youtubeApiKey", "").trim()

                preferences.apiKey = apiKey
                preferences.aiModel = model
                preferences.voice = voice
                preferences.personality = personality
                preferences.userName = userName

                youTubePrefs.isYouTubeEnabled = youtubeEnabled
                youTubePrefs.youtubeApiKey = youtubeApiKey

                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Failed to save settings: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
