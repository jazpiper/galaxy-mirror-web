package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ScreenAwakeSettingsTest {
  @Test
  fun defaultsKeepCurrentBehaviorUntilUserEnablesSettings() {
    val settings = ScreenAwakeSettings()

    assertFalse(settings.keepScreenAwakeDuringMirroring)
    assertFalse(settings.minimizeBrightnessDuringMirroring)
    assertFalse(settings.shouldKeepScreenAwake(isMirroringActive = true))
    assertFalse(settings.shouldMinimizeBrightness(isMirroringActive = true))
  }

  @Test
  fun keepScreenAwakeOnlyAppliesWhileMirroring() {
    val settings = ScreenAwakeSettings(keepScreenAwakeDuringMirroring = true)

    assertTrue(settings.shouldKeepScreenAwake(isMirroringActive = true))
    assertFalse(settings.shouldKeepScreenAwake(isMirroringActive = false))
  }

  @Test
  fun brightnessMinimizeOnlyAppliesWhileMirroring() {
    val settings = ScreenAwakeSettings(minimizeBrightnessDuringMirroring = true)

    assertTrue(settings.shouldMinimizeBrightness(isMirroringActive = true))
    assertFalse(settings.shouldMinimizeBrightness(isMirroringActive = false))
  }
}
