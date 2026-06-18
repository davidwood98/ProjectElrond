package ai.elrond.calendar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide cache of [CalendarProvider] instances, one per [CalendarProviderType]. Reusing a
 * single Outlook provider (and its Ktor [HttpClient]) avoids spinning up a new client on every
 * Events-tab refresh. The shared [OutlookAuthProvider] singleton means the provider and the UI
 * observe the same auth state.
 */
@Singleton
class CalendarProviders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outlookAuth: OutlookAuthProvider,
) {
    private val cache = ConcurrentHashMap<CalendarProviderType, CalendarProvider>()

    /**
     * The provider for [type], or a device-calendar fallback when [type] can't work in this build.
     * Uses [ConcurrentHashMap.computeIfAbsent] (atomic) rather than Kotlin's get-then-put `getOrPut`,
     * so two concurrent callers can't each build an Outlook provider and leak one's Ktor HttpClient.
     */
    fun forType(type: CalendarProviderType): CalendarProvider =
        cache.computeIfAbsent(type) { CalendarProviderFactory.createOrDeviceFallback(type, context, outlookAuth) }
}
