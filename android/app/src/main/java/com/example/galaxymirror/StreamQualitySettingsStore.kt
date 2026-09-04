package com.example.galaxymirror

import android.content.Context
import androidx.core.content.edit

class StreamQualitySettingsStore(
  private val store: KeyValueStore,
) {
  fun readMode(): StreamQualityMode =
    StreamQualityMode.fromWireValue(store.getString(KEY_STREAM_QUALITY_MODE, StreamQualityMode.AUTO.wireValue))
      ?: StreamQualityMode.AUTO

  fun writeMode(mode: StreamQualityMode) {
    store.putString(KEY_STREAM_QUALITY_MODE, mode.wireValue)
  }

  interface KeyValueStore {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
  }

  class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val preferences =
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String, defaultValue: String): String =
      preferences.getString(key, defaultValue) ?: defaultValue

    override fun putString(key: String, value: String) {
      preferences.edit { putString(key, value) }
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "stream_quality_settings"
    const val KEY_STREAM_QUALITY_MODE = "stream_quality_mode"
  }
}
