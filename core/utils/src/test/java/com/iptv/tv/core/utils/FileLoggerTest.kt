package com.iptv.tv.core.utils

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLoggerTest {

    @Test
    fun `recent export contains rotated crash and current process log`() {
        val directory = Files.createTempDirectory("file-logger-test").toFile()
        directory.resolve("app.log.1").writeText("old uncaught crash\n")
        directory.resolve("app.log").writeText("new process start\n")

        val result = FileLogger.readRecentFromDirectory(directory, maxBytes = 1_024)

        assertTrue(result.contains("old uncaught crash"))
        assertTrue(result.contains("new process start"))
        assertTrue(result.contains("app.log.1"))
        assertTrue(result.contains("app.log"))
    }

    @Test
    fun `recent export reads a bounded tail instead of the file prefix`() {
        val directory = Files.createTempDirectory("file-logger-tail-test").toFile()
        directory.resolve("app.log").writeText("prefix-that-must-be-dropped\n" + "z".repeat(64) + "tail")

        val result = FileLogger.readRecentFromDirectory(directory, maxBytes = 32)

        assertFalse(result.contains("prefix-that-must-be-dropped"))
        assertTrue(result.endsWith("tail\n"))
    }
}
