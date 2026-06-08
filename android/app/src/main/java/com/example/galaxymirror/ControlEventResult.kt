package com.example.galaxymirror

data class ControlEventResult(
    val seq: Long?,
    val type: String,
    val applied: Boolean,
    val message: String,
) {
    fun toAckJson(): String =
        """{"type":"CONTROL_ACK","payload":{"seq":$seq,"eventType":"$type","applied":$applied,"message":"$message"}}"""
}
