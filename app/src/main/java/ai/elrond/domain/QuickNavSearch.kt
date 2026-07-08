package ai.elrond.domain

/**
 * Pure search over the Quick Nav content (FA-24): filters notebooks by their own title or by
 * their owning subject's name, case-insensitively. Blank query = everything. JVM-testable, no
 * Android/Compose.
 */
object QuickNavSearch {

    fun filterNotebooks(
        query: String,
        subjectTree: List<SubjectNode>,
        notebooksBySubject: Map<String?, List<NotebookSummary>>,
    ): List<NotebookSummary> {
        val q = query.trim()
        if (q.isEmpty()) return notebooksBySubject.values.flatten().distinctBy { it.notebookId }
        val matchingSubjectIds = SubjectTree.flatten(subjectTree)
            .filter { it.subject.name.contains(q, ignoreCase = true) }
            .mapTo(HashSet()) { it.subject.id }
        val bySubject = notebooksBySubject.entries
            .filter { (subjectId, _) -> subjectId != null && subjectId in matchingSubjectIds }
            .flatMap { it.value }
        val byTitle = notebooksBySubject.values.flatten()
            .filter { it.title.contains(q, ignoreCase = true) }
        return (byTitle + bySubject).distinctBy { it.notebookId }
    }
}
