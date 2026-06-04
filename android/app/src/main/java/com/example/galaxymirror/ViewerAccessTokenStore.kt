package com.example.galaxymirror

import android.content.Context
import java.security.SecureRandom

class ViewerAccessTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("viewer_access", Context.MODE_PRIVATE)

    fun getOrCreateToken(): String {
        preferences.getString(KEY_TOKEN, null)?.let { return it }
        val token = generateToken()
        preferences.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private fun generateToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_TOKEN = "token"
    }
}
