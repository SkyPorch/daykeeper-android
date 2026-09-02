@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package com.skyporch.daykeeper.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.skyporch.daykeeper.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** JVM Android-view/lifecycle tests. These do not substitute for device instrumentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaykeeperMessengerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstConversationSendHistoryAndReadBadge() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            assertEquals(1L, session.state.value.conversationId)
            session.setDraft(" hello ")
            session.sendMessage()
            advanceUntilIdle()
            assertEquals("hello", session.state.value.messages.single().content)
            assertEquals("", session.state.value.draft)
            assertEquals(1, client.sends)
            client.unread = 3
            session.refresh()
            advanceUntilIdle()
            session.setDraft("keep draft")
            session.markRead()
            advanceUntilIdle()
            assertEquals(0, session.state.value.conversations.single().unreadForContact)
            assertEquals("keep draft", session.state.value.draft)
            assertEquals(1, client.seenWrites)
            session.reset()
        }

    @Test
    @Config(sdk = [23, 26, 29, 30])
    fun privacyFlagsAndMessengerConstructOnOlderApiLevels() =
        runTest(dispatcher) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val owner = TestOwner()
            val session = DaykeeperMessengerSession(FakeClient())
            val view = DaykeeperMessengerView(activity)
            activity.setContentView(view)
            view.bind(session, owner)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            advanceUntilIdle()
            button(view, "New conversation").performClick()
            advanceUntilIdle()
            assertEquals(1L, session.state.value.conversationId)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            session.reset()
            activity.finish()
        }

    @Test
    fun seenRefreshPreservesNewUnreadArrivals() =
        runTest(dispatcher) {
            val client = FakeClient().apply { unreadAfterSeen = 2 }
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.markRead()
            advanceUntilIdle()
            assertEquals(2, session.state.value.conversations.single().unreadForContact)
            assertEquals(1, client.seenWrites)
            session.reset()
        }

    @Test
    fun backgroundHidesDataAndPreservesDraftOnlyInSameSession() =
        runTest(dispatcher) {
            val session = DaykeeperMessengerSession(FakeClient())
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("private draft")
            session.suspend()
            assertTrue(session.state.value.suspended)
            assertTrue(session.state.value.messages.isEmpty())
            assertEquals("", session.state.value.draft)
            session.resume()
            advanceUntilIdle()
            assertEquals("private draft", session.state.value.draft)
            session.reset()
            session.resume()
            advanceUntilIdle()
            assertTrue(session.state.value.signedOut)
            assertEquals("", session.state.value.draft)
            val next = DaykeeperMessengerSession(FakeClient())
            next.resume()
            advanceUntilIdle()
            assertTrue(next.state.value.conversations.isEmpty())
            next.reset()
        }

    @Test
    fun cancelledAcceptedSendCannotEraseDraftOrReplay() =
        runTest(dispatcher) {
            val accepted = CompletableDeferred<Unit>()
            val client = FakeClient().apply { sendCompletion = accepted }
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("accepted once")
            session.sendMessage()
            advanceUntilIdle()
            assertEquals(1, client.sends)
            session.suspend()
            accepted.complete(Unit)
            advanceUntilIdle()
            session.resume()
            advanceUntilIdle()
            assertEquals("accepted once", session.state.value.draft)
            assertTrue(session.state.value.uncertainMessage)
            assertEquals(1, session.state.value.messages.size)
            session.sendMessage()
            advanceUntilIdle()
            assertEquals(1, client.sends)
            session.discardUncertainDraft()
            assertFalse(session.state.value.uncertainMessage)
            assertEquals("", session.state.value.draft)
            session.reset()
        }

    @Test
    fun cancelledAcceptedCreationRequiresReviewAndDoesNotSelectLateThread() =
        runTest(dispatcher) {
            val accepted = CompletableDeferred<Unit>()
            val client = FakeClient().apply { createCompletion = accepted }
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.suspend()
            accepted.complete(Unit)
            advanceUntilIdle()
            session.resume()
            advanceUntilIdle()
            assertNull(session.state.value.conversationId)
            assertEquals(1, session.state.value.conversations.size)
            assertTrue(session.state.value.uncertainCreation)
            session.createConversation()
            advanceUntilIdle()
            assertEquals(1, client.creates)
            session.acknowledgeUncertainCreation()
            advanceUntilIdle()
            assertEquals(1, client.creates)
            session.reset()
        }

    @Test
    fun lateResponseAfterResetCannotLeakIntoAnotherCustomer() =
        runTest(dispatcher) {
            val accepted = CompletableDeferred<Unit>()
            val old = DaykeeperMessengerSession(FakeClient().apply { createCompletion = accepted })
            old.resume()
            advanceUntilIdle()
            old.createConversation()
            advanceUntilIdle()
            old.reset()
            val next = DaykeeperMessengerSession(FakeClient())
            next.resume()
            advanceUntilIdle()
            accepted.complete(Unit)
            advanceUntilIdle()
            assertTrue(old.state.value.signedOut)
            assertTrue(old.state.value.conversations.isEmpty())
            assertTrue(next.state.value.conversations.isEmpty())
            next.reset()
        }

    @Test
    fun perThreadDraftsSurviveNavigation() =
        runTest(dispatcher) {
            val session = DaykeeperMessengerSession(FakeClient())
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("one")
            session.showConversations()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("two")
            session.showConversations()
            advanceUntilIdle()
            session.openConversation(1)
            advanceUntilIdle()
            assertEquals("one", session.state.value.draft)
            session.showConversations()
            advanceUntilIdle()
            session.openConversation(2)
            advanceUntilIdle()
            assertEquals("two", session.state.value.draft)
            session.reset()
        }

    @Test
    fun nativeViewSupportsComposerActionsAndLifecycleRedaction() =
        runTest(dispatcher) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val owner = TestOwner()
            val session = DaykeeperMessengerSession(FakeClient())
            val view = DaykeeperMessengerView(activity)
            activity.setContentView(view)
            view.bind(session, owner)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            advanceUntilIdle()
            button(view, "New conversation").performClick()
            advanceUntilIdle()
            val composer = descendants(view).filterIsInstance<EditText>().single()
            composer.setText("view message")
            advanceUntilIdle()
            assertTrue(button(view, "Send message").isEnabled)
            button(view, "Send message").performClick()
            advanceUntilIdle()
            settle(view)
            assertTrue(
                descendants(view).filterIsInstance<TextView>().any {
                    it.text.contains("view message")
                }
            )
            composer.setText("view private draft")
            advanceUntilIdle()
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            advanceUntilIdle()
            assertEquals("", composer.text.toString())
            settle(view)
            assertFalse(
                descendants(view).filterIsInstance<TextView>().any {
                    it.text.contains("view message")
                }
            )
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            advanceUntilIdle()
            assertEquals("view private draft", composer.text.toString())
            button(view, "Sign out of support").performClick()
            advanceUntilIdle()
            assertEquals("", composer.text.toString())
            assertTrue(session.state.value.signedOut)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            activity.finish()
        }

    @Test
    fun nativeViewAllowsLogoutWhileRequestIsPending() =
        runTest(dispatcher) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val owner = TestOwner()
            val accepted = CompletableDeferred<Unit>()
            val session =
                DaykeeperMessengerSession(FakeClient().apply { createCompletion = accepted })
            val view = DaykeeperMessengerView(activity)
            activity.setContentView(view)
            view.bind(session, owner)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            advanceUntilIdle()
            button(view, "New conversation").performClick()
            advanceUntilIdle()
            assertTrue(button(view, "Sign out of support").isEnabled)
            button(view, "Sign out of support").performClick()
            advanceUntilIdle()
            accepted.complete(Unit)
            advanceUntilIdle()
            assertTrue(session.state.value.signedOut)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            activity.finish()
        }

    @Test
    fun finalAuthenticationDenialsClearCustomerDataAndPreventFurtherWrites() =
        runTest(dispatcher) {
            for (status in listOf(401, 403)) {
                val client = FakeClient()
                val session = DaykeeperMessengerSession(client)
                session.resume()
                advanceUntilIdle()
                session.createConversation()
                advanceUntilIdle()
                session.setDraft("private customer draft")
                client.listFailure = responseError(status)
                session.refresh()
                advanceUntilIdle()
                assertTrue(session.state.value.signedOut)
                assertEquals("", session.state.value.draft)
                assertTrue(session.state.value.conversations.isEmpty())
                assertTrue(session.state.value.messages.isEmpty())
                session.createConversation()
                session.sendMessage()
                session.resume()
                advanceUntilIdle()
                assertEquals(1, client.creates)
                assertEquals(0, client.sends)
            }
        }

    @Test
    fun quotaRejectionPreservesDraftAndRequiresAnExplicitNextSend() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("keep my message")
            client.sendFailure = responseError(429, "daykeeper_usage_limit_exceeded")
            session.sendMessage()
            advanceUntilIdle()
            assertEquals("keep my message", session.state.value.draft)
            assertEquals("daykeeper_usage_limit_exceeded", session.state.value.errorCode)
            assertFalse(session.state.value.uncertainMessage)
            assertEquals(1, client.sends)
            client.sendFailure = null
            session.refresh()
            advanceUntilIdle()
            assertEquals(1, client.sends)
            session.sendMessage()
            advanceUntilIdle()
            assertEquals(2, client.sends)
            assertEquals(1, session.state.value.messages.size)
            session.reset()
        }

    @Test
    fun uncertainMessageCannotBeDiscardedUntilFreshHistorySucceeds() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("unconfirmed")
            client.sendFailure = responseError(500)
            session.sendMessage()
            advanceUntilIdle()
            assertTrue(session.state.value.uncertainMessage)
            assertFalse(session.state.value.recoveryReady)
            session.discardUncertainDraft()
            assertEquals("unconfirmed", session.state.value.draft)
            client.listFailure = responseError(503)
            session.refresh()
            advanceUntilIdle()
            session.discardUncertainDraft()
            assertTrue(session.state.value.uncertainMessage)
            assertFalse(session.state.value.recoveryReady)
            client.listFailure = null
            session.refresh()
            advanceUntilIdle()
            assertTrue(session.state.value.recoveryReady)
            client.listFailure = responseError(503)
            session.refresh()
            advanceUntilIdle()
            assertFalse(session.state.value.recoveryReady)
            client.listFailure = null
            session.refresh()
            advanceUntilIdle()
            session.discardUncertainDraft()
            assertFalse(session.state.value.uncertainMessage)
            assertEquals("", session.state.value.draft)
            assertEquals(1, client.sends)
            session.reset()
        }

    @Test
    fun uncertainCreationCannotBeAcknowledgedUntilFreshListSucceeds() =
        runTest(dispatcher) {
            val client = FakeClient().apply { createFailure = responseError(503) }
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.acknowledgeUncertainCreation()
            assertTrue(session.state.value.uncertainCreation)
            assertFalse(session.state.value.recoveryReady)
            session.createConversation()
            advanceUntilIdle()
            assertEquals(1, client.creates)
            client.listFailure = responseError(503)
            session.refresh()
            advanceUntilIdle()
            session.acknowledgeUncertainCreation()
            assertTrue(session.state.value.uncertainCreation)
            client.listFailure = null
            session.refresh()
            advanceUntilIdle()
            assertTrue(session.state.value.recoveryReady)
            session.acknowledgeUncertainCreation()
            assertFalse(session.state.value.uncertainCreation)
            assertEquals(1, client.creates)
            client.createFailure = null
            session.createConversation()
            advanceUntilIdle()
            assertEquals(2, client.creates)
            session.reset()
        }

    @Test
    fun nativeRecoveryControlIsDisabledUntilSuccessfulRefresh() =
        runTest(dispatcher) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val owner = TestOwner()
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            val view = DaykeeperMessengerView(activity)
            activity.setContentView(view)
            view.bind(session, owner)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            advanceUntilIdle()
            button(view, "New conversation").performClick()
            advanceUntilIdle()
            val composer = descendants(view).filterIsInstance<EditText>().single()
            composer.setText("unconfirmed")
            advanceUntilIdle()
            client.sendFailure = responseError(500)
            button(view, "Send message").performClick()
            advanceUntilIdle()
            assertFalse(button(view, "Discard uncertain draft").isEnabled)
            assertEquals("unconfirmed", composer.text.toString())
            button(view, "Refresh").performClick()
            advanceUntilIdle()
            assertTrue(button(view, "Discard uncertain draft").isEnabled)
            button(view, "Discard uncertain draft").performClick()
            advanceUntilIdle()
            assertEquals("", composer.text.toString())
            assertEquals(1, client.sends)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            session.reset()
            activity.finish()
        }

    @Test
    fun expiredTokenOnSendKeepsDraftAndHistoryAndMarksItForReview() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("first")
            session.sendMessage()
            advanceUntilIdle()
            assertEquals(1, session.state.value.messages.size)
            session.setDraft("please keep this")
            client.sendFailure = responseError(401, "expired_token")
            session.sendMessage()
            advanceUntilIdle()
            assertFalse("One expired token must not sign the customer out", session.state.value.signedOut)
            assertEquals("please keep this", session.state.value.draft)
            assertEquals(1, session.state.value.messages.size)
            assertTrue("The send needs review, not a resend", session.state.value.uncertainMessage)
            assertEquals("Exactly one recovery read", 1, client.identityReads)
            assertEquals("The write is never replayed", 2, client.sends)
            session.reset()
        }

    @Test
    fun expiredTokenOnSendSignsOutOnlyWhenTheRefreshedReadAlsoFails() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            session.setDraft("gone with the session")
            client.sendFailure = responseError(401, "expired_token")
            client.identityFailure = responseError(401, "expired_token")
            session.sendMessage()
            advanceUntilIdle()
            assertTrue(session.state.value.signedOut)
            assertEquals("", session.state.value.draft)
            assertTrue(session.state.value.messages.isEmpty())
            assertEquals(1, client.identityReads)
            assertEquals(1, client.sends)
        }

    @Test
    fun refreshPagesFromTheLastMessageSoAnOversizedThreadStaysReadable() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            client.messages.add(message(1))
            session.refresh()
            advanceUntilIdle()
            assertEquals(listOf(1L), session.state.value.messages.map { it.id })
            // The thread has grown past the transport ceiling: reading the whole
            // conversation in one response would now fail outright.
            client.uncursoredHistoryFailure = responseError(500)
            client.messages.add(message(2))
            client.cursors.clear()
            session.refresh()
            advanceUntilIdle()
            assertNull(session.state.value.errorCode)
            assertEquals(listOf(1L, 2L), session.state.value.messages.map { it.id })
            assertEquals("Refresh asks only for messages after the last one held", listOf<Long?>(1L), client.cursors)
            session.reset()
        }

    @Test
    fun loadEarlierMessagesMergesWithoutDroppingWhatIsAlreadyLoaded() =
        runTest(dispatcher) {
            val client = FakeClient()
            val session = DaykeeperMessengerSession(client)
            session.resume()
            advanceUntilIdle()
            session.createConversation()
            advanceUntilIdle()
            client.messages.add(message(5))
            session.refresh()
            advanceUntilIdle()
            assertEquals(listOf(5L), session.state.value.messages.map { it.id })
            assertTrue(session.state.value.canLoadEarlier)
            client.messages.add(0, message(4))
            client.messages.add(0, message(3))
            session.refresh()
            advanceUntilIdle()
            assertEquals("A cursored refresh cannot see older messages", listOf(5L), session.state.value.messages.map { it.id })
            session.loadEarlierMessages()
            advanceUntilIdle()
            assertEquals(listOf(3L, 4L, 5L), session.state.value.messages.map { it.id })
            assertEquals("Reading history is never a write", 0, client.sends)
            session.reset()
        }

    // Obtain the SDK's real sanitized error through its public API; no internal-constructor bypass.
    private fun responseError(status: Int, code: String = "support_upstream_unavailable") =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse().setResponseCode(status).setBody("{\"error\":\"$code\"}")
                )
                val client =
                    DaykeeperClient(
                        server.url("/").toString(),
                        DaykeeperTokenProvider { "synthetic" },
                        1_000,
                    )
                try {
                    client.createConversation()
                    error("Expected fixture failure")
                } catch (error: DaykeeperException) {
                    assertEquals(status, error.status)
                    error
                }
            }
        }

    /**
     * RecyclerView rows appear only after the list diff is dispatched on the main looper and the
     * hierarchy has had a measure/layout pass.
     */
    private fun settle(view: View) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
    }

    private fun message(id: Long, conversationId: Long = 1L) =
        DaykeeperMessage(
            id,
            conversationId,
            "Message $id",
            "text",
            JsonObject(emptyMap()),
            1,
            null,
            null,
            emptyList(),
        )

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun button(view: View, text: String) =
        descendants(view).filterIsInstance<Button>().single { it.text.toString() == text }

    private class TestOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private class FakeClient : DaykeeperCustomerClient {
        var sends = 0
        var creates = 0
        var seenWrites = 0
        var unread = 0
        var unreadAfterSeen = 0
        var sendCompletion: CompletableDeferred<Unit>? = null
        var createCompletion: CompletableDeferred<Unit>? = null
        var createFailure: DaykeeperException? = null
        var sendFailure: DaykeeperException? = null
        var listFailure: DaykeeperException? = null
        var identityFailure: DaykeeperException? = null
        var uncursoredHistoryFailure: DaykeeperException? = null
        var identityReads = 0
        val cursors = mutableListOf<Long?>()
        val threads = mutableListOf<DaykeeperConversation>()
        val messages = mutableListOf<DaykeeperMessage>()

        override suspend fun getIdentity(): DaykeeperCustomerIdentity {
            identityReads++
            identityFailure?.let { throw it }
            return DaykeeperCustomerIdentity(
                "https://support.example",
                "demo",
                "demo",
                "demo",
                "demo",
                null,
                "Demo",
            )
        }

        override suspend fun listConversations(): DaykeeperConversationList {
            listFailure?.let { throw it }
            return DaykeeperConversationList(
                threads.map { it.copy(unreadForContact = unread) },
                null,
            )
        }

        override suspend fun createConversation(): DaykeeperConversationResult {
            creates++
            createFailure?.let { throw it }
            val item =
                DaykeeperConversation(threads.size + 1L, "open", null, null, 0, unread, null, "")
            threads.add(item)
            withContext(NonCancellable) { createCompletion?.await() }
            return DaykeeperConversationResult(item)
        }

        override suspend fun getUnread() = DaykeeperUnreadSummary(unread, null, threads)

        override suspend fun markConversationSeen(conversationId: Long): DaykeeperSeenResult {
            seenWrites++
            unread = unreadAfterSeen
            return DaykeeperSeenResult(conversationId, true, 0)
        }

        override suspend fun listMessages(
            conversationId: Long,
            after: Long?,
        ): DaykeeperMessageList {
            cursors.add(after)
            if (after == null) uncursoredHistoryFailure?.let { throw it }
            return DaykeeperMessageList(
                messages.filter {
                    it.conversationId == conversationId && (after == null || it.id > after)
                }
            )
        }

        override suspend fun sendMessage(
            conversationId: Long,
            content: String,
        ): DaykeeperMessageResult {
            sends++
            sendFailure?.let { throw it }
            val item =
                DaykeeperMessage(
                    messages.size + 1L,
                    conversationId,
                    content,
                    "text",
                    JsonObject(emptyMap()),
                    0,
                    null,
                    null,
                    emptyList(),
                )
            messages.add(item)
            withContext(NonCancellable) { sendCompletion?.await() }
            return DaykeeperMessageResult(item)
        }

        override suspend fun claimAnonymousConversation(widgetToken: String) =
            DaykeeperClaimConversationResult("not_found", 0)
    }
}
