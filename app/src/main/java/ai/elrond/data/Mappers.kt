package ai.elrond.data

import ai.elrond.notes.Notebook
import ai.elrond.notes.NotePage

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
