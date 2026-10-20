package com.jarvis.assistant.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.ui.chat.ChatHistoryBottomSheet
import com.jarvis.assistant.ui.orb.OrbHelper
import com.jarvis.assistant.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var orbHelper: OrbHelper

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.toggleSession()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupOrb()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
    }

    private fun setupOrb() {
        orbHelper = OrbHelper(binding.webViewOrb)
        orbHelper.setup()
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.btnPowerSession.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.toggleSession()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnMicMute.setOnClickListener {
            viewModel.toggleMicMute()
        }

        binding.tvViewHistory.setOnClickListener {
            showChatHistory()
        }

        binding.boxJarvisTitle.setOnClickListener {
            showChatHistory()
        }
    }

    private fun showChatHistory() {
        ChatHistoryBottomSheet.newInstance().show(
            supportFragmentManager,
            ChatHistoryBottomSheet.TAG
        )
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.liveTime.collect { time ->
                        binding.tvLiveTime.text = time
                    }
                }

                launch {
                    viewModel.personalityName.collect { personality ->
                        binding.tvPersonalityMode.text = personality
                    }
                }

                launch {
                    viewModel.isSessionOn.collect { isOn ->
                        updatePowerButtonUi(isOn)
                    }
                }

                launch {
                    viewModel.isMicMuted.collect { isMuted ->
                        updateMicMuteUi(isMuted)
                    }
                }

                launch {
                    viewModel.conversationState.collect { state ->
                        updateConversationStateUi(state)
                    }
                }

                launch {
                    viewModel.audioLevel.collect { level ->
                        orbHelper.updateAudioLevel(level)
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { status ->
                        updateConnectionStatusUi(status)
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updatePowerButtonUi(isOn: Boolean) {
        if (isOn) {
            binding.btnPowerSession.setBackgroundResource(R.drawable.bg_power_button)
            binding.ivPowerIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary_cyan)
            )
            binding.tvPowerText.text = getString(R.string.btn_on)
            binding.tvPowerText.setTextColor(
                ContextCompat.getColor(this, R.color.primary_cyan)
            )
        } else {
            binding.btnPowerSession.setBackgroundResource(R.drawable.bg_power_button_off)
            binding.ivPowerIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.text_muted)
            )
            binding.tvPowerText.text = getString(R.string.btn_off)
            binding.tvPowerText.setTextColor(
                ContextCompat.getColor(this, R.color.text_muted)
            )
        }
    }

    private fun updateMicMuteUi(isMuted: Boolean) {
        if (isMuted) {
            binding.btnMicMute.setImageResource(R.drawable.ic_mic_off)
            binding.btnMicMute.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
        } else {
            binding.btnMicMute.setImageResource(R.drawable.ic_mic)
            binding.btnMicMute.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary_cyan)
            )
        }
    }

    private fun updateConversationStateUi(state: ConversationState) {
        binding.tvConversationState.text = state.displayName
        val colorRes = when (state) {
            ConversationState.IDLE -> R.color.text_secondary
            ConversationState.LISTENING -> R.color.primary_cyan
            ConversationState.THINKING -> R.color.purple_glow
            ConversationState.SPEAKING -> R.color.neon_blue
        }
        binding.tvConversationState.setTextColor(ContextCompat.getColor(this, colorRes))
        orbHelper.updateState(state)
    }

    private fun updateConnectionStatusUi(status: String) {
        binding.tvConnectionStatus.text = status
        val dotColor = when (status) {
            "LIVE" -> ContextCompat.getColor(this, R.color.status_green)
            "CONNECTING...", "RECONNECTING..." -> ContextCompat.getColor(this, R.color.status_amber)
            else -> ContextCompat.getColor(this, R.color.text_muted)
        }
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
    }
}
