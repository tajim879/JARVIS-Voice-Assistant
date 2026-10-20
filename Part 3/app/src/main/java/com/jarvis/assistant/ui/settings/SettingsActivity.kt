package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivitySettingsBinding
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val requestMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsUi()
    }

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsUi()
    }

    private val requestContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsUi()
    }

    private val youTubePrefs by lazy { JarvisApp.instance.youTubePreferences }
    private val youTubeManager by lazy { JarvisApp.instance.youTubeAutomationManager }
    private val memoryRepo by lazy { JarvisApp.instance.memoryRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        populateDropdowns()
        loadCurrentSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsUi()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun populateDropdowns() {
        // 1. AI Models
        val modelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            GeminiConstants.SUPPORTED_MODELS
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerModel.adapter = modelAdapter

        // 2. Voices
        val voiceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            GeminiConstants.SUPPORTED_VOICES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerVoice.adapter = voiceAdapter

        // 3. Personalities
        val personalityAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            GeminiConstants.SUPPORTED_PERSONALITIES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerPersonality.adapter = personalityAdapter

        binding.spinnerPersonality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = GeminiConstants.SUPPORTED_PERSONALITIES[position]
                binding.tvPersonalityDesc.text = when (selected) {
                    GeminiConstants.PERSONALITY_GIRLFRIEND ->
                        "Warm Hinglish • Emotional but natural • Short spoken replies"
                    GeminiConstants.PERSONALITY_PROFESSIONAL ->
                        "Formal English • Precise • No emojis"
                    else ->
                        "Friendly • Helpful • Hinglish or English"
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadCurrentSettings() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etUserName.setText(preferences.userName)

        val currentModel = preferences.aiModel
        val modelIndex = GeminiConstants.SUPPORTED_MODELS.indexOf(currentModel)
        if (modelIndex >= 0) {
            binding.spinnerModel.setSelection(modelIndex)
        }

        val currentVoice = preferences.voice
        val voiceIndex = GeminiConstants.SUPPORTED_VOICES.indexOf(currentVoice)
        if (voiceIndex >= 0) {
            binding.spinnerVoice.setSelection(voiceIndex)
        }

        val currentPersonality = preferences.personality
        val personalityIndex = GeminiConstants.SUPPORTED_PERSONALITIES.indexOf(currentPersonality)
        if (personalityIndex >= 0) {
            binding.spinnerPersonality.setSelection(personalityIndex)
        }

        // YouTube Settings
        binding.switchYouTubeEnabled.isChecked = youTubePrefs.isYouTubeEnabled
        binding.etYouTubeApiKey.setText(youTubePrefs.youtubeApiKey)
    }

    private fun setupListeners() {
        binding.btnPasteApiKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasteData = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasteData.isNotEmpty()) {
                    binding.etApiKey.setText(pasteData)
                    Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPermMic.setOnClickListener {
            if (PermissionHelper.hasRecordAudioPermission(this)) {
                Toast.makeText(this, "Microphone access is already allowed", Toast.LENGTH_SHORT).show()
            } else {
                requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnPermNotification.setOnClickListener {
            if (PermissionHelper.hasNotificationPermission(this)) {
                Toast.makeText(this, "Notification service is already allowed", Toast.LENGTH_SHORT).show()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Toast.makeText(this, "Notifications are automatically granted on this device", Toast.LENGTH_SHORT).show()
                    updatePermissionsUi()
                }
            }
        }

        binding.btnPermBattery.setOnClickListener {
            if (PermissionHelper.hasBatteryOptimizationPermission(this)) {
                Toast.makeText(this, "Unrestricted battery access is already allowed", Toast.LENGTH_SHORT).show()
            } else {
                PermissionHelper.requestBatteryOptimization(this)
            }
        }

        binding.btnPermContacts.setOnClickListener {
            if (PermissionHelper.hasContactsPermission(this)) {
                Toast.makeText(this, "Contacts access is already allowed", Toast.LENGTH_SHORT).show()
            } else {
                requestContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        binding.btnPermWriteSettings.setOnClickListener {
            if (PermissionHelper.hasWriteSettingsPermission(this)) {
                Toast.makeText(this, "System Settings permission is already allowed", Toast.LENGTH_SHORT).show()
            } else {
                PermissionHelper.requestWriteSettings(this)
            }
        }

        binding.btnPermAccessibility.setOnClickListener {
            if (PermissionHelper.isAccessibilityServiceEnabled(this)) {
                Toast.makeText(this, "Accessibility automation is active", Toast.LENGTH_SHORT).show()
            } else {
                PermissionHelper.openAccessibilitySettings(this)
            }
        }

        // Memory Manager Listeners
        binding.btnManageMemories.setOnClickListener {
            val intent = android.content.Intent(this, com.jarvis.assistant.ui.memory.MemoryActivity::class.java)
            startActivity(intent)
        }

        binding.btnClearMemories.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Clear All Memories")
                .setMessage("Are you sure you want to permanently erase all facts, routines, and preferences from JARVIS?")
                .setPositiveButton("CLEAR ALL") { _, _ ->
                    lifecycleScope.launch {
                        memoryRepo.clearAll()
                        Toast.makeText(this@SettingsActivity, "Memory database cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        // YouTube Listeners
        binding.btnPasteYouTubeKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasteData = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasteData.isNotEmpty()) {
                    binding.etYouTubeApiKey.setText(pasteData)
                    Toast.makeText(this, "YouTube API Key pasted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnValidateYouTubeKey.setOnClickListener {
            val key = binding.etYouTubeApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvYouTubeKeyStatus.text = "Status: Key is empty"
                binding.tvYouTubeKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                return@setOnClickListener
            }

            binding.tvYouTubeKeyStatus.text = "Status: Validating key with Google..."
            binding.tvYouTubeKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))

            lifecycleScope.launch {
                val (isValid, msg) = youTubeManager.validateApiKey(key)
                if (isValid) {
                    binding.tvYouTubeKeyStatus.text = "Status: ✓ $msg"
                    binding.tvYouTubeKeyStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_green))
                    youTubePrefs.youtubeApiKey = key
                } else {
                    binding.tvYouTubeKeyStatus.text = "Status: ⚠ $msg"
                    binding.tvYouTubeKeyStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_red))
                }
            }
        }
    }

    private fun updatePermissionsUi() {
        // 1. Microphone
        val hasMic = PermissionHelper.hasRecordAudioPermission(this)
        updateBadge(binding.btnPermMic, hasMic)

        // 2. Notifications
        val hasNotification = PermissionHelper.hasNotificationPermission(this)
        updateBadge(binding.btnPermNotification, hasNotification)

        // 3. Unrestricted Battery
        val hasBattery = PermissionHelper.hasBatteryOptimizationPermission(this)
        updateBadge(binding.btnPermBattery, hasBattery)

        // 4. Contacts
        val hasContacts = PermissionHelper.hasContactsPermission(this)
        updateBadge(binding.btnPermContacts, hasContacts)

        // 5. System Settings
        val hasWriteSettings = PermissionHelper.hasWriteSettingsPermission(this)
        updateBadge(binding.btnPermWriteSettings, hasWriteSettings)

        // 6. Accessibility
        val hasAccessibility = PermissionHelper.isAccessibilityServiceEnabled(this)
        if (hasAccessibility) {
            binding.btnPermAccessibility.text = "ENABLED"
            binding.btnPermAccessibility.setBackgroundResource(R.drawable.bg_badge_granted)
            binding.btnPermAccessibility.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.btnPermAccessibility.text = "ENABLE"
            binding.btnPermAccessibility.setBackgroundResource(R.drawable.bg_badge_action)
            binding.btnPermAccessibility.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
        }

        // Summary Footnote
        val allGranted = hasMic && hasNotification && hasBattery && hasContacts
        if (allGranted) {
            binding.tvPermissionsStatus.text = "✓ Core permissions allowed. JARVIS is fully operational."
            binding.tvPermissionsStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            val missing = mutableListOf<String>()
            if (!hasMic) missing.add("Mic")
            if (!hasNotification) missing.add("Notifications")
            if (!hasBattery) missing.add("Battery")
            if (!hasContacts) missing.add("Contacts")
            binding.tvPermissionsStatus.text = "⚠ Recommended: allow ${missing.joinToString(", ")} for full functionality."
            binding.tvPermissionsStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
        }
    }

    private fun updateBadge(button: android.widget.TextView, isGranted: Boolean) {
        if (isGranted) {
            button.text = "ALLOWED"
            button.setBackgroundResource(R.drawable.bg_badge_granted)
            button.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            button.text = "ALLOW"
            button.setBackgroundResource(R.drawable.bg_badge_action)
            button.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
        }
    }

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val userName = binding.etUserName.text?.toString()?.trim() ?: ""

        val selectedModel = binding.spinnerModel.selectedItem as? String
            ?: GeminiConstants.DEFAULT_MODEL
        val selectedVoice = binding.spinnerVoice.selectedItem as? String
            ?: GeminiConstants.DEFAULT_VOICE
        val selectedPersonality = binding.spinnerPersonality.selectedItem as? String
            ?: GeminiConstants.PERSONALITY_ASSISTANT

        preferences.apiKey = apiKey
        preferences.userName = userName
        preferences.aiModel = selectedModel
        preferences.voice = selectedVoice
        preferences.personality = selectedPersonality

        // Save YouTube Settings
        youTubePrefs.isYouTubeEnabled = binding.switchYouTubeEnabled.isChecked
        youTubePrefs.youtubeApiKey = binding.etYouTubeApiKey.text?.toString()?.trim() ?: ""

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}

