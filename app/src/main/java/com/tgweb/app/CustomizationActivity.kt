package com.tgweb.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.AdapterView
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
        val surfaceColor = if (runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, false)) {
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
        val md3HideBasePlates = findViewById<Switch>(R.id.customMd3HideBasePlatesSwitch)
        val dynamicColor = findViewById<Switch>(R.id.customDynamicColorSwitch)
        val menuShowModSettings = findViewById<Switch>(R.id.customMenuShowModSettingsSwitch)
        val menuShowDownloads = findViewById<Switch>(R.id.customMenuShowDownloadsSwitch)
        val menuDownloadsPositionTitle = findViewById<TextView>(R.id.customMenuDownloadsPositionTitle)
        val menuDownloadsPositionSpinner = findViewById<Spinner>(R.id.customMenuDownloadsPositionSpinner)
        val menuShowDividers = findViewById<Switch>(R.id.customMenuShowDividersSwitch)
        val menuHideMore = findViewById<Switch>(R.id.customMenuHideMoreSwitch)

        md3Effects.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_EFFECTS, true)
        dynamicColor.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, false)
        md3HideBasePlates.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_HIDE_BASE_PLATES, false)
        menuShowModSettings.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MENU_SHOW_MOD_SETTINGS, true)
        menuShowDownloads.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MENU_SHOW_DOWNLOADS, true)
        menuShowDividers.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MENU_SHOW_DIVIDERS, false)
        menuHideMore.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MENU_HIDE_MORE, true)

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
        md3HideBasePlates.alpha = if (md3Enabled) 1f else 0.45f
        md3HideBasePlates.isEnabled = md3Enabled

        val downloadsPositions = listOf(
            getString(R.string.custom_menu_downloads_position_start) to "start",
            getString(R.string.custom_menu_downloads_position_end) to "end",
        )
        menuDownloadsPositionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            downloadsPositions.map { it.first },
        )
        val savedDownloadsPosition = runtimePrefs
            .getString(KeepAliveService.KEY_MENU_DOWNLOADS_POSITION, "end")
            .orEmpty()
        val selectedDownloadsPosition = downloadsPositions.indexOfFirst { it.second == savedDownloadsPosition }
        if (selectedDownloadsPosition >= 0) {
            menuDownloadsPositionSpinner.setSelection(selectedDownloadsPosition, false)
        }
        menuDownloadsPositionTitle.alpha = if (menuShowDownloads.isChecked) 1f else 0.45f
        menuDownloadsPositionSpinner.isEnabled = menuShowDownloads.isChecked

        md3Effects.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MD3_EFFECTS, value)
                .apply()
            markPendingReload()
            containerStyleTitle.alpha = if (value) 1f else 0.45f
            containerStyleSpinner.isEnabled = value
            md3HideBasePlates.alpha = if (value) 1f else 0.45f
            md3HideBasePlates.isEnabled = value
        }
        dynamicColor.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, value)
                .apply()
            markPendingReload()
        }
        md3HideBasePlates.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MD3_HIDE_BASE_PLATES, value)
                .apply()
            markPendingReload()
        }
        containerStyleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val styleValue = containerStyles.getOrNull(position)?.second ?: "segmented"
                runtimePrefs.edit()
                    .putString(KeepAliveService.KEY_MD3_CONTAINER_STYLE, styleValue)
                    .apply()
                markPendingReload()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        menuShowModSettings.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MENU_SHOW_MOD_SETTINGS, value)
                .apply()
            markPendingReload()
        }
        menuShowDownloads.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MENU_SHOW_DOWNLOADS, value)
                .apply()
            menuDownloadsPositionTitle.alpha = if (value) 1f else 0.45f
            menuDownloadsPositionSpinner.isEnabled = value
            markPendingReload()
        }
        menuShowDividers.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MENU_SHOW_DIVIDERS, value)
                .apply()
            markPendingReload()
        }
        menuHideMore.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MENU_HIDE_MORE, value)
                .apply()
            markPendingReload()
        }
        menuDownloadsPositionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val positionValue = downloadsPositions.getOrNull(position)?.second ?: "end"
                runtimePrefs.edit()
                    .putString(KeepAliveService.KEY_MENU_DOWNLOADS_POSITION, positionValue)
                    .apply()
                markPendingReload()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun markPendingReload() {
        runtimePrefs.edit()
            .putBoolean(KeepAliveService.KEY_PENDING_WEB_RELOAD, true)
            .apply()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
