package com.example.galaxymirror.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultDataRepositoryTest {

    @Test
    fun data_emitsExpectedList() = runTest {
        val repository: DataRepository = DefaultDataRepository()
        val result = repository.data.first()
        assertEquals(listOf("Android"), result)
    }
}
