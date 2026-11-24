package com.arponzxc.novaai.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arponzxc.novaai.config.SocialLinks
import com.arponzxc.novaai.databinding.ActivityPrivacyShieldBinding
import com.arponzxc.novaai.util.ThemeManager

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
            SocialLinks.openTelegramOfficial(this)
        }

        binding.btnJoinTelegram.setOnClickListener {
            SocialLinks.openTelegramDiscussion(this)
        }

        binding.btnJoinInstagram.setOnClickListener {
            SocialLinks.openInstagram(this)
        }

        binding.btnJoinYoutube.setOnClickListener {
            SocialLinks.openYoutube(this)
        }

        binding.btnJoinGithub.setOnClickListener {
            SocialLinks.openGithub(this)
        }
    }
}
