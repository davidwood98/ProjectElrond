package ai.elrond.data

import ai.elrond.domain.AiInkNote
import ai.elrond.calendar.CalendarEvent
import ai.elrond.domain.Notebook
import ai.elrond.domain.NotePage
import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoPriority

fun NotebookEntity.toDomain(): Notebook = Notebook(
    id = id,
    name = name,
    createdAt = createdAt,
)

fun NotePageEntity.toDomain(): NotePage = NotePage(
    id = id,
    notebookId = notebookId,
    customTitle = customTitle,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    tags = tags,
    contextSummary = contextSummary,
)

fun TodoItemEntity.toDomain(): TodoItem = TodoItem(
    id = id,
    content = title,
    isCompleted = isCompleted,
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
    isCompleted = isCompleted,
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
)

fun AiInkNote.toEntity(pageId: String, createdAt: Long): AiNoteEntity = AiNoteEntity(
    id = id,
    pageId = pageId,
    text = text,
    x = x,
    y = y,
    widthPx = widthPx,
    heightPx = heightPx,
    createdAt = createdAt,
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
