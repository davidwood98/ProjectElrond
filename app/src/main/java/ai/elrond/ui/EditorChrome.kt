package ai.elrond.ui

import ai.elrond.domain.NotePage
import ai.elrond.domain.NotebookSummary
import ai.elrond.domain.PageTransform
import ai.elrond.domain.PageViewOrientation
import ai.elrond.domain.PaperColor
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.SubjectNode
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapTheme
import ai.elrond.ui.theme.Neutral100
import ai.elrond.ui.theme.Neutral200
import ai.elrond.ui.theme.Neutral400
import ai.elrond.ui.theme.Neutral300
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/**
 * Editor chrome from the Claude Design "Canvas" handoff (FA-14): the paper background, the note
 * title/date header (with inline rename + a note-tab placeholder), the Pages popup, and the
 * Library/Subjects drawer.
 *
 * Pages and Subjects are UI-only placeholders per scope — the app is one-page-per-note and has no
 * subject backend yet, so those overlays scaffold the design without storing anything. The Library
 * drawer's note list IS live (it navigates between real notes).
 */

/** Maps a [PaperColor] to its on-screen sheet tint (the enum → Color bridge lives in the ui layer). */
fun PaperColor.toSheetColor(): Color = when (this) {
    PaperColor.WHITE -> Color(0xFFFFFFFF)
    PaperColor.CREAM -> Color(0xFFFBF6EC)
    PaperColor.YELLOW -> Color(0xFFFCF8D8)
    PaperColor.DRAFTING_BLUE -> Color(0xFFEAF1F8)
}

/**
 * Paper background drawn behind the ink: Ruled / Dots / Grid / Plain, in the notebook's paper colour
 * (FA-20). The lattice spacing scales with [gridSpacing] (1 = compact … 10 = sparse; 5 = default).
 *
 * The page is a fixed A-ratio sheet placed by [transform]: it fills the shorter screen edge and is
 * centred (a "desk" margin appears on the long axis), so rotating the device never reflows it.
 * [landscape] flips the sheet's aspect (a wide sheet instead of tall). The sheet is the chosen paper
 * colour with the rule/dot/grid lattice clipped to it; the desk is a neutral tint with a hairline
 * page border. Everything is positioned through [transform] so the paper sits under the page-mapped ink.
 */
