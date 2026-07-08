package ai.elrond.di

import ai.elrond.data.ThumbnailCache
import ai.elrond.data.CalendarRepository
import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NotebookLinkRepository
import ai.elrond.data.NoteRepository
import ai.elrond.data.SessionNotesTracker
import ai.elrond.data.SubjectRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TagRepository
import ai.elrond.data.TodoRepository
import ai.elrond.data.ExtractionScheduler
import ai.elrond.data.SettingsRepository
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the app's object graph — replaces the manual `ElrondApplication`
 * container (FA-3). Repositories are process singletons. AI/recognition bindings live in
 * [AiModule] so tests can replace them.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ElrondDatabase =
        ElrondDatabase.get(context)

    @Provides
    @Singleton
    fun provideNoteRepository(db: ElrondDatabase): NoteRepository = NoteRepository(
        notebookDao = db.notebookDao(),
        pageDao = db.notePageDao(),
        strokeDao = db.strokeDao(),
        aiNoteDao = db.aiNoteDao(),
        editEventDao = db.pageEditEventDao(),
    )

    @Provides
    @Singleton
    fun provideTodoRepository(db: ElrondDatabase): TodoRepository =
        TodoRepository(todoDao = db.todoDao())

    @Provides
    @Singleton
    fun provideSubjectRepository(db: ElrondDatabase): SubjectRepository =
        SubjectRepository(subjectDao = db.subjectDao(), noteSubjectDao = db.noteSubjectDao())

    @Provides
    @Singleton
    fun provideNotebookLinkRepository(db: ElrondDatabase): NotebookLinkRepository =
        NotebookLinkRepository(linkDao = db.notebookLinkDao())

    @Provides
    @Singleton
    fun provideTagRepository(db: ElrondDatabase): TagRepository =
        TagRepository(tagDao = db.tagDao(), notebookTagDao = db.notebookTagDao())

    /** Process-wide in-memory session-notes holder (FA-16); cleared on background by MainActivity. */
    @Provides
    @Singleton
    fun provideSessionNotesTracker(): SessionNotesTracker = SessionNotesTracker()

    @Provides
    @Singleton
    fun provideCalendarRepository(db: ElrondDatabase): CalendarRepository =
        CalendarRepository(dao = db.calendarEventDao())

    @Provides
    @Singleton
    fun provideSuggestionRepository(db: ElrondDatabase): SuggestionRepository =
        SuggestionRepository(dao = db.pendingSuggestionDao())

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideThumbnailCache(@ApplicationContext context: Context): ThumbnailCache =
        ThumbnailCache(context.cacheDir)

    /**
     * Enqueues background auto-extraction for a page (wraps WorkManager + app context).
     * `@JvmSuppressWildcards` keeps the Dagger key as `Function1<String, Unit>` (no variance
     * wildcards) so it matches the injection site.
     */
    @Provides
    fun provideExtractionEnqueuer(
        @ApplicationContext context: Context,
    ): @JvmSuppressWildcards (String) -> Unit =
        { pageId -> ExtractionScheduler.enqueue(context, pageId) }
}
