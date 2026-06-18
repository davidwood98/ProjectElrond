package ai.elrond.calendar

import android.content.ContentResolver
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarProviderTest {

    private val context = mockk<Context> {
        every { contentResolver } returns mockk<ContentResolver>()
    }

    @Test
    fun `factory returns the provider matching the requested type`() {
        assertEquals(CalendarProviderType.DEVICE, CalendarProviderFactory.create(CalendarProviderType.DEVICE, context).type)
        assertEquals(CalendarProviderType.GOOGLE, CalendarProviderFactory.create(CalendarProviderType.GOOGLE, context).type)
        assertEquals(CalendarProviderType.OUTLOOK, CalendarProviderFactory.create(CalendarProviderType.OUTLOOK, context).type)
    }

    @Test
    fun `factory builds a DeviceCalendarProvider for DEVICE`() {
        val provider = CalendarProviderFactory.create(CalendarProviderType.DEVICE, context)
        assertTrue(provider is DeviceCalendarProvider)
    }

    @Test
    fun `createOrDeviceFallback degrades unconfigured providers to the device calendar`() {
        // Google OAuth isn't wired, and the test build has no Outlook client id — both fall back.
        assertTrue(
            CalendarProviderFactory.createOrDeviceFallback(CalendarProviderType.GOOGLE, context) is DeviceCalendarProvider,
        )
        assertTrue(
            CalendarProviderFactory.createOrDeviceFallback(CalendarProviderType.OUTLOOK, context) is DeviceCalendarProvider,
        )
        // Device is always available.
        assertTrue(
            CalendarProviderFactory.createOrDeviceFallback(CalendarProviderType.DEVICE, context) is DeviceCalendarProvider,
        )
    }

    @Test
    fun `google stub is unauthenticated and fails every call`() = runTest {
        val provider = GoogleCalendarProvider(googleConfig())
        assertTrue(!provider.isAuthenticated)
        assertTrue(provider.getCalendars().isFailure)
        assertTrue(provider.getEvents(DateRange(0, 1)).isFailure)
        assertTrue(provider.createEvent(sampleEvent()).isFailure)
        assertTrue(provider.updateEvent(sampleEvent()).isFailure)
        assertTrue(provider.deleteEvent("x").isFailure)
        assertTrue(provider.authenticate().exceptionOrNull() is CalendarNotAuthenticatedException)
    }

    @Test
    fun `NoOpOutlookAuthProvider stays not-configured and refuses tokens`() = runTest {
        val auth = NoOpOutlookAuthProvider()
        assertEquals(OutlookAuthState.NotConfigured, auth.state.value)
        assertTrue(auth.currentToken().exceptionOrNull() is CalendarNotAuthenticatedException)
    }

    private fun googleConfig() = OAuthConfig("id", "ai.elrond:/oauth2redirect", listOf("scope"))

    private fun sampleEvent() = CalendarEvent(
        title = "Test",
        startTime = 0L,
        endTime = 1L,
    )
}
