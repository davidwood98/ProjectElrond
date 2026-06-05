package ai.elrond.data

import ai.elrond.ai.AiInkNote
import ai.elrond.notes.Notebook
import ai.elrond.notes.NotePage
import ai.elrond.todo.TodoItem
import ai.elrond.todo.TodoPriority

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
