package com.example.galaxymirror

import org.json.JSONObject

interface ControlEventApplier {
    fun handleControlEvent(
        json: JSONObject,
        resultCallback: (ControlEventResult) -> Unit = {},
    )
}
