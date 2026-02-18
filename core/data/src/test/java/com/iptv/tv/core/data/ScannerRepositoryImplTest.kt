package com.iptv.tv.core.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.ScannerRepositoryImpl
import com.iptv.tv.core.model.ScannerSearchRequest
import com.iptv.tv.core.network.datasource.PublicRepositoryScannerDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerRepositoryImplTest {
    @Test
    fun returnsSuccessFromDataSource() = runTest {
        val dataSource = mockk<PublicRepositoryScannerDataSource>()
        val request = ScannerSearchRequest(query = "news", limit = 50)
        coEvery { dataSource.search(request) } returns emptyList()

        val repository = ScannerRepositoryImpl(dataSource)
        val result = repository.search(request)

        assertTrue(result is AppResult.Success)
        coVerify(exactly = 1) { dataSource.search(request) }
    }
}
