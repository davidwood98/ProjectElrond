package ai.elrond.ui

import ai.elrond.domain.SubjectNode
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Callbacks the editable subject tree needs. The read-only Quick Nav supplies only the first two. */
data class SubjectTreeActions(
    val onToggleExpand: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onAddChild: (parentId: String, name: String) -> Unit = { _, _ -> },
    val onRename: (id: String, name: String) -> Unit = { _, _ -> },
    val onSetColor: (id: String, colorId: Int) -> Unit = { _, _ -> },
    val onDelete: (id: String) -> Unit = {},
    /** Drag-to-reorder: move the subject one step among its siblings (up = toward the top). */
    val onMove: (id: String, up: Boolean) -> Unit = { _, _ -> },
)

internal const val SUBJECT_ROW_TAG = "subject-row"
internal const val SUBJECT_ADD_CHILD_TAG = "subject-add-child"
internal const val SUBJECT_DOT_TAG = "subject-color-dot"

/**
 * The subject (folder) tree. In [editable] mode (home sidebar): tap selects/filters, the chevron
 * expands, the colour dot opens the picker, **+** adds a child, long-press opens the context menu
 * (rename / add subfolder / change colour / delete), and the drag handle reorders siblings. In
 * read-only mode (canvas Quick Nav) only expand/collapse works — subjects can't be edited or selected.
 */
@Composable
fun SubjectTreeView(
    nodes: List<SubjectNode>,
    expandedIds: Set<String>,
    selectedId: String?,
    editable: Boolean,
    actions: SubjectTreeActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SubjectNodeList(
            siblings = nodes,
            expandedIds = expandedIds,
            selectedId = selectedId,
            editable = editable,
            actions = actions,
        )
    }
}

@Composable
private fun SubjectNodeList(
    siblings: List<SubjectNode>,
    expandedIds: Set<String>,
    selectedId: String?,
    editable: Boolean,
    actions: SubjectTreeActions,
) {
    siblings.forEach { node ->
        SubjectRow(
            node = node,
            expanded = node.subject.id in expandedIds,
            selected = node.subject.id == selectedId,
            editable = editable,
            actions = actions,
        )
        if (node.children.isNotEmpty() && node.subject.id in expandedIds) {
            SubjectNodeList(
                siblings = node.children,
                expandedIds = expandedIds,
                selectedId = selectedId,
                editable = editable,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubjectRow(
    node: SubjectNode,
    expanded: Boolean,
    selected: Boolean,
    editable: Boolean,
    actions: SubjectTreeActions,
) {
    val subject = node.subject
    val hasChildren = node.children.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var addingChild by remember { mutableStateOf(false) }
    var pickingColor by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        val rowColor = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            dragging -> MaterialTheme.colorScheme.surfaceVariant
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(rowColor)
                .padding(start = (8 + node.depth * 16).dp, end = 6.dp)
                .testTag(SUBJECT_ROW_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand/collapse chevron (only when there are children).
            if (hasChildren) {
                IconButton(onClick = { actions.onToggleExpand(subject.id) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Neutral500,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(26.dp))
            }

            // Colour dot — tap opens the picker (editable only). The visual dot is 13dp but the tap
            // target is a 32dp box so it's comfortably hittable on a tablet.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .then(if (editable) Modifier.clickable { pickingColor = true } else Modifier)
                    .testTag(SUBJECT_DOT_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(subjectColor(subject.colorId)),
                )
            }

            // Name — tap selects/filters (editable) or toggles expand (read-only); long-press opens menu.
            Text(
                subject.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else LeapGrey,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = { if (editable) actions.onSelect(subject.id) else actions.onToggleExpand(subject.id) },
                        onLongClick = { if (editable) menuOpen = true },
                    )
                    .padding(vertical = 9.dp),
            )

            if (editable) {
                // Inline + to add a child subject.
                IconButton(
                    onClick = { addingChild = true },
                    modifier = Modifier.size(28.dp).testTag(SUBJECT_ADD_CHILD_TAG),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add subfolder", tint = Neutral500, modifier = Modifier.size(18.dp))
                }
                // Drag handle to reorder among siblings. A drag past a small threshold moves the
                // subject one step in the drag's direction (single-step keeps the maths robust to the
                // tree's variable row spacing — descendants render between siblings). The handle does
                // NOT translate, so the gesture source stays put under the finger.
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "Reorder",
                    tint = if (dragging) MaterialTheme.colorScheme.primary else Neutral500,
                    modifier = Modifier
                        .size(22.dp)
                        .pointerInput(subject.id) {
                            var dy = 0f
                            val threshold = 14.dp.toPx()
                            detectDragGestures(
                                onDragStart = { dy = 0f; dragging = true },
                                onDragEnd = {
                                    dragging = false
                                    if (dy <= -threshold) actions.onMove(subject.id, true)
                                    else if (dy >= threshold) actions.onMove(subject.id, false)
                                },
                                onDragCancel = { dragging = false },
                                onDrag = { change, amount -> change.consume(); dy += amount.y },
                            )
                        },
                )
            }

            // Context menu anchored to the row end.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Add subfolder") },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    onClick = { menuOpen = false; addingChild = true },
                )
                DropdownMenuItem(
                    text = { Text("Change colour") },
                    leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    onClick = { menuOpen = false; pickingColor = true },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; confirmingDelete = true },
                )
            }
        }
    }

    if (renaming) {
        SubjectNameDialog(
            title = "Rename subject",
            initial = subject.name,
            confirmLabel = "Save",
            onConfirm = { actions.onRename(subject.id, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (addingChild) {
        SubjectNameDialog(
            title = "New subfolder",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { actions.onAddChild(subject.id, it); addingChild = false },
            onDismiss = { addingChild = false },
        )
    }
    if (pickingColor) {
        SubjectColorPicker(
            currentColorId = subject.colorId,
            onPick = { actions.onSetColor(subject.id, it); pickingColor = false },
            onDismiss = { pickingColor = false },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete “${subject.name}”?") },
            text = {
                Text(
                    if (hasChildren) {
                        "This also deletes its subfolders. Notes inside stay in your library — they just become unfiled."
                    } else {
                        "Notes inside stay in your library — they just become unfiled."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { actions.onDelete(subject.id); confirmingDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * A name-entry dialog (auto-focused field + keyboard) used for both creating and renaming subjects —
 * satisfies the spec's "auto-opens keyboard". A blank name falls back to the repository default.
 */
@Composable
fun SubjectNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
