package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.CompensationPolicy
import ai.vishwakarma.labelling.domain.ContactSharing
import ai.vishwakarma.labelling.domain.CriticismResponse
import ai.vishwakarma.labelling.domain.EndorserAttribution
import ai.vishwakarma.labelling.domain.GapsPolicy
import ai.vishwakarma.labelling.domain.OutOfCorpusPolicy
import ai.vishwakarma.labelling.domain.PersonaPosture
import ai.vishwakarma.labelling.domain.PersonaStance
import ai.vishwakarma.labelling.domain.PersonaVerbosity
import ai.vishwakarma.labelling.domain.PersonaVocabulary
import ai.vishwakarma.labelling.domain.SpeculationPolicy
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.domain.WeaknessEagerness
import ai.vishwakarma.labelling.domain.WeaknessFraming
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `subject_persona` (LLD §6) — one doc per subject, doc id = subjectId. */
@Repository
class SubjectPersonaRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findBySubject(subjectId: String): SubjectPersona? =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toPersona()

    fun save(persona: SubjectPersona) {
        col.document(persona.subjectId).set(persona.toMap()).await()
    }

    fun delete(subjectId: String) {
        col.document(subjectId).delete().await()
    }

    private fun SubjectPersona.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "stance" to stance?.name,
            "advocateName" to advocateName,
            "presetId" to presetId,
            "verbosity" to verbosity?.name,
            "vocabulary" to vocabulary?.name,
            "posture" to posture?.name,
            "endorserAttribution" to endorserAttribution?.name,
            "weaknessEagerness" to weaknessEagerness?.name,
            "weaknessFraming" to weaknessFraming?.name,
            "criticismResponse" to criticismResponse?.name,
            "compensation" to compensation?.name,
            "gaps" to gaps?.name,
            "outOfCorpus" to outOfCorpus?.name,
            "contactSharing" to contactSharing?.name,
            "speculation" to speculation?.name,
            "customText" to customText,
            "skipped" to skipped,
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
            "personaHash" to personaHash,
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toPersona(): SubjectPersona =
        SubjectPersona(
            subjectId = getString("subjectId") ?: id,
            stance = PersonaStance.fromOrNull(getString("stance")),
            advocateName = getString("advocateName"),
            presetId = getString("presetId"),
            verbosity = PersonaVerbosity.fromOrNull(getString("verbosity")),
            vocabulary = PersonaVocabulary.fromOrNull(getString("vocabulary")),
            posture = PersonaPosture.fromOrNull(getString("posture")),
            endorserAttribution = EndorserAttribution.fromOrNull(getString("endorserAttribution")),
            weaknessEagerness = WeaknessEagerness.fromOrNull(getString("weaknessEagerness")),
            weaknessFraming = WeaknessFraming.fromOrNull(getString("weaknessFraming")),
            criticismResponse = CriticismResponse.fromOrNull(getString("criticismResponse")),
            compensation = CompensationPolicy.fromOrNull(getString("compensation")),
            gaps = GapsPolicy.fromOrNull(getString("gaps")),
            outOfCorpus = OutOfCorpusPolicy.fromOrNull(getString("outOfCorpus")),
            contactSharing = ContactSharing.fromOrNull(getString("contactSharing")),
            speculation = SpeculationPolicy.fromOrNull(getString("speculation")),
            customText = getString("customText"),
            skipped = (get("skipped") as? List<String>) ?: emptyList(),
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
            personaHash = getString("personaHash"),
        )

    companion object {
        const val COLLECTION = "subject_persona"
    }
}
