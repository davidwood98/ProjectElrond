package ai.elrond.ui

import ai.elrond.ai.TriggerMode
import ai.elrond.settings.SettingsRepository
import ai.elrond.settings.SettingsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** App settings: AI activation (command vs gesture), canvas input, AI responses, auto-extraction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val trigger by viewModel.triggerCommand.collectAsStateWithLifecycle()
    val triggerMode by viewModel.triggerMode.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val aiSelectedOnCreate by viewModel.aiNoteSelectedOnCreate.collectAsStateWithLifecycle()
    val autoExtraction by viewModel.autoExtractionEnabled.collectAsStateWithLifecycle()
    val confirmEnabled by viewModel.extractionConfirmationEnabled.collectAsStateWithLifecycle()
    val confirmTodo by viewModel.confirmTodoExtraction.collectAsStateWithLifecycle()
    val confirmCalendar by viewModel.confirmCalendarExtraction.collectAsStateWithLifecycle()

    var draft by remember(trigger) { mutableStateOf(trigger) }
    val tooLong = draft.trim().length > SettingsRepository.MAX_TRIGGER_LENGTH
    val empty = draft.isBlank()
    val effectiveTrigger = if (!empty && !tooLong) draft.trim() else trigger

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI activation", style = MaterialTheme.typography.titleMedium)
            Text(
                "How you ask the AI a question on a note page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = triggerMode == TriggerMode.COMMAND,
                    onClick = { viewModel.setTriggerMode(TriggerMode.COMMAND) },
                    label = { Text("Written command") },
                )
                FilterChip(
                    selected = triggerMode == TriggerMode.GESTURE,
                    onClick = { viewModel.setTriggerMode(TriggerMode.GESTURE) },
                    label = { Text("Circle gesture") },
                )
            }

            when (triggerMode) {
                TriggerMode.COMMAND -> {
                    Text(
                        "Write the command at the end of a line to ask about that line (or the " +
                            "lines above it). Max ${SettingsRepository.MAX_TRIGGER_LENGTH} characters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { new ->
                            draft = new
                            // Save immediately when valid so it takes effect on the next note opened.
                            if (new.isNotBlank() &&
                                new.trim().length <= SettingsRepository.MAX_TRIGGER_LENGTH
                            ) {
                                viewModel.setTriggerCommand(new)
                            }
                        },
                        singleLine = true,
                        isError = tooLong || empty,
                        label = { Text("Trigger command") },
                        supportingText = {
                            when {
                                empty -> Text("Cannot be empty")
                                tooLong -> Text(
                                    "Too long — max ${SettingsRepository.MAX_TRIGGER_LENGTH} characters",
                                )
                                else -> Text("Saved")
                            }
                        },
                    )
                    TriggerPreview("What's the capital of France?  $effectiveTrigger")
                }

                TriggerMode.GESTURE -> {
                    Text(
                        "Draw a circle (lasso) around the handwriting you want to ask about, then " +
                            "lift your pen. The AI reads whatever is inside the loop.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TriggerPreview("◯  draw a loop around your question")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Canvas input", style = MaterialTheme.typography.titleMedium)
            SettingRow(
                title = "Palm rejection (stylus only)",
                subtitle = "Ignore finger touches so a hand resting on the screen doesn't draw. " +
                    "Turn off to draw with a finger.",
                checked = stylusOnly,
                onCheckedChange = viewModel::setStylusOnly,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("AI responses", style = MaterialTheme.typography.titleMedium)
            SettingRow(
                title = "Edit mode on creation",
                subtitle = "New AI answers start selected so you can move, resize or delete them — " +
                    "tap anywhere off the box to place it. When off, answers land in the note flow " +
                    "and a long-press selects them.",
                checked = aiSelectedOnCreate,
                onCheckedChange = viewModel::setAiNoteSelectedOnCreate,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Auto-extraction", style = MaterialTheme.typography.titleMedium)
            Text(
                "Detect to-do items and calendar events in the background after you write — " +
                    "no /Q needed. /Q still works for instant questions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingRow(
                title = "Detect tasks & events automatically",
                checked = autoExtraction,
                onCheckedChange = viewModel::setAutoExtractionEnabled,
            )
            SettingRow(
                title = "Ask before adding",
                subtitle = "Show a Yes/No popup next to the detected text. When off, items are " +
                    "added quietly and the to-do tab flags new ones.",
                checked = confirmEnabled,
                enabled = autoExtraction,
                onCheckedChange = viewModel::setExtractionConfirmationEnabled,
                indent = 16.dp,
            )
            SettingRow(
                title = "Confirm to-do items",
                checked = confirmTodo,
                enabled = autoExtraction && confirmEnabled,
                onCheckedChange = viewModel::setConfirmTodoExtraction,
                indent = 32.dp,
            )
            SettingRow(
                title = "Confirm calendar events",
                checked = confirmCalendar,
                enabled = autoExtraction && confirmEnabled,
                onCheckedChange = viewModel::setConfirmCalendarExtraction,
                indent = 32.dp,
            )
        }
    }
}

/** A small "this is what you write/draw" example box for the activation section. */
@Composable
private fun TriggerPreview(example: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(example, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    indent: Dp = 0.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = indent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
