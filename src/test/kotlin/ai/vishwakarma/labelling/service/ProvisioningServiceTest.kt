package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.ServingState
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.domain.WindowPreset
import ai.vishwakarma.labelling.gcs.CheckpointLocator
import ai.vishwakarma.labelling.gcs.ServingStager
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.UserRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class ProvAdvocateRepo : AdvocateRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Advocate>()

    override fun find(subjectId: String): Advocate? = store[subjectId]

    override fun findAll(): List<Advocate> = store.values.toList()

    override fun save(advocate: Advocate) {
        store[advocate.subjectId] = advocate
    }
}

private class ProvSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]

    override fun findAll(): List<Subject> = store.values.toList()
}

private class ProvVersionRepo : ModelVersionRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ModelVersion>()

    override fun findById(id: String): ModelVersion? = store[id]

    override fun findAll(): List<ModelVersion> = store.values.toList()

    override fun save(version: ModelVersion) {
        store[version.id] = version
    }
}

private class ProvUserRepo : UserRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<User>()

    override fun findAll(): List<User> = store
}

private class ProvBookkeeping : MailBookkeepingRepository(mock(Firestore::class.java)) {
    override fun countSendIfBelow(date: String, cap: Int): Boolean = true
}

private class ProvTransport : MailTransport {
    val sent = mutableListOf<Pair<String, String>>()

    override fun send(from: String, to: String, subject: String, body: String) {
        sent += to to subject
    }
}

/**
 * Scriptable [ai.vishwakarma.labelling.serving.ServingBackend] double: the deterministic two-step
 * progression from AdvocateServingServiceTest, plus scriptable [deployed] substrate truth for the
 * sweep-reconcile tests and a [tornDown] record of orphan teardowns.
 */
private class ProvBackend(
    override val id: String = "fake",
    val failServe: Boolean = false,
    val hold: Boolean = false,
) : ai.vishwakarma.labelling.serving.ServingBackend {
    var deployed: List<ai.vishwakarma.labelling.serving.Deployment> = emptyList()
    val tornDown = mutableListOf<String>()

    override fun target(): String = "ep-1"

    override fun beginServe(
        req: ai.vishwakarma.labelling.serving.ServeRequest
    ): ai.vishwakarma.labelling.serving.ServingHandle =
        if (failServe)
            ai.vishwakarma.labelling.serving.ServingHandle(ServingState.FAILED, error = "boom")
        else
            ai.vishwakarma.labelling.serving.ServingHandle(
                ServingState.DEPLOYING,
                endpointId = "ep-1",
                operation = "op-upload",
            )

    override fun advance(
        handle: ai.vishwakarma.labelling.serving.ServingHandle
    ): ai.vishwakarma.labelling.serving.ServingHandle =
        when {
            hold -> handle
            handle.state == ServingState.DEPLOYING && handle.operation == "op-upload" ->
                handle.copy(modelResource = "model-1", operation = "op-deploy")
            handle.state == ServingState.DEPLOYING && handle.operation == "op-deploy" ->
                handle.copy(state = ServingState.LIVE, deployedModelId = "dm-1", operation = null)
            handle.state == ServingState.TEARING_DOWN ->
                ai.vishwakarma.labelling.serving.ServingHandle(ServingState.NONE)
            else -> handle
        }

    override fun beginTeardown(
        handle: ai.vishwakarma.labelling.serving.ServingHandle
    ): ai.vishwakarma.labelling.serving.ServingHandle {
        handle.deployedModelId?.let { tornDown += it }
        return handle.copy(state = ServingState.TEARING_DOWN, operation = "op-undeploy")
    }

    override fun deployments(): List<ai.vishwakarma.labelling.serving.Deployment> = deployed

    override fun chat(req: ai.vishwakarma.labelling.serving.ChatRequest): String =
        error("not under test")
}

class ProvisioningServiceTest {

