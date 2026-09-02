package com.skyporch.daykeeper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Conversation status as the gateway reports it. The set of statuses is open: a status this SDK
 * release has never heard of is carried through as [Unknown] instead of failing the whole list.
 */
sealed class DaykeeperConversationStatus(val raw: String) {
    object Open : DaykeeperConversationStatus("open")

    object Pending : DaykeeperConversationStatus("pending")

    object Resolved : DaykeeperConversationStatus("resolved")

    object Snoozed : DaykeeperConversationStatus("snoozed")

    /** A status added by the gateway after this SDK release. Read [raw] to display or log it. */
    class Unknown(raw: String) : DaykeeperConversationStatus(raw) {
        override fun equals(other: Any?) = other is Unknown && other.raw == raw

        override fun hashCode() = raw.hashCode()
    }

    override fun toString() = raw

    companion object {
        fun of(raw: String): DaykeeperConversationStatus =
            when (raw) {
                Open.raw -> Open
                Pending.raw -> Pending
                Resolved.raw -> Resolved
                Snoozed.raw -> Snoozed
                else -> Unknown(raw)
            }
    }
}

/** Timestamps remain JSON integers, strings or null; the SDK does not infer local time. */
@Serializable
data class DaykeeperConversation(
    val id: Long,
    val status: String,
    val createdAt: JsonElement?,
    val updatedAt: JsonElement?,
    val unreadCount: Int,
    /** Customer badge count. unreadCount is the agent-side count. */
    val unreadForContact: Int,
    val lastSeenAt: Long?,
    val preview: String?,
) {
    /** Typed view of [status]. Never throws; an unfamiliar value becomes Unknown. */
    val conversationStatus: DaykeeperConversationStatus
        get() = DaykeeperConversationStatus.of(status)
}

@Serializable
data class DaykeeperAttachment(
    val id: Long,
    val fileType: String?,
    val dataUrl: String?,
    val thumbUrl: String?,
)

@Serializable data class DaykeeperMessageSender(val name: String?, val avatarUrl: String?)

@Serializable
data class DaykeeperMessage(
    val id: Long,
    val conversationId: Long,
    val content: String?,
    val contentType: String,
    val contentAttributes: JsonObject,
    val messageType: Int,
    val createdAt: JsonElement?,
    val sender: DaykeeperMessageSender?,
    val attachments: List<DaykeeperAttachment>,
)

@Serializable
data class DaykeeperCustomerIdentity(
    val baseUrl: String,
    val websiteToken: String,
    val subject: String,
    val identifier: String,
    val identifierHash: String,
    val email: String?,
    val name: String,
)

@Serializable
data class DaykeeperConversationList(
    val conversations: List<DaykeeperConversation>,
    val widgetConversationId: Long?,
)

@Serializable data class DaykeeperConversationResult(val conversation: DaykeeperConversation)

@Serializable
data class DaykeeperUnreadSummary(
    val unreadCount: Int,
    val conversation: DaykeeperConversation?,
    val conversations: List<DaykeeperConversation>,
)

@Serializable
data class DaykeeperSeenResult(
    val conversationId: Long,
    val seen: Boolean,
    val seenAt: Long,
    val seenMessageId: Long? = null,
)

@Serializable data class DaykeeperMessageList(val messages: List<DaykeeperMessage>)

@Serializable data class DaykeeperMessageResult(val message: DaykeeperMessage)

@Serializable
data class DaykeeperClaimConversationResult(val status: String, val conversations: Int)

/** One immutable customer identity per client. Recreate the client on account changes. */
fun interface DaykeeperTokenProvider {
    suspend fun token(forceRefresh: Boolean): String
}

/** Customer capabilities only. No tenant management, lifecycle campaign or erasure APIs. */
interface DaykeeperCustomerClient {
    suspend fun getIdentity(): DaykeeperCustomerIdentity

    suspend fun listConversations(): DaykeeperConversationList

    suspend fun createConversation(): DaykeeperConversationResult

    suspend fun getUnread(): DaykeeperUnreadSummary

    suspend fun markConversationSeen(conversationId: Long): DaykeeperSeenResult

    suspend fun listMessages(conversationId: Long, after: Long? = null): DaykeeperMessageList

    suspend fun sendMessage(conversationId: Long, content: String): DaykeeperMessageResult

    suspend fun claimAnonymousConversation(widgetToken: String): DaykeeperClaimConversationResult
}
