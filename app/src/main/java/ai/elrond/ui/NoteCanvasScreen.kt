package ai.elrond.ui

import android.content.res.Configuration
import ai.elrond.presentation.AiUiState
import ai.elrond.domain.CanvasTool
import ai.elrond.domain.LiveTransform
import ai.elrond.domain.PageNavigationMode
import ai.elrond.domain.PageViewOrientation
import ai.elrond.ui.theme.LeapTheme
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.PrefixTriggerState
import ai.elrond.domain.SuggestionType
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.SubjectViewModel
import ai.elrond.presentation.TagViewModel
import ai.elrond.presentation.TodoViewModel
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.loaders.OrganicLoader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** How long the unobtrusive page-count pill / orientation prompt stay before fading away (FA-20). */
private const val AUTO_FADE_MILLIS = 5_000L

/**
 * Visibility flag for an unobtrusive overlay that shows on a state change then fades after
 * [AUTO_FADE_MILLIS] (FA-20). Re-shows whenever any of [keys] change; stays hidden while [active] is
 * false (so a gated overlay — e.g. only on an orientation mismatch — never flashes). Used for both
 * the page-count pill and the rotation prompt.
 */
@Composable
private fun rememberAutoFadeVisible(vararg keys: Any?, active: Boolean = true): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active, *keys) {
        if (active) {
            visible = true
            delay(AUTO_FADE_MILLIS)
            visible = false
        } else {
            visible = false
        }
    }
    return visible
}

