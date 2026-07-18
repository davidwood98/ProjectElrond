package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit tests for the subject-tree helpers (build / pathTo / flatten / reorder). */
class SubjectTreeTest {

    private fun subject(
        id: String,
        parentId: String? = null,
        sortOrder: Long = 0L,
        name: String = id,
    ) = Subject(id = id, parentId = parentId, name = name, colorId = 0, sortOrder = sortOrder, createdAt = 0, modifiedAt = 0)

    @Test
    fun `build nests children under parents and sorts siblings by sortOrder`() {
        val subjects = listOf(
            subject("b", sortOrder = 1),
            subject("a", sortOrder = 0),
            subject("a2", parentId = "a", sortOrder = 1),
            subject("a1", parentId = "a", sortOrder = 0),
        )
        val tree = SubjectTree.build(subjects)

        assertEquals(listOf("a", "b"), tree.map { it.subject.id })
        assertEquals(listOf("a1", "a2"), tree[0].children.map { it.subject.id })
        assertEquals(0, tree[0].depth)
        assertEquals(1, tree[0].children[0].depth)
        assertEquals(emptyList<String>(), tree[1].children.map { it.subject.id })
    }

    @Test
    fun `build treats a subject with a missing parent as a root (orphan rescue)`() {
        val tree = SubjectTree.build(listOf(subject("orphan", parentId = "ghost")))
        assertEquals(listOf("orphan"), tree.map { it.subject.id })
    }

    @Test
    fun `build does not loop on a parentId cycle`() {
        // a → b → a. Neither has a "real" root, so both are rescued as roots; the recursion must stop.
        val tree = SubjectTree.build(listOf(subject("a", parentId = "b"), subject("b", parentId = "a")))
        assertEquals(2, SubjectTree.flatten(tree).size) // each appears once, no infinite recursion
    }

    @Test
    fun `pathTo returns the root-to-leaf ancestry chain`() {
        val byId = listOf(
            subject("s1"),
            subject("s2", parentId = "s1"),
            subject("s3", parentId = "s2"),
        ).associateBy { it.id }

        assertEquals(listOf("s1", "s2", "s3"), SubjectTree.pathTo("s3", byId).map { it.id })
        assertEquals(listOf("s1"), SubjectTree.pathTo("s1", byId).map { it.id })
        assertEquals(emptyList<String>(), SubjectTree.pathTo(null, byId).map { it.id })
        assertEquals(emptyList<String>(), SubjectTree.pathTo("missing", byId).map { it.id })
    }

    @Test
    fun `flatten is pre-order (parent before children)`() {
        val tree = SubjectTree.build(
            listOf(
                subject("a", sortOrder = 0),
                subject("a1", parentId = "a", sortOrder = 0),
                subject("b", sortOrder = 1),
            ),
        )
        assertEquals(listOf("a", "a1", "b"), SubjectTree.flatten(tree).map { it.subject.id })
    }

    @Test
    fun `reorder moves an item and clamps an out-of-range target`() {
        val siblings = listOf(
            subject("a", sortOrder = 0),
            subject("b", sortOrder = 1),
            subject("c", sortOrder = 2),
        )
        assertEquals(listOf("b", "c", "a"), SubjectTree.reorder(siblings, "a", 2).map { it.id })
        assertEquals(listOf("c", "a", "b"), SubjectTree.reorder(siblings, "c", 0).map { it.id })
        // Out-of-range target clamps to the last index.
        assertEquals(listOf("b", "c", "a"), SubjectTree.reorder(siblings, "a", 99).map { it.id })
    }

    @Test
    fun `reorder leaves the order unchanged for an unknown id or a no-op move`() {
        val siblings = listOf(subject("a", sortOrder = 0), subject("b", sortOrder = 1))
        assertEquals(listOf("a", "b"), SubjectTree.reorder(siblings, "ghost", 1).map { it.id })
        assertEquals(listOf("a", "b"), SubjectTree.reorder(siblings, "a", 0).map { it.id })
    }

    @Test
    fun `move steps an item up or down one position and clamps at the ends`() {
        val siblings = listOf(
            subject("a", sortOrder = 0),
            subject("b", sortOrder = 1),
            subject("c", sortOrder = 2),
        )
        assertEquals(listOf("a", "c", "b"), SubjectTree.move(siblings, "c", up = true).map { it.id })
        assertEquals(listOf("b", "a", "c"), SubjectTree.move(siblings, "a", up = false).map { it.id })
        // Clamped at the top — already first, moving up is a no-op.
        assertEquals(listOf("a", "b", "c"), SubjectTree.move(siblings, "a", up = true).map { it.id })
        // Clamped at the bottom.
        assertEquals(listOf("a", "b", "c"), SubjectTree.move(siblings, "c", up = false).map { it.id })
    }

    // --- FA-24c search scoping: rootAncestorId + descendantsOf ---

    private fun byId(vararg subjects: Subject) = subjects.associateBy { it.id }

    @Test
    fun `descendantsOf a leaf subject is itself only`() {
        val map = byId(subject("a"))
        assertEquals(setOf("a"), SubjectTree.descendantsOf("a", map))
    }

    @Test
    fun `descendantsOf a multi-level subtree is inclusive of every level`() {
        // a > b > c ; a > d
        val map = byId(
            subject("a"),
            subject("b", parentId = "a"),
            subject("c", parentId = "b"),
            subject("d", parentId = "a"),
            subject("z"), // unrelated sibling tree — must not leak in
        )
        assertEquals(setOf("a", "b", "c", "d"), SubjectTree.descendantsOf("a", map))
        assertEquals(setOf("b", "c"), SubjectTree.descendantsOf("b", map))
    }

    @Test
    fun `descendantsOf a null or unknown subject is empty`() {
        val map = byId(subject("a"))
        assertEquals(emptySet<String>(), SubjectTree.descendantsOf(null, map))
        assertEquals(emptySet<String>(), SubjectTree.descendantsOf("ghost", map))
    }

    @Test
    fun `rootAncestorId walks up to the top of the tree from any depth`() {
        // a > b > c
        val map = byId(
            subject("a"),
            subject("b", parentId = "a"),
            subject("c", parentId = "b"),
        )
        assertEquals("a", SubjectTree.rootAncestorId("c", map))
        assertEquals("a", SubjectTree.rootAncestorId("b", map))
        assertEquals("a", SubjectTree.rootAncestorId("a", map))
        assertEquals(null, SubjectTree.rootAncestorId("ghost", map))
    }

    @Test
    fun `search scope from deep in a tree covers the whole tree from its root`() {
        // The FA-24c rule: searching while inside a>b>c scopes to all of a's tree.
        val map = byId(
            subject("a"),
            subject("b", parentId = "a"),
            subject("c", parentId = "b"),
            subject("d", parentId = "a"),
        )
        val scope = SubjectTree.descendantsOf(SubjectTree.rootAncestorId("c", map), map)
        assertEquals(setOf("a", "b", "c", "d"), scope)
    }
}
