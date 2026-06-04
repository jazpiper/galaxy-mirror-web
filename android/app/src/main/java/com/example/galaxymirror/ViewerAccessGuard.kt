package com.example.galaxymirror

class ViewerAccessGuard(
    private val expectedToken: String,
) {
    fun isAllowed(queryToken: String?, headerToken: String?): Boolean {
        return queryToken?.takeIf { it.isNotBlank() } == expectedToken ||
            headerToken?.takeIf { it.isNotBlank() } == expectedToken
    }
}
