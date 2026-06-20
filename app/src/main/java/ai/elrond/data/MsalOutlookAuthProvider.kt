package ai.elrond.data

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import java.io.File
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real [OutlookAuthProvider] over MSAL single-account mode. This is the ONLY class that references
 * `com.microsoft.identity.client.*` — everything else depends on the [OutlookAuthProvider] seam, so
 * unit tests never load MSAL. Behaviour here is validated on-device (MSAL needs a real Activity,
 * browser tab, and a registered Azure app); the JVM suite covers the Graph mapping and the auth
 * *seam*, not this implementation.
 *
 * Configuration is generated at runtime into the app cache (so the Azure client id stays in
 * local.properties → BuildConfig and never lands in a committed res/raw file). A blank client id
 * leaves the provider [OutlookAuthState.NotConfigured] and every operation fails gracefully.
 *
 * Token strategy per the spec: [currentToken] tries silent acquisition first; if MSAL reports
 * [MsalUiRequiredException] (or any failure) it returns a failure *without* showing UI, and the
 * Events tab surfaces the interactive [signIn] prompt. [MsalException]s are caught and surfaced as
 * failed [Result]s rather than thrown.
 */
class MsalOutlookAuthProvider(
    private val context: Context,
    private val config: OAuthConfig,
) : OutlookAuthProvider {

    private val _state = MutableStateFlow<OutlookAuthState>(
        if (config.clientId.isBlank()) OutlookAuthState.NotConfigured else OutlookAuthState.SignedOut,
    )
    override val state: StateFlow<OutlookAuthState> = _state.asStateFlow()

    private val authority: String =
        "https://login.microsoftonline.com/${config.tenantId ?: "common"}"

    private val initMutex = Mutex()
    @Volatile private var pca: ISingleAccountPublicClientApplication? = null
    @Volatile private var initFailed = false

    /** Lazily builds the single-account PCA. Returns null when unconfigured or init fails. */
    private suspend fun ensurePca(): ISingleAccountPublicClientApplication? {
        pca?.let { return it }
        if (config.clientId.isBlank() || initFailed) return null
        return initMutex.withLock {
            pca?.let { return it }
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val configFile = writeConfigFile()
                    PublicClientApplication.createSingleAccountPublicClientApplication(context, configFile)
                }
            }.getOrElse {
                initFailed = true
                _state.value = OutlookAuthState.NotConfigured
                return@withLock null
            }
            pca = created
            refreshAccountState(created)
            created
        }
    }

    override suspend fun currentToken(): Result<String> {
        val app = ensurePca() ?: return Result.failure(CalendarNotAuthenticatedException(CalendarProviderType.OUTLOOK))
        val account = currentAccount(app) ?: run {
            _state.value = OutlookAuthState.SignedOut
            return Result.failure(CalendarNotAuthenticatedException(CalendarProviderType.OUTLOOK))
        }
        return runCatching {
            suspendCancellableCoroutine<IAuthenticationResult> { cont ->
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(authority)
                    .withScopes(config.scopes)
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(result: IAuthenticationResult) = cont.resumeOnce(result)
                        override fun onError(exception: MsalException) {
                            if (exception is MsalUiRequiredException) _state.value = OutlookAuthState.SignedOut
                            cont.resumeWithThrowable(exception)
                        }
                    })
                    .build()
                app.acquireTokenSilentAsync(params)
            }.accessToken
        }
    }

    override suspend fun signIn(activity: Activity): Result<Unit> {
        val app = ensurePca() ?: return Result.failure(
            IllegalStateException("Outlook sign-in is unavailable — no Azure client id configured."),
        )
        return runCatching {
            val result = suspendCancellableCoroutine<IAuthenticationResult> { cont ->
                val params = SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(config.scopes)
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(result: IAuthenticationResult) = cont.resumeOnce(result)
                        override fun onError(exception: MsalException) = cont.resumeWithThrowable(exception)
                        override fun onCancel() =
                            cont.resumeWithThrowable(MsalUserCancelException())
                    })
                    .build()
                app.signIn(params)
            }
            _state.value = OutlookAuthState.SignedIn(result.account.username)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        val app = pca ?: run {
            _state.value = if (config.clientId.isBlank()) {
                OutlookAuthState.NotConfigured
            } else {
                OutlookAuthState.SignedOut
            }
            return Result.success(Unit)
        }
        return runCatching {
            suspendCancellableCoroutine<Unit> { cont ->
                app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                    override fun onSignOut() = cont.resumeOnce(Unit)
                    override fun onError(exception: MsalException) = cont.resumeWithThrowable(exception)
                })
            }
            _state.value = OutlookAuthState.SignedOut
        }
    }

    private suspend fun refreshAccountState(app: ISingleAccountPublicClientApplication) {
        val account = currentAccount(app)
        _state.value = if (account != null) {
            OutlookAuthState.SignedIn(account.username)
        } else {
            OutlookAuthState.SignedOut
        }
    }

    private suspend fun currentAccount(app: ISingleAccountPublicClientApplication): IAccount? =
        runCatching {
            suspendCancellableCoroutine<IAccount?> { cont ->
                app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                    // MSAL may invoke onAccountLoaded and/or onAccountChanged; resumeOnce guards against
                    // a double-resume (the continuation can only be resumed once).
                    override fun onAccountLoaded(activeAccount: IAccount?) = cont.resumeOnce(activeAccount)
                    override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) =
                        cont.resumeOnce(currentAccount)
                    override fun onError(exception: MsalException) = cont.resumeWithThrowable(exception)
                })
            }
        }.getOrNull()

    /**
     * Writes the MSAL JSON config to the app cache and returns the file. Single-account mode,
     * audience chosen from the tenant: "common" allows personal + work/school accounts.
     */
    private fun writeConfigFile(): File {
        val tenant = config.tenantId ?: "common"
        val audienceType = if (tenant == "common") "AzureADandPersonalMicrosoftAccount" else "AzureADMyOrg"
        val configJson = """
            {
              "client_id": "${config.clientId}",
              "authorization_user_agent": "DEFAULT",
              "redirect_uri": "${config.redirectUri}",
              "account_mode": "SINGLE",
              "broker_redirect_uri_registered": false,
              "authorities": [
                {
                  "type": "AAD",
                  "audience": { "type": "$audienceType", "tenant_id": "$tenant" }
                }
              ]
            }
        """.trimIndent()
        return File(context.cacheDir, "msal_config.json").apply { writeText(configJson) }
    }
}

/** MSAL has no dedicated cancel exception; this marks a user-cancelled interactive sign-in. */
class MsalUserCancelException : Exception("Sign-in was cancelled.")

private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) resumeWith(Result.success(value))
}

private fun <T> CancellableContinuation<T>.resumeWithThrowable(t: Throwable) {
    if (isActive) resumeWith(Result.failure(t))
}
