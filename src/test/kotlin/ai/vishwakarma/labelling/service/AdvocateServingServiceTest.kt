package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.BaseModel
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.ServingState
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.BaseModelRepository
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.serving.ChatRequest
import ai.vishwakarma.labelling.serving.Deployment
import ai.vishwakarma.labelling.serving.ServeRequest
import ai.vishwakarma.labelling.serving.ServingBackend
import ai.vishwakarma.labelling.serving.ServingHandle
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeVersionRepo : ModelVersionRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ModelVersion>()

    override fun findById(id: String): ModelVersion? = store[id]

    override fun findAll(): List<ModelVersion> = store.values.toList()

    override fun save(version: ModelVersion) {
        store[version.id] = version
    }
}

private class FakeAdvocateRepo : AdvocateRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Advocate>()

    override fun findAll(): List<Advocate> = store.values.toList()
}

private class FakeBaseModelRepo : BaseModelRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, BaseModel>()

    override fun findById(id: String): BaseModel? = store[id]

    override fun findAll(): List<BaseModel> = store.values.toList()

    override fun save(model: BaseModel) {
        store[model.id] = model
    }
}

/**
 * Scriptable [ServingBackend] double driving the two-step Vertex progression deterministically:
 * beginServe → DEPLOYING(op-upload); advance op-upload → DEPLOYING(model, op-deploy); advance
 * op-deploy → LIVE(deployedModelId); beginTeardown → TEARING_DOWN(op-undeploy); advance that →
 * NONE.
 */
private class FakeBackend(override val id: String = "fake", val failServe: Boolean = false) :
    ServingBackend {
    override fun target(): String = "ep-1"

    override fun beginServe(req: ServeRequest): ServingHandle =
        if (failServe) ServingHandle(ServingState.FAILED, error = "boom")
        else ServingHandle(ServingState.DEPLOYING, endpointId = "ep-1", operation = "op-upload")

    override fun advance(handle: ServingHandle): ServingHandle =
        when {
            handle.state == ServingState.DEPLOYING && handle.operation == "op-upload" ->
                handle.copy(modelResource = "model-1", operation = "op-deploy")
            handle.state == ServingState.DEPLOYING && handle.operation == "op-deploy" ->
                handle.copy(
                    state = ServingState.LIVE,
                    deployedModelId = "dm-1",
                    operation = null,
                )
            handle.state == ServingState.TEARING_DOWN -> ServingHandle(ServingState.NONE)
            else -> handle
        }

    override fun beginTeardown(handle: ServingHandle): ServingHandle =
        handle.copy(state = ServingState.TEARING_DOWN, operation = "op-undeploy")

    override fun deployments(): List<Deployment> = emptyList()

    override fun chat(req: ChatRequest): String = error("not under test")
}

class AdvocateServingServiceTest {

    private val versions = FakeVersionRepo()
    private val advocates = FakeAdvocateRepo()
    private val baseModelRepo = FakeBaseModelRepo()

    private fun props(enabled: Boolean = true, dryRun: Boolean = false) =
        AppProperties(
            serving =
                AppProperties.Serving(
                    enabled = enabled,
                    dryRun = dryRun,
                    backend = "fake",
                    endpointId = "ep-1",
                    image = "img",
                )
        )

    private fun service(
        dryRun: Boolean = false,
        enabled: Boolean = true,
        failServe: Boolean = false
    ) =
        AdvocateServingService(
            versions,
            advocates,
            listOf(FakeBackend(failServe = failServe)),
            props(enabled, dryRun),
            BaseModelService(baseModelRepo),
        )

