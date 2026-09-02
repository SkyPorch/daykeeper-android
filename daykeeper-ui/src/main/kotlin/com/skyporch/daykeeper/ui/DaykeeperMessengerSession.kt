package com.skyporch.daykeeper.ui

import android.os.Looper
import com.skyporch.daykeeper.DaykeeperConversation
import com.skyporch.daykeeper.DaykeeperCustomerClient
import com.skyporch.daykeeper.DaykeeperException
import com.skyporch.daykeeper.DaykeeperMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DaykeeperMessengerState(
    val conversations: List<DaykeeperConversation> = emptyList(),
    val conversationId: Long? = null,
    val messages: List<DaykeeperMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val suspended: Boolean = true,
    val signedOut: Boolean = false,
    val errorCode: String? = null,
    val uncertainMessage: Boolean = false,
    val uncertainCreation: Boolean = false,
    /** A fresh post-failure read completed; the user must still explicitly review and confirm. */
    val recoveryReady: Boolean = false,
    /** A thread is open and at least one page of history is loaded. */
    val canLoadEarlier: Boolean = false,
)

/**
 * One session per signed-in customer; all methods must run on the main thread. Keep in a host
 * ViewModel across configuration changes. reset() permanently revokes this session. State and
 * drafts live only in memory. Never persist the session or its token provider in a Bundle.
 */
class DaykeeperMessengerSession(client: DaykeeperCustomerClient) {
    private var client: DaykeeperCustomerClient? = client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(DaykeeperMessengerState())
    val state: StateFlow<DaykeeperMessengerState> = mutableState.asStateFlow()
    private val drafts = mutableMapOf<Long, String>()
    private val uncertain = mutableSetOf<Long>()
    private val reviewedUncertain = mutableSetOf<Long>()
    private var selected: Long? = null
    private var creationUncertain = false
    private var creationReviewed = false
    private var revision = 0L
    private var job: Job? = null
    private var write: String? = null

    fun resume() {
        main()
        if (client == null || !state.value.suspended) return
        mutableState.value = snapshot()
        refresh()
    }

    fun suspend() {
        main()
        if (state.value.suspended) return
        cancelOperation()
        reviewedUncertain.clear()
        creationReviewed = false
        mutableState.value = DaykeeperMessengerState(suspended = true)
    }

    fun reset() {
        main()
        revision++
        job?.cancel()
        scope.cancel()
        client = null
        drafts.clear()
        uncertain.clear()
        reviewedUncertain.clear()
        selected = null
        creationUncertain = false
        creationReviewed = false
        write = null
        mutableState.value = DaykeeperMessengerState(signedOut = true)
    }

    fun setDraft(value: String) {
        main()
        val id = selected ?: return
        if (!usable() || state.value.busy || id in uncertain || value.length > 16_000) return
        drafts[id] = value
        mutableState.value = state.value.copy(draft = value)
    }

    fun showConversations() {
        main()
        if (!usable() || state.value.busy) return
        selected = null
        mutableState.value = snapshot(state.value.conversations)
        refresh()
    }

    fun openConversation(id: Long) {
        main()
        if (!usable() || state.value.busy || state.value.conversations.none { it.id == id }) return
        selected = id
        mutableState.value = snapshot(state.value.conversations)
        refresh()
    }

    fun refresh() = operate {
        selected?.let { id -> reviewedUncertain.remove(id) }
        creationReviewed = false
        val summaries = it.listConversations().conversations
        currentCoroutineContext().ensureActive()
        // Ask only for what is new. Re-reading a long thread in full can exceed the
        // transport's response ceiling and make the whole conversation unreadable.
        val cursor = state.value.messages.lastOrNull()?.id
        val messages =
            selected?.let { id -> merge(state.value.messages, it.listMessages(id, cursor).messages) }
                ?: emptyList()
        currentCoroutineContext().ensureActive()
        selected?.takeIf { id -> id in uncertain }?.let { id -> reviewedUncertain.add(id) }
        if (selected == null && creationUncertain) creationReviewed = true
        snapshot(summaries, messages)
    }

    fun createConversation() {
        main()
        if (creationUncertain) return
        operate("create") {
            val item = it.createConversation().conversation
            currentCoroutineContext().ensureActive()
            selected = item.id
            snapshot(
                listOf(item) +
                    state.value.conversations.filterNot { existing -> existing.id == item.id }
            )
        }
    }

    fun sendMessage() {
        main()
        val id = selected ?: return
        val content = drafts[id]?.trim().orEmpty()
        if (content.isEmpty() || id in uncertain) return
        operate("send") {
            val message = it.sendMessage(id, content).message
            currentCoroutineContext().ensureActive()
            drafts.remove(id)
            snapshot(
                state.value.conversations,
                (state.value.messages.filterNot { prior -> prior.id == message.id } + message)
                    .sortedBy { item -> item.id },
            )
        }
    }

