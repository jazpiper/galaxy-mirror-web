package com.example.galaxymirror

data class CleanupStep(
  val name: String,
  val action: () -> Unit,
)

data class CleanupFailure(
  val name: String,
  val throwable: Throwable,
)

object CleanupStepRunner {
  fun run(steps: List<CleanupStep>): List<CleanupFailure> {
    val failures = mutableListOf<CleanupFailure>()
    steps.forEach { step ->
      try {
        step.action()
      } catch (throwable: Throwable) {
        failures += CleanupFailure(step.name, throwable)
      }
    }
    return failures
  }
}
