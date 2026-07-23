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

    // Idle throttle must stay strictly below every active tier so that idle genuinely
    // reduces data usage — including DATA_SAVER (18fps / 1.5Mbps), the lowest tier and the
    // one where a data-conscious user most wants the savings. Keep the ceilings under that.
    return active.copy(
      fps = minOf(active.fps, 10),
      maxBitrateBps = minOf(active.maxBitrateBps, 800_000),
    )
  }
}
