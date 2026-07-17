package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.QuestionStatus
import ai.vishwakarma.labelling.domain.QuestionTrigger
import ai.vishwakarma.labelling.domain.SubjectQuestion
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

/**
 * `subject_questions` collection (LLD §5.1, F11). The status filter uses the `(subjectId, status)`
 * composite index — see `firestore.indexes.json` at the repo root.
 */
@Repository
class SubjectQuestionRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun find(id: String): SubjectQuestion? =
        col.document(id).get().await().takeIf { it.exists() }?.toQuestion()

    fun listBySubject(subjectId: String, status: QuestionStatus? = null): List<SubjectQuestion> {
        var query = col.whereEqualTo("subjectId", subjectId)
        status?.let { query = query.whereEqualTo("status", it.name) }
        return query
            .get()
            .await()
            .documents
            .map { it.toQuestion() }
            .sortedByDescending { it.askedAt }
    }

    fun save(question: SubjectQuestion) {
        col.document(question.id).set(question.toMap()).await()
    }

    /**
     * The §9.4 answer atomicity: the SIDECARED `claim_reviews` row(s) and the ANSWERED flip commit
     * in one transaction — an answer can never exist without its sidecar or vice versa. The review
     * docs arrive pre-mapped ([ClaimReviewRepository.docRef]/`docData`) so each collection's
     * serialization stays in its own repository.
     */
    fun saveAnswered(
        question: SubjectQuestion,
        reviewDocs: List<Pair<DocumentReference, Map<String, Any?>>>,
    ) {
        db.runTransaction { tx ->
                reviewDocs.forEach { (ref, data) -> tx.set(ref, data) }
                tx.set(col.document(question.id), question.toMap())
            }
            .await()
    }

    private fun SubjectQuestion.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "trigger" to trigger.name,
            "claimIds" to claimIds,
            "edgeId" to edgeId,
            "questionText" to questionText,
            "status" to status.name,
            "answer" to answer,
            "askedAt" to askedAt.toTimestamp(),
            "answeredAt" to answeredAt.toTimestamp(),
            "generatedBy" to generatedBy,
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toQuestion(): SubjectQuestion =
        SubjectQuestion(
            id = id,
            subjectId = getString("subjectId") ?: "",
            trigger =
                QuestionTrigger.fromOrNull(getString("trigger")) ?: QuestionTrigger.UNFAVORABLE,
            claimIds = (get("claimIds") as? List<String>) ?: emptyList(),
            edgeId = getString("edgeId"),
            questionText = getString("questionText") ?: "",
            status = QuestionStatus.fromOrNull(getString("status")) ?: QuestionStatus.OPEN,
            answer = getString("answer"),
            askedAt = instant("askedAt"),
            answeredAt = instant("answeredAt"),
            generatedBy = getString("generatedBy"),
        )

    companion object {
        const val COLLECTION = "subject_questions"
    }
}
