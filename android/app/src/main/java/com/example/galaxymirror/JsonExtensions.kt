package com.example.galaxymirror

import org.json.JSONObject

fun JSONObject.getPayloadOrSelf(): JSONObject {
    return if (has("payload")) optJSONObject("payload") ?: this else this
}
