package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.any
import java.nio.file.Files

@Suppress("UnspecifiedRegisterReceiverFlag")
class FavoriteAppsRepositoryTest {
    @Test
    fun launchFavorite_returnsFalse_whenStartActivityThrowsActivityNotFoundException() {
        val tempDir = Files.createTempDirectory("fav-app-repo-test").toFile()
        val mockContext = mock(Context::class.java)
        val mockPackageManager = mock(PackageManager::class.java)
        val mockSharedPreferences = mock(SharedPreferences::class.java)
        `when`(mockContext.filesDir).thenReturn(tempDir)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.registerReceiver(any(), any())).thenReturn(null)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.getString(anyString(), any())).thenReturn("[{\"packageName\":\"com.test.app\",\"label\":\"Test App\"}]")
        `when`(mockPackageManager.getLaunchIntentForPackage("com.test.app")).thenReturn(Intent())
        `when`(mockContext.startActivity(any())).thenThrow(ActivityNotFoundException("Mock exception"))

        val repo = FavoriteAppsRepository(mockContext)
        val result = repo.launchFavorite("com.test.app")

        assertFalse(result)

        CrashDiagnostics.flushExecutorForTesting()
        val report = CrashDiagnostics.readDebugReport(tempDir)
        assertTrue(report.contains("launch favorite com.test.app"))
        assertTrue(report.contains("ActivityNotFoundException"))
    }

    @Test
    fun launchFavorite_returnsFalse_whenPackageIsNotInFavorites() {
        val mockContext = mock(Context::class.java)
        val mockPackageManager = mock(PackageManager::class.java)
        val mockSharedPreferences = mock(SharedPreferences::class.java)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.registerReceiver(any(), any())).thenReturn(null)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        // No favorites configured
        `when`(mockSharedPreferences.getString(anyString(), any())).thenReturn(null)

        val repo = FavoriteAppsRepository(mockContext)
        val result = repo.launchFavorite("com.test.app")

        assertFalse(result)
        verify(mockPackageManager, never()).getLaunchIntentForPackage(anyString())
    }
}
