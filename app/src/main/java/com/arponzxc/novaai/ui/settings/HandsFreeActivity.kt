package com.arponzxc.novaai.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arponzxc.novaai.R
import com.arponzxc.novaai.config.SocialLinks
import com.arponzxc.novaai.databinding.ActivityHandsFreeBinding
import com.arponzxc.novaai.ui.permission.PermissionActivity
import com.arponzxc.novaai.util.PermissionHelper
import com.arponzxc.novaai.util.ThemeManager

class HandsFreeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandsFreeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHandsFreeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnOpenAllPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        binding.btnConfigureApi.setOnClickListener {
            startActivity(Intent(this, ApiConnectionActivity::class.java))
        }

        binding.btnHandsFreeOfficialTelegram.setOnClickListener {
            SocialLinks.openTelegramOfficial(this)
        }

        binding.btnHandsFreeDiscussion.setOnClickListener {
            SocialLinks.openTelegramDiscussion(this)
        }

        binding.btnHandsFreeInstagram.setOnClickListener {
            SocialLinks.openInstagram(this)
        }

        binding.btnHandsFreeYoutube.setOnClickListener {
            SocialLinks.openYoutube(this)
        }

        binding.btnHandsFreeGithub.setOnClickListener {
            SocialLinks.openGithub(this)
        }

        binding.tvFooterHandsFree.setOnClickListener {
            SocialLinks.openTelegramOfficial(this)
        }
    }

    private fun updateServiceStatus() {
        val isEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        if (isEnabled) {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.tvAccessibilityStatus.text = "HANDS-FREE SERVICE: ACTIVE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.btnToggleAccessibility.text = "Accessibility Service is Active"
        } else {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.tvAccessibilityStatus.text = "SERVICE DISABLED • TAP TO ENABLE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.btnToggleAccessibility.text = "Enable Accessibility Service"
        }
    }
}
