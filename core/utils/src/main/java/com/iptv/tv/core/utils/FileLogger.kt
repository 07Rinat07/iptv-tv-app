package com.iptv.tv.core.utils

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object FileLogger {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private val lock = ReentrantLock()

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    fun logDir(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun logFile(context: Context): File {
        val dir = logDir(context)
        return File(dir, LOG_FILE)
    }

    /**
     * Returns a bounded tail of both the rotated and current process logs. Diagnostics export uses
     * this instead of copying only app.log so an uncaught exception remains available after a
     * rotation and after the application process is started again.
     */
    fun readRecent(context: Context, maxBytes: Int = 256_000): String {
        return readRecentFromDirectory(logDir(context), maxBytes)
    }

    internal fun readRecentFromDirectory(directory: File, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        return try {
            lock.withLock {
                val files = listOf(File(directory, "$LOG_FILE.1"), File(directory, LOG_FILE))
                    .filter { it.isFile && it.length() > 0L }
                if (files.isEmpty()) return@withLock ""

                val perFileLimit = (maxBytes / files.size).coerceAtLeast(1)
                buildString {
                    files.forEach { file ->
                        append("--- ")
                        append(file.name)
                        append(" (tail) ---\n")
                        append(readTail(file, perFileLimit))
                        if (isNotEmpty() && this[lastIndex] != '\n') append('\n')
                    }
                }
            }
        } catch (ignored: Exception) {
            ""
        }
    }

    fun write(context: Context, level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            lock.withLock {
                val file = logFile(context)
                FileWriter(file, true).use { fw ->
                    PrintWriter(fw).use { pw ->
                        pw.print("[")
                        pw.print(timestamp())
                        pw.print("] ")
                        pw.print(level)
                        pw.print("/")
                        pw.print(tag)
                        pw.print(": ")
                        pw.println(message.redactSensitiveLogData())
                        throwable?.let { t ->
                            pw.print(t.toSanitizedStackTrace())
                        }
                    }
                }
                rotateIfNeeded(file)
            }
        } catch (ignored: Exception) {
            // best-effort logging; don't crash app due to logger
        }
    }

    private fun rotateIfNeeded(file: File) {
        try {
            val maxSize = 1_000_000 // 1 MB
            if (file.length() > maxSize) {
                val old = File(file.parentFile, "app.log.1")
                if (old.exists()) old.delete()
                file.renameTo(old)
            }
        } catch (ignored: Exception) {}
    }

    private fun readTail(file: File, maxBytes: Int): String {
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val byteCount = minOf(length, maxBytes.toLong()).toInt()
            input.seek(length - byteCount)
            val bytes = ByteArray(byteCount)
            input.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun Throwable.toSanitizedStackTrace(): String {
        return StringWriter().use { sw ->
            PrintWriter(sw).use { pw ->
                printStackTrace(pw)
            }
            sw.toString().redactSensitiveLogData()
        }
    }

    private fun String.redactSensitiveLogData(): String {
        return replace(Regex("(?i)(password|passwd|pass|pwd|token|access_token|refresh_token|api_key|apikey|secret|key|mac|username|login|user)=([^\\s&]+)")) {
            "${it.groupValues[1]}=<redacted>"
        }.replace(Regex("(?i)(://)([^\\s:/?#]+):([^\\s@/?#]+)@")) {
            "${it.groupValues[1]}<redacted>:<redacted>@"
        }
    }
}
