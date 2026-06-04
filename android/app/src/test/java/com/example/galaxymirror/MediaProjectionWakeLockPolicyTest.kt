package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MediaProjectionWakeLockPolicyTest {
  @Test
  fun wakeLockIsHeldOnlyWhenServiceRunsAndKeepAwakeIsEnabled() {
    assertTrue(
      MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
        serviceRunning = true,
        keepAwakeEnabled = true,
      ),
    )
    assertFalse(
      MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
        serviceRunning = false,
        keepAwakeEnabled = true,
      ),
    )
    assertFalse(
      MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
        serviceRunning = true,
        keepAwakeEnabled = false,
      ),
    )
  }
}
