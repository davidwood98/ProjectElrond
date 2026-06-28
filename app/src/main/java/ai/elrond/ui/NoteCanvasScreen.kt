package ai.elrond.ui

import ai.elrond.presentation.AiUiState
import ai.elrond.domain.CanvasTool
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.PrefixTriggerState
import ai.elrond.domain.SuggestionType
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.SubjectViewModel
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

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
) {
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val aiNotes by viewModel.aiNotes.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    // Vertical scroll within the page (FA-20); the page is fit-to-width and taller than the viewport.
    val pageScrollPx by viewModel.pageScrollPx.collectAsStateWithLifecycle()
    // The notebook's pages (FA-20) — drives the page indicator; a horizontal finger swipe turns pages.
    val notebookPages by viewModel.notebookPages.collectAsStateWithLifecycle()
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
    // FA-14 appearance tweaks + editor header state.
    val paperStyle by settingsViewModel.paperStyle.collectAsStateWithLifecycle()
    val penIconStyle by settingsViewModel.penIconStyle.collectAsStateWithLifecycle()
    val pageTitle by viewModel.pageTitle.collectAsStateWithLifecycle()
    val pageDateLabel by viewModel.pageDateLabel.collectAsStateWithLifecycle()
    val libraryNotes by noteListViewModel.pages.collectAsStateWithLifecycle()
    // The note tabs show notes opened in the current foreground session (FA-16) — cleared on background.
    val sessionNotes by noteListViewModel.sessionNotes.collectAsStateWithLifecycle()
    // Quick Nav (FA-16): the read-only subject tree with each subject's notes nested inside it.
    val subjectTree by subjectViewModel.tree.collectAsStateWithLifecycle()
    val subjectExpandedIds by subjectViewModel.expandedIds.collectAsStateWithLifecycle()
    val noteSubjects by subjectViewModel.noteSubjects.collectAsStateWithLifecycle()
    // subjectId → its notes (null key = unfiled), so the Quick Nav tree can render notes under subjects.
    val notesBySubject = remember(libraryNotes, noteSubjects) {
        libraryNotes.groupBy { noteSubjects[it.id] }
    }
    var showPages by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }

    // S Pen side-button gestures (FA-19). One shared tracker, fed by both the InkCanvas (while
    // drawing) and an always-present top-level observer below — so the button keeps working in
    // every tool mode, including Lasso where the selection overlay otherwise owns input.
    val stylusButtonTracker = remember(viewModel) { StylusButtonTracker(viewModel) }
    DisposableEffect(stylusButtonTracker) { onDispose { stylusButtonTracker.dispose() } }

    // AI-box selection lives in the UI (not persisted). When the "edit mode on creation"
    // setting is on (default) a freshly created note starts selected; loaded notes always
    // start deselected (part of the note flow). Deselect by tapping anywhere off the box.
    val selectOnCreate by settingsViewModel.aiNoteSelectedOnCreate.collectAsStateWithLifecycle()
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    // Auto-select only notes the user just created via /Q (a ViewModel event), never notes
    // loaded from storage — so a saved page opens with its AI notes deselected.
    LaunchedEffect(viewModel) {
        viewModel.createdNoteEvents.collect { id ->
            if (selectOnCreate) selectedNoteId = id
        }
    }
    // FA-20: a horizontal page-turn swipe emits the target page id — navigate to it (the editor
    // re-opens on that page; a fresh page when swiping past the last with content on the current one).
    LaunchedEffect(viewModel) {
        viewModel.pageTurnEvents.collect { id -> onOpenNote(id) }
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

        // Paper background (Ruled / Plain / Dots) behind the transparent ink layers; scrolls with ink.
        PaperBackground(paper = paperStyle, scrollPx = pageScrollPx, modifier = Modifier.fillMaxSize())

        InkCanvas(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            stylusButtonTracker = stylusButtonTracker,
        )

        // Tap anywhere off a selected AI box to deselect it (place it into the note flow).
        // Present only while something is selected, so it never intercepts drawing otherwise.
        if (selectedNoteId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { selectedNoteId = null }
                    },
            )
        }

        // AI answers as handwriting-style ink, inline at their trigger position. Error/clarify
        // notes are NOT placed here — they render as a centred pop-up below (always on-screen).
        aiNotes.forEach { note ->
            if (!note.isError) {
                key(note.id) {
                    AiInkNoteView(
                        note = note,
                        selected = selectedNoteId == note.id,
                        onSelect = { selectedNoteId = note.id },
                        onMove = { dx, dy -> viewModel.moveAiNote(note.id, dx, dy) },
                        onResize = { dW, dH -> viewModel.resizeAiNote(note.id, dW, dH) },
                        onRemove = { viewModel.removeAiNote(note.id) },
                        scrollPx = pageScrollPx,
                    )
                }
            }
        }

        // On-canvas AI activity: loading dots while thinking, red ink on failure.
        when (val state = aiState) {
            is AiUiState.Thinking -> AiLoadingIndicator(x = state.x, y = state.y - pageScrollPx)
            is AiUiState.Error -> AiErrorInk(
                message = state.message,
                x = state.x,
                y = state.y - pageScrollPx,
                onDismiss = viewModel::dismissAiResponse,
            )
            AiUiState.Idle -> Unit
        }

        // Lasso selection tool: a full-screen overlay that owns input while the tool is active
        // (drawn above ink + AI notes, below the toolbars so they stay tappable).
        if (tool == CanvasTool.LASSO) {
            SelectionLayer(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }

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
            ToolbarButton(
                painter = painterResource(
                    ElrondIcons.penToolIcon(ElrondIcons.Pen, ElrondIcons.PenTip, penIconStyle),
                ),
                contentDescription = "Pen",
                onClick = { viewModel.selectTool(CanvasTool.PEN) },
                selected = tool == CanvasTool.PEN,
                treatment = toolTreatment,
            )
            // Visual placeholders (Highlighter / Pencil / Text) — NOT yet wired as tools. Present in
            // the handoff order so the full toolbar spacing/feel can be reviewed on-device; they
            // render in the resting state and no-op on tap. The pen-family icons honour the FA-14
            // Body/Tip setting.
            ToolbarButton(
                painter = painterResource(
                    ElrondIcons.penToolIcon(
                        ElrondIcons.Highlighter,
                        ElrondIcons.HighlighterTip,
                        penIconStyle,
                    ),
                ),
                contentDescription = "Highlighter (coming soon)",
                onClick = {},
            )
            ToolbarButton(
                painter = painterResource(
                    ElrondIcons.penToolIcon(ElrondIcons.Pencil, ElrondIcons.PencilTip, penIconStyle),
                ),
                contentDescription = "Pencil (coming soon)",
                onClick = {},
            )
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
            // Import + Record: visual placeholders (no backend yet); no-op on tap.
            ToolbarButton(
                painter = painterResource(ElrondIcons.Add),
                contentDescription = "Import (coming soon)",
                onClick = {},
            )
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
                    // Handoff menu items not yet wired to a backend — shown disabled.
                    DropdownMenuItem(
                        text = { Text("Page style") },
                        enabled = false,
                        onClick = {},
                    )
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

        // Note title + created date in the grey header band, below the toolbar. The note tabs sit at
        // the top of the band, just above the title (active tab keeps its accent fill).
        EditorHeader(
            title = pageTitle,
            dateLabel = pageDateLabel,
            onRename = viewModel::renamePage,
            tabs = {
                NoteTabPills(
                    tabs = sessionNotes,
                    currentPageId = pageId,
                    currentTitle = pageTitle,
                    onSelectTab = { id -> if (id != pageId) onOpenNote(id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                // Inset-aware so the band can't slide under a side cutout/nav bar; keeps its own 14dp
                // visual margin (slightly tighter than the toolbar's "side spacing").
                .padding(start = leftInset + 14.dp, end = rightInset + 14.dp, top = headerTop),
        )

        if (showPages) {
            PagesOverlay(currentTitle = pageTitle, onDismiss = { showPages = false })
        }
        if (showLibrary) {
            LibraryOverlay(
                subjectTree = subjectTree,
                notesBySubject = notesBySubject,
                expandedIds = subjectExpandedIds,
                currentPageId = pageId,
                onToggleSubject = subjectViewModel::toggleExpanded,
                onLocateCurrent = {
                    noteSubjects[pageId]?.let { subjectViewModel.expandToSubject(it) }
                },
                onOpenNote = { id ->
                    showLibrary = false
                    if (id != pageId) onOpenNote(id)
                },
                onDismiss = { showLibrary = false },
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
        if (notebookPages.size > 1) {
            val pageIdx = notebookPages.indexOfFirst { it.id == pageId }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
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
private fun AiErrorInk(message: String, x: Float, y: Float, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    Text(
        text = message,
        fontFamily = HandwritingFontFamily,
        fontSize = 24.sp,
        color = ErrorInkColor,
        modifier = Modifier
            .absoluteOffset(
                x = with(density) { x.toDp() },
                y = with(density) { y.toDp() },
            )
            .clickable(onClick = onDismiss)
            .padding(4.dp),
    )
}

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
