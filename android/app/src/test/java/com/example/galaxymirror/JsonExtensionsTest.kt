package com.example.galaxymirror

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class JsonExtensionsTest {

    @Test
    fun getPayloadOrSelf_returnsPayloadObjectWhenPayloadKeyIsJsonObject() {
        val json = JSONObject("""{"payload":{"type":"tap","x":0.5,"y":0.25}}""")
        val payload = json.getPayloadOrSelf()

        assertEquals("tap", payload.getString("type"))
        assertEquals(0.5, payload.getDouble("x"), 0.0001)
        assertEquals(0.25, payload.getDouble("y"), 0.0001)
    }

    @Test
    fun getPayloadOrSelf_returnsSelfWhenPayloadKeyIsMissing() {
        val json = JSONObject("""{"type":"tap","x":0.5,"y":0.25}""")
        val result = json.getPayloadOrSelf()

        assertSame(json, result)
    }

    @Test
    fun getPayloadOrSelf_returnsSelfWhenPayloadKeyIsNotJsonObject() {
        val json = JSONObject("""{"payload":"not_an_object"}""")
        val result = json.getPayloadOrSelf()

        assertSame(json, result)
    }

    @Test
    fun getStringOrNull_returnsStringWhenKeyExistsAndIsNotNull() {
        val json = JSONObject("""{"name":"GalaxyMirror","empty":""}""")

        assertEquals("GalaxyMirror", json.getStringOrNull("name"))
        assertEquals("", json.getStringOrNull("empty"))
    }

    @Test
    fun getStringOrNull_returnsNullWhenKeyIsMissing() {
        val json = JSONObject("""{"name":"GalaxyMirror"}""")

        assertNull(json.getStringOrNull("missingKey"))
    }

    @Test
    fun getStringOrNull_returnsNullWhenKeyIsNullValue() {
        val json = JSONObject("""{"name":null}""")

        assertNull(json.getStringOrNull("name"))
    }
}
