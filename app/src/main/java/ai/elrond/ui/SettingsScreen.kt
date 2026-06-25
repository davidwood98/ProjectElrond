package ai.elrond.ui

import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import ai.elrond.domain.AppAccent
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.PenIconStyle
import ai.elrond.domain.TriggerMode
import ai.elrond.data.CalendarProviderType
import ai.elrond.data.SettingsRepository
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.domain.ToolSelectedTreatment
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.loaders.OrganicLoader
import ai.elrond.ui.theme.color
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.hypot

/** App settings: AI activation (command vs gesture), canvas input, AI responses, auto-extraction. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val trigger by viewModel.triggerCommand.collectAsStateWithLifecycle()
    val triggerMode by viewModel.triggerMode.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val toolTreatment by viewModel.toolSelectedTreatment.collectAsStateWithLifecycle()
    val aiSelectedOnCreate by viewModel.aiNoteSelectedOnCreate.collectAsStateWithLifecycle()
    val autoExtraction by viewModel.autoExtractionEnabled.collectAsStateWithLifecycle()
    val confirmEnabled by viewModel.extractionConfirmationEnabled.collectAsStateWithLifecycle()
    val confirmTodo by viewModel.confirmTodoExtraction.collectAsStateWithLifecycle()
    val confirmCalendar by viewModel.confirmCalendarExtraction.collectAsStateWithLifecycle()
    val snapBackEnabled by viewModel.lassoSnapBackEnabled.collectAsStateWithLifecycle()
    val snapBackThreshold by viewModel.lassoSnapBackThreshold.collectAsStateWithLifecycle()
    val calendarProvider by viewModel.calendarProvider.collectAsStateWithLifecycle()
    val penIconStyle by viewModel.penIconStyle.collectAsStateWithLifecycle()
    val appAccent by viewModel.appAccent.collectAsStateWithLifecycle()
    val paperStyle by viewModel.paperStyle.collectAsStateWithLifecycle()
    val aiLoaderStyle by viewModel.aiLoaderStyle.collectAsStateWithLifecycle()
    val aiColorMode by viewModel.aiColorMode.collectAsStateWithLifecycle()

    var draft by remember(trigger) { mutableStateOf(trigger) }
    // Local slider position, re-seeded whenever the persisted threshold changes (e.g. when toggling
    // snap-back on restores the default); persisted on release via onValueChangeFinished.
    var snapBackPos by remember(snapBackThreshold) { mutableStateOf(snapBackThreshold) }
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

            Text("Selected tool style", style = MaterialTheme.typography.titleMedium)
            Text(
                "How the active tool is highlighted in the note toolbar. Tap a style to use it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TreatmentOption(
                    label = "Soft tile",
                    treatment = ToolSelectedTreatment.SOFT_TILE,
                    current = toolTreatment,
                    onSelect = { viewModel.setToolSelectedTreatment(ToolSelectedTreatment.SOFT_TILE) },
                )
                TreatmentOption(
                    label = "Filled",
                    treatment = ToolSelectedTreatment.FILLED,
                    current = toolTreatment,
                    onSelect = { viewModel.setToolSelectedTreatment(ToolSelectedTreatment.FILLED) },
                )
                TreatmentOption(
                    label = "Underline",
                    treatment = ToolSelectedTreatment.UNDERLINE,
                    current = toolTreatment,
                    onSelect = { viewModel.setToolSelectedTreatment(ToolSelectedTreatment.UNDERLINE) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text(
                "Personalise how the app and your note pages look (from the Leap design tweaks).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Accent colour",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppAccent.entries.forEach { accent ->
                    AccentSwatch(
                        accent = accent,
                        selected = appAccent == accent,
                        onSelect = { viewModel.setAppAccent(accent) },
                    )
                }
            }

            Text(
                "Pen icon style",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Show pen, highlighter and pencil as the whole tool, or just its writing tip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PenStyleOption(
                    label = "Body",
                    style = PenIconStyle.BODY,
                    current = penIconStyle,
                    treatment = toolTreatment,
                    onSelect = { viewModel.setPenIconStyle(PenIconStyle.BODY) },
                )
                PenStyleOption(
                    label = "Tip",
                    style = PenIconStyle.TIP,
                    current = penIconStyle,
                    treatment = toolTreatment,
                    onSelect = { viewModel.setPenIconStyle(PenIconStyle.TIP) },
                )
            }

            Text(
                "Paper style",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperStyle.entries.forEach { style ->
                    FilterChip(
                        selected = paperStyle == style,
                        onClick = { viewModel.setPaperStyle(style) },
                        label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            // Note-tab mode (Attached/Separate) setting removed pending a redesign — the editor
            // currently always shows tabs in the grey header band above the title.

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- AI assistant mark (FA-17, organic-loaders handoff) ---
            Text("AI assistant", style = MaterialTheme.typography.titleMedium)
            Text(
                "The logo that marks AI items and the loader shown while the assistant is thinking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Colour",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AiColorModeOption(
                    label = "Colour",
                    mode = AiColorMode.COLOR,
                    current = aiColorMode,
                    onSelect = { viewModel.setAiColorMode(AiColorMode.COLOR) },
                )
                AiColorModeOption(
                    label = "Black",
                    mode = AiColorMode.BLACK,
                    current = aiColorMode,
                    onSelect = { viewModel.setAiColorMode(AiColorMode.BLACK) },
                )
            }

            Text(
                "Loader style",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "The animation shown while the AI is thinking. Tap one to use it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AiLoaderStyle.entries.forEach { style ->
                    AiLoaderOption(
                        style = style,
                        current = aiLoaderStyle,
                        colorMode = aiColorMode,
                        onSelect = { viewModel.setAiLoaderStyle(style) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Interaction", style = MaterialTheme.typography.titleMedium)
            SettingRow(
                title = "Snap selection back to origin",
                subtitle = "When you nudge a lasso selection only a little and let go, it returns to " +
                    "where it started — so a small accidental move doesn't shift your ink.",
                checked = snapBackEnabled,
                onCheckedChange = viewModel::setLassoSnapBackEnabled,
            )
            Text(
                "Snap-back threshold: ${formatPercent(snapBackPos)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (snapBackEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Slider(
                value = snapBackPos,
                onValueChange = { snapBackPos = it },
                onValueChangeFinished = { viewModel.setLassoSnapBackThreshold(snapBackPos) },
                valueRange = 0f..SettingsRepository.MAX_LASSO_SNAP_BACK_THRESHOLD,
                steps = SNAP_BACK_SLIDER_STEPS,
                enabled = snapBackEnabled,
            )
            SnapBackPreview(threshold = snapBackPos, enabled = snapBackEnabled)

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

            Text("Calendar", style = MaterialTheme.typography.titleMedium)
            Text(
                "Which calendar the Events tab reads. Device uses your phone's calendar; Outlook " +
                    "connects your Microsoft account — sign in from Calendar → Events.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarProviderType.entries.forEach { type ->
                    FilterChip(
                        selected = calendarProvider == type,
                        onClick = { viewModel.setCalendarProvider(type) },
                        label = {
                            Text(type.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }

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

/**
 * A tappable preview of one selected-tool style (the handoff's A/B/C). Renders the real
 * [ToolbarButton] in that treatment so the user sees exactly what they'll get; the chosen one's
 * label is shown in the accent colour.
 */
