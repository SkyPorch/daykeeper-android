@file:Suppress("DEPRECATION")

package com.skyporch.daykeeper

import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DaykeeperClientTest {
    private val conversation =
        """{"id":7,"status":"open","createdAt":null,"updatedAt":"2026-09-01T00:00:00Z","unreadCount":3,"unreadForContact":2,"lastSeenAt":null,"preview":"hello"}"""
    private val message =
        """{"id":9,"conversationId":7,"content":"hello","contentType":"text","contentAttributes":{},"messageType":0,"createdAt":null,"sender":null,"attachments":[]}"""
    private val identity =
        """{"baseUrl":"https://support.example","websiteToken":"public","subject":"one","identifier":"one","identifierHash":"hash","email":null,"name":"Demo"}"""

    private fun client(
        server: MockWebServer,
        timeout: Long = 30_000,
        provider: DaykeeperTokenProvider = DaykeeperTokenProvider { "synthetic-customer" },
    ) = DaykeeperClient(server.url("/gateway/").toString(), provider, timeout)

    private suspend fun failure(block: suspend () -> Any?): DaykeeperException {
        try {
            block()
            fail("Expected DaykeeperException")
        } catch (error: DaykeeperException) {
            return error
        }
        error("unreachable")
    }

    private fun server(block: suspend (MockWebServer) -> Unit) = runBlocking {
        MockWebServer().use {
            it.start()
            block(it)
        }
    }

    private fun response(body: String, status: Int = 200) =
        MockResponse()
            .setResponseCode(status)
            .setBody(body)
            .setHeader("Content-Type", "application/json")

    @Test
    fun allEightCustomerMethodsPreservePrefixAndWireShapes() = server { server ->
        listOf(
                identity,
                """{"conversations":[$conversation],"widgetConversationId":null}""",
                """{"conversation":$conversation}""",
                """{"unreadCount":2,"conversation":$conversation,"conversations":[$conversation]}""",
                """{"conversationId":7,"seen":true,"seenAt":1,"seenMessageId":9}""",
                """{"messages":[$message]}""",
                """{"message":$message}""",
                """{"status":"merged","conversations":1}""",
            )
            .forEach { server.enqueue(response(it)) }
        val sdk = client(server)
        assertEquals("one", sdk.getIdentity().subject)
        assertEquals(2, sdk.listConversations().conversations.single().unreadForContact)
        assertEquals(7L, sdk.createConversation().conversation.id)
        assertEquals(2, sdk.getUnread().unreadCount)
        assertTrue(sdk.markConversationSeen(7).seen)
        assertEquals(9L, sdk.listMessages(7, 8).messages.single().id)
        assertEquals("hello", sdk.sendMessage(7, " hello ").message.content)
        assertEquals("merged", sdk.claimAnonymousConversation(" widget ").status)
        val paths =
            listOf(
                "identity",
                "conversations",
                "conversations",
                "unread",
                "conversations/7/seen",
                "conversations/7/messages?after=8",
                "conversations/7/messages",
                "anonymous-conversations/claim",
            )
        paths.forEachIndexed { index, path ->
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/gateway/v1/$path", request.path)
            assertEquals(if (index in listOf(2, 4, 6, 7)) "POST" else "GET", request.method)
            assertEquals("Bearer synthetic-customer", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            if (index == 6) assertEquals("{\"content\":\"hello\"}", request.body.readUtf8())
            if (index == 7) assertEquals("{\"widgetToken\":\"widget\"}", request.body.readUtf8())
        }
    }

    @Test
    fun get401RefreshesExactlyOnce() = server { server ->
        val hints = mutableListOf<Boolean>()
        val sdk =
            client(
                server,
                provider =
                    DaykeeperTokenProvider {
                        hints.add(it)
                        if (it) "new" else "old"
                    },
            )
        server.enqueue(response("{}", 401))
        server.enqueue(response(identity))
        sdk.getIdentity()
        assertEquals(listOf(false, true), hints)
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun terminal401NeverRefreshesAndUnknownErrorsAreRedacted() = server { server ->
        server.enqueue(
            response("""{"error":"secret https://private.example token","retryable":false}""", 401)
        )
        val count = AtomicInteger()
        val error = failure {
            client(
                    server,
                    provider =
                        DaykeeperTokenProvider {
                            count.incrementAndGet()
                            "token"
                        },
                )
                .getIdentity()
        }
        assertEquals(1, count.get())
        assertEquals(1, server.requestCount)
        assertEquals("daykeeper_request_failed", error.code)
        assertNull(error.cause)
        assertFalse(error.toString().contains("secret"))
    }

    @Test
    fun repeated401StopsAfterOneRefresh() = server { server ->
        repeat(2) { server.enqueue(response("{}", 401)) }
        assertEquals(401, failure { client(server).getIdentity() }.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun allWritesAreSingleShotAcrossAuthTimeoutQuotaAndServerFailures() = server { server ->
        val sdk = client(server)
        val writes: List<suspend () -> Any> =
            listOf(
                { sdk.createConversation() },
                { sdk.sendMessage(7, "hello") },
                { sdk.markConversationSeen(7) },
                { sdk.claimAnonymousConversation("widget") },
            )
        for (status in listOf(401, 408, 429, 500, 503)) for (write in writes) {
            val before = server.requestCount
            server.enqueue(response("{\"retryable\":true}", status).setHeader("Retry-After", "0"))
            val error = failure(write)
            assertEquals(status, error.status)
            assertEquals(status == 408 || status >= 500, error.outcomeUnknown)
            assertFalse(error.retryable)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    fun redirectsNeverReachSameOrForeignDestination() = server { source ->
        MockWebServer().use { target ->
            target.start()
            val sdk = client(source)
            for (status in listOf(301, 302, 303, 307, 308)) for (same in listOf(false, true)) {
                source.enqueue(
                    response("{}", status)
                        .setHeader(
                            "Location",
                            (if (same) source else target).url("/redirect-destination"),
                        )
                )
                val error = failure { sdk.sendMessage(7, "hello") }
                assertEquals(status, error.status)
                assertTrue(error.outcomeUnknown)
                assertFalse(error.retryable)
                assertEquals("/gateway/v1/conversations/7/messages", source.takeRequest().path)
            }
            assertEquals(10, source.requestCount)
            assertEquals(0, target.requestCount)
        }
    }

    @Test
    fun cookiesAndPublicCacheAreNotSharedBetweenRequestsOrCustomers() = server { server ->
        val prior = CookieHandler.getDefault()
        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)
        try {
            cookieManager.cookieStore.add(
                server.url("/").toUri(),
                HttpCookie("ambient", "private").apply {
                    path = "/"
                    version = 0
                },
            )
            assertFalse(cookieManager.cookieStore.cookies.isEmpty())
            server.enqueue(response(identity))
            server.url("/").toUrl().openConnection().getInputStream().use { it.readBytes() }
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("ambient=private"))
            repeat(3) {
                server.enqueue(
                    response(identity)
                        .setHeader("Set-Cookie", "session=private; Path=/")
                        .setHeader("Cache-Control", "public, max-age=3600")
                )
            }
            val first = client(server)
            first.getIdentity()
            first.getIdentity()
            client(server).getIdentity()
            repeat(3) { assertNull(server.takeRequest().getHeader("Cookie")) }
            assertEquals(4, server.requestCount)
        } finally {
            CookieHandler.setDefault(prior)
        }
    }

    @Test
    fun invalidConfigurationIdentifiersAndTokensFailBeforeNetwork() = server { server ->
        for (url in
            listOf(
                "http://support.example",
                "https://u:p@example.com",
                "https://@example.com",
                "https://example.com/?",
                "https://example.com/#",
                "file:///tmp/test",
            )) {
            assertEquals(
                "INVALID_CONFIGURATION",
                failure { DaykeeperClient(url, DaykeeperTokenProvider { "test" }) }.code,
            )
        }
        for (token in listOf("", "bearer token", "token\nheader", "é", "a".repeat(16_385))) {
            assertEquals(
                "INVALID_CONFIGURATION",
                failure {
                    client(server, provider = DaykeeperTokenProvider { token }).getIdentity()
                }
                    .code,
            )
        }
        val sdk = client(server)
        for (id in listOf(0L, -1L, 9_007_199_254_740_992L)) failure { sdk.sendMessage(id, "hello") }
        failure { sdk.listMessages(7, 0) }
        failure { sdk.sendMessage(7, " ") }
        failure { sdk.sendMessage(7, "😀".repeat(8_001)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun providerErrorsNeverEscapeOrDispatch() = server { server ->
        val error = failure {
            client(server, provider = DaykeeperTokenProvider { error("sensitive source token") })
                .createConversation()
        }
        assertEquals("TOKEN_PROVIDER_ERROR", error.code)
        assertNull(error.cause)
        assertFalse(error.outcomeUnknown)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun totalDeadlineBoundsNonCooperativeProviderAndPreventsLateDispatch() = server { server ->
        val started = System.nanoTime()
        val error = failure {
            client(
                    server,
                    1_000,
                    DaykeeperTokenProvider {
                        withContext(NonCancellable) { delay(1_500) }
                        "late"
                    },
                )
                .createConversation()
        }
        assertEquals("REQUEST_TIMEOUT", error.code)
        assertFalse(error.outcomeUnknown)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_400)
        delay(650)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationBeforeLateProviderDoesNotSend() = server { server ->
        coroutineScope {
            val sdk =
                client(
                    server,
                    provider =
                        DaykeeperTokenProvider {
                            withContext(NonCancellable) { delay(300) }
                            "late"
                        },
                )
            val operation = async { failure { sdk.sendMessage(7, "hello") } }
            delay(50)
            operation.cancel()
            operation.join()
            delay(400)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun slowWriteResponseIsUncertainAndNotReplayed() = server { server ->
        server.enqueue(response("{\"message\":$message}").setBodyDelay(2, TimeUnit.SECONDS))
        val error = failure { client(server, 1_000).sendMessage(7, "hello") }
        assertTrue(error.outcomeUnknown)
        assertFalse(error.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun responseCapAppliesAfterGzipDecompression() = server { server ->
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(1_048_577)) }
        server.enqueue(MockResponse().setBody(compressed).setHeader("Content-Encoding", "gzip"))
        val error = failure { client(server).createConversation() }
        assertEquals("RESPONSE_TOO_LARGE", error.code)
        assertTrue(error.outcomeUnknown)
    }

    @Test
    fun crossConversationAndUnsafeResponsesAreRejected() = server { server ->
        val sdk = client(server)
        for (bad in
            listOf(
                message.replace("\"conversationId\":7", "\"conversationId\":8"),
                message.replace("\"id\":9", "\"id\":9007199254740992"),
            )) {
            server.enqueue(response("{\"message\":$bad}"))
            val error = failure { sdk.sendMessage(7, "hello") }
            assertEquals("INVALID_RESPONSE", error.code)
            assertTrue(error.outcomeUnknown)
        }
        server.enqueue(response("{\"messages\":[$message,$message]}"))
        assertEquals("INVALID_RESPONSE", failure { sdk.listMessages(7) }.code)
        server.enqueue(response("{\"conversationId\":8,\"seen\":true,\"seenAt\":1}"))
        assertTrue(failure { sdk.markConversationSeen(7) }.outcomeUnknown)
    }

    @Test
    fun malformedSuccessAfterWriteIsUncertain() = server { server ->
        server.enqueue(response("{bad"))
        val error = failure { client(server).createConversation() }
        assertEquals("INVALID_RESPONSE", error.code)
        assertTrue(error.outcomeUnknown)
    }

    @Test
    fun selfSignedTlsIsRejectedBySystemTrust() = runBlocking {
        MockWebServer().use { server ->
            val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),
                false,
            )
            server.start()
            val error = failure { client(server, 1_000).getIdentity() }
            assertTrue(error.code in setOf("NETWORK_ERROR", "REQUEST_TIMEOUT"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun unknownConversationStatusIsPreservedAndKeepsTheRestOfTheList() = server { server ->
        val escalated =
            conversation.replace("\"id\":7", "\"id\":8").replace("\"open\"", "\"escalated\"")
        server.enqueue(
            response("""{"conversations":[$conversation,$escalated],"widgetConversationId":null}""")
        )
        val list = client(server).listConversations()
        assertEquals(listOf(7L, 8L), list.conversations.map { it.id })
        assertEquals(DaykeeperConversationStatus.Open, list.conversations[0].conversationStatus)
        assertEquals(
            DaykeeperConversationStatus.Unknown("escalated"),
            list.conversations[1].conversationStatus,
        )
        assertEquals("escalated", list.conversations[1].status)
    }

    @Test
    fun blankConversationStatusIsStillRejected() = server { server ->
        server.enqueue(
            response(
                """{"conversations":[${conversation.replace("\"open\"", "\" \"")}],"widgetConversationId":null}"""
            )
        )
        assertEquals("INVALID_RESPONSE", failure { client(server).listConversations() }.code)
    }

    @Test
    fun plainLoopbackIsRefusedWhenTheHostAppIsNotADebugBuild() = server { server ->
        val url = server.url("/gateway/").toString()
        val provider = DaykeeperTokenProvider { "synthetic-customer" }
        // Debug builds keep the local-fixture convenience.
        DaykeeperClient(url, provider, 30_000, true)
        for (release in listOf(false)) {
            try {
                DaykeeperClient(url, provider, 30_000, release)
                fail("Expected a release build to refuse plain HTTP")
            } catch (error: DaykeeperException) {
                assertEquals("INVALID_CONFIGURATION", error.code)
            }
        }
    }

    @Test
    fun messageCursorIsSentAsTheAfterQueryParameter() = server { server ->
        server.enqueue(response("""{"messages":[$message]}"""))
        client(server).listMessages(7, 4)
        val request = server.takeRequest()
        assertEquals("/gateway/v1/conversations/7/messages?after=4", request.path)
    }
}
