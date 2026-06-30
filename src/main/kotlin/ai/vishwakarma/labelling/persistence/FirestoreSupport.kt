package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ExampleTags
import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import java.time.Instant

/** Block on a Firestore ApiFuture (the client is used synchronously throughout). */
fun <T> ApiFuture<T>.await(): T = this.get()

/** Convert an [Instant] to a Firestore [Timestamp], or null. */
fun Instant?.toTimestamp(): Timestamp? =
    this?.let { Timestamp.ofTimeSecondsAndNanos(it.epochSecond, it.nano) }

/** Read a Firestore timestamp field as an [Instant], or null. */
fun DocumentSnapshot.instant(field: String): Instant? =
    getTimestamp(field)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }

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
