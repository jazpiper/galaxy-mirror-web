package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.json.JSONObject
import org.junit.Test

class StreamQualityPolicyTest {
  @Test
  fun autoModeUsesHighQualityOnWifiAndStandardOnCellular() {
    assertEquals(
      StreamQualityMode.HIGH,
      StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.WIFI).mode,
    )
    assertEquals(
      StreamQualityMode.STANDARD,
      StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.CELLULAR).mode,
    )
    assertEquals(
      StreamQualityMode.STANDARD,
      StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER).mode,
    )
  }

  @Test
  fun manualModeAlwaysWinsOverDetectedNetwork() {
    assertEquals(
      StreamQualityMode.STANDARD,
      StreamQualityPolicy.resolve(StreamQualityMode.STANDARD, StreamNetworkTransport.WIFI).mode,
    )
    assertEquals(
      StreamQualityMode.HIGH,
      StreamQualityPolicy.resolve(StreamQualityMode.HIGH, StreamNetworkTransport.CELLULAR).mode,
    )
    assertEquals(
      StreamQualityMode.DATA_SAVER,
      StreamQualityPolicy.resolve(StreamQualityMode.DATA_SAVER, StreamNetworkTransport.WIFI).mode,
    )
  }

  @Test
  fun profilesReduceCaptureWorkFromCurrentFullQuality() {
    val dataSaver = StreamQualityPolicy.resolve(StreamQualityMode.DATA_SAVER, StreamNetworkTransport.WIFI)
    val standard = StreamQualityPolicy.resolve(StreamQualityMode.STANDARD, StreamNetworkTransport.WIFI)
    val high = StreamQualityPolicy.resolve(StreamQualityMode.HIGH, StreamNetworkTransport.CELLULAR)

    assertEquals(540, dataSaver.width)
    assertEquals(1200, dataSaver.height)
    assertEquals(12, dataSaver.fps)
    assertEquals(600_000, dataSaver.maxBitrateBps)

    assertEquals(720, standard.width)
    assertEquals(1600, standard.height)
    assertEquals(15, standard.fps)
    assertEquals(1_200_000, standard.maxBitrateBps)

    assertEquals(1080, high.width)
    assertEquals(2400, high.height)
    assertEquals(30, high.fps)
    assertEquals(3_000_000, high.maxBitrateBps)
  }

  @Test
  fun codecParsesModeAndRejectsInvalidInput() {
    assertEquals(StreamQualityMode.HIGH, StreamQualityCodec.parseMode("""{"mode":"HIGH"}"""))
    assertEquals(StreamQualityMode.AUTO, StreamQualityCodec.parseMode("""{"mode":"auto"}"""))
    assertNull(StreamQualityCodec.parseMode("""{"mode":"FAST"}"""))
    assertNull(StreamQualityCodec.parseMode("""not-json"""))
  }

  @Test
  fun codecSerializesStatusForViewerUi() {
    val json =
      JSONObject(
        StreamQualityCodec.toStatusJson(
          selectedMode = StreamQualityMode.AUTO,
          networkTransport = StreamNetworkTransport.WIFI,
          profile = StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.WIFI),
        )
      )

    assertEquals("AUTO", json.getString("selectedMode"))
    assertEquals("WIFI", json.getString("networkTransport"))
    assertEquals("HIGH", json.getString("effectiveMode"))
    assertEquals(1080, json.getInt("width"))
    assertEquals(2400, json.getInt("height"))
    assertEquals(30, json.getInt("fps"))
    assertEquals(3_000_000, json.getInt("maxBitrateBps"))
    assertEquals("자동", json.getString("selectedLabel"))
    assertEquals("고화질", json.getString("effectiveLabel"))
  }
}
