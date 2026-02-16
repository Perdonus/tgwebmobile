package com.tgweb.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgweb.core.data.AppRepositories
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class DownloadsActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var refreshButton: Button
    private lateinit var adapter: ArrayAdapter<String>
    private var rows: List<Row> = emptyList()

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
        setContentView(R.layout.activity_downloads)
        UiThemeBridge.applyWindowColors(this, surfaceColor)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), surfaceColor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.downloads_title)

        listView = findViewById(R.id.downloadsList)
        refreshButton = findViewById(R.id.downloadsRefreshButton)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter
        listView.emptyView = findViewById(R.id.downloadsEmptyText)

        refreshButton.setOnClickListener { refresh() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            showActions(row)
        }

        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val snapshot = runCatching { AppRepositories.webBootstrapProvider() }.getOrNull()
            rows = snapshot?.cachedMedia
                ?.map { media ->
                    val displayName = prettifyName(media.localPath, media.mimeType)
                    Row(
                        fileId = media.fileId,
                        localPath = media.localPath,
                        mimeType = media.mimeType,
                        sizeBytes = media.sizeBytes,
                        displayName = displayName,
                    )
                }
                ?.sortedByDescending { File(it.localPath).lastModified() }
                .orEmpty()

            val labels = rows.map {
                "${it.displayName}\n${formatSize(it.sizeBytes)} • ${it.mimeType}"
            }
            adapter.clear()
            adapter.addAll(labels)
            adapter.notifyDataSetChanged()
        }
    }

    private fun showActions(row: Row) {
        AlertDialog.Builder(this)
            .setTitle(row.displayName)
            .setItems(
                arrayOf(
                    getString(R.string.downloads_action_export),
                    getString(R.string.downloads_action_delete_cache),
                ),
            ) { _, which ->
                when (which) {
                    0 -> exportToDownloads(row.fileId)
                    1 -> deleteFromCache(row.fileId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportToDownloads(fileId: String) {
        lifecycleScope.launch {
            val result = AppRepositories.mediaRepository.downloadToPublicStorage(fileId, "flygram_downloads")
            if (result.isSuccess) {
                showToast(getString(R.string.downloads_export_done))
            } else {
                showToast(getString(R.string.downloads_export_failed))
            }
        }
    }

    private fun deleteFromCache(fileId: String) {
        lifecycleScope.launch {
            val deleted = AppRepositories.mediaRepository.removeCachedFile(fileId)
            if (deleted) {
                showToast(getString(R.string.downloads_delete_done))
                refresh()
            } else {
                showToast(getString(R.string.downloads_delete_failed))
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * kb
        val gb = mb * kb
        return when {
            bytes >= gb -> "${(bytes / gb * 10.0).roundToInt() / 10.0} GB"
            bytes >= mb -> "${(bytes / mb * 10.0).roundToInt() / 10.0} MB"
            bytes >= kb -> "${(bytes / kb * 10.0).roundToInt() / 10.0} KB"
            else -> "$bytes B"
        }
    }

    private fun prettifyName(localPath: String, mimeType: String): String {
        val raw = File(localPath).name
        val withoutPrefix = raw.replaceFirst(Regex("^[0-9a-fA-F-]+_"), "")
        if (withoutPrefix.isBlank()) return raw
        if (withoutPrefix.endsWith(".bin", ignoreCase = true) && mimeType != "application/octet-stream") {
            return withoutPrefix.removeSuffix(".bin")
        }
        return withoutPrefix
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class Row(
        val fileId: String,
        val localPath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val displayName: String,
    )
}