@Composable
private fun TreatmentOption(
    label: String,
    treatment: ToolSelectedTreatment,
    current: ToolSelectedTreatment,
    onSelect: () -> Unit,
) {
    val chosen = treatment == current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolbarButton(
            painter = painterResource(ElrondIcons.Pen),
            contentDescription = "$label preview",
            onClick = onSelect,
            selected = true,
            treatment = treatment,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            color = if (chosen) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** A tappable preview of the AI colour mode (FA-17): the real [AiLogo] drawn in that mode. */
@Composable
private fun AiColorModeOption(
    label: String,
    mode: AiColorMode,
    current: AiColorMode,
    onSelect: () -> Unit,
) {
    val selected = mode == current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AiLogo(modifier = Modifier.size(40.dp), colorMode = mode, contentDescription = null)
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** A tappable, live preview tile of one organic loader (FA-17), in the user's current colour mode. */
@Composable
private fun AiLoaderOption(
    style: AiLoaderStyle,
    current: AiLoaderStyle,
    colorMode: AiColorMode,
    onSelect: () -> Unit,
) {
    val selected = style == current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            OrganicLoader(style = style, colorMode = colorMode, size = 46.dp)
        }
        Text(
            style.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** A tappable accent-colour swatch (FA-14); a ring + bold label marks the chosen one. */
@Composable
private fun AccentSwatch(accent: AppAccent, selected: Boolean, onSelect: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.color)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
        Text(
            accent.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * A tappable preview of one pen-icon style (FA-14 Body/Tip). Renders the real [ToolbarButton] with
 * the pen drawn that way (in the user's current selected-tool treatment) so the choice is literal.
 */
@Composable
private fun PenStyleOption(
    label: String,
    style: PenIconStyle,
    current: PenIconStyle,
    treatment: ToolSelectedTreatment,
    onSelect: () -> Unit,
) {
    val chosen = style == current
    @DrawableRes val icon = if (style == PenIconStyle.TIP) ElrondIcons.PenTip else ElrondIcons.Pen
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolbarButton(
            painter = painterResource(icon),
            contentDescription = "$label pen-icon preview",
            onClick = onSelect,
            selected = true,
            treatment = treatment,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            color = if (chosen) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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

/** "2.5%" for a 0–1 fraction (one decimal). */
private fun formatPercent(fraction: Float): String = "${"%.1f".format(fraction * 100f)}%"

/**
 * A feel-test for the snap-back threshold: an origin dot at the centre, a dashed ring at the snap
 * radius ([threshold] × the box width), and a draggable dot that snaps back to the centre when
 * released inside the ring — mirroring the canvas behaviour. Inert when snap-back is off.
 */
@Composable
private fun SnapBackPreview(threshold: Float, enabled: Boolean) {
    var dot by remember { mutableStateOf(Offset.Zero) } // offset from centre, px
    val accent = MaterialTheme.colorScheme.primary
    val originColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Preview — drag the dot, release near the centre to snap back",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(SNAP_BACK_PREVIEW_DP.dp)) {
                Box(
                    modifier = Modifier
                        .size(SNAP_BACK_PREVIEW_DP.dp)
                        .align(Alignment.Center)
                        .pointerInput(threshold, enabled) {
                            detectDragGestures(
                                onDragEnd = {
                                    val s = size.width.toFloat()
                                    if (enabled && s > 0f && hypot(dot.x / s, dot.y / s) < threshold) {
                                        dot = Offset.Zero
                                    }
                                },
                            ) { change, drag ->
                                change.consume()
                                val half = size.width / 2f
                                dot = Offset(
                                    (dot.x + drag.x).coerceIn(-half, half),
                                    (dot.y + drag.y).coerceIn(-half, half),
                                )
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        if (enabled && threshold > 0f) {
                            drawCircle(
                                color = accent.copy(alpha = 0.4f),
                                radius = threshold * size.width,
                                center = c,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                                ),
                            )
                        }
                        drawCircle(color = originColor, radius = 4.dp.toPx(), center = c)
                        drawCircle(color = accent, radius = 7.dp.toPx(), center = c + dot)
                    }
                }
            }
        }
    }
}

/** Discrete slider stops for 0–10% in 0.5% steps (21 values → 19 between the endpoints). */
private const val SNAP_BACK_SLIDER_STEPS = 19
private const val SNAP_BACK_PREVIEW_DP = 160
