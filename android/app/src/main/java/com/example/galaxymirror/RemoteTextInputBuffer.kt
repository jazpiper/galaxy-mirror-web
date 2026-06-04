package com.example.galaxymirror

data class RemoteTextSnapshot(
  val targetKey: String,
  val text: String,
  val selectionStart: Int,
  val selectionEnd: Int,
)

data class RemoteTextEdit(
  val targetKey: String,
  val nextText: String,
  val nextSelectionStart: Int,
  val nextSelectionEnd: Int,
)

class RemoteTextInputBuffer {
  private var cached: RemoteTextSnapshot? = null

  fun planCommit(snapshot: RemoteTextSnapshot, text: String): RemoteTextEdit {
    val base = currentSnapshot(snapshot)
    val range = base.selectionRange()
    val nextText = base.text.replaceRange(range.first, range.second, text)
    val cursor = range.first + text.length
    return RemoteTextEdit(
      targetKey = base.targetKey,
      nextText = nextText,
      nextSelectionStart = cursor,
      nextSelectionEnd = cursor,
    )
  }

  fun planDelete(snapshot: RemoteTextSnapshot, count: Int): RemoteTextEdit {
    val base = currentSnapshot(snapshot)
    val range = base.selectionRange()
    val deleteStart =
      if (range.first != range.second) {
        range.first
      } else {
        maxOf(0, range.first - count)
      }
    return RemoteTextEdit(
      targetKey = base.targetKey,
      nextText = base.text.replaceRange(deleteStart, range.second, ""),
      nextSelectionStart = deleteStart,
      nextSelectionEnd = deleteStart,
    )
  }

  fun markApplied(edit: RemoteTextEdit) {
    cached =
      RemoteTextSnapshot(
        targetKey = edit.targetKey,
        text = edit.nextText,
        selectionStart = edit.nextSelectionStart,
        selectionEnd = edit.nextSelectionEnd,
      )
  }

  fun invalidate() {
    cached = null
  }

  private fun currentSnapshot(snapshot: RemoteTextSnapshot): RemoteTextSnapshot {
    val cachedSnapshot = cached
    return if (cachedSnapshot?.targetKey == snapshot.targetKey) {
      cachedSnapshot
    } else {
      snapshot
    }
  }

  private fun RemoteTextSnapshot.selectionRange(): Pair<Int, Int> {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    return start to end
  }
}
