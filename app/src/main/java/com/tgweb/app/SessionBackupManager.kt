package com.tgweb.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SessionBackupManager {
    enum class BackupFormat(
        val wire: String,
        val rootEntry: String,
        val label: String,
    ) {
        SESSION(
            wire = "tgweb_session_v1",
            rootEntry = "session_profile",
            label = "Session",
        ),
        TDATA(
            wire = "tgweb_tdata_bridge_v1",
            rootEntry = "tdata_profile",
            label = "tdata",
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

    suspend fun exportBackup(
        context: Context,
        format: BackupFormat,
        destinationUri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceDir = webViewDataDir(context)
            require(sourceDir.exists() && sourceDir.isDirectory) {
                "WebView profile is missing"
            }

            val outputStream = context.contentResolver.openOutputStream(destinationUri)
                ?: error("Unable to open destination stream")

            outputStream.use { stream ->
                ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                    val createdAt = System.currentTimeMillis()
                    val manifest = JSONObject()
                        .put("format", format.wire)
                        .put("createdAt", createdAt)
                        .put("appId", context.packageName)
                        .put("profileRoot", format.rootEntry)
                        .toString()

                    zip.putNextEntry(ZipEntry(MANIFEST_FILE_NAME))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    val settingsJson = JSONObject()
                    context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
                        .all
                        .forEach { (key, value) ->
                            when (value) {
                                is String -> settingsJson.put(key, value)
                                is Boolean -> settingsJson.put(key, value)
                                is Int -> settingsJson.put(key, value)
                                is Long -> settingsJson.put(key, value)
                                is Float -> settingsJson.put(key, value)
                            }
                        }
                    zip.putNextEntry(ZipEntry(SETTINGS_FILE_NAME))
                    zip.write(settingsJson.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zipDirectory(
                        rootDir = sourceDir,
                        current = sourceDir,
                        zip = zip,
                        zipRoot = format.rootEntry,
                    )
                }
            }
        }
    }

    suspend fun stageImport(
        context: Context,
        sourceUri: Uri,
        expectedFormat: BackupFormat? = null,
    ): Result<ImportInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "session_import_${System.currentTimeMillis()}")
            deleteRecursively(tempDir)
            tempDir.mkdirs()

            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: error("Unable to open import source")

            val entries = mutableSetOf<String>()
            inputStream.use { stream ->
                ZipInputStream(BufferedInputStream(stream)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val sanitizedName = sanitizeZipEntryName(entry.name) ?: continue
                        entries += sanitizedName
                        val outFile = File(tempDir, sanitizedName)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output ->
                                zip.copyTo(output)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            val manifestFile = File(tempDir, MANIFEST_FILE_NAME)
            val manifest = if (manifestFile.exists()) {
                runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
            } else {
                null
            }

            val detectedFormat = detectImportFormat(
                tempDir = tempDir,
                entries = entries,
                manifest = manifest,
            )

            if (expectedFormat != null && detectedFormat != expectedFormat) {
                error("Backup format mismatch: expected ${expectedFormat.label}, got ${detectedFormat.label}")
            }

            val sourceRoot = when {
                File(tempDir, detectedFormat.rootEntry).isDirectory -> File(tempDir, detectedFormat.rootEntry)
                File(tempDir, WEBVIEW_DIR_NAME).isDirectory -> File(tempDir, WEBVIEW_DIR_NAME)
                else -> error("Backup does not contain a WebView profile")
            }

            val staging = pendingImportDir(context)
            replaceDirectory(
                source = sourceRoot,
                destination = staging,
            )

            val settingsFile = File(tempDir, SETTINGS_FILE_NAME)
            val settingsPending = pendingSettingsFile(context)
            if (settingsFile.exists()) {
                settingsPending.parentFile?.mkdirs()
                settingsFile.copyTo(settingsPending, overwrite = true)
            } else {
                settingsPending.delete()
            }

            val createdAt = manifest?.optLong("createdAt")?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val prefs = context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_PENDING_IMPORT, true)
                .putString(KEY_PENDING_IMPORT_FORMAT, detectedFormat.wire)
                .putString(
                    KEY_PENDING_IMPORT_NOTICE,
                    "Импорт ${detectedFormat.label} подготовлен. Перезапусти приложение для применения.",
                )
                .apply()

            deleteRecursively(tempDir)
            ImportInfo(format = detectedFormat, createdAt = createdAt)
        }
    }

    fun applyPendingImportIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING_IMPORT, false)) return

        val staging = pendingImportDir(context)
        if (!staging.exists() || !staging.isDirectory) {
            prefs.edit()
                .remove(KEY_PENDING_IMPORT)
                .remove(KEY_PENDING_IMPORT_FORMAT)
                .remove(KEY_PENDING_IMPORT_NOTICE)
                .apply()
            return
        }

        val target = webViewDataDir(context)
        val backup = File(context.applicationInfo.dataDir, "${WEBVIEW_DIR_NAME}_previous")
        deleteRecursively(backup)

        if (target.exists()) {
            if (!target.renameTo(backup)) {
                deleteRecursively(backup)
                replaceDirectory(target, backup)
                deleteRecursively(target)
            }
        }

        if (!staging.renameTo(target)) {
            replaceDirectory(staging, target)
            deleteRecursively(staging)
        }

        val settingsPending = pendingSettingsFile(context)
        if (settingsPending.exists()) {
            runCatching {
                val root = JSONObject(settingsPending.readText())
                val editor = prefs.edit()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = root.opt(key)
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
            }
            settingsPending.delete()
        }

        deleteRecursively(backup)
        prefs.edit()
            .remove(KEY_PENDING_IMPORT)
            .remove(KEY_PENDING_IMPORT_FORMAT)
            .putString(KEY_LAST_IMPORT_APPLIED_NOTICE, "Импорт профиля успешно применён")
            .apply()
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
        val suffix = if (format == BackupFormat.SESSION) "session" else "tdata"
        return "flygram_${suffix}_$date.zip"
    }

    private fun detectImportFormat(
        tempDir: File,
        entries: Set<String>,
        manifest: JSONObject?,
    ): BackupFormat {
        val manifestFormat = BackupFormat.fromWire(manifest?.optString("format"))
        if (manifestFormat != null) return manifestFormat

        if (File(tempDir, BackupFormat.SESSION.rootEntry).isDirectory) return BackupFormat.SESSION
        if (File(tempDir, BackupFormat.TDATA.rootEntry).isDirectory) return BackupFormat.TDATA
        if (File(tempDir, WEBVIEW_DIR_NAME).isDirectory) return BackupFormat.SESSION

        if (looksLikeDesktopTdata(entries)) {
            error(
                "Desktop tdata archive detected. " +
                    "Direct Telegram Desktop tdata import is encrypted and unsupported in this build.",
            )
        }

        error("Unknown backup format")
    }

    private fun looksLikeDesktopTdata(entries: Set<String>): Boolean {
        val lowered = entries.map { it.lowercase(Locale.US) }
        val hasKeyData = lowered.any { it.endsWith("/key_datas") || it == "key_datas" }
        val hasMap = lowered.any { it.endsWith("/map0") || it == "map0" }
        val hasDPrefix = lowered.any { name ->
            val top = name.substringBefore('/')
            top.startsWith("d877f783d5d3ef8c")
        }
        return hasKeyData || hasMap || hasDPrefix
    }

    private fun sanitizeZipEntryName(raw: String): String? {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.contains("..")) return null
        return normalized
    }

    private fun zipDirectory(
        rootDir: File,
        current: File,
        zip: ZipOutputStream,
        zipRoot: String,
    ) {
        current.listFiles()?.forEach { child ->
            val relative = child.relativeTo(rootDir).path.replace(File.separatorChar, '/')
            if (relative.isBlank()) return@forEach
            val entryName = "$zipRoot/$relative"
            if (child.isDirectory) {
                val dirEntry = if (entryName.endsWith("/")) entryName else "$entryName/"
                zip.putNextEntry(ZipEntry(dirEntry))
                zip.closeEntry()
                zipDirectory(rootDir, child, zip, zipRoot)
            } else {
                val lower = child.name.lowercase(Locale.US)
                if (lower == "lock" || lower == "singletonlock" || lower == "singletoncookie") {
                    return@forEach
                }
                zip.putNextEntry(ZipEntry(entryName))
                child.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    private fun replaceDirectory(
        source: File,
        destination: File,
    ) {
        deleteRecursively(destination)
        destination.mkdirs()
        copyDirectoryContents(source, destination)
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        source.listFiles()?.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                copyDirectoryContents(child, target)
            } else {
                child.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun deleteRecursively(target: File) {
        if (!target.exists()) return
        if (target.isDirectory) {
            target.listFiles()?.forEach { deleteRecursively(it) }
        }
        target.delete()
    }

    private fun webViewDataDir(context: Context): File {
        return File(context.applicationInfo.dataDir, WEBVIEW_DIR_NAME)
    }

    private fun pendingImportDir(context: Context): File {
        return File(context.applicationInfo.dataDir, WEBVIEW_IMPORT_DIR_NAME)
    }

    private fun pendingSettingsFile(context: Context): File {
        return File(context.filesDir, PENDING_SETTINGS_FILE_NAME)
    }

    private const val WEBVIEW_DIR_NAME = "app_webview"
    private const val WEBVIEW_IMPORT_DIR_NAME = "app_webview_import_staging"
    private const val MANIFEST_FILE_NAME = "tgweb_backup_manifest.json"
    private const val SETTINGS_FILE_NAME = "tgweb_runtime_settings.json"
    private const val PENDING_SETTINGS_FILE_NAME = "tgweb_pending_settings.json"
    private const val KEY_PENDING_IMPORT = "session_backup_pending_import"
    private const val KEY_PENDING_IMPORT_FORMAT = "session_backup_pending_import_format"
    private const val KEY_PENDING_IMPORT_NOTICE = "session_backup_pending_import_notice"
    private const val KEY_LAST_IMPORT_APPLIED_NOTICE = "session_backup_last_applied_notice"
}
