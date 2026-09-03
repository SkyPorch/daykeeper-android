package com.skyporch.daykeeper

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink

/**
 * Customer-only, coroutine SDK. No cookies, redirects, persistent cache or implicit write replay.
 * Token providers must be cooperative and must never switch customer identity within this client.
 */
class DaykeeperClient
internal constructor(
    baseUrl: String,
    private val tokenProvider: DaykeeperTokenProvider,
    private val timeoutMillis: Long,
    allowPlainLoopback: Boolean,
) : DaykeeperCustomerClient {
    @JvmOverloads
    constructor(
        baseUrl: String,
        tokenProvider: DaykeeperTokenProvider,
        timeoutMillis: Long = 30_000,
    ) : this(baseUrl, tokenProvider, timeoutMillis, BuildConfig.DEBUG)

    private val base: String
    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient

    init {
        val uri =
            try {
                URI(baseUrl)
            } catch (_: Exception) {
                null
            }
        val url = baseUrl.toHttpUrlOrNull()
        if (
            uri == null ||
                url == null ||
                uri.rawUserInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                timeoutMillis !in 1_000..60_000 ||
                // Plain HTTP to a developer's own loopback fixture is a debug-build
                // convenience. A release build refuses every unencrypted base URL.
                (!url.isHttps &&
                    !(allowPlainLoopback && url.host in setOf("localhost", "127.0.0.1", "::1")))
        )
            throw DaykeeperException("INVALID_CONFIGURATION")
        base = url.toString().trimEnd('/')
        http =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                // Defense in depth: deny internal HTTP follow-ups, including status-based retries.
                .addNetworkInterceptor { chain ->
                    val dispatch = checkNotNull(chain.request().tag(Dispatch::class.java))
                    if (!dispatch.attempt.compareAndSet(false, true))
                        throw IOException("follow_up_denied")
                    dispatch.writeDispatched.set(true)
                    chain.proceed(chain.request())
                }
                .build()
    }

    override suspend fun getIdentity(): DaykeeperCustomerIdentity = request("/v1/identity")

    override suspend fun getIdentityWithFreshToken(): DaykeeperCustomerIdentity =
        request("/v1/identity", forceRefresh = true)

    override suspend fun listConversations(): DaykeeperConversationList =
        request<DaykeeperConversationList>("/v1/conversations").also {
            conversations(it.conversations)
            response(it.widgetConversationId == null || safeId(it.widgetConversationId))
        }

    override suspend fun createConversation(): DaykeeperConversationResult =
        request<DaykeeperConversationResult>("/v1/conversations", write = true) {
            conversations(listOf(it.conversation))
        }

    override suspend fun getUnread(): DaykeeperUnreadSummary =
        request<DaykeeperUnreadSummary>("/v1/unread").also {
            response(it.unreadCount >= 0)
            conversations(it.conversations)
            it.conversation?.let { item -> conversations(listOf(item)) }
        }

    override suspend fun markConversationSeen(conversationId: Long): DaykeeperSeenResult {
        positive(conversationId)
        return request("/v1/conversations/$conversationId/seen", write = true) {
            response(
                it.conversationId == conversationId &&
                    it.seen &&
                    it.seenAt >= 0 &&
                    (it.seenMessageId == null || safeId(it.seenMessageId))
            )
        }
    }

    override suspend fun listMessages(conversationId: Long, after: Long?): DaykeeperMessageList {
        positive(conversationId)
        after?.let(::positive)
        return request<DaykeeperMessageList>(
                "/v1/conversations/$conversationId/messages" + (after?.let { "?after=$it" } ?: "")
            )
            .also { messages(it.messages, conversationId) }
    }

    override suspend fun sendMessage(
        conversationId: Long,
        content: String,
    ): DaykeeperMessageResult {
        positive(conversationId)
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > 16_000)
            throw DaykeeperException("INVALID_CONFIGURATION")
        return request(
            "/v1/conversations/$conversationId/messages",
            true,
            json.encodeToString(mapOf("content" to trimmed)),
        ) {
            messages(listOf(it.message), conversationId)
        }
    }

    override suspend fun claimAnonymousConversation(
        widgetToken: String
    ): DaykeeperClaimConversationResult {
        val trimmed = widgetToken.trim()
        if (trimmed.isEmpty() || trimmed.length > 16_384)
            throw DaykeeperException("INVALID_CONFIGURATION")
        return request(
            "/v1/anonymous-conversations/claim",
            true,
            json.encodeToString(mapOf("widgetToken" to trimmed)),
        ) {
            response(
                it.conversations >= 0 &&
                    it.status in
                        setOf(
                            "merged",
                            "already_claimed",
                            "already_identified",
                            "email_mismatch",
                            "foreign_contact",
                            "invalid_token",
                            "not_found",
                        )
            )
        }
    }

    private class Dispatch {
        val attempt = AtomicBoolean()
        val writeDispatched = AtomicBoolean()
    }

    private data class WireResponse(val status: Int, val body: String)

    private suspend inline fun <reified T> request(
        path: String,
        write: Boolean = false,
        body: String = "",
        forceRefresh: Boolean = false,
        crossinline validate: (T) -> Unit = {},
    ): T {
        currentCoroutineContext().ensureActive()
        val dispatch = Dispatch()
        // A non-cooperative provider cannot hold the caller past its deadline or dispatch a late
        // token.
        // The provider still owns and must cancel its underlying work; no thread is forcibly
        // killed.
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = workerScope.async {
            // A forced-refresh read already carries a new credential, so it gets one
            // attempt like a write rather than a refresh-and-retry pair.
            repeat(if (write || forceRefresh) 1 else 2) { attempt ->
                currentCoroutineContext().ensureActive()
                val token =
                    try {
                        tokenProvider.token(forceRefresh || attempt == 1)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        throw DaykeeperException("TOKEN_PROVIDER_ERROR")
                    }
                currentCoroutineContext().ensureActive()
                if (token.isEmpty() || token.length > 16_384 || token.any { it.code !in 33..126 }) {
                    throw DaykeeperException("INVALID_CONFIGURATION")
                }
                dispatch.attempt.set(false)
                val builder =
                    Request.Builder()
                        .url(base + path)
                        .tag(Dispatch::class.java, dispatch)
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache, no-store")
                if (write)
                    builder.post(
                        object : RequestBody() {
                            private val bytes = body.toByteArray(Charsets.UTF_8)

                            override fun contentType() =
                                "application/json; charset=utf-8".toMediaType()

                            override fun contentLength() = bytes.size.toLong()

                            override fun isOneShot() = true

                            override fun writeTo(sink: BufferedSink) {
                                sink.write(bytes)
                            }
                        }
                    )
                val wire = perform(builder.build())
                currentCoroutineContext().ensureActive()
                val hint =
                    try {
                        json.parseToJsonElement(wire.body) as? JsonObject
                    } catch (_: Exception) {
                        null
                    }
                val retryHint = (hint?.get("retryable") as? JsonPrimitive)?.booleanOrNull
                if (!write && attempt == 0 && wire.status == 401 && retryHint != false)
                    return@repeat
                if (wire.status !in 200..299) {
                    // `message` is never read: it is free-form prose for a human reading the
                    // gateway's own surfaces. Both fields must be actual JSON strings, so a
                    // number, boolean, array or object is not coerced into something
                    // code-shaped.
                    val code = stringField(hint, "error")
                    throw DaykeeperException(
                        code?.takeIf { DaykeeperException.isSafeCode(it) }
                            ?: "daykeeper_request_failed",
                        wire.status,
                        !write &&
                            (retryHint
                                ?: (wire.status == 408 ||
                                    wire.status == 429 ||
                                    wire.status >= 500)),
                        nextAction = DaykeeperNextAction.of(stringField(hint, "nextAction")),
                    )
                }
                val result =
                    try {
                        json.decodeFromString<T>(wire.body)
                    } catch (_: Exception) {
                        throw DaykeeperException("INVALID_RESPONSE", retryable = true)
                    }
                validate(result)
                return@async result
            }
            throw DaykeeperException("INVALID_RESPONSE")
        }
        try {
            return withTimeout(timeoutMillis) { worker.await() }
        } catch (error: Exception) {
            val safe =
                when (error) {
                    is DaykeeperException -> error
                    is TimeoutCancellationException ->
                        DaykeeperException("REQUEST_TIMEOUT", retryable = true)
                    is CancellationException -> DaykeeperException("REQUEST_ABORTED")
                    else -> DaykeeperException("NETWORK_ERROR", retryable = true)
                }
            throw if (write) safe.forWrite(dispatch.writeDispatched.get()) else safe
        } finally {
            workerScope.cancel()
        }
    }

    private suspend fun perform(request: Request): WireResponse =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                DaykeeperException("NETWORK_ERROR", retryable = true)
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val source = it.body.source()
                                val buffer = Buffer()
                                while (true) {
                                    if (buffer.size > MAX_RESPONSE_BYTES)
                                        throw DaykeeperException("RESPONSE_TOO_LARGE")
                                    val count =
                                        source.read(
                                            buffer,
                                            minOf(8192, MAX_RESPONSE_BYTES + 1 - buffer.size),
                                        )
                                    if (count == -1L) break
                                }
                                if (continuation.isActive)
                                    continuation.resume(WireResponse(it.code, buffer.readUtf8()))
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive)
                                continuation.resumeWithException(
                                    error as? DaykeeperException
                                        ?: DaykeeperException("NETWORK_ERROR", retryable = true)
                                )
                        }
                    }
                }
            )
        }

    private fun stringField(hint: JsonObject?, name: String) =
        (hint?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun positive(id: Long) {
        if (!safeId(id)) throw DaykeeperException("INVALID_CONFIGURATION")
    }

    private fun safeId(id: Long) = id in 1..9_007_199_254_740_991L

    private fun response(valid: Boolean) {
        if (!valid) throw DaykeeperException("INVALID_RESPONSE")
    }

    private fun timestamp(value: kotlinx.serialization.json.JsonElement?) =
        value == null ||
            value == JsonNull ||
            (value is JsonPrimitive && (value.isString || value.longOrNull != null))

    private fun conversations(values: List<DaykeeperConversation>) {
        response(
            values.map { it.id }.toSet().size == values.size &&
                values.all {
                    safeId(it.id) &&
                        it.unreadCount >= 0 &&
                        it.unreadForContact >= 0 &&
                        // Statuses are extensible. Only a missing or blank one is malformed;
                        // an unfamiliar status must not discard the whole list.
                        it.status.isNotBlank() &&
                        timestamp(it.createdAt) &&
                        timestamp(it.updatedAt)
                }
        )
    }

    private fun messages(values: List<DaykeeperMessage>, conversationId: Long) {
        response(
            values.map { it.id }.toSet().size == values.size &&
                values.all {
                    safeId(it.id) &&
                        it.conversationId == conversationId &&
                        timestamp(it.createdAt) &&
                        it.attachments.all { item -> safeId(item.id) }
                }
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576L
    }
}
