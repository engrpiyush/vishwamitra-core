package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ProviderConfig
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ProviderRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findById(id: String): ProviderConfig? =
        col.document(id).get().await().takeIf { it.exists() }?.toProvider()

    fun findAll(): List<ProviderConfig> =
        col.get().await().documents.map { it.toProvider() }.sortedBy { it.id }

    fun save(provider: ProviderConfig) {
        col.document(provider.id).set(provider.toMap()).await()
    }

    private fun ProviderConfig.toMap(): Map<String, Any?> =
        mapOf(
            "enabled" to enabled,
            "model" to model,
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toProvider(): ProviderConfig =
        ProviderConfig(
            id = id,
            enabled = getBoolean("enabled") ?: false,
            model = getString("model") ?: "",
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "providers"
    }
}
