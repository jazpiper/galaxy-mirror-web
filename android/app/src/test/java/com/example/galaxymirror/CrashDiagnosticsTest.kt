package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashDiagnosticsTest {
    @Test
    fun recordUnhandledExceptionMakesCrashReadable() {
        val dir = Files.createTempDirectory("galaxy-crash-test").toFile()

        CrashDiagnostics.recordUnhandledException(
            dir = dir,
            threadName = "CaptureThread",
            throwable = IllegalStateException("projection token reused")
        )

        val report = CrashDiagnostics.readDebugReport(dir)

        assertTrue(report.contains("LAST UNHANDLED EXCEPTION"))
        assertTrue(report.contains("CaptureThread"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("projection token reused"))
    }

    @Test
    fun recentEventsAreIncludedAndClearRemovesCrash() {
        val dir = Files.createTempDirectory("galaxy-crash-clear-test").toFile()

        CrashDiagnostics.recordEvent(dir, "before startCapture")
        CrashDiagnostics.flushExecutorForTesting()
        CrashDiagnostics.recordUnhandledException(
            dir = dir,
            threadName = "main",
            throwable = RuntimeException("boom")
        )
        CrashDiagnostics.clearCrash(dir)

        val report = CrashDiagnostics.readDebugReport(dir)

        assertFalse(report.contains("LAST UNHANDLED EXCEPTION"))
        assertTrue(report.contains("No saved crash"))
        assertTrue(report.contains("before startCapture"))
    }
}
