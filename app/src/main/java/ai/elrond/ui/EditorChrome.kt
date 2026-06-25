package ai.elrond.ui

import ai.elrond.domain.NotePage
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.SubjectNode
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapTheme
import ai.elrond.ui.theme.Neutral200
import ai.elrond.ui.theme.Neutral400
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Editor chrome from the Claude Design "Canvas" handoff (FA-14): the paper background, the note
 * title/date header (with inline rename + a note-tab placeholder), the Pages popup, and the
 * Library/Subjects drawer.
 *
 * Pages and Subjects are UI-only placeholders per scope — the app is one-page-per-note and has no
 * subject backend yet, so those overlays scaffold the design without storing anything. The Library
 * drawer's note list IS live (it navigates between real notes).
 */

/** Paper background drawn behind the ink: Ruled / Plain / Dots (from the paper-style setting). */
@Composable
fun PaperBackground(paper: PaperStyle, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val mark = Neutral200
    Canvas(modifier = modifier.fillMaxSize().background(surface)) {
        when (paper) {
            PaperStyle.PLAIN -> Unit // blank white page
            PaperStyle.DOTS -> {
                val step = 26.dp.toPx()
                val r = 1.4.dp.toPx()
                var y = step
                while (y < size.height) {
                    var x = step
                    while (x < size.width) {
                        drawCircle(color = mark, radius = r, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            PaperStyle.RULED -> {
                val step = 34.dp.toPx()
                val w = 1.dp.toPx()
                var y = step
                while (y < size.height) {
                    drawLine(color = mark, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = w)
                    y += step
                }
            }
        }
    }
}

/** The grey header-band tint (handoff: `rgba(38,38,38,0.045)`). */
private val HeaderBandColor = Color(0xFF262626).copy(alpha = 0.045f)

/**
 * The editor header (FA-15): a distinct light-grey band holding the open note's title (bold Poppins,
 * tap the edit icon to rename inline) and the **created** date on the right. In **Separate** tab mode
 * `NoteCanvasScreen` passes the note tabs via [tabs] and they render at the top of this band, just
 * above the title; in **Attached** mode [tabs] is null (the tabs dock inside the toolbar card instead).
 */
@Composable
fun EditorHeader(
    title: String,
    dateLabel: String,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
    tabs: (@Composable () -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(HeaderBandColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Separate-mode note tabs sit at the top of the band, just above the title.
        if (tabs != null) {
            tabs()
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (editing) {
                val focusRequester = remember { FocusRequester() }
                var text by remember(title) { mutableStateOf(title) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .focusRequester(focusRequester)
                        // Commit when focus is lost (tap away), not only on the IME Done action.
                        .onFocusChanged { if (!it.isFocused && editing) { onRename(text); editing = false } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(text); editing = false }),
                )
                // Focus + raise the keyboard immediately so the user can type on the first tap.
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                // The title is NOT clickable: a wide clickable here sits over the canvas and would
                // swallow S Pen strokes that start in the header band. Rename is via the edit icon.
                Text(
                    // headlineSmall is Poppins; bump to ExtraBold for the bolder display look.
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = LeapGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                IconButton(onClick = { editing = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Rename note",
                        tint = Neutral500,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (dateLabel.isBlank()) "Saved" else "$dateLabel · Saved",
                style = MaterialTheme.typography.bodyMedium,
                color = Neutral500,
            )
        }
    }
}

/**
 * The note-tab pills: the notes opened this session, in a **stable** order (they do not reshuffle when
 * a note is re-selected — only the active highlight moves). Inactive tabs navigate. The current note is
 * highlighted in place; in the brief race before it lands in [tabs] it's shown as a leading active tab
 * so the active pill is never missing.
 */
@Composable
internal fun NoteTabPills(
    tabs: List<NotePage>,
    currentPageId: String,
    currentTitle: String,
    onSelectTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCurrent = remember(tabs, currentPageId) { tabs.any { it.id == currentPageId } }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fallback only while the current note hasn't yet propagated into the session list.
        if (!hasCurrent) {
            TabPill(label = currentTitle, active = true, onClick = {})
        }
        tabs.forEach { page ->
            val active = page.id == currentPageId
            TabPill(
                label = if (active) currentTitle else page.displayTitle(),
                active = active,
                onClick = { if (!active) onSelectTab(page.id) },
            )
        }
    }
}

@Composable
private fun TabPill(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LeapTheme.tokens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) tokens.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) tokens.accentStrong else Neutral500,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Pages popup (centred dialog) — a UI placeholder. The app stores one page per note, so this
 * shows the open note as "Page 1" plus a disabled "Add page" tile; the select/bookmark header
 * buttons are present (per the handoff) but inert until multi-page notes exist.
 */
@Composable
fun PagesOverlay(currentTitle: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Pages",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = LeapGrey,
                    )
                    Spacer(Modifier.width(10.dp))
                    Pill("1 page")
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = "Bookmarked (coming soon)",
                        tint = Neutral400,
                        modifier = Modifier.size(22.dp).padding(end = 4.dp),
                    )
                    Icon(
                        Icons.Outlined.CheckBox,
                        contentDescription = "Select (coming soon)",
                        tint = Neutral400,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painterResource(ElrondIcons.Close),
                        contentDescription = "Close",
                        tint = Neutral500,
                        modifier = Modifier.size(22.dp).clickable(onClick = onDismiss),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PageCard(label = "Page 1", caption = currentTitle, modifier = Modifier.weight(1f))
                    AddPageCard(modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Multiple pages per note are coming soon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Neutral500,
                )
            }
        }
    }
}

@Composable
private fun PageCard(label: String, caption: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, Neutral200, RoundedCornerShape(13.dp)),
    ) {
        // Dotted thumbnail, echoing the editor's dotted paper.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val step = 13.dp.toPx()
            val r = 1.dp.toPx()
            var y = step
            while (y < size.height) {
                var x = step
                while (x < size.width) {
                    drawCircle(color = Neutral200, radius = r, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = LeapGrey, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = Neutral400,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
    }
}

@Composable
private fun AddPageCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(118.dp + 38.dp)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, Neutral200, RoundedCornerShape(13.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Neutral400, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text("Add page", style = MaterialTheme.typography.bodySmall, color = Neutral400)
    }
}

/**
 * The canvas **Quick Nav** drawer (left, FA-16). The subject tree is **read-only** here — expand /
 * collapse only, no editing — per the spec; the **Unfiled** list below shows notes with no subject
 * and navigates to them. (Full subject CRUD + filing live in the home Library.)
 */
@Composable
fun LibraryOverlay(
    subjectTree: List<SubjectNode>,
    notesBySubject: Map<String?, List<NotePage>>,
    expandedIds: Set<String>,
    currentPageId: String,
    onToggleSubject: (String) -> Unit,
    onLocateCurrent: () -> Unit,
    onOpenNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Which note to flash as "you are here" — set when the locate button reveals the current note.
    var highlighted by remember { mutableStateOf<String?>(null) }
    val unfiled = notesBySubject[null].orEmpty()
    val isEmpty = subjectTree.isEmpty() && notesBySubject.values.all { it.isEmpty() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x2E262626))
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier.fillMaxHeight().width(320.dp).align(Alignment.CenterStart),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = LeapTheme.tokens.accentStrong)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Quick Nav",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = LeapGrey,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painterResource(ElrondIcons.Close),
                        contentDescription = "Close",
                        tint = Neutral500,
                        modifier = Modifier.size(20.dp).clickable(onClick = onDismiss),
                    )
                }
                // "Subjects" header with a right-aligned "current note" locator (expands the path to,
                // and highlights, the open note).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SUBJECTS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Neutral500,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onLocateCurrent(); highlighted = currentPageId },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Outlined.MyLocation,
                            contentDescription = "Current note",
                            tint = LeapTheme.tokens.accentStrong,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    if (isEmpty) {
                        Text(
                            "No notes yet. Create notes and subjects from the home library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Neutral400,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 8.dp),
                        )
                    }
                    // The subject tree, with each subject's notes nested inside it (file-explorer style).
                    subjectTree.forEach { node ->
                        QuickNavSubject(
                            node = node,
                            notesBySubject = notesBySubject,
                            expandedIds = expandedIds,
                            highlightedNoteId = highlighted,
                            depth = 0,
                            onToggle = onToggleSubject,
                            onOpenNote = onOpenNote,
                        )
                    }
                    // Notes that aren't filed into any subject sit at the root, under an "Unfiled" label.
                    if (unfiled.isNotEmpty()) {
                        SectionLabel("Unfiled")
                        unfiled.forEach { page ->
                            QuickNavNote(
                                page = page,
                                depth = 0,
                                highlighted = page.id == highlighted,
                                onOpenNote = onOpenNote,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One subject row in Quick Nav; when expanded it reveals its child subjects then its notes. */
@Composable
private fun QuickNavSubject(
    node: SubjectNode,
    notesBySubject: Map<String?, List<NotePage>>,
    expandedIds: Set<String>,
    highlightedNoteId: String?,
    depth: Int,
    onToggle: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val subject = node.subject
    val notes = notesBySubject[subject.id].orEmpty()
    val expandable = node.children.isNotEmpty() || notes.isNotEmpty()
    val expanded = subject.id in expandedIds
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable { if (expandable) onToggle(subject.id) }
            .padding(start = (8 + depth * 16).dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expandable) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Neutral500,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(subjectColor(subject.colorId)))
        Spacer(Modifier.width(10.dp))
        Text(
            subject.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = LeapGrey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (expanded) {
        node.children.forEach { child ->
            QuickNavSubject(
                node = child,
                notesBySubject = notesBySubject,
                expandedIds = expandedIds,
                highlightedNoteId = highlightedNoteId,
                depth = depth + 1,
                onToggle = onToggle,
                onOpenNote = onOpenNote,
            )
        }
        notes.forEach { page ->
            QuickNavNote(
                page = page,
                depth = depth + 1,
                highlighted = page.id == highlightedNoteId,
                onOpenNote = onOpenNote,
            )
        }
    }
}

/** A note leaf in Quick Nav; tapping it opens the note in a canvas tab. */
@Composable
private fun QuickNavNote(
    page: NotePage,
    depth: Int,
    highlighted: Boolean,
    onOpenNote: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlighted) LeapTheme.tokens.accentSoft else Color.Transparent)
            .clickable { onOpenNote(page.id) }
            // Align the note text with subject names one level shallower (chevron + dot ≈ 27dp).
            .padding(start = (8 + depth * 16 + 27).dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = if (highlighted) LeapTheme.tokens.accentStrong else Neutral400,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            page.displayTitle(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (highlighted) LeapTheme.tokens.accentStrong else LeapGrey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Neutral500,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** Small neutral count pill used in overlay headers. */
@Composable
private fun Pill(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Neutral500,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
