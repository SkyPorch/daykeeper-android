package com.skyporch.daykeeper.ui

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Plain-text native messenger. Bind one view to one customer session and its view lifecycle. */
class DaykeeperMessengerView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    private var session: DaykeeperMessengerSession? = null
    private var owner: LifecycleOwner? = null
    private var collection: Job? = null
    private var previous: DaykeeperMessengerState? = null
    private val heading = label("Support", 24f)
    private val status =
        label("", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val back = action("Conversations") { session?.showConversations() }
    private val refresh = action("Refresh") { session?.refresh() }
    private val logout = action("Sign out of support") { session?.reset() }
    private val create = action("New conversation") { session?.createConversation() }
    private val seen = action("Mark read") { session?.markRead() }
    private val recover =
        action("Review complete") {
            if (session?.state?.value?.conversationId == null)
                session?.acknowledgeUncertainCreation()
            else session?.discardUncertainDraft()
        }
    private val composer =
        EditText(context).apply {
            hint = "Write a message"
            contentDescription = "Message"
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(16_000))
            if (Build.VERSION.SDK_INT >= 26)
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (Build.VERSION.SDK_INT >= 26)
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        session?.setDraft(s?.toString().orEmpty())
                    }

                    override fun afterTextChanged(s: Editable?) = Unit
                }
            )
        }
    private val send =
        action("Send message") {
            session?.sendMessage()
            composer.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        }
    private val observer =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                start()
            }

            override fun onStop(owner: LifecycleOwner) {
                stop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                unbind()
            }
        }

    init {
        isFillViewport = true
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= 26)
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30)
            importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        column.addView(heading)
        column.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(back, refresh, logout).forEach {
                    addView(it, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
            }
        )
        column.addView(status)
        // The whole messenger scrolls when large text/IME insets consume the viewport;
        // fixed composer/error chrome must not make history impossible to review.
        column.addView(
            rows,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f),
        )
        column.addView(recover)
        column.addView(create)
        column.addView(seen)
        column.addView(composer)
        column.addView(send)
        addView(column)
        render(DaykeeperMessengerState())
    }

    fun bind(session: DaykeeperMessengerSession, lifecycleOwner: LifecycleOwner) {
        check(this.session == null) { "Unbind the old messenger before binding another session" }
        check(lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            "Lifecycle is destroyed"
        }
        this.session = session
        owner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
    }

    fun unbind() {
        stop()
        owner?.lifecycle?.removeObserver(observer)
        owner = null
        session = null
        previous = null
        render(DaykeeperMessengerState())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (collection != null) return
        val bound = session ?: return
        bound.resume()
        render(bound.state.value)
        collection = owner?.lifecycleScope?.launch { bound.state.collect { render(it) } }
    }

    private fun stop() {
        collection?.cancel()
        collection = null
        session?.suspend()
        render(DaykeeperMessengerState())
    }

    private fun render(state: DaykeeperMessengerState) {
        val active = !state.suspended && !state.signedOut
        val thread = active && state.conversationId != null
        heading.text = if (thread) "Conversation ${state.conversationId}" else "Support"
        status.text =
            when {
                state.signedOut -> "Signed out of support. Sign in again through the app."
                !active -> "Support is paused."
                state.uncertainMessage ->
                    "Delivery is unconfirmed. Refresh and review the conversation before discarding this draft. No message will be resent automatically."
                state.uncertainCreation ->
                    "Creation is unconfirmed. Refresh and review your conversations before starting another."
                state.errorCode == "daykeeper_usage_limit_exceeded" ->
                    "Your workspace has reached its support limit. Existing conversations are still available."
                state.errorCode != null -> "Unable to finish. Refresh to check the latest state."
                state.busy -> "Updating…"
                else -> ""
            }
        back.visibility = if (thread) VISIBLE else GONE
        create.visibility = if (active && !thread) VISIBLE else GONE
        seen.visibility = if (thread) VISIBLE else GONE
        composer.visibility = if (thread) VISIBLE else GONE
        send.visibility = if (thread) VISIBLE else GONE
        recover.visibility =
            if (active && (if (thread) state.uncertainMessage else state.uncertainCreation)) VISIBLE
            else GONE
        recover.text = if (thread) "Discard uncertain draft" else "I reviewed the conversations"
        listOf(back, refresh, logout, seen, recover).forEach {
            it.isEnabled = active && !state.busy
        }
        logout.isEnabled = active
        recover.isEnabled = active && !state.busy && state.recoveryReady
        create.isEnabled = active && !state.busy && !state.uncertainCreation
        composer.isEnabled = thread && !state.busy && !state.uncertainMessage
        send.isEnabled = composer.isEnabled && state.draft.isNotBlank()
        if (composer.text.toString() != state.draft) composer.setText(state.draft)
        val prior = previous
        if (
            prior == null ||
                prior.conversations != state.conversations ||
                prior.messages != state.messages ||
                prior.conversationId != state.conversationId ||
                prior.suspended != state.suspended ||
                prior.signedOut != state.signedOut
        ) {
            rows.removeAllViews()
            if (active) {
                if (thread) {
                    if (state.messages.isEmpty())
                        rows.addView(label("No messages yet. Write the first message below."))
                    state.messages.forEach { message ->
                        rows.addView(
                            label(
                                "${message.sender?.name ?: if (message.messageType == 0) "You" else "Support"}\n${message.content.orEmpty()}"
                            )
                        )
                        if (message.attachments.isNotEmpty())
                            rows.addView(
                                label(
                                    "${message.attachments.size} attachment(s). Viewing attachments is not yet supported."
                                )
                            )
                    }
                } else {
                    if (state.conversations.isEmpty())
                        rows.addView(label("No conversations yet. Start one when you need help."))
                    state.conversations.forEach { item ->
                        rows.addView(
                            action(
                                "Conversation ${item.id} · ${item.status} · ${item.unreadForContact} unread\n${item.preview.orEmpty()}"
                            ) {
                                session?.openConversation(item.id)
                            }
                        )
                    }
                }
            }
        }
        previous = state
    }

    private fun label(value: String, size: Float = 16f) =
        TextView(context).apply {
            text = value
            textSize = size
            setPadding(0, dp(8), 0, dp(8))
            isSaveEnabled = false
            // Never render arbitrary HTML, load remote avatars or turn response text into links.
            autoLinkMask = 0
        }

    private fun action(value: String, click: () -> Unit) =
        Button(context).apply {
            text = value
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { click() }
            isSaveEnabled = false
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
