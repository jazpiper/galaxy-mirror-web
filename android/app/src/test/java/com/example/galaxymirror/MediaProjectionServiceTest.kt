package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MediaProjectionServiceTest {
    @Test
    fun activityResultOkMinusOneIsAValidProjectionResultCode() {
        assertTrue(MediaProjectionService.isValidStartData(resultCode = -1, hasResultData = true))
    }

    @Test
    fun missingResultCodeAndMissingIntentAreRejected() {
        assertFalse(
            MediaProjectionService.isValidStartData(
                resultCode = MediaProjectionService.RESULT_CODE_MISSING,
                hasResultData = true
            )
        )
        assertFalse(MediaProjectionService.isValidStartData(resultCode = -1, hasResultData = false))
    }
}
