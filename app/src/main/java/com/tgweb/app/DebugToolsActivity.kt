package com.tgweb.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.messaging.FirebaseMessaging
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
import kotlinx.coroutines.launch

class DebugToolsActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        UiThemeBridge.prepareSettingsTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_tools)

        val palette = UiThemeBridge.resolveSettingsPalette(this)
        UiThemeBridge.applyWindowColors(this, palette)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), palette)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.debug_tools_title)

        statusText = findViewById(R.id.debugStatusText)
        val testButton = findViewById<Button>(R.id.debugTestNotificationButton)
        val settingsButton = findViewById<Button>(R.id.debugOpenNotificationSettingsButton)
        val logsButton = findViewById<Button>(R.id.debugOpenLogsButton)

        statusText.text = buildStatus()
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    statusText.text = buildStatus() + "\n" +
                        getString(R.string.debug_fcm_token_value, token.take(24))
                }
                .addOnFailureListener {
                    statusText.text = buildStatus() + "\n" +
                        getString(R.string.debug_fcm_token_unavailable)
                }
        }
        testButton.setOnClickListener {
            lifecycleScope.launch {
                statusText.text = buildStatus() + "\n" + getString(R.string.debug_push_test_running)
                val result = runCatching {
                    AppRepositories.notificationService.sendServerTestPush()
                }.getOrElse { error ->
                    com.tgweb.core.data.PushDebugResult(
                        success = false,
                        title = "Push test failed",
                        details = "Exception: ${error::class.java.simpleName}: ${error.message}\n\n${DebugLogStore.dump()}",
                    )
                }

                statusText.text = buildStatus() + "\n" + getString(
                    R.string.debug_push_test_result,
                    if (result.success) {
                        getString(R.string.debug_push_test_sent)
                    } else {
                        getString(R.string.debug_push_test_failed)
                    },
                )
                showLogDialog(
                    title = result.title,
                    logs = result.details,
                    allowClear = false,
                )
            }
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
        logsButton.setOnClickListener {
            showLogDialog(
                title = getString(R.string.debug_logs_title),
                logs = DebugLogStore.dump(),
                allowClear = true,
            )
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
        val notificationsState = if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            getString(R.string.debug_notifications_enabled)
        } else {
            getString(R.string.debug_notifications_disabled)
        }
        val keepAliveState = if (KeepAliveService.isEnabled(this)) {
            getString(R.string.debug_keep_alive_enabled)
        } else {
            getString(R.string.debug_keep_alive_disabled)
        }
        return "$notificationsState\n$keepAliveState"
    }

    private fun showLogDialog(title: String, logs: String, allowClear: Boolean) {
        val contentView = ScrollView(this).apply {
            val body = TextView(this@DebugToolsActivity).apply {
                text = if (logs.isBlank()) getString(R.string.debug_logs_empty) else logs
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(
                    UiThemeBridge.dp(context, 24),
                    UiThemeBridge.dp(context, 20),
                    UiThemeBridge.dp(context, 24),
                    UiThemeBridge.dp(context, 20),
                )
            }
            addView(
                body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(contentView)
            .setPositiveButton(R.string.debug_copy_logs) { _, _ ->
                val manager = getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(ClipData.newPlainText("flygram-debug-logs", logs))
                Toast.makeText(this, getString(R.string.debug_logs_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.debug_close_logs, null)
            .apply {
                if (allowClear) {
                    setNeutralButton(R.string.debug_clear_logs) { _, _ ->
                        DebugLogStore.clear()
                        Toast.makeText(this@DebugToolsActivity, getString(R.string.debug_logs_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
