package ai.vishwakarma.labelling.config

import ai.vishwakarma.labelling.domain.AdvocateName
import ai.vishwakarma.labelling.domain.AdvocateRegion
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.Taxonomy
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.TaxonomyRepository
import ai.vishwakarma.labelling.security.DevAuthFilter
import ai.vishwakarma.labelling.service.BaseModelService
import ai.vishwakarma.labelling.service.NotebookTemplateService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Idempotently seeds starter content (base models, the label vocabulary, advocate scenarios) when
 * empty, plus bootstrap ADMIN users from config. The tool catalog starts empty (an advocate model
 * rarely tool-calls). Safe to run on every startup; only fills gaps.
 */
@Configuration
class DataSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun seedRunner(
        props: AppProperties,
        baseModels: BaseModelService,
        scenarios: ScenarioService,
        taxonomyRepo: TaxonomyRepository,
        users: UserService,
        advocateNames: AdvocateNameRepository,
        notebookTemplates: NotebookTemplateService,
        subjects: SubjectRepository,
    ): ApplicationRunner = ApplicationRunner {
        runCatching {
                seedBootstrapAdmins(props, users)
                seedBaseModels(baseModels)
                seedTaxonomy(taxonomyRepo)
                seedScenarios(scenarios)
                seedAdvocateNames(advocateNames)
                seedNotebookTemplates(notebookTemplates, scenarios)
                reconcileHandleSentinels(subjects)
                seedDevSubject(props, subjects, users)
            }
            .onFailure { log.warn("Seeding skipped (datastore unavailable?): {}", it.message) }
    }

    private fun seedBootstrapAdmins(props: AppProperties, users: UserService) {
        props.auth.bootstrapAdmins
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { email ->
                if (users.roleFor(email) == null) {
                    users.upsert(email, Role.ADMIN, addedBy = "bootstrap")
                    log.info("Seeded bootstrap admin {}", email)
                }
            }
    }

    private fun seedBaseModels(baseModels: BaseModelService) {
        // Idempotent per family (not seed-once): later releases add rows — the Stage 4 Qwen
        // targets landed after the first three — and must appear on environments seeded before
        // them. Trade-off: a deleted family reappears on restart; deactivate instead of deleting.
        val existing = baseModels.list().map { it.family.lowercase() }.toSet()
        var seeded = 0
        fun ensure(
            publisherModel: String,
            displayName: String,
            family: String,
            active: Boolean,
            tunable: Boolean = true,
        ) {
            if (family.lowercase() in existing) return
            baseModels.create(publisherModel, displayName, family, active, "seed", tunable)
            seeded++
        }
        // The tune picker's curated allowlist (2026-07-13): only the two advocate targets we've
        // verified end-to-end are `tunable = true`; the rest are catalog rows kept visible-but-
        // disabled until proven. qwen3-4b: tune+serve verified (VA-59 + this session). Llama 3.2
        // 3B: serve-verified (VA-74, 88 tok/s); tune enabled on the owner's call (2026-07-13).
        ensure("qwen/qwen3@qwen3-32b", "Qwen 3 32B", "qwen3-32b", active = true, tunable = false)
        ensure(
            "google/gemma3@gemma-3-27b-it",
            "Gemma 3 27B IT",
            "gemma3-27b",
            active = true,
            tunable = false,
        )
        ensure(
            "google/medgemma@medgemma-27b-it",
            "MedGemma 27B IT",
            "medgemma-27b",
            active = false,
            tunable = false,
        )
        // Stage 4 advocate targets (S4-D6; catalog ids live-verified 2026-07-12, VA-59).
        ensure("qwen/qwen3@qwen3-4b", "Qwen3 4B", "qwen3-4b", active = true, tunable = true)
        ensure(
            "qwen/qwen3-5@qwen3.5-9b",
            "Qwen 3.5 9B",
            "qwen35-9b",
            active = true,
            tunable = false,
        )
        // 2B-class serving tier (VA-74: 168 tok/s on the V100 pins). us-central1-only catalog
        // entry (verified 2026-07-13) — DEPLOY-ONLY: a tune submit 400s, so not tunable.
        ensure(
            "qwen/qwen3@qwen3-1.7b",
            "Qwen3 1.7B",
            "qwen3-1-7b",
            active = true,
            tunable = false,
        )
        // Llama 3.2 3B — alt advocate lineage. Catalog id live-verified us-central1 2026-07-13
        // (needs TUNING_REGION=us-central1); serve-verified VA-74. Tune acceptance owner-accepted.
        ensure(
            "meta/llama3-2@llama-3.2-3b",
            "Llama 3.2 3B",
            "llama-3-2-3b",
            active = true,
            tunable = true,
        )
        if (seeded > 0) log.info("Seeded {} base model(s)", seeded)
        // Apply the curated allowlist to rows that predate the `tunable` flag (idempotent).
        val reconciled = baseModels.reconcileTunable(setOf("qwen3-4b", "llama-3-2-3b"))
        if (reconciled > 0) log.info("Reconciled tunable flag on {} base model(s)", reconciled)
    }

    private fun seedTaxonomy(taxonomyRepo: TaxonomyRepository) {
        val current = taxonomyRepo.get()
        if (current.labels.isNotEmpty()) return
        taxonomyRepo.save(
            Taxonomy(
                labels =
                    listOf(
                        "leadership",
                        "technical",
                        "community",
                        "education",
                        "career",
                        "award",
                        "mentorship",
                        "creativity",
                        "resilience",
                        "english",
                    ),
            ),
        )
        log.info("Seeded label vocabulary")
    }

    /**
     * The Stage 4 advocate-name pool (QA on A2): 18 names, 6 per region, half F / half M. A skipped
     * A2 wizard answer resolves deterministically from this pool — deleting or adding rows shifts
     * those picks, which auto-archives affected examples via the persona hash.
     */
    private fun seedAdvocateNames(advocateNames: AdvocateNameRepository) {
        if (advocateNames.findAll().isNotEmpty()) return
        val pool =
            listOf(
                Triple("Emily Carter", AdvocateRegion.US, "F"),
                Triple("James Walker", AdvocateRegion.US, "M"),
                Triple("Sofia Reyes", AdvocateRegion.US, "F"),
                Triple("Michael Brooks", AdvocateRegion.US, "M"),
                Triple("Grace Bennett", AdvocateRegion.US, "F"),
                Triple("Daniel Hayes", AdvocateRegion.US, "M"),
                Triple("Clara Novak", AdvocateRegion.EU, "F"),
                Triple("Lukas Weber", AdvocateRegion.EU, "M"),
                Triple("Elena Rossi", AdvocateRegion.EU, "F"),
                Triple("Tomas Berg", AdvocateRegion.EU, "M"),
                Triple("Amelie Laurent", AdvocateRegion.EU, "F"),
                Triple("Jonas Keller", AdvocateRegion.EU, "M"),
                Triple("Ananya Iyer", AdvocateRegion.IN, "F"),
                Triple("Arjun Mehta", AdvocateRegion.IN, "M"),
                Triple("Priya Nair", AdvocateRegion.IN, "F"),
                Triple("Rohan Kulkarni", AdvocateRegion.IN, "M"),
                Triple("Kavya Menon", AdvocateRegion.IN, "F"),
                Triple("Vikram Rao", AdvocateRegion.IN, "M"),
            )
        pool.forEach { (name, region, gender) ->
            val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            advocateNames.save(
                AdvocateName(
                    id = "${region.name.lowercase()}-$slug",
                    name = name,
                    region = region,
                    gender = gender,
                    createdBy = "seed",
                )
            )
        }
        log.info("Seeded advocate-name pool ({} names)", pool.size)
    }

    /**
     * VA-87: seed the §14A.6 category taxonomy, then one-shot-migrate the legacy `scenarios` rows
     * into `notebook_templates` (only while that collection is empty). Scenario rows stay in place
     * so the SFT drafting path keeps working until VA-88 supersedes it.
     */
    private fun seedNotebookTemplates(
        notebookTemplates: NotebookTemplateService,
        scenarios: ScenarioService,
    ) {
        if (notebookTemplates.seedTaxonomyIfEmpty()) {
            log.info("Seeded notebook-template category taxonomy")
        }
        notebookTemplates.migrateScenariosIfEmpty(scenarios.list())
    }

    /**
     * VA-31 follow-up (all profiles): handles created before the sentinel machinery have no
     * `handles/{handle}` doc, so nothing stops a second subject from claiming them. Backfill the
     * missing sentinels on startup; a conflict (two pre-existing subjects, one handle) is logged
     * loudly for manual resolution — first writer keeps the handle.
     */
    private fun reconcileHandleSentinels(subjects: SubjectRepository) {
        subjects
            .findAll()
            .filter { !it.handle.isNullOrBlank() }
            .forEach { subject ->
                val handle = subject.handle!!
                when (subjects.sentinelFor(handle)) {
                    subject.id -> Unit
                    null -> {
                        subjects.claimHandle(subject.id, handle)
                        log.info("Backfilled handle sentinel '{}' → {}", handle, subject.id)
                    }
                    else ->
                        log.error(
                            "Handle sentinel conflict: '{}' is claimed by another subject — " +
                                "{} keeps no sentinel; resolve manually",
                            handle,
                            subject.id,
                        )
                }
            }
    }

    /**
     * VA-29 (LLD §4.6): dev profile only — one handled subject + its bound SUBJECT login so
     * `?devRole=SUBJECT` (DevAuthFilter) exercises the subject world against the emulator with zero
     * OAuth. Gated on the dev-bypass flag; never seeds in prod.
     */
    private fun seedDevSubject(
        props: AppProperties,
        subjects: SubjectRepository,
        users: UserService
    ) {
        if (!props.auth.devBypass) return
        if (subjects.findById(DevAuthFilter.DEV_SUBJECT_ID) == null) {
            // Written with its handle sentinel in one transaction (VA-31). The handle `dev` is on
            // the §17.2 reserved list — this direct write is the sanctioned exception so
            // dev.localhost:8080 exercises the subject world (validation would refuse it).
            subjects.createWithHandle(
                Subject(
                    id = DevAuthFilter.DEV_SUBJECT_ID,
                    displayName = "Dev Subject",
                    handle = DevAuthFilter.DEV_SUBJECT_HANDLE,
                    notes = "Seeded dev-profile subject for ?devRole=SUBJECT (VA-29).",
                    createdBy = "seed",
                )
            )
            log.info("Seeded dev subject '{}'", DevAuthFilter.DEV_SUBJECT_ID)
        } else {
            // Pre-VA-31 dev datastores have the subject but not the sentinel — idempotent repair.
            subjects.claimHandle(DevAuthFilter.DEV_SUBJECT_ID, DevAuthFilter.DEV_SUBJECT_HANDLE)
        }
        if (users.roleFor(DevAuthFilter.DEV_SUBJECT_EMAIL) == null) {
            users.upsert(
                email = DevAuthFilter.DEV_SUBJECT_EMAIL,
                role = Role.SUBJECT,
                addedBy = "seed",
                subjectId = DevAuthFilter.DEV_SUBJECT_ID,
            )
            log.info("Seeded dev SUBJECT login {}", DevAuthFilter.DEV_SUBJECT_EMAIL)
        }
    }

    private fun seedScenarios(scenarios: ScenarioService) {
        if (scenarios.list().isNotEmpty()) return
        scenarios.create(
            title = "Identity — origin story",
            description =
                "Who the person is and where they come from, grounded in their own account.",
            claimType = ClaimType.IDENTITY,
            labels = emptyList(),
            promptTemplate =
                "Generate a conversation where someone asks about {{subject}}'s background; the advocate gives a grounded, first-person-advocate origin story without inventing facts.",
            actor = "seed",
        )
        scenarios.create(
            title = "Episode — award-winning moment",
            description = "A concrete, corroborated achievement told as a specific episode.",
            claimType = ClaimType.EPISODE,
            labels = listOf("award"),
            promptTemplate =
                "Generate a conversation where someone asks what {{subject}} is most proud of; the advocate recounts a specific, evidence-backed award/achievement episode and answers assertively (high authenticity).",
            actor = "seed",
        )
        scenarios.create(
            title = "Value — owning a weakness honestly",
            description =
                "How the person handles a growth area; tests measured, non-defensive tone.",
            claimType = ClaimType.WEAKNESS,
            labels = listOf("resilience"),
            promptTemplate =
                "Generate a conversation where someone probes a weakness of {{subject}}; the advocate answers honestly and constructively, hedging where evidence is thin (lower authenticity).",
            actor = "seed",
        )
        scenarios.create(
            title = "Skill — technical depth",
            description = "Demonstrating a concrete skill with grounded specifics.",
            claimType = ClaimType.SKILL,
            labels = listOf("technical"),
            promptTemplate =
                "Generate a conversation where someone asks how strong {{subject}} is at a particular skill; the advocate substantiates it with specific, grounded evidence.",
            actor = "seed",
        )
        log.info("Seeded scenarios")
    }
}
