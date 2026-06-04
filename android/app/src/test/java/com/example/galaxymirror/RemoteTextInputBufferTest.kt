package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class RemoteTextInputBufferTest {
  @Test
  fun rapidCommitsUseCachedTextWhenAccessibilitySnapshotIsStale() {
    val buffer = RemoteTextInputBuffer()
    val staleSnapshot =
      RemoteTextSnapshot(
        targetKey = "chat-input",
        text = "이제 ",
        selectionStart = 3,
        selectionEnd = 3,
      )

    val first = buffer.planCommit(staleSnapshot, "간")
    buffer.markApplied(first)
    val second = buffer.planCommit(staleSnapshot, "단")
    buffer.markApplied(second)
    val third = buffer.planCommit(staleSnapshot, "한")

    assertEquals("이제 간", first.nextText)
    assertEquals("이제 간단", second.nextText)
    assertEquals("이제 간단한", third.nextText)
  }

  @Test
  fun deleteUsesCachedCursorAfterRapidCommit() {
    val buffer = RemoteTextInputBuffer()
    val staleSnapshot =
      RemoteTextSnapshot(
        targetKey = "chat-input",
        text = "가",
        selectionStart = 1,
        selectionEnd = 1,
      )

    val commit = buffer.planCommit(staleSnapshot, "나")
    buffer.markApplied(commit)
    val delete = buffer.planDelete(staleSnapshot, count = 1)

    assertEquals("가나", commit.nextText)
    assertEquals("가", delete.nextText)
  }

  @Test
  fun targetChangeStartsFromCurrentSnapshot() {
    val buffer = RemoteTextInputBuffer()
    val first = buffer.planCommit(
      RemoteTextSnapshot(
        targetKey = "first-input",
        text = "A",
        selectionStart = 1,
        selectionEnd = 1,
      ),
      "B",
    )
    buffer.markApplied(first)

    val second = buffer.planCommit(
      RemoteTextSnapshot(
        targetKey = "second-input",
        text = "가",
        selectionStart = 1,
        selectionEnd = 1,
      ),
      "나",
    )

    assertEquals("가나", second.nextText)
  }
}
