package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class AdaptiveStreamQualityTest {
  @Test
  fun activeAutoWifiUsesHighProfile() {
    val profile =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.AUTO,
        networkTransport = StreamNetworkTransport.WIFI,
        viewerActivity = ViewerActivityState.ACTIVE,
      )

    assertEquals(StreamQualityMode.HIGH, profile.mode)
    assertEquals(30, profile.fps)
  }

  @Test
  fun idleAutoWifiUsesLowerFpsThanActive() {
    val active =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.AUTO,
        networkTransport = StreamNetworkTransport.WIFI,
        viewerActivity = ViewerActivityState.ACTIVE,
      )
    val idle =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.AUTO,
        networkTransport = StreamNetworkTransport.WIFI,
        viewerActivity = ViewerActivityState.IDLE,
      )

    assertTrue(idle.fps < active.fps)
    assertTrue(idle.maxBitrateBps < active.maxBitrateBps)
  }
}