/** Note page screen: full-bleed ink canvas with a floating tool bar. */
@Composable
fun NoteCanvasScreen(
    pageId: String,
    onHome: () -> Unit,
    onOpenNote: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CanvasViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    noteListViewModel: NoteListViewModel = hiltViewModel(),
    subjectViewModel: SubjectViewModel = hiltViewModel(),
    tagViewModel: TagViewModel = hiltViewModel(),
) {
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val aiNotes by viewModel.aiNotes.collectAsStateWithLifecycle()
    // Notebook link boxes on this page (FA-24) — passive chips; tap opens the target notebook.
    val links by viewModel.links.collectAsStateWithLifecycle()
    // Unified selection (FA-21): strokes + AI boxes. Drives both the lasso chrome and which AI box
    // shows as selected (a box can be selected by a 1.5s press-and-hold or a lasso).
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    // Page → screen transform (FA-20): horizontal centring offset (margins in landscape) + vertical
    // scroll. The page is a fixed portrait sheet; this is the single source for placing it on screen.
    val pageTransform by viewModel.pageTransform.collectAsStateWithLifecycle()
    // Document → screen transform (FA-20): anchored to page 1, drives the continuous multi-page paper.
    val documentTransform by viewModel.documentTransform.collectAsStateWithLifecycle()
    val pageWidthSpacePx by viewModel.pageWidthSpacePx.collectAsStateWithLifecycle()
    // The notebook's pages (FA-20) — drives the page indicator; a horizontal finger swipe turns pages.
    val notebookPages by viewModel.notebookPages.collectAsStateWithLifecycle()
    // The current notebook (all notebookPages share it) — for the active editor-tab highlight.
    val currentNotebookId = notebookPages.firstOrNull()?.notebookId
    val pendingExtraction by viewModel.pendingExtraction.collectAsStateWithLifecycle()
    val todoCount by todoViewModel.activeCount.collectAsStateWithLifecycle()
    var showTodoPanel by remember { mutableStateOf(false) }
    val pendingSuggestions by viewModel.pendingSuggestions.collectAsStateWithLifecycle()
    val hasNewExtractedItems by settingsViewModel.hasNewExtractedItems.collectAsStateWithLifecycle()
    val transientMessage by viewModel.transientMessage.collectAsStateWithLifecycle()
    // Prefix `/Q` listening state — drives the bottom-of-canvas listening indicator.
    val prefixTriggerState by viewModel.prefixTriggerState.collectAsStateWithLifecycle()
    // The active-tool highlight style (A soft tile / B filled / C underline), from Settings.
    val toolTreatment by settingsViewModel.toolSelectedTreatment.collectAsStateWithLifecycle()
    var showMoreMenu by remember { mutableStateOf(false) }
    var showPageStyle by remember { mutableStateOf(false) }
    // FA-24 notebook links: the Add(+) menu, the target picker (Quick Nav in pick mode — creating
    // a new link, or redefining a broken one when redefineLinkId is set), the broken-link menu,
    // and the Backlinks dialog.
    var showAddMenu by remember { mutableStateOf(false) }
    var linkPickingMode by remember { mutableStateOf(false) }
    var redefineLinkId by remember { mutableStateOf<String?>(null) }
    var brokenLinkMenuTargetId by remember { mutableStateOf<String?>(null) }
    var showBacklinks by remember { mutableStateOf(false) }
    // FA-20: page style is per-notebook (paper / grid density / colour / orientation), resolved by
    // the CanvasViewModel (per-notebook override else the global default).
    val paperStyle by viewModel.paperStyle.collectAsStateWithLifecycle()
    val gridSpacing by viewModel.gridSpacing.collectAsStateWithLifecycle()
    val paperColor by viewModel.paperColor.collectAsStateWithLifecycle()
    val viewOrientation by viewModel.viewOrientation.collectAsStateWithLifecycle()
    val pageNavigationMode by viewModel.pageNavigationMode.collectAsStateWithLifecycle()
    val penIconStyle by settingsViewModel.penIconStyle.collectAsStateWithLifecycle()
    // FA-23 tool configuration (colour / line type / tip width), edited via the per-tool dropdown.
    val penColor by viewModel.penColor.collectAsStateWithLifecycle()
    val penLineType by viewModel.penLineType.collectAsStateWithLifecycle()
    val highlighterColor by viewModel.highlighterColor.collectAsStateWithLifecycle()
    val highlighterWidth by viewModel.highlighterWidth.collectAsStateWithLifecycle()
    val pencilLineType by viewModel.pencilLineType.collectAsStateWithLifecycle()
    val pencilLead by viewModel.pencilLead.collectAsStateWithLifecycle()
    var showPenMenu by remember { mutableStateOf(false) }
    var showHighlighterMenu by remember { mutableStateOf(false) }
    var showPencilMenu by remember { mutableStateOf(false) }
    val pageTitle by viewModel.pageTitle.collectAsStateWithLifecycle()
    val pageDateLabel by viewModel.pageDateLabel.collectAsStateWithLifecycle()
    // Notebook tags in the header band (FA-24). The tag data flows through the shared
    // TagRepository, so the Library picker and this row can never drift.
    val headerNotebookId by viewModel.notebookIdFlow.collectAsStateWithLifecycle()
    val headerTags by remember(headerNotebookId) {
        headerNotebookId?.let { tagViewModel.tagsFor(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingRemovalTagIds by remember(headerNotebookId) {
        headerNotebookId?.let { tagViewModel.pendingRemovalTagIdsFor(it) } ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val allTags by tagViewModel.tags.collectAsStateWithLifecycle()
    var tagPickerOpen by remember { mutableStateOf(false) }
    // Quick Nav lists NOTEBOOKS (one per notebook, titled by the notebook name so renames show), never
    // individual pages (FA-20).
    val libraryNotebooks by noteListViewModel.notebooks.collectAsStateWithLifecycle()
    // The editor tabs show notebooks opened in the current foreground session (FA-20).
    val sessionNotebooks by noteListViewModel.sessionNotebooks.collectAsStateWithLifecycle()
    // Quick Nav (FA-16): the read-only subject tree with each subject's notebooks nested inside it.
    val subjectTree by subjectViewModel.tree.collectAsStateWithLifecycle()
    val subjectExpandedIds by subjectViewModel.expandedIds.collectAsStateWithLifecycle()
    val noteSubjects by subjectViewModel.noteSubjects.collectAsStateWithLifecycle()
    // subjectId → its notebooks (null key = unfiled), so the Quick Nav tree nests notebooks under subjects.
    val notebooksBySubject = remember(libraryNotebooks, noteSubjects) {
        libraryNotebooks.groupBy { noteSubjects[it.notebookId] }
    }
    var showPages by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    // Clears the inline title editor's focus (commits it) when the canvas is touched (FA-20).
    val focusManager = LocalFocusManager.current

    // S Pen side-button gestures (FA-19). One shared tracker, fed by both the InkCanvas (while
    // drawing) and an always-present top-level observer below — so the button keeps working in
    // every tool mode, including Lasso where the selection overlay otherwise owns input.
    val stylusButtonTracker = remember(viewModel) { StylusButtonTracker(viewModel) }
    DisposableEffect(stylusButtonTracker) { onDispose { stylusButtonTracker.dispose() } }

    // When the "edit mode on creation" setting is on (default) a freshly created /Q answer starts
    // selected; loaded notes never do. Selection itself now lives in the ViewModel (FA-21), so the
    // box uses the shared lasso chrome and can be selected alongside strokes.
    val selectOnCreate by settingsViewModel.aiNoteSelectedOnCreate.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.createdNoteEvents.collect { id ->
            if (selectOnCreate) viewModel.selectAiNote(id)
        }
    }
    // FA-20: a horizontal page-turn swipe emits the target page id — navigate to it (the editor
    // re-opens on that page; a fresh page when swiping past the last with content on the current one).
    LaunchedEffect(viewModel) {
        viewModel.pageTurnEvents.collect { id -> onOpenNote(id) }
    }
    // FA-24: tapping a healthy link box opens the target notebook's page. A dedicated event — NOT
    // pageTurnEvents, which is intra-notebook page navigation.
    LaunchedEffect(viewModel) {
        viewModel.openLinkEvents.collect { id -> if (id != pageId) onOpenNote(id) }
    }
    // FA-24: press-and-hold on a broken link surfaces its Redefine/Delete menu.
    LaunchedEffect(viewModel) {
        viewModel.brokenLinkMenuEvents.collect { id -> brokenLinkMenuTargetId = id }
    }

    // Pinch-zoom indicator (FA-20): a pill on the left showing the zoom %, accent-styled when on a
    // snap target (100% / fit-width). It appears on each zoom change and fades 5s after the last one.
    val zoomSnapped by viewModel.zoomSnapped.collectAsStateWithLifecycle()
    var zoomPillPercent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.zoomEvents.collectLatest { scale ->
            zoomPillPercent = (scale * 100).roundToInt()
            delay(AUTO_FADE_MILLIS)
            zoomPillPercent = null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewModel.setCanvasSize(it.width.toFloat(), it.height.toFloat()) },
    ) {
        // The floating toolbar renders at a constant 0.78× (the handoff's portrait scale) in BOTH
        // orientations — landscape no longer enlarges it, so the icons stay the same size on rotation.
        val toolbarScale = 0.78f

        // The canvas is full-bleed (the app runs edge-to-edge), but the floating chrome must reference
        // the system bars so it sits a CONSTANT distance from the notification bar / screen edge in
        // BOTH orientations. Previously the offsets were hardcoded (14/28 top, 16/48 side) and ignored
        // insets, so the toolbar tucked under the status bar in portrait and floated low in landscape,
        // and the side gap drifted between rotations.
        //   topGap       = a constant gap below the status bar (notification bar)
        //   leftPad/rightPad = a constant "side spacing" from the safe (cutout/nav-aware) screen edge
        // If these need tuning on device, adjust the +8.dp ("top gap") / +24.dp ("side spacing") below.
        val layoutDir = LocalLayoutDirection.current
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val sideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues()
        val leftInset = sideInsets.calculateLeftPadding(layoutDir)
        val rightInset = sideInsets.calculateRightPadding(layoutDir)
        val topGap = statusTop + 0.dp
        val leftPad = leftInset + 14.dp
        val rightPad = rightInset + 14.dp
        // Note tabs live in the grey header band, just above the title (the old Attached-in-toolbar
        // mode + its setting were removed pending a redesign). The grey band sits below the toolbar;
        // its offset is derived from topGap so the toolbar→title spacing stays constant.
        val headerTop = topGap + 62.dp * toolbarScale + 6.dp

        // FA-20: the page starts BELOW the header. The note TABS are a PINNED band (they never scroll
        // away); only the TITLE block scrolls up — and it slides up BEHIND the pinned tabs band. We
        // measure both band heights, tell the VM where the page top sits (so its transform docks the
        // page below the whole header), and slide just the title block up by the scroll amount.
        val density = LocalDensity.current
        var tabsBandHeightPx by remember { mutableStateOf(0) }
        var titleBandHeightPx by remember { mutableStateOf(0) }
        val tabsTopPx = with(density) { headerTop.toPx() }
        val titleGapPx = with(density) { 4.dp.toPx() }
        val pageTopGapPx = with(density) { 8.dp.toPx() }
        // Title block's rest position: just below the pinned tabs band.
        val titleTopPx = tabsTopPx + tabsBandHeightPx + titleGapPx
        val pageTopInsetPx = titleTopPx + titleBandHeightPx + pageTopGapPx
        LaunchedEffect(pageTopInsetPx) { viewModel.setPageTopInset(pageTopInsetPx) }
        // The document scroll (pageTopInset − document origin) drives the title slide, independent of
        // which page is open (FA-20).
        val headerScrollPx = (pageTopInsetPx - documentTransform.offsetY).coerceAtLeast(0f)
        val sideStart = leftInset + 14.dp
        val sideEnd = rightInset + 14.dp

        // Paper background (Ruled / Plain / Dots) behind the transparent ink layers. The page is a
        // fixed portrait sheet centred on screen (margins in landscape) and scrolled — all from the
        // transform — so the paper sits exactly under the page-mapped ink.
        PaperBackground(
            paper = paperStyle,
            transform = documentTransform,
            gridSpacing = gridSpacing,
            paperColor = paperColor,
            landscape = viewOrientation == PageViewOrientation.LANDSCAPE,
            pageWidthSpacePx = pageWidthSpacePx,
            modifier = Modifier.fillMaxSize(),
        )

        // AI answers as handwriting-style ink, inline at their trigger position — rendered BELOW the
        // ink canvas so handwritten strokes always sit in FRONT of the AI text (FA-21). They're
        // passive (no pointer input); the pen hits the ink layer above and draws over them, and the
        // selection box/handles/toolbar render on top via SelectionDecorations. Error/clarify notes
        // are NOT placed here — they render as a centred pop-up below (always on-screen).
        aiNotes.forEach { note ->
            if (!note.isError) {
                key(note.id) {
                    val live = selection?.takeIf { note.id in it.aiNoteIds }?.transform
                    AiInkNoteView(
                        note = note,
                        transform = pageTransform,
                        liveTransform = live ?: LiveTransform.IDENTITY,
                        onMeasured = { w, h -> viewModel.reportAiNoteMeasuredSize(note.id, w, h) },
                    )
                }
            }
        }

        // Notebook link boxes (FA-24) — rendered AFTER the AI notes (so links sit visually on top;
        // CanvasViewModel.linkAt is hit-tested before aiNoteAt to match) and, like them, BELOW the
        // ink canvas: passive chips the pen writes over. Tap-to-open and hold-to-select are handled
        // by InkCanvas → the ViewModel. Labels track the target's CURRENT title (device feedback);
        // the stored linkText is only the fallback cache.
        val linkTitleByNotebook = remember(libraryNotebooks) {
            libraryNotebooks.associate { it.notebookId to it.title }
        }
        links.forEach { link ->
            key(link.id) {
                val live = selection?.takeIf { link.id in it.linkIds }?.transform
                NotebookLinkView(
                    link = link,
                    transform = pageTransform,
                    liveTransform = live ?: LiveTransform.IDENTITY,
                    liveTitle = link.targetNotebookId?.let { linkTitleByNotebook[it] },
                    onMeasured = { w, h -> viewModel.reportLinkMeasuredSize(link.id, w, h) },
                )
            }
        }

        InkCanvas(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            stylusButtonTracker = stylusButtonTracker,
            // Tapping/drawing on the canvas commits + closes the inline title editor (FA-20).
            onInteract = { focusManager.clearFocus() },
        )

        // Live dashed/dotted stroke while the pen is down (FA-23) — the wet ink layer can't render
        // patterns, so InkCanvas buffers the points and this overlay paints them in the line style.
        LivePatternStrokeOverlay(viewModel = viewModel, modifier = Modifier.fillMaxSize())

        // On-canvas AI activity: loading dots while thinking, red ink on failure.
        when (val state = aiState) {
            is AiUiState.Thinking -> AiLoadingIndicator(
                x = pageTransform.pageToScreenX(state.x),
                y = pageTransform.pageToScreenY(state.y),
            )
            is AiUiState.Error -> AiErrorInk(
                message = state.message,
                x = pageTransform.pageToScreenX(state.x),
                y = pageTransform.pageToScreenY(state.y),
                containerWidthPx = constraints.maxWidth.toFloat(),
                onDismiss = viewModel::dismissAiResponse,
            )
            AiUiState.Idle -> Unit
        }

        // Lasso selection tool: a full-screen overlay that owns input while the tool is active
        // (drawn above ink + AI notes, below the toolbars so they stay tappable).
        if (tool == CanvasTool.LASSO) {
            SelectionLayer(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        // Selection chrome (box + handles + toolbar) for ANY selection, in any tool — an AI box can
        // be selected by a 1.5s press-and-hold while in pen mode (FA-21). Sits above the lasso
        // catcher so its handles stay tappable, and owns no full-screen input so the pen still writes.
        SelectionDecorations(viewModel = viewModel, modifier = Modifier.fillMaxSize())

        // Always-present S Pen button observer (FA-19). A transparent top View that watches the
        // side button via generic-motion events (which carry the real BUTTON_STYLUS_PRIMARY bits and
        // fire while the pen hovers) and feeds the shared tracker — so the button works in every tool
        // mode, even when the Lasso overlay owns touch. It only handles GENERIC motion (returns
        // false) and never the touch stream, so drawing / lasso / toolbar input pass straight through.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                View(ctx).apply {
                    setOnGenericMotionListener { _, event ->
                        stylusButtonTracker.onMotionEvent(event)
                        false // observe only — let the event continue to the layers below
                    }
                }
            },
        )

        // Note title + created date in a grey band. Composed BEFORE the pinned tabs band (next) and
        // the toolbar so it slides up BEHIND them as you scroll (FA-20). Its rest position is just
        // below the tabs band; scrolling slides only this title block away.
        EditorHeader(
            title = pageTitle,
            dateLabel = pageDateLabel,
            onRename = viewModel::renamePage,
            tags = headerTags,
            pendingRemovalTagIds = pendingRemovalTagIds,
            onBeginUntag = { tag ->
                headerNotebookId?.let { tagViewModel.beginUntag(it, tag.id) }
            },
            onCancelUntag = { tag ->
                headerNotebookId?.let { tagViewModel.cancelUntag(it, tag.id) }
            },
            onAddTag = if (headerNotebookId != null) {
                {
                    tagViewModel.pruneOrphans() // the menu must never list an orphaned tag
                    tagPickerOpen = true
                }
            } else {
                null
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset { IntOffset(0, (titleTopPx - headerScrollPx).roundToInt()) }
                .onSizeChanged { titleBandHeightPx = it.height }
                .padding(start = sideStart, end = sideEnd),
        )
        // FA-24: the shared tag picker (same dialog + repository methods as the Library ⋮ menu).
        val pickerNotebookId = headerNotebookId
        if (tagPickerOpen && pickerNotebookId != null) {
            TagPickerDialog(
                allTags = allTags,
                assignedTagIds = headerTags.map { it.id }.toSet(),
                onToggle = { tag ->
                    if (tag.id in headerTags.map { it.id }.toSet()) {
                        tagViewModel.removeTag(pickerNotebookId, tag.id)
                    } else {
                        tagViewModel.assignTag(pickerNotebookId, tag.id)
                    }
                },
                onCreateAndAssign = { tagViewModel.createAndAssignTag(pickerNotebookId, it) },
                onDismiss = { tagPickerOpen = false },
            )
        }

        // Pinned note-tabs band: composed AFTER the title block (opaque, so it occludes the title as
        // it slides up behind it) and never takes a scroll offset — so the tabs stay put (FA-20).
        NoteTabsBand(
            tabs = sessionNotebooks,
            currentNotebookId = currentNotebookId,
            currentTitle = pageTitle,
            onSelectTab = { id -> if (id != pageId) onOpenNote(id) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset { IntOffset(0, tabsTopPx.roundToInt()) }
                .onSizeChanged { tabsBandHeightPx = it.height }
                .padding(start = sideStart, end = sideEnd),
        )

        // ── Leap note toolbar (Claude Design "Note Tool Icons" handoff) ──────────────────────
        // Left pod: exit the page, plus Pages + Library overlays (FA-14).
        LeapToolbarContainer(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = leftPad, top = topGap)
                .graphicsLayer {
                    scaleX = toolbarScale; scaleY = toolbarScale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            ToolbarButton(
                painter = painterResource(ElrondIcons.Close),
                contentDescription = "Home",
                onClick = onHome,
            )
            ToolbarDivider()
            ToolbarButton(
                painter = painterResource(ElrondIcons.Pages),
                contentDescription = "Pages",
                onClick = { showPages = true },
                selected = showPages,
                treatment = toolTreatment,
            )
            ToolbarButton(
                painter = painterResource(ElrondIcons.Folder),
                contentDescription = "Library",
                onClick = { showLibrary = true },
                selected = showLibrary,
                treatment = toolTreatment,
            )
        }

        // Centre pod: drawing tools + undo/redo. The active tool uses the user's chosen highlight
        // style (A soft tile / B filled / C underline). Undo/Redo are actions — greyed when unavailable.
        val centreTools: @Composable RowScope.() -> Unit = {
            // FA-23: tapping the ALREADY-selected pen/highlighter/pencil opens its config dropdown
            // (colour / line type / tip width) anchored below the button; a first tap just selects.
            Box {
                ToolbarButton(
                    painter = painterResource(
                        ElrondIcons.penToolIcon(ElrondIcons.Pen, ElrondIcons.PenTip, penIconStyle),
                    ),
                    contentDescription = "Pen",
                    onClick = {
                        if (tool == CanvasTool.PEN) showPenMenu = true
                        else viewModel.selectTool(CanvasTool.PEN)
                    },
                    selected = tool == CanvasTool.PEN,
                    treatment = toolTreatment,
                )
                DropdownMenu(
                    expanded = showPenMenu,
                    onDismissRequest = { showPenMenu = false },
                    // Non-focusable so a pen DOWN outside still dismisses the menu but is NOT
                    // consumed — it reaches the canvas and draws. A focusable (touch-modal) popup
                    // silently ate the first stroke after a config change (device bug, 2026-07-07).
                    properties = ToolConfigMenuProperties,
                ) {
                    PenConfigMenu(
                        selectedColor = penColor,
                        onColor = viewModel::setPenColor,
                        selectedLineType = penLineType,
                        onLineType = viewModel::setPenLineType,
                    )
                }
            }
            Box {
                ToolbarButton(
                    painter = painterResource(
                        ElrondIcons.penToolIcon(
                            ElrondIcons.Highlighter,
                            ElrondIcons.HighlighterTip,
                            penIconStyle,
                        ),
                    ),
                    contentDescription = "Highlighter",
                    onClick = {
                        if (tool == CanvasTool.HIGHLIGHTER) showHighlighterMenu = true
                        else viewModel.selectTool(CanvasTool.HIGHLIGHTER)
                    },
                    selected = tool == CanvasTool.HIGHLIGHTER,
                    treatment = toolTreatment,
                )
                DropdownMenu(
                    expanded = showHighlighterMenu,
                    onDismissRequest = { showHighlighterMenu = false },
                    properties = ToolConfigMenuProperties,
                ) {
                    HighlighterConfigMenu(
                        selectedColor = highlighterColor,
                        onColor = viewModel::setHighlighterColor,
                        selectedWidth = highlighterWidth,
                        onWidth = viewModel::setHighlighterWidth,
                    )
                }
            }
            Box {
                ToolbarButton(
                    painter = painterResource(
                        ElrondIcons.penToolIcon(
                            ElrondIcons.PencilMech,
                            ElrondIcons.PencilMechTip,
                            penIconStyle,
                        ),
                    ),
                    contentDescription = "Pencil",
                    onClick = {
                        if (tool == CanvasTool.PENCIL) showPencilMenu = true
                        else viewModel.selectTool(CanvasTool.PENCIL)
                    },
                    selected = tool == CanvasTool.PENCIL,
                    treatment = toolTreatment,
                )
                DropdownMenu(
                    expanded = showPencilMenu,
                    onDismissRequest = { showPencilMenu = false },
                    properties = ToolConfigMenuProperties,
                ) {
                    PencilConfigMenu(
                        selectedLead = pencilLead,
                        onLead = viewModel::setPencilLead,
                        selectedLineType = pencilLineType,
                        onLineType = viewModel::setPencilLineType,
                    )
                }
            }
            ToolbarButton(
                painter = painterResource(ElrondIcons.Eraser),
                contentDescription = "Eraser",
                onClick = { viewModel.selectTool(CanvasTool.ERASER) },
                selected = tool == CanvasTool.ERASER,
                treatment = toolTreatment,
            )
            ToolbarButton(
                painter = painterResource(ElrondIcons.Text),
                contentDescription = "Text (coming soon)",
                onClick = {},
            )
            ToolbarButton(
                painter = painterResource(ElrondIcons.Lasso),
                contentDescription = "Lasso select",
                onClick = { viewModel.selectTool(CanvasTool.LASSO) },
                selected = tool == CanvasTool.LASSO,
                treatment = toolTreatment,
            )
            ToolbarDivider()
            ToolbarButton(
                painter = painterResource(ElrondIcons.Undo),
                contentDescription = "Undo",
                onClick = viewModel::undo,
                enabled = canUndo,
            )
            ToolbarButton(
                painter = painterResource(ElrondIcons.Redo),
                contentDescription = "Redo",
                onClick = viewModel::redo,
                enabled = canRedo,
            )
            // Add(+): a general "add-in" menu (FA-24) — Link notebook is live; Add page reserved.
            Box {
                ToolbarButton(
                    painter = painterResource(ElrondIcons.Add),
                    contentDescription = "Add",
                    onClick = { showAddMenu = true },
                )
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Link notebook") },
                        onClick = {
                            showAddMenu = false
                            redefineLinkId = null
                            linkPickingMode = true
                            showLibrary = true
                        },
                    )
                    // Reserved menu slot (mirrors the Import/Record placeholders) — no backend yet.
                    DropdownMenuItem(
                        text = { Text("Add page") },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
            ToolbarButton(
                painter = painterResource(ElrondIcons.Record),
                contentDescription = "Record (coming soon)",
                onClick = {},
            )
            ToolbarButton(
                painter = painterResource(ElrondIcons.Hand),
                contentDescription = "Finger drawing",
                onClick = { viewModel.setStylusOnly(!stylusOnly) },
                selected = !stylusOnly,
                treatment = toolTreatment,
            )
        }
        val centreModifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = topGap)
            .graphicsLayer {
                scaleX = toolbarScale; scaleY = toolbarScale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        // Plain centre toolbar — the note tabs live in the grey header band (see EditorHeader).
        LeapToolbarContainer(modifier = centreModifier) { centreTools() }

        // Right pod: the to-do list (with its outstanding-count badge) + a More menu (Clear page).
        LeapToolbarContainer(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = rightPad, top = topGap)
                .graphicsLayer {
                    scaleX = toolbarScale; scaleY = toolbarScale
                    transformOrigin = TransformOrigin(1f, 0f)
                },
        ) {
            val todoBadge: (@Composable () -> Unit)? = when {
                hasNewExtractedItems -> {
                    { Badge { Text("+") } }
                }
                todoCount > 0 -> {
                    { Badge { Text(todoCount.toString()) } }
                }
                else -> null
            }
            ToolbarButton(
                painter = painterResource(ElrondIcons.Checklist),
                contentDescription = "To-do list",
                onClick = {
                    showTodoPanel = true
                    // Opening the panel clears the "new items" flair.
                    settingsViewModel.markExtractedItemsSeen()
                },
                badge = todoBadge,
            )
            Box {
                ToolbarButton(
                    painter = painterResource(ElrondIcons.MoreVert),
                    contentDescription = "More actions",
                    onClick = { showMoreMenu = true },
                )
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear page") },
                        onClick = {
                            showMoreMenu = false
                            viewModel.clearPage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Page style") },
                        onClick = {
                            showMoreMenu = false
                            showPageStyle = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Backlinks") },
                        onClick = {
                            showMoreMenu = false
                            showBacklinks = true
                        },
                    )
                    // Handoff menu items not yet wired to a backend — shown disabled.
                    DropdownMenuItem(
                        text = { Text("Export") },
                        enabled = false,
                        onClick = {},
                    )
                    DropdownMenuItem(
                        text = { Text("Favourite") },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }

        if (showPages) {
            PagesOverlay(
                pages = notebookPages,
                currentPageId = pageId,
                noteListViewModel = noteListViewModel,
                onOpenPage = { id -> showPages = false; if (id != pageId) onOpenNote(id) },
                onAddPage = { showPages = false; viewModel.addPageAndOpen() },
                onDeletePage = viewModel::deletePageFromNotebook,
                onToggleBookmark = viewModel::setPageBookmark,
                onMovePage = viewModel::movePage,
                onReorder = viewModel::reorderPages,
                onMultiDelete = viewModel::deletePagesFromNotebook,
                onDismiss = { showPages = false },
            )
        }
        if (showPageStyle) {
            PageStyleDialog(
                paperStyle = paperStyle,
                gridSpacing = gridSpacing,
                paperColor = paperColor,
                viewOrientation = viewOrientation,
                pageNavigationMode = pageNavigationMode,
                onPaperStyle = viewModel::setPaperStyle,
                onGridSpacing = viewModel::setGridSpacing,
                onPaperColor = viewModel::setPaperColor,
                onViewOrientation = viewModel::setViewOrientation,
                onPageNavigationMode = viewModel::setPageNavigationMode,
                onDismiss = { showPageStyle = false },
            )
        }
        if (showLibrary) {
            LibraryOverlay(
                subjectTree = subjectTree,
                notebooksBySubject = notebooksBySubject,
                expandedIds = subjectExpandedIds,
                currentNotebookId = currentNotebookId,
                onToggleSubject = subjectViewModel::toggleExpanded,
                onLocateCurrent = {
                    currentNotebookId?.let { nb -> noteSubjects[nb] }?.let { subjectViewModel.expandToSubject(it) }
                },
                onOpenNote = { id ->
                    showLibrary = false
                    if (id != pageId) onOpenNote(id)
                },
                onDismiss = {
                    showLibrary = false
                    linkPickingMode = false
                    redefineLinkId = null
                },
                // FA-24 link picking: selecting a notebook creates (or redefines) a link box
                // instead of navigating.
                pickMode = linkPickingMode,
                onPickNotebook = { summary ->
                    showLibrary = false
                    linkPickingMode = false
                    val redefine = redefineLinkId
                    redefineLinkId = null
                    if (redefine != null) viewModel.redefineLink(redefine, summary)
                    else viewModel.createLink(summary)
                },
            )
        }
        // FA-24: a held broken link offers exactly Redefine (re-run the target picker) and Delete.
        // A screen-centred dialog by design — never anchored to a canvas point, so it can't render
        // off-screen (the FA-7 precedent).
        brokenLinkMenuTargetId?.let { linkId ->
            AlertDialog(
                onDismissRequest = { brokenLinkMenuTargetId = null },
                title = { Text("Reference not found") },
                text = { Text("The notebook this link pointed to was deleted.") },
                confirmButton = {
                    TextButton(onClick = {
                        brokenLinkMenuTargetId = null
                        redefineLinkId = linkId
                        linkPickingMode = true
                        showLibrary = true
                    }) { Text("Redefine") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.deleteLink(linkId)
                        brokenLinkMenuTargetId = null
                    }) { Text("Delete") }
                },
            )
        }
        if (showBacklinks) {
            BacklinksDialog(
                backlinksFlow = viewModel.observeBacklinks(),
                onOpenNote = { id ->
                    showBacklinks = false
                    if (id != pageId) onOpenNote(id)
                },
                onDismiss = { showBacklinks = false },
            )
        }

        if (showTodoPanel) {
            TodoPanel(
                viewModel = todoViewModel,
                onDismiss = { showTodoPanel = false },
                onOpenSource = { sourceId ->
                    showTodoPanel = false
                    if (sourceId != pageId) onOpenNote(sourceId)
                },
            )
        }

        pendingExtraction?.let { pending ->
            TaskExtractionSheet(
                tasks = pending.tasks,
                onConfirm = viewModel::confirmExtraction,
                onDismiss = viewModel::dismissExtraction,
            )
        }

        // FA-2 background-extracted items: one collated sheet for all of them (handle each).
        if (pendingSuggestions.isNotEmpty()) {
            SuggestionExtractionSheet(
                suggestions = pendingSuggestions,
                onResolve = viewModel::resolveSuggestions,
            )
        }

        // Short, self-clearing notification (e.g. "Already on your to-do list"). The ViewModel
        // owns the timeout, so the UI just shows it while present.
        transientMessage?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // Prefix `/Q` listening indicator: rises from the bottom while the canvas listens for the
        // question (loader + ✕ cancel). When the inactivity timer fires it slides out and the
        // note-position thinking loader (above, driven by aiState) takes over — the loader appears
        // to move up to where the answer will land.
        PrefixListeningIndicator(
            state = prefixTriggerState,
            onCancel = viewModel::cancelPrefixTrigger,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )

        // Page indicator (FA-20): "Page n / total" when the notebook has more than one page. A
        // horizontal finger swipe turns pages; swiping past the last (with content) adds a new one.
        // It re-shows on each page change then fades away after 5s so it stays unobtrusive.
        if (notebookPages.size > 1) {
            val pageIdx = notebookPages.indexOfFirst { it.id == pageId }
            AnimatedVisibility(
                visible = rememberAutoFadeVisible(pageId, notebookPages.size),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    shadowElevation = 2.dp,
                ) {
                    Text(
                        text = "Page ${if (pageIdx >= 0) pageIdx + 1 else 1} / ${notebookPages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Zoom indicator pill (FA-20): left-edge, shows the current zoom %, fades after 5s. When the
        // zoom rests on a snap target (100% / fit-width) it gets an accent border + accent text — the
        // visual cue that you've snapped.
        zoomPillPercent?.let { percent ->
            val accent = LeapTheme.tokens.accent
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = leftPad),
                shape = MaterialTheme.shapes.large,
                color = if (zoomSnapped) LeapTheme.tokens.accentSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                border = if (zoomSnapped) BorderStroke(1.5.dp, accent) else null,
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (zoomSnapped) LeapTheme.tokens.accentStrong else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        // Orientation prompt (FA-20): when the device is rotated so its orientation no longer matches
        // the notebook's page orientation, an unobtrusive corner button offers to switch the whole
        // notebook's pages to match (the page sheet's aspect swaps; strokes/toolbar stay upright).
        val deviceLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val deviceOrientation =
            if (deviceLandscape) PageViewOrientation.LANDSCAPE else PageViewOrientation.PORTRAIT
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Show the prompt on a fresh orientation mismatch, then fade it away after 5s so it stays
        // unobtrusive (it re-appears if the device is rotated into a new mismatch).
        val orientationMismatch = deviceOrientation != viewOrientation
        AnimatedVisibility(
            visible = rememberAutoFadeVisible(deviceOrientation, active = orientationMismatch),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = rightPad, bottom = bottomInset + 16.dp),
        ) {
            Surface(
                onClick = { viewModel.setViewOrientation(deviceOrientation) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LeapTheme.tokens.toolbarBorder),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = LeapTheme.tokens.accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (deviceLandscape) "Landscape page" else "Portrait page",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // Unclear-request pop-up: rendered last (top-most) and CENTRED on screen, so its
        // Yes/No / Edit-prompt / Okay controls are always reachable even when the /Q was near a
        // page edge. The answer it produces (on Yes / Re-send) lands inline at the trigger.
        aiNotes.lastOrNull { it.isError }?.let { note ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Scrim that consumes taps so the user can't draw on the canvas behind a modal
                    // clarify prompt (the note has its own Okay / Edit controls).
                    .background(Color(0x33262626))
                    .pointerInput(Unit) { detectTapGestures {} }
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                AiErrorNoteView(
                    note = note,
                    onResend = { edited -> viewModel.resendQuery(note.id, edited) },
                    onOkay = { viewModel.removeAiNote(note.id) },
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Confirmation bottom sheet for AI-extracted tasks, with a per-item toggle so the
 * user adds only the ones they want. Dismissing adds nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskExtractionSheet(
    tasks: List<String>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val checked = remember(tasks) {
        mutableStateListOf<Boolean>().apply { repeat(tasks.size) { add(true) } }
    }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "AI found these action items — add to your to-do list?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            tasks.forEachIndexed { index, task ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked[index],
                        onCheckedChange = { checked[index] = it },
                    )
                    Text(
                        text = task,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                FilledTonalButton(
                    onClick = {
                        onConfirm(checked.indices.filter { checked[it] }.toSet())
                    },
                    enabled = checked.any { it },
                ) {
                    Text("Add selected")
                }
            }
        }
    }
}

/**
 * Bottom-of-canvas indicator for prefix `/Q` listening: the user's organic loader plus a ✕ to
 * cancel, sliding up from the bottom edge. Shown only while [PrefixTriggerState.Listening] — when
 * the query starts processing it slides out and the note-position [AiLoadingIndicator] takes over.
 */
@Composable
private fun PrefixListeningIndicator(
    state: PrefixTriggerState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state is PrefixTriggerState.Listening,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrganicLoader(
                style = LocalAiLoaderStyle.current,
                colorMode = LocalAiColorMode.current,
                size = 48.dp,
            )
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
    }
}

/**
 * The on-canvas "thinking" indicator placed where the answer will land — the user's chosen organic
 * loader (FA-17), styled by [LocalAiLoaderStyle] / [LocalAiColorMode]. Replaces the old ink dots.
 */
@Composable
private fun AiLoadingIndicator(x: Float, y: Float) {
    val density = LocalDensity.current
    OrganicLoader(
        style = LocalAiLoaderStyle.current,
        colorMode = LocalAiColorMode.current,
        size = 56.dp,
        modifier = Modifier.absoluteOffset(
            x = with(density) { x.toDp() },
            y = with(density) { y.toDp() },
        ),
    )
}

/** Inline failure message in red handwriting style, on the canvas; tap to dismiss. */
@Composable
private fun AiErrorInk(
    message: String,
    x: Float,
    y: Float,
    containerWidthPx: Float,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    // The longer billing/auth messages must wrap AND stay on-screen (the on-canvas popup rule):
    // cap the width, then clamp the measured box inside the right edge — a /Q near the edge
    // otherwise pushed the text off-screen.
    var measuredWidthPx by remember { mutableStateOf(0) }
    Text(
        text = message,
        fontFamily = HandwritingFontFamily,
        fontSize = 24.sp,
        color = ErrorInkColor,
        modifier = Modifier
            .absoluteOffset {
                val margin = 8.dp.toPx()
                val maxX = (containerWidthPx - measuredWidthPx - margin).coerceAtLeast(margin)
                IntOffset(x.coerceIn(margin, maxX).roundToInt(), y.roundToInt())
            }
            .widthIn(max = ERROR_INK_MAX_WIDTH)
            .onSizeChanged { measuredWidthPx = it.width }
            .clickable(onClick = onDismiss)
            .padding(4.dp),
    )
}

/** Wrap width for the on-canvas red-ink error, so long billing/auth messages stay readable. */
private val ERROR_INK_MAX_WIDTH = 380.dp

/**
 * One collated confirmation sheet for all background-extracted items (FA-2). Each item has a
 * checkbox (on by default) so the user handles every detected to-do/event in a single place:
 * "Add selected" commits the checked items and dismisses the unchecked ones; "Dismiss" (or
 * swiping the sheet away) dismisses them all. Either way every row is marked handled, so the
 * same items never re-surface on a later save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionExtractionSheet(
    suggestions: List<PendingSuggestion>,
    onResolve: (acceptIds: List<String>, dismissIds: List<String>) -> Unit,
) {
    val checked = remember(suggestions) {
        mutableStateListOf<Boolean>().apply { repeat(suggestions.size) { add(true) } }
    }
    val sheetState = rememberModalBottomSheetState()
    val allIds = suggestions.map { it.id }

    ModalBottomSheet(
        onDismissRequest = { onResolve(emptyList(), allIds) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "AI found these — add to your lists?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            suggestions.forEachIndexed { index, suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked.getOrElse(index) { true },
                        onCheckedChange = { if (index < checked.size) checked[index] = it },
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(suggestion.content, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (suggestion.type == SuggestionType.TODO) "To-do" else "Event",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { onResolve(emptyList(), allIds) }) { Text("Dismiss") }
                FilledTonalButton(
                    onClick = {
                        val accept = suggestions.filterIndexed { i, _ -> checked.getOrElse(i) { false } }.map { it.id }
                        val dismiss = suggestions.filterIndexed { i, _ -> !checked.getOrElse(i) { false } }.map { it.id }
                        onResolve(accept, dismiss)
                    },
                    enabled = checked.any { it },
                ) { Text("Add selected") }
            }
        }
    }
}