    private fun seedReady(
        id: String = "v1",
        state: ServingState = ServingState.NONE
    ): ModelVersion {
        val v =
            ModelVersion(
                id = id,
                baseModelId = "b",
                family = "qwen3-4b",
                version = "v1.0",
                method = TuningMethod.SFT,
                baseKind = BaseKind.FOUNDATION,
                gcsCheckpointUri = "gs://bucket/tuned/qwen3-4b-v1.0/final/",
                status = VersionStatus.READY,
                displayName = "vishwakarma-ai-qwen3-4b-v1.0",
                servingState = state,
            )
        versions.store[id] = v
        return v
    }

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ error("expected Right, got Left: ${it.message}") }, { it })

    private fun <T> Either<DomainError, T>.err(): DomainError? = fold({ it }, { null })

    // ---- guards -----------------------------------------------------------------

    @Test
    fun `serve is gated by the family row's hostable flag (VA-86)`() {
        seedReady()
        baseModelRepo.store["bm"] =
            BaseModel(
                id = "bm",
                publisherModel = "qwen/qwen3@qwen3-4b",
                displayName = "Qwen3 4B",
                family = "qwen3-4b",
                hostable = false,
            )
        val err = service().serve("v1").err()
        assertIs<DomainError.Invalid>(err)
        assertTrue(err.message.contains("not hostable"))

        // Flipping the row on unblocks the same version; a family with no row also passes.
        baseModelRepo.store["bm"] = baseModelRepo.store["bm"]!!.copy(hostable = true)
        assertEquals(ServingState.DEPLOYING, service().serve("v1").expectRight().servingState)
    }

    @Test
    fun `serve requires enabled, READY status, and a checkpoint`() {
        seedReady()
        assertIs<DomainError.Conflict>(service(enabled = false).serve("v1").err())

        versions.store["v1"] = versions.store["v1"]!!.copy(status = VersionStatus.TRAINING)
        assertIs<DomainError.Invalid>(service().serve("v1").err())

        versions.store["v1"] =
            versions.store["v1"]!!.copy(status = VersionStatus.READY, gcsCheckpointUri = null)
        assertIs<DomainError.Invalid>(service().serve("v1").err())

        assertIs<DomainError.NotFound>(service().serve("nope").err())
    }

    @Test
    fun `only one version may occupy the shared endpoint`() {
        seedReady("v1", state = ServingState.LIVE).also {
            versions.store["v1"] = it.copy(servingDeployedModelId = "dm-live")
        }
        seedReady("v2")
        val err = service().serve("v2").err()
        assertIs<DomainError.Conflict>(err)
        assertTrue(err.message.contains("one advocate serves at a time"))
        assertEquals(ServingState.NONE, versions.store["v2"]!!.servingState)
    }

    @Test
    fun `an advocate window occupying the endpoint blocks an operator serve (VA-38 cross-guard)`() {
        seedReady()
        advocates.store["subj-1"] =
            Advocate(subjectId = "subj-1", state = AdvocateState.PROVISIONING)
        val err = service().serve("v1").err()
        assertIs<DomainError.Conflict>(err)
        assertTrue(err.message.contains("end its window first"))
    }

    // ---- dry-run ----------------------------------------------------------------

    @Test
    fun `dry-run serve goes straight to LIVE and teardown clears it`() {
        seedReady()
        val live = service(dryRun = true).serve("v1").expectRight()
        assertEquals(ServingState.LIVE, live.servingState)
        assertNotNull(live.servedAt)

        val down = service(dryRun = true).teardown("v1").expectRight()
        assertEquals(ServingState.NONE, down.servingState)
        assertNull(down.servingDeployedModelId)
        assertNull(down.servedAt)
    }

    // ---- live backend state machine ---------------------------------------------

    @Test
    fun `serve then poll walks upload to deploy to LIVE, then teardown to NONE`() {
        seedReady()
        val svc = service()

        val deploying = svc.serve("v1").expectRight()
        assertEquals(ServingState.DEPLOYING, deploying.servingState)
        assertEquals("op-upload", deploying.servingOperation)
        assertNull(deploying.servedAt)

        // upload done → deploy kicked
        val afterUpload = svc.poll("v1").expectRight()
        assertEquals(ServingState.DEPLOYING, afterUpload.servingState)
        assertEquals("model-1", afterUpload.servingModelResource)
        assertEquals("op-deploy", afterUpload.servingOperation)

        // deploy done → LIVE
        val live = svc.poll("v1").expectRight()
        assertEquals(ServingState.LIVE, live.servingState)
        assertEquals("dm-1", live.servingDeployedModelId)
        assertNull(live.servingOperation)
        assertNotNull(live.servedAt)

        // teardown → TEARING_DOWN → poll → NONE
        val tearing = svc.teardown("v1").expectRight()
        assertEquals(ServingState.TEARING_DOWN, tearing.servingState)
        val gone = svc.poll("v1").expectRight()
        assertEquals(ServingState.NONE, gone.servingState)
        assertNull(gone.servingDeployedModelId)
    }

    @Test
    fun `a failed beginServe persists FAILED and returns Left`() {
        seedReady()
        val svc = service(failServe = true)
        assertIs<DomainError.Invalid>(svc.serve("v1").err())
        assertEquals(ServingState.FAILED, versions.store["v1"]!!.servingState)
        assertEquals("boom", versions.store["v1"]!!.servingError)
    }

    @Test
    fun `poll on a settled state is a no-op`() {
        seedReady("v1", state = ServingState.LIVE)
        val same = service().poll("v1").expectRight()
        assertEquals(ServingState.LIVE, same.servingState)
    }
}
