package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public enum class InventorySnapshotKind { BRANCH_INVENTORY }

/** Independent protected-row metadata, never copied from an untrusted payload. */
public data class InventorySnapshotBinding(
    public val kind: InventorySnapshotKind,
    public val scope: InventoryScope,
    /** This bounded whole-cut profile uses the branch ID as its one inventory row ID. */
    public val id: DomainId,
    public val revision: Revision,
)

public enum class InventorySnapshotFailure {
    MALFORMED, TOO_LARGE, NON_CANONICAL, UNSUPPORTED_VERSION, BINDING_MISMATCH, INCONSISTENT_STATE,
}

/** No protected payload, financial evidence or parser transcript is included in this error. */
public class InventorySnapshotRejectedException(public val failure: InventorySnapshotFailure) :
    IllegalArgumentException(failure.name)

/**
 * Versioned protected LOCAL storage, not an RPC, authorization capability or recovery admission.
 * This is a bounded whole-cut foundation, not a production full-branch document scaling design.
 * Related sales, source/result indexes and complete authoritative history remain store obligations.
 * No command, InventoryPlan, ReservationProof or IssueProof is serialized or reconstructed.
 */
public object InventorySnapshotCodec {
    public const val SCHEMA_VERSION: Int = 1
    public const val MAX_ENCODED_BYTES: Int = 8 * 1_024 * 1_024
    public const val MAX_ARRAY_ENTRIES: Int = 16_384
    public const val MAX_STORED_RECORDS: Int = 16_384
    public const val MAX_JSON_CONTAINERS: Int = 65_536
    public const val MAX_JSON_DEPTH: Int = 24
    /** Structural replay units: events * (1 + facts + 2 * slices + targets), not a CPU-step count. */
    public const val MAX_VALUATION_VERIFICATION_STEPS: Int = 262_144

    public fun encode(snapshot: InventorySnapshot): String = checkedInventorySnapshot {
        requireLegacyInventoryFormat(snapshot)
        checkInventoryStorageBudget(snapshot)
        validateStoredInventory(snapshot)
        val envelope = StoredInventoryEnvelope(SCHEMA_VERSION, InventorySnapshotKind.BRANCH_INVENTORY.name,
            StoredInventoryScope.from(snapshot.scope), snapshot.scope.branchId, snapshot.revision,
            StoredInventoryState.from(snapshot))
        inventorySnapshotJson.encodeToString(envelope).also(::boundInventoryJson)
    }

    /**
     * Verifies the supplied historical cut without changing its time/safety/monetary evidence.
     * Callers must separately revalidate current safety under authoritative time and transaction CAS.
     * Domain revision zero is not silently mapped to the database's initial aggregate revision one.
     */
    public fun decode(encoded: String, expected: InventorySnapshotBinding): InventorySnapshot = checkedInventorySnapshot {
        boundInventoryJson(encoded)
        inventorySnapshotCheck(expected.kind == InventorySnapshotKind.BRANCH_INVENTORY && expected.id == expected.scope.branchId,
            InventorySnapshotFailure.BINDING_MISMATCH)
        val envelope = inventorySnapshotJson.decodeFromString<StoredInventoryEnvelope>(encoded)
        inventorySnapshotCheck(envelope.schemaVersion == SCHEMA_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        inventorySnapshotCheck(envelope.kind == expected.kind.name && envelope.scope.restore() == expected.scope &&
            envelope.id == expected.id && envelope.revision == expected.revision, InventorySnapshotFailure.BINDING_MISMATCH)
        inventorySnapshotCheck(inventorySnapshotJson.encodeToString(envelope) == encoded, InventorySnapshotFailure.NON_CANONICAL)
        envelope.state.restore(expected).also {
            requireLegacyInventoryFormat(it)
            checkInventoryStorageBudget(it)
            validateStoredInventory(it)
        }
    }
}

private val inventorySnapshotJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

@Serializable
private data class StoredInventoryEnvelope(
    val schemaVersion: Int,
    val kind: String,
    val scope: StoredInventoryScope,
    val id: DomainId,
    val revision: Revision,
    val state: StoredInventoryState,
)

/** Checks bytes, arrays, container count/depth and UTF-16 validity BEFORE recursive JSON parsing. */
private fun boundInventoryJson(encoded: String) {
    inventorySnapshotCheck(encoded.length <= InventorySnapshotCodec.MAX_ENCODED_BYTES, InventorySnapshotFailure.TOO_LARGE)
    var bytes = 0
    var index = 0
    var quoted = false
    var escaped = false
    var containers = 0
    val frames = mutableListOf<Pair<Char, Int>>()
    while (index < encoded.length) {
        val character = encoded[index]
        val code = character.code
        bytes += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            code in 0xD800..0xDBFF -> {
                inventorySnapshotCheck(index + 1 < encoded.length && encoded[index + 1].code in 0xDC00..0xDFFF,
                    InventorySnapshotFailure.MALFORMED)
                index++
                4
            }
            code in 0xDC00..0xDFFF -> throw InventorySnapshotRejectedException(InventorySnapshotFailure.MALFORMED)
            else -> 3
        }
        inventorySnapshotCheck(bytes <= InventorySnapshotCodec.MAX_ENCODED_BYTES, InventorySnapshotFailure.TOO_LARGE)
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                containers++
                inventorySnapshotCheck(frames.size < InventorySnapshotCodec.MAX_JSON_DEPTH &&
                    containers <= InventorySnapshotCodec.MAX_JSON_CONTAINERS, InventorySnapshotFailure.TOO_LARGE)
                frames += character to 0
            }
            '}', ']' -> {
                inventorySnapshotCheck(frames.isNotEmpty() && frames.last().first == if (character == '}') '{' else '[',
                    InventorySnapshotFailure.MALFORMED)
                frames.removeAt(frames.lastIndex)
            }
            ',' -> if (frames.isNotEmpty() && frames.last().first == '[') {
                val count = frames.last().second + 1
                inventorySnapshotCheck(count < InventorySnapshotCodec.MAX_ARRAY_ENTRIES, InventorySnapshotFailure.TOO_LARGE)
                frames[frames.lastIndex] = '[' to count
            }
        }
        index++
    }
    inventorySnapshotCheck(!quoted && frames.isEmpty(), InventorySnapshotFailure.MALFORMED)
}

internal fun inventorySnapshotCheck(
    condition: Boolean,
    failure: InventorySnapshotFailure = InventorySnapshotFailure.INCONSISTENT_STATE,
) {
    if (!condition) throw InventorySnapshotRejectedException(failure)
}

internal inline fun <T> checkedInventorySnapshot(block: () -> T): T = try {
    block()
} catch (failure: InventorySnapshotRejectedException) {
    throw failure
} catch (_: SerializationException) {
    throw InventorySnapshotRejectedException(InventorySnapshotFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
} catch (_: ArithmeticException) {
    throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
} catch (_: IllegalStateException) {
    throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
} catch (_: NoSuchElementException) {
    throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
}
