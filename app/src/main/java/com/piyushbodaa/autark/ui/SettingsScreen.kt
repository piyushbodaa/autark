package com.piyushbodaa.autark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piyushbodaa.autark.BuildConfig
import com.piyushbodaa.autark.data.Settings
import com.piyushbodaa.autark.engine.LlamaEngine
import com.piyushbodaa.autark.ui.theme.LocalPalette
import com.piyushbodaa.autark.ui.theme.Type
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmWipe by remember { mutableStateOf(false) }
    val canThink = (engine as? LlamaEngine.State.Ready)?.canThink == true

    Column(
        Modifier
            .fillMaxSize()
            .background(p.paper)
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            NavText("Back", onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
            Wordmark("Settings", Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Section("Instructions") {
                Fine("Autark follows these in every chat.")
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = settings.systemPrompt,
                    onValueChange = { text -> vm.updateSettings { it.copy(systemPrompt = text) } },
                    textStyle = Type.body.copy(color = p.ink),
                    cursorBrush = SolidColor(p.ink),
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.paper2)
                        .padding(16.dp),
                )
                NavText("Reset", onClick = { vm.updateSettings { it.copy(systemPrompt = Settings.DEFAULT_SYSTEM_PROMPT) } })
            }

            Section("Replies") {
                LabeledSlider(
                    label = "Creativity",
                    value = settings.temperature,
                    display = String.format(Locale.US, "%.1f", settings.temperature),
                    hint = "Lower is focused and predictable. Higher is more varied.",
                    range = 0f..1.5f,
                    steps = 14,
                    onChange = { v -> vm.updateSettings { it.copy(temperature = (v * 10).roundToInt() / 10f) } },
                )
                LabeledSlider(
                    label = "Longest reply",
                    value = settings.maxTokens.toFloat(),
                    display = "${settings.maxTokens} tokens",
                    hint = "A token is about three quarters of a word.",
                    range = 256f..4096f,
                    steps = 14,
                    onChange = { v -> vm.updateSettings { it.copy(maxTokens = ((v / 256).roundToInt() * 256).coerceIn(256, 4096)) } },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Think before answering", style = Type.body, color = p.ink)
                        Fine(if (canThink) "Slower, but better at maths and tricky questions." else "Not available yet.")
                    }
                    Switch(
                        checked = settings.thinking && canThink,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(thinking = on) } },
                        enabled = canThink,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = p.solid,
                            checkedThumbColor = p.onSolid,
                            uncheckedTrackColor = p.pill,
                            uncheckedThumbColor = p.grey,
                            uncheckedBorderColor = p.pill,
                        ),
                    )
                }
            }

            Section("Memory") {
                Fine("How much of the conversation Autark keeps in mind. Longer uses more of the phone's memory.")
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048 to "Short", 4096 to "Medium", 8192 to "Long").forEach { (size, label) ->
                        Pill(label, solid = settings.contextSize == size, onClick = { vm.updateSettings { it.copy(contextSize = size) } })
                    }
                }
            }

            Section("Privacy") {
                Text(
                    "Autark has no account and no servers. Chats are stored only on this phone and are never " +
                        "backed up to the cloud. The internet is used once, to download the model.",
                    style = Type.body,
                    color = p.ink,
                )
                Spacer(Modifier.height(12.dp))
                Pill("Delete all chats", onClick = { confirmWipe = true })
            }

            Section("About") {
                Text("Autark ${BuildConfig.VERSION_NAME}", style = Type.body, color = p.ink)
                Fine("Runs ${vm.model.name} (${vm.model.license}) with llama.cpp (MIT).")
                (engine as? LlamaEngine.State.Ready)?.let { Fine("${it.description} · ${it.contextSize} tokens of memory") }
                Row {
                    NavText("Model license", onClick = { openUrl(context, vm.model.licenseUrl) })
                    NavText("Contact", onClick = { openUrl(context, "mailto:$DEVELOPER_EMAIL") })
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all chats?") },
            text = { Text("Every conversation will be removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllChats()
                    confirmWipe = false
                }) { Text("DELETE ALL") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        HorizontalDivider(color = LocalPalette.current.line)
        Spacer(Modifier.height(14.dp))
        Fine(title, upper = true)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    display: String,
    hint: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
            Fine(display, upper = true)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = p.solid,
                activeTrackColor = p.solid,
                inactiveTrackColor = p.pill,
                activeTickColor = p.solid,
                inactiveTickColor = p.line,
            ),
        )
        Fine(hint)
    }
}
