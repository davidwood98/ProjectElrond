package ai.elrond.domain

/** A subject plus its (already-sorted) children — the tree the sidebar renders. */
data class SubjectNode(
    val subject: Subject,
    val children: List<SubjectNode>,
    val depth: Int,
)

/**
 * Pure subject-tree helpers (no Android/Compose) — the JVM-testable core behind the sidebar tree,
 * the note breadcrumb, and drag-to-reorder.
 */
object SubjectTree {

    /** Sibling order: by [Subject.sortOrder], then name, then createdAt — stable + deterministic. */
    private val SIBLING_ORDER =
        compareBy<Subject>({ it.sortOrder }, { it.name.lowercase() }, { it.createdAt }, { it.id })

    /**
     * Builds the forest of root [SubjectNode]s from a flat list, recursively attaching + sorting
     * children. Orphans (a parentId pointing at a missing/deleted subject) are treated as roots so
     * nothing silently disappears; cycles are broken by a visited guard.
     */
    fun build(subjects: List<Subject>): List<SubjectNode> {
        val byParent = subjects.groupBy { it.parentId }
        val knownIds = subjects.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>() // shared across roots so the cycle rescue below never double-adds
        val result = ArrayList<SubjectNode>()
        // A subject is a root if it has no parent, or its parent no longer exists (orphan rescue).
        subjects.filter { it.parentId == null || it.parentId !in knownIds }
            .sortedWith(SIBLING_ORDER)
            .forEach { result.add(node(it, byParent, depth = 0, seen = seen)) }
        // Rescue any subject unreachable from a root (only possible via a parentId cycle) as a root,
        // so a pathological cycle never makes subjects silently vanish from the tree. The `!in seen`
        // guard is load-bearing: node(...) marks descendants seen as it recurses, so a later element
        // may already be covered by an earlier rescue.
        subjects.sortedWith(SIBLING_ORDER)
            .forEach { if (it.id !in seen) result.add(node(it, byParent, depth = 0, seen = seen)) }
        return result
    }

    private fun node(
        subject: Subject,
        byParent: Map<String?, List<Subject>>,
        depth: Int,
        seen: MutableSet<String>,
    ): SubjectNode {
        seen.add(subject.id)
        val children = byParent[subject.id].orEmpty()
            .filter { it.id !in seen } // guard against a parentId cycle
            .sortedWith(SIBLING_ORDER)
            .map { node(it, byParent, depth + 1, seen) }
        return SubjectNode(subject, children, depth)
    }

    /**
     * The ancestry path from the root down to [subjectId] (inclusive) — the order the breadcrumb
     * renders (ancestors as dots, the last element as the named pill). Empty if [subjectId] is null
     * or unknown. Cycle-safe.
     */
    fun pathTo(subjectId: String?, byId: Map<String, Subject>): List<Subject> {
        if (subjectId == null) return emptyList()
        val chain = ArrayList<Subject>()
        val seen = HashSet<String>()
        var current = byId[subjectId]
        while (current != null && seen.add(current.id)) {
            chain.add(current)
            current = current.parentId?.let { byId[it] }
        }
        return chain.asReversed()
    }

    /** Pre-order flatten of the forest (parents before their children); keeps each node's depth. */
    fun flatten(nodes: List<SubjectNode>): List<SubjectNode> = buildList {
        fun visit(list: List<SubjectNode>) {
            list.forEach { node ->
                add(node)
                visit(node.children)
            }
        }
        visit(nodes)
    }

    /**
     * Reorders [siblings] (same-parent subjects) by moving [movedId] to [targetIndex], returning the
     * new sibling order. The caller persists by reassigning each result's [Subject.sortOrder] to its
     * position. Out-of-range targets clamp; an unknown id leaves the order unchanged.
     */
    fun reorder(siblings: List<Subject>, movedId: String, targetIndex: Int): List<Subject> {
        val ordered = siblings.sortedWith(SIBLING_ORDER).toMutableList()
        val from = ordered.indexOfFirst { it.id == movedId }
        if (from < 0) return ordered
        val to = targetIndex.coerceIn(0, ordered.lastIndex)
        if (from == to) return ordered
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        return ordered
    }

    /**
     * Moves [movedId] one position up or down among its [siblings] (clamped at the ends), returning
     * the new order. Direction-based so the caller needs no pixel/index maths — drag-to-reorder maps a
     * drag's direction to a single step.
     */
    fun move(siblings: List<Subject>, movedId: String, up: Boolean): List<Subject> {
        val ordered = siblings.sortedWith(SIBLING_ORDER)
        val from = ordered.indexOfFirst { it.id == movedId }
        if (from < 0) return ordered
        val to = (from + if (up) -1 else 1).coerceIn(0, ordered.lastIndex)
        return reorder(siblings, movedId, to)
    }
}
