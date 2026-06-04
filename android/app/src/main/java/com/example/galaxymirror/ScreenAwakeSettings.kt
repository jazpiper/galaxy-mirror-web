package com.example.galaxymirror

data class ScreenAwakeSettings(
  val keepScreenAwakeDuringMirroring: Boolean = false,
  val minimizeBrightnessDuringMirroring: Boolean = false,
) {
  fun shouldKeepScreenAwake(isMirroringActive: Boolean): Boolean =
    isMirroringActive && keepScreenAwakeDuringMirroring

  fun shouldMinimizeBrightness(isMirroringActive: Boolean): Boolean =
    isMirroringActive && minimizeBrightnessDuringMirroring
}
