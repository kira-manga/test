package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Loads the immutable v2 and errata fixture files from their repository paths and
 * reconstructs each declared record from the frozen builder registry.
 *
 * This is test setup only. It does not parse launch-record production bytes itself,
 * grant authority or replace the frozen expectations.
 */
internal object FrozenLaunchRecordV2Fixtures {
    private const val V2_DIRECTORY = "foundation-launch-intent-v2"
    private const val ERRATA_DIRECTORY = "foundation-launch-intent-v2-errata-v1"
    private const val HEADER = "ukkera.local-master-launch/v1"
    private const val FIELD_COUNT = 12
    private const val STOP_BYTES = 35
    private val hex = HexFormat.of()

    private val designRoot: Path = locateDesignRoot()
    private val json = Json
    private val v2: JsonObject = readFixtures(V2_DIRECTORY)
    private val errata: JsonObject = readFixtures(ERRATA_DIRECTORY)

    val coordinateOrder: List<String> = v2.requiredArray("coordinateOrder").map { it.jsonPrimitive.content }

    val vectors: Map<String, FrozenLaunchVector> = run {
        val vectors = linkedMapOf<String, FrozenLaunchVector>()
        v2.requiredObject("coordinateVectors").forEach { (id, element) ->
            val value = element.jsonObject
            val fields = value.requiredArray("fields").map { it.jsonObject }
            val names = fields.map { it.requiredString("name") }
            val values = fields.map { it.requiredString("value") }
            require(names == coordinateOrder) { "Vector $id does not preserve frozen coordinate order" }
            require(values.size == FIELD_COUNT && values.distinct().size == FIELD_COUNT) {
                "Vector $id must contain twelve distinct coordinate values"
            }
            val runBytes = canonicalRun(values)
            require(runBytes.size == value.requiredInt("runBytes")) { "Vector $id run length mismatch" }
            require(sha256(runBytes) == value.requiredString("runSha256")) { "Vector $id run hash mismatch" }
            vectors[id] = FrozenLaunchVector(id, names, values, runBytes)
        }
        require(vectors.size == 15) { "Expected 15 frozen coordinate vectors, found ${vectors.size}" }
        require(vectors.getValue("V-A").values == errata.requiredObject("baseVector")
            .requiredArray("fields").map { it.jsonObject.requiredString("value") }) {
            "Errata base vector V-A differs from the immutable v2 vector"
        }
        vectors
    }

    val v2RecordIds: Set<String> = v2.requiredObject("records").keys
    val errataRecordIds: Set<String> = errata.requiredObject("records").keys
    val v2RecordCount: Int get() = v2RecordIds.size
    val errataRecordCount: Int get() = errataRecordIds.size

    val records: Map<String, FrozenLaunchRecord> = run {
        val records = linkedMapOf<String, FrozenLaunchRecord>()
        parseRecords(v2, "v2").forEach { record ->
            require(records.put(record.id, record) == null) { "Duplicate record ${record.id}" }
        }
        parseRecords(errata, "errata").forEach { record ->
            require(records.put(record.id, record) == null) { "Duplicate record ${record.id}" }
        }
        require(records.size == 126) { "Expected 126 effective records, found ${records.size}" }
        records
    }

    val recordIdsByCase: Map<String, List<String>> = records.values
        .flatMap { record -> record.caseRefs.map { caseId -> caseId to record.id } }
        .groupBy({ it.first }, { it.second })

    val matchingExpectations: Map<String, Map<String, Boolean>> =
        v2.requiredObject("matchingExpectations").mapValues { (_, element) ->
            element.jsonObject.mapValues { (_, expected) -> expected.jsonPrimitive.boolean }
        }