    fun markRead() {
        main()
        val id = selected ?: return
        operate("seen") {
            it.markConversationSeen(id)
            currentCoroutineContext().ensureActive()
            // A new message may arrive after the seen write. Use the server count, not a local
            // zero.
            val summaries = it.listConversations().conversations
            currentCoroutineContext().ensureActive()
            snapshot(summaries, state.value.messages)
        }
    }

    /**
     * Ask the gateway for the conversation's default window again and fold in anything this client
     * does not already hold. The customer contract exposes only a forward `after` cursor, so this is
     * the widest backward request the SDK can make. It never drops loaded history and never writes.
     */
    fun loadEarlierMessages() {
        main()
        val id = selected ?: return
        if (!state.value.canLoadEarlier) return
        operate {
            val page = it.listMessages(id, null).messages
            currentCoroutineContext().ensureActive()
            snapshot(state.value.conversations, merge(state.value.messages, page))
        }
    }

    /** After reading refreshed history, discard the uncertain draft; this never sends a message. */
    fun discardUncertainDraft() {
        main()
        val id = selected ?: return
        if (!usable() || state.value.busy || id !in uncertain || id !in reviewedUncertain) return
        drafts.remove(id)
        uncertain.remove(id)
        reviewedUncertain.remove(id)
        mutableState.value = snapshot(state.value.conversations, state.value.messages)
    }

    /** Explicit acknowledgement after reviewing the list. Does not create or repeat a request. */
    fun acknowledgeUncertainCreation() {
        main()
        if (!usable() || state.value.busy || selected != null || !creationReviewed) return
        creationUncertain = false
        creationReviewed = false
        mutableState.value = snapshot(state.value.conversations)
    }

    private fun operate(
        kind: String? = null,
        block: suspend (DaykeeperCustomerClient) -> DaykeeperMessengerState,
    ) {
        main()
        val activeClient = client ?: return
        if (!usable() || state.value.busy) return
        val current = ++revision
        write = kind
        mutableState.value = state.value.copy(busy = true, errorCode = null)
        job = scope.launch {
            try {
                val result = block(activeClient)
                if (revision == current) mutableState.value = result
            } catch (error: Exception) {
                if (revision != current) return@launch
                val safe = error as? DaykeeperException
                if (safe?.status == 401 || safe?.status == 403) {
                    if (kind == null) {
                        // A rejected read means the credential is gone. Revoked access must
                        // not leave previously loaded customer data on screen.
                        reset()
                        return@launch
                    }
                    // A write may have been accepted before the token expired. Do not throw the
                    // draft and the history away on the first rejection: ask for one fresh token
                    // and prove the customer is still signed in with a read. The write itself is
                    // never resent.
                    val stillSignedIn =
                        try {
                            activeClient.getIdentity()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    if (revision != current) return@launch
                    if (!stillSignedIn) {
                        reset()
                        return@launch
                    }
                    markUncertain()
                    mutableState.value =
                        snapshot(state.value.conversations, state.value.messages)
                            .copy(errorCode = safe.code)
                    return@launch
                }
                if (kind != null && (safe == null || safe.outcomeUnknown)) markUncertain()
                mutableState.value =
                    snapshot(state.value.conversations, state.value.messages)
                        .copy(errorCode = safe?.code ?: "daykeeper_request_failed")
            } finally {
                if (revision == current) {
                    write = null
                    mutableState.value = state.value.copy(busy = false)
                }
            }
        }
    }

    private fun cancelOperation() {
        revision++
        markUncertain()
        write = null
        job?.cancel()
    }

    /** Pages may overlap; fold by identifier and keep the gateway's monotonic order. */
    private fun merge(existing: List<DaykeeperMessage>, incoming: List<DaykeeperMessage>) =
        if (incoming.isEmpty()) existing
        else
            (existing.associateBy { it.id } + incoming.associateBy { it.id })
                .values
                .sortedBy { it.id }

    private fun markUncertain() {
        if (write == "create") {
            creationUncertain = true
            creationReviewed = false
        }
        if (write == "send")
            selected?.let {
                uncertain.add(it)
                reviewedUncertain.remove(it)
            }
    }

    private fun snapshot(
        conversations: List<DaykeeperConversation> = emptyList(),
        messages: List<DaykeeperMessage> = emptyList(),
    ) =
        DaykeeperMessengerState(
            conversations,
            selected,
            messages,
            drafts[selected].orEmpty(),
            suspended = false,
            uncertainMessage = selected in uncertain,
            uncertainCreation = creationUncertain,
            recoveryReady = selected?.let { it in reviewedUncertain } ?: creationReviewed,
            canLoadEarlier = selected != null && messages.isNotEmpty(),
        )

    private fun usable() = client != null && !state.value.suspended

    private fun main() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Daykeeper messenger requires the main thread"
        }
    }
}
