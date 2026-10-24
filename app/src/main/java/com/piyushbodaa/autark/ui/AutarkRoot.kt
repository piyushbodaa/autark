package com.piyushbodaa.autark.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piyushbodaa.autark.data.Conversation
import com.piyushbodaa.autark.models.ModelManager
import com.piyushbodaa.autark.ui.theme.AutarkTheme
import com.piyushbodaa.autark.ui.theme.LocalPalette
import com.piyushbodaa.autark.ui.theme.Night
import com.piyushbodaa.autark.ui.theme.Type
import kotlinx.coroutines.launch

enum class Screen { Chat, Settings }

@Composable
fun AutarkRoot(vm: AppViewModel = viewModel()) {
    val status by vm.modelStatus.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.onAppVisible()
        onStopOrDispose { vm.onAppHidden() }
    }
    if (status != ModelManager.Status.Ready) {
        SetupScreen(vm.model, status, onDownload = vm::startDownload, onCancel = vm::cancelDownload)
        return
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    BackHandler(enabled = screen != Screen.Chat) { screen = Screen.Chat }
    when (screen) {
        Screen.Chat -> ChatWithDrawer(vm, onOpenSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Chat })
    }
}

@Composable
private fun ChatWithDrawer(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    if (drawer.targetValue == DrawerValue.Open) LightStatusBarIcons()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AutarkTheme(palette = Night) {
                val p = LocalPalette.current
                ModalDrawerSheet(drawerContainerColor = p.paper, drawerShape = RectangleShape) {
                    Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)) {
                        Wordmark()
                        Spacer(Modifier.height(28.dp))
                        Fine("Chats", upper = true)
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        if (conversations.isEmpty()) {
                            item {
                                Fine(
                                    "Your chats will appear here. They're kept only on this phone.",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(conversations, key = { it.id }) { conversation ->
                            ChatRow(
                                conversation = conversation,
                                selected = conversation.id == current.id,
                                onOpen = {
                                    vm.openChat(conversation.id)
                                    scope.launch { drawer.close() }
                                },
                                onLongPress = { confirmDelete = conversation },
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    ) {
                        Pill("New chat", solid = true, onClick = {
                            vm.newChat()
                            scope.launch { drawer.close() }
                        })
                        Pill("Settings", onClick = {
                            scope.launch { drawer.close() }
                            onOpenSettings()
                        })
                    }
                }
            }
        },
    ) {
        ChatScreen(vm = vm, onOpenDrawer = { scope.launch { drawer.open() } })
    }

    confirmDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${conversation.title.ifEmpty { "Untitled" }}” will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(conversation.id)
                    confirmDelete = null
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(conversation: Conversation, selected: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress, onLongClickLabel = "Delete chat")
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(
            conversation.title.ifEmpty { "Untitled" }.uppercase(),
            style = Type.title,
            color = if (selected) p.grey else p.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Fine(DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString())
    }
}
