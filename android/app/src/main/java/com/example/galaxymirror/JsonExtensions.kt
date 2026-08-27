package com.example.galaxymirror

import org.json.JSONObject

fun JSONObject.getPayloadOrSelf(): JSONObject {
    return if (has("payload")) optJSONObject("payload") ?: this else this
}

fun JSONObject.getStringOrNull(key: String): String? {
    return if (has(key) && !isNull(key)) getString(key) else null
}
