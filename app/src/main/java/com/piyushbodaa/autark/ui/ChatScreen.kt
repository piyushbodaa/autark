package com.piyushbodaa.autark.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piyushbodaa.autark.data.Message
import com.piyushbodaa.autark.engine.LlamaEngine
import com.piyushbodaa.autark.ui.theme.LocalPalette
import com.piyushbodaa.autark.ui.theme.Type
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ChatScreen(vm: AppViewModel, onOpenDrawer: () -> Unit) {
    val p = LocalPalette.current
    val conversation by vm.current.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val shared by vm.shared.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // The composer's text lives here, so that Edit can fill it in.
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // The chat whose last message is being edited; another chat on screen means no edit.
    var editedChat by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editedChat == conversation.id
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val asking = shared != null && conversation.messages.isEmpty()

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.dismissNotice()
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.paper),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            TopBar(
                engine = engine,
                canStartNew = conversation.messages.isNotEmpty() || shared != null,
                onOpenDrawer = onOpenDrawer,
                onNewChat = vm::newChat,
            )
            Box(Modifier.weight(1f)) {
                when {
                    asking -> SharedText(shared!!, onPick = vm::sendShared, onDismiss = vm::dropShared)
                    conversation.messages.isEmpty() -> EmptyChat(onPick = vm::send)
                    else -> Messages(
                        messages = conversation.messages,
                        generating = generating,
                        reading = reading,
                        engine = engine,
                        onRegenerate = vm::regenerate,
                        onContinue = vm::continueReply,
                        onEdit = { text ->
                            draft = TextFieldValue(text, TextRange(text.length))
                            editedChat = conversation.id
                        },
                    )
                }
            }
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 12.dp)) {
                    Fine("Editing your message", upper = true, modifier = Modifier.weight(1f))
                    NavText("Cancel", onClick = {
                        editedChat = null
                        draft = TextFieldValue()
                    })
                }
            }
            Composer(
                value = draft,
                onValueChange = {
                    draft = it
                    if (it.text.isNotBlank()) vm.onTyping()
                },
                placeholder = if (asking) "Or ask anything about it" else "Message",
                generating = generating,
                focus = focus,
                onSend = {
                    when {
                        editing -> vm.edit(draft.text)
                        asking -> vm.sendShared(draft.text)
                        else -> vm.send(draft.text)
                    }
                    editedChat = null
                    draft = TextFieldValue()
                },
                onStop = vm::stop,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
}

