package com.example.galaxymirror

import java.security.MessageDigest

class ViewerAccessGuard(
    private val expectedToken: String,
) {
    fun isAllowed(queryToken: String?, headerToken: String?): Boolean {
        val expectedBytes = expectedToken.toByteArray(Charsets.UTF_8)
        val qBytes = queryToken?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
        val hBytes = headerToken?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)

        val matchQuery = qBytes != null && MessageDigest.isEqual(qBytes, expectedBytes)
        val matchHeader = hBytes != null && MessageDigest.isEqual(hBytes, expectedBytes)
        return matchQuery || matchHeader
    }
}
