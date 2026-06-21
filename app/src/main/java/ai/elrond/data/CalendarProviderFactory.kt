package ai.elrond.data

import ai.elrond.BuildConfig
import android.content.Context

/**
 * Returns the [CalendarProvider] for a requested [CalendarProviderType]. The single place that knows
 * how to construct each provider, so callers depend only on the interface and the user's stored
 * preference.
 *
 * Outlook config comes from BuildConfig (fed by local.properties — never committed). Google stays a
 * stub. [createOrDeviceFallback] degrades gracefully to the always-working device calendar when the
 * requested provider can't function in this build (Google unimplemented, or Outlook missing a client
 * id), so a misconfigured preference never crashes the calendar.
 */
object CalendarProviderFactory {

    /** OAuth config for the still-stubbed Google provider — replace with real values at setup. */
    private val googleConfig = OAuthConfig(
        clientId = "REPLACE_WITH_GOOGLE_OAUTH_CLIENT_ID",
        redirectUri = "ai.elrond:/oauth2redirect",
        scopes = listOf("https://www.googleapis.com/auth/calendar"),
    )

    /** Outlook/Azure OAuth config, sourced from BuildConfig (local.properties). */
    val outlookConfig: OAuthConfig = OAuthConfig(
        clientId = BuildConfig.OUTLOOK_CLIENT_ID,
        redirectUri = BuildConfig.OUTLOOK_REDIRECT_URI,
        scopes = listOf("Calendars.ReadWrite"),
        tenantId = BuildConfig.OUTLOOK_TENANT_ID.ifBlank { "common" },
    )

    fun create(
        type: CalendarProviderType,
        context: Context,
        outlookAuth: OutlookAuthProvider = NoOpOutlookAuthProvider(),
    ): CalendarProvider = when (type) {
        CalendarProviderType.DEVICE -> DeviceCalendarProvider(context.contentResolver)
        CalendarProviderType.GOOGLE -> GoogleCalendarProvider(googleConfig)
        CalendarProviderType.OUTLOOK -> OutlookCalendarProvider(
            config = outlookConfig,
            tokenProvider = outlookAuth::currentToken,
        )
    }

    /** True when [type] has a working backend in this build (used for graceful fallback). */
    fun isConfigured(type: CalendarProviderType): Boolean = when (type) {
        CalendarProviderType.DEVICE -> true
        CalendarProviderType.OUTLOOK -> outlookConfig.clientId.isNotBlank()
        CalendarProviderType.GOOGLE -> false // Google OAuth not yet wired
    }

    /**
     * The requested provider, or a [DeviceCalendarProvider] fallback when it can't work in this build
     * — so selecting Google (unimplemented) or Outlook-without-a-client-id reads the device calendar
     * instead of failing. (Outlook *with* a client id is returned even when signed out: the Events tab
     * then shows the sign-in prompt rather than silently falling back.)
     */
    fun createOrDeviceFallback(
        type: CalendarProviderType,
        context: Context,
        outlookAuth: OutlookAuthProvider = NoOpOutlookAuthProvider(),
    ): CalendarProvider =
        if (isConfigured(type)) create(type, context, outlookAuth) else DeviceCalendarProvider(context.contentResolver)
}
