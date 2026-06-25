package ai.elrond.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory record of the notes opened during the current foreground session (FA-16) — the source
 * for the editor's note tabs, which now show "this session" instead of the old 24h "Recent" window.
 *
 * Deliberately NOT persisted (a process [@Singleton][javax.inject.Singleton] holder): a session is
 * the app's foreground lifetime, so the list is [clear]ed when the app goes to the background
 * (`MainActivity.onStop`, real background only — config changes are excluded), and a fresh foreground
 * starts empty.
 */
class SessionNotesTracker {

    private val _openedPageIds = MutableStateFlow<List<String>>(emptyList())

    /** Page ids opened this session, in the order they were first opened (stable — the editor tabs
     *  must not reshuffle when an already-open note is re-selected). */
    val openedPageIds: StateFlow<List<String>> = _openedPageIds

    /**
     * Records a note as opened. New notes are appended at the end; re-opening one that's already in
     * the session leaves the order **unchanged**, so the editor tabs keep their position and only the
     * active highlight moves.
     */
    fun recordOpened(pageId: String) {
        _openedPageIds.update { current -> if (pageId in current) current else current + pageId }
    }

    /** Resets the session (app backgrounded / closed). */
    fun clear() {
        _openedPageIds.value = emptyList()
    }
}
