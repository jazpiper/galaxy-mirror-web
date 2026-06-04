package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class CleanupStepRunnerTest {
  @Test
  fun runsEveryStepEvenWhenOneStepFails() {
    val calls = mutableListOf<String>()
    val failures =
      CleanupStepRunner.run(
        listOf(
          CleanupStep("first") { calls += "first" },
          CleanupStep("throws") {
            calls += "throws"
            error("boom")
          },
          CleanupStep("last") { calls += "last" },
        )
      )

    assertEquals(listOf("first", "throws", "last"), calls)
    assertEquals(1, failures.size)
    assertEquals("throws", failures.single().name)
    assertTrue(failures.single().throwable.message!!.contains("boom"))
  }
}
