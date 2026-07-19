package ai.elrond.ui

import ai.elrond.domain.SuggestedTag
import ai.elrond.domain.SuggestionOrigin
import ai.elrond.domain.Tag
import ai.elrond.ui.theme.LeapBlue
import ai.elrond.ui.theme.LeapGreen
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapNavy
import ai.elrond.ui.theme.LeapPink
import ai.elrond.ui.theme.Neutral100
import ai.elrond.ui.theme.Neutral200
import ai.elrond.ui.theme.Neutral500
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

// ── Tag pill metrics (FA-24). The row fills the fixed leftover space between the capped title
// and the date: it never shifts the header chrome regardless of tag count or title length —
// overflow scrolls internally.
private val TAG_PILL_MAX_WIDTH = 96.dp
private val TAG_ROW_FADE_WIDTH = 18.dp
private const val TAG_COLLAPSE_ANIM_MS = 250

/**
 * The shared tag-assignment picker (FA-24) — opened from BOTH the Library card ⋮ menu and the
 * editor header's `+`, so the two surfaces can't drift. Rows TOGGLE membership: tapping an
 * unassigned tag assigns it, tapping an assigned one removes it immediately (a modal checkbox
 * list is an explicit action — the 2s undo window belongs to the always-visible header row).
 * The field below creates-and-assigns a new tag (get-or-create by unique name).
 *
 * FA-24d: an optional "Suggested" section (visually separated by a label + divider) sits above the
 * existing/manual tags — tapping a suggested pill commits it via [onAcceptSuggestion].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TagPickerDialog(
    allTags: List<Tag>,
    assignedTagIds: Set<String>,
    onToggle: (Tag) -> Unit,
    onCreateAndAssign: (String) -> Unit,
    onDismiss: () -> Unit,
    suggestions: List<SuggestedTag> = emptyList(),
    onAcceptSuggestion: (SuggestedTag) -> Unit = {},
) {
    var newTagName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Tags",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LeapGrey,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        "Suggested",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Neutral500,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        suggestions.forEach { suggestion ->
                            SuggestionPill(
                                suggestion = suggestion,
                                onTap = { onAcceptSuggestion(suggestion) },
                            )
                        }
                    }
                    HorizontalDivider(
                        color = Neutral200,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (allTags.isEmpty()) {
                    Text(
                        "No tags yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Neutral500,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    allTags.forEach { tag ->
                        val assigned = tag.id in assignedTagIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(tag) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(Color(tag.colorArgb)),
                            )
                            Spacer(Modifier.width(11.dp))
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = LeapGrey,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (assigned) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Assigned",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        label = { Text("New tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (newTagName.isNotBlank()) {
                                onCreateAndAssign(newTagName)
                                newTagName = ""
                            }
                        },
                        enabled = newTagName.isNotBlank(),
                    ) { Text("Add") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

/**
 * The editor header's tag row (FA-24) — fills ALL the space between the title (which keeps its
 * own max-width cap) and the created date, so the region is fixed for a given title/date and
 * can never encroach on either (device feedback: the old fixed 220dp strip wasted the run-up
 * to the title). Pills are anchored to the RIGHT edge and build out leftward — the first tag
 * sits next to the `+`, each new one pushes the rest left. Overflow scrolls horizontally
 * (initially pinned to the right end) with a fade-out gradient on each edge that hides content
 * (a silently-clipped scrollable row would hide tags with no affordance). Each pill truncates
 * independently ([TAG_PILL_MAX_WIDTH]); the trailing `+` opens the shared picker.
 *
 * New tags GENERATE at the left end — existing pills keep their (right-anchored) positions and
 * the newcomer appears alone on the left (device feedback: adding must not shuffle the rest).
 *
 * Pill gesture (device feedback: the old double-tap felt clunky): a SINGLE tap deselects the
 * tag — the pill greys out in place for the ViewModel's 2s correction window, and tapping the
 * greyed pill again cancels the removal (no DB write happened). While greyed it also shows its
 * full untruncated name, so the user sees exactly what's being removed. When the window
 * elapses the pill collapses its width away (never a jump-cut) via a ghost entry that outlives
 * the tag by the exit animation.
 */
