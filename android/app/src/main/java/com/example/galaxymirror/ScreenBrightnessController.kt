package com.example.galaxymirror

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit

data class ScreenBrightnessSnapshot(
  val brightness: Int,
  val mode: Int,
)

enum class ScreenBrightnessResult {
  APPLIED,
  RESTORED,
  UNCHANGED,
  PERMISSION_REQUIRED,
  FAILED,
}

interface BrightnessSettingsAccess {
  fun canWriteSystemSettings(): Boolean
  fun readBrightness(): Int
  fun readBrightnessMode(): Int
  fun writeBrightness(value: Int): Boolean
  fun writeBrightnessMode(value: Int): Boolean
}

interface BrightnessRestoreStore {
  fun readSnapshot(): ScreenBrightnessSnapshot?
  fun saveSnapshot(snapshot: ScreenBrightnessSnapshot)
  fun clearSnapshot()
}

class ScreenBrightnessController(
  private val settingsAccess: BrightnessSettingsAccess,
  private val restoreStore: BrightnessRestoreStore,
) {
  constructor(context: Context) : this(
    AndroidBrightnessSettingsAccess(context.applicationContext),
    SharedPreferencesBrightnessRestoreStore(context.applicationContext),
  )

  fun canWriteSystemSettings(): Boolean = settingsAccess.canWriteSystemSettings()

  fun applyForMirroring(
    settings: ScreenAwakeSettings,
    isMirroringActive: Boolean,
  ): ScreenBrightnessResult {
    return if (settings.shouldMinimizeBrightness(isMirroringActive)) {
      minimizeBrightness()
    } else {
      restoreIfNeeded()
    }
  }

  private fun minimizeBrightness(): ScreenBrightnessResult {
    if (!settingsAccess.canWriteSystemSettings()) return ScreenBrightnessResult.PERMISSION_REQUIRED

    return try {
      if (restoreStore.readSnapshot() == null) {
        restoreStore.saveSnapshot(
          ScreenBrightnessSnapshot(
            brightness = settingsAccess.readBrightness().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
            mode = settingsAccess.readBrightnessMode(),
          ),
        )
      }

      val modeWritten = settingsAccess.writeBrightnessMode(MANUAL_BRIGHTNESS_MODE)
      val brightnessWritten = settingsAccess.writeBrightness(MIN_BRIGHTNESS)
      if (modeWritten && brightnessWritten) {
        ScreenBrightnessResult.APPLIED
      } else {
        ScreenBrightnessResult.FAILED
      }
    } catch (_: SecurityException) {
      ScreenBrightnessResult.PERMISSION_REQUIRED
    } catch (_: Exception) {
      ScreenBrightnessResult.FAILED
    }
  }

  private fun restoreIfNeeded(): ScreenBrightnessResult {
    val snapshot = restoreStore.readSnapshot() ?: return ScreenBrightnessResult.UNCHANGED
    if (!settingsAccess.canWriteSystemSettings()) return ScreenBrightnessResult.PERMISSION_REQUIRED

    return try {
      val brightnessWritten = settingsAccess.writeBrightness(snapshot.brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS))
      val modeWritten = settingsAccess.writeBrightnessMode(snapshot.mode)
      if (brightnessWritten && modeWritten) {
        restoreStore.clearSnapshot()
        ScreenBrightnessResult.RESTORED
      } else {
        ScreenBrightnessResult.FAILED
      }
    } catch (_: SecurityException) {
      ScreenBrightnessResult.PERMISSION_REQUIRED
    } catch (_: Exception) {
      ScreenBrightnessResult.FAILED
    }
  }

  companion object {
    const val MIN_BRIGHTNESS = 10
    const val MAX_BRIGHTNESS = 255
    const val MANUAL_BRIGHTNESS_MODE = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
  }
}

private class AndroidBrightnessSettingsAccess(
  private val context: Context,
) : BrightnessSettingsAccess {
  private val resolver = context.contentResolver

  override fun canWriteSystemSettings(): Boolean = Settings.System.canWrite(context)

  override fun readBrightness(): Int =
    Settings.System.getInt(
      resolver,
      Settings.System.SCREEN_BRIGHTNESS,
      DEFAULT_BRIGHTNESS,
    )

  override fun readBrightnessMode(): Int =
    Settings.System.getInt(
      resolver,
      Settings.System.SCREEN_BRIGHTNESS_MODE,
      Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    )

  override fun writeBrightness(value: Int): Boolean =
    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)

  override fun writeBrightnessMode(value: Int): Boolean =
    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, value)

  private companion object {
    const val DEFAULT_BRIGHTNESS = 128
  }
}

private class SharedPreferencesBrightnessRestoreStore(
  context: Context,
) : BrightnessRestoreStore {
  private val preferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun readSnapshot(): ScreenBrightnessSnapshot? {
    if (!preferences.getBoolean(KEY_HAS_SNAPSHOT, false)) return null
    return ScreenBrightnessSnapshot(
      brightness = preferences.getInt(KEY_BRIGHTNESS, ScreenBrightnessController.MAX_BRIGHTNESS),
      mode = preferences.getInt(KEY_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL),
    )
  }

  override fun saveSnapshot(snapshot: ScreenBrightnessSnapshot) {
    preferences.edit {
      putBoolean(KEY_HAS_SNAPSHOT, true)
      putInt(KEY_BRIGHTNESS, snapshot.brightness)
      putInt(KEY_MODE, snapshot.mode)
    }
  }

  override fun clearSnapshot() {
    preferences.edit {
      remove(KEY_HAS_SNAPSHOT)
      remove(KEY_BRIGHTNESS)
      remove(KEY_MODE)
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "screen_brightness_restore"
    const val KEY_HAS_SNAPSHOT = "has_snapshot"
    const val KEY_BRIGHTNESS = "brightness"
    const val KEY_MODE = "mode"
  }
}
