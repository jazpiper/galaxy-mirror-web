package com.example.galaxymirror

import android.content.Context
import androidx.core.content.edit

class ScreenAwakeSettingsStore(
  private val store: KeyValueStore,
) {
  fun read(): ScreenAwakeSettings {
    return ScreenAwakeSettings(
      keepScreenAwakeDuringMirroring =
        store.getBoolean(KEY_KEEP_SCREEN_AWAKE, ScreenAwakeSettings().keepScreenAwakeDuringMirroring),
      minimizeBrightnessDuringMirroring =
        store.getBoolean(
          KEY_MINIMIZE_BRIGHTNESS,
          store.getBoolean(KEY_LEGACY_MIRROR_PROTECTION_ENABLED, ScreenAwakeSettings().minimizeBrightnessDuringMirroring),
        ),
    )
  }

  fun write(settings: ScreenAwakeSettings) {
    store.putBoolean(KEY_KEEP_SCREEN_AWAKE, settings.keepScreenAwakeDuringMirroring)
    store.putBoolean(KEY_MINIMIZE_BRIGHTNESS, settings.minimizeBrightnessDuringMirroring)
    store.putBoolean(KEY_LEGACY_MIRROR_PROTECTION_ENABLED, false)
  }

  interface KeyValueStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
  }

  class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val preferences =
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
      preferences.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
      preferences.edit { putBoolean(key, value) }
    }

    override fun getInt(key: String, defaultValue: Int): Int =
      preferences.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) {
      preferences.edit { putInt(key, value) }
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "screen_awake_settings"
    const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake_during_mirroring"
    const val KEY_MINIMIZE_BRIGHTNESS = "minimize_brightness_during_mirroring"
    const val KEY_LEGACY_MIRROR_PROTECTION_ENABLED = "mirror_protection_enabled"
  }
}
