package com.example.galaxymirror

import org.json.JSONObject

enum class StreamQualityMode(
  val wireValue: String,
  val koreanLabel: String,
) {
  AUTO("AUTO", "자동"),
  DATA_SAVER("DATA_SAVER", "저데이터"),
  STANDARD("STANDARD", "표준"),
  HIGH("HIGH", "고화질");

  companion object {
    fun fromWireValue(value: String?): StreamQualityMode? =
      entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) }
  }
}

enum class StreamNetworkTransport(
  val wireValue: String,
  val koreanLabel: String,
) {
  WIFI("WIFI", "Wi-Fi"),
  CELLULAR("CELLULAR", "모바일 데이터"),
  OTHER("OTHER", "기타 네트워크");

  companion object {
    fun fromWireValue(value: String?): StreamNetworkTransport? =
      entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) }
  }
}

data class StreamQualityProfile(
  val mode: StreamQualityMode,
  val width: Int,
  val height: Int,
  val fps: Int,
  val maxBitrateBps: Int,
) {
  val label: String = mode.koreanLabel
}

object StreamQualityPolicy {
  fun resolve(
    selectedMode: StreamQualityMode,
    networkTransport: StreamNetworkTransport,
  ): StreamQualityProfile {
    val effectiveMode =
      when (selectedMode) {
        StreamQualityMode.AUTO ->
          when (networkTransport) {
            StreamNetworkTransport.WIFI -> StreamQualityMode.HIGH
            StreamNetworkTransport.CELLULAR,
            StreamNetworkTransport.OTHER -> StreamQualityMode.STANDARD
          }
        else -> selectedMode
      }
    return profileFor(effectiveMode)
  }

  fun profileFor(mode: StreamQualityMode): StreamQualityProfile =
    when (mode) {
      StreamQualityMode.AUTO -> profileFor(StreamQualityMode.STANDARD)
      StreamQualityMode.DATA_SAVER ->
        StreamQualityProfile(
          mode = StreamQualityMode.DATA_SAVER,
          width = 540,
          height = 1200,
          fps = 18,
          maxBitrateBps = 1_500_000,
        )
      StreamQualityMode.STANDARD ->
        StreamQualityProfile(
          mode = StreamQualityMode.STANDARD,
          width = 720,
          height = 1600,
          fps = 24,
          maxBitrateBps = 4_000_000,
        )
      StreamQualityMode.HIGH ->
        StreamQualityProfile(
          mode = StreamQualityMode.HIGH,
          width = 1080,
          height = 2400,
          fps = 30,
          maxBitrateBps = 8_000_000,
        )
    }
}

object StreamQualityCodec {
  fun parseMode(body: String): StreamQualityMode? =
    try {
      StreamQualityMode.fromWireValue(JSONObject(body).optString("mode"))
    } catch (_: Exception) {
      null
    }

  fun toStatusJson(
    selectedMode: StreamQualityMode,
    networkTransport: StreamNetworkTransport,
    profile: StreamQualityProfile,
    activityState: ViewerActivityState = ViewerActivityState.ACTIVE,
  ): String =
    JSONObject()
      .put("selectedMode", selectedMode.wireValue)
      .put("selectedLabel", selectedMode.koreanLabel)
      .put("networkTransport", networkTransport.wireValue)
      .put("networkLabel", networkTransport.koreanLabel)
      .put("activityState", activityState.name)
      .put("effectiveMode", profile.mode.wireValue)
      .put("effectiveLabel", profile.label)
      .put("width", profile.width)
      .put("height", profile.height)
      .put("fps", profile.fps)
      .put("maxBitrateBps", profile.maxBitrateBps)
      .toString()
}
