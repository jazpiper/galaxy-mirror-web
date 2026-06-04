package com.example.galaxymirror

import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertNull
import org.junit.Test

class TextInputTargetSelectorTest {
    @Test
    fun findTarget_prefersFocusedNodeWhenEditableAndEnabled() {
        val focused = FakeNode(editable = true, focused = true, enabled = true)
        val root = FakeNode(children = listOf(focused))

        val target = TextInputTargetSelector.findTarget(
            focused = focused,
            root = root,
            isEditable = FakeNode::editable,
            isFocused = FakeNode::focused,
            isEnabled = FakeNode::enabled,
            childCount = { it.children.size },
            childAt = { node, index -> node.children[index] }
        )

        assertSame(focused, target)
    }

    @Test
    fun findTarget_usesFocusedEditableDescendantWhenInputFocusIsContainer() {
        val editableChild = FakeNode(editable = true, focused = true, enabled = true)
        val focusedContainer = FakeNode(editable = false, focused = true, children = listOf(editableChild))
        val root = FakeNode(children = listOf(focusedContainer))

        val target = TextInputTargetSelector.findTarget(
            focused = focusedContainer,
            root = root,
            isEditable = FakeNode::editable,
            isFocused = FakeNode::focused,
            isEnabled = FakeNode::enabled,
            childCount = { it.children.size },
            childAt = { node, index -> node.children[index] }
        )

        assertSame(editableChild, target)
    }

    @Test
    fun findTarget_rejectsDisabledEditableNodes() {
        val disabledEditable = FakeNode(editable = true, focused = true, enabled = false)
        val root = FakeNode(children = listOf(disabledEditable))

        val target = TextInputTargetSelector.findTarget(
            focused = disabledEditable,
            root = root,
            isEditable = FakeNode::editable,
            isFocused = FakeNode::focused,
            isEnabled = FakeNode::enabled,
            childCount = { it.children.size },
            childAt = { node, index -> node.children[index] }
        )

        assertNull(target)
    }

    private data class FakeNode(
        val editable: Boolean = false,
        val focused: Boolean = false,
        val enabled: Boolean = true,
        val children: List<FakeNode> = emptyList()
    )
}
