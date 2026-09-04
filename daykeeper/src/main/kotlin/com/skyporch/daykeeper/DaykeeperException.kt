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
        /**
         * The gateway's error vocabulary is open: it gains codes without an SDK release, and a
         * consuming app switches on them, so the SDK checks the shape of a code rather than
         * matching it against a list it would have to chase. Anything else — a free-form English
         * sentence, an upper-case or hyphenated token, a value that is not a JSON string at all —
         * is not a code and collapses to `daykeeper_request_failed`, which is what keeps server
         * prose out of a client-visible error. The envelope's `message` field is never read for
         * the same reason.
         */
        private val CODE_SHAPE = Regex("^[a-z][a-z0-9_]{2,63}$")

        fun isSafeCode(value: String) = CODE_SHAPE.matches(value)
    }
}