@Composable
private fun TopBar(engine: LlamaEngine.State, canStartNew: Boolean, onOpenDrawer: () -> Unit, onNewChat: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        NavText("Chats", onClick = onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Center)) {
            Wordmark()
            // The one accent: a dot while the model is waking up or has failed.
            when (engine) {
                is LlamaEngine.State.Loading, LlamaEngine.State.Empty -> StatusDot(p.grey)
                is LlamaEngine.State.Failed -> StatusDot(p.accent)
                is LlamaEngine.State.Ready -> Unit
            }
        }
        NavText("New", onClick = onNewChat, enabled = canStartNew, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun StatusDot(color: Color) {
    Spacer(Modifier.width(6.dp))
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Short pill labels; tapping sends the full prompt. Prompts a 2B model answers well: on the
 * emulator (23 Oct) it got the facts of UPI wrong, muddled a workout and wrote a Hindi story that
 * didn't make sense, while these came out well.
 */
private val suggestions = listOf(
    "Bakery names" to "Give me 5 name ideas for a home bakery.",
    "Explain simply" to "Explain photosynthesis to a 10-year-old.",
    "Birthday wish" to "Write a warm birthday message for my mom.",
    "Leave request" to "Write a short, polite leave request to my manager for Friday.",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Headline("Ask anything.", "It stays on this phone.", Modifier.widthIn(max = 360.dp))
        Spacer(Modifier.height(16.dp))
        Fine("Works offline. Nothing you type is sent anywhere.", align = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { (label, prompt) -> Pill(label, onClick = { onPick(prompt) }) }
        }
    }
}

/** What to do with text shared from another app; each prompt goes before the text. */
private val sharedActions = listOf(
    "Summarize" to "Summarize this in a few short points:",
    "Explain" to "Explain this simply:",
    "Reply" to "Write a short, friendly reply to this message:",
    "Fix grammar" to "Fix the grammar and spelling of this text. Keep its meaning and tone, and reply with just the corrected text:",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedText(shared: AppViewModel.Shared, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Headline("Ask about it.", "It stays on this phone.", Modifier.widthIn(max = 360.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            shared.text,
            style = Type.body,
            color = p.ink,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(p.paper2)
                .padding(16.dp),
        )
        if (shared.shortened) {
            Spacer(Modifier.height(8.dp))
            Fine("It's long, so only the first part is used.", align = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sharedActions.forEach { (label, instruction) -> Pill(label, onClick = { onPick(instruction) }) }
        }
        Spacer(Modifier.height(8.dp))
        NavText("Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun Messages(
    messages: List<Message>,
    generating: Boolean,
    reading: Boolean,
    engine: LlamaEngine.State,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var reporting by remember { mutableStateOf<Message?>(null) }
    val lastUser = messages.indexOfLast { it.isUser }

    // Newest at the bottom; reverseLayout keeps a streaming reply pinned above the composer.
    val reversed = messages.asReversed()
    LaunchedEffect(messages.size) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(reversed, key = { index, _ -> messages.size - index }) { index, message ->
            if (message.isUser) {
                val position = messages.size - 1 - index
                UserMessage(
                    text = message.text,
                    // Only the last question can be edited, and asked again if it never got an answer.
                    onEdit = if (position == lastUser && !generating) ({ onEdit(message.text) }) else null,
                    onRetry = if (index == 0 && !generating) onRegenerate else null,
                )
            } else {
                AssistantMessage(
                    message = message,
                    streaming = generating && index == 0,
                    waiting = if (generating && index == 0) waitingLabel(engine, reading) else null,
                    isLast = index == 0,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Autark", Thinking.split(message.text).answer))
                    },
                    onRegenerate = onRegenerate,
                    onContinue = onContinue,
                    onReport = { reporting = message },
                )
            }
        }
    }

    reporting?.let { message ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Report this response?") },
            text = {
                Text(
                    "This opens your email app with the response filled in, so you can tell the developer " +
                        "what was wrong. Nothing is sent unless you send the email.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    reportResponse(context, message.model, message.text)
                    reporting = null
                }) { Text("OPEN EMAIL") }
            },
            dismissButton = { TextButton(onClick = { reporting = null }) { Text("CANCEL") } },
        )
    }
}

/** What the reply is waiting on before its first word, once that takes long enough to notice. */
@Composable
private fun waitingLabel(engine: LlamaEngine.State, reading: Boolean): String? {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(reading) {
        slow = false
        if (reading) {
            delay(1200)
            slow = true
        }
    }
    return when {
        engine is LlamaEngine.State.Loading || engine == LlamaEngine.State.Empty -> "Waking up"
        reading && slow -> "Reading"
        else -> ""
    }
}

@Composable
private fun UserMessage(text: String, onEdit: (() -> Unit)?, onRetry: (() -> Unit)?) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            text,
            style = Type.body,
            color = p.ink,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(p.pill)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (onEdit != null || onRetry != null) {
            Row(modifier = Modifier.padding(top = 2.dp)) {
                onEdit?.let { NavText("Edit", onClick = it) }
                onRetry?.let { NavText("Retry", onClick = it) }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: Message,
    streaming: Boolean,
    waiting: String?,
    isLast: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onReport: () -> Unit,
) {
    val parts = remember(message.text) { Thinking.split(message.text) }
    Column(Modifier.fillMaxWidth()) {
        parts.thoughts?.let { Thoughts(it, thinking = streaming && parts.stillThinking) }
        when {
            parts.answer.isNotEmpty() -> MarkdownText(parts.answer)
            streaming && !parts.stillThinking -> Waiting(waiting)
            !streaming && parts.stillThinking -> Fine("Stopped before answering.")
        }
        if (!streaming) message.problem?.let { Fine(it.message) }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (message.tokens > 0 && message.seconds > 0) {
                    Fine(String.format(Locale.US, "%.0f tok/s", message.tokens / message.seconds), upper = true)
                }
                Spacer(Modifier.weight(1f))
                if (parts.answer.isNotEmpty()) NavText("Copy", onClick = onCopy)
                // Continue picks up the answer; one cut off while thinking starts again instead.
                if (isLast && message.cut && parts.answer.isNotEmpty()) NavText("Continue", onClick = onContinue)
                if (isLast) NavText("Retry", onClick = onRegenerate)
                NavText("Report", onClick = onReport)
            }
        }
    }
}

@Composable
private fun Thoughts(text: String, thinking: Boolean) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { open = !open }
            .padding(vertical = 4.dp),
    ) {
        Fine(
            when {
                thinking -> "Thinking…"
                open -> "Hide reasoning"
                else -> "Show reasoning"
            },
            upper = true,
        )
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = Type.fine, color = p.muted)
        }
    }
}

/** Three dots, and what the reply is waiting on once that takes a moment. */
@Composable
private fun Waiting(label: String?) {
    val p = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 10.dp),
    ) {
        repeat(3) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(p.grey),
            )
        }
        if (!label.isNullOrEmpty()) {
            Spacer(Modifier.width(4.dp))
            Fine(label, upper = true)
        }
    }
}

/** A pill-shaped field with a solid round button, like the dock on piyushbodaa.com. */
@Composable
private fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    generating: Boolean,
    focus: FocusRequester,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val p = LocalPalette.current
    val canSend = value.text.isNotBlank() && !generating
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(p.paper2)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (value.text.isEmpty()) Text(placeholder, style = Type.body, color = p.grey)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = Type.body.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (generating || canSend) p.solid else p.pill)
                .clickable(enabled = generating || canSend, role = Role.Button) {
                    if (generating) onStop() else onSend()
                }
                .semantics { contentDescription = if (generating) "Stop" else "Send" },
        ) {
            if (generating) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(p.onSolid),
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (canSend) p.onSolid else p.grey,
                )
            }
        }
    }
}