    private val advocates = ProvAdvocateRepo()
    private val subjects = ProvSubjectRepo()
    private val versions = ProvVersionRepo()
    private val users = ProvUserRepo()
    private val transport = ProvTransport()

    private fun props(
        enabled: Boolean = true,
        dryRun: Boolean = false,
        deployTimeout: Duration = Duration.ofMinutes(60),
    ) =
        AppProperties(
            // Blank buckets keep CheckpointLocator/ServingStager offline (validate-true, no copy).
            gcp = AppProperties.Gcp(servingBucket = ""),
            product = AppProperties.Product(baseDomain = "localhost", operatorDomain = "localhost"),
            serving =
                AppProperties.Serving(
                    enabled = enabled,
                    dryRun = dryRun,
                    backend = "fake",
                    endpointId = "ep-1",
                    image = "img",
                    deployTimeout = deployTimeout,
                ),
        )

    private fun service(
        backend: ProvBackend = ProvBackend(),
        enabled: Boolean = true,
        dryRun: Boolean = false,
        deployTimeout: Duration = Duration.ofMinutes(60),
    ): ProvisioningService {
        val p = props(enabled, dryRun, deployTimeout)
        return ProvisioningService(
            advocates = advocates,
            subjects = subjects,
            versions = versions,
            backends = listOf(backend),
            locator = CheckpointLocator(p),
            stager = ServingStager(p),
            users = UserService(users),
            mail = MailService(transport, ProvBookkeeping(), p),
            links = ProductLinks(p, 8090),
            props = p,
        )
    }

    private fun seedSubject(id: String = "subj-1", handle: String? = "dev") {
        subjects.store[id] = Subject(id = id, displayName = "Neo", handle = handle)
        users.store += User(email = "$id@x.com", role = Role.SUBJECT, subjectId = id)
    }

