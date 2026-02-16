package com.tgweb.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
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

        val md3Effects = findViewById<Switch>(R.id.customMd3Switch)
        val containerStyleTitle = findViewById<TextView>(R.id.customContainerStyleTitle)
        val containerStyleSpinner = findViewById<Spinner>(R.id.customContainerStyleSpinner)
        val dynamicColor = findViewById<Switch>(R.id.customDynamicColorSwitch)

        md3Effects.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_EFFECTS, true)
        dynamicColor.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, true)
        val containerStyles = listOf(
            getString(R.string.custom_container_style_segmented) to "segmented",
            getString(R.string.custom_container_style_dividers) to "dividers",
        )
        val savedContainerStyle = runtimePrefs
            .getString(KeepAliveService.KEY_MD3_CONTAINER_STYLE, "segmented")
            .orEmpty()
        containerStyleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            containerStyles.map { it.first },
        )
        val selectedContainerIndex = containerStyles.indexOfFirst { it.second == savedContainerStyle }
        if (selectedContainerIndex >= 0) {
            containerStyleSpinner.setSelection(selectedContainerIndex, false)
        }
        val md3Enabled = md3Effects.isChecked
        containerStyleTitle.alpha = if (md3Enabled) 1f else 0.45f
        containerStyleSpinner.isEnabled = md3Enabled

        md3Effects.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MD3_EFFECTS, value)
                .putBoolean(KeepAliveService.KEY_PENDING_WEB_RELOAD, true)
                .apply()
            containerStyleTitle.alpha = if (value) 1f else 0.45f
            containerStyleSpinner.isEnabled = value
        }
        dynamicColor.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, value)
                .putBoolean(KeepAliveService.KEY_PENDING_WEB_RELOAD, true)
                .apply()
        }
        containerStyleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val styleValue = containerStyles.getOrNull(position)?.second ?: "segmented"
                runtimePrefs.edit()
                    .putString(KeepAliveService.KEY_MD3_CONTAINER_STYLE, styleValue)
                    .putBoolean(KeepAliveService.KEY_PENDING_WEB_RELOAD, true)
                    .apply()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
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
