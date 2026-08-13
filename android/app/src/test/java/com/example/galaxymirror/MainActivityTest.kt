package com.example.galaxymirror

import android.content.ComponentName
import android.os.Looper.getMainLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito.`when`

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun mainActivityLaunchesSuccessfully() {
        val mockBinder = mock(MediaProjectionService.LocalBinder::class.java)
        val mockService = mock(MediaProjectionService::class.java)
        `when`(mockBinder.getService()).thenReturn(mockService)
        `when`(mockService.serviceState).thenReturn(MutableStateFlow(MirrorServiceState()))

        val componentName = ComponentName("com.example.galaxymirror", "com.example.galaxymirror.MediaProjectionService")
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            componentName, mockBinder
        )

        val activityController = Robolectric.buildActivity(MainActivity::class.java)

        activityController.create().start().resume()
        shadowOf(getMainLooper()).idle()

        val activity = activityController.get()
        assertNotNull("Activity should not be null", activity)

        activityController.pause().stop().destroy()
    }
}
