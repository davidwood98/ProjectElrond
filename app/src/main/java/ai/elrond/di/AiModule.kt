package ai.elrond.di

import ai.elrond.BuildConfig
import ai.elrond.data.HandwritingRecognizer
import ai.elrond.data.MlKitHandwritingRecognizer
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AiCalendarEventExtractor
import ai.elrond.aibackend.AiTaskExtractor
import ai.elrond.aibackend.CalendarEventExtractor
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.aibackend.anthropic.AnthropicConfig
import ai.elrond.aibackend.anthropic.AnthropicProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AI / handwriting-recognition bindings, kept in their own module so tests can swap them
 * wholesale via `@TestInstallIn(replaces = [AiModule::class])` with fakes — exercising the
 * graph without real ML Kit or the Anthropic API.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    /** Unscoped: a fresh recognizer per consumer, so each owns its native resources and close(). */
    @Provides
    fun provideHandwritingRecognizer(): HandwritingRecognizer = MlKitHandwritingRecognizer()

    /** Null when no API key is configured (AI disabled) — consumers handle the null. */
    @Provides
    @Singleton
    fun provideAiProvider(): AIProvider? =
        BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
            ?.let { AnthropicProvider(AnthropicConfig(apiKey = it)) }

    @Provides
    fun provideTaskExtractor(provider: AIProvider?): TaskExtractor? =
        provider?.let { AiTaskExtractor(it) }

    @Provides
    fun provideCalendarEventExtractor(provider: AIProvider?): CalendarEventExtractor? =
        provider?.let { AiCalendarEventExtractor(it) }
}
