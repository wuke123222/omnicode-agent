package dev.omnicode.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A bounded, deterministic A/B experiment definition. Values are metadata only; no prompt text is stored. */
data class ExperimentVariant(val id: String, val label: String, val weight: Int = 50)

data class ExperimentDefinition(
    val id: String,
    val name: String,
    val hypothesis: String,
    val variants: List<ExperimentVariant>,
    val active: Boolean,
    val createdAt: Instant,
    val assignments: Map<String, String> = emptyMap(),
    val observations: Map<String, ExperimentObservation> = emptyMap(),
)

data class ExperimentObservation(
    val samples: Int = 0,
    val successes: Int = 0,
    val totalLatencyMillis: Long = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
)

class ExperimentVariantState {
    var id: String = ""
    var label: String = ""
    var weight: Int = 50
}

class ExperimentObservationState {
    var variantId: String = ""
    var samples: Int = 0
    var successes: Int = 0
    var totalLatencyMillis: Long = 0
    var totalInputTokens: Long = 0
    var totalOutputTokens: Long = 0
}

class ExperimentState {
    var id: String = ""
    var name: String = ""
    var hypothesis: String = ""
    var active: Boolean = false
    var createdAtEpochMillis: Long = 0
    var variants: MutableList<ExperimentVariantState> = mutableListOf()
    var assignments: MutableMap<String, String> = mutableMapOf()
    var observations: MutableList<ExperimentObservationState> = mutableListOf()
}

class ExperimentLabState {
    var experiments: MutableList<ExperimentState> = mutableListOf()
}

/**
 * Project-local A/B test ledger. Assignment is deterministic per experiment and subject key, so a
 * retry or IDE restart cannot silently move a subject between variants. Only bounded counters are
 * persisted; prompts, source files, API keys, and response text never enter this state.
 */
