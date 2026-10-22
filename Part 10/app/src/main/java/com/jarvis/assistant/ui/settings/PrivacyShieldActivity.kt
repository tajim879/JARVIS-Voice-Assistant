package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.databinding.ActivityPrivacyShieldBinding
import com.jarvis.assistant.util.ThemeManager

class PrivacyShieldActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyShieldBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyShieldBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnJoinOfficialTelegram.setOnClickListener {
            openUrl("https://t.me/jarvis52360")
        }

        binding.btnJoinTelegram.setOnClickListener {
            openUrl("https://t.me/jarvis_discussion00")
        }

        binding.btnJoinInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/dev.tajim")
        }

        binding.btnJoinYoutube.setOnClickListener {
            openUrl("https://youtube.com/@dev.tajim00")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
