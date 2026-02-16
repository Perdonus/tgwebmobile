package com.tgweb.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class CustomizationActivity : AppCompatActivity() {
    private val runtimePrefs by lazy {
        getSharedPreferences(KeepAliveService.PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val surfaceColor = if (runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, true)) {
            UiThemeBridge.readDynamicSurfaceColor(this)
        } else {
            UiThemeBridge.resolveSettingsSurfaceColor(this)
        }
        setTheme(
            if (UiThemeBridge.isLight(surfaceColor)) {
                R.style.Theme_TGWeb_Settings_Light
            } else {
                R.style.Theme_TGWeb_Settings_Dark
            },
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customization)
        UiThemeBridge.applyWindowColors(this, surfaceColor)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), surfaceColor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.customization_title)

        val hideStories = findViewById<Switch>(R.id.customHideStoriesSwitch)
        val md3Effects = findViewById<Switch>(R.id.customMd3Switch)
        val dynamicColor = findViewById<Switch>(R.id.customDynamicColorSwitch)

        hideStories.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_HIDE_STORIES, false)
        md3Effects.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_EFFECTS, true)
        dynamicColor.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, true)

        hideStories.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit().putBoolean(KeepAliveService.KEY_HIDE_STORIES, value).apply()
        }
        md3Effects.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit().putBoolean(KeepAliveService.KEY_MD3_EFFECTS, value).apply()
        }
        dynamicColor.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit().putBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, value).apply()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

