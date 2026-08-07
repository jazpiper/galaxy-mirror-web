package com.example.galaxymirror

import android.content.Context
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

class CrashDiagnosticsTest {
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setup() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun teardown() {
        if (originalHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
        // Reset the installed flag via reflection for testing
        val installedField = CrashDiagnostics::class.java.getDeclaredField("installed")
        installedField.isAccessible = true
        (installedField.get(CrashDiagnostics) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

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

    @Test
    fun `install sets uncaught exception handler and delegates to previous handler`() {
        val mockContext = mock(Context::class.java)
        val tempDir = Files.createTempDirectory("galaxy-crash-install-test").toFile()
        `when`(mockContext.filesDir).thenReturn(tempDir)

        val mockHandler = mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(mockHandler)

        CrashDiagnostics.install(mockContext)

        val newHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(newHandler != mockHandler)

        val throwable = RuntimeException("Test crash")
        newHandler?.uncaughtException(Thread.currentThread(), throwable)

        verify(mockHandler).uncaughtException(Thread.currentThread(), throwable)
    }

    @Test
    fun `uncaught exception handler delegates to previous handler even if diagnostic gathering fails`() {
        val mockContext = mock(Context::class.java)
        // Provide a directory path that does not exist and cannot be created to force IOException on write
        val readOnlyDir = File("/proc/invalid/path/that/does/not/exist")
        `when`(mockContext.filesDir).thenReturn(readOnlyDir)

        val mockHandler = mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(mockHandler)

        CrashDiagnostics.install(mockContext)

        val newHandler = Thread.getDefaultUncaughtExceptionHandler()

        val throwable = RuntimeException("Test crash that fails to write")
        try {
            newHandler?.uncaughtException(Thread.currentThread(), throwable)
        } catch (e: Exception) {
            // The exception from recordUnhandledException is expected to propagate
        }

        // Even though diagnostic gathering failed and threw, the finally block ensures delegation
        verify(mockHandler).uncaughtException(Thread.currentThread(), throwable)
    }
}
