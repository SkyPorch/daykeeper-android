package com.skyporch.daykeeper

/**
 * A next step the gateway suggested. The vocabulary is deliberately closed: unlike an error code,
 * this is an instruction the app acts on, so a value this release does not know is dropped.
 */
enum class DaykeeperNextAction(val raw: String) {
    REVIEW_USAGE("review_usage"),
    REVIEW_SETUP("review_setup"),
    REFRESH_CONVERSATION("refresh_conversation");

    internal companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** Safe diagnostic metadata. Never contains a token, URL, body or underlying exception. */
class DaykeeperException
internal constructor(
    val code: String,
    val status: Int? = null,
    val retryable: Boolean = false,
    val outcomeUnknown: Boolean = false,
    /** Present only when the gateway sent one of the three known next actions. */
    val nextAction: DaykeeperNextAction? = null,
) : Exception(code) {
    internal fun forWrite(dispatched: Boolean) =
        DaykeeperException(
            code,
            status,
            false,
            // A POST can be accepted before a redirect (for example, a 303).
            // Only explicit non-timeout 4xx rejection makes a dispatched write definite.
            dispatched && (status == null || status !in 400..499 || status == 408),
            nextAction,
        )

    internal companion object {
        /** Only documented remote codes may cross the SDK boundary as diagnostics. */
        private val SAFE_REMOTE_CODES =
            setOf(
                "missing_bearer_token",
                "invalid_bearer_token",
                "invalid_token",
                "invalid_tenant",
                "unknown_tenant",
                "unsupported_token",
                "invalid_signature",
                "invalid_issuer",
                "invalid_audience",
                "invalid_subject",
                "invalid_expiration",
                "expired_token",
                "token_lifetime_too_long",
                "insufficient_scope",
                "erasure_targets_do_not_match_token",
                "unknown_campaign",
                "widget_token_required",
                "not_found",
                "support_upstream_rejected",
                "support_upstream_unavailable",
                "support_gateway_request_failed",
                "conversation_not_found",
                "daykeeper_usage_limit_exceeded",
                "daykeeper_usage_not_enabled",
                "daykeeper_support_not_ready",
                "daykeeper_resource_conflict",
                "daykeeper_support_unavailable",
                "rate_limited",
                "widget_unavailable",
            )

        fun isSafeCode(value: String) = value in SAFE_REMOTE_CODES
    }
}
