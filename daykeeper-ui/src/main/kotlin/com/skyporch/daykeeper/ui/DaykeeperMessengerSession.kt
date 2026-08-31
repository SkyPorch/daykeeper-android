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
        val messages = selected?.let { id -> it.listMessages(id).messages } ?: emptyList()
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
                    reset()
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
        )

    private fun usable() = client != null && !state.value.suspended

    private fun main() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Daykeeper messenger requires the main thread"
        }
    }
}
