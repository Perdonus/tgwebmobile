package com.tgweb.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionBackupManager {
    enum class BackupFormat(
        val wire: String,
        val label: String,
        val fileExtension: String,
    ) {
        SESSION(
            wire = "session",
            label = ".session",
            fileExtension = ".session",
        ),
        TELETHON(
            wire = "telethon",
            label = "Telethon .session",
            fileExtension = ".session",
        ),
        PYROGRAM(
            wire = "pyrogram",
            label = "Pyrogram .session",
            fileExtension = ".session",
        ),
        TDATA(
            wire = "tdata",
            label = "TData",
            fileExtension = ".zip",
        ),
        ;

        companion object {
            fun fromWire(raw: String?): BackupFormat? {
                return entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
            }
        }
    }

    data class ImportInfo(
        val format: BackupFormat,
        val createdAt: Long,
    )

    data class PendingImport(
        val format: BackupFormat,
        val payload: TelegramSessionPayload,
    )

    suspend fun exportBackup(
        context: Context,
        format: BackupFormat,
        destinationUri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(format != BackupFormat.SESSION) {
                "Для экспорта .session выбери Telethon или Pyrogram"
            }
            val payload = loadCurrentSessionSnapshot(context)
                ?: error("Открой FlyGram, дождись загрузки аккаунта и повтори экспорт")

            val tempFile = File(
                context.cacheDir,
                "session_export_${System.currentTimeMillis()}${format.fileExtension}",
            )
            deleteRecursively(tempFile)
            TelegramSessionFormats.exportToFile(format, payload, tempFile)

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open destination stream")

            deleteRecursively(tempFile)
        }
    }

    suspend fun stageImport(
        context: Context,
        sourceUri: Uri,
        expectedFormat: BackupFormat? = null,
    ): Result<ImportInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(context, sourceUri)
            val suffix = displayName?.substringAfterLast('.', missingDelimiterValue = "").orEmpty()
            val tempFile = File(
                context.cacheDir,
                "session_import_${System.currentTimeMillis()}.${suffix.ifBlank { "bin" }}",
            )
            deleteRecursively(tempFile)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open import source")

            val parsed = TelegramSessionFormats.parseImportFile(tempFile)
            requireImportCompatibility(expectedFormat, parsed.format)
            persistPendingImport(
                context = context,
                pendingImport = PendingImport(
                    format = parsed.format,
                    payload = parsed.payload.normalized(),
                ),
            )

            val prefs = context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PENDING_IMPORT_FORMAT, parsed.format.wire)
                .putString(
                    KEY_PENDING_IMPORT_NOTICE,
                    "Импорт ${parsed.format.label} подготовлен. Перезапусти приложение для применения.",
                )
                .apply()

            deleteRecursively(tempFile)
            ImportInfo(
                format = parsed.format,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    fun applyPendingImportIfNeeded(context: Context) {
        // Legacy cleanup from old app_webview-based imports. Real session import is now applied via Web K bridge.
        deleteRecursively(File(context.applicationInfo.dataDir, WEBVIEW_IMPORT_DIR_NAME))
        deleteRecursively(File(context.applicationInfo.dataDir, "${WEBVIEW_DIR_NAME}_previous"))
        pendingSettingsFile(context).delete()
    }

    fun hasPendingImport(context: Context): Boolean {
        return pendingImportFile(context).exists()
    }

    fun peekPendingImport(context: Context): PendingImport? {
        val file = pendingImportFile(context)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val format = BackupFormat.fromWire(root.optString("format"))
                ?: error("Unknown pending session format")
            val payload = TelegramSessionPayload.fromJson(root.getJSONObject("payload"))
            PendingImport(format = format, payload = payload)
        }.getOrNull()
    }

    fun clearPendingImport(context: Context) {
        pendingImportFile(context).delete()
        val prefs = context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PENDING_IMPORT_FORMAT)
            .remove(KEY_PENDING_IMPORT_NOTICE)
            .apply()
    }

    fun markPendingImportApplied(
        context: Context,
        format: BackupFormat,
        payload: TelegramSessionPayload,
        appliedUserId: Long? = payload.userId,
    ) {
        val appliedPayload = if (appliedUserId != null && payload.userId != appliedUserId) {
            payload.copy(userId = appliedUserId, updatedAt = System.currentTimeMillis())
        } else {
            payload.copy(updatedAt = System.currentTimeMillis())
        }.normalized()

        currentSessionFile(context).parentFile?.mkdirs()
        currentSessionFile(context).writeText(appliedPayload.toJson().toString())
        clearPendingImport(context)

        val prefs = context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_IMPORT_APPLIED_NOTICE, "Импорт ${format.label} успешно применён")
            .apply()
    }

    fun storeCurrentSessionSnapshot(
        context: Context,
        rawJson: String,
    ): Result<TelegramSessionPayload> {
        return runCatching {
            val payload = TelegramSessionPayload.fromJson(rawJson).normalized()
            val file = currentSessionFile(context)
            file.parentFile?.mkdirs()
            file.writeText(payload.toJson().toString())
            payload
        }
    }

    fun loadCurrentSessionSnapshot(context: Context): TelegramSessionPayload? {
        val file = currentSessionFile(context)
        if (!file.exists()) return null
        return runCatching {
            TelegramSessionPayload.fromJson(file.readText())
        }.getOrNull()
    }

    fun consumeLastImportAppliedNotice(context: Context): String? {
        val prefs = context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_LAST_IMPORT_APPLIED_NOTICE, null)
        if (value != null) {
            prefs.edit().remove(KEY_LAST_IMPORT_APPLIED_NOTICE).apply()
        }
        return value
    }

    fun buildDefaultFileName(format: BackupFormat): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = when (format) {
            BackupFormat.SESSION -> "session"
            BackupFormat.TELETHON -> "telethon"
            BackupFormat.PYROGRAM -> "pyrogram"
            BackupFormat.TDATA -> "tdata"
        }
        return "flygram_${suffix}_$date${format.fileExtension}"
    }

    private fun persistPendingImport(
        context: Context,
        pendingImport: PendingImport,
    ) {
        val root = JSONObject()
            .put("format", pendingImport.format.wire)
            .put("payload", pendingImport.payload.normalized().toJson())
            .put("createdAt", System.currentTimeMillis())
        val file = pendingImportFile(context)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    private fun requireImportCompatibility(
        expectedFormat: BackupFormat?,
        actualFormat: BackupFormat,
    ) {
        when (expectedFormat) {
            null -> Unit
            BackupFormat.SESSION -> require(actualFormat == BackupFormat.TELETHON || actualFormat == BackupFormat.PYROGRAM) {
                "Ожидался .session, но импортирован ${actualFormat.label}"
            }
            else -> require(actualFormat == expectedFormat) {
                "Ожидался ${expectedFormat.label}, но импортирован ${actualFormat.label}"
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)
            }
    }

    private fun currentSessionFile(context: Context): File {
        return File(context.filesDir, CURRENT_SESSION_FILE_NAME)
    }

    private fun pendingImportFile(context: Context): File {
        return File(context.filesDir, PENDING_IMPORT_FILE_NAME)
    }

    private fun pendingSettingsFile(context: Context): File {
        return File(context.filesDir, PENDING_SETTINGS_FILE_NAME)
    }

    private fun deleteRecursively(target: File) {
        if (!target.exists()) return
        if (target.isDirectory) {
            target.listFiles()?.forEach(::deleteRecursively)
        }
        target.delete()
    }

    private const val WEBVIEW_DIR_NAME = "app_webview"
    private const val WEBVIEW_IMPORT_DIR_NAME = "app_webview_import_staging"
    private const val CURRENT_SESSION_FILE_NAME = "flygram_current_session.json"
    private const val PENDING_IMPORT_FILE_NAME = "flygram_pending_import.json"
    private const val PENDING_SETTINGS_FILE_NAME = "tgweb_pending_settings.json"
    private const val KEY_PENDING_IMPORT_FORMAT = "session_backup_pending_import_format"
    private const val KEY_PENDING_IMPORT_NOTICE = "session_backup_pending_import_notice"
    private const val KEY_LAST_IMPORT_APPLIED_NOTICE = "session_backup_last_applied_notice"
}
