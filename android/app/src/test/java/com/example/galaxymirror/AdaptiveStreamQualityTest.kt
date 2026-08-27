package com.example.galaxymirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    assertEquals(8_000_000, profile.maxBitrateBps)
  }

  @Test
  fun activeAutoCellularUsesStandardProfile() {
    val profile =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.AUTO,
        networkTransport = StreamNetworkTransport.CELLULAR,
        viewerActivity = ViewerActivityState.ACTIVE,
      )

    assertEquals(StreamQualityMode.STANDARD, profile.mode)
    assertEquals(24, profile.fps)
    assertEquals(4_000_000, profile.maxBitrateBps)
  }

  @Test
  fun activeAutoOtherUsesStandardProfile() {
    val profile =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.AUTO,
        networkTransport = StreamNetworkTransport.OTHER,
        viewerActivity = ViewerActivityState.ACTIVE,
      )

    assertEquals(StreamQualityMode.STANDARD, profile.mode)
    assertEquals(24, profile.fps)
    assertEquals(4_000_000, profile.maxBitrateBps)
  }

  @Test
  fun activeStateMatchesPolicyForAllCombinations() {
    for (mode in StreamQualityMode.entries) {
      for (transport in StreamNetworkTransport.entries) {
        val expected = StreamQualityPolicy.resolve(mode, transport)
        val actual =
          AdaptiveStreamQuality.resolve(
            selectedMode = mode,
            networkTransport = transport,
            viewerActivity = ViewerActivityState.ACTIVE,
          )

        assertEquals("Mismatch for mode=$mode, transport=$transport", expected, actual)
      }
    }
  }

  @Test
  fun idleStateClampsFpsAndBitrateForAllCombinations() {
    for (mode in StreamQualityMode.entries) {
      for (transport in StreamNetworkTransport.entries) {
        val active = StreamQualityPolicy.resolve(mode, transport)
        val idle =
          AdaptiveStreamQuality.resolve(
            selectedMode = mode,
            networkTransport = transport,
            viewerActivity = ViewerActivityState.IDLE,
          )

        assertEquals(active.mode, idle.mode)
        assertEquals(active.width, idle.width)
        assertEquals(active.height, idle.height)
        assertEquals(minOf(active.fps, 10), idle.fps)
        assertEquals(minOf(active.maxBitrateBps, 800_000), idle.maxBitrateBps)
      }
    }
  }

  @Test
  fun idleAutoWifiUsesLowerFpsAndBitrateThanActive() {
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
    assertEquals(10, idle.fps)
    assertEquals(800_000, idle.maxBitrateBps)
  }

  @Test
  fun idleDataSaverStaysBelowActiveDataSaver() {
    // DATA_SAVER is the lowest active tier (18fps / 1.5Mbps). The idle throttle must still
    // strictly reduce both fps and bitrate here, otherwise idle mode saves nothing for the
    // mode where users most expect it.
    val active =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.DATA_SAVER,
        networkTransport = StreamNetworkTransport.CELLULAR,
        viewerActivity = ViewerActivityState.ACTIVE,
      )
    val idle =
      AdaptiveStreamQuality.resolve(
        selectedMode = StreamQualityMode.DATA_SAVER,
        networkTransport = StreamNetworkTransport.CELLULAR,
        viewerActivity = ViewerActivityState.IDLE,
      )

    assertTrue(idle.fps < active.fps)
    assertTrue(idle.maxBitrateBps < active.maxBitrateBps)
    assertEquals(10, idle.fps)
    assertEquals(800_000, idle.maxBitrateBps)
  }
}
