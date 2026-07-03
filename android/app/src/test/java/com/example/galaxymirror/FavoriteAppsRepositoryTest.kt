package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class FavoriteAppsRepositoryTest {

    private class FakeKeyValueStore : FavoriteAppsRepository.KeyValueStore {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue

        override fun putString(key: String, value: String?) {
            if (value == null) {
                map.remove(key)
            } else {
                map[key] = value
            }
        }

        fun rawPutString(key: String, value: String) {
            map[key] = value
        }
    }

    private class FakeAppLauncher : FavoriteAppsRepository.AppLauncher {
        var appsToReturn = listOf<FavoriteApp>()
        val launchedPackages = mutableListOf<String>()
        var launchShouldSucceed = true

        override fun getLaunchableApps(): List<FavoriteApp> = appsToReturn

        override fun launchApp(packageName: String): Boolean {
            if (launchShouldSucceed) {
                launchedPackages.add(packageName)
                return true
            }
            return false
        }
    }

    @Test
    fun getFavoritesReturnsEmptyListInitially() {
        val store = FakeKeyValueStore()
        val launcher = FakeAppLauncher()
        val repo = FavoriteAppsRepository(store, launcher)

        val favorites = repo.getFavorites()

        assertTrue(favorites.isEmpty())
    }

    @Test
    fun getFavoritesParsesJsonFromStore() {
        val store = FakeKeyValueStore()
        store.rawPutString("favorites", """[{"packageName":"com.app","label":"App"}]""")
        val launcher = FakeAppLauncher()
        val repo = FavoriteAppsRepository(store, launcher)

        val favorites = repo.getFavorites()

        assertEquals(1, favorites.size)
        assertEquals("com.app", favorites[0].packageName)
        assertEquals("App", favorites[0].label)
    }

    @Test
    fun getFavoritesResponseJsonReturnsCorrectFormat() {
        val store = FakeKeyValueStore()
        store.rawPutString("favorites", """[{"packageName":"com.app","label":"App"}]""")
        val launcher = FakeAppLauncher()
        val repo = FavoriteAppsRepository(store, launcher)

        val json = repo.getFavoritesResponseJson()

        assertTrue(json.contains("com.app"))
        assertTrue(json.contains("App"))
    }

    @Test
    fun addFavoriteAppendsToListAndSaves() {
        val store = FakeKeyValueStore()
        val launcher = FakeAppLauncher()
        val repo = FavoriteAppsRepository(store, launcher)

        val newFavorites = repo.addFavorite(FavoriteApp("com.new", "New App"))

        assertEquals(1, newFavorites.size)
        assertEquals("com.new", newFavorites[0].packageName)
        val saved = repo.getFavorites()
        assertEquals(1, saved.size)
        assertEquals("com.new", saved[0].packageName)
    }

    @Test
    fun removeFavoriteRemovesFromListAndSaves() {
        val store = FakeKeyValueStore()
        store.rawPutString("favorites", """[{"packageName":"com.app","label":"App"},{"packageName":"com.keep","label":"Keep"}]""")
        val launcher = FakeAppLauncher()
        val repo = FavoriteAppsRepository(store, launcher)

        val newFavorites = repo.removeFavorite("com.app")

        assertEquals(1, newFavorites.size)
        assertEquals("com.keep", newFavorites[0].packageName)
        val saved = repo.getFavorites()
        assertEquals(1, saved.size)
        assertEquals("com.keep", saved[0].packageName)
    }

    @Test
    fun getLaunchableAppsReturnsNormalizedAndSortedList() {
        val store = FakeKeyValueStore()
        val launcher = FakeAppLauncher()
        launcher.appsToReturn = listOf(
            FavoriteApp("com.z", "Zebra"),
            FavoriteApp("com.a", "Apple"),
            FavoriteApp("com.a", "Apple Duplicate") // Should be removed by normalize
        )
        val repo = FavoriteAppsRepository(store, launcher)

        val apps = repo.getLaunchableApps()

        assertEquals(2, apps.size)
        assertEquals("Apple", apps[0].label)
        assertEquals("Zebra", apps[1].label)
    }

    @Test
    fun launchFavoriteSucceeds() {
        val store = FakeKeyValueStore()
        val launcher = FakeAppLauncher()
        val repo = FavoriteAppsRepository(store, launcher)

        val result = repo.launchFavorite("com.app")

        assertTrue(result)
        assertEquals("com.app", launcher.launchedPackages[0])
    }

    @Test
    fun launchFavoriteFailsWhenLauncherReturnsFalse() {
        val store = FakeKeyValueStore()
        val launcher = FakeAppLauncher()
        launcher.launchShouldSucceed = false
        val repo = FavoriteAppsRepository(store, launcher)

        val result = repo.launchFavorite("com.app")

        assertFalse(result)
        assertTrue(launcher.launchedPackages.isEmpty())
    }
}