    val retentionScenarios: Map<String, FrozenRetentionScenario> = run {
        val scenarios = linkedMapOf<String, FrozenRetentionScenario>()
        v2.requiredObject("retentionScenarios").forEach { (id, element) ->
            val value = element.jsonObject
            val write = value["write"] ?: error("Retention scenario $id has no write specification")
            val writePhase: String?
            val partialRecord: String?
            if (write is JsonPrimitive) {
                require(write.content == "normal") { "Retention scenario $id has an unknown write mode" }
                writePhase = null
                partialRecord = null
            } else {
                val definition = write.jsonObject
                require(definition.requiredString("kind") == "throw") {
                    "Retention scenario $id has an unknown write definition"
                }
                writePhase = definition.requiredString("phase")
                partialRecord = definition.optionalString("partialRecord")
            }
            val expected = value.requiredObject("expected")
            scenarios[id] = FrozenRetentionScenario(
                id = id,
                initial = value.requiredString("initial"),
                operation = value.requiredString("operation"),
                writePhase = writePhase,
                partialRecord = partialRecord,
                verify = value.requiredString("verify"),
                expected = FrozenRetentionExpectation(
                    outcome = expected.requiredString("outcome"),
                    writeCalls = expected.requiredInt("writeCalls"),
                    verificationReads = expected.requiredInt("verificationReads"),
                    writtenRecord = expected.optionalString("writtenRecord"),
                    finalState = expected.requiredString("finalState"),
                ),
            )
        }
        require(scenarios.size == 31) { "Expected 31 retention scenarios, found ${scenarios.size}" }
        scenarios
    }

    val concurrencyScenarios: Map<String, FrozenConcurrencyScenario> = run {
        val scenarios = linkedMapOf<String, FrozenConcurrencyScenario>()
        v2.requiredObject("concurrencyScenarios").forEach { (id, element) ->
            val value = element.jsonObject
            val expected = value.requiredObject("expected")
            scenarios[id] = FrozenConcurrencyScenario(
                id = id,
                operations = value.requiredArray("operations").map { it.jsonPrimitive.content },
                successes = expected.requiredInt("successes"),
                failures = expected.requiredInt("failures"),
                writeCalls = expected.requiredInt("writeCalls"),
                verificationReads = expected.requiredInt("verificationReads"),
                eventPatterns = expected.requiredArray("eventPatterns").map { pattern ->
                    pattern.jsonArray.map { it.jsonPrimitive.content }
                },
                finalStateOptions = expected.requiredArray("finalStateOptions").map { it.jsonPrimitive.content },
            )
        }
        require(scenarios.size == 3) { "Expected three v2 concurrency scenarios, found ${scenarios.size}" }
        scenarios
    }

    val correctedConcurrency: FrozenCorrectedConcurrency = run {
        val value = errata.requiredObject("concurrencyScenarios").requiredObject("CS-002-CORRECTED")
        require(value.requiredString("initial") == "OBSERVED-MISSING") {
            "Corrected concurrency scenario must start from observed absence"
        }
        val fault = value.requiredObject("fault")
        require(fault.requiredInt("ordinal") == 1 && fault.requiredString("phase") == "beforeBytes") {
            "Corrected concurrency scenario must inject its only fault before the first write's bytes"
        }
        val expected = value.requiredObject("expected")
        FrozenCorrectedConcurrency(
            id = "CS-002-CORRECTED",
            expectedSuccesses = expected.requiredInt("successes"),
            expectedFailures = expected.requiredInt("failures"),
            expectedWriteCalls = expected.requiredInt("writeCalls"),
            expectedVerificationReads = expected.requiredInt("verificationReads"),
            assignments = expected.requiredArray("assignments").map { element ->
                val assignment = element.jsonObject
                FrozenCorrectedAssignment(
                    variant = assignment.requiredString("variant"),
                    firstWriteCaller = assignment.requiredString("firstWriteCaller"),
                    trace = assignment.requiredArray("trace").map { it.jsonPrimitive.content },
                    failed = assignment.requiredString("failed"),
                    successful = assignment.requiredString("successful"),
                    finalState = assignment.requiredString("finalState"),
                )
            },
        )
    }

    val duplicateValuePlan: List<FrozenDuplicateValuePlan> =
        errata.requiredArray("duplicateValuePlan").map { element ->
            val value = element.jsonObject
            FrozenDuplicateValuePlan(
                recordId = value.requiredString("record"),
                destinationIndex = value.requiredInt("destinationIndex"),
                donorIndex = value.requiredInt("donorIndex"),
                changedPositions = value.requiredArray("changedPositions").map { it.jsonPrimitive.int },
                duplicatePositions = value.requiredArray("duplicatePositions").map { it.jsonPrimitive.int },
                duplicatedValue = value.requiredString("duplicatedValue"),
                removedValue = value.requiredString("removedValue"),
                bytes = value.requiredInt("bytes"),
                sha256 = value.requiredString("sha256"),
            )
        }

