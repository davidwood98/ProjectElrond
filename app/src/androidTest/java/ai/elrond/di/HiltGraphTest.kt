package ai.elrond.di

import ai.elrond.data.CalendarRepository
import ai.elrond.data.NoteRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import ai.elrond.data.SettingsRepository
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Validates that the Hilt SingletonComponent graph constructs at runtime and provides the
 * app's repositories, with [FakeAiModule] standing in for the real AI bindings (FA-3).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltGraphTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var noteRepository: NoteRepository
    @Inject lateinit var todoRepository: TodoRepository
    @Inject lateinit var calendarRepository: CalendarRepository
    @Inject lateinit var suggestionRepository: SuggestionRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun graph_constructs_and_provides_repositories() {
        assertNotNull(noteRepository)
        assertNotNull(todoRepository)
        assertNotNull(calendarRepository)
        assertNotNull(suggestionRepository)
        assertNotNull(settingsRepository)
    }
}
