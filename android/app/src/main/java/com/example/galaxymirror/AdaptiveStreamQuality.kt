package com.example.galaxymirror

enum class ViewerActivityState {
  ACTIVE,
  IDLE,
}

object AdaptiveStreamQuality {
  fun resolve(
    selectedMode: StreamQualityMode,
    networkTransport: StreamNetworkTransport,
    viewerActivity: ViewerActivityState,
  ): StreamQualityProfile {
    val active = StreamQualityPolicy.resolve(selectedMode, networkTransport)
    if (viewerActivity == ViewerActivityState.ACTIVE) return active

    return active.copy(
      width = minOf(active.width, 540),
      height = minOf(active.height, 1200),
      fps = minOf(active.fps, 5),
      maxBitrateBps = minOf(active.maxBitrateBps, 350_000),
    )
  }
}
