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
      fps = minOf(active.fps, 15),
      maxBitrateBps = minOf(active.maxBitrateBps, 1_500_000),
    )
  }
}
