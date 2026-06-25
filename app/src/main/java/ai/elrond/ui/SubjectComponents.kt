package ai.elrond.ui

import ai.elrond.domain.Subject
import ai.elrond.domain.SubjectNode
import ai.elrond.domain.SubjectPalette
import ai.elrond.domain.SubjectTree
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.Neutral300
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Bridges a subject's palette [colorId] to a Compose [Color] (keeps [SubjectPalette] Compose-free). */
fun subjectColor(colorId: Int): Color = Color(SubjectPalette.argb(colorId))

internal const val SUBJECT_COLOR_PICKER_TAG = "subject-color-picker"
internal const val SUBJECT_BREADCRUMB_TAG = "subject-breadcrumb"

/**
 * The pastel swatch-grid colour picker (modal dialog). Laid out [SubjectPalette.SHADE_COUNT] columns
 * wide so each row is one hue's shade ramp — the "full spectrum with shade levels" of the spec.
 */
@Composable
fun SubjectColorPicker(
    currentColorId: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 360.dp).testTag(SUBJECT_COLOR_PICKER_TAG),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Choose a colour",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LeapGrey,
                )
                Spacer(Modifier.padding(top = 8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(SubjectPalette.SHADE_COUNT),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items((0 until SubjectPalette.SIZE).toList()) { colorId ->
                        val selected = colorId == SubjectPalette.normalize(currentColorId)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(subjectColor(colorId))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Neutral300,
                                    shape = CircleShape,
                                )
                                .clickable { onPick(colorId) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

/**
 * The note breadcrumb shown on note cards: the subject's ancestry path rendered left-to-right as
 * coloured dots for the ancestors, then the containing (deepest) subject as a named pill. Tapping the
 * pill navigates to that subject. Renders nothing when the note is unfiled.
 */
@Composable
fun SubjectBreadcrumb(
    subjectId: String?,
    subjectsById: Map<String, Subject>,
    onTapSubject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val path = remember(subjectId, subjectsById) { SubjectTree.pathTo(subjectId, subjectsById) }
    if (path.isEmpty()) return
    Row(
        modifier = modifier.testTag(SUBJECT_BREADCRUMB_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        path.dropLast(1).forEach { ancestor ->
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(subjectColor(ancestor.colorId)))
        }
        val leaf = path.last()
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = subjectColor(leaf.colorId).copy(alpha = 0.20f),
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { onTapSubject(leaf.id) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(subjectColor(leaf.colorId)))
                Spacer(Modifier.width(5.dp))
                Text(
                    leaf.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LeapGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The breadcrumb tab bar at the top of the notes grid while a subject is selected:
 * `All Notes › Subject1 › Subject2`, every crumb tappable. Renders nothing for All Notes (empty path).
 */
@Composable
fun SubjectPathTabs(
    path: List<Subject>,
    onSelectAll: () -> Unit,
    onSelectSubject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (path.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(label = "All Notes", dot = null, active = false, onClick = onSelectAll)
        path.forEachIndexed { index, subject ->
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Neutral500,
                modifier = Modifier.size(18.dp),
            )
            Crumb(
                label = subject.name,
                dot = subjectColor(subject.colorId),
                active = index == path.lastIndex,
                onClick = { onSelectSubject(subject.id) },
            )
        }
    }
}

@Composable
private fun Crumb(label: String, dot: Color?, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.onSurface else Neutral500,
            maxLines = 1,
        )
    }
}

/**
 * "Move to subject" picker (modal dialog) for a note card: a flat, indented list of every subject
 * plus an **Unfiled** option. Tapping a row files the note there (or unfiles it) and dismisses.
 */
@Composable
fun SubjectPickerDialog(
    tree: List<SubjectNode>,
    currentSubjectId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(tree) { SubjectTree.flatten(tree) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Move to subject",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LeapGrey,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    PickerRow(
                        label = "Unfiled",
                        dot = null,
                        indent = 0,
                        selected = currentSubjectId == null,
                        onClick = { onPick(null) },
                    )
                    rows.forEach { node ->
                        PickerRow(
                            label = node.subject.name,
                            dot = subjectColor(node.subject.colorId),
                            indent = node.depth,
                            selected = node.subject.id == currentSubjectId,
                            onClick = { onPick(node.subject.id) },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, dot: Color?, indent: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (20 + indent * 18).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(11.dp))
        } else {
            Spacer(Modifier.width(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else LeapGrey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}
