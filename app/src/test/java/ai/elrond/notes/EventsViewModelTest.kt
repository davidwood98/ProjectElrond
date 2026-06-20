package ai.elrond.notes

import ai.elrond.presentation.EventsUiState
import ai.elrond.presentation.EventsViewModel
import ai.elrond.data.CalendarEvent
import ai.elrond.data.CalendarNotAuthenticatedException
import ai.elrond.data.CalendarProviderType
import ai.elrond.data.DateRange
import ai.elrond.data.OutlookAuthProvider
import ai.elrond.data.OutlookAuthState
import android.app.Activity
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Controllable fake — signIn/out flip the observable state, like MSAL would. */
    private class FakeOutlookAuth(initial: OutlookAuthState) : OutlookAuthProvider {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<OutlookAuthState> = _state
        var signedInAs = "me@x.com"
        override suspend fun currentToken() = Result.success("tok")
        override suspend fun signIn(activity: Activity): Result<Unit> {
            _state.value = OutlookAuthState.SignedIn(signedInAs)
            return Result.success(Unit)
        }
        override suspend fun signOut(): Result<Unit> {
            _state.value = OutlookAuthState.SignedOut
            return Result.success(Unit)
        }
    }

    private fun vm(
        type: CalendarProviderType,
        auth: OutlookAuthProvider,
        load: suspend (CalendarProviderType, DateRange) -> Result<List<CalendarEvent>> = { _, _ -> Result.success(emptyList()) },
    ) = EventsViewModel(flowOf(type), auth, load, now = { 0L })

    @Test
    fun `outlook not configured surfaces NotConfigured`() = runTest(dispatcher) {
        val viewModel = vm(CalendarProviderType.OUTLOOK, FakeOutlookAuth(OutlookAuthState.NotConfigured))
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(EventsUiState.NotConfigured, viewModel.uiState.value)
    }

    @Test
    fun `outlook signed out surfaces NeedsSignIn`() = runTest(dispatcher) {
        val viewModel = vm(CalendarProviderType.OUTLOOK, FakeOutlookAuth(OutlookAuthState.SignedOut))
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(EventsUiState.NeedsSignIn, viewModel.uiState.value)
    }

    @Test
    fun `signed in outlook loads and sorts upcoming events`() = runTest(dispatcher) {
        val unsorted = listOf(
            CalendarEvent(id = "b", title = "Later", startTime = 200L, endTime = 300L),
            CalendarEvent(id = "a", title = "Sooner", startTime = 100L, endTime = 150L),
        )
        val viewModel = vm(CalendarProviderType.OUTLOOK, FakeOutlookAuth(OutlookAuthState.SignedIn("me@x.com"))) { _, _ ->
            Result.success(unsorted)
        }
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        val state = viewModel.uiState.value as EventsUiState.Events
        assertEquals("me@x.com", state.signedInAs)
        assertEquals(listOf("a", "b"), state.events.map { it.id })
    }

    @Test
    fun `a load failure surfaces Error`() = runTest(dispatcher) {
        val viewModel = vm(CalendarProviderType.OUTLOOK, FakeOutlookAuth(OutlookAuthState.SignedIn("x"))) { _, _ ->
            Result.failure(RuntimeException("offline"))
        }
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        val state = viewModel.uiState.value as EventsUiState.Error
        assertTrue(state.message.contains("offline"))
    }

    @Test
    fun `an auth failure while signed in falls back to NeedsSignIn for outlook`() = runTest(dispatcher) {
        val viewModel = vm(CalendarProviderType.OUTLOOK, FakeOutlookAuth(OutlookAuthState.SignedIn("x"))) { _, _ ->
            Result.failure(CalendarNotAuthenticatedException(CalendarProviderType.OUTLOOK))
        }
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(EventsUiState.NeedsSignIn, viewModel.uiState.value)
    }

    @Test
    fun `device provider loads events without any sign-in`() = runTest(dispatcher) {
        val viewModel = vm(CalendarProviderType.DEVICE, FakeOutlookAuth(OutlookAuthState.NotConfigured)) { _, _ ->
            Result.success(listOf(CalendarEvent(id = "d", title = "Dentist", startTime = 10L, endTime = 20L)))
        }
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        val state = viewModel.uiState.value as EventsUiState.Events
        assertEquals(CalendarProviderType.DEVICE, state.providerType)
        assertEquals(listOf("d"), state.events.map { it.id })
    }

    @Test
    fun `signIn transitions from NeedsSignIn to a loaded events list`() = runTest(dispatcher) {
        val auth = FakeOutlookAuth(OutlookAuthState.SignedOut)
        val viewModel = vm(CalendarProviderType.OUTLOOK, auth) { _, _ ->
            Result.success(listOf(CalendarEvent(id = "e", title = "Sync", startTime = 1L, endTime = 2L)))
        }
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(EventsUiState.NeedsSignIn, viewModel.uiState.value)

        viewModel.signIn(mockk<Activity>(relaxed = true))
        advanceUntilIdle()

        val state = viewModel.uiState.value as EventsUiState.Events
        assertEquals(listOf("e"), state.events.map { it.id })
    }
}