    val effectiveCaseIds: List<String> = buildList {
        for (index in 1..27) add("FLI2-READ-${index.toString().padStart(3, '0')}")
        add("FLI2E-READ-001")
        for (index in 1..6) add("FLI2-MATCH-${index.toString().padStart(3, '0')}")
        for (index in 1..23) add("FLI2-RETAIN-${index.toString().padStart(3, '0')}")
        add("FLI2-CONC-001")
        add("FLI2-CONC-003")
        add("FLI2E-CONC-001")
    }

    fun record(id: String): FrozenLaunchRecord = records[id] ?: error("Unknown frozen record $id")
    fun vector(id: String): FrozenLaunchVector = vectors[id] ?: error("Unknown frozen vector $id")
    fun retention(id: String): FrozenRetentionScenario = retentionScenarios[id] ?: error("Unknown retention scenario $id")
    fun recordIdsForCase(caseId: String): List<String> =
        recordIdsByCase[caseId] ?: error("No frozen record references for case $caseId")

    fun label(bytes: ByteArray?): String {
        if (bytes == null) return "OBSERVED-MISSING"
        return records.entries.firstOrNull { (_, record) -> record.bytes.contentEquals(bytes) }?.key
            ?: "UNKNOWN(${bytes.size} bytes)"
    }

    fun failedWriteDisposition(label: String): String = when {
        label == "STOP-VALID" -> "STOP"
        label.startsWith("RUN-") -> "RUN"
        else -> label
    }

    private fun locateDesignRoot(): Path {
        val starts = listOfNotNull(
            System.getProperty("user.dir")?.let { Path.of(it).toAbsolutePath().normalize() },
            Path.of("").toAbsolutePath().normalize(),
        ).distinct()
        for (start in starts) {
            var current: Path? = start
            while (current != null) {
                val candidate = current.resolve("qa/pos/designs")
                if (Files.isRegularFile(candidate.resolve(V2_DIRECTORY).resolve("fixtures.json")) &&
                    Files.isRegularFile(candidate.resolve(ERRATA_DIRECTORY).resolve("fixtures.json"))
                ) {
                    return candidate
                }
                current = current.parent
            }
        }
        error(
            "Frozen launch-intent fixtures were not found under qa/pos/designs. " +
                "Searched upward from ${starts.joinToString()}.",
        )
    }

    private fun readFixtures(directory: String): JsonObject {
        val path = designRoot.resolve(directory).resolve("fixtures.json")
        require(Files.isRegularFile(path)) { "Missing frozen fixture file $path" }
        return json.parseToJsonElement(Files.readString(path)).jsonObject.also { value ->
            require(value.requiredString("specVersion") == directory) {
                "Fixture file $path declares an unexpected specification version"
            }
        }
    }

    private fun parseRecords(source: JsonObject, sourceName: String): List<FrozenLaunchRecord> =
        source.requiredObject("records").map { (id, element) ->
            val value = element.jsonObject
            val bytes = buildRecord(value.requiredObject("content"), "$sourceName/$id")
            val declaredBytes = value.requiredInt("bytes")
            require(bytes.size == declaredBytes) {
                "$sourceName/$id built ${bytes.size} bytes but declares $declaredBytes"
            }
            val declaredHash = value.requiredString("sha256")
            val actualHash = sha256(bytes)
            require(actualHash == declaredHash) {
                "$sourceName/$id built SHA-256 $actualHash but declares $declaredHash"
            }
            FrozenLaunchRecord(
                id = id,
                bytes = bytes,
                expected = parseObservation(value.requiredObject("expectedObservation")),
                caseRefs = value.requiredArray("caseRefs").map { it.jsonPrimitive.content },
            )
        }

    private fun parseObservation(value: JsonObject): LaunchObservation = when (val kind = value.requiredString("kind")) {
        "STOP" -> LaunchObservation.Stopped
        "UNRESOLVED" -> LaunchObservation.Unresolved
        "RUN" -> LaunchObservation.Running(value.requiredString("vector"))
        else -> error("Unknown frozen observation kind $kind")
    }

