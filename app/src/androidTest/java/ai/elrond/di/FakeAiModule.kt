package ai.elrond.di

import ai.elrond.data.HandwritingRecognizer
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.CalendarEventExtractor
import ai.elrond.aibackend.TagSuggestionExtractor
import ai.elrond.aibackend.TaskExtractor
import androidx.ink.strokes.Stroke
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [AiModule] in instrumented tests with fakes, so the Hilt graph builds without
 * touching real ML Kit or the Anthropic API. Demonstrates the FA-3 test-module pattern.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AiModule::class])
object FakeAiModule {

    @Provides
    fun provideHandwritingRecognizer(): HandwritingRecognizer = object : HandwritingRecognizer {
        override suspend fun recognize(strokes: List<Stroke>): Result<String> = Result.success("")
    }

    @Provides
    @Singleton
    fun provideAiProvider(): AIProvider? = null

    @Provides
    fun provideTaskExtractor(): TaskExtractor? = null

    @Provides
    fun provideCalendarEventExtractor(): CalendarEventExtractor? = null

    @Provides
    fun provideTagSuggestionExtractor(): TagSuggestionExtractor? = null
}
