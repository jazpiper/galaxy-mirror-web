package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class StreamQualityTest {

  @Test
  fun streamQualityModeFromWireValueWorks() {
    assertEquals(StreamQualityMode.AUTO, StreamQualityMode.fromWireValue("AUTO"))
    assertEquals(StreamQualityMode.DATA_SAVER, StreamQualityMode.fromWireValue("DATA_SAVER"))
    assertEquals(StreamQualityMode.STANDARD, StreamQualityMode.fromWireValue("STANDARD"))
    assertEquals(StreamQualityMode.HIGH, StreamQualityMode.fromWireValue("HIGH"))
  }

  @Test
  fun streamQualityModeFromWireValueIgnoresCaseAndWhitespace() {
    assertEquals(StreamQualityMode.AUTO, StreamQualityMode.fromWireValue("auto"))
    assertEquals(StreamQualityMode.DATA_SAVER, StreamQualityMode.fromWireValue(" Data_Saver "))
    assertEquals(StreamQualityMode.STANDARD, StreamQualityMode.fromWireValue("  standard  "))
    assertEquals(StreamQualityMode.HIGH, StreamQualityMode.fromWireValue("\tHIGH\n"))
  }

  @Test
  fun streamQualityModeFromWireValueReturnsNullForInvalidOrNull() {
    assertNull(StreamQualityMode.fromWireValue("INVALID"))
    assertNull(StreamQualityMode.fromWireValue(""))
    assertNull(StreamQualityMode.fromWireValue("  "))
    assertNull(StreamQualityMode.fromWireValue(null))
  }

  @Test
  fun streamNetworkTransportFromWireValueWorks() {
    assertEquals(StreamNetworkTransport.WIFI, StreamNetworkTransport.fromWireValue("WIFI"))
    assertEquals(StreamNetworkTransport.CELLULAR, StreamNetworkTransport.fromWireValue("CELLULAR"))
    assertEquals(StreamNetworkTransport.OTHER, StreamNetworkTransport.fromWireValue("OTHER"))
  }

  @Test
  fun streamNetworkTransportFromWireValueIgnoresCaseAndWhitespace() {
    assertEquals(StreamNetworkTransport.WIFI, StreamNetworkTransport.fromWireValue("wifi"))
    assertEquals(StreamNetworkTransport.CELLULAR, StreamNetworkTransport.fromWireValue("  Cellular  "))
    assertEquals(StreamNetworkTransport.OTHER, StreamNetworkTransport.fromWireValue("\tother\n"))
  }

  @Test
  fun streamNetworkTransportFromWireValueReturnsNullForInvalidOrNull() {
    assertNull(StreamNetworkTransport.fromWireValue("UNKNOWN"))
    assertNull(StreamNetworkTransport.fromWireValue(""))
    assertNull(StreamNetworkTransport.fromWireValue("  "))
    assertNull(StreamNetworkTransport.fromWireValue(null))
  }

  @Test
  fun streamQualityProfileInitializesLabelFromMode() {
    val autoProfile = StreamQualityProfile(
      mode = StreamQualityMode.AUTO,
      width = 720,
      height = 1280,
      fps = 30,
      maxBitrateBps = 2_000_000,
    )
    assertEquals("자동", autoProfile.label)

    val highProfile = StreamQualityProfile(
      mode = StreamQualityMode.HIGH,
      width = 1080,
      height = 1920,
      fps = 60,
      maxBitrateBps = 8_000_000,
    )
    assertEquals("고화질", highProfile.label)
  }
}
