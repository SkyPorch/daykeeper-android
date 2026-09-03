package com.skyporch.daykeeper.ui

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val heading = label(string(R.string.daykeeper_title), 24f)
    private val status =
        label("", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val rowAdapter = RowAdapter()
    // A list adapter with a diff so a new message rebinds one row instead of rebuilding the
    // whole thread. Scrolling stays with the outer ScrollView so large text and IME insets keep
    // working exactly as before.
    private val rows =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rowAdapter
            isNestedScrollingEnabled = false
            itemAnimator = null
            isSaveEnabled = false
        }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val back = action(string(R.string.daykeeper_back)) { session?.showConversations() }
    private val refresh = action(string(R.string.daykeeper_refresh)) { session?.refresh() }
    private val logout = action(string(R.string.daykeeper_sign_out)) { session?.reset() }
    private val create =
        action(string(R.string.daykeeper_new_conversation)) { session?.createConversation() }
    private val seen = action(string(R.string.daykeeper_mark_read)) { session?.markRead() }
    private val recover =
        action(string(R.string.daykeeper_review_complete)) {
            if (session?.state?.value?.conversationId == null)
                session?.acknowledgeUncertainCreation()
            else session?.discardUncertainDraft()
        }
    private val composer =
        EditText(context).apply {
            hint = string(R.string.daykeeper_composer_hint)
            contentDescription = string(R.string.daykeeper_composer_description)
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
        action(string(R.string.daykeeper_send)) {
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
        heading.text =
            if (thread)
                string(R.string.daykeeper_conversation_heading, state.conversationId ?: 0L)
            else string(R.string.daykeeper_title)
        status.text =
            when {
                state.signedOut -> string(R.string.daykeeper_status_signed_out)
                !active -> string(R.string.daykeeper_status_paused)
                state.uncertainMessage -> string(R.string.daykeeper_status_uncertain_message)
                state.uncertainCreation -> string(R.string.daykeeper_status_uncertain_creation)
                state.errorCode == "daykeeper_usage_limit_exceeded" ->
                    string(R.string.daykeeper_status_usage_limit)
                state.errorCode != null -> string(R.string.daykeeper_status_error)
                state.busy -> string(R.string.daykeeper_status_busy)
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
        recover.text =
            if (thread) string(R.string.daykeeper_discard_draft)
            else string(R.string.daykeeper_reviewed_conversations)
        listOf(back, refresh, logout, seen, recover).forEach {
            it.isEnabled = active && !state.busy
        }
        logout.isEnabled = active
        recover.isEnabled = active && !state.busy && state.recoveryReady
        create.isEnabled = active && !state.busy && !state.uncertainCreation
        composer.isEnabled = thread && !state.busy && !state.uncertainMessage
        send.isEnabled = composer.isEnabled && state.draft.isNotBlank()
        if (composer.text.toString() != state.draft) composer.setText(state.draft)
        if (active) rowAdapter.submit(rowsFor(state, thread)) else redactRows()
    }

    /**
     * Clearing must take effect now, not at the next layout pass: stopping the lifecycle has to
     * hide the conversation immediately. Detaching the adapter removes the attached row views
     * synchronously; re-attaching leaves an empty list.
     */
    private fun redactRows() {
        rowAdapter.submit(emptyList())
        rows.adapter = null
        rows.adapter = rowAdapter
    }

    private fun rowsFor(state: DaykeeperMessengerState, thread: Boolean): List<Row> {
        if (thread) {
            if (state.messages.isEmpty())
                return listOf(Row.Text("empty", string(R.string.daykeeper_empty_messages)))
            return state.messages.flatMap { message ->
                val author =
                    message.sender?.name
                        ?: string(
                            if (message.messageType == 0) R.string.daykeeper_sender_you
                            else R.string.daykeeper_sender_support
                        )
                val body =
                    Row.Text(
                        "message-${message.id}",
                        string(R.string.daykeeper_message_row, author, message.content.orEmpty()),
                    )
                if (message.attachments.isEmpty()) listOf(body)
                else
                    listOf(
                        body,
                        Row.Text(
                            "attachments-${message.id}",
                            string(R.string.daykeeper_attachments, message.attachments.size),
                        ),
                    )
            }
        }
        if (state.conversations.isEmpty())
            return listOf(Row.Text("empty", string(R.string.daykeeper_empty_conversations)))
        return state.conversations.map { item ->
            Row.Action(
                "conversation-${item.id}",
                string(
                    R.string.daykeeper_conversation_row,
                    item.id,
                    item.status,
                    item.unreadForContact,
                    item.preview.orEmpty(),
                ),
                item.id,
            )
        }
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

    private fun string(id: Int, vararg arguments: Any) =
        if (arguments.isEmpty()) context.getString(id) else context.getString(id, *arguments)

    /** One rendered line. [key] identifies the line so a diff can leave untouched rows alone. */
    private sealed interface Row {
        val key: String
        val text: String

        data class Text(override val key: String, override val text: String) : Row

        data class Action(
            override val key: String,
            override val text: String,
            val conversationId: Long,
        ) : Row
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        private var items = emptyList<Row>()

        /**
         * Diffing happens here on the caller's thread rather than through AsyncListDiffer, so the
         * visible list always matches the state that was just rendered and a redaction takes
         * effect immediately instead of on a posted callback.
         */
        fun submit(next: List<Row>) {
            val previous = items
            items = next
            DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize() = previous.size

                        override fun getNewListSize() = next.size

                        override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                            previous[oldPosition].key == next[newPosition].key

                        override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                            previous[oldPosition] == next[newPosition]
                    },
                    false,
                )
                .dispatchUpdatesTo(this)
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Row.Action) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val view = if (viewType == 1) action("") {} else label("")
            view.layoutParams =
                RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    // Rows used to be siblings in a LinearLayout and kept a little air between
                    // them; RecyclerView.LayoutParams starts with no margins at all.
                    .apply { setMargins(0, dp(4), 0, dp(4)) }
            return RowHolder(view)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Text -> (holder.itemView as TextView).text = row.text
                is Row.Action ->
                    (holder.itemView as Button).apply {
                        text = row.text
                        setOnClickListener { session?.openConversation(row.conversationId) }
                    }
            }
        }
    }
}
