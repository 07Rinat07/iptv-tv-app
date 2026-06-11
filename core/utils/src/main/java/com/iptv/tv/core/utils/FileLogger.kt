package com.iptv.tv.core.utils

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
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
                        pw.println(message)
                        throwable?.let { t ->
                            t.printStackTrace(pw)
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
}
