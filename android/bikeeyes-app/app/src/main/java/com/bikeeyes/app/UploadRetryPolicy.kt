package com.bikeeyes.app

import java.io.IOException
import retrofit2.HttpException

enum class UploadFailureKind { AUTH, TRANSIENT, PERMANENT }

data class UploadFailureDecision(
    val kind: UploadFailureKind,
    val category: String,
    val httpStatus: Int? = null,
    val retryDelayMs: Long? = null
)

object UploadRetryPolicy {
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 6L * 60 * 60 * 1000

    fun classify(exception: Exception, retryCount: Int): UploadFailureDecision =
        when (exception) {
            is HttpException -> classifyHttpCode(exception.code(), retryCount)
            is IOException -> UploadFailureDecision(
                UploadFailureKind.TRANSIENT,
                "NETWORK",
                retryDelayMs = backoffDelayMs(retryCount)
            )
            else -> UploadFailureDecision(
                UploadFailureKind.TRANSIENT,
                "UNEXPECTED",
                retryDelayMs = backoffDelayMs(retryCount)
            )
        }

    fun classifyHttpCode(code: Int, retryCount: Int): UploadFailureDecision = when {
        code == 401 -> UploadFailureDecision(UploadFailureKind.AUTH, "AUTH", code)
        code == 408 || code == 425 || code == 429 || code in 500..599 ->
            UploadFailureDecision(
                UploadFailureKind.TRANSIENT,
                if (code == 429) "RATE_LIMIT" else "SERVER",
                code,
                backoffDelayMs(retryCount)
            )
        else -> UploadFailureDecision(UploadFailureKind.PERMANENT, "CLIENT_REQUEST", code)
    }

    fun backoffDelayMs(retryCount: Int): Long {
        val exponent = (retryCount - 1).coerceIn(0, 30)
        return (BASE_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
    }
}
