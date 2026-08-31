package com.skyporch.daykeeper.example

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import com.skyporch.daykeeper.*
import com.skyporch.daykeeper.ui.DaykeeperMessengerSession
import com.skyporch.daykeeper.ui.DaykeeperMessengerView
import kotlinx.serialization.json.JsonObject

/** Synthetic in-memory demo. No tokens, production gateway, persistent data or network requests. */
class MainActivity : ComponentActivity() {
    private val model: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            TextView(this).apply {
                text = "Daykeeper · offline demo data only"
                textSize = 16f
            }
        )
        val messenger =
            DaykeeperMessengerView(this).apply { bind(model.session, this@MainActivity) }
        root.addView(
            Button(this).apply {
                text = "Switch demo customer"
                isAllCaps = false
                setOnClickListener {
                    messenger.unbind()
                    model.switchCustomer()
                    messenger.bind(model.session, this@MainActivity)
                }
            }
        )
        root.addView(
            messenger,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bounds =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                )
            view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom)
            insets
        }
        setContentView(root)
    }
}

class DemoViewModel : ViewModel() {
    var session = DaykeeperMessengerSession(DemoClient())
        private set

    fun switchCustomer() {
        session.reset()
        session = DaykeeperMessengerSession(DemoClient())
    }

    override fun onCleared() {
        session.reset()
    }
}

private class DemoClient : DaykeeperCustomerClient {
    private val threads = mutableListOf<DaykeeperConversation>()
    private val messages = mutableListOf<DaykeeperMessage>()

    override suspend fun getIdentity() =
        DaykeeperCustomerIdentity(
            "https://support.example",
            "demo",
            "demo",
            "demo",
            "demo",
            null,
            "Demo",
        )

    override suspend fun listConversations() = DaykeeperConversationList(threads.toList(), null)

    override suspend fun createConversation(): DaykeeperConversationResult {
        val item = DaykeeperConversation(threads.size + 1L, "open", null, null, 0, 0, null, "")
        threads.add(item)
        return DaykeeperConversationResult(item)
    }

    override suspend fun getUnread() = DaykeeperUnreadSummary(0, null, threads.toList())

    override suspend fun markConversationSeen(conversationId: Long) =
        DaykeeperSeenResult(conversationId, true, 0)

    override suspend fun listMessages(conversationId: Long, after: Long?) =
        DaykeeperMessageList(
            messages.filter {
                it.conversationId == conversationId && (after == null || it.id > after)
            }
        )

    override suspend fun sendMessage(
        conversationId: Long,
        content: String,
    ): DaykeeperMessageResult {
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
        return DaykeeperMessageResult(item)
    }

    override suspend fun claimAnonymousConversation(widgetToken: String) =
        DaykeeperClaimConversationResult("not_found", 0)
}
