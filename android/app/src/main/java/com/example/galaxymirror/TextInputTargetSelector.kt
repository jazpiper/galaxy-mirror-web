package com.example.galaxymirror

internal object TextInputTargetSelector {
    fun <T> findTarget(
        focused: T?,
        root: T?,
        isEditable: (T) -> Boolean,
        isFocused: (T) -> Boolean,
        isEnabled: (T) -> Boolean,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?
    ): T? {
        if (focused != null && isTextTarget(focused, isEditable, isEnabled)) {
            return focused
        }

        return root?.findDepthFirst(
            predicate = { node ->
                isFocused(node) && isTextTarget(node, isEditable, isEnabled)
            },
            childCount = childCount,
            childAt = childAt
        )
    }

    private fun <T> isTextTarget(
        node: T,
        isEditable: (T) -> Boolean,
        isEnabled: (T) -> Boolean
    ): Boolean = isEditable(node) && isEnabled(node)

    private fun <T> T.findDepthFirst(
        predicate: (T) -> Boolean,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?
    ): T? {
        if (predicate(this)) return this

        for (index in 0 until childCount(this)) {
            val child = childAt(this, index) ?: continue
            val match = child.findDepthFirst(predicate, childCount, childAt)
            if (match != null) return match
        }

        return null
    }
}