    private fun seedAdvocate(
        id: String = "subj-1",
        state: AdvocateState = AdvocateState.UNPROVISIONED,
        modelUri: String? = "gs://staging/advocates/subj-1/model/",
        deployedModelId: String? = null,
        windowEndsAt: Instant? = null,
        provisioningStartedAt: Instant? = null,
    ): Advocate {
        val adv =
            Advocate(
                subjectId = id,
                state = state,
                modelUri = modelUri,
                servingDeployedModelId = deployedModelId,
                windowEndsAt = windowEndsAt,
                provisioningStartedAt = provisioningStartedAt,
            )
        advocates.store[id] = adv
        return adv
    }

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ error("expected Right, got Left: ${it.message}") }, { it })

    private fun <T> Either<DomainError, T>.err(): DomainError? = fold({ it }, { null })

    // ---- register -----------------------------------------------------------------

    @Test
    fun `register validates subject, handle and URI, then writes UNPROVISIONED`() {
        assertIs<DomainError.NotFound>(service().register("nope", "gs://b/p/", "op").err())

        seedSubject("subj-2", handle = null)
        assertIs<DomainError.Invalid>(service().register("subj-2", "gs://b/p/", "op").err())

        seedSubject()
        assertIs<DomainError.Invalid>(service().register("subj-1", "s3://b/p/", "op").err())

        val adv = service().register("subj-1", "gs://bucket/tuned/final/", "op").expectRight()
        assertEquals(AdvocateState.UNPROVISIONED, adv.state)
        // No staging bucket in tests — the source URI is used as-is.
        assertEquals("gs://bucket/tuned/final/", adv.modelUri)
    }

    @Test
    fun `register refuses while a window is in progress and resets a failed one`() {
        seedSubject()
        seedAdvocate(state = AdvocateState.LIVE)
        assertIs<DomainError.Conflict>(service().register("subj-1", "gs://b/p/", "op").err())

        seedAdvocate(state = AdvocateState.DEPLOY_FAILED)
        advocates.store["subj-1"] = advocates.store["subj-1"]!!.copy(lastError = "old failure")
        val adv = service().register("subj-1", "gs://b/new/", "op").expectRight()
        assertEquals(AdvocateState.UNPROVISIONED, adv.state)
        assertNull(adv.lastError)
        assertEquals("gs://b/new/", adv.modelUri)
    }

    // ---- startWindow guards ---------------------------------------------------------

    @Test
    fun `startWindow guards - disabled, missing, wrong state, no model`() {
        seedSubject()
        seedAdvocate()
        assertIs<DomainError.Conflict>(
            service(enabled = false).startWindow("subj-1", WindowPreset.D1, "s").err()
        )
        assertIs<DomainError.NotFound>(service().startWindow("nope", WindowPreset.D1, "s").err())

        seedAdvocate(state = AdvocateState.LIVE)
        assertIs<DomainError.Conflict>(service().startWindow("subj-1", WindowPreset.D1, "s").err())

        seedAdvocate(modelUri = null)
        assertIs<DomainError.Invalid>(service().startWindow("subj-1", WindowPreset.D1, "s").err())
    }

    @Test
    fun `one occupant across both worlds - advocate and operator serve exclude each other`() {
        seedSubject()
        seedSubject("subj-2")
        seedAdvocate()
        seedAdvocate("subj-2", state = AdvocateState.PROVISIONING)
        val err = service().startWindow("subj-1", WindowPreset.D1, "s").err()
        assertIs<DomainError.Conflict>(err)
        assertTrue(err.message.contains("only one"))

        advocates.store.remove("subj-2")
        versions.store["v1"] =
            ModelVersion(
                id = "v1",
                baseModelId = "b",
                family = "f",
                version = "v1.0",
                method = TuningMethod.SFT,
                baseKind = BaseKind.FOUNDATION,
                status = VersionStatus.READY,
                servingState = ServingState.LIVE,
            )
        assertIs<DomainError.Conflict>(service().startWindow("subj-1", WindowPreset.D1, "s").err())
    }

    // ---- dry-run ---------------------------------------------------------------------

    @Test
    fun `dry-run window goes straight LIVE with the preset end and mails the subject`() {
        seedSubject()
        seedAdvocate()
        val before = Instant.now()
        val live = service(dryRun = true).startWindow("subj-1", WindowPreset.D3, "s").expectRight()
        assertEquals(AdvocateState.LIVE, live.state)
        assertEquals(WindowPreset.D3, live.windowPreset)
        assertNotNull(live.windowEndsAt)
        assertTrue(live.windowEndsAt!! >= before.plus(Duration.ofDays(3)).minusSeconds(5))
        assertEquals(listOf("subj-1@x.com"), transport.sent.map { it.first })

        val ended = service(dryRun = true).endWindow("subj-1", "manual", "s").expectRight()
        assertEquals(AdvocateState.UNPROVISIONED, ended.state)
        assertNull(ended.windowEndsAt)
    }

    // ---- live state machine ------------------------------------------------------------

    @Test
    fun `startWindow then polls walk PROVISIONING to LIVE (mail once) and endWindow to UNPROVISIONED`() {
        seedSubject()
        seedAdvocate()
        val svc = service()

        val started = svc.startWindow("subj-1", WindowPreset.D1, "neo@x.com").expectRight()
        assertEquals(AdvocateState.PROVISIONING, started.state)
        assertEquals("op-upload", started.servingOperation)
        assertNotNull(started.provisioningStartedAt)
        assertTrue(transport.sent.isEmpty())

        val afterUpload = svc.poll("subj-1").expectRight()
        assertEquals(AdvocateState.PROVISIONING, afterUpload.state)
        assertEquals("model-1", afterUpload.servingModelResource)

        val live = svc.poll("subj-1").expectRight()
        assertEquals(AdvocateState.LIVE, live.state)
        assertEquals("dm-1", live.servingDeployedModelId)
        assertNull(live.provisioningStartedAt)
        assertEquals(1, transport.sent.size)

        val stopping = svc.endWindow("subj-1", "manual", "neo@x.com").expectRight()
        assertEquals(AdvocateState.DEPROVISIONING, stopping.state)
        val gone = svc.poll("subj-1").expectRight()
        assertEquals(AdvocateState.UNPROVISIONED, gone.state)
        assertNull(gone.servingDeployedModelId)
        assertNull(gone.windowPreset)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `a failed beginServe lands DEPLOY_FAILED with the error operator-visible`() {
        seedSubject()
        seedAdvocate()
        assertIs<DomainError.Invalid>(
            service(ProvBackend(failServe = true)).startWindow("subj-1", WindowPreset.D1, "s").err()
        )
        val adv = advocates.store["subj-1"]!!
        assertEquals(AdvocateState.DEPLOY_FAILED, adv.state)
        assertEquals("boom", adv.lastError)
    }

    @Test
    fun `an expired LIVE window is torn down by poll (the sweeper path)`() {
        seedSubject()
        seedAdvocate(
            state = AdvocateState.LIVE,
            deployedModelId = "dm-1",
            windowEndsAt = Instant.now().minusSeconds(60),
        )
        service().poll("subj-1").expectRight()
        assertEquals(AdvocateState.DEPROVISIONING, advocates.store["subj-1"]!!.state)
    }

    @Test
    fun `a deploy stuck past the timeout flips DEPLOY_FAILED`() {
        seedSubject()
        seedAdvocate(
            state = AdvocateState.PROVISIONING,
            provisioningStartedAt = Instant.now().minus(Duration.ofMinutes(90)),
        )
        advocates.store["subj-1"] = advocates.store["subj-1"]!!.copy(servingOperation = "op-upload")
        val adv = service(ProvBackend(hold = true)).poll("subj-1").expectRight()
        assertEquals(AdvocateState.DEPLOY_FAILED, adv.state)
        assertTrue(adv.lastError!!.contains("gave up"))
    }

    @Test
    fun `endWindow during early PROVISIONING clamps the window instead of tearing down`() {
        seedSubject()
        seedAdvocate(state = AdvocateState.PROVISIONING)
        val adv = service().endWindow("subj-1", "manual", "s").expectRight()
        assertEquals(AdvocateState.PROVISIONING, adv.state)
        assertTrue(adv.windowEndsAt!! <= Instant.now())
    }

    // ---- sweep reconcile ------------------------------------------------------------------

    @Test
    fun `sweep repairs a LIVE advocate whose deployment vanished`() {
        seedSubject()
        seedAdvocate(
            state = AdvocateState.LIVE,
            deployedModelId = "dm-1",
            windowEndsAt = Instant.now().plusSeconds(3600),
        )
        val backend = ProvBackend() // deployed = empty ⇒ dm-1 is gone
        val counters = service(backend).sweep()
        assertEquals(1, counters["repaired"])
        val adv = advocates.store["subj-1"]!!
        assertEquals(AdvocateState.UNPROVISIONED, adv.state)
        assertTrue(adv.lastError!!.contains("repaired by sweep"))
    }

    @Test
    fun `sweep tears down a deployment nobody claims but spares claimed ones`() {
        seedSubject()
        seedAdvocate(
            state = AdvocateState.LIVE,
            deployedModelId = "dm-mine",
            windowEndsAt = Instant.now().plusSeconds(3600),
        )
        val backend = ProvBackend()
        backend.deployed =
            listOf(
                ai.vishwakarma.labelling.serving.Deployment("dm-mine", "advocate-dev"),
                ai.vishwakarma.labelling.serving.Deployment("dm-orphan", "serve-old"),
            )
        val counters = service(backend).sweep()
        assertEquals(1, counters["orphansTornDown"])
        assertEquals(listOf("dm-orphan"), backend.tornDown)
        assertEquals(AdvocateState.LIVE, advocates.store["subj-1"]!!.state)
    }
}
