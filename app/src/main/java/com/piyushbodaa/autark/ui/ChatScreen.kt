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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piyushbodaa.autark.data.Message
import com.piyushbodaa.autark.engine.LlamaEngine
import com.piyushbodaa.autark.ui.theme.LocalPalette
import com.piyushbodaa.autark.ui.theme.Type
import java.util.Locale

@Composable
fun ChatScreen(vm: AppViewModel, onOpenDrawer: () -> Unit) {
    val p = LocalPalette.current
    val conversation by vm.current.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.dismissNotice()
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
                canStartNew = conversation.messages.isNotEmpty(),
                onOpenDrawer = onOpenDrawer,
                onNewChat = vm::newChat,
            )
            Box(Modifier.weight(1f)) {
                if (conversation.messages.isEmpty()) {
                    EmptyChat(onPick = vm::send)
                } else {
                    Messages(messages = conversation.messages, generating = generating, onRegenerate = vm::regenerate)
                }
            }
            Composer(generating = generating, onSend = vm::send, onStop = vm::stop)
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

/** Short pill labels; tapping sends the full prompt. */
private val suggestions = listOf(
    "Explain UPI" to "Explain how UPI works, simply.",
    "Leave request" to "Write a short, polite leave request to my manager for Friday.",
    "Home workout" to "Give me a 20-minute home workout with no equipment.",
    "हिंदी में कहानी" to "मुझे हिंदी में एक छोटी कहानी सुनाओ।",
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

@Composable
private fun Messages(messages: List<Message>, generating: Boolean, onRegenerate: () -> Unit) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var reporting by remember { mutableStateOf<Message?>(null) }

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
                UserBubble(message.text)
            } else {
                AssistantMessage(
                    message = message,
                    streaming = generating && index == 0,
                    isLast = index == 0,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Autark", Thinking.split(message.text).answer))
                    },
                    onRegenerate = onRegenerate,
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

@Composable
private fun UserBubble(text: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
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
    }
}

@Composable
private fun AssistantMessage(
    message: Message,
    streaming: Boolean,
    isLast: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onReport: () -> Unit,
) {
    val parts = remember(message.text) { Thinking.split(message.text) }
    Column(Modifier.fillMaxWidth()) {
        parts.thoughts?.let { Thoughts(it, parts.stillThinking) }
        when {
            parts.answer.isNotEmpty() -> MarkdownText(parts.answer)
            streaming && !parts.stillThinking -> TypingIndicator()
        }
        if (!streaming && parts.answer.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (message.tokens > 0 && message.seconds > 0) {
                    Fine(String.format(Locale.US, "%.0f tok/s", message.tokens / message.seconds), upper = true)
                }
                Spacer(Modifier.weight(1f))
                NavText("Copy", onClick = onCopy)
                if (isLast) NavText("Retry", onClick = onRegenerate)
                NavText("Report", onClick = onReport)
            }
        }
    }
}

@Composable
private fun Thoughts(text: String, stillThinking: Boolean) {
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
                stillThinking -> "Thinking…"
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

@Composable
private fun TypingIndicator() {
    val p = LocalPalette.current
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(p.grey),
            )
        }
    }
}

/** A pill-shaped field with a solid round button, like the dock on piyushbodaa.com. */
@Composable
private fun Composer(generating: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && !generating
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
            if (text.isEmpty()) Text("Message", style = Type.body, color = p.grey)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Type.body.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
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
                    if (generating) {
                        onStop()
                    } else {
                        onSend(text)
                        text = ""
                    }
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