@Composable
fun PaperBackground(
    paper: PaperStyle,
    modifier: Modifier = Modifier,
    transform: PageTransform = PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
    gridSpacing: Int = PaperStyle.DEFAULT_GRID_SPACING,
    paperColor: PaperColor = PaperColor.DEFAULT,
    landscape: Boolean = false,
) {
    val sheet = paperColor.toSheetColor()
    val desk = Neutral100
    val border = Neutral200
    val mark = Neutral200
    // Density 1→0.6× … 5→1.0× … 10→1.5× of the base spacing.
    val densityFactor = 0.5f + gridSpacing.coerceIn(PaperStyle.MIN_GRID_SPACING, PaperStyle.MAX_GRID_SPACING) * 0.1f
    Canvas(modifier = modifier.fillMaxSize().background(desk)) {
        // The page rectangle on screen: left/top from the transform, width = the screen span between
        // the two side margins, height its A-ratio extent for the orientation. (scale = 1 until zoom.)
        val pageLeft = transform.offsetX
        val pageWidth = (size.width - 2f * transform.offsetX).coerceAtLeast(0f)
        val pageTop = transform.offsetY // = -scroll
        val pageHeight = if (landscape) pageWidth / PageTransform.ASPECT_RATIO else pageWidth * PageTransform.ASPECT_RATIO
        val pageRight = pageLeft + pageWidth
        val pageBottom = pageTop + pageHeight

        drawRect(color = sheet, topLeft = Offset(pageLeft, pageTop), size = Size(pageWidth, pageHeight))

        // Lattice, clipped to the sheet and offset by the page scroll so it moves with the ink.
        clipRect(left = pageLeft, top = pageTop, right = pageRight, bottom = pageBottom) {
            when (paper) {
                PaperStyle.PLAIN -> Unit
                PaperStyle.DOTS -> {
                    val step = 26.dp.toPx() * densityFactor
                    val r = 1.4.dp.toPx()
                    var y = pageTop + step
                    while (y < pageBottom) {
                        var x = pageLeft + step
                        while (x < pageRight) {
                            drawCircle(color = mark, radius = r, center = Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                PaperStyle.RULED -> {
                    val step = 34.dp.toPx() * densityFactor
                    val w = 1.dp.toPx()
                    var y = pageTop + step
                    while (y < pageBottom) {
                        drawLine(color = mark, start = Offset(pageLeft, y), end = Offset(pageRight, y), strokeWidth = w)
                        y += step
                    }
                }
                PaperStyle.GRID -> {
                    val step = 28.dp.toPx() * densityFactor
                    val w = 1.dp.toPx()
                    var y = pageTop + step
                    while (y < pageBottom) {
                        drawLine(color = mark, start = Offset(pageLeft, y), end = Offset(pageRight, y), strokeWidth = w)
                        y += step
                    }
                    var x = pageLeft + step
                    while (x < pageRight) {
                        drawLine(color = mark, start = Offset(x, pageTop), end = Offset(x, pageBottom), strokeWidth = w)
                        x += step
                    }
                }
            }
        }

        // Hairline page border so the sheet edge is visible against the desk.
        drawRect(
            color = border,
            topLeft = Offset(pageLeft, pageTop),
            size = Size(pageWidth, pageHeight),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** The grey header-band tint (handoff: `rgba(38,38,38,0.045)`). */
// Opaque light-grey band so the paper texture (ruled lines / dots) doesn't bleed through the title +
// tabs. Neutral100 matches the previous translucent band's apparent colour over white paper.
private val HeaderBandColor = Neutral100

/**
 * Per-notebook page-style dialog (FA-20): paper style (Lines / Dots / Grid / Blank), spacing density
 * (1–10), orientation (Portrait / Landscape), and paper colour. Edits apply to the whole notebook
 * (the global default lives in Settings); changes are live as the user picks them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageStyleDialog(
    paperStyle: PaperStyle,
    gridSpacing: Int,
    paperColor: PaperColor,
    viewOrientation: PageViewOrientation,
    onPaperStyle: (PaperStyle) -> Unit,
    onGridSpacing: (Int) -> Unit,
    onPaperColor: (PaperColor) -> Unit,
    onViewOrientation: (PageViewOrientation) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LeapTheme.tokens.accent
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Page style", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Applies to this notebook",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PageStyleLabel("Paper")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val styles = listOf(
                        PaperStyle.RULED to "Lines",
                        PaperStyle.DOTS to "Dots",
                        PaperStyle.GRID to "Grid",
                        PaperStyle.PLAIN to "Blank",
                    )
                    styles.forEach { (style, label) ->
                        FilterChip(
                            selected = paperStyle == style,
                            onClick = { onPaperStyle(style) },
                            label = { Text(label) },
                        )
                    }
                }

                PageStyleLabel("Spacing density")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compact", style = MaterialTheme.typography.labelSmall, color = Neutral500)
                    Slider(
                        value = gridSpacing.toFloat(),
                        onValueChange = { onGridSpacing(it.roundToInt()) },
                        valueRange = PaperStyle.MIN_GRID_SPACING.toFloat()..PaperStyle.MAX_GRID_SPACING.toFloat(),
                        steps = PaperStyle.MAX_GRID_SPACING - PaperStyle.MIN_GRID_SPACING - 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("Wide", style = MaterialTheme.typography.labelSmall, color = Neutral500)
                }

                PageStyleLabel("Orientation")
                OrientationDropdown(viewOrientation, onViewOrientation)

                PageStyleLabel("Paper colour")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PaperColor.entries.forEach { color ->
                        val selected = paperColor == color
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color.toSheetColor())
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) accent else Neutral300,
                                    shape = CircleShape,
                                )
                                .clickable { onPaperColor(color) },
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun PageStyleLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OrientationDropdown(
    current: PageViewOrientation,
    onSelect: (PageViewOrientation) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = if (current == PageViewOrientation.LANDSCAPE) "Landscape" else "Portrait"
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(label)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(PageViewOrientation.PORTRAIT to "Portrait", PageViewOrientation.LANDSCAPE to "Landscape")
                .forEach { (orientation, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { onSelect(orientation); open = false },
                    )
                }
        }
    }
}

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
    tabs: List<NotebookSummary>,
    currentNotebookId: String?,
    currentTitle: String,
    onSelectTab: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCurrent = remember(tabs, currentNotebookId) { tabs.any { it.notebookId == currentNotebookId } }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fallback only while the current notebook hasn't yet propagated into the session list.
        if (!hasCurrent) {
            TabPill(label = currentTitle, active = true, onClick = {})
        }
        tabs.forEach { tab ->
            val active = tab.notebookId == currentNotebookId
            TabPill(
                label = if (active) currentTitle else tab.title,
                active = active,
                // Tapping a notebook tab opens its most-recently-viewed page (FA-20 open-to-last-viewed).
                onClick = { if (!active) onSelectTab(tab.lastViewedPageId) },
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
 * The Pages popup (FA-20): a thumbnail grid of the open notebook's pages. Tap a page to open it; the
 * star toggles a bookmark; the ⋮ menu moves a page earlier/later or deletes it. **Press-hold-drag**
 * a page to reorder — the page under the finger shows an accent drop indicator and the drag commits
 * the new order on release. The **Select** (tick-box) toolbar toggle enters multi-select mode, where
 * tapping a page checks it and a **Delete (N)** action removes the checked pages (the notebook always
 * keeps at least one). The trailing tile adds a new page.
 */
@Composable
fun PagesOverlay(
    pages: List<NotePage>,
    currentPageId: String,
    noteListViewModel: NoteListViewModel,
    onOpenPage: (String) -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (String) -> Unit,
    onToggleBookmark: (String, Boolean) -> Unit,
    onMovePage: (String, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
    onMultiDelete: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }

    fun toggleSelect(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.85f),
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
                    Pill(if (pages.size == 1) "1 page" else "${pages.size} pages")
                    Spacer(Modifier.weight(1f))
                    if (selectMode) {
                        TextButton(
                            onClick = {
                                onMultiDelete(selectedIds.toSet())
                                selectedIds.clear()
                                selectMode = false
                            },
                            // Never offer to delete the whole notebook.
                            enabled = selectedIds.isNotEmpty() && selectedIds.size < pages.size,
                        ) { Text("Delete (${selectedIds.size})") }
                        TextButton(onClick = { selectMode = false; selectedIds.clear() }) { Text("Done") }
                    } else {
                        IconButton(onClick = { selectMode = true }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Outlined.CheckBox,
                                contentDescription = "Select pages",
                                tint = Neutral500,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painterResource(ElrondIcons.Close),
                            contentDescription = "Close",
                            tint = Neutral500,
                            modifier = Modifier.size(22.dp).clickable(onClick = onDismiss),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                        val dragModifier = Modifier.pointerInput(page.id, selectMode, pages.size) {
                            if (selectMode) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingId = page.id; dragOffset = Offset.Zero; dropTargetId = null },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta
                                    // Hit-test the dragged card's current centre against the other cards.
                                    val centre = (cardBounds[page.id]?.center ?: Offset.Zero) + dragOffset
                                    dropTargetId = cardBounds.entries
                                        .firstOrNull { it.key != page.id && it.value.contains(centre) }?.key
                                },
                                onDragEnd = {
                                    val from = draggingId
                                    val to = dropTargetId
                                    if (from != null && to != null && from != to) {
                                        val ids = pages.map { it.id }.toMutableList()
                                        ids.remove(from)
                                        val toIdx = ids.indexOf(to)
                                        ids.add(if (toIdx < 0) ids.size else toIdx, from)
                                        onReorder(ids)
                                    }
                                    draggingId = null; dropTargetId = null; dragOffset = Offset.Zero
                                },
                                onDragCancel = { draggingId = null; dropTargetId = null; dragOffset = Offset.Zero },
                            )
                        }
                        PageGridCard(
                            page = page,
                            number = index + 1,
                            isCurrent = page.id == currentPageId,
                            canDelete = pages.size > 1,
                            canMoveEarlier = index > 0,
                            canMoveLater = index < pages.lastIndex,
                            noteListViewModel = noteListViewModel,
                            selectMode = selectMode,
                            selected = page.id in selectedIds,
                            isDragging = draggingId == page.id,
                            isDropTarget = dropTargetId == page.id && draggingId != null,
                            dragOffset = if (draggingId == page.id) dragOffset else Offset.Zero,
                            dragModifier = dragModifier,
                            onBoundsChanged = { cardBounds[page.id] = it },
                            onOpen = { if (selectMode) toggleSelect(page.id) else onOpenPage(page.id) },
                            onToggleSelect = { toggleSelect(page.id) },
                            onToggleBookmark = { onToggleBookmark(page.id, !page.isBookmarked) },
                            onDelete = { onDeletePage(page.id) },
                            onMove = { forward -> onMovePage(page.id, forward) },
                        )
                    }
                    if (!selectMode) item { AddPageTile(onClick = onAddPage) }
                }
            }
        }
    }
}

@Composable
private fun PageGridCard(
    page: NotePage,
    number: Int,
    isCurrent: Boolean,
    canDelete: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    noteListViewModel: NoteListViewModel,
    selectMode: Boolean,
    selected: Boolean,
    isDragging: Boolean,
    isDropTarget: Boolean,
    dragOffset: Offset,
    dragModifier: Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = LeapTheme.tokens.accent
    val borderColor = when {
        isDropTarget -> accent
        isCurrent -> accent
        else -> Neutral200
    }
    val borderWidth = when {
        isDropTarget -> 3.dp
        isCurrent -> 2.dp
        else -> 1.dp
    }
    Column(
        modifier = Modifier
            .onGloballyPositioned { onBoundsChanged(it.boundsInParent()) }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                if (isDragging) { scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 16f; alpha = 0.96f }
            }
            .clip(RoundedCornerShape(13.dp))
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(13.dp))
            .then(dragModifier)
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            NoteThumbnail(page = page, viewModel = noteListViewModel, modifier = Modifier.fillMaxSize())
            if (page.isBookmarked) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = "Bookmarked",
                    tint = accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
            // Multi-select checkbox overlay.
            if (selectMode) {
                Icon(
                    if (selected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) accent else Neutral500,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 11.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Page $number",
                style = MaterialTheme.typography.labelMedium,
                color = LeapGrey,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (!selectMode) {
                IconButton(onClick = onToggleBookmark, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (page.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (page.isBookmarked) accent else Neutral400,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            painterResource(ElrondIcons.MoreVert),
                            contentDescription = "Page actions",
                            tint = Neutral500,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canMoveEarlier) {
                            DropdownMenuItem(text = { Text("Move earlier") }, onClick = { menuOpen = false; onMove(false) })
                        }
                        if (canMoveLater) {
                            DropdownMenuItem(text = { Text("Move later") }, onClick = { menuOpen = false; onMove(true) })
                        }
                        if (canDelete) {
                            DropdownMenuItem(text = { Text("Delete page") }, onClick = { menuOpen = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPageTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .height(190.dp)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, Neutral200, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add page", tint = Neutral400, modifier = Modifier.size(28.dp))
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
