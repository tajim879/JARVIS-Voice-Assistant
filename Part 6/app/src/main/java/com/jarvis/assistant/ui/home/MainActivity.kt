package com.jarvis.assistant.ui.home

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.ui.chat.ChatHistoryBottomSheet
import com.jarvis.assistant.ui.orb.OrbHelper
import com.jarvis.assistant.ui.settings.SettingsActivity
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var orbHelper: OrbHelper

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required to run in the background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                // Elevate foreground service to include MEDIA_PROJECTION (Android 14/15 requirement)
                JarvisConversationService.elevateToMediaProjection()

                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                val metrics = resources.displayMetrics
                JarvisConversationService.startScreenCapture(projection, metrics)
                Toast.makeText(this, "JARVIS Screen Vision Active", Toast.LENGTH_SHORT).show()
                orbHelper.updateScreenShareState(true)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting MediaProjection: ${e.message}", e)
                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_LONG).show()
                orbHelper.updateScreenShareState(false)
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            orbHelper.updateScreenShareState(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupOrb()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
    }

    private fun setupOrb() {
        orbHelper = OrbHelper(binding.webViewOrb, object : OrbHelper.OrbListener {
            override fun onOrbClicked() {
                handleMicOrOrbClick()
            }

            override fun onMicClicked() {
                handleMicOrOrbClick()
            }

            override fun onPowerClicked() {
                handlePowerClick()
            }

            override fun onSettingsClicked() {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            override fun onHistoryClicked() {
                showChatHistory()
            }

            override fun onScreenShareClicked() {
                handleScreenShareToggle()
            }
        })
        orbHelper.setup()
    }

    private var lastScreenShareToggleTime = 0L

    private fun handleScreenShareToggle() {
        val now = System.currentTimeMillis()
        if (now - lastScreenShareToggleTime < 800L) return
        lastScreenShareToggleTime = now

        if (JarvisConversationService.isScreenSharing.value) {
            JarvisConversationService.stopScreenCapture()
            orbHelper.updateScreenShareState(false)
            Toast.makeText(this, "Screen Vision Disabled", Toast.LENGTH_SHORT).show()
        } else {
            if (!JarvisConversationService.isSessionOn.value) {
                checkPermissionsAndStart()
            }
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun handlePowerClick() {
        if (JarvisConversationService.isSessionOn.value) {
            viewModel.toggleSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    private fun handleMicOrOrbClick() {
        if (!JarvisConversationService.isSessionOn.value) {
            checkPermissionsAndStart()
        } else if (JarvisConversationService.conversationState.value == com.jarvis.assistant.data.model.ConversationState.SPEAKING) {
            JarvisConversationService.interruptPlayback()
        } else {
            viewModel.toggleMicMute()
        }
    }

    private fun showChatHistory() {
        ChatHistoryBottomSheet.newInstance().show(
            supportFragmentManager,
            ChatHistoryBottomSheet.TAG
        )
    }

    private fun checkPermissionsAndStart() {
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            showBatteryPermissionDialog()
            return
        }

        viewModel.toggleSession()
    }

    private fun showBatteryPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unrestricted Battery Required")
            .setMessage("Jarvis requires Unrestricted Battery access so it can stay active in the background and respond to wake words for 5+ hours.\n\nPlease allow it or open Settings.")
            .setPositiveButton("ALLOW") { _, _ ->
                PermissionHelper.requestBatteryOptimization(this)
            }
            .setNeutralButton("OPEN SETTINGS") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.liveTime.collect { time ->
                        orbHelper.updateLiveTime(time)
                    }
                }

                launch {
                    viewModel.isSessionOn.collect { isOn ->
                        orbHelper.updatePowerState(isOn)
                    }
                }

                launch {
                    viewModel.isMicMuted.collect { isMuted ->
                        orbHelper.updateMicMuted(isMuted)
                    }
                }

                launch {
                    JarvisConversationService.isScreenSharing.collect { isSharing ->
                        orbHelper.updateScreenShareState(isSharing)
                    }
                }

                launch {
                    viewModel.conversationState.collect { state ->
                        orbHelper.updateState(state)
                    }
                }

                launch {
                    viewModel.audioLevel.collect { level ->
                        orbHelper.updateAudioLevel(level)
                    }
                }

                launch {
                    viewModel.audioFrequency.collect { freq ->
                        orbHelper.updateAudioFrequency(freq.amplitude, freq.pitchHz, freq.bass, freq.mid, freq.high)
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { status ->
                        orbHelper.updateConnectionStatus(status)
                    }
                }

                launch {
                    viewModel.chatTurns.collect { turns ->
                        orbHelper.updateChatTurns(turns)
                    }
                }

                launch {
                    viewModel.liveUserTranscript.collect { transcript ->
                        if (transcript.isNotEmpty()) {
                            orbHelper.updateLiveUserTranscript(transcript)
                        }
                    }
                }

                launch {
                    viewModel.liveAssistantResponse.collect { response ->
                        if (response.isNotEmpty()) {
                            orbHelper.updateLiveAssistantResponse(response)
                        }
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        Snackbar.make(binding.rootLayout, message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
