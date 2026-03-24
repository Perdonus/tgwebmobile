package com.tgweb.app

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tgweb.core.data.AppRepositories
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.roundToInt

class DownloadsActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var refreshButton: Button
    private lateinit var adapter: ArrayAdapter<Row>
    private lateinit var palette: UiThemeBridge.SettingsPalette
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiThemeBridge.prepareSettingsTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        palette = UiThemeBridge.resolveSettingsPalette(this)
        UiThemeBridge.applyWindowColors(this, palette)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), palette)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.downloads_title)

        listView = findViewById(R.id.downloadsList)
        refreshButton = findViewById(R.id.downloadsRefreshButton)
        adapter = object : ArrayAdapter<Row>(this, android.R.layout.simple_list_item_2, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val row = getItem(position) ?: return view
                val title = view.findViewById<TextView>(android.R.id.text1)
                val subtitle = view.findViewById<TextView>(android.R.id.text2)
                title.text = row.displayName
                subtitle.text = "${row.status} • ${formatSize(row.sizeBytes)} • ${row.mimeType}"
                UiThemeBridge.styleTwoLineListRow(
                    context = this@DownloadsActivity,
                    rowView = view,
                    titleView = title,
                    subtitleView = subtitle,
                    palette = palette,
                    activated = row.isInProgress,
                )
                return view
            }
        }
        listView.adapter = adapter
        listView.emptyView = findViewById(R.id.downloadsEmptyText)
        UiThemeBridge.styleListView(listView, palette)

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
            val merged = LinkedHashMap<String, Row>()

            AppRepositories.getDownloadStatuses().forEach { state ->
                val localPath = state.localUri?.takeIf { it.startsWith("/") }.orEmpty()
                merged[state.fileId] = Row(
                    fileId = state.fileId,
                    localPath = localPath,
                    mimeType = state.mimeType.ifBlank { "application/octet-stream" },
                    sizeBytes = state.expectedBytes.coerceAtLeast(0L),
                    displayName = state.displayName.ifBlank { state.fileId },
                    status = when {
                        !state.error.isNullOrBlank() -> "Ошибка"
                        state.percent in 1..99 -> "Скачивается ${state.percent}%"
                        !state.localUri.isNullOrBlank() -> "Готово"
                        else -> "Ожидание"
                    },
                    updatedAt = state.updatedAt,
                    isInProgress = state.percent in 1..99 && state.error.isNullOrBlank(),
                )
            }

            snapshot?.cachedMedia.orEmpty().forEach { media ->
                val file = File(media.localPath)
                val current = merged[media.fileId]
                merged[media.fileId] = Row(
                    fileId = media.fileId,
                    localPath = media.localPath,
                    mimeType = media.mimeType,
                    sizeBytes = media.sizeBytes.takeIf { it > 0L } ?: file.length(),
                    displayName = prettifyName(media.localPath, media.mimeType),
                    status = current?.status?.takeIf { current.isInProgress } ?: "Готово",
                    updatedAt = maxOf(current?.updatedAt ?: 0L, file.lastModified()),
                    isInProgress = current?.isInProgress == true,
                )
            }

            rows = merged.values
                .sortedByDescending { it.updatedAt }

            adapter.clear()
            adapter.addAll(rows)
            adapter.notifyDataSetChanged()
        }
    }

    private fun showActions(row: Row) {
        if (row.localPath.isBlank()) {
            showToast("Файл ещё не готов для экспорта")
            return
        }
        MaterialAlertDialogBuilder(this)
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
        val status: String,
        val updatedAt: Long,
        val isInProgress: Boolean,
    )
}
