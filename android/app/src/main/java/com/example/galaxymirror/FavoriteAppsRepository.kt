package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager

class FavoriteAppsRepository(
    private val store: KeyValueStore,
    private val appLauncher: AppLauncher,
) {
    constructor(context: Context) : this(
        store = SharedPreferencesStore(context),
        appLauncher = AndroidAppLauncher(context)
    )

    fun getFavorites(): List<FavoriteApp> {
        return FavoriteAppsCodec.fromStoredJson(store.getString(KEY_FAVORITES, null))
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
        return appLauncher.getLaunchableApps()
            .let(FavoriteAppsCodec::normalizeFavorites)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launchFavorite(packageName: String): Boolean {
        return try {
            appLauncher.launchApp(packageName)
        } catch (e: Exception) {
            android.util.Log.e("FavoriteAppsRepository", "Failed to launch package: $packageName", e)
            false
        }
    }

    private fun saveFavorites(favorites: List<FavoriteApp>) {
        store.putString(KEY_FAVORITES, FavoriteAppsCodec.toStoredJson(favorites))
    }

    interface KeyValueStore {
        fun getString(key: String, defaultValue: String?): String?
        fun putString(key: String, value: String?)
    }

    interface AppLauncher {
        fun getLaunchableApps(): List<FavoriteApp>
        fun launchApp(packageName: String): Boolean
    }

    class SharedPreferencesStore(context: Context) : KeyValueStore {
        private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun getString(key: String, defaultValue: String?): String? =
            preferences.getString(key, defaultValue)

        override fun putString(key: String, value: String?) {
            preferences.edit().putString(key, value).apply()
        }
    }

    class AndroidAppLauncher(private val context: Context) : AppLauncher {
        private val packageManager: PackageManager = context.packageManager

        override fun getLaunchableApps(): List<FavoriteApp> {
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
        }

        override fun launchApp(packageName: String): Boolean {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return true
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "favorite_apps"
        const val KEY_FAVORITES = "favorites"
    }
}
