package com.arponzxc.novaai.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arponzxc.novaai.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.settingsContainer)?.let {
            com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(it)
        }
    }
}
