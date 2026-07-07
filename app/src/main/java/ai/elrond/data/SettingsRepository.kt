package ai.elrond.data

import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import ai.elrond.domain.AppAccent
import ai.elrond.domain.FingerGestureAction
import ai.elrond.domain.HighlighterColor
import ai.elrond.domain.HighlighterWidth
import ai.elrond.domain.InkLineType
import ai.elrond.domain.NoteTabsMode
import ai.elrond.domain.PenColor
import ai.elrond.domain.PencilLead
import ai.elrond.domain.PageNavigationMode
import ai.elrond.domain.StylusHoldTool
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.PenIconStyle
import ai.elrond.domain.ToolSelectedTreatment
import ai.elrond.domain.TriggerMode
import ai.elrond.domain.UnitSystem
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App settings backed by DataStore. Currently holds the (debug) `/Q` activation
 * command so it can be changed to e.g. ">Q" or "@Q".
 */
class SettingsRepository(private val context: Context) {

    /** The activation command the canvas watches for. Defaults to [DEFAULT_TRIGGER]. */
    val triggerCommand: Flow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[TRIGGER_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_TRIGGER }

    /** Stores a new trigger; rejects anything longer than [MAX_TRIGGER_LENGTH]. */
    suspend fun setTriggerCommand(value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_TRIGGER_LENGTH) {
            "Trigger must be 1–$MAX_TRIGGER_LENGTH characters"
        }
        context.settingsDataStore.edit { it[TRIGGER_KEY] = trimmed }
    }

    /** How the AI is activated on the canvas: the written command, or a lasso gesture. */
    val triggerMode: Flow<TriggerMode> = context.settingsDataStore.data
        .map { prefs -> TriggerMode.fromName(prefs[TRIGGER_MODE_KEY]) }

    suspend fun setTriggerMode(mode: TriggerMode) {
        context.settingsDataStore.edit { it[TRIGGER_MODE_KEY] = mode.name }
    }

    /**
     * Prefix-mode ([TriggerMode.PREFIX_COMMAND]) inactivity delay (ms): how long the canvas waits
     * after the last prompt stroke before sending the question. Clamped to
     * [[MIN_PREFIX_TRIGGER_DELAY_MS], [MAX_PREFIX_TRIGGER_DELAY_MS]]; default
     * [DEFAULT_PREFIX_TRIGGER_DELAY_MS].
     */
    val prefixTriggerDelayMs: Flow<Long> = context.settingsDataStore.data
        .map {
            (it[PREFIX_TRIGGER_DELAY_KEY] ?: DEFAULT_PREFIX_TRIGGER_DELAY_MS)
                .coerceIn(MIN_PREFIX_TRIGGER_DELAY_MS, MAX_PREFIX_TRIGGER_DELAY_MS)
        }

    suspend fun setPrefixTriggerDelayMs(ms: Long) {
        context.settingsDataStore.edit {
            it[PREFIX_TRIGGER_DELAY_KEY] = ms.coerceIn(MIN_PREFIX_TRIGGER_DELAY_MS, MAX_PREFIX_TRIGGER_DELAY_MS)
        }
    }

    /**
     * Prefix-mode no-prompt timeout (ms): if nothing is written after the command within this
     * window, the listening session is quietly cancelled and the command is left as normal ink.
     * Clamped to [[MIN_PREFIX_NO_PROMPT_TIMEOUT_MS], [MAX_PREFIX_NO_PROMPT_TIMEOUT_MS]]; default
     * [DEFAULT_PREFIX_NO_PROMPT_TIMEOUT_MS].
     */
    val prefixNoPromptTimeoutMs: Flow<Long> = context.settingsDataStore.data
        .map {
            (it[PREFIX_NO_PROMPT_TIMEOUT_KEY] ?: DEFAULT_PREFIX_NO_PROMPT_TIMEOUT_MS)
                .coerceIn(MIN_PREFIX_NO_PROMPT_TIMEOUT_MS, MAX_PREFIX_NO_PROMPT_TIMEOUT_MS)
        }

    suspend fun setPrefixNoPromptTimeoutMs(ms: Long) {
        context.settingsDataStore.edit {
            it[PREFIX_NO_PROMPT_TIMEOUT_KEY] =
                ms.coerceIn(MIN_PREFIX_NO_PROMPT_TIMEOUT_MS, MAX_PREFIX_NO_PROMPT_TIMEOUT_MS)
        }
    }

    /** Palm rejection: when true (default), finger touches never draw ink. */
    val stylusOnly: Flow<Boolean> = context.settingsDataStore.data
        .map { it[STYLUS_ONLY_KEY] ?: DEFAULT_STYLUS_ONLY }

    suspend fun setStylusOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[STYLUS_ONLY_KEY] = enabled }
    }

    // --- Finger gestures (FA-19) ---
    // Multi-finger taps mapped to canvas actions. Independent of palm rejection: detected whether
    // stylus-only is on or off (a deliberate 2-/3-finger tap is distinct from a resting palm).

    /** Master switch for finger gestures (default on). */
    val fingerGesturesEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[FINGER_GESTURES_ENABLED_KEY] ?: DEFAULT_FINGER_GESTURES_ENABLED }

    suspend fun setFingerGesturesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[FINGER_GESTURES_ENABLED_KEY] = enabled }
    }

    /** Single tap, two fingers (default [FingerGestureAction.UNDO]). */
    val twoFingerTapAction: Flow<FingerGestureAction> = context.settingsDataStore.data
        .map { it[TWO_FINGER_TAP_KEY]?.let(FingerGestureAction::fromName) ?: DEFAULT_TWO_FINGER_TAP_ACTION }

    suspend fun setTwoFingerTapAction(action: FingerGestureAction) {
        context.settingsDataStore.edit { it[TWO_FINGER_TAP_KEY] = action.name }
    }

    /** Single tap, three fingers (default [FingerGestureAction.REDO]). */
    val threeFingerTapAction: Flow<FingerGestureAction> = context.settingsDataStore.data
        .map { it[THREE_FINGER_TAP_KEY]?.let(FingerGestureAction::fromName) ?: DEFAULT_THREE_FINGER_TAP_ACTION }

    suspend fun setThreeFingerTapAction(action: FingerGestureAction) {
        context.settingsDataStore.edit { it[THREE_FINGER_TAP_KEY] = action.name }
    }

    /** Double tap, two fingers (default [FingerGestureAction.LAST_TOOL_SWAP]). */
    val twoFingerDoubleTapAction: Flow<FingerGestureAction> = context.settingsDataStore.data
        .map { it[TWO_FINGER_DOUBLE_TAP_KEY]?.let(FingerGestureAction::fromName) ?: DEFAULT_TWO_FINGER_DOUBLE_TAP_ACTION }

    suspend fun setTwoFingerDoubleTapAction(action: FingerGestureAction) {
        context.settingsDataStore.edit { it[TWO_FINGER_DOUBLE_TAP_KEY] = action.name }
    }

    /** Double tap, three fingers (default [FingerGestureAction.NONE]). */
    val threeFingerDoubleTapAction: Flow<FingerGestureAction> = context.settingsDataStore.data
        .map { it[THREE_FINGER_DOUBLE_TAP_KEY]?.let(FingerGestureAction::fromName) ?: DEFAULT_THREE_FINGER_DOUBLE_TAP_ACTION }

    suspend fun setThreeFingerDoubleTapAction(action: FingerGestureAction) {
        context.settingsDataStore.edit { it[THREE_FINGER_DOUBLE_TAP_KEY] = action.name }
    }

    // --- S Pen button (FA-19) ---
    // The stylus side button (BUTTON_STYLUS_PRIMARY). Press-and-hold is momentary (springs to a
    // tool while held); single/double click fire a discrete action like the finger gestures.

    /** Master switch for S Pen button gestures (default on). */
    val stylusButtonEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[STYLUS_BUTTON_ENABLED_KEY] ?: DEFAULT_STYLUS_BUTTON_ENABLED }

    suspend fun setStylusButtonEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[STYLUS_BUTTON_ENABLED_KEY] = enabled }
    }

    /** Tool the button springs to while held, reverting on release (default Eraser). */
    val stylusHoldTool: Flow<StylusHoldTool> = context.settingsDataStore.data
        .map { it[STYLUS_HOLD_TOOL_KEY]?.let(StylusHoldTool::fromName) ?: DEFAULT_STYLUS_HOLD_TOOL }

    suspend fun setStylusHoldTool(tool: StylusHoldTool) {
        context.settingsDataStore.edit { it[STYLUS_HOLD_TOOL_KEY] = tool.name }
    }

    /** Action bound to a double button click (default select Lasso). */
    val stylusDoubleClickAction: Flow<FingerGestureAction> = context.settingsDataStore.data
        .map { it[STYLUS_DOUBLE_CLICK_KEY]?.let(FingerGestureAction::fromName) ?: DEFAULT_STYLUS_DOUBLE_CLICK_ACTION }

    suspend fun setStylusDoubleClickAction(action: FingerGestureAction) {
        context.settingsDataStore.edit { it[STYLUS_DOUBLE_CLICK_KEY] = action.name }
    }

    /** Action bound to a single button click (default none). */
    val stylusSingleClickAction: Flow<FingerGestureAction> = context.settingsDataStore.data
        .map { it[STYLUS_SINGLE_CLICK_KEY]?.let(FingerGestureAction::fromName) ?: DEFAULT_STYLUS_SINGLE_CLICK_ACTION }

    suspend fun setStylusSingleClickAction(action: FingerGestureAction) {
        context.settingsDataStore.edit { it[STYLUS_SINGLE_CLICK_KEY] = action.name }
    }

    /** How the active note-tool is highlighted in the toolbar (A soft tile / B filled / C underline). */
    val toolSelectedTreatment: Flow<ToolSelectedTreatment> = context.settingsDataStore.data
        .map { ToolSelectedTreatment.fromName(it[TOOL_TREATMENT_KEY]) }

    suspend fun setToolSelectedTreatment(treatment: ToolSelectedTreatment) {
        context.settingsDataStore.edit { it[TOOL_TREATMENT_KEY] = treatment.name }
    }

    // --- Canvas tool configuration (FA-23, the toolbar's per-tool dropdown menus) ---

    /** Pen ink colour (black / red / blue; default the original navy blue). */
    val penColor: Flow<PenColor> = context.settingsDataStore.data
        .map { PenColor.fromName(it[PEN_COLOR_KEY]) }

    suspend fun setPenColor(color: PenColor) {
        context.settingsDataStore.edit { it[PEN_COLOR_KEY] = color.name }
    }

    /** Pen line style (solid / centreline / dashed / dotted / dash-dot). */
    val penLineType: Flow<InkLineType> = context.settingsDataStore.data
        .map { InkLineType.fromName(it[PEN_LINE_TYPE_KEY]) }

    suspend fun setPenLineType(type: InkLineType) {
        context.settingsDataStore.edit { it[PEN_LINE_TYPE_KEY] = type.name }
    }

    /** Highlighter colour (pink / blue / green / yellow / orange). */
    val highlighterColor: Flow<HighlighterColor> = context.settingsDataStore.data
        .map { HighlighterColor.fromName(it[HIGHLIGHTER_COLOR_KEY]) }

    suspend fun setHighlighterColor(color: HighlighterColor) {
        context.settingsDataStore.edit { it[HIGHLIGHTER_COLOR_KEY] = color.name }
    }

    /** Highlighter tip width (fine / standard / thick). */
    val highlighterWidth: Flow<HighlighterWidth> = context.settingsDataStore.data
        .map { HighlighterWidth.fromName(it[HIGHLIGHTER_WIDTH_KEY]) }

    suspend fun setHighlighterWidth(width: HighlighterWidth) {
        context.settingsDataStore.edit { it[HIGHLIGHTER_WIDTH_KEY] = width.name }
    }

    /** Pencil line style — same choices as the pen. */
    val pencilLineType: Flow<InkLineType> = context.settingsDataStore.data
        .map { InkLineType.fromName(it[PENCIL_LINE_TYPE_KEY]) }

    suspend fun setPencilLineType(type: InkLineType) {
        context.settingsDataStore.edit { it[PENCIL_LINE_TYPE_KEY] = type.name }
    }

    /** Pencil lead grade (2H…2B), light → dark; default HB. */
    val pencilLead: Flow<PencilLead> = context.settingsDataStore.data
        .map { PencilLead.fromName(it[PENCIL_LEAD_KEY]) }

    suspend fun setPencilLead(lead: PencilLead) {
        context.settingsDataStore.edit { it[PENCIL_LEAD_KEY] = lead.name }
    }

    // --- Appearance tweaks (FA-14, from the Claude Design handoff) ---

    /** Pen-family toolbar icon style: whole-tool [PenIconStyle.BODY] or writing-[PenIconStyle.TIP]. */
    val penIconStyle: Flow<PenIconStyle> = context.settingsDataStore.data
        .map { PenIconStyle.fromName(it[PEN_ICON_STYLE_KEY]) }

    suspend fun setPenIconStyle(style: PenIconStyle) {
        context.settingsDataStore.edit { it[PEN_ICON_STYLE_KEY] = style.name }
    }

    /** The app's accent colour (Blue / Navy / Green / Pink). Recolours the accent app-wide. */
    val appAccent: Flow<AppAccent> = context.settingsDataStore.data
        .map { AppAccent.fromName(it[APP_ACCENT_KEY]) }

    suspend fun setAppAccent(accent: AppAccent) {
        context.settingsDataStore.edit { it[APP_ACCENT_KEY] = accent.name }
    }

    // --- AI mark appearance (FA-17, from the organic-loaders handoff) ---

    /** Which organic loader animates while the AI is thinking (default 17c · cluster). */
    val aiLoaderStyle: Flow<AiLoaderStyle> = context.settingsDataStore.data
        .map { AiLoaderStyle.fromName(it[AI_LOADER_STYLE_KEY]) }

    suspend fun setAiLoaderStyle(style: AiLoaderStyle) {
        context.settingsDataStore.edit { it[AI_LOADER_STYLE_KEY] = style.name }
    }

    /** Colour treatment for both the loader and the AI logo (Colour / Black). Default Colour. */
    val aiColorMode: Flow<AiColorMode> = context.settingsDataStore.data
        .map { AiColorMode.fromName(it[AI_COLOR_MODE_KEY]) }

    suspend fun setAiColorMode(mode: AiColorMode) {
        context.settingsDataStore.edit { it[AI_COLOR_MODE_KEY] = mode.name }
    }

    /** Unit system the AI must use for measurements in its answers (Metric / Imperial). Default Metric. */
    val unitSystem: Flow<UnitSystem> = context.settingsDataStore.data
        .map { UnitSystem.fromName(it[UNIT_SYSTEM_KEY]) }

    suspend fun setUnitSystem(system: UnitSystem) {
        context.settingsDataStore.edit { it[UNIT_SYSTEM_KEY] = system.name }
    }

    /** The note-canvas paper background (Ruled / Plain / Dots). */
    val paperStyle: Flow<PaperStyle> = context.settingsDataStore.data
        .map { PaperStyle.fromName(it[PAPER_STYLE_KEY]) }

    suspend fun setPaperStyle(style: PaperStyle) {
        context.settingsDataStore.edit { it[PAPER_STYLE_KEY] = style.name }
    }

    /** Default page scroll direction (Vertical-continuous / Horizontal-turn); per-notebook override in the editor (FA-20). */
    val pageNavigationMode: Flow<PageNavigationMode> = context.settingsDataStore.data
        .map { PageNavigationMode.fromName(it[PAGE_NAVIGATION_MODE_KEY]) }

    suspend fun setPageNavigationMode(mode: PageNavigationMode) {
        context.settingsDataStore.edit { it[PAGE_NAVIGATION_MODE_KEY] = mode.name }
    }

    /** Editor note-tab layout: tabs docked atop the tools (Attached) or above the title (Separate). */
    val noteTabsMode: Flow<NoteTabsMode> = context.settingsDataStore.data
        .map { NoteTabsMode.fromName(it[NOTE_TABS_MODE_KEY]) }

    suspend fun setNoteTabsMode(mode: NoteTabsMode) {
        context.settingsDataStore.edit { it[NOTE_TABS_MODE_KEY] = mode.name }
    }

    /** The user's preferred calendar backend (default DEVICE). */
    val calendarProvider: Flow<CalendarProviderType> = context.settingsDataStore.data
        .map { prefs ->
            prefs[CALENDAR_PROVIDER_KEY]
                ?.let { runCatching { CalendarProviderType.valueOf(it) }.getOrNull() }
                ?: CalendarProviderType.DEVICE
        }

    suspend fun setCalendarProvider(type: CalendarProviderType) {
        context.settingsDataStore.edit { it[CALENDAR_PROVIDER_KEY] = type.name }
    }

    /**
     * Whether a freshly created AI response starts in the selected/edit state (move,
     * resize, delete; tap off to place it). When off it lands deselected, in the note
     * flow, and a long-press selects it. Default true.
     */
    val aiNoteSelectedOnCreate: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[AI_NOTE_SELECTED_ON_CREATE_KEY] ?: DEFAULT_AI_NOTE_SELECTED_ON_CREATE }

    suspend fun setAiNoteSelectedOnCreate(enabled: Boolean) {
        context.settingsDataStore.edit { it[AI_NOTE_SELECTED_ON_CREATE_KEY] = enabled }
    }

    // --- Lasso move snap-back (FA-10) ---

    /**
     * Snap-back threshold as a fraction of the canvas size (0–[MAX_LASSO_SNAP_BACK_THRESHOLD]): a
     * lasso move released within this normalised distance of its origin snaps back. 0 disables it.
     */
    val lassoSnapBackThreshold: Flow<Float> = context.settingsDataStore.data
        .map {
            (it[LASSO_SNAPBACK_THRESHOLD_KEY] ?: DEFAULT_LASSO_SNAP_BACK_THRESHOLD)
                .coerceIn(0f, MAX_LASSO_SNAP_BACK_THRESHOLD)
        }

    suspend fun setLassoSnapBackThreshold(value: Float) {
        context.settingsDataStore.edit {
            it[LASSO_SNAPBACK_THRESHOLD_KEY] = value.coerceIn(0f, MAX_LASSO_SNAP_BACK_THRESHOLD)
        }
    }

    /** Whether lasso-move snap-back is on (default true). A 0% threshold also turns it off. */
    val lassoSnapBackEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[LASSO_SNAPBACK_ENABLED_KEY] ?: DEFAULT_LASSO_SNAP_BACK_ENABLED }

    suspend fun setLassoSnapBackEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[LASSO_SNAPBACK_ENABLED_KEY] = enabled }
    }

    // --- Subjects sidebar state (FA-16) ---

    /** Ids of subjects currently expanded in the sidebar tree (persisted across launches). */
    val expandedSubjectIds: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[EXPANDED_SUBJECTS_KEY] ?: emptySet() }

    suspend fun setSubjectExpanded(id: String, expanded: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[EXPANDED_SUBJECTS_KEY] ?: emptySet()
            prefs[EXPANDED_SUBJECTS_KEY] = if (expanded) current + id else current - id
        }
    }

    /** Expands several subjects at once (one write) — used to reveal the path to the current note. */
    suspend fun expandSubjects(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            prefs[EXPANDED_SUBJECTS_KEY] = (prefs[EXPANDED_SUBJECTS_KEY] ?: emptySet()) + ids
        }
    }

    /** The selected subject that filters the notes grid; null = no filter (All Notes). */
    val selectedSubjectId: Flow<String?> = context.settingsDataStore.data
        .map { it[SELECTED_SUBJECT_KEY]?.takeIf { id -> id.isNotEmpty() } }

    suspend fun setSelectedSubjectId(id: String?) {
        context.settingsDataStore.edit {
            if (id == null) it.remove(SELECTED_SUBJECT_KEY) else it[SELECTED_SUBJECT_KEY] = id
        }
    }

    // --- Background auto-extraction (FA-2) ---

    /** Master switch: run TODO/calendar extraction in the background after a note is saved. */
    val autoExtractionEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[AUTO_EXTRACTION_KEY] ?: DEFAULT_TRUE }

    suspend fun setAutoExtractionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_EXTRACTION_KEY] = enabled }
    }

    /** Global confirmation switch: show the on-canvas Yes/No popup for detected items. */
    val extractionConfirmationEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[EXTRACTION_CONFIRM_KEY] ?: DEFAULT_TRUE }

    suspend fun setExtractionConfirmationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[EXTRACTION_CONFIRM_KEY] = enabled }
    }

    /** Per-type confirmation (only meaningful while [extractionConfirmationEnabled]). */
    val confirmTodoExtraction: Flow<Boolean> = context.settingsDataStore.data
        .map { it[CONFIRM_TODO_KEY] ?: DEFAULT_TRUE }

    suspend fun setConfirmTodoExtraction(enabled: Boolean) {
        context.settingsDataStore.edit { it[CONFIRM_TODO_KEY] = enabled }
    }

    val confirmCalendarExtraction: Flow<Boolean> = context.settingsDataStore.data
        .map { it[CONFIRM_CALENDAR_KEY] ?: DEFAULT_TRUE }

    suspend fun setConfirmCalendarExtraction(enabled: Boolean) {
        context.settingsDataStore.edit { it[CONFIRM_CALENDAR_KEY] = enabled }
    }

    /**
     * Set true by the background job when it auto-adds TODO items without a confirmation
     * popup, so the UI can flair the to-do tab; cleared when the user opens the TODO panel.
     */
    val hasNewExtractedItems: Flow<Boolean> = context.settingsDataStore.data
        .map { it[NEW_EXTRACTED_ITEMS_KEY] ?: false }

    suspend fun setHasNewExtractedItems(value: Boolean) {
        context.settingsDataStore.edit { it[NEW_EXTRACTED_ITEMS_KEY] = value }
    }

    companion object {
        const val DEFAULT_TRIGGER = "/Q"
        const val MAX_TRIGGER_LENGTH = 2
        const val DEFAULT_AI_NOTE_SELECTED_ON_CREATE = true
        const val DEFAULT_STYLUS_ONLY = true
        const val DEFAULT_TRUE = true

        /** FA-19 finger gestures: master on; 1×2 Undo, 1×3 Redo, 2×2 last-tool, 2×3 unbound. */
        const val DEFAULT_FINGER_GESTURES_ENABLED = true
        val DEFAULT_TWO_FINGER_TAP_ACTION = FingerGestureAction.UNDO
        val DEFAULT_THREE_FINGER_TAP_ACTION = FingerGestureAction.REDO
        val DEFAULT_TWO_FINGER_DOUBLE_TAP_ACTION = FingerGestureAction.LAST_TOOL_SWAP
        val DEFAULT_THREE_FINGER_DOUBLE_TAP_ACTION = FingerGestureAction.NONE

        /** FA-19 S Pen button: master on; hold→Eraser (momentary), double-click→Lasso, single→none. */
        const val DEFAULT_STYLUS_BUTTON_ENABLED = true
        val DEFAULT_STYLUS_HOLD_TOOL = StylusHoldTool.ERASER
        val DEFAULT_STYLUS_DOUBLE_CLICK_ACTION = FingerGestureAction.SELECT_LASSO
        val DEFAULT_STYLUS_SINGLE_CLICK_ACTION = FingerGestureAction.NONE

        /** Lasso-move snap-back: default 2.5% of the canvas size, capped at 10%; on by default. */
        const val DEFAULT_LASSO_SNAP_BACK_THRESHOLD = 0.025f
        const val MAX_LASSO_SNAP_BACK_THRESHOLD = 0.10f
        const val DEFAULT_LASSO_SNAP_BACK_ENABLED = true

        /** Prefix-mode inactivity delay: default 0.5s, clamped to 0.2–3.0s. */
        const val DEFAULT_PREFIX_TRIGGER_DELAY_MS = 500L
        const val MIN_PREFIX_TRIGGER_DELAY_MS = 200L
        const val MAX_PREFIX_TRIGGER_DELAY_MS = 3_000L

        /** Prefix-mode no-prompt timeout: default 2s, clamped to 1–10s. */
        const val DEFAULT_PREFIX_NO_PROMPT_TIMEOUT_MS = 2_000L
        const val MIN_PREFIX_NO_PROMPT_TIMEOUT_MS = 1_000L
        const val MAX_PREFIX_NO_PROMPT_TIMEOUT_MS = 10_000L
        private val TRIGGER_KEY = stringPreferencesKey("trigger_command")
        private val TRIGGER_MODE_KEY = stringPreferencesKey("trigger_mode")
        private val PREFIX_TRIGGER_DELAY_KEY = longPreferencesKey("prefix_trigger_delay_ms")
        private val PREFIX_NO_PROMPT_TIMEOUT_KEY = longPreferencesKey("prefix_no_prompt_timeout_ms")
        private val STYLUS_ONLY_KEY = booleanPreferencesKey("stylus_only")
        private val FINGER_GESTURES_ENABLED_KEY = booleanPreferencesKey("finger_gestures_enabled")
        private val TWO_FINGER_TAP_KEY = stringPreferencesKey("two_finger_tap_action")
        private val THREE_FINGER_TAP_KEY = stringPreferencesKey("three_finger_tap_action")
        private val TWO_FINGER_DOUBLE_TAP_KEY = stringPreferencesKey("two_finger_double_tap_action")
        private val THREE_FINGER_DOUBLE_TAP_KEY = stringPreferencesKey("three_finger_double_tap_action")
        private val STYLUS_BUTTON_ENABLED_KEY = booleanPreferencesKey("stylus_button_enabled")
        private val STYLUS_HOLD_TOOL_KEY = stringPreferencesKey("stylus_hold_tool")
        private val STYLUS_DOUBLE_CLICK_KEY = stringPreferencesKey("stylus_double_click_action")
        private val STYLUS_SINGLE_CLICK_KEY = stringPreferencesKey("stylus_single_click_action")
        private val PEN_COLOR_KEY = stringPreferencesKey("pen_color")
        private val PEN_LINE_TYPE_KEY = stringPreferencesKey("pen_line_type")
        private val HIGHLIGHTER_COLOR_KEY = stringPreferencesKey("highlighter_color")
        private val HIGHLIGHTER_WIDTH_KEY = stringPreferencesKey("highlighter_width")
        private val PENCIL_LINE_TYPE_KEY = stringPreferencesKey("pencil_line_type")
        private val PENCIL_LEAD_KEY = stringPreferencesKey("pencil_lead")
        private val TOOL_TREATMENT_KEY = stringPreferencesKey("tool_selected_treatment")
        private val PEN_ICON_STYLE_KEY = stringPreferencesKey("pen_icon_style")
        private val AI_LOADER_STYLE_KEY = stringPreferencesKey("ai_loader_style")
        private val AI_COLOR_MODE_KEY = stringPreferencesKey("ai_color_mode")
        private val UNIT_SYSTEM_KEY = stringPreferencesKey("unit_system")
        private val APP_ACCENT_KEY = stringPreferencesKey("app_accent")
        private val PAPER_STYLE_KEY = stringPreferencesKey("paper_style")
        private val PAGE_NAVIGATION_MODE_KEY = stringPreferencesKey("page_navigation_mode")
        private val NOTE_TABS_MODE_KEY = stringPreferencesKey("note_tabs_mode")
        private val CALENDAR_PROVIDER_KEY = stringPreferencesKey("calendar_provider")
        private val AI_NOTE_SELECTED_ON_CREATE_KEY = booleanPreferencesKey("ai_note_selected_on_create")
        private val LASSO_SNAPBACK_THRESHOLD_KEY = floatPreferencesKey("lasso_snapback_threshold")
        private val LASSO_SNAPBACK_ENABLED_KEY = booleanPreferencesKey("lasso_snapback_enabled")
        private val AUTO_EXTRACTION_KEY = booleanPreferencesKey("auto_extraction_enabled")
        private val EXTRACTION_CONFIRM_KEY = booleanPreferencesKey("extraction_confirmation_enabled")
        private val CONFIRM_TODO_KEY = booleanPreferencesKey("confirm_todo_extraction")
        private val CONFIRM_CALENDAR_KEY = booleanPreferencesKey("confirm_calendar_extraction")
        private val NEW_EXTRACTED_ITEMS_KEY = booleanPreferencesKey("has_new_extracted_items")
        private val EXPANDED_SUBJECTS_KEY = stringSetPreferencesKey("expanded_subject_ids")
        private val SELECTED_SUBJECT_KEY = stringPreferencesKey("selected_subject_id")
    }
}
