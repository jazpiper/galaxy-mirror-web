package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ScreenBrightnessControllerTest {
  @Test
  fun minimizeSavesCurrentBrightnessAndWritesMinimumManualBrightness() {
    val access = FakeBrightnessSettingsAccess(canWrite = true, brightness = 180, mode = 1)
    val store = FakeBrightnessRestoreStore()
    val controller = ScreenBrightnessController(access, store)

    val result =
      controller.applyForMirroring(
        settings = ScreenAwakeSettings(minimizeBrightnessDuringMirroring = true),
        isMirroringActive = true,
      )

    assertEquals(ScreenBrightnessResult.APPLIED, result)
    assertEquals(ScreenBrightnessController.MIN_BRIGHTNESS, access.brightness)
    assertEquals(ScreenBrightnessController.MANUAL_BRIGHTNESS_MODE, access.mode)
    assertEquals(ScreenBrightnessSnapshot(brightness = 180, mode = 1), store.snapshot)
  }

  @Test
  fun restoreUsesSavedSnapshotWhenMinimizeNoLongerApplies() {
    val access =
      FakeBrightnessSettingsAccess(
        canWrite = true,
        brightness = ScreenBrightnessController.MIN_BRIGHTNESS,
        mode = ScreenBrightnessController.MANUAL_BRIGHTNESS_MODE,
      )
    val store = FakeBrightnessRestoreStore(ScreenBrightnessSnapshot(brightness = 120, mode = 0))
    val controller = ScreenBrightnessController(access, store)

    val result =
      controller.applyForMirroring(
        settings = ScreenAwakeSettings(minimizeBrightnessDuringMirroring = true),
        isMirroringActive = false,
      )

    assertEquals(ScreenBrightnessResult.RESTORED, result)
    assertEquals(120, access.brightness)
    assertEquals(0, access.mode)
    assertNull(store.snapshot)
  }

  @Test
  fun permissionRequiredDoesNotWriteBrightness() {
    val access = FakeBrightnessSettingsAccess(canWrite = false, brightness = 200, mode = 1)
    val store = FakeBrightnessRestoreStore()
    val controller = ScreenBrightnessController(access, store)

    val result =
      controller.applyForMirroring(
        settings = ScreenAwakeSettings(minimizeBrightnessDuringMirroring = true),
        isMirroringActive = true,
      )

    assertEquals(ScreenBrightnessResult.PERMISSION_REQUIRED, result)
    assertEquals(200, access.brightness)
    assertEquals(1, access.mode)
    assertNull(store.snapshot)
    assertFalse(access.writeCalled)
  }

  private class FakeBrightnessSettingsAccess(
    private val canWrite: Boolean,
    var brightness: Int,
    var mode: Int,
  ) : BrightnessSettingsAccess {
    var writeCalled = false

    override fun canWriteSystemSettings(): Boolean = canWrite

    override fun readBrightness(): Int = brightness

    override fun readBrightnessMode(): Int = mode

    override fun writeBrightness(value: Int): Boolean {
      writeCalled = true
      brightness = value
      return true
    }

    override fun writeBrightnessMode(value: Int): Boolean {
      writeCalled = true
      mode = value
      return true
    }
  }

  private class FakeBrightnessRestoreStore(
    var snapshot: ScreenBrightnessSnapshot? = null,
  ) : BrightnessRestoreStore {
    override fun readSnapshot(): ScreenBrightnessSnapshot? = snapshot

    override fun saveSnapshot(snapshot: ScreenBrightnessSnapshot) {
      this.snapshot = snapshot
    }

    override fun clearSnapshot() {
      snapshot = null
    }
  }
}
