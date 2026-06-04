package com.example.galaxymirror

import org.json.JSONArray
import org.json.JSONObject

data class FavoriteApp(
    val packageName: String,
    val label: String,
)

object FavoriteAppsCodec {
    fun normalizeFavorites(favorites: List<FavoriteApp>): List<FavoriteApp> {
        val seen = linkedSetOf<String>()
        return favorites
            .map { FavoriteApp(packageName = it.packageName.trim(), label = it.label.trim()) }
            .filter { it.packageName.isNotBlank() && it.label.isNotBlank() }
            .filter { seen.add(it.packageName) }
    }

    fun toResponseJson(favorites: List<FavoriteApp>): String {
        return JSONObject()
            .put("apps", toJsonArray(normalizeFavorites(favorites)))
            .toString()
    }

    fun toStoredJson(favorites: List<FavoriteApp>): String {
        return toJsonArray(normalizeFavorites(favorites)).toString()
    }

    fun fromStoredJson(raw: String?): List<FavoriteApp> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val apps =
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    FavoriteApp(
                        packageName = item.optString("packageName"),
                        label = item.optString("label"),
                    )
                }
            normalizeFavorites(apps)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseLaunchPackageName(raw: String): String? {
        return try {
            JSONObject(raw)
                .optString("packageName")
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun toJsonArray(favorites: List<FavoriteApp>): JSONArray {
        return JSONArray().apply {
            favorites.forEach { app ->
                put(
                    JSONObject()
                        .put("packageName", app.packageName)
                        .put("label", app.label)
                )
            }
        }
    }
}
