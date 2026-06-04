package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager

class FavoriteAppsRepository(
    private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getFavorites(): List<FavoriteApp> {
        return FavoriteAppsCodec.fromStoredJson(preferences.getString(KEY_FAVORITES, null))
    }

    fun getFavoritesResponseJson(): String {
        return FavoriteAppsCodec.toResponseJson(getFavorites())
    }

    fun addFavorite(app: FavoriteApp): List<FavoriteApp> {
        val favorites = FavoriteAppsCodec.normalizeFavorites(getFavorites() + app)
        saveFavorites(favorites)
        return favorites
    }

    fun removeFavorite(packageName: String): List<FavoriteApp> {
        val favorites = getFavorites().filterNot { it.packageName == packageName }
        saveFavorites(favorites)
        return favorites
    }

    fun getLaunchableApps(): List<FavoriteApp> {
        val launcherIntent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        return packageManager
            .queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                FavoriteApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager).toString(),
                )
            }
            .let(FavoriteAppsCodec::normalizeFavorites)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launchFavorite(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            android.util.Log.e("FavoriteAppsRepository", "Failed to launch package: $packageName", e)
            false
        }
    }

    private fun saveFavorites(favorites: List<FavoriteApp>) {
        preferences
            .edit()
            .putString(KEY_FAVORITES, FavoriteAppsCodec.toStoredJson(favorites))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "favorite_apps"
        const val KEY_FAVORITES = "favorites"
    }
}
