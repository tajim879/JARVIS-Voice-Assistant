package com.jarvis.assistant.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        populateDropdowns()
        loadCurrentSettings()
        setupListeners()
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
    }

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val userName = binding.etUserName.text?.toString()?.trim() ?: ""

        val selectedModel = binding.spinnerModel.selectedItem as? String
            ?: GeminiConstants.DEFAULT_MODEL
        val selectedVoice = binding.spinnerVoice.selectedItem as? String
            ?: "Aoede"
        val selectedPersonality = binding.spinnerPersonality.selectedItem as? String
            ?: GeminiConstants.PERSONALITY_ASSISTANT

        preferences.apiKey = apiKey
        preferences.userName = userName
        preferences.aiModel = selectedModel
        preferences.voice = selectedVoice
        preferences.personality = selectedPersonality

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}
