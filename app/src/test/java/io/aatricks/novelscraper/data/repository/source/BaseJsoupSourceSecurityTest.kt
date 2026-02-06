package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ExploreItem
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

class BaseJsoupSourceSecurityTest {

    class TestSource(
        preferencesManager: PreferencesManager,
        okHttpClient: OkHttpClient?
    ) : BaseJsoupSource(preferencesManager, okHttpClient) {
        override val name = "Test"
        override val baseUrl = "https://example.com"
        override suspend fun getPopularNovels(page: Int, tags: List<String>) = emptyList<ExploreItem>()
        override suspend fun searchNovels(query: String, page: Int) = emptyList<ExploreItem>()
        override suspend fun getNovelDetails(url: String) = ExploreItem(title = "", url = "", source = "Test")

        fun testConnect(url: String) = connect(url)
        // Helper to access protected getDocument
        fun testGetDocument(url: String) = getDocument(url)
    }

    @Test
    fun `connect does NOT set global hostname verifier when ignoreSslErrors is true`() {
        val originalVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        try {
            val prefs = mock(PreferencesManager::class.java)
            `when`(prefs.ignoreSslErrors).thenReturn(true)

            val source = TestSource(prefs, null)

            // Trigger the code path
            source.testConnect("https://example.com")

            val currentVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            val mockSession = mock(SSLSession::class.java)

            // A secure verifier should reject this
            val accepted = currentVerifier.verify("evil.com", mockSession)

            assertFalse("Global HostnameVerifier was modified to accept invalid hostnames!", accepted)

        } finally {
            HttpsURLConnection.setDefaultHostnameVerifier(originalVerifier)
        }
    }
}
