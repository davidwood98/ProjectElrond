package ai.elrond.ui

import ai.elrond.domain.NotePage
import ai.elrond.domain.NoteTabsMode
import ai.elrond.domain.PaperStyle
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.theme.LeapGreen
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapNavy
import ai.elrond.ui.theme.LeapPink
import ai.elrond.ui.theme.LeapTheme
import ai.elrond.ui.theme.Neutral200
import ai.elrond.ui.theme.Neutral400
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * The editor header: the open note's title (Poppins, tap to rename inline) and creation date, with
 * a note-tab pill above it. The tab is a placeholder for future multi-note tabs — only the open note
 * exists, shown as the active tab; [tabsMode] styles it (Separate = floating pill, Attached = docked).
 */
@Composable
fun EditorHeader(
    title: String,
    dateLabel: String,
    tabsMode: NoteTabsMode,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        NoteTabChip(title = title, mode = tabsMode)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (editing) {
                var text by remember(title) { mutableStateOf(title) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.widthIn(max = 320.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(text); editing = false }),
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LeapGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clickable { editing = true },
                )
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

@Composable
private fun NoteTabChip(title: String, mode: NoteTabsMode) {
    val tokens = LeapTheme.tokens
    val shape = if (mode == NoteTabsMode.ATTACHED) {
        RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    } else {
        RoundedCornerShape(8.dp)
    }
    Surface(color = tokens.accentSoft, shape = shape) {
        Text(
            text = title,
            color = tokens.accentStrong,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 12.dp, vertical = 5.dp),
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
 * The Library / subject-structure drawer (left). Subjects are a UI placeholder (no backend yet) —
 * shown as static colour-dot groups; the note list below is live and navigates to real notes.
 */
@Composable
fun LibraryOverlay(
    notes: List<NotePage>,
    onOpenNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                        "Library",
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    SectionLabel("Subjects")
                    SubjectRow("Welcome", LeapPink)
                    SubjectRow("Test structure", LeapNavy)
                    SubjectRow("Personal", LeapGreen)
                    Text(
                        "Organising notes into subjects is coming soon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Neutral400,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 8.dp),
                    )
                    SectionLabel("Notes")
                    notes.forEach { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { onOpenNote(page.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                tint = Neutral400,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                page.displayTitle(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LeapGrey,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun SubjectRow(name: String, dot: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(11.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, color = LeapGrey)
    }
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
