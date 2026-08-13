package com.example.galaxymirror

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times

class BlackOverlayControllerTest {

    private lateinit var context: Context
    private lateinit var windowManager: WindowManager
    private lateinit var settingsMock: MockedStatic<Settings>
    private lateinit var controller: BlackOverlayController

    @Before
    fun setup() {
        context = mock(Context::class.java)
        windowManager = mock(WindowManager::class.java)
        whenever(context.getSystemService(Context.WINDOW_SERVICE)).thenReturn(windowManager)

        settingsMock = mockStatic(Settings::class.java)
        controller = BlackOverlayController(context)
    }

    @After
    fun teardown() {
        settingsMock.close()
    }

    @Test
    fun canDrawOverlays_returnsTrue_whenSDKisBelowM() {
        assertTrue(controller.canDrawOverlays())
    }

    @Test
    fun showOverlay_addsViewToWindowManager_whenPermissionGranted() {
        assertTrue(controller.showOverlay())
        assertTrue(controller.isShowing())
        verify(windowManager).addView(any(), any())
    }

    @Test
    fun showOverlay_returnsTrue_whenAlreadyShowing() {
        assertTrue(controller.showOverlay())
        assertTrue(controller.showOverlay())
        verify(windowManager, times(1)).addView(any(), any())
    }

    @Test
    fun hideOverlay_removesViewFromWindowManager_whenShowing() {
        controller.showOverlay()

        assertTrue(controller.hideOverlay())
        assertFalse(controller.isShowing())
        verify(windowManager).removeView(any())
    }

    @Test
    fun hideOverlay_returnsFalse_whenNotShowing() {
        assertFalse(controller.hideOverlay())
    }

    @Test
    fun showOverlay_returnsFalse_whenExceptionThrown() {
        whenever(windowManager.addView(any(), any())).thenThrow(RuntimeException("Test Exception"))

        assertFalse(controller.showOverlay())
        assertFalse(controller.isShowing())
    }

    @Test
    fun hideOverlay_returnsFalse_whenExceptionThrown() {
        controller.showOverlay()
        whenever(windowManager.removeView(any())).thenThrow(RuntimeException("Test Exception"))

        assertFalse(controller.hideOverlay())
        assertFalse(controller.isShowing())
    }
}
