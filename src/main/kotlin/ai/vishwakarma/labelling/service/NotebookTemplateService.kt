package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.DeclaredType
import ai.vishwakarma.labelling.domain.FormatSpec
import ai.vishwakarma.labelling.domain.NotebookTemplate
import ai.vishwakarma.labelling.domain.NotebookTemplateTaxonomy
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.TemplateCategory
import ai.vishwakarma.labelling.domain.TemplateCategoryGroup
import ai.vishwakarma.labelling.persistence.NotebookTemplateRepository
import ai.vishwakarma.labelling.persistence.NotebookTemplateTaxonomyRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Manages the notebook-template library and its evaluation-trait category taxonomy (LLD §14A.6,
 * VA-87). Templates are versioned like the other admin rows; categories live in a single taxonomy
 * document and double as the VA-60 behavioural-eval axes. `Stage4Planning` consumes the library
 * once VA-88 lands.
 */
@Service
class NotebookTemplateService(
    private val templates: NotebookTemplateRepository,
    private val taxonomyRepo: NotebookTemplateTaxonomyRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ---- Templates ---------------------------------------------------------

    /** All templates in taxonomy display order (group, then category, then title). */
    fun list(): List<NotebookTemplate> {
        val taxonomy = taxonomy()
        val order = taxonomy.categories.withIndex().associate { (i, c) -> c.slug to i }
        return templates
            .findAll()
            .sortedWith(compareBy({ order[it.category] ?: Int.MAX_VALUE }, { it.title }))
    }

    fun get(id: String): NotebookTemplate? = templates.findById(id)

    fun create(
        category: String,
        title: String,
        formatSpec: FormatSpec,
        promptTemplate: String,
        coverageTarget: Int,
        actor: String?,
        facets: String = "",
        evidenceGate: String = "",
        requiredClaimTypes: List<ClaimType> = emptyList(),
        requiredDeclaredTypes: List<String> = emptyList(),
        outcomes: List<String> = emptyList(),
    ): Either<DomainError, NotebookTemplate> =
        validate(category, title, coverageTarget) {
            val template =
                NotebookTemplate(
                    id = templates.newId(),
                    category = category,
                    title = title.trim(),
                    formatSpec = formatSpec.clean(),
                    promptTemplate = promptTemplate.trim(),
                    coverageTarget = coverageTarget,
                    facets = facets.trim(),
                    evidenceGate = evidenceGate.trim(),
                    requiredClaimTypes = requiredClaimTypes,
                    requiredDeclaredTypes = declaredTypes(requiredDeclaredTypes),
                    outcomes = outcomes.map { it.trim() }.filter { it.isNotBlank() },
                    version = 1,
                    updatedBy = actor,
                    updatedAt = Instant.now(),
                )
            templates.save(template)
            template
        }

    fun update(
        id: String,
        category: String,
        title: String,
        formatSpec: FormatSpec,
        promptTemplate: String,
        coverageTarget: Int,
        actor: String?,
        facets: String = "",
        evidenceGate: String = "",
        requiredClaimTypes: List<ClaimType> = emptyList(),
        requiredDeclaredTypes: List<String> = emptyList(),
        outcomes: List<String> = emptyList(),
    ): Either<DomainError, NotebookTemplate> {
        val existing =
            templates.findById(id) ?: return DomainError.NotFound("Template not found").left()
        return validate(category, title, coverageTarget) {
            val updated =
                existing.copy(
                    category = category,
                    title = title.trim(),
                    formatSpec = formatSpec.clean(),
                    promptTemplate = promptTemplate.trim(),
                    coverageTarget = coverageTarget,
                    facets = facets.trim(),
                    evidenceGate = evidenceGate.trim(),
                    requiredClaimTypes = requiredClaimTypes,
                    requiredDeclaredTypes = declaredTypes(requiredDeclaredTypes),
                    outcomes = outcomes.map { it.trim() }.filter { it.isNotBlank() },
                    version = existing.version + 1,
                    updatedBy = actor,
                    updatedAt = Instant.now(),
                )
            templates.save(updated)
            updated
        }
    }

    fun delete(id: String) = templates.delete(id)

    private fun validate(
        category: String,
        title: String,
        coverageTarget: Int,
        onValid: () -> NotebookTemplate,
    ): Either<DomainError, NotebookTemplate> =
        when {
            title.isBlank() -> DomainError.Invalid("Title is required").left()
            coverageTarget < 1 -> DomainError.Invalid("Coverage target must be at least 1").left()
            taxonomy().bySlug(category) == null ->
                DomainError.Invalid("Unknown category '$category'").left()
            else -> onValid().right()
        }

    /**
     * Normalise the fine evidence gate and drop anything [DeclaredType] does not recognise. Silent
     * rather than a validation error on purpose: this is a *narrowing* control, so an unknown key
     * that survived would gate on a `declaredType` nothing ever stamps and mute the template
     * entirely — dropping it degrades to the coarse gate, which is the pre-feature behaviour.
     */
    private fun declaredTypes(raw: List<String>): List<String> =
        raw.map { it.trim().lowercase() }.filter { DeclaredType.recognises(it) }.distinct()

    private fun FormatSpec.clean() =
        FormatSpec(
            turnShape = turnShape.trim(),
            intent = intent.trim(),
            personaLens = personaLens.trim(),
            expectedBehaviours = expectedBehaviours.map { it.trim() }.filter { it.isNotBlank() },
            openingProbe = openingProbe.trim(),
            failureModes = failureModes.map { it.trim() }.filter { it.isNotBlank() },
        )

    // ---- Taxonomy ----------------------------------------------------------

    fun taxonomy(): NotebookTemplateTaxonomy = taxonomyRepo.get()

    fun addCategory(
        name: String,
        group: TemplateCategoryGroup,
    ): Either<DomainError, TemplateCategory> {
        val clean = name.trim()
        if (clean.isBlank()) return DomainError.Invalid("Category name is required").left()
        val slug = slugify(clean)
        if (slug.isEmpty()) {
            return DomainError.Invalid("Category name must contain letters or digits").left()
        }
        val current = taxonomyRepo.get()
        if (current.bySlug(slug) != null) {
            return DomainError.Conflict("Category '$slug' already exists").left()
        }
        val category = TemplateCategory(slug = slug, name = clean, group = group)
        // Keep the doc in group order so display order is stable without per-row sort keys.
        val updated =
            (current.categories + category).sortedWith(compareBy({ it.group.ordinal }, { it.name }))
        taxonomyRepo.save(NotebookTemplateTaxonomy(updated))
        return category.right()
    }

    fun removeCategory(slug: String): Either<DomainError, NotebookTemplateTaxonomy> {
        val current = taxonomyRepo.get()
        current.bySlug(slug) ?: return DomainError.NotFound("Category '$slug' not found").left()
        val inUse = templates.findAll().count { it.category == slug }
        if (inUse > 0) {
            return DomainError.Conflict(
                    "Category '$slug' is used by $inUse template(s) — reassign them first"
                )
                .left()
        }
        val updated = NotebookTemplateTaxonomy(current.categories.filterNot { it.slug == slug })
        taxonomyRepo.save(updated)
        return updated.right()
    }

    // ---- Seed + migration (DataSeeder) --------------------------------------

    /** Seed the LLD §14A.6 category list when the taxonomy document is empty. */
    fun seedTaxonomyIfEmpty(): Boolean {
        if (taxonomyRepo.get().categories.isNotEmpty()) return false
        val seed =
            DEFAULT_CATEGORIES.map { (group, name) ->
                TemplateCategory(slug = slugify(name), name = name, group = group)
            }
        taxonomyRepo.save(NotebookTemplateTaxonomy(seed))
        return true
    }

    /**
     * One-shot migration of the legacy `scenarios` rows (VA-87): runs only while the collection is
     * empty (the seeding idiom), so deleted templates stay deleted across restarts. The source rows
     * are left untouched — the SFT scenario path keeps working until superseded.
     */
    fun migrateScenariosIfEmpty(scenarios: List<Scenario>): Int {
        if (templates.findAll().isNotEmpty() || scenarios.isEmpty()) return 0
        val taxonomy = taxonomy()
        val fallback = taxonomy.categories.firstOrNull()?.slug ?: return 0
        scenarios.forEach { s ->
            val category =
                categoryForClaimType(s.claimType).takeIf { taxonomy.bySlug(it) != null } ?: fallback
            templates.save(
                NotebookTemplate(
                    id = templates.newId(),
                    category = category,
                    title = s.title,
                    formatSpec = FormatSpec(intent = s.description),
                    promptTemplate = s.promptTemplate,
                    coverageTarget = 1,
                    version = 1,
                    migratedFrom = "scenario:${s.id}",
                    updatedBy = "migration",
                    updatedAt = Instant.now(),
                )
            )
        }
        log.info("Migrated {} scenario row(s) into notebook_templates", scenarios.size)
        return scenarios.size
    }

    private fun categoryForClaimType(claimType: ClaimType?): String =
        when (claimType) {
            ClaimType.IDENTITY -> "personal-introduction"
            ClaimType.EPISODE -> "storytelling"
            ClaimType.VALUE -> "values-philosophy"
            ClaimType.WEAKNESS -> "weaknesses"
            ClaimType.SKILL -> "technical-expertise"
            null -> "meta-conversations"
        }

    private fun slugify(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)

    companion object {
        /** The ~30 seed categories from LLD §14A.6, in group order. */
        val DEFAULT_CATEGORIES: List<Pair<TemplateCategoryGroup, String>> =
            listOf(
                TemplateCategoryGroup.FOUNDATIONAL to "Personal Introduction",
                TemplateCategoryGroup.FOUNDATIONAL to "Career Timeline",
                TemplateCategoryGroup.FOUNDATIONAL to "Project Deep Dive",
                TemplateCategoryGroup.FOUNDATIONAL to "Technical Expertise",
                TemplateCategoryGroup.FOUNDATIONAL to "Future Goals",
                TemplateCategoryGroup.BEHAVIOURAL_VALUES to "Behavioural Questions",
                TemplateCategoryGroup.BEHAVIOURAL_VALUES to "Leadership",
                TemplateCategoryGroup.BEHAVIOURAL_VALUES to "Communication",
                TemplateCategoryGroup.BEHAVIOURAL_VALUES to "Values & Philosophy",
                TemplateCategoryGroup.BEHAVIOURAL_VALUES to "Strengths",
                TemplateCategoryGroup.BEHAVIOURAL_VALUES to "Weaknesses",
                TemplateCategoryGroup.PERSPECTIVES to "Peer",
                TemplateCategoryGroup.PERSPECTIVES to "Manager",
                TemplateCategoryGroup.PERSPECTIVES to "Mentor",
                TemplateCategoryGroup.PERSPECTIVES to "Client",
                TemplateCategoryGroup.EVALUATIVE to "Candidate Comparison",
                TemplateCategoryGroup.EVALUATIVE to "Company Fit",
                TemplateCategoryGroup.EVALUATIVE to "Resume Validation",
                TemplateCategoryGroup.EVALUATIVE to "Recommendation & Advocacy",
                TemplateCategoryGroup.HARD_BOUNDARY to "Objections & Difficult Questions",
                TemplateCategoryGroup.HARD_BOUNDARY to "Privacy & Boundaries",
                TemplateCategoryGroup.HARD_BOUNDARY to "Unknown Information & Refusals",
                TemplateCategoryGroup.MULTI_TURN to "Recruiter",
                TemplateCategoryGroup.MULTI_TURN to "Hiring Manager",
                TemplateCategoryGroup.MULTI_TURN to "Executive",
                TemplateCategoryGroup.STYLE_OTHER to "Casual Networking",
                TemplateCategoryGroup.STYLE_OTHER to "Elevator Pitch",
                TemplateCategoryGroup.STYLE_OTHER to "Storytelling",
                TemplateCategoryGroup.STYLE_OTHER to "Domain-specific",
                TemplateCategoryGroup.STYLE_OTHER to "Meta Conversations",
            )
    }
}
