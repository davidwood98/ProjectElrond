# Claude Code Prompt: Subjects (Folder Hierarchy) Feature

## What to Build

Implement a hierarchical folder/subject system for organizing notes in Project Elrond.

### Core Features

**Sidebar (Home Library):**
- Hierarchical tree of subjects (folder structure) with expand/collapse
- Each subject has a random pastel colour dot (~66 colours, full spectrum with shade levels)
- Inline **+** button to create new child subject (auto-opens keyboard)
- Long-press menu: rename, delete, add subfolder, change colour
- Drag-to-reorder subjects within the same parent level
- Single-tap colour dot to open colour picker
- Clicking a subject filters the main notes grid to show only direct notes in that subject

**Note Cards:**
- Add small dropdown menu (chevron-down or more_vert) to note card titles → rename, delete, assign to subject
- Show subject breadcrumb on notes: if note is in multiple subjects at different hierarchy levels, display coloured dots left-to-right, then deepest subject as a pill with name
  - Example: `[red dot] [blue dot] [green pill: Subject3]`
- Pill is tappable → navigates to that subject in sidebar
- When viewing a specific subject, show breadcrumb tabs at top: `All Notes > Subject1 > Subject2` (all tappable)

**Quick Nav Overlay (on canvas):**
- Rename "Library" to "Quick Nav"
- Rename "Notes" header to "Unfiled"
- Subjects are read-only here (expand/collapse only, no editing)
- Only notes can be selected/edited

**Canvas Tabs:**
- Change from showing "Recents" (last 24h) to showing notes opened **in current session only**
- Session = app foreground lifetime; reset when app closes/backgrounds

---

## Important Project Context

**Architecture:**
- Follow strict **by-layer** structure: UI → ViewModel → Repository → Room/Domain
- Package layout: `ai.elrond.ui`, `ai.elrond.presentation`, `ai.elrond.domain`, `ai.elrond.data`, `ai.elrond.di`
- Use **Hilt** for DI (all ViewModels `@HiltViewModel`, repositories `@Singleton`)
- All pure logic must be **JVM-testable** (no Android in domain layer)

**Database:**
- Current version: **v9** → upgrade to **v10** with new `subjects` table (id, parentId, name, colorId, createdAt, modifiedAt) and `note_subjects` junction table (pageId, subjectId)
- Create `MIGRATION_9_10` and update `ElrondMigrationTest`
- Use Room DAOs with Flow-based queries

**Testing:**
- JVM unit tests (Robolectric): all data layer, repository logic, ViewModels, pure domain functions
- Instrumented tests: key Compose interactions (sidebar expand/collapse, colour picker, pill tapping)
- All tests must pass: `./gradlew test` (JVM), `:app:testDebugUnitTest`, `:aibackend:test`

**UI:**
- Use **Leap design system** (already in place—respect existing colours, typography, spacing)
- State management via `Flow` + `StateFlow` (observable from ViewModels)
- Sidebar: use existing `LibraryScreen` structure; integrate as a new tree component
- Colour picker: modal/popup with pastel swatch grid
- Context menu: long-press reveals options (use ModalBottomSheet or Popup)

**Settings Persistence:**
- Store sidebar state (expanded subjects, selected subject) in **DataStore** (not SharedPreferences)
- Session notes list: in-memory only (no persistence)

---

## Acceptance Criteria

- ✅ Subject hierarchy displays in sidebar with full CRUD (create, rename, delete, reorder, change colour)
- ✅ Notes can be assigned to multiple subjects; breadcrumb displays correctly on cards
- ✅ Clicking a subject filters notes; tab bar shows breadcrumb path (all clickable)
- ✅ Quick Nav is read-only for subjects; canvas tabs show session-only notes
- ✅ Database migration v9→v10 passes; all tests green
- ✅ No breaking changes to existing features (notes, calendar, todos, canvas, etc.)

---

## Known Unknowns (Confirm with User)

1. **Cascade delete:** When deleting a subject with children, should we delete children, promote them, or prevent deletion?
2. **Note in multiple subject branches:** Can a note be in both "Subject1" and "Subject1/Subject2"?
3. **Colour palette:** Use Material Design pastels or custom Leap-branded colours?

---

## General Notes

- Respect the project's existing conventions (see CLAUDE.md: by-layer structure, testing approach, design system)
- This is a tablet-first app (Samsung Galaxy Tab S with S Pen) — ensure UI works across landscape/portrait and 7–13" screens
- The project uses **Jetpack Compose** for all UI; no XML layouts
- All changes should be backward-compatible; don't break existing features
