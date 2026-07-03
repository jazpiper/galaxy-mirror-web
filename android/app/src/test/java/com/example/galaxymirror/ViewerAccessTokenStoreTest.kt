package com.example.galaxymirror

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import org.junit.Test

class ViewerAccessTokenStoreTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data

        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { changes[it] = value }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { changes[it] = values }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { changes[it] = value }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { changes[it] = value }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { changes[it] = value }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { changes[it] = value }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { changes[it] = null }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in changes) {
                if (v == null) {
                    data.remove(k)
                } else {
                    data[k] = v
                }
            }
            changes.clear()
            clear = false
        }
    }

    private class FakeContext(private val sharedPrefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return sharedPrefs
        }
    }

    @Test
    fun getOrCreateTokenGeneratesNewTokenWhenEmpty() {
        val prefs = FakeSharedPreferences()
        val context = FakeContext(prefs)
        val store = ViewerAccessTokenStore(context)

        val token = store.getOrCreateToken()

        assertFalse(token.isBlank())

        // Assert the generated token is saved in shared preferences
        assertEquals(1, prefs.all.size)
        val storedToken = prefs.all.values.first()
        assertEquals(token, storedToken)
    }

    @Test
    fun getOrCreateTokenReturnsSameTokenOnMultipleCalls() {
        val prefs = FakeSharedPreferences()
        val context = FakeContext(prefs)
        val store = ViewerAccessTokenStore(context)

        val token1 = store.getOrCreateToken()
        val token2 = store.getOrCreateToken()

        assertEquals(token1, token2)
    }

    @Test
    fun getOrCreateTokenReturnsExistingToken() {
        val prefs = FakeSharedPreferences()
        val context = FakeContext(prefs)
        val store = ViewerAccessTokenStore(context)

        // Generate the token initially
        val initialToken = store.getOrCreateToken()

        // Create a new store instance with the same preferences
        val newStore = ViewerAccessTokenStore(context)

        // Ensure the token remains consistent across instances using the same preferences
        val retrievedToken = newStore.getOrCreateToken()

        assertEquals(initialToken, retrievedToken)
    }
}