@Composable
fun TagRow(
    tags: List<Tag>,
    pendingRemovalTagIds: Set<String>,
    onBeginUntag: (Tag) -> Unit,
    onCancelUntag: (Tag) -> Unit,
    onAddTag: () -> Unit,
    modifier: Modifier = Modifier,
    fadeColor: Color = Neutral100,
    suggestions: List<SuggestedTag> = emptyList(),
    onAcceptSuggestion: (SuggestedTag) -> Unit = {},
) {
    // Ghost bookkeeping so a removed tag collapses instead of jump-cutting: keep every seen tag
    // (and a stable order); prune ghosts after the exit animation has played. Unseen ids are
    // inserted at the FRONT (left end): [tags] arrives newest-first, so walking it reversed
    // (oldest first) both preserves that order on first composition and makes any later new tag
    // land at the left without moving the right-anchored older pills.
    val known = remember { mutableStateMapOf<String, Tag>() }
    val order = remember { mutableStateListOf<String>() }
    val liveIds = tags.map { it.id }.toSet()
    tags.forEach { tag -> known[tag.id] = tag }
    tags.asReversed().forEach { tag ->
        if (tag.id !in order) order.add(0, tag.id)
    }
    LaunchedEffect(liveIds) {
        if (order.any { it !in liveIds }) {
            delay(TAG_COLLAPSE_ANIM_MS + 50L)
            order.retainAll(liveIds)
            known.keys.retainAll(liveIds)
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val scrollState = rememberScrollState()
        Box(modifier = Modifier.weight(1f)) {
            // Right-anchored build-out: the pill row end-aligns while it fits (each new pill
            // pushes the rest left), and reverseScrolling pins the viewport to the RIGHT end
            // when it overflows — scrolling reveals the older content on the left.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .horizontalScroll(scrollState, reverseScrolling = true),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Suggestions sit at the LEFT (leading) end so they never push the right-anchored
                // confirmed pills; tapping one commits it (it then reappears as a confirmed tag).
                suggestions.forEach { suggestion ->
                    SuggestionPill(suggestion = suggestion, onTap = { onAcceptSuggestion(suggestion) })
                }
                order.forEach { id ->
                    val tag = known[id] ?: return@forEach
                    AnimatedVisibility(
                        visible = id in liveIds,
                        exit = shrinkHorizontally(animationSpec = tween(TAG_COLLAPSE_ANIM_MS)) +
                            fadeOut(animationSpec = tween(TAG_COLLAPSE_ANIM_MS)),
                    ) {
                        val pendingRemoval = id in pendingRemovalTagIds
                        TagPill(
                            tag = tag,
                            pendingRemoval = pendingRemoval,
                            // Single tap deselects; a tap on the greyed pill corrects the mistake.
                            onTap = { if (pendingRemoval) onCancelUntag(tag) else onBeginUntag(tag) },
                        )
                    }
                }
            }
            // Fade affordance on each edge hiding content (the band colour is opaque, so a plain
            // overlay gradient reads correctly — no blend modes needed). The overlay MUST be
            // matchParentSize, never fillMaxHeight/Size: this Box is content-sized (the pills),
            // and a fill* child under the screen-height max constraint inflated the whole header
            // band to screen height the moment scrolling engaged (the device-reported title/date
            // pushed-to-bottom glitch). matchParentSize sizes to the pills without influencing
            // the Box's own measurement. With reverseScrolling, value 0 = the RIGHT end: content
            // is hidden on the LEFT while value < maxValue, and on the RIGHT once value > 0.
            Box(modifier = Modifier.matchParentSize()) {
                if (scrollState.value < scrollState.maxValue) {
                    FadeEdge(color = fadeColor, leftEdge = true, modifier = Modifier.align(Alignment.CenterStart))
                }
                if (scrollState.value > 0) {
                    FadeEdge(color = fadeColor, leftEdge = false, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        IconButton(onClick = onAddTag, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add tag",
                tint = Neutral500,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FadeEdge(color: Color, leftEdge: Boolean, modifier: Modifier = Modifier) {
    val brush = if (leftEdge) {
        Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)))
    } else {
        Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color))
    }
    Box(modifier = modifier.width(TAG_ROW_FADE_WIDTH).fillMaxHeight().background(brush))
}

/**
 * One tag pill. [pendingRemoval] renders it greyed in place AND lifts the max-width cap so the
 * full name shows — the user sees exactly what's being removed during the correction window.
 */
// Low-opacity Leap-brand wash marking an AI (Level 2) suggestion — distinct from the flat neutral
// of a Level 1 (existing-tag) suggestion and from a confirmed tag's solid colour. Kept a light tint
// so LeapGrey pill text stays readable in both themes (FA-24d).
private val AiSuggestionBrush = Brush.linearGradient(
    listOf(
        LeapBlue.copy(alpha = 0.18f),
        LeapGreen.copy(alpha = 0.18f),
        LeapNavy.copy(alpha = 0.18f),
        LeapPink.copy(alpha = 0.18f),
    ),
)

/**
 * A tag SUGGESTION pill (FA-24d): a leading "+" marks "not yet added, tap to add" — the one cue that
 * distinguishes it from an identically-neutral pending-removal pill. Three looks:
 *  - [SuggestionOrigin.EXISTING] (Level 1) — flat neutral.
 *  - [SuggestionOrigin.AI] (new AI tag) — Leap-gradient FILL.
 *  - [SuggestionOrigin.AI_EXISTING] (AI picked one of your tags) — neutral fill + thin gradient BORDER.
 */
@Composable
private fun SuggestionPill(
    suggestion: SuggestedTag,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    val newAiTag = suggestion.origin == SuggestionOrigin.AI
    val aiPickedExisting = suggestion.origin == SuggestionOrigin.AI_EXISTING
    val textColor = if (newAiTag) LeapGrey else Neutral500
    val base = modifier
        .clip(shape)
        .let { if (newAiTag) it.background(AiSuggestionBrush) else it.background(Neutral200) }
        .let { if (aiPickedExisting) it.border(1.dp, AiSuggestionBrush, shape) else it }
        .clickable(onClick = onTap)
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "+ ",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
        Text(
            text = suggestion.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = TAG_PILL_MAX_WIDTH),
        )
    }
}

@Composable
private fun TagPill(
    tag: Tag,
    pendingRemoval: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (pendingRemoval) Neutral200 else Color(tag.colorArgb)
    val textColor = if (pendingRemoval) Neutral500 else LeapGrey
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Greyed = about to be removed: show the full name so the correction is informed.
            modifier = if (pendingRemoval) Modifier else Modifier.widthIn(max = TAG_PILL_MAX_WIDTH),
        )
    }
}
