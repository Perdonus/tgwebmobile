package com.tgweb.core.data

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DebugLogStore {
    private const val MAX_LINES = 2000
    private const val MAX_DUMP_CHARS = 220_000
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES + 32)

    fun log(tag: String, message: String) {
        val safeTag = tag.trim().ifBlank { "LOG" }.uppercase(Locale.US)
        val safeMessage = message.trim().ifBlank { "-" }
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$timestamp][$safeTag] $safeMessage"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun dump(): String {
        synchronized(lock) {
            if (lines.isEmpty()) return "No logs yet."
            val full = lines.joinToString(separator = "\n")
            if (full.length <= MAX_DUMP_CHARS) return full
            return full.takeLast(MAX_DUMP_CHARS)
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
    }
}

