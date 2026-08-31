package com.skyporch.daykeeper

/** Safe diagnostic metadata. Never contains a token, URL, body or underlying exception. */
class DaykeeperException
internal constructor(
    val code: String,
    val status: Int? = null,
    val retryable: Boolean = false,
    val outcomeUnknown: Boolean = false,
) : Exception(code) {
    internal fun forWrite(dispatched: Boolean) =
        DaykeeperException(
            code,
            status,
            false,
            // A POST can be accepted before a redirect (for example, a 303).
            // Only explicit non-timeout 4xx rejection makes a dispatched write definite.
            dispatched && (status == null || status !in 400..499 || status == 408),
        )

    internal companion object {
        val safeCodes =
            setOf(
                "missing_bearer_token",
                "invalid_bearer_token",
                "invalid_token",
                "unsupported_token",
                "invalid_signature",
                "invalid_tenant",
                "unknown_tenant",
                "invalid_issuer",
                "invalid_audience",
                "invalid_subject",
                "invalid_expiration",
                "expired_token",
                "token_lifetime_too_long",
                "insufficient_scope",
                "not_found",
                "rate_limited",
                "support_upstream_rejected",
                "support_upstream_unavailable",
                "daykeeper_usage_limit_exceeded",
                "daykeeper_usage_not_enabled",
                "daykeeper_support_not_ready",
                "daykeeper_resource_conflict",
                "daykeeper_support_unavailable",
            )
    }
}
