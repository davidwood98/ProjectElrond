package ai.elrond.ui

import ai.elrond.domain.NotePage
import ai.elrond.domain.NotebookSummary
import ai.elrond.domain.PageNavigationMode
import ai.elrond.domain.PageTransform
import ai.elrond.domain.PageViewOrientation
import ai.elrond.domain.PaperColor
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.QuickNavSearch
import ai.elrond.domain.SubjectNode
import ai.elrond.domain.Tag
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
    /** Page-space sheet width; 0 falls back to deriving it from the centring margin (preview/tests). */
    pageWidthSpacePx: Float = 0f,
    /** Number of pages stacked in the continuous document (1 in horizontal mode; FA-20). */
    pageCount: Int = 1,
    /** Page-space margin break between stacked pages in vertical-continuous mode (FA-20). */
    pageGapSpacePx: Float = 0f,
) {
    val sheet = paperColor.toSheetColor()
    val desk = Neutral100
    val border = Neutral200
    val mark = Neutral200
    // Density 1→0.6× … 5→1.0× … 10→1.5× of the base spacing.
    val densityFactor = 0.5f + gridSpacing.coerceIn(PaperStyle.MIN_GRID_SPACING, PaperStyle.MAX_GRID_SPACING) * 0.1f
    Canvas(modifier = modifier.fillMaxSize().background(desk)) {
        val scale = if (transform.scale != 0f) transform.scale else 1f
        // The sheet's on-screen width: from the page-space width × zoom when known (correct under
        // zoom/pan), else the symmetric centring margin (the old fit-width path used by previews/tests).
        // panX is the transient page-turn slide — it shifts the sheet's left but not its width.
        val pageWidth =
            if (pageWidthSpacePx > 0f) pageWidthSpacePx * scale
            else (size.width - 2f * transform.offsetX).coerceAtLeast(0f)
        val pageLeft = transform.offsetX + transform.panX
        val pageRight = pageLeft + pageWidth
        val pageHeight = if (landscape) pageWidth / PageTransform.ASPECT_RATIO else pageWidth * PageTransform.ASPECT_RATIO
        val gap = pageGapSpacePx * scale

        // Draw each page of the continuous document, stacked with the margin break. Only sheets that
        // intersect the viewport are painted.
        for (i in 0 until pageCount.coerceAtLeast(1)) {
            val pageTop = transform.offsetY + i * (pageHeight + gap)
            val pageBottom = pageTop + pageHeight
            if (pageBottom < 0f || pageTop > size.height) continue // off-screen — skip

            drawRect(color = sheet, topLeft = Offset(pageLeft, pageTop), size = Size(pageWidth, pageHeight))

            // Lattice, clipped to the sheet so it moves with the page. The spacing + mark size scale
            // with the zoom ([scale]) so the grid/lines/dots stay fixed to the PAGE (usable as stroke
            // references) rather than the screen — zooming in spreads them out 1:1 with the ink (FA-20).
            clipRect(left = pageLeft, top = pageTop, right = pageRight, bottom = pageBottom) {
                when (paper) {
                    PaperStyle.PLAIN -> Unit
                    PaperStyle.DOTS -> {
                        val step = 26.dp.toPx() * densityFactor * scale
                        val r = 1.4.dp.toPx() * scale
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
                        val step = 34.dp.toPx() * densityFactor * scale
                        val w = 1.dp.toPx() * scale
                        var y = pageTop + step
                        while (y < pageBottom) {
                            drawLine(color = mark, start = Offset(pageLeft, y), end = Offset(pageRight, y), strokeWidth = w)
                            y += step
                        }
                    }
                    PaperStyle.GRID -> {
                        val step = 28.dp.toPx() * densityFactor * scale
                        val w = 1.dp.toPx() * scale
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
}

/** The grey header-band tint (handoff: `rgba(38,38,38,0.045)`). */
// Opaque light-grey band so the paper texture (ruled lines / dots) doesn't bleed through the title +
// tabs. Neutral100 matches the previous translucent band's apparent colour over white paper.
private val HeaderBandColor = Neutral100

/** Title max-widths (FA-24 device feedback): landscape gets 50% more room for the title. */
private val TITLE_MAX_WIDTH_PORTRAIT = 320.dp
private val TITLE_MAX_WIDTH_LANDSCAPE = 480.dp

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
    pageNavigationMode: PageNavigationMode,
    onPaperStyle: (PaperStyle) -> Unit,
    onGridSpacing: (Int) -> Unit,
    onPaperColor: (PaperColor) -> Unit,
    onViewOrientation: (PageViewOrientation) -> Unit,
    onPageNavigationMode: (PageNavigationMode) -> Unit,
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

                PageStyleLabel("Scroll direction")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(
                        PageNavigationMode.VERTICAL to "Vertical",
                        PageNavigationMode.HORIZONTAL to "Horizontal",
                    )
                    modes.forEach { (mode, label) ->
                        FilterChip(
                            selected = pageNavigationMode == mode,
                            onClick = { onPageNavigationMode(mode) },
                            label = { Text(label) },
                        )
                    }
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
 * The editor title band (FA-15/FA-20): a distinct light-grey band holding the open note's title (bold
 * Poppins — **tap the title itself to rename inline**) and the **created** date on the right. The note
 * tabs are a separate pinned band ([NoteTabsBand]) above this one; only this title block scrolls away.
 *
 * FA-24 (device-feedback layout): the [TagRow] fills ALL the space between the capped title and
 * the created date — fixed regions with internal overflow handling, so the tag row and the
 * title can never encroach on each other regardless of tag count or title length. The title's
 * max-width is 320dp in portrait and 50% wider (480dp) in [landscape]; the tag space always
 * runs up to that limit before its faded scroll engages. Pills anchor to the right and build
 * out leftward.
 */
@Composable
fun EditorHeader(
    title: String,
    dateLabel: String,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    tags: List<Tag> = emptyList(),
    pendingRemovalTagIds: Set<String> = emptySet(),
    onBeginUntag: (Tag) -> Unit = {},
    onCancelUntag: (Tag) -> Unit = {},
    onAddTag: (() -> Unit)? = null,
) {
    val titleMaxWidth = if (landscape) TITLE_MAX_WIDTH_LANDSCAPE else TITLE_MAX_WIDTH_PORTRAIT
    var editing by remember { mutableStateOf(false) }
    val accent = LeapTheme.tokens.accent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(HeaderBandColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title renders identically whether viewing or editing — same Poppins ExtraBold
        // headlineSmall, same colour, same position. Editing just swaps the Text for an unstyled
        // BasicTextField with that exact TextStyle and NO box/border/background, so tapping the
        // title turns it into a live cursor in place: the text never resizes and no input box
        // appears around it (FA-20). Tap the title to start; tap away (focus loss) commits.
        val titleStyle = MaterialTheme.typography.headlineSmall.merge(
            // headlineSmall is Poppins; bump to ExtraBold for the bolder display look.
            TextStyle(fontWeight = FontWeight.ExtraBold, color = LeapGrey),
        )
        if (editing) {
            val focusRequester = remember { FocusRequester() }
            // Start with the whole title selected, so the first key typed (before the user taps to
            // place a cursor) replaces the title outright; tapping to position the cursor instead
            // collapses the selection and edits in place (FA-20 user flow).
            var value by remember(title) {
                mutableStateOf(TextFieldValue(title, TextRange(0, title.length)))
            }
            // Only commit on focus-LOSS once the field has actually GAINED focus. On first
            // composition Compose delivers an initial onFocusChanged(isFocused = false) before
            // requestFocus() runs — without this guard that closed the editor the instant it opened.
            var hasFocused by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = titleStyle,
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .widthIn(max = titleMaxWidth)
                    .focusRequester(focusRequester)
                    // Commit when focus is lost (tap a blank space), not only on the IME Done action.
                    .onFocusChanged {
                        if (it.isFocused) {
                            hasFocused = true
                        } else if (hasFocused && editing) {
                            onRename(value.text)
                            editing = false
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(value.text); editing = false }),
            )
            // Focus + raise the keyboard immediately so the user can type on the first tap.
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            // The title itself is the rename control: tap it to edit in place (FA-20). The tap
            // target is only the title text (not a full-width strip), so it doesn't swallow S Pen
            // strokes that start elsewhere in the band.
            Text(
                text = title,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = titleMaxWidth)
                    .clickable { editing = true },
            )
        }
        // FA-24 tag row, LEFT of the created date: takes ALL the leftover space between the
        // capped title and the date (weight), so the region is fixed for a given title/date —
        // an empty row can't shift the chrome, and the pills (right-anchored inside TagRow)
        // run all the way up to the title's limit before the faded scroll engages.
        if (onAddTag != null) {
            Spacer(Modifier.width(10.dp))
            TagRow(
                tags = tags,
                pendingRemovalTagIds = pendingRemovalTagIds,
                onBeginUntag = onBeginUntag,
                onCancelUntag = onCancelUntag,
                onAddTag = onAddTag,
                fadeColor = HeaderBandColor,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = if (dateLabel.isBlank()) "Saved" else "$dateLabel · Saved",
            style = MaterialTheme.typography.bodyMedium,
            color = Neutral500,
        )
    }
}

/**
 * The pinned note-tabs band (FA-20): the grey header band that holds the note tabs. It stays put
 * while the title block scrolls up behind it — so the tabs never scroll away.
 */
@Composable
fun NoteTabsBand(
    tabs: List<NotebookSummary>,
    currentNotebookId: String?,
    currentTitle: String,
    onSelectTab: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NoteTabPills(
        tabs = tabs,
        currentNotebookId = currentNotebookId,
        currentTitle = currentTitle,
        onSelectTab = onSelectTab,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(HeaderBandColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
    // Bookmark filter: when on, the grid shows only bookmarked pages (a quick-nav view). Reorder is
    // disabled while filtered (it's a full-notebook operation); tap still opens the page.
    var filterBookmarks by remember { mutableStateOf(false) }
    val hasBookmarks = pages.any { it.isBookmarked }
    // Effective filter: never strand the user on an empty grid if the last bookmark is removed.
    val bookmarkedOnly = filterBookmarks && hasBookmarks
    val visiblePages = if (bookmarkedOnly) pages.filter { it.isBookmarked } else pages
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // The pointer's position in grid space at drag start (the dragged card's parent origin + the
    // local touch point). Adding [dragOffset] to this tracks the finger/stylus through the grid —
    // we hit-test THAT, not the dragged tile's own bounds (which move with its graphicsLayer and so
    // would double-count the drag). This makes the drop follow the pen, and land where indicated.
    var dragStartPointer by remember { mutableStateOf(Offset.Zero) }
    // The insertion gap during a reorder drag: dropIndex == k means "between page k-1 and page k"
    // (k in 0..pages.size). Rendered as an accent line in the margin at that gap.
    var dropIndex by remember { mutableStateOf<Int?>(null) }
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
                    // Bookmarks filter — show only bookmarked pages (disabled when none exist).
                    Spacer(Modifier.width(4.dp))
                    val accent = LeapTheme.tokens.accent
                    IconButton(
                        onClick = { filterBookmarks = !filterBookmarks },
                        enabled = hasBookmarks,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            if (bookmarkedOnly) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (bookmarkedOnly) "Showing bookmarked pages" else "Filter to bookmarked pages",
                            tint = when {
                                !hasBookmarks -> Neutral400
                                bookmarkedOnly -> accent
                                else -> Neutral500
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
                    itemsIndexed(visiblePages, key = { _, p -> p.id }) { index, page ->
                        // True page number / neighbours come from the FULL list, so the filtered
                        // view still shows real numbers and reorder math stays whole-notebook.
                        val fullIndex = pages.indexOfFirst { it.id == page.id }
                        val dragModifier = Modifier.pointerInput(page.id, selectMode, bookmarkedOnly, pages.size) {
                            // No reorder while filtered to bookmarks (it's a full-notebook operation).
                            if (selectMode || bookmarkedOnly) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    draggingId = page.id
                                    dragOffset = Offset.Zero
                                    dropIndex = null
                                    // Snapshot the pen's grid-space position now (bounds are clean
                                    // before any drag translation), then track it via dragOffset.
                                    dragStartPointer = (cardBounds[page.id]?.topLeft ?: Offset.Zero) + offset
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta
                                    // The pen position in grid space — hit-test it against the other
                                    // cards to find the insertion gap (before/after the card it's over).
                                    val pointer = dragStartPointer + dragOffset
                                    val target = cardBounds.entries
                                        .firstOrNull { it.key != page.id && it.value.contains(pointer) }
                                    if (target != null) {
                                        val tIdx = pages.indexOfFirst { it.id == target.key }
                                        val before = pointer.x < target.value.center.x
                                        dropIndex = if (before) tIdx else tIdx + 1
                                    }
                                },
                                onDragEnd = {
                                    val from = draggingId
                                    val target = dropIndex
                                    if (from != null && target != null) {
                                        val ids = pages.map { it.id }.toMutableList()
                                        val fromIdx = ids.indexOf(from)
                                        if (fromIdx >= 0) {
                                            ids.removeAt(fromIdx)
                                            // Shift the gap left if it sat after the removed card.
                                            val insert = (if (fromIdx < target) target - 1 else target)
                                                .coerceIn(0, ids.size)
                                            ids.add(insert, from)
                                            if (ids != pages.map { it.id }) onReorder(ids)
                                        }
                                    }
                                    draggingId = null; dropIndex = null; dragOffset = Offset.Zero
                                },
                                onDragCancel = { draggingId = null; dropIndex = null; dragOffset = Offset.Zero },
                            )
                        }
                        // The insertion line shows on a card's leading edge when the gap is before it,
                        // and on the last card's trailing edge when the gap is at the very end. It's
                        // hidden for a no-op drop (the gap on either side of the dragged card itself).
                        val fromIdx = draggingId?.let { id -> pages.indexOfFirst { it.id == id } } ?: -1
                        val noOp = dropIndex == fromIdx || dropIndex == fromIdx + 1
                        val showDropBefore = draggingId != null && !noOp && dropIndex == fullIndex
                        val showDropAfter =
                            draggingId != null && !noOp && dropIndex == pages.size && fullIndex == pages.lastIndex
                        PageGridCard(
                            page = page,
                            number = fullIndex + 1,
                            isCurrent = page.id == currentPageId,
                            canDelete = pages.size > 1,
                            canMoveEarlier = fullIndex > 0 && !bookmarkedOnly,
                            canMoveLater = fullIndex < pages.lastIndex && !bookmarkedOnly,
                            noteListViewModel = noteListViewModel,
                            selectMode = selectMode,
                            selected = page.id in selectedIds,
                            isDragging = draggingId == page.id,
                            showDropBefore = showDropBefore,
                            showDropAfter = showDropAfter,
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
                    // "Add page" only in the full (unfiltered) view — not while filtered to bookmarks.
                    if (!selectMode && !bookmarkedOnly) item { AddPageTile(onClick = onAddPage) }
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
    showDropBefore: Boolean,
    showDropAfter: Boolean,
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
    val borderColor = if (isCurrent) accent else Neutral200
    val borderWidth = if (isCurrent) 2.dp else 1.dp
    // Outer Box is NOT clipped, so the reorder drop line can render into the inter-tile margin.
    Box(
        modifier = Modifier
            // Lift the dragged tile above its siblings: in a LazyVerticalGrid later items draw on
            // top, so without this the dragged page slides BEHIND the pages after it.
            .zIndex(if (isDragging) 1f else 0f)
            .onGloballyPositioned { onBoundsChanged(it.boundsInParent()) }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                if (isDragging) { scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 16f; alpha = 0.96f }
            },
    ) {
      Column(
        modifier = Modifier
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
      // Reorder drop indicator: an accent line in the inter-tile margin marking where the dragged
      // page will land (leading edge for a gap before this card; trailing edge for the very end).
      if (showDropBefore) DropLine(start = true, accent = accent)
      if (showDropAfter) DropLine(start = false, accent = accent)
    }
}

/** The reorder insertion line drawn in the inter-tile margin on a card's leading/trailing edge. */
@Composable
private fun BoxScope.DropLine(start: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
            .offset(x = if (start) (-7).dp else 7.dp)
            .width(3.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(accent),
    )
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
 *
 * FA-24 additions: a **search field** (filters notebooks by title or owning subject name — typing
 * swaps the tree for a flat result list), and a **link-picking mode** ([pickMode]) where choosing a
 * notebook calls [onPickNotebook] (creating/redefining an on-canvas link box) instead of navigating.
 */
@Composable
fun LibraryOverlay(
    subjectTree: List<SubjectNode>,
    notebooksBySubject: Map<String?, List<NotebookSummary>>,
    expandedIds: Set<String>,
    currentNotebookId: String?,
    onToggleSubject: (String) -> Unit,
    onLocateCurrent: () -> Unit,
    onOpenNote: (String) -> Unit,
    onDismiss: () -> Unit,
    pickMode: Boolean = false,
    onPickNotebook: ((NotebookSummary) -> Unit)? = null,
) {
    // Which notebook to flash as "you are here" — set when the locate button reveals the open notebook.
    var highlighted by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val unfiled = notebooksBySubject[null].orEmpty()
    val isEmpty = subjectTree.isEmpty() && notebooksBySubject.values.all { it.isEmpty() }
    // One click handler for every notebook row: pick mode creates/redefines a link, else navigate.
    val onOpenNotebook: (NotebookSummary) -> Unit = { notebook ->
        if (pickMode) onPickNotebook?.invoke(notebook) else onOpenNote(notebook.lastViewedPageId)
    }
    val searching = searchQuery.isNotBlank()
    val searchResults = if (searching) {
        QuickNavSearch.filterNotebooks(searchQuery, subjectTree, notebooksBySubject)
    } else {
        emptyList()
    }

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
                        if (pickMode) "Link a notebook" else "Quick Nav",
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
                QuickNavSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                )
                // "Subjects" header with a right-aligned "current note" locator (expands the path to,
                // and highlights, the open note). Hidden while searching (results are a flat list).
                if (!searching) {
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
                            onClick = { onLocateCurrent(); highlighted = currentNotebookId },
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
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    when {
                        searching -> {
                            if (searchResults.isEmpty()) {
                                Text(
                                    "No notebooks match \"${searchQuery.trim()}\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Neutral400,
                                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                                )
                            }
                            searchResults.forEach { notebook ->
                                QuickNavNotebook(
                                    notebook = notebook,
                                    depth = 0,
                                    highlighted = notebook.notebookId == highlighted,
                                    onOpenNotebook = onOpenNotebook,
                                )
                            }
                        }
                        else -> {
                            if (isEmpty) {
                                Text(
                                    "No notes yet. Create notes and subjects from the home library.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Neutral400,
                                    modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 8.dp),
                                )
                            }
                            // The subject tree, with each subject's notebooks nested inside it.
                            subjectTree.forEach { node ->
                                QuickNavSubject(
                                    node = node,
                                    notebooksBySubject = notebooksBySubject,
                                    expandedIds = expandedIds,
                                    highlightedNotebookId = highlighted,
                                    depth = 0,
                                    onToggle = onToggleSubject,
                                    onOpenNotebook = onOpenNotebook,
                                )
                            }
                            // Notebooks that aren't filed into any subject sit at the root, under "Unfiled".
                            if (unfiled.isNotEmpty()) {
                                SectionLabel("Unfiled")
                                unfiled.forEach { notebook ->
                                    QuickNavNotebook(
                                        notebook = notebook,
                                        depth = 0,
                                        highlighted = notebook.notebookId == highlighted,
                                        onOpenNotebook = onOpenNotebook,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact Quick Nav search box (FA-24) — filters notebooks by title or subject name. */
@Composable
private fun QuickNavSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Neutral100)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = Neutral500,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search notebooks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neutral400,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LeapGrey),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                painterResource(ElrondIcons.Close),
                contentDescription = "Clear search",
                tint = Neutral500,
                modifier = Modifier.size(16.dp).clickable { onQueryChange("") },
            )
        }
    }
}

/** One subject row in Quick Nav; when expanded it reveals its child subjects then its notebooks. */
@Composable
private fun QuickNavSubject(
    node: SubjectNode,
    notebooksBySubject: Map<String?, List<NotebookSummary>>,
    expandedIds: Set<String>,
    highlightedNotebookId: String?,
    depth: Int,
    onToggle: (String) -> Unit,
    onOpenNotebook: (NotebookSummary) -> Unit,
) {
    val subject = node.subject
    val notebooks = notebooksBySubject[subject.id].orEmpty()
    val expandable = node.children.isNotEmpty() || notebooks.isNotEmpty()
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
                notebooksBySubject = notebooksBySubject,
                expandedIds = expandedIds,
                highlightedNotebookId = highlightedNotebookId,
                depth = depth + 1,
                onToggle = onToggle,
                onOpenNotebook = onOpenNotebook,
            )
        }
        notebooks.forEach { notebook ->
            QuickNavNotebook(
                notebook = notebook,
                depth = depth + 1,
                highlighted = notebook.notebookId == highlightedNotebookId,
                onOpenNotebook = onOpenNotebook,
            )
        }
    }
}

/**
 * A notebook leaf in Quick Nav (FA-20). Shows the NOTEBOOK title (tracks renames) — never individual
 * pages — and tapping it opens the notebook at its last-viewed page.
 */
@Composable
private fun QuickNavNotebook(
    notebook: NotebookSummary,
    depth: Int,
    highlighted: Boolean,
    onOpenNotebook: (NotebookSummary) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlighted) LeapTheme.tokens.accentSoft else Color.Transparent)
            .clickable { onOpenNotebook(notebook) }
            // Align the notebook text with subject names one level shallower (chevron + dot ≈ 27dp).
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
            notebook.title,
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
