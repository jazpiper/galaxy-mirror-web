package com.example.galaxymirror.ui.main

import com.example.galaxymirror.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoading() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(viewModel.uiState.first(), MainScreenUiState.Loading)
  }

  @Test
  fun uiState_onItemSaved_isDisplayed() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    val success = viewModel.uiState.filterIsInstance<MainScreenUiState.Success>().first()
    assertEquals(listOf("Sample"), success.data)
  }

  @Test
  fun uiState_onRepositoryError_isError() = runTest {
    val throwable = IllegalStateException("boom")
    val viewModel = MainScreenViewModel(FailingMyModelRepository(throwable))
    val error = viewModel.uiState.filterIsInstance<MainScreenUiState.Error>().first()
    assertEquals(throwable, error.throwable)
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}

private class FailingMyModelRepository(private val throwable: Throwable) : DataRepository {
  override val data: Flow<List<String>> = flow { throw throwable }
}
