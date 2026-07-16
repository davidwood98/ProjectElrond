package ai.elrond.data

import ai.elrond.domain.AiInkNote
import ai.elrond.domain.Notebook
import ai.elrond.domain.NotebookLink
import ai.elrond.domain.NotePage
import ai.elrond.domain.PageNavigationMode
import ai.elrond.domain.PageViewOrientation
import ai.elrond.domain.PaperColor
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.RecognizedLine
import ai.elrond.domain.Subject
import ai.elrond.domain.Tag
import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoStatus

fun NotebookEntity.toDomain(): Notebook = Notebook(
    id = id,
    name = name,
    createdAt = createdAt,
    // A null column means "inherit the global default"; only parse when a value is stored.
    pageNavigationMode = pageNavigationMode?.let(PageNavigationMode::fromName),
    paperStyle = paperStyle?.let(PaperStyle::fromName),
    viewOrientation = viewOrientation?.let(PageViewOrientation::fromName),
    gridSpacing = gridSpacing,
    paperColor = paperColor?.let(PaperColor::fromName),
    templateId = templateId,
    modifiedAt = modifiedAt,
)

fun NotePageEntity.toDomain(): NotePage = NotePage(
    id = id,
    notebookId = notebookId,
    customTitle = customTitle,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    tags = tags,
    contextSummary = contextSummary,
    pageNumber = pageNumber,
    isBookmarked = isBookmarked,
)

fun SubjectEntity.toDomain(): Subject = Subject(
    id = id,
    parentId = parentId,
    name = name,
    colorId = colorId,
    sortOrder = sortOrder,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
)

fun TodoItemEntity.toDomain(): TodoItem = TodoItem(
    id = id,
    content = title,
    // Legacy guard: a row marked completed is DONE regardless of the status column (pre-FA-14 rows
    // default status=0); otherwise honour the stored workflow status.
    status = if (isCompleted) TodoStatus.DONE else TodoStatus.fromInt(status),
    dueAt = dueAt,
    priority = priority.toPriority(),
    sourcePageId = sourcePageId,
    sourcePageTitle = sourcePageTitle,
    isAiExtracted = isAiExtracted,
    createdAt = createdAt,
    completedAt = completedAt,
)

fun TodoItem.toEntity(): TodoItemEntity = TodoItemEntity(
    id = id,
    title = content,
    isCompleted = status.isDone, // kept in sync with status
    status = status.ordinal,
    dueAt = dueAt,
    priority = priority.ordinal,
    sourcePageId = sourcePageId,
    sourcePageTitle = sourcePageTitle,
    isAiExtracted = isAiExtracted,
    createdAt = createdAt,
    completedAt = completedAt,
)

private fun Int.toPriority(): TodoPriority =
    TodoPriority.entries.getOrElse(this) { TodoPriority.NONE }

fun AiNoteEntity.toDomain(): AiInkNote = AiInkNote(
    id = id,
    text = text,
    x = x,
    y = y,
    widthPx = widthPx,
    heightPx = heightPx,
    fontScale = fontScale,
)

fun AiInkNote.toEntity(pageId: String, createdAt: Long): AiNoteEntity = AiNoteEntity(
    id = id,
    pageId = pageId,
    text = text,
    x = x,
    y = y,
    widthPx = widthPx,
    heightPx = heightPx,
    fontScale = fontScale,
    createdAt = createdAt,
)

fun TagEntity.toDomain(): Tag = Tag(id = id, name = name, colorArgb = colorArgb)

fun NotebookTagRow.toTag(): Tag = Tag(id = tagId, name = name, colorArgb = colorArgb)

fun NotebookLinkEntity.toDomain(): NotebookLink = NotebookLink(
    id = id,
    targetNotebookId = targetNotebookId,
    targetPageId = targetPageId,
    x = x,
    y = y,
    widthPx = widthPx,
    heightPx = heightPx,
    linkText = linkText,
    createdAt = createdAt,
)

/**
 * Unlike [AiInkNote.toEntity], [NotebookLink.createdAt] comes from the domain object — never a
 * repository "now" — because it drives the user-visible Backlinks ordering and must survive
 * unrelated autosaves of the same page. Do not "fix" this for consistency.
 */
fun NotebookLink.toEntity(sourcePageId: String): NotebookLinkEntity = NotebookLinkEntity(
    id = id,
    sourcePageId = sourcePageId,
    targetNotebookId = targetNotebookId,
    targetPageId = targetPageId,
    x = x,
    y = y,
    widthPx = widthPx,
    heightPx = heightPx,
    linkText = linkText,
    createdAt = createdAt,
)

fun RecognizedLineEntity.toDomain(): RecognizedLine = RecognizedLine(
    text = text,
    minX = minX,
    minY = minY,
    maxX = maxX,
    maxY = maxY,
)

fun CalendarEventEntity.toDomain(): CalendarEvent = CalendarEvent(
    id = externalEventId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    location = location,
    attendees = attendees,
    calendarId = calendarId,
    sourceNoteId = sourcePageId,
    isAiSuggested = isAiSuggested,
)

/** Local id is stored separately from the (optional) backing-calendar event id. */
val CalendarEventEntity.localId: String get() = id