    private fun buildRecord(content: JsonObject, context: String): ByteArray = when (val kind = content.requiredString("kind")) {
        "utf8" -> content.requiredString("text").encodeToByteArray()
        "hex" -> hex.parseHex(content.requiredString("hex"))
        "builder" -> buildRecord(content.requiredString("builder"), content["args"]?.jsonObject ?: JsonObject(emptyMap()), context)
        else -> error("$context has unknown record content kind $kind")
    }

    private fun buildRecord(builder: String, args: JsonObject, context: String): ByteArray = when (builder) {
        "stopReplaceLineEnding" -> stopBytes().decodeToString()
            .replace("\n", args.requiredString("separator"))
            .encodeToByteArray()
        "runTransformField" -> {
            val fields = vector(args.requiredString("baseVector")).values.toMutableList()
            val index = oneBasedIndex(args.requiredInt("fieldIndex"), context)
            fields[index] = when (val transform = args.requiredString("transform")) {
                "uppercase" -> fields[index].map { character ->
                    if (character in 'a'..'f') character.uppercaseChar() else character
                }.joinToString("")
                "leadingSpace" -> " ${fields[index]}"
                "trailingSpace" -> "${fields[index]} "
                else -> error("$context has unknown field transform $transform")
            }
            canonicalRun(fields)
        }
        "runRemoveField" -> {
            val fields = vector(args.requiredString("baseVector")).values.toMutableList()
            fields.removeAt(oneBasedIndex(args.requiredInt("fieldIndex"), context))
            canonicalRun(fields)
        }
        "runDuplicateField" -> {
            val fields = vector(args.requiredString("baseVector")).values.toMutableList()
            val index = oneBasedIndex(args.requiredInt("fieldIndex"), context)
            val line = fields[index]
            when (val placement = args.requiredString("placement")) {
                "inPlace" -> fields.add(index + 1, line)
                "append" -> fields.add(line)
                else -> error("$context has unknown duplicate placement $placement")
            }
            canonicalRun(fields)
        }
        "runAppendField" -> {
            val fields = vector(args.requiredString("baseVector")).values.toMutableList()
            fields.add(args.requiredString("value"))
            canonicalRun(fields)
        }
        "runInsertBlankLineAfterDisposition" -> {
            val fields = vector(args.requiredString("baseVector")).values
            buildString {
                append(HEADER).append('\n').append("RUN\n\n")
                fields.forEach { append(it).append('\n') }
            }.encodeToByteArray()
        }
        "runReplaceField" -> {
            val fields = vector(args.requiredString("baseVector")).values.toMutableList()
            fields[oneBasedIndex(args.requiredInt("fieldIndex"), context)] = args.requiredString("value")
            canonicalRun(fields)
        }
        "stopPlusAsciiFill" -> {
            val targetBytes = args.requiredInt("targetBytes")
            require(targetBytes >= STOP_BYTES) { "$context target is smaller than the STOP record" }
            stopBytes() + ByteArray(targetBytes - STOP_BYTES) { 0x41 }
        }
        "runReplaceFieldWithVectorField" -> {
            val fields = vector(args.requiredString("baseVector")).values.toMutableList()
            val destination = oneBasedIndex(args.requiredInt("destinationFieldIndex"), context)
            val donor = oneBasedIndex(args.requiredInt("donorFieldIndex"), context)
            fields[destination] = fields[donor]
            canonicalRun(fields)
        }
        else -> error("$context has unknown frozen builder $builder")
    }

    private fun oneBasedIndex(index: Int, context: String): Int {
        require(index in 1..FIELD_COUNT) { "$context has out-of-range field index $index" }
        return index - 1
    }

    private fun canonicalRun(fields: List<String>): ByteArray {
        return buildString {
            append(HEADER).append('\n').append("RUN\n")
            fields.forEach { append(it).append('\n') }
        }.encodeToByteArray()
    }

    private fun stopBytes(): ByteArray = "$HEADER\nSTOP\n".encodeToByteArray()
}

