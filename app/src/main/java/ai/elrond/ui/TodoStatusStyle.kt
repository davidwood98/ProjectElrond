package ai.elrond.ui

import ai.elrond.domain.TodoStatus
import androidx.compose.ui.graphics.Color

/**
 * FA-14 workflow-status label + dot colour, shared by the editor to-do panel and the Library
 * to-do board (List + Kanban) so the status pills read identically everywhere.
 */
internal val TodoStatusStyle: Map<TodoStatus, Pair<String, Color>> = mapOf(
    TodoStatus.TODO to ("To-do" to Color(0xFFA9ABAC)),
    TodoStatus.IN_PROGRESS to ("In progress" to Color(0xFF4652A3)),
    TodoStatus.DONE to ("Done" to Color(0xFF3CB078)),
)
