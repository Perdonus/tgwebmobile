package com.tgweb.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
import kotlinx.coroutines.launch

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
        val logsButton = findViewById<Button>(R.id.debugOpenLogsButton)

        statusText.text = buildStatus()
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    statusText.text = buildStatus() + "\nFCM token: " + token.take(24) + "..."
                }
                .addOnFailureListener {
                    statusText.text = buildStatus() + "\nFCM token: unavailable"
                }
        }
        testButton.setOnClickListener {
            lifecycleScope.launch {
                statusText.text = buildStatus() + "\nPush test: sending via backend..."
                val result = runCatching {
                    AppRepositories.notificationService.sendServerTestPush()
                }.getOrElse { error ->
                    com.tgweb.core.data.PushDebugResult(
                        success = false,
                        title = "Push test failed",
                        details = "Exception: ${error::class.java.simpleName}: ${error.message}\n\n${DebugLogStore.dump()}",
                    )
                }

                statusText.text = buildStatus() +
                    "\nPush test: " + if (result.success) "request sent" else "failed"
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
        val allowed = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val keepAliveEnabled = KeepAliveService.isEnabled(this)
        return "Notifications: ${if (allowed) "enabled" else "disabled"}\nKeep alive: ${if (keepAliveEnabled) "enabled" else "disabled"}"
    }

    private fun showLogDialog(title: String, logs: String, allowClear: Boolean) {
        val contentView = ScrollView(this).apply {
            val body = TextView(this@DebugToolsActivity).apply {
                text = if (logs.isBlank()) "No logs yet." else logs
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(28, 20, 28, 20)
            }
            addView(
                body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(contentView)
            .setPositiveButton(R.string.debug_copy_logs) { _, _ ->
                val manager = getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(ClipData.newPlainText("flygram-debug-logs", logs))
                Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.debug_close_logs, null)
            .apply {
                if (allowClear) {
                    setNeutralButton(R.string.debug_clear_logs) { _, _ ->
                        DebugLogStore.clear()
                        Toast.makeText(this@DebugToolsActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
