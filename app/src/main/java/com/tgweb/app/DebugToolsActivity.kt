package com.tgweb.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.MessageItem
import kotlin.random.Random

class DebugToolsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val surfaceColor = UiThemeBridge.resolveSettingsSurfaceColor(this)
        setTheme(
            if (UiThemeBridge.isLight(surfaceColor)) {
                R.style.Theme_TGWeb_Settings_Light
            } else {
                R.style.Theme_TGWeb_Settings_Dark
            },
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_tools)
        UiThemeBridge.applyWindowColors(this, surfaceColor)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), surfaceColor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.debug_tools_title)

        val statusText = findViewById<TextView>(R.id.debugStatusText)
        val testButton = findViewById<Button>(R.id.debugTestNotificationButton)
        val settingsButton = findViewById<Button>(R.id.debugOpenNotificationSettingsButton)

        statusText.text = buildStatus()
        testButton.setOnClickListener {
            val randomId = Random.nextLong(100_000, 999_999)
            AppRepositories.notificationService.showMessageNotification(
                MessageItem(
                    messageId = randomId,
                    chatId = Random.nextLong(1000, 9999),
                    senderUserId = 0L,
                    text = randomDebugMessage(),
                    status = "received",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            statusText.text = buildStatus()
        }
        settingsButton.setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
            startActivity(intent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun buildStatus(): String {
        val allowed = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val keepAliveEnabled = KeepAliveService.isEnabled(this)
        return "Notifications: ${if (allowed) "enabled" else "disabled"}\nKeep alive: ${if (keepAliveEnabled) "enabled" else "disabled"}"
    }

    private fun randomDebugMessage(): String {
        val variants = listOf(
            "Debug push: build looks alive",
            "Debug push: random test notification",
            "Debug push: background channel check",
            "Debug push: keep-alive path is running",
            "Debug push: UI bridge event delivered",
        )
        return variants.random()
    }
}

