package com.tgweb.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val DEFAULT_SERVER_SALT = "AAAAAAAAAAAAAAAA"

private fun normalizeSessionHex(raw: String): String {
    val hex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    require(hex.length % 2 == 0) { "Invalid hex length" }
    return hex.uppercase(Locale.US)
}

internal data class TelegramSessionPayload(
    val sourceFormat: String,
    val dcId: Int,
    val userId: Long?,
    val authKeys: Map<Int, String>,
    val serverSalts: Map<Int, String> = emptyMap(),
    val authKeyFingerprint: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun normalized(): TelegramSessionPayload {
        require(dcId in 1..5) { "Base DC is missing" }
        require(authKeys.isNotEmpty()) { "No auth keys in session payload" }
        val normalizedKeys = authKeys
            .mapKeys { it.key }
            .filterKeys { it in 1..5 }
            .mapValues { (_, value) ->
                normalizeSessionHex(value).also {
                    require(it.length == 512) { "Invalid auth key length for DC" }
                }
            }
            .toSortedMap()
        require(normalizedKeys.containsKey(dcId)) { "Base DC auth key is missing" }

        val normalizedSalts = normalizedKeys.keys.associateWith { key ->
            serverSalts[key]
                ?.let(::normalizeSessionHex)
                ?.takeIf { it.length == 16 }
                ?: DEFAULT_SERVER_SALT
        }
        val normalizedFingerprint = authKeyFingerprint
            .takeIf { it.isNotBlank() }
            ?.let(::normalizeSessionHex)
            ?.take(8)
            ?: normalizedKeys[dcId].orEmpty().take(8)

        return copy(
            authKeys = normalizedKeys,
            serverSalts = normalizedSalts,
            authKeyFingerprint = normalizedFingerprint,
        )
    }

    fun toJson(): JSONObject {
        val authKeysJson = JSONObject()
        authKeys.toSortedMap().forEach { (dcId, authKey) ->
            authKeysJson.put(dcId.toString(), authKey)
        }
        val serverSaltsJson = JSONObject()
        serverSalts.toSortedMap().forEach { (dcId, salt) ->
            serverSaltsJson.put(dcId.toString(), salt)
        }

        return JSONObject()
            .put("sourceFormat", sourceFormat)
            .put("dcId", dcId)
            .put("userId", userId?.toString() ?: "")
            .put("authKeys", authKeysJson)
            .put("serverSalts", serverSaltsJson)
            .put("authKeyFingerprint", authKeyFingerprint)
            .put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(raw: String): TelegramSessionPayload {
            return fromJson(JSONObject(raw))
        }

        fun fromJson(root: JSONObject): TelegramSessionPayload {
            val authKeys = root.optJSONObject("authKeys")
                ?.keys()
                ?.asSequence()
                ?.mapNotNull { key ->
                    key.toIntOrNull()?.let { dcId ->
                        dcId to root.optJSONObject("authKeys")!!.optString(key)
                    }
                }
                ?.toMap()
                ?: emptyMap()

            val serverSalts = root.optJSONObject("serverSalts")
                ?.keys()
                ?.asSequence()
                ?.mapNotNull { key ->
                    key.toIntOrNull()?.let { dcId ->
                        dcId to root.optJSONObject("serverSalts")!!.optString(key)
                    }
                }
                ?.toMap()
                ?: emptyMap()

            return TelegramSessionPayload(
                sourceFormat = root.optString("sourceFormat"),
                dcId = root.optInt("dcId"),
                userId = root.optString("userId").takeIf { it.isNotBlank() }?.toLongOrNull(),
                authKeys = authKeys,
                serverSalts = serverSalts,
                authKeyFingerprint = root.optString("authKeyFingerprint"),
                updatedAt = root.optLong("updatedAt").takeIf { it > 0L } ?: System.currentTimeMillis(),
            ).normalized()
        }
    }
}

internal object TelegramSessionFormats {
    private const val TELETHON_VERSION = 7
    private const val PYROGRAM_VERSION = 2
    private const val TELEGRAM_DESKTOP_VERSION = 3_004_000
    private val TELEGRAM_DESKTOP_MAGIC = "TDF$".toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()

    data class ParsedImport(
        val format: SessionBackupManager.BackupFormat,
        val payload: TelegramSessionPayload,
    )

