package ai.elrond.di

import ai.elrond.calendar.CalendarProviderFactory
import ai.elrond.calendar.CalendarProviderType
import ai.elrond.calendar.MsalOutlookAuthProvider
import ai.elrond.calendar.NoOpOutlookAuthProvider
import ai.elrond.calendar.OutlookAuthProvider
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Calendar OAuth bindings, kept separate (like [AiModule]) so tests can swap them and so the MSAL
 * dependency is confined to one provider. When Outlook has no client id (unconfigured build), this
 * returns the [NoOpOutlookAuthProvider] fallback — so the Hilt graph constructs without ever loading
 * MSAL (important for instrumented tests that have no Azure registration).
 */
@Module
@InstallIn(SingletonComponent::class)
object CalendarModule {

    @Provides
    @Singleton
    fun provideOutlookAuthProvider(@ApplicationContext context: Context): OutlookAuthProvider =
        if (CalendarProviderFactory.isConfigured(CalendarProviderType.OUTLOOK)) {
            MsalOutlookAuthProvider(context, CalendarProviderFactory.outlookConfig)
        } else {
            NoOpOutlookAuthProvider()
        }
}