internal class FrozenLaunchVector(
    val id: String,
    val names: List<String>,
    val values: List<String>,
    val runBytes: ByteArray,
) {
    fun coordinates(): SelfOwnedAllocationCoordinatesV1 {
        require(values.size == 12) { "Frozen vector $id must contain twelve values" }
        return SelfOwnedAllocationCoordinatesV1(
            allocationId = DomainId(values[0]),
            scope = BranchScope(DomainId(values[1]), DomainId(values[2]), DomainId(values[3])),
            installationId = DomainId(values[4]),
            assignmentId = DomainId(values[5]),
            deviceId = DomainId(values[6]),
            storageInstanceId = DomainId(values[7]),
            storageKeyId = DomainId(values[8]),
            pepperKeyId = DomainId(values[9]),
            targetSigningKeyId = DomainId(values[10]),
            firstActorId = DomainId(values[11]),
        )
    }
}

internal class FrozenLaunchRecord(
    val id: String,
    val bytes: ByteArray,
    val expected: LaunchObservation,
    val caseRefs: List<String>,
)

internal sealed interface LaunchObservation {
    data object Absent : LaunchObservation
    data object Stopped : LaunchObservation
    data object Unresolved : LaunchObservation
    data class Running(val vectorId: String) : LaunchObservation
}

internal data class FrozenRetentionScenario(
    val id: String,
    val initial: String,
    val operation: String,
    val writePhase: String?,
    val partialRecord: String?,
    val verify: String,
    val expected: FrozenRetentionExpectation,
)

internal data class FrozenRetentionExpectation(
    val outcome: String,
    val writeCalls: Int,
    val verificationReads: Int,
    val writtenRecord: String?,
    val finalState: String,
)

internal data class FrozenConcurrencyScenario(
    val id: String,
    val operations: List<String>,
    val successes: Int,
    val failures: Int,
    val writeCalls: Int,
    val verificationReads: Int,
    val eventPatterns: List<List<String>>,
    val finalStateOptions: List<String>,
)

internal data class FrozenCorrectedConcurrency(
    val id: String,
    val expectedSuccesses: Int,
    val expectedFailures: Int,
    val expectedWriteCalls: Int,
    val expectedVerificationReads: Int,
    val assignments: List<FrozenCorrectedAssignment>,
)

internal data class FrozenCorrectedAssignment(
    val variant: String,
    val firstWriteCaller: String,
    val trace: List<String>,
    val failed: String,
    val successful: String,
    val finalState: String,
)

internal data class FrozenDuplicateValuePlan(
    val recordId: String,
    val destinationIndex: Int,
    val donorIndex: Int,
    val changedPositions: List<Int>,
    val duplicatePositions: List<Int>,
    val duplicatedValue: String,
    val removedValue: String,
    val bytes: Int,
    val sha256: String,
)

internal sealed interface LaunchWriteFault {
    data object BeforeBytes : LaunchWriteFault
    data class AfterPartial(val bytes: ByteArray) : LaunchWriteFault
    data object AfterFullBytes : LaunchWriteFault
}

internal sealed interface LaunchVerifyInjection {
    data object Exact : LaunchVerifyInjection
    data object ObservedMissing : LaunchVerifyInjection
    data object Throws : LaunchVerifyInjection
    data class Bytes(val bytes: ByteArray) : LaunchVerifyInjection
}

internal data class LaunchWriteAttempt(
    val ordinal: Int,
    val bytes: ByteArray,
    val stateBefore: ByteArray?,
    val faulted: Boolean,
)

internal sealed interface LaunchStorageEvent {
    val trace: String

    data class WriteAccepted(val bytes: ByteArray) : LaunchStorageEvent {
        override val trace: String = "W:${FrozenLaunchRecordV2Fixtures.label(bytes)}"
    }

    data class WriteRejected(val bytes: ByteArray) : LaunchStorageEvent {
        override val trace: String = "W:throw(${FrozenLaunchRecordV2Fixtures.failedWriteDisposition(FrozenLaunchRecordV2Fixtures.label(bytes))})"
    }

    data class VerificationRead(val bytes: ByteArray?) : LaunchStorageEvent {
        override val trace: String = "V:${FrozenLaunchRecordV2Fixtures.label(bytes)}"
    }

    data object VerificationMissing : LaunchStorageEvent {
        override val trace: String = "V:OBSERVED-MISSING"
    }

    data object VerificationThrew : LaunchStorageEvent {
        override val trace: String = "V:throw"
    }
}

/**
 * Controlled byte storage with real read/replace calls, per-call thread safety and
 * caller-owned copies. It deliberately does not lock across a record's write and
 * verification, so the production record object must provide that serialization.
 */