@Service(Service.Level.PROJECT)
@State(name = "OmniCodeExperimentLab", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ExperimentLabService(private val project: Project) : PersistentStateComponent<ExperimentLabState> {
    private val experiments = linkedMapOf<String, ExperimentDefinition>()

    @Synchronized
    override fun getState(): ExperimentLabState = ExperimentLabState().also { state ->
        state.experiments = experiments.values.map { it.toState() }.toMutableList()
    }

    @Synchronized
    override fun loadState(state: ExperimentLabState) {
        experiments.clear()
        state.experiments.take(MAX_EXPERIMENTS).mapNotNull { it.toDefinitionOrNull() }.forEach { experiments[it.id] = it }
    }

    @Synchronized fun list(): List<ExperimentDefinition> = experiments.values.toList()

    @Synchronized
    fun create(name: String, hypothesis: String, variantLabels: List<String>): ExperimentDefinition {
        val cleanName = boundedText(name, MAX_NAME_CHARS, "实验名称")
        val cleanHypothesis = boundedText(hypothesis, MAX_HYPOTHESIS_CHARS, "假设")
        require(variantLabels.size in 2..MAX_VARIANTS) { "实验必须包含 2-$MAX_VARIANTS 个变体" }
        val labels = variantLabels.map { boundedText(it, MAX_VARIANT_CHARS, "变体名称") }
        require(labels.distinct().size == labels.size) { "变体名称必须唯一" }
        check(experiments.size < MAX_EXPERIMENTS) { "项目实验数量已达到上限" }
        val variants = labels.mapIndexed { index, label ->
            ExperimentVariant(id = "v${index + 1}", label = label, weight = 100 / labels.size)
        }.mapIndexed { index, variant -> if (index == labels.lastIndex) variant.copy(weight = 100 - labels.dropLast(1).sumOf { it.weight }) else variant }
        val definition = ExperimentDefinition(UUID.randomUUID().toString(), cleanName, cleanHypothesis, variants, false, Instant.now())
        experiments[definition.id] = definition
        return definition
    }

    @Synchronized fun setActive(id: String, active: Boolean): ExperimentDefinition = mutate(id) { it.copy(active = active) }

    @Synchronized
    fun assign(experimentId: String, subjectKey: String): ExperimentVariant {
        require(subjectKey.length in 1..MAX_SUBJECT_KEY_CHARS) { "subjectKey 超出长度限制" }
        require(subjectKey.none(Char::isISOControl)) { "subjectKey 含控制字符" }
        val experiment = requireNotNull(experiments[experimentId]) { "实验不存在" }
        require(experiment.active) { "实验尚未启用" }
        val existing = experiment.assignments[subjectKey]
        val variantId = existing ?: weightedVariant(experiment, subjectKey).id
        if (existing == null) experiments[experimentId] = experiment.copy(assignments = experiment.assignments + (subjectKey to variantId))
        return experiment.variants.first { it.id == variantId }
    }

    @Synchronized
    fun record(experimentId: String, subjectKey: String, success: Boolean, latencyMillis: Long, inputTokens: Long, outputTokens: Long) {
        val experiment = experiments[experimentId] ?: error("实验不存在")
        val variant = assign(experimentId, subjectKey)
        val old = experiment.observations[variant.id] ?: ExperimentObservation()
        val next = old.copy(
            samples = (old.samples + 1).coerceAtMost(MAX_SAMPLES),
            successes = (old.successes + if (success) 1 else 0).coerceAtMost(MAX_SAMPLES),
            totalLatencyMillis = (old.totalLatencyMillis + latencyMillis.coerceIn(0, MAX_LATENCY_MILLIS)).coerceAtMost(MAX_COUNTER),
            totalInputTokens = (old.totalInputTokens + inputTokens.coerceIn(0, MAX_TOKEN_COUNTER)).coerceAtMost(MAX_COUNTER),
            totalOutputTokens = (old.totalOutputTokens + outputTokens.coerceIn(0, MAX_TOKEN_COUNTER)).coerceAtMost(MAX_COUNTER),
        )
        experiments[experimentId] = experiment.copy(observations = experiment.observations + (variant.id to next))
    }

    @Synchronized fun delete(id: String): Boolean = experiments.remove(id) != null

    private fun mutate(id: String, block: (ExperimentDefinition) -> ExperimentDefinition): ExperimentDefinition {
        val next = block(requireNotNull(experiments[id]) { "实验不存在" })
        experiments[id] = next
        return next
    }

    private fun weightedVariant(experiment: ExperimentDefinition, subjectKey: String): ExperimentVariant {
        val hash = MessageDigest.getInstance("SHA-256").digest("${experiment.id}:$subjectKey".toByteArray(StandardCharsets.UTF_8))
        val bucket = ((hash[0].toInt() and 0xff) shl 8 or (hash[1].toInt() and 0xff)) % 100
        var cursor = 0
        return experiment.variants.first { variant -> cursor += variant.weight; bucket < cursor }
    }

    private fun ExperimentDefinition.toState() = ExperimentState().also { state ->
        state.id = id; state.name = name; state.hypothesis = hypothesis; state.active = active; state.createdAtEpochMillis = createdAt.toEpochMilli()
        state.variants = variants.map { ExperimentVariantState().also { v -> v.id = it.id; v.label = it.label; v.weight = it.weight } }.toMutableList()
        state.assignments = assignments.toMutableMap()
        state.observations = observations.map { (key, value) -> ExperimentObservationState().also { o -> o.variantId = key; o.samples = value.samples; o.successes = value.successes; o.totalLatencyMillis = value.totalLatencyMillis; o.totalInputTokens = value.totalInputTokens; o.totalOutputTokens = value.totalOutputTokens } }.toMutableList()
    }

    private fun ExperimentState.toDefinitionOrNull(): ExperimentDefinition? {
        if (id.isBlank() || name.isBlank() || variants.size !in 2..MAX_VARIANTS) return null
        val valid = variants.map { ExperimentVariant(it.id.take(20), it.label.take(MAX_VARIANT_CHARS), it.weight.coerceIn(1, 100)) }
        if (valid.sumOf { it.weight } != 100 || valid.map { it.id }.distinct().size != valid.size) return null
        val allowed = valid.mapTo(hashSetOf(), ExperimentVariant::id)
        return ExperimentDefinition(id.take(80), name.take(MAX_NAME_CHARS), hypothesis.take(MAX_HYPOTHESIS_CHARS), valid, active,
            Instant.ofEpochMilli(createdAtEpochMillis.coerceAtLeast(0)), assignments.filterValues { it in allowed }.entries.take(MAX_ASSIGNMENTS).associate { it.toPair() },
            observations.filter { it.variantId in allowed }.associate { it.variantId to ExperimentObservation(it.samples.coerceIn(0, MAX_SAMPLES), it.successes.coerceIn(0, MAX_SAMPLES), it.totalLatencyMillis.coerceIn(0, MAX_COUNTER), it.totalInputTokens.coerceIn(0, MAX_TOKEN_COUNTER), it.totalOutputTokens.coerceIn(0, MAX_TOKEN_COUNTER)) })
    }

    private companion object {
        const val MAX_EXPERIMENTS = 40; const val MAX_VARIANTS = 4; const val MAX_NAME_CHARS = 120; const val MAX_HYPOTHESIS_CHARS = 500; const val MAX_VARIANT_CHARS = 80; const val MAX_SUBJECT_KEY_CHARS = 160; const val MAX_ASSIGNMENTS = 20_000; const val MAX_SAMPLES = 1_000_000; const val MAX_LATENCY_MILLIS = 86_400_000L; const val MAX_TOKEN_COUNTER = 10_000_000L; const val MAX_COUNTER = 1_000_000_000L
        fun boundedText(value: String, max: Int, label: String): String { val clean = value.trim(); require(clean.isNotBlank() && clean.length <= max && clean.none(Char::isISOControl)) { "$label 无效" }; return clean }
    }
}

val ExperimentObservation.successRate: Double get() = if (samples == 0) 0.0 else successes.toDouble() / samples
val ExperimentObservation.averageLatencyMillis: Long get() = if (samples == 0) 0 else totalLatencyMillis / samples
