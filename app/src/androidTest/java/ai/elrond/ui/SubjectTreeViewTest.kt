package ai.elrond.ui

import ai.elrond.domain.Subject
import ai.elrond.domain.SubjectTree
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the Subjects sidebar interactions called out in the spec: expand/collapse,
 * the colour picker, and breadcrumb pill tapping. Renders the components directly with controlled
 * state (no Hilt / DB needed).
 */
@RunWith(AndroidJUnit4::class)
class SubjectTreeViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun subject(id: String, parentId: String? = null, sortOrder: Long = 0L) =
        Subject(id = id, parentId = parentId, name = id, colorId = 0, sortOrder = sortOrder, createdAt = 0, modifiedAt = 0)

    @Test
    fun expand_collapse_toggles_child_visibility() {
        composeRule.setContent {
            var expanded by remember { mutableStateOf(emptySet<String>()) }
            SubjectTreeView(
                nodes = SubjectTree.build(listOf(subject("Parent"), subject("Child", parentId = "Parent"))),
                expandedIds = expanded,
                selectedId = null,
                editable = true,
                actions = SubjectTreeActions(
                    onToggleExpand = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                    onSelect = {},
                ),
            )
        }

        composeRule.onNodeWithText("Parent").assertIsDisplayed()
        // Collapsed: the child isn't shown until the parent is expanded.
        composeRule.onNodeWithText("Child").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Expand").performClick()
        composeRule.onNodeWithText("Child").assertIsDisplayed()
    }

    @Test
    fun tapping_the_colour_dot_opens_the_picker() {
        composeRule.setContent {
            SubjectTreeView(
                nodes = SubjectTree.build(listOf(subject("Maths"))),
                expandedIds = emptySet(),
                selectedId = null,
                editable = true,
                actions = SubjectTreeActions(onToggleExpand = {}, onSelect = {}),
            )
        }

        composeRule.onNodeWithTag(SUBJECT_DOT_TAG).performClick()
        composeRule.onNodeWithTag(SUBJECT_COLOR_PICKER_TAG).assertIsDisplayed()
    }

    @Test
    fun breadcrumb_pill_tap_invokes_callback() {
        var tapped: String? = null
        val s1 = subject("Science")
        val s2 = subject("Biology", parentId = "Science")
        composeRule.setContent {
            SubjectBreadcrumb(
                subjectId = "Biology",
                subjectsById = mapOf("Science" to s1, "Biology" to s2),
                onTapSubject = { tapped = it },
            )
        }

        composeRule.onNodeWithText("Biology").performClick()
        assertEquals("Biology", tapped)
    }
}
