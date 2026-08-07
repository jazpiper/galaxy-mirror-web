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

  @Test
  fun runsAllStepsSuccessfully() {
    val calls = mutableListOf<String>()
    val failures =
      CleanupStepRunner.run(
        listOf(
          CleanupStep("first") { calls += "first" },
          CleanupStep("second") { calls += "second" },
          CleanupStep("third") { calls += "third" },
        )
      )

    assertEquals(listOf("first", "second", "third"), calls)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun handlesMultipleFailures() {
    val calls = mutableListOf<String>()
    val failures =
      CleanupStepRunner.run(
        listOf(
          CleanupStep("first") { calls += "first" },
          CleanupStep("throws1") {
            calls += "throws1"
            error("boom1")
          },
          CleanupStep("throws2") {
            calls += "throws2"
            error("boom2")
          },
          CleanupStep("last") { calls += "last" },
        )
      )

    assertEquals(listOf("first", "throws1", "throws2", "last"), calls)
    assertEquals(2, failures.size)
    assertEquals("throws1", failures[0].name)
    assertTrue(failures[0].throwable.message!!.contains("boom1"))
    assertEquals("throws2", failures[1].name)
    assertTrue(failures[1].throwable.message!!.contains("boom2"))
  }
}