internal class ControlledLaunchStorage(
    initial: ByteArray? = null,
    private val readThrows: Boolean = false,
    private val writeFault: LaunchWriteFault? = null,
    private val verifyInjection: LaunchVerifyInjection = LaunchVerifyInjection.Exact,
    private val afterWriteAttempt: (Int) -> Unit = {},
) : LocalMasterLaunchStorage {
    private val lock = Any()
    private var retained: ByteArray? = initial?.copyOf()
    private var writes = 0
    private var reads = 0
    private var verifications = 0
    private var awaitingVerification = false
    private val events = mutableListOf<LaunchStorageEvent>()
    private val attempts = mutableListOf<LaunchWriteAttempt>()

    val writeCalls: Int get() = synchronized(lock) { writes }
    val readCount: Int get() = synchronized(lock) { reads }
    val verificationReads: Int get() = synchronized(lock) { verifications }

    override fun read(): ByteArray? = synchronized(lock) {
        reads++
        if (readThrows) throw IllegalStateException("controlled read failure")
        if (!awaitingVerification) return@synchronized retained?.copyOf()
        awaitingVerification = false
        verifications++
        when (val injection = verifyInjection) {
            LaunchVerifyInjection.Exact -> retained?.copyOf().also { events += LaunchStorageEvent.VerificationRead(it) }
            LaunchVerifyInjection.ObservedMissing -> {
                events += LaunchStorageEvent.VerificationMissing
                null
            }
            LaunchVerifyInjection.Throws -> {
                events += LaunchStorageEvent.VerificationThrew
                throw IllegalStateException("controlled verification failure")
            }
            is LaunchVerifyInjection.Bytes -> injection.bytes.copyOf().also {
                events += LaunchStorageEvent.VerificationRead(it)
            }
        }
    }

    override fun replaceAndForce(bytes: ByteArray) {
        val (ordinal, failure) = synchronized(lock) {
            writes++
            val snapshot = bytes.copyOf()
            val before = retained?.copyOf()
            val fault = writeFault?.takeIf { writes == 1 }
            attempts += LaunchWriteAttempt(writes, snapshot.copyOf(), before, fault != null)
            val failure = when (fault) {
                null -> {
                    retained = snapshot
                    awaitingVerification = true
                    events += LaunchStorageEvent.WriteAccepted(snapshot)
                    null
                }
                LaunchWriteFault.BeforeBytes -> {
                    events += LaunchStorageEvent.WriteRejected(snapshot)
                    IllegalStateException("controlled write failure before bytes")
                }
                is LaunchWriteFault.AfterPartial -> {
                    retained = fault.bytes.copyOf()
                    events += LaunchStorageEvent.WriteRejected(snapshot)
                    IllegalStateException("controlled write failure after a partial prefix")
                }
                LaunchWriteFault.AfterFullBytes -> {
                    retained = snapshot
                    events += LaunchStorageEvent.WriteRejected(snapshot)
                    IllegalStateException("controlled write failure after full bytes")
                }
            }
            writes to failure
        }
        // A test may pause here while the real record still owns its monitor.
        // The storage bookkeeping lock is already released: another writer
        // can enter immediately if the record fails to serialize callers.
        afterWriteAttempt(ordinal)
        if (failure != null) throw failure
    }

    fun setObservedBytes(bytes: ByteArray?) {
        synchronized(lock) { retained = bytes?.copyOf() }
    }

    fun storedBytes(): ByteArray? = synchronized(lock) { retained?.copyOf() }

    fun writeAttempts(): List<LaunchWriteAttempt> = synchronized(lock) {
        attempts.map { LaunchWriteAttempt(it.ordinal, it.bytes.copyOf(), it.stateBefore?.copyOf(), it.faulted) }
    }

    fun events(): List<LaunchStorageEvent> = synchronized(lock) { events.toList() }

    fun trace(): List<String> = events().map { it.trace }
}

internal fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun JsonObject.requiredObject(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.requiredArray(name: String): kotlinx.serialization.json.JsonArray = getValue(name).jsonArray
private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.int
private fun JsonObject.optionalString(name: String): String? {
    val element: JsonElement = this[name] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.content
}
