package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.User
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `users` collection — email→role allowlist. Document id = email. */
@Repository
class UserRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findByEmail(email: String): User? =
        col.document(email).get().await().takeIf { it.exists() }?.toUser()

    fun findAll(): List<User> =
        col.get().await().documents.map { it.toUser() }.sortedBy { it.email }

    fun upsert(user: User) {
        col.document(user.email).set(user.toMap()).await()
    }

    fun delete(email: String) {
        col.document(email).delete().await()
    }

    private fun User.toMap(): Map<String, Any?> =
        mapOf(
            "email" to email,
            "role" to role.name,
            "active" to active,
            "addedBy" to addedBy,
            "addedAt" to (addedAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toUser(): User =
        User(
            email = getString("email") ?: id,
            role = Role.fromOrNull(getString("role")) ?: Role.AUTHOR,
            active = getBoolean("active") ?: true,
            addedBy = getString("addedBy"),
            addedAt = instant("addedAt"),
        )

    companion object {
        const val COLLECTION = "users"
    }
}
