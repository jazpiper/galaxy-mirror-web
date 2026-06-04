package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ScreenAwakeSettingsStoreTest {
  @Test
  fun readReturnsDefaultsWhenStoreIsEmpty() {
    val store = ScreenAwakeSettingsStore(FakeStore())

    val settings = store.read()

    assertFalse(settings.keepScreenAwakeDuringMirroring)
    assertFalse(settings.minimizeBrightnessDuringMirroring)
  }

  @Test
  fun writeAndReadRoundTripsSettings() {
    val store = ScreenAwakeSettingsStore(FakeStore())
    val expected =
      ScreenAwakeSettings(
        keepScreenAwakeDuringMirroring = true,
        minimizeBrightnessDuringMirroring = true,
      )

    store.write(expected)

    assertEquals(expected, store.read())
  }

  @Test
  fun legacyProtectionPreferenceMigratesToBrightnessMinimize() {
    val fakeStore = FakeStore()
    val store = ScreenAwakeSettingsStore(fakeStore)
    fakeStore.rawPutBoolean("mirror_protection_enabled", true)

    val settings = store.read()

    assertTrue(settings.minimizeBrightnessDuringMirroring)
    assertFalse(settings.keepScreenAwakeDuringMirroring)
  }

  private class FakeStore : ScreenAwakeSettingsStore.KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    fun rawPutBoolean(key: String, value: Boolean) {
      values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
      values[key] as? Boolean ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
      values[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int =
      values[key] as? Int ?: defaultValue

    override fun putInt(key: String, value: Int) {
      values[key] = value
    }
  }
}
