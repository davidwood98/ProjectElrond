package ai.elrond.data

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Authentication state for the Outlook (Microsoft) account. */
sealed interface OutlookAuthState {
    /** No Azure client id configured in this build — Outlook can't be used. */
    data object NotConfigured : OutlookAuthState

    /** Configured, but no account is signed in. */
    data object SignedOut : OutlookAuthState

    /** An account is signed in; [username] is its UPN / display name when MSAL reports one. */
    data class SignedIn(val username: String?) : OutlookAuthState
}

/**
 * Abstraction over the MSAL single-account flow, deliberately free of any MSAL types so the rest of
 * the app — and the unit tests — never depend on the library. The only implementation that touches
 * `com.microsoft.identity.client.*` is [MsalOutlookAuthProvider]; tests use a fake or
 * [NoOpOutlookAuthProvider]. This mirrors the AIProvider seam: all provider-specific code stays
 * behind one interface so it can be swapped (and so a future iOS port re-implements just this).
 *
 * The interactive [signIn] needs a host [Activity] (MSAL opens a browser tab), so it is driven from
 * the UI; [currentToken] is the silent, background path the [CalendarProvider] uses on every Graph
 * call. When silent acquisition needs interaction, it fails *without* showing UI — callers surface
 * the sign-in prompt instead.
 */
interface OutlookAuthProvider {
    val state: StateFlow<OutlookAuthState>

    /** A valid Graph access token, refreshed silently. Fails (no UI) when interactive sign-in is required. */
    suspend fun currentToken(): Result<String>

    /** Interactive sign-in; needs a host [Activity] for the browser tab. Updates [state] on success. */
    suspend fun signIn(activity: Activity): Result<Unit>

    suspend fun signOut(): Result<Unit>
}

/**
 * No-op provider used when Outlook isn't configured in the build (blank client id) or in tests:
 * always [OutlookAuthState.NotConfigured], every token/sign-in attempt fails gracefully. Keeping a
 * concrete fallback (rather than a nullable provider) means the Events tab and the
 * [OutlookCalendarProvider] never have to null-check — they just observe a state that never becomes
 * SignedIn.
 */
class NoOpOutlookAuthProvider : OutlookAuthProvider {
    override val state: StateFlow<OutlookAuthState> =
        MutableStateFlow(OutlookAuthState.NotConfigured)

    override suspend fun currentToken(): Result<String> =
        Result.failure(CalendarNotAuthenticatedException(CalendarProviderType.OUTLOOK))

    override suspend fun signIn(activity: Activity): Result<Unit> =
        Result.failure(IllegalStateException("Outlook is not configured in this build."))

    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
}
