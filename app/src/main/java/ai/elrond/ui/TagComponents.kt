package ai.elrond.ui

import ai.elrond.domain.Tag
import ai.elrond.domain.TagTapResolver
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.Neutral100
import ai.elrond.ui.theme.Neutral200
import ai.elrond.ui.theme.Neutral500
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

// ── Tag pill metrics (FA-24). The row is a FIXED region: it never grows, shrinks or shifts the
// header chrome regardless of tag count or title length — overflow scrolls internally.
private val TAG_PILL_MAX_WIDTH = 96.dp
private val TAG_ROW_FADE_WIDTH = 18.dp
private const val TAG_COLLAPSE_ANIM_MS = 250

/**
 * The shared tag-assignment picker (FA-24) — opened from BOTH the Library card ⋮ menu and the
 * editor header's `+`, so the two surfaces can't drift. Rows TOGGLE membership: tapping an
 * unassigned tag assigns it, tapping an assigned one removes it immediately (a modal checkbox
 * list is an explicit action — the 2s undo window belongs to the always-visible header row).
 * The field below creates-and-assigns a new tag (get-or-create by unique name).
 */
@Composable
fun TagPickerDialog(
    allTags: List<Tag>,
    assignedTagIds: Set<String>,
    onToggle: (Tag) -> Unit,
    onCreateAndAssign: (String) -> Unit,
    onDismiss: () -> Unit,
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
 * The editor header's tag row (FA-24) — a FIXED-width region right of the created date. Never
 * encroaches on the title: overflow scrolls horizontally with a fade-out gradient on each edge
 * that hides content (a silently-clipped scrollable row would hide tags with no affordance).
 * Each pill truncates independently ([TAG_PILL_MAX_WIDTH]); the trailing `+` opens the shared
 * picker.
 *
 * Pill gesture (uniform regardless of truncation — [TagTapResolver]): tap 1 previews the full
 * name (expanded pill); tap 2 within 300ms greys it out in place (the ViewModel's 2s window);
 * tapping the greyed pill cancels; when the window elapses the pill collapses its width away
 * (never a jump-cut) via a ghost entry that outlives the tag by the exit animation.
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
) {
    // Preview state is purely cosmetic → local; PendingRemoval gates a DB write → ViewModel.
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    var lastTapAt by remember { mutableStateOf(0L) }

    // Ghost bookkeeping so a removed tag collapses instead of jump-cutting: keep every seen tag
    // (and a stable order); prune ghosts after the exit animation has played.
    val known = remember { mutableStateMapOf<String, Tag>() }
    val order = remember { mutableStateListOf<String>() }
    val liveIds = tags.map { it.id }.toSet()
    tags.forEach { tag ->
        known[tag.id] = tag
        if (tag.id !in order) order.add(tag.id)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                order.forEach { id ->
                    val tag = known[id] ?: return@forEach
                    AnimatedVisibility(
                        visible = id in liveIds,
                        exit = shrinkHorizontally(animationSpec = tween(TAG_COLLAPSE_ANIM_MS)) +
                            fadeOut(animationSpec = tween(TAG_COLLAPSE_ANIM_MS)),
                    ) {
                        TagPill(
                            tag = tag,
                            selected = selectedTagId == id,
                            pendingRemoval = id in pendingRemovalTagIds,
                            onTap = {
                                val now = System.currentTimeMillis()
                                when (
                                    TagTapResolver.resolve(
                                        nowMs = now,
                                        lastTapAtMs = lastTapAt,
                                        selectedTagId = selectedTagId,
                                        tagId = id,
                                        isPendingRemoval = id in pendingRemovalTagIds,
                                    )
                                ) {
                                    TagTapResolver.TapOutcome.ENTER_PREVIEW -> {
                                        selectedTagId = id
                                        lastTapAt = now
                                    }
                                    TagTapResolver.TapOutcome.BEGIN_UNTAG -> {
                                        selectedTagId = null
                                        onBeginUntag(tag)
                                    }
                                    TagTapResolver.TapOutcome.CANCEL_UNTAG -> {
                                        onCancelUntag(tag)
                                        selectedTagId = id // re-selects as still-tagged
                                        lastTapAt = now
                                    }
                                }
                            },
                        )
                    }
                }
            }
            // Fade affordance on each edge hiding content (the band colour is opaque, so a plain
            // overlay gradient reads correctly — no blend modes needed).
            if (scrollState.value > 0) {
                FadeEdge(color = fadeColor, leftEdge = true, modifier = Modifier.align(Alignment.CenterStart))
            }
            if (scrollState.value < scrollState.maxValue) {
                FadeEdge(color = fadeColor, leftEdge = false, modifier = Modifier.align(Alignment.CenterEnd))
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
 * One tag pill. [selected] (preview) lifts the max-width cap so the full name shows — the
 * spec's "expanded pill" variant; [pendingRemoval] renders it greyed in place.
 */
@Composable
private fun TagPill(
    tag: Tag,
    selected: Boolean,
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
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (selected) Modifier else Modifier.widthIn(max = TAG_PILL_MAX_WIDTH),
        )
    }
}

/** The header tag row's fixed width (see [TagRow]) — a hard width, so an empty row can't shift chrome. */
val TAG_ROW_WIDTH: Dp = 220.dp
