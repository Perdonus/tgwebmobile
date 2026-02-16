package com.tgweb.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionToolsActivity : AppCompatActivity() {
    private lateinit var importSessionButton: Button
    private lateinit var importTdataButton: Button
    private lateinit var exportSessionButton: Button
    private lateinit var exportTdataButton: Button
    private lateinit var statusText: TextView

    private var pendingExportFormat: SessionBackupManager.BackupFormat? = null
    private var pendingImportFormat: SessionBackupManager.BackupFormat? = null
    private var isBusy: Boolean = false

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val format = pendingExportFormat
            pendingExportFormat = null
            if (uri == null || format == null) return@registerForActivityResult
            exportBackup(uri, format)
        }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val format = pendingImportFormat
            pendingImportFormat = null
            if (uri == null) return@registerForActivityResult
            importBackup(uri, format)
        }

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
        setContentView(R.layout.activity_session_tools)
        UiThemeBridge.applyWindowColors(this, surfaceColor)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), surfaceColor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.session_tools_title)

        bindViews()
        bindActions()
        maybeRunIntentAction()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        importSessionButton = findViewById(R.id.importSessionButton)
        importTdataButton = findViewById(R.id.importTdataButton)
        exportSessionButton = findViewById(R.id.exportSessionButton)
        exportTdataButton = findViewById(R.id.exportTdataButton)
        statusText = findViewById(R.id.sessionStatusText)
    }

    private fun bindActions() {
        importSessionButton.setOnClickListener {
            startImport(SessionBackupManager.BackupFormat.SESSION)
        }
        importTdataButton.setOnClickListener {
            startImport(SessionBackupManager.BackupFormat.TDATA)
        }
        exportSessionButton.setOnClickListener {
            startExport(SessionBackupManager.BackupFormat.SESSION)
        }
        exportTdataButton.setOnClickListener {
            startExport(SessionBackupManager.BackupFormat.TDATA)
        }
    }

    private fun maybeRunIntentAction() {
        when (intent?.getStringExtra(EXTRA_ACTION_MODE)) {
            ACTION_IMPORT_SESSION -> startImport(SessionBackupManager.BackupFormat.SESSION)
            ACTION_IMPORT_TDATA -> startImport(SessionBackupManager.BackupFormat.TDATA)
            else -> Unit
        }
    }

    private fun startExport(format: SessionBackupManager.BackupFormat) {
        if (isBusy) return
        pendingExportFormat = format
        createDocumentLauncher.launch(SessionBackupManager.buildDefaultFileName(format))
    }

    private fun startImport(format: SessionBackupManager.BackupFormat) {
        if (isBusy) return
        pendingImportFormat = format
        openDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/json", "*/*"))
    }

    private fun exportBackup(destination: Uri, format: SessionBackupManager.BackupFormat) {
        setBusy(true)
        statusText.text = getString(R.string.session_tools_export_running, format.label)
        lifecycleScope.launch {
            val result = SessionBackupManager.exportBackup(
                context = this@SessionToolsActivity,
                format = format,
                destinationUri = destination,
            )

            result
                .onSuccess {
                    val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    statusText.text = getString(R.string.session_tools_export_done, format.label, now)
                    showToast(getString(R.string.session_tools_export_done_short, format.label))
                }
                .onFailure { error ->
                    statusText.text = getString(R.string.session_tools_error, error.message.orEmpty())
                    showToast(getString(R.string.session_tools_export_failed))
                }
            setBusy(false)
        }
    }

    private fun importBackup(source: Uri, expected: SessionBackupManager.BackupFormat?) {
        setBusy(true)
        val label = expected?.label ?: getString(R.string.session_tools_unknown_format)
        statusText.text = getString(R.string.session_tools_import_running, label)
        lifecycleScope.launch {
            val result = SessionBackupManager.stageImport(
                context = this@SessionToolsActivity,
                sourceUri = source,
                expectedFormat = expected,
            )

            result
                .onSuccess { info ->
                    val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    statusText.text = getString(R.string.session_tools_import_done, info.format.label, now)
                    showToast(getString(R.string.session_tools_import_done_short, info.format.label))
                    restartApp()
                }
                .onFailure { error ->
                    statusText.text = getString(R.string.session_tools_error, error.message.orEmpty())
                    showToast(getString(R.string.session_tools_import_failed))
                    setBusy(false)
                }
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        listOf(importSessionButton, importTdataButton, exportSessionButton, exportTdataButton).forEach { button ->
            button.isEnabled = !busy
            button.alpha = if (busy) 0.55f else 1f
        }
        statusText.visibility = View.VISIBLE
    }

    private fun restartApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(launchIntent)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ACTION_MODE = "extra_action_mode"
        const val ACTION_IMPORT_SESSION = "import_session"
        const val ACTION_IMPORT_TDATA = "import_tdata"
    }
}
