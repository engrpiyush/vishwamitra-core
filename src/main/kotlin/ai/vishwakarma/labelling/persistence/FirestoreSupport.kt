package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Stamp
import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import java.time.Instant
import java.time.LocalDate

/** Block on a Firestore ApiFuture (the client is used synchronously throughout). */
fun <T> ApiFuture<T>.await(): T = this.get()

/** Convert an [Instant] to a Firestore [Timestamp], or null. */
fun Instant?.toTimestamp(): Timestamp? =
    this?.let { Timestamp.ofTimeSecondsAndNanos(it.epochSecond, it.nano) }

/** Read a Firestore timestamp field as an [Instant], or null. */
fun DocumentSnapshot.instant(field: String): Instant? =
    getTimestamp(field)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }

/**
 * Serialize a date-only value as an ISO-8601 string ("yyyy-MM-dd"), or null. Stored as text (not a
 * Timestamp) so a calendar date carries no time-zone baggage.
 */
fun LocalDate?.toIsoDate(): String? = this?.toString()

/** Read an ISO date string field back as a [LocalDate], tolerating missing/garbage values. */
fun DocumentSnapshot.localDate(field: String): LocalDate? =
    getString(field)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Serialize [ExampleTags] to a Firestore map (enums as names; labels as a string list). */
fun ExampleTags.toTagMap(): Map<String, Any?> =
    mapOf(
        "claimType" to claimType?.name,
        "authenticityTier" to authenticityTier?.name,
        "labels" to labels,
        "hasToolCall" to hasToolCall,
    )

/** Read [ExampleTags] from a Firestore tag map, tolerating missing/legacy fields. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.toExampleTags(): ExampleTags =
    ExampleTags(
        claimType =
            (this["claimType"] as? String)?.let {
                runCatching { ClaimType.valueOf(it) }.getOrNull()
            },
        authenticityTier =
            (this["authenticityTier"] as? String)?.let {
                runCatching { AuthenticityTier.valueOf(it) }.getOrNull()
            },
        labels = (this["labels"] as? List<String>) ?: emptyList(),
        hasToolCall = this["hasToolCall"] as? Boolean ?: false,
    )

/** Serialize the Stage 4 traceability stamp (§6) to a Firestore map. */
fun Stage4Stamp.toStampMap(): Map<String, Any?> =
    mapOf(
        "subjectId" to subjectId,
        "sourceClaimIds" to sourceClaimIds,
        "scoreRunId" to scoreRunId,
        "category" to category?.name,
        "planId" to planId,
        "personaHash" to personaHash,
        "generatorPromptHash" to generatorPromptHash,
        "templateId" to templateId,
        "templateCategory" to templateCategory,
    )

/** Read a [Stage4Stamp] from a Firestore map; null when there is no subjectId (legacy example). */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.toStage4Stamp(): Stage4Stamp? {
    val subjectId = (this["subjectId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    return Stage4Stamp(
        subjectId = subjectId,
        sourceClaimIds = (this["sourceClaimIds"] as? List<String>) ?: emptyList(),
        scoreRunId = this["scoreRunId"] as? String,
        category = Stage4Category.fromOrNull(this["category"] as? String),
        planId = this["planId"] as? String,
        personaHash = this["personaHash"] as? String,
        generatorPromptHash = this["generatorPromptHash"] as? String,
        templateId = this["templateId"] as? String,
        templateCategory = this["templateCategory"] as? String,
    )
}
