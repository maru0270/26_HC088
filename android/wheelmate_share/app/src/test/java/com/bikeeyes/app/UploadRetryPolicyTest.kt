package com.bikeeyes.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadRetryPolicyTest {
    @Test
    fun `401 pauses until authentication is restored`() {
        assertEquals(
            UploadFailureKind.AUTH,
            UploadRetryPolicy.classifyHttpCode(401, 1).kind
        )
    }

    @Test
    fun `server and rate limit errors are retried`() {
        assertEquals(
            UploadFailureKind.TRANSIENT,
            UploadRetryPolicy.classifyHttpCode(503, 1).kind
        )
        assertEquals(
            "RATE_LIMIT",
            UploadRetryPolicy.classifyHttpCode(429, 1).category
        )
    }

    @Test
    fun `invalid client request is not retried forever`() {
        assertEquals(
            UploadFailureKind.PERMANENT,
            UploadRetryPolicy.classifyHttpCode(422, 1).kind
        )
    }

    @Test
    fun `backoff grows and is capped at six hours`() {
        assertEquals(5_000L, UploadRetryPolicy.backoffDelayMs(1))
        assertEquals(10_000L, UploadRetryPolicy.backoffDelayMs(2))
        assertEquals(6L * 60 * 60 * 1000, UploadRetryPolicy.backoffDelayMs(99))
    }
}
