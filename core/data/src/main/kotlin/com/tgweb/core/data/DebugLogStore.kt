package com.tgweb.core.data

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DebugLogStore {
    private const val MAX_LINES = 10_000
    private const val MAX_DUMP_CHARS = 1_000_000
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES + 32)
    @Volatile
    private var uncaughtInstalled = false

    fun log(tag: String, message: String) {
        val safeTag = tag.trim().ifBlank { "LOG" }.uppercase(Locale.US)
        val safeMessage = message.trim().ifBlank { "-" }
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val thread = Thread.currentThread().name
        val line = "[$timestamp][$safeTag][$thread] $safeMessage"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun logError(tag: String, message: String, error: Throwable?) {
        if (error == null) {
            log(tag, message)
            return
        }
        val cause = "${error::class.java.simpleName}: ${error.message}"
        log(tag, "$message; $cause")
    }

    fun installUncaughtHandler() {
        if (uncaughtInstalled) return
        synchronized(lock) {
            if (uncaughtInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logError(
                    "CRASH",
                    "Uncaught exception on thread=${thread.name}",
                    throwable,
                )
                previous?.uncaughtException(thread, throwable)
            }
            uncaughtInstalled = true
            log("DEBUG", "Global uncaught exception handler installed")
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