    fun parseImportFile(sourceFile: File): ParsedImport {
        val header = sourceFile.inputStream().buffered().use { input ->
            ByteArray(16).also { buffer ->
                val read = input.read(buffer)
                if (read <= 0) return@use ByteArray(0)
            }
        }

        return when {
            header.startsWith(byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())) -> {
                ParsedImport(
                    format = SessionBackupManager.BackupFormat.TDATA,
                    payload = parseTDataZip(sourceFile),
                )
            }
            header.startsWith("SQLite format 3".toByteArray(Charsets.US_ASCII)) -> parseSqliteSession(sourceFile)
            else -> error("Неподдерживаемый формат сессии")
        }
    }

    fun exportToFile(
        format: SessionBackupManager.BackupFormat,
        payload: TelegramSessionPayload,
        destination: File,
    ) {
        when (format) {
            SessionBackupManager.BackupFormat.SESSION -> error("Generic .session format cannot be exported")
            SessionBackupManager.BackupFormat.TELETHON -> writeTelethonSession(payload, destination)
            SessionBackupManager.BackupFormat.PYROGRAM -> writePyrogramSession(payload, destination)
            SessionBackupManager.BackupFormat.TDATA -> {
                destination.outputStream().buffered().use { output ->
                    writeTDataZip(payload, output)
                }
            }
        }
    }

    private fun parseSqliteSession(sourceFile: File): ParsedImport {
        val database = SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        database.use { db ->
            val tableNames = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    tableNames += cursor.getString(0)
                }
            }

            return when {
                tableNames.containsAll(listOf("sessions", "peers", "version")) -> {
                    ParsedImport(
                        format = SessionBackupManager.BackupFormat.PYROGRAM,
                        payload = parsePyrogram(db),
                    )
                }
                tableNames.containsAll(listOf("sessions", "entities", "sent_files", "update_state", "version")) -> {
                    ParsedImport(
                        format = SessionBackupManager.BackupFormat.TELETHON,
                        payload = parseTelethon(db),
                    )
                }
                else -> error("Файл .session не похож ни на Telethon, ни на Pyrogram")
            }
        }
    }

    private fun parseTelethon(database: SQLiteDatabase): TelegramSessionPayload {
        val authKeys = linkedMapOf<Int, String>()
        var baseDcId = 0
        database.rawQuery(
            "SELECT dc_id, auth_key FROM sessions ORDER BY dc_id ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dcId = cursor.getInt(0)
                val authKey = cursor.getBlob(1)
                if (authKey == null || authKey.isEmpty()) continue
                if (baseDcId == 0) baseDcId = dcId
                authKeys[dcId] = bytesToHexUpper(authKey)
            }
        }
        require(baseDcId in 1..5 && authKeys.isNotEmpty()) { "В Telethon session не найден auth key" }

        return TelegramSessionPayload(
            sourceFormat = SessionBackupManager.BackupFormat.TELETHON.wire,
            dcId = baseDcId,
            userId = null,
            authKeys = authKeys,
            serverSalts = authKeys.keys.associateWith { DEFAULT_SERVER_SALT },
            authKeyFingerprint = authKeys[baseDcId].orEmpty().take(8),
        ).normalized()
    }

    private fun parsePyrogram(database: SQLiteDatabase): TelegramSessionPayload {
        val authKeys = linkedMapOf<Int, String>()
        var baseDcId = 0
        var userId: Long? = null
        database.rawQuery(
            "SELECT dc_id, auth_key, user_id FROM sessions ORDER BY dc_id ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dcId = cursor.getInt(0)
                val authKey = cursor.getBlob(1)
                if (authKey == null || authKey.isEmpty()) continue
                if (baseDcId == 0) baseDcId = dcId
                val rowUserId = if (cursor.isNull(2)) null else cursor.getLong(2)
                if (userId == null && rowUserId != null && rowUserId > 0L) {
                    userId = rowUserId
                }
                authKeys[dcId] = bytesToHexUpper(authKey)
            }
        }
        require(baseDcId in 1..5 && authKeys.isNotEmpty()) { "В Pyrogram session не найден auth key" }

        return TelegramSessionPayload(
            sourceFormat = SessionBackupManager.BackupFormat.PYROGRAM.wire,
            dcId = baseDcId,
            userId = userId,
            authKeys = authKeys,
            serverSalts = authKeys.keys.associateWith { DEFAULT_SERVER_SALT },
            authKeyFingerprint = authKeys[baseDcId].orEmpty().take(8),
        ).normalized()
    }

    private fun writeTelethonSession(payload: TelegramSessionPayload, destination: File) {
        val normalized = payload.normalized()
        val authKey = hexToBytes(normalized.authKeys[normalized.dcId].orEmpty())
        destination.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(destination, null)
        database.use { db ->
            db.execSQL("CREATE TABLE entities (id integer primary key, hash integer not null, username text, phone integer, name text, date integer)")
            db.execSQL("CREATE TABLE sent_files (md5_digest blob, file_size integer, type integer, id integer, hash integer, primary key(md5_digest, file_size, type))")
            db.execSQL("CREATE TABLE sessions (dc_id integer primary key, server_address text, port integer, auth_key blob, takeout_id integer)")
            db.execSQL("CREATE TABLE update_state (id integer primary key, pts integer, qts integer, date integer, seq integer)")
            db.execSQL("CREATE TABLE version (version integer primary key)")

            db.insert("version", null, ContentValues().apply {
                put("version", TELETHON_VERSION)
            })

            db.insert("sessions", null, ContentValues().apply {
                put("dc_id", normalized.dcId)
                put("server_address", defaultDcAddress(normalized.dcId))
                put("port", 443)
                put("auth_key", authKey)
                putNull("takeout_id")
            })
        }
    }

    private fun writePyrogramSession(payload: TelegramSessionPayload, destination: File) {
        val normalized = payload.normalized()
        val authKey = hexToBytes(normalized.authKeys[normalized.dcId].orEmpty())
        val userId = normalized.userId ?: error("Для экспорта Pyrogram нужен userId")
        destination.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(destination, null)
        database.use { db ->
            db.execSQL("CREATE TABLE sessions (dc_id INTEGER PRIMARY KEY, test_mode INTEGER, auth_key BLOB, date INTEGER NOT NULL, user_id INTEGER, is_bot INTEGER)")
            db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY, access_hash INTEGER, type INTEGER NOT NULL, username TEXT, phone_number TEXT, last_update_on INTEGER NOT NULL DEFAULT (CAST(STRFTIME('%s', 'now') AS INTEGER)))")
            db.execSQL("CREATE TABLE version (number INTEGER PRIMARY KEY)")

            db.insert("version", null, ContentValues().apply {
                put("number", PYROGRAM_VERSION)
            })

            db.insert("sessions", null, ContentValues().apply {
                put("dc_id", normalized.dcId)
                put("test_mode", 0)
                put("auth_key", authKey)
                put("date", normalized.updatedAt / 1000L)
                put("user_id", userId)
                put("is_bot", 0)
            })
        }
    }

    private fun parseTDataZip(sourceFile: File): TelegramSessionPayload {
        val zipFile = ZipFile(sourceFile)
        zipFile.use { zip ->
            val files = linkedMapOf<String, ByteArray>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val normalized = normalizeZipEntry(entry.name) ?: continue
                files[normalized] = zip.getInputStream(entry).use { it.readBytes() }
            }

            val keyDataBody = readTdfFile(files["key_datas"] ?: error("В tdata не найден key_datas"))
            val keyDataReader = QtReader(keyDataBody)
            val salt = keyDataReader.qByteArray()
            val keyEncrypted = keyDataReader.qByteArray()
            val infoEncrypted = keyDataReader.qByteArray()

            val passcodeKey = createLocalKey(salt, "")
            val localKey = decryptLocal(keyEncrypted, passcodeKey)
            val infoReader = QtReader(decryptLocal(infoEncrypted, localKey))
            val count = infoReader.int32().coerceAtLeast(1)
            val order = buildList(count) {
                repeat(count) {
                    add(infoReader.int32())
                }
            }
            val activeOrderIndex = infoReader.int32().coerceAtLeast(0)
            val accountIdx = order.getOrNull(activeOrderIndex)?.coerceAtLeast(0) ?: 0
            val dataName = if (accountIdx == 0) "data" else "data#${accountIdx + 1}"
            val dataKeyName = toFilePart(md5(dataName.toByteArray(Charsets.UTF_8)).copyOfRange(0, 8))
            val authBody = readTdfFile(files["${dataKeyName}s"] ?: error("В tdata не найден файл авторизации"))
            val authContainerReader = QtReader(authBody)
            val authEncrypted = authContainerReader.qByteArray()
            val authPlain = decryptLocal(authEncrypted, localKey)

            val settingsReader = QtReader(authPlain)
            var mtpAuthorization: ByteArray? = null
            while (!settingsReader.ended) {
                val blockId = settingsReader.uint32()
                val value = settingsReader.qByteArray()
                if (blockId == SETTINGS_BLOCK_MTP_AUTHORIZATION) {
                    mtpAuthorization = value
                    break
                }
            }

            val mtpData = mtpAuthorization ?: error("В tdata не найден блок mtpAuthorization")
            val mtpReader = QtReader(mtpData)
            val legacyUserId = mtpReader.int32()
            val legacyMainDcId = mtpReader.int32()

            val userId: Long
            val mainDcId: Int
            if (legacyUserId == -1 && legacyMainDcId == -1) {
                userId = mtpReader.int64()
                mainDcId = mtpReader.int32()
            } else {
                userId = legacyUserId.toLong()
                mainDcId = legacyMainDcId
            }

            val authKeys = linkedMapOf<Int, String>()
            repeat(mtpReader.uint32()) {
                val dcId = mtpReader.int32()
                authKeys[dcId] = bytesToHexUpper(mtpReader.raw(256))
            }

            repeat(mtpReader.uint32()) {
                mtpReader.int32()
                mtpReader.raw(256)
            }

            return TelegramSessionPayload(
                sourceFormat = SessionBackupManager.BackupFormat.TDATA.wire,
                dcId = mainDcId,
                userId = userId,
                authKeys = authKeys,
                serverSalts = authKeys.keys.associateWith { DEFAULT_SERVER_SALT },
                authKeyFingerprint = authKeys[mainDcId].orEmpty().take(8),
            ).normalized()
        }
    }

    private fun writeTDataZip(payload: TelegramSessionPayload, output: OutputStream) {
        val normalized = payload.normalized()
        val userId = normalized.userId ?: error("Для экспорта TData нужен userId")
        val localKey = ByteArray(256).also(secureRandom::nextBytes)
        val dataName = md5("data".toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)
        val dataPart = toFilePart(dataName)

        val keyDataFile = buildKeyDataFile(localKey)
        val mtpAuthorizationFile = buildMtpAuthorizationFile(normalized, userId, localKey)
        val mapFile = buildEmptyMapFile(localKey)

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("tdata/"))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("tdata/key_datas"))
            zip.write(keyDataFile)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("tdata/${dataPart}s"))
            zip.write(mtpAuthorizationFile)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("tdata/$dataPart/"))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("tdata/$dataPart/maps"))
            zip.write(mapFile)
            zip.closeEntry()
        }
    }

    private fun buildKeyDataFile(localKey: ByteArray): ByteArray {
        val infoWriter = QtWriter()
        infoWriter.int32(1)
        infoWriter.int32(0)
        infoWriter.int32(0)
        val infoEncrypted = encryptLocal(infoWriter.result(), localKey)

        val salt = ByteArray(32).also(secureRandom::nextBytes)
        val passcodeKey = createLocalKey(salt, "")
        val keyEncrypted = encryptLocal(localKey, passcodeKey)

        val writer = QtWriter()
        writer.qByteArray(salt)
        writer.qByteArray(keyEncrypted)
        writer.qByteArray(infoEncrypted)
        return writeTdfFile(writer.result())
    }

    private fun buildMtpAuthorizationFile(
        payload: TelegramSessionPayload,
        userId: Long,
        localKey: ByteArray,
    ): ByteArray {
        val authWriter = QtWriter()
        authWriter.int32(-1)
        authWriter.int32(-1)
        authWriter.int64(userId)
        authWriter.int32(payload.dcId)
        authWriter.int32(payload.authKeys.size)
        payload.authKeys.toSortedMap().forEach { (dcId, authKeyHex) ->
            authWriter.int32(dcId)
            authWriter.raw(hexToBytes(authKeyHex))
        }
        authWriter.int32(0)

        val settingsWriter = QtWriter()
        settingsWriter.uint32(SETTINGS_BLOCK_MTP_AUTHORIZATION)
        settingsWriter.qByteArray(authWriter.result())

        val encrypted = encryptLocal(settingsWriter.result(), localKey)
        val container = QtWriter()
        container.qByteArray(encrypted)
        return writeTdfFile(container.result())
    }

    private fun buildEmptyMapFile(localKey: ByteArray): ByteArray {
        val encrypted = encryptLocal(ByteArray(0), localKey)
        val writer = QtWriter()
        writer.qByteArray(ByteArray(0))
        writer.qByteArray(ByteArray(0))
        writer.qByteArray(encrypted)
        return writeTdfFile(writer.result())
    }

    private fun normalizeZipEntry(raw: String): String? {
        val normalized = raw.replace('\\', '/').trim('/')
        if (normalized.isBlank() || normalized.contains("..")) return null
        return if (normalized.startsWith("tdata/")) normalized.removePrefix("tdata/") else normalized
    }

    private fun readTdfFile(bytes: ByteArray): ByteArray {
        require(bytes.size >= 24) { "Повреждённый TData файл" }
        require(bytes.copyOfRange(0, 4).contentEquals(TELEGRAM_DESKTOP_MAGIC)) { "Некорректный magic TData" }
        val version = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(version > 0) { "Некорректная версия TData" }
        val body = bytes.copyOfRange(8, bytes.size - 16)
        val expectedMd5 = bytes.copyOfRange(bytes.size - 16, bytes.size)
        val versionBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(version).array()
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()
        val actualMd5 = md5(body + sizeBytes + versionBytes + TELEGRAM_DESKTOP_MAGIC)
        require(expectedMd5.contentEquals(actualMd5)) { "TData checksum mismatch" }
        return body
    }

    private fun writeTdfFile(body: ByteArray): ByteArray {
        val versionBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(TELEGRAM_DESKTOP_VERSION).array()
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()
        val md5 = md5(body + sizeBytes + versionBytes + TELEGRAM_DESKTOP_MAGIC)
        return TELEGRAM_DESKTOP_MAGIC + versionBytes + body + md5
    }

    private fun createLocalKey(salt: ByteArray, passcode: String): ByteArray {
        val passBytes = passcode.toByteArray(Charsets.UTF_8)
        val hash = sha512(salt + passBytes + salt)
        val iterations = if (passcode.isEmpty()) 1 else 100_000
        return pbkdf2(hash, salt, iterations, 256, "HmacSHA512")
    }

    private fun decryptLocal(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(encrypted.size >= 16) { "Encrypted blob is too short" }
        val msgKey = encrypted.copyOfRange(0, 16)
        val encryptedData = encrypted.copyOfRange(16, encrypted.size)
        val (aesKey, aesIv) = deriveAesOld(key, msgKey, client = false)
        val decrypted = aesIgeDecrypt(encryptedData, aesKey, aesIv)
        require(sha1(decrypted).copyOfRange(0, 16).contentEquals(msgKey)) { "Failed to decrypt TData blob" }
        val dataLength = ByteBuffer.wrap(decrypted, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        require(dataLength in 4..decrypted.size) { "Invalid TData payload length" }
        return decrypted.copyOfRange(4, dataLength)
    }

    private fun encryptLocal(data: ByteArray, key: ByteArray): ByteArray {
        val dataSize = data.size + 4
        val paddingLength = (16 - (dataSize % 16)) % 16
        val padding = ByteArray(paddingLength).also(secureRandom::nextBytes)
        val plain = ByteArray(dataSize + padding.size)
        ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(dataSize)
        data.copyInto(plain, destinationOffset = 4)
        padding.copyInto(plain, destinationOffset = 4 + data.size)

        val msgKey = sha1(plain).copyOfRange(0, 16)
        val (aesKey, aesIv) = deriveAesOld(key, msgKey, client = false)
        return msgKey + aesIgeEncrypt(plain, aesKey, aesIv)
    }

    private fun deriveAesOld(authKey: ByteArray, msgKey: ByteArray, client: Boolean): Pair<ByteArray, ByteArray> {
        val x = if (client) 0 else 8
        val sha1a = sha1(msgKey + authKey.copyOfRange(x, x + 32))
        val sha1b = sha1(authKey.copyOfRange(32 + x, 48 + x) + msgKey + authKey.copyOfRange(48 + x, 64 + x))
        val sha1c = sha1(authKey.copyOfRange(64 + x, 96 + x) + msgKey)
        val sha1d = sha1(msgKey + authKey.copyOfRange(96 + x, 128 + x))

        val aesKey = sha1a.copyOfRange(0, 8) + sha1b.copyOfRange(8, 20) + sha1c.copyOfRange(4, 16)
        val aesIv = sha1a.copyOfRange(8, 20) + sha1b.copyOfRange(0, 8) + sha1c.copyOfRange(16, 20) + sha1d.copyOfRange(0, 8)
        return aesKey to aesIv
    }

    private fun aesIgeEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "IGE encryption requires 16-byte blocks" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(data.size)
        var prevC = iv.copyOfRange(0, 16)
        var prevP = iv.copyOfRange(16, 32)
        var offset = 0
        while (offset < data.size) {
            val plainBlock = data.copyOfRange(offset, offset + 16)
            val xored = xor16(plainBlock, prevC)
            val encryptedBlock = cipher.doFinal(xored)
            val cipherBlock = xor16(encryptedBlock, prevP)
            cipherBlock.copyInto(out, destinationOffset = offset)
            prevC = cipherBlock
            prevP = plainBlock
            offset += 16
        }
        return out
    }

    private fun aesIgeDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "IGE decryption requires 16-byte blocks" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(data.size)
        var prevC = iv.copyOfRange(0, 16)
        var prevP = iv.copyOfRange(16, 32)
        var offset = 0
        while (offset < data.size) {
            val cipherBlock = data.copyOfRange(offset, offset + 16)
            val xored = xor16(cipherBlock, prevP)
            val decryptedBlock = cipher.doFinal(xored)
            val plainBlock = xor16(decryptedBlock, prevC)
            plainBlock.copyInto(out, destinationOffset = offset)
            prevC = cipherBlock
            prevP = plainBlock
            offset += 16
        }
        return out
    }

    private fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
        algorithm: String,
    ): ByteArray {
        val macLength = Mac.getInstance(algorithm).macLength
        val blocks = (keyLengthBytes + macLength - 1) / macLength
        val output = ByteArrayOutputStream(blocks * macLength)
        for (blockIndex in 1..blocks) {
            output.write(pbkdf2Block(password, salt, iterations, blockIndex, algorithm))
        }
        return output.toByteArray().copyOf(keyLengthBytes)
    }

    private fun pbkdf2Block(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        blockIndex: Int,
        algorithm: String,
    ): ByteArray {
        val blockIndexBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(blockIndex).array()
        var u = hmac(password, salt + blockIndexBytes, algorithm)
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = hmac(password, u, algorithm)
            for (index in result.indices) {
                result[index] = (result[index].toInt() xor u[index].toInt()).toByte()
            }
        }
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray, algorithm: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    private fun md5(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(data)
    }

    private fun sha1(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-1").digest(data)
    }

    private fun sha512(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-512").digest(data)
    }

    private fun xor16(left: ByteArray, right: ByteArray): ByteArray {
        val out = ByteArray(16)
        for (index in 0 until 16) {
            out[index] = (left[index].toInt() xor right[index].toInt()).toByte()
        }
        return out
    }

    private fun defaultDcAddress(dcId: Int): String {
        return when (dcId) {
            1 -> "149.154.175.53"
            2 -> "149.154.167.51"
            3 -> "149.154.175.100"
            4 -> "149.154.167.91"
            5 -> "91.108.56.130"
            else -> "149.154.167.51"
        }
    }

    private fun bytesToHexUpper(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789ABCDEF"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(chars)
    }

    private fun toFilePart(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = digits[value and 0x0F]
            chars[index * 2 + 1] = digits[(value ushr 4) and 0x0F]
        }
        return String(chars)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val normalized = normalizeSessionHex(hex)
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private class QtReader(
        private val buffer: ByteArray,
    ) {
        var position: Int = 0
            private set

        val ended: Boolean
            get() = position >= buffer.size

        fun int32(): Int {
            val value = ByteBuffer.wrap(buffer, position, 4).order(ByteOrder.BIG_ENDIAN).int
            position += 4
            return value
        }

        fun uint32(): Int {
            val value = ByteBuffer.wrap(buffer, position, 4).order(ByteOrder.BIG_ENDIAN).int
            position += 4
            return value
        }

        fun int64(): Long {
            val value = ByteBuffer.wrap(buffer, position, 8).order(ByteOrder.BIG_ENDIAN).long
            position += 8
            return value
        }

        fun raw(length: Int): ByteArray {
            val value = buffer.copyOfRange(position, position + length)
            position += length
            return value
        }

        fun qByteArray(): ByteArray {
            val length = uint32()
            if (length == 0 || length == -1) {
                return ByteArray(0)
            }
            require(length >= 0 && position + length <= buffer.size) { "Invalid QByteArray length" }
            return raw(length)
        }
    }

    private class QtWriter {
        private val output = ByteArrayOutputStream()

        fun int32(value: Int) {
            output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        }

        fun uint32(value: Int) {
            int32(value)
        }

        fun int64(value: Long) {
            output.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
        }

        fun raw(value: ByteArray) {
            output.write(value)
        }

        fun qByteArray(value: ByteArray) {
            uint32(value.size)
            raw(value)
        }

        fun result(): ByteArray = output.toByteArray()
    }

    private const val SETTINGS_BLOCK_MTP_AUTHORIZATION = 0x4B
}
