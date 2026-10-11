package com.xiaomiscalesync

import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placeholder smoke test: verifies the unit test classpath (JUnit4 + MockWebServer) works.
 * Real tests for the Xiaomi protocol core will live in later tasks.
 */
class MockWebServerSmokeTest {
    @Test
    fun mockWebServerStartsAndAcceptsRequests() {
        val server = MockWebServer()
        try {
            server.start()
            assertEquals(0, server.requestCount)
            assertEquals("/", server.url("/").encodedPath)
        } finally {
            server.shutdown()
        }
    }
}
