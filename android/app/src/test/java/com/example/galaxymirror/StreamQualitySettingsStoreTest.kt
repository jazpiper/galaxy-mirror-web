package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class StreamQualitySettingsStoreTest {
  @Test
  fun readReturnsAutoWhenStoreIsEmpty() {
    val store = StreamQualitySettingsStore(FakeStore())

    assertEquals(StreamQualityMode.AUTO, store.readMode())
  }

  @Test
  fun writeAndReadRoundTripsSelectedMode() {
    val fakeStore = FakeStore()
    val store = StreamQualitySettingsStore(fakeStore)

    store.writeMode(StreamQualityMode.STANDARD)

    assertEquals(StreamQualityMode.STANDARD, store.readMode())
  }

  @Test
  fun readFallsBackToAutoForUnknownStoredValue() {
    val fakeStore = FakeStore()
    fakeStore.rawPutString("stream_quality_mode", "FAST")
    val store = StreamQualitySettingsStore(fakeStore)

    assertEquals(StreamQualityMode.AUTO, store.readMode())
  }

  private class FakeStore : StreamQualitySettingsStore.KeyValueStore {
    private val values = mutableMapOf<String, String>()

    fun rawPutString(key: String, value: String) {
      values[key] = value
    }

    override fun getString(key: String, defaultValue: String): String =
      values[key] ?: defaultValue

    override fun putString(key: String, value: String) {
      values[key] = value
    }
  }
}
