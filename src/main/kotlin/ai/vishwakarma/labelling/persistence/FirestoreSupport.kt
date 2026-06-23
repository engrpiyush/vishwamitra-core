package ai.vishwakarma.labelling.persistence

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import java.time.Instant

/** Block on a Firestore ApiFuture (the client is used synchronously throughout). */
fun <T> ApiFuture<T>.await(): T = this.get()

/** Convert an [Instant] to a Firestore [Timestamp], or null. */
fun Instant?.toTimestamp(): Timestamp? = this?.let { Timestamp.ofTimeSecondsAndNanos(it.epochSecond, it.nano) }

/** Read a Firestore timestamp field as an [Instant], or null. */
fun DocumentSnapshot.instant(field: String): Instant? =
    getTimestamp(field)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
