package com.example.galaxymirror

import java.security.MessageDigest

class ViewerAccessGuard(
    private val expectedToken: String,
) {
    fun isAllowed(
        queryToken: String?,
        headerToken: String?,
        requestHost: String?,
        remoteHost: String? = null,
    ): Boolean {
        if (isLoopbackHost(requestHost) || isLoopbackHost(remoteHost)) return true

        val expectedBytes = expectedToken.toByteArray(Charsets.UTF_8)
        val qBytes = queryToken?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
        val hBytes = headerToken?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)

        val matchQuery = qBytes != null && MessageDigest.isEqual(qBytes, expectedBytes)
        val matchHeader = hBytes != null && MessageDigest.isEqual(hBytes, expectedBytes)
        return matchQuery || matchHeader
    }

    private fun isLoopbackHost(host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        return normalized == "127.0.0.1" ||
            normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1"
    }

    private fun normalizeHost(host: String?): String? {
        val trimmed = host?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("[")) {
            return trimmed.substringAfter("[").substringBefore("]")
        }

        val colonCount = trimmed.count { it == ':' }
        return if (colonCount == 1 && trimmed.substringAfterLast(":").all { it.isDigit() }) {
            trimmed.substringBeforeLast(":")
        } else {
            trimmed
        }
    }
}
