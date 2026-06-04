package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.json.JSONObject
import org.junit.Test

class FavoriteAppsCodecTest {
    @Test
    fun normalizeFavorites_removesBlankAndDuplicatePackageNames() {
        val favorites =
            FavoriteAppsCodec.normalizeFavorites(
                listOf(
                    FavoriteApp("com.chat", "Chat"),
                    FavoriteApp(" ", "Blank"),
                    FavoriteApp("com.chat", "Chat Duplicate"),
                    FavoriteApp("com.mail", "Mail"),
                )
            )

        assertEquals(
            listOf(
                FavoriteApp("com.chat", "Chat"),
                FavoriteApp("com.mail", "Mail"),
            ),
            favorites,
        )
    }

    @Test
    fun favoritesJson_usesAppsArrayShape() {
        val json =
            JSONObject(
                FavoriteAppsCodec.toResponseJson(
                    listOf(FavoriteApp("com.chat", "Chat"))
                )
            )

        val apps = json.getJSONArray("apps")
        assertEquals(1, apps.length())
        assertEquals("com.chat", apps.getJSONObject(0).getString("packageName"))
        assertEquals("Chat", apps.getJSONObject(0).getString("label"))
    }

    @Test
    fun storedJson_roundTripsFavorites() {
        val stored =
            FavoriteAppsCodec.toStoredJson(
                listOf(
                    FavoriteApp("com.chat", "Chat"),
                    FavoriteApp("com.mail", "Mail"),
                )
            )

        assertEquals(
            listOf(
                FavoriteApp("com.chat", "Chat"),
                FavoriteApp("com.mail", "Mail"),
            ),
            FavoriteAppsCodec.fromStoredJson(stored),
        )
    }

    @Test
    fun parseLaunchPackageName_rejectsBlankOrMalformedBody() {
        assertEquals(
            "com.chat",
            FavoriteAppsCodec.parseLaunchPackageName("""{"packageName":"com.chat"}"""),
        )
        assertNull(FavoriteAppsCodec.parseLaunchPackageName("""{"packageName":" "}"""))
        assertNull(FavoriteAppsCodec.parseLaunchPackageName("""not-json"""))
    }
}
