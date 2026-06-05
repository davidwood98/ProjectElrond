package ai.elrond.calendar

import android.content.Context

/**
 * Returns the [CalendarProvider] for a requested [CalendarProviderType]. The
 * single place that knows how to construct each provider, so callers depend only
 * on the interface and the user's stored preference.
 */
object CalendarProviderFactory {

    /** OAuth config for the stubbed providers — replace with real values at setup. */
    private val googleConfig = OAuthConfig(
        clientId = "REPLACE_WITH_GOOGLE_OAUTH_CLIENT_ID",
        redirectUri = "ai.elrond:/oauth2redirect",
        scopes = listOf("https://www.googleapis.com/auth/calendar"),
    )
    private val outlookConfig = OAuthConfig(
        clientId = "REPLACE_WITH_AZURE_APP_CLIENT_ID",
        redirectUri = "msauth://ai.elrond/callback",
        scopes = listOf("Calendars.ReadWrite"),
        tenantId = "common",
    )

    fun create(type: CalendarProviderType, context: Context): CalendarProvider = when (type) {
        CalendarProviderType.DEVICE -> DeviceCalendarProvider(context.contentResolver)
        CalendarProviderType.GOOGLE -> GoogleCalendarProvider(googleConfig)
        CalendarProviderType.OUTLOOK -> OutlookCalendarProvider(outlookConfig)
    }
}
