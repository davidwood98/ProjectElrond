package ai.elrond.ui

import ai.elrond.data.ElrondDatabase
import ai.elrond.data.TodoRepository
import ai.elrond.todo.TodoViewModel
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the TODO panel: add → complete (drops into Done) → delete.
 * Backed by a real in-memory Room database + the real ViewModel/repository.
 */
@RunWith(AndroidJUnit4::class)
class TodoPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: ElrondDatabase
    private lateinit var viewModel: TodoViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = TodoViewModel(TodoRepository(db.todoDao()))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun add_complete_and_delete_a_task() {
        composeRule.setContent {
            TodoPanel(viewModel = viewModel, onDismiss = {}, onOpenSource = {})
        }

        composeRule.onNodeWithText("Add a task").performTextInput("Buy milk")
        composeRule.onNodeWithContentDescription("Add task").performClick()
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithText("Buy milk").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Buy milk").assertIsDisplayed()

        // Tick the single task's checkbox → it moves into the "Done" section.
        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithText("Done (1)").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Delete task").performClick()
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithText("Buy milk").fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
