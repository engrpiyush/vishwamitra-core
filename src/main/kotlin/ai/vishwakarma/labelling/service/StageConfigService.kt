package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ConfigField
import ai.vishwakarma.labelling.domain.ConfigFieldKind
import ai.vishwakarma.labelling.domain.ConfigFieldView
import ai.vishwakarma.labelling.domain.StageConfigDoc
import ai.vishwakarma.labelling.domain.StageKey
import ai.vishwakarma.labelling.persistence.StageConfigRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The VA-83 live config store: per-stage `app.*` blocks resolved as **stored override → yml/env
 * default → code default**. Stage components call the [intake]/[stage2]/[stage3]/[stage4] accessors
 * at read time, so an ADMIN edit applies on the very next run/poll — no deploy. Merged blocks are
 * cached and invalidated on save (plus a short TTL as multi-instance belt-and-braces); when the
 * store is unreachable the bootstrap [AppProperties] apply unchanged.
 *
 * Only catalog fields marked editable ever merge — read-only (startup-bound) and secret fields
 * always come from the bootstrap instance, so a stray stored value can never smuggle a secret or
 * pretend a restart-bound change took effect.
 */
@Service
class StageConfigService(
    private val props: AppProperties,
    private val repo: StageConfigRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Cached(val doc: StageConfigDoc, val block: Any, val at: Instant)

    private val cache = ConcurrentHashMap<StageKey, Cached>()

    /**
     * The bootstrap properties, for the blocks the store doesn't manage (gcp/tuning/serving/auth).
     */
    val boot: AppProperties
        get() = props

    // ---- Effective accessors (the runtime read path) -------------------------

    fun intake(): AppProperties.Intake = effective(StageKey.STAGE1) as AppProperties.Intake

    fun stage2(): AppProperties.Stage2 = effective(StageKey.STAGE2) as AppProperties.Stage2

    fun stage3(): AppProperties.Stage3 = effective(StageKey.STAGE3) as AppProperties.Stage3

    fun stage4(): AppProperties.Stage4 = effective(StageKey.STAGE4) as AppProperties.Stage4

    /** The stored override doc (for version/audit display); empty doc when none. */
    fun doc(stage: StageKey): StageConfigDoc = load(stage).doc

    // ---- Configuration-tab views ---------------------------------------------

    /** Catalog fields with effective/default renderings, in catalog (group) order. */
    fun fieldViews(stage: StageKey): List<ConfigFieldView> {
        val base = baseBlock(stage)
        val merged = effective(stage)
        val stored = load(stage).doc.overrides.keys
        return StageConfigCatalog.fields(stage).map { f ->
            ConfigFieldView(
                field = f,
                effective = renderFor(f, currentValue(merged, f.name)),
                default = renderFor(f, currentValue(base, f.name)),
                overridden = f.editable && f.name in stored,
            )
        }
    }

    /**
     * Parse + persist the Configuration-tab form. Only deviations from the bootstrap default are
     * stored (typing the default back = removing the override); version increments on every save.
     */
    fun update(
        stage: StageKey,
        form: Map<String, String>,
        actor: String?,
    ): Either<DomainError, StageConfigDoc> {
        val base = baseBlock(stage)
        val overrides = mutableMapOf<String, Any?>()
        val errors = mutableListOf<String>()
        for (f in StageConfigCatalog.fields(stage).filter { it.editable }) {
            val raw = form[f.name] ?: continue
            val parsed =
                try {
                    parseForm(f.kind, raw)
                } catch (e: Exception) {
                    errors += "${f.label}: ${e.message ?: "invalid value"}"
                    continue
                }
            // Closed value sets are enforced here, not in parseForm — that helper sees only the
            // kind, never the field, so it structurally cannot know a STRING's allowed values.
            if (f.options.isNotEmpty() && parsed !in f.options) {
                errors += "${f.label}: '$parsed' is not one of ${f.options.joinToString(" | ")}"
                continue
            }
            if (parsed != currentValue(base, f.name)) overrides[f.name] = storeValue(f.kind, parsed)
        }
        if (errors.isNotEmpty()) return DomainError.Invalid(errors.joinToString(" · ")).left()
        val saved =
            StageConfigDoc(
                stage = stage.id,
                overrides = overrides,
                version = load(stage).doc.version + 1,
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        repo.save(saved)
        cache.remove(stage)
        return saved.right()
    }

    /** Clear every override (keeps the doc + version for the audit trail). */
    fun reset(stage: StageKey, actor: String?): StageConfigDoc {
        val saved =
            StageConfigDoc(
                stage = stage.id,
                overrides = emptyMap(),
                version = load(stage).doc.version + 1,
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        repo.save(saved)
        cache.remove(stage)
        return saved
    }

    // ---- Merge machinery ------------------------------------------------------

    private fun baseBlock(stage: StageKey): Any =
        when (stage) {
            StageKey.STAGE1 -> props.intake
            StageKey.STAGE2 -> props.stage2
            StageKey.STAGE3 -> props.stage3
            StageKey.STAGE4 -> props.stage4
        }

    private fun effective(stage: StageKey): Any = load(stage).block

    private fun load(stage: StageKey): Cached {
        val now = Instant.now()
        cache[stage]?.let { if (Duration.between(it.at, now) < CACHE_TTL) return it }
        val doc =
            runCatching { repo.find(stage.id) }
                .getOrElse {
                    log.warn(
                        "stage_config read failed for {} — using bootstrap: {}",
                        stage.id,
                        it.message
                    )
                    StageConfigDoc(stage = stage.id)
                }
        val block = mergeBlock(stage, baseBlock(stage), doc.overrides)
        return Cached(doc, block, now).also { cache[stage] = it }
    }

    private fun mergeBlock(stage: StageKey, base: Any, overrides: Map<String, Any?>): Any {
        if (overrides.isEmpty()) return base
        val byName = StageConfigCatalog.fields(stage).filter { it.editable }.associateBy { it.name }
        val values = mutableMapOf<String, Any?>()
        val mixValues = mutableMapOf<String, Any?>()
        for ((name, stored) in overrides) {
            val field = byName[name] ?: continue
            val coerced =
                try {
                    coerceStored(field.kind, stored)
                } catch (e: Exception) {
                    log.warn("Ignoring bad stored override {}.{}: {}", stage.id, name, e.message)
                    continue
                }
            if (name.startsWith("mix.")) mixValues[name.removePrefix("mix.")] = coerced
            else values[name] = coerced
        }
        if (mixValues.isNotEmpty()) {
            val baseMix = currentValue(base, "mix") ?: AppProperties.Stage4.Mix()
            values["mix"] = rebuild(baseMix, mixValues)
        }
        return if (values.isEmpty()) base else rebuild(base, values)
    }

    /** Copy [base] via its primary constructor with [values] replacing the named parameters. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> rebuild(base: T, values: Map<String, Any?>): T {
        val klass = base::class
        val ctor =
            klass.primaryConstructor ?: error("${klass.simpleName} has no primary constructor")
        val propsByName = klass.memberProperties.associateBy { it.name }
        val args =
            ctor.parameters.associateWith { p ->
                if (values.containsKey(p.name)) values[p.name]
                else propsByName.getValue(p.name!!).getter.call(base)
            }
        return ctor.callBy(args) as T
    }

    private fun currentValue(block: Any, name: String): Any? =
        if (name.startsWith("mix.")) {
            readProp(block, "mix")?.let { readProp(it, name.removePrefix("mix.")) }
        } else {
            readProp(block, name)
        }

    private fun readProp(obj: Any, name: String): Any? =
        obj::class.memberProperties.firstOrNull { it.name == name }?.getter?.call(obj)

    // ---- Value conversion -------------------------------------------------------

    /** Firestore value → the Kotlin type the constructor parameter needs. */
    @Suppress("UNCHECKED_CAST")
    private fun coerceStored(kind: ConfigFieldKind, stored: Any?): Any? =
        when (kind) {
            ConfigFieldKind.STRING -> stored?.toString() ?: ""
            ConfigFieldKind.INT -> (stored as Number).toInt()
            ConfigFieldKind.LONG -> (stored as Number).toLong()
            ConfigFieldKind.DOUBLE -> (stored as Number).toDouble()
            ConfigFieldKind.BOOLEAN -> stored as Boolean
            ConfigFieldKind.DURATION -> Duration.parse(stored.toString())
            ConfigFieldKind.STRING_LIST -> (stored as List<*>).map { it.toString() }
            ConfigFieldKind.DOUBLE_MAP ->
                (stored as Map<*, *>).entries.associate {
                    it.key.toString() to (it.value as Number).toDouble()
                }
        }

    /** Parsed Kotlin value → what we persist (Duration as ISO-8601 text; the rest native). */
    private fun storeValue(kind: ConfigFieldKind, parsed: Any?): Any? =
        if (kind == ConfigFieldKind.DURATION) parsed.toString() else parsed

    /** Form string → typed value; throws with a user-readable message on bad input. */
    private fun parseForm(kind: ConfigFieldKind, raw: String): Any? {
        val t = raw.trim()
        return when (kind) {
            ConfigFieldKind.STRING -> t
            ConfigFieldKind.INT ->
                t.toIntOrNull() ?: throw IllegalArgumentException("'$t' is not a whole number")
            ConfigFieldKind.LONG ->
                t.toLongOrNull() ?: throw IllegalArgumentException("'$t' is not a whole number")
            ConfigFieldKind.DOUBLE ->
                t.toDoubleOrNull() ?: throw IllegalArgumentException("'$t' is not a number")
            ConfigFieldKind.BOOLEAN ->
                when (t.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("'$t' is not true/false")
                }
            ConfigFieldKind.DURATION -> parseDuration(t)
            ConfigFieldKind.STRING_LIST -> t.split(',').map { it.trim() }.filter { it.isNotBlank() }
            ConfigFieldKind.DOUBLE_MAP ->
                t.split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .associate { entry ->
                        val parts = entry.split('=', limit = 2)
                        val key = parts.getOrNull(0)?.trim().orEmpty()
                        val value = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                        if (key.isBlank() || value == null) {
                            throw IllegalArgumentException("'$entry' is not KEY=number")
                        }
                        key to value
                    }
        }
    }

    /** Accepts `90s` / `15m` / `2h` or ISO-8601 (`PT15M`). */
    private fun parseDuration(raw: String): Duration {
        SIMPLE_DURATION.find(raw)?.let { m ->
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2].lowercase()) {
                "s" -> Duration.ofSeconds(n)
                "m" -> Duration.ofMinutes(n)
                else -> Duration.ofHours(n)
            }
        }
        return runCatching { Duration.parse(raw) }
            .getOrElse {
                throw IllegalArgumentException(
                    "'$raw' is not a duration (use 90s, 15m, 2h or PT15M)"
                )
            }
    }

    private fun renderFor(field: ConfigField, value: Any?): String {
        if (field.secret) {
            return if ((value as? String).isNullOrBlank()) "(not set)" else "••••••••"
        }
        return render(field.kind, value)
    }

    private fun render(kind: ConfigFieldKind, value: Any?): String =
        when (kind) {
            ConfigFieldKind.DURATION -> renderDuration(value as Duration)
            ConfigFieldKind.STRING_LIST -> (value as List<*>).joinToString(", ")
            ConfigFieldKind.DOUBLE_MAP ->
                (value as Map<*, *>).entries.joinToString(", ") { "${it.key}=${it.value}" }
            else -> value?.toString() ?: ""
        }

    private fun renderDuration(d: Duration): String =
        when {
            d.toMillis() % 3_600_000L == 0L -> "${d.toHours()}h"
            d.toMillis() % 60_000L == 0L -> "${d.toMinutes()}m"
            d.toMillis() % 1000L == 0L -> "${d.seconds}s"
            else -> d.toString()
        }

    companion object {
        private val CACHE_TTL: Duration = Duration.ofSeconds(30)
        private val SIMPLE_DURATION = Regex("^(\\d+)\\s*([smhSMH])$")
    }
}
