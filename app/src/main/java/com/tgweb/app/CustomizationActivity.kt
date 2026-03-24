package com.tgweb.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

class CustomizationActivity : AppCompatActivity() {
    private val runtimePrefs by lazy {
        getSharedPreferences(KeepAliveService.PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiThemeBridge.prepareSettingsTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customization)

        val palette = UiThemeBridge.resolveSettingsPalette(this)
        UiThemeBridge.applyWindowColors(this, palette)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), palette)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.customization_title)

        val md3Effects = findViewById<MaterialSwitch>(R.id.customMd3Switch)
        val containerStyleTitle = findViewById<TextView>(R.id.customContainerStyleTitle)
        val containerStyleField = findViewById<TextInputLayout>(R.id.customContainerStyleField)
        val containerStyleDropdown = findViewById<MaterialAutoCompleteTextView>(R.id.customContainerStyleSpinner)
        val md3HideBasePlates = findViewById<MaterialSwitch>(R.id.customMd3HideBasePlatesSwitch)
        val dynamicColor = findViewById<MaterialSwitch>(R.id.customDynamicColorSwitch)
        val replyAutoFocus = findViewById<MaterialSwitch>(R.id.customReplyAutoFocusSwitch)
        val menuDownloadsPositionTitle = findViewById<TextView>(R.id.customMenuDownloadsPositionTitle)
        val menuDownloadsPositionField = findViewById<TextInputLayout>(R.id.customMenuDownloadsPositionField)
        val menuDownloadsPositionDropdown =
            findViewById<MaterialAutoCompleteTextView>(R.id.customMenuDownloadsPositionSpinner)
        val menuShowDividers = findViewById<MaterialSwitch>(R.id.customMenuShowDividersSwitch)
        val menuHideMore = findViewById<MaterialSwitch>(R.id.customMenuHideMoreSwitch)

        md3Effects.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_EFFECTS, true)
        dynamicColor.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, false)
        md3HideBasePlates.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_HIDE_BASE_PLATES, false)
        replyAutoFocus.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_REPLY_AUTO_FOCUS, true)
        menuShowDividers.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MENU_SHOW_DIVIDERS, false)
        menuHideMore.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_MENU_HIDE_MORE, true)

        val containerStyles = listOf(
            getString(R.string.custom_container_style_segmented) to "segmented",
            getString(R.string.custom_container_style_dividers) to "dividers",
        )
        containerStyleDropdown.setSimpleItems(containerStyles.map { it.first }.toTypedArray())
        val savedContainerStyle = runtimePrefs
            .getString(KeepAliveService.KEY_MD3_CONTAINER_STYLE, "segmented")
            .orEmpty()
        val containerLabel = containerStyles.firstOrNull { it.second == savedContainerStyle }?.first
            ?: containerStyles.first().first
        containerStyleDropdown.setText(containerLabel, false)

        val downloadsPositions = listOf(
            getString(R.string.custom_menu_downloads_position_start) to "start",
            getString(R.string.custom_menu_downloads_position_end) to "end",
        )
        menuDownloadsPositionDropdown.setSimpleItems(downloadsPositions.map { it.first }.toTypedArray())
        val savedDownloadsPosition = runtimePrefs
            .getString(KeepAliveService.KEY_MENU_DOWNLOADS_POSITION, "end")
            .orEmpty()
        val downloadLabel = downloadsPositions.firstOrNull { it.second == savedDownloadsPosition }?.first
            ?: downloadsPositions.last().first
        menuDownloadsPositionDropdown.setText(downloadLabel, false)

        syncMd3Dependents(
            enabled = md3Effects.isChecked,
            containerStyleTitle = containerStyleTitle,
            containerStyleField = containerStyleField,
            hideBasePlateSwitch = md3HideBasePlates,
        )

        md3Effects.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MD3_EFFECTS, value)
                .apply()
            syncMd3Dependents(value, containerStyleTitle, containerStyleField, md3HideBasePlates)
            markPendingReload()
        }
        dynamicColor.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, value)
                .apply()
            markPendingReload()
        }
        replyAutoFocus.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_REPLY_AUTO_FOCUS, value)
                .apply()
            markPendingReload()
        }
        md3HideBasePlates.setOnCheckedChangeListener { _, value ->
            runtimePrefs.edit()
                .putBoolean(KeepAliveService.KEY_MD3_HIDE_BASE_PLATES, value)
                .apply()
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

        containerStyleDropdown.setOnItemClickListener { _, _, position, _ ->
            val styleValue = containerStyles.getOrNull(position)?.second ?: "segmented"
            runtimePrefs.edit()
                .putString(KeepAliveService.KEY_MD3_CONTAINER_STYLE, styleValue)
                .apply()
            markPendingReload()
        }
        menuDownloadsPositionDropdown.setOnItemClickListener { _, _, position, _ ->
            val positionValue = downloadsPositions.getOrNull(position)?.second ?: "end"
            runtimePrefs.edit()
                .putString(KeepAliveService.KEY_MENU_DOWNLOADS_POSITION, positionValue)
                .apply()
            markPendingReload()
        }

        menuDownloadsPositionTitle.alpha = 1f
        menuDownloadsPositionField.isEnabled = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun syncMd3Dependents(
        enabled: Boolean,
        containerStyleTitle: TextView,
        containerStyleField: TextInputLayout,
        hideBasePlateSwitch: MaterialSwitch,
    ) {
        val alpha = if (enabled) 1f else 0.45f
        containerStyleTitle.alpha = alpha
        containerStyleField.isEnabled = enabled
        containerStyleField.alpha = alpha
        hideBasePlateSwitch.isEnabled = enabled
        hideBasePlateSwitch.alpha = alpha
    }

    private fun markPendingReload() {
        runtimePrefs.edit()
            .putBoolean(KeepAliveService.KEY_PENDING_WEB_RELOAD, true)
            .apply()
    }
}
