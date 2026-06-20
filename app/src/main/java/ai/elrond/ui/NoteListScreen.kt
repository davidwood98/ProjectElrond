package ai.elrond.ui

import ai.elrond.presentation.NoteListViewModel
import ai.elrond.domain.NotePage
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.TodoViewModel
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Landing page: grid of saved notes, FAB to create, long-press to delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onOpenNote: (pageId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteListViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val todoCount by todoViewModel.activeCount.collectAsStateWithLifecycle()
    val hasNewExtractedItems by settingsViewModel.hasNewExtractedItems.collectAsStateWithLifecycle()
    var deleteCandidate by remember { mutableStateOf<NotePage?>(null) }
    var showTodoPanel by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Project Elrond",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Project Elrond") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showTodoPanel = true
                        settingsViewModel.markExtractedItemsSeen()
                    }) {
                        BadgedBox(badge = {
                            when {
                                hasNewExtractedItems -> Badge { Text("+") }
                                todoCount > 0 -> Badge { Text(todoCount.toString()) }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "To-do list")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createNote(onOpenNote) }) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        if (pages.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding).fillMaxSize())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pages, key = { it.id }) { page ->
                    NoteCard(
                        page = page,
                        viewModel = viewModel,
                        onClick = { onOpenNote(page.id) },
                        onLongClick = { deleteCandidate = page },
                    )
                }
            }
        }
    }

    if (showTodoPanel) {
        TodoPanel(
            viewModel = todoViewModel,
            onDismiss = { showTodoPanel = false },
            onOpenSource = { sourceId ->
                showTodoPanel = false
                onOpenNote(sourceId)
            },
        )
    }

    deleteCandidate?.let { page ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete this note?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNote(page.id)
                        deleteCandidate = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }
    } // ModalNavigationDrawer content
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    page: NotePage,
    viewModel: NoteListViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            NoteThumbnail(
                page = page,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
            Text(
                text = page.displayTitle(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Edited ${EDITED_FORMATTER.format(Instant.ofEpochMilli(page.modifiedAt).atZone(ZoneId.systemDefault()))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Card thumbnail: shows the cached WebP if one exists (decoded off the main thread), otherwise
 * draws the stroke polylines (also decoded off-main — see [NoteListViewModel.preview]). Both are
 * re-fetched when the page is edited (keyed on [NotePage.modifiedAt]), so a freshly generated
 * thumbnail replaces the polyline fallback on the next visit.
 */
@Composable
private fun NoteThumbnail(
    page: NotePage,
    viewModel: NoteListViewModel,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, page.id, page.modifiedAt) {
        value = viewModel.thumbnail(page.id)
    }
    val cached = bitmap
    if (cached != null) {
        Image(
            bitmap = cached.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.testTag(THUMBNAIL_IMAGE_TAG),
        )
    } else {
        val preview by produceState<List<List<Pair<Float, Float>>>>(emptyList(), page.id, page.modifiedAt) {
            value = viewModel.preview(page.id)
        }
        StrokeThumbnail(polylines = preview, modifier = modifier.testTag(THUMBNAIL_FALLBACK_TAG))
    }
}

/** Miniature of the page's ink, drawn from normalized (0..1) polylines. */
@Composable
private fun StrokeThumbnail(
    polylines: List<List<Pair<Float, Float>>>,
    modifier: Modifier = Modifier,
) {
    val inkColor = Color(ai.elrond.presentation.CanvasViewModel.USER_INK_COLOR)
    Canvas(modifier = modifier) {
        if (polylines.isEmpty()) {
            // Blank page hint: a few faint ruled lines.
            val lineColor = inkColor.copy(alpha = 0.08f)
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            }
            return@Canvas
        }
        val scale = minOf(size.width, size.height) * 0.9f
        val pad = minOf(size.width, size.height) * 0.05f
        polylines.forEach { line ->
            if (line.size < 2) return@forEach
            val path = Path()
            line.forEachIndexed { i, (x, y) ->
                val px = pad + x * scale
                val py = pad + y * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, inkColor.copy(alpha = 0.85f), style = Stroke(width = 2.5f))
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No notes yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Tap + to create your first note.\nWrite naturally with your S Pen, " +
                    "and write /Q to ask the AI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private val EDITED_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** Test tags distinguishing the cached-bitmap thumbnail from the polyline fallback. */
internal const val THUMBNAIL_IMAGE_TAG = "note-thumbnail-image"
internal const val THUMBNAIL_FALLBACK_TAG = "note-thumbnail-fallback"
