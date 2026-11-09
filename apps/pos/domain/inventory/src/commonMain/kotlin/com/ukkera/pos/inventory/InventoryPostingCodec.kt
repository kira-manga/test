package com.ukkera.pos.inventory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Canonical bounded HISTORICAL facts. No plan/proof/authorization/deadline is decoded. The envelope
 * binds only domain coordinates; the adapter must bind complete cut bytes, full branch scope,
 * producer and source/upstream claims independently. It must decode incoming UTF-8 strictly.
 * The 64 KiB batch ceiling is NOT permission for a 64 KiB batch plus a companion wrapper: a future
 * store must separately bound the COMPLETE row to 64 KiB and the COMPLETE proposal to 1 MiB.
 */
public object InventoryPostingCodec {
    public const val SCHEMA_VERSION: Int = 1
    public const val PROFILE: String = "purchase-retail-expiry-v1"
    public const val MAX_ENCODED_BYTES: Int = 65_536
    public const val MAX_EFFECTS: Int = 1_024
    public const val MAX_STORED_RECORDS: Int = 4_096
    public const val MAX_ARRAY_ENTRIES: Int = 4_096
    public const val MAX_JSON_CONTAINERS: Int = 8_192
    public const val MAX_JSON_DEPTH: Int = 24

    public fun encode(batch: InventoryPostingBatch): String = checkedInventoryPosting {
        checkInventoryPostingBatchBudget(batch)
        validateInventoryPostingBatch(batch)
        postingJson.encodeToString(StoredInventoryPosting.from(batch)).also(::boundInventoryPostingJson)
    }

    /** Expected metadata must be obtained independently, never copied out of this payload. */
    public fun decode(encoded: String, expectedBinding: InventoryPostingBinding): InventoryPostingBatch = checkedInventoryPosting {
        boundInventoryPostingJson(encoded)
        validateInventoryPostingBinding(expectedBinding)
        val stored = postingJson.decodeFromString<StoredInventoryPosting>(encoded)
        inventoryPostingCheck(stored.schemaVersion == SCHEMA_VERSION && stored.profile == PROFILE &&
            stored.calculationVersion == MovingAveragePlanner.CALCULATION_VERSION, InventoryPostingFailure.UNSUPPORTED_VERSION)
        inventoryPostingCheck(stored.binding() == expectedBinding, InventoryPostingFailure.BINDING_MISMATCH)
        inventoryPostingCheck(postingJson.encodeToString(stored) == encoded, InventoryPostingFailure.NON_CANONICAL)
        checkStoredInventoryPostingBudget(stored)
        InventoryPostingBatch(expectedBinding, stored.effects.map { it.restore() }).also {
            checkInventoryPostingBatchBudget(it)
            validateInventoryPostingBatch(it)
        }
    }
}

private val postingJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

/** No recursive parser or DTO is allocated before this bounded UTF/structure scan. */
private fun boundInventoryPostingJson(encoded: String) {
    inventoryPostingCheck(encoded.length <= InventoryPostingCodec.MAX_ENCODED_BYTES, InventoryPostingFailure.TOO_LARGE)
    var bytes = 0
    var index = 0
    var quoted = false
    var escaped = false
    var containers = 0
    val frames = mutableListOf<Pair<Char, Int>>()
    while (index < encoded.length) {
        val character = encoded[index]
        bytes += when (character.code) {
            in 0..0x7F -> 1
            in 0x80..0x7FF -> 2
            in 0xD800..0xDBFF -> {
                inventoryPostingCheck(index + 1 < encoded.length && encoded[index + 1].code in 0xDC00..0xDFFF,
                    InventoryPostingFailure.MALFORMED)
                index++
                4
            }
            in 0xDC00..0xDFFF -> throw InventoryPostingRejectedException(InventoryPostingFailure.MALFORMED)
            else -> 3
        }
        inventoryPostingCheck(bytes <= InventoryPostingCodec.MAX_ENCODED_BYTES, InventoryPostingFailure.TOO_LARGE)
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                containers++
                inventoryPostingCheck(frames.size < InventoryPostingCodec.MAX_JSON_DEPTH &&
                    containers <= InventoryPostingCodec.MAX_JSON_CONTAINERS, InventoryPostingFailure.TOO_LARGE)
                frames += character to 0
            }
            '}', ']' -> {
                inventoryPostingCheck(frames.isNotEmpty() && frames.last().first == if (character == '}') '{' else '[',
                    InventoryPostingFailure.MALFORMED)
                frames.removeAt(frames.lastIndex)
            }
            ',' -> if (frames.isNotEmpty() && frames.last().first == '[') {
                val count = frames.last().second + 1
                inventoryPostingCheck(count < InventoryPostingCodec.MAX_ARRAY_ENTRIES, InventoryPostingFailure.TOO_LARGE)
                frames[frames.lastIndex] = '[' to count
            }
        }
        index++
    }
    inventoryPostingCheck(!quoted && frames.isEmpty(), InventoryPostingFailure.MALFORMED)
}

/** Conservative allocation guard, not a guarantee that N records fit the final encoded row. */
internal class InventoryPostingBudget {
    private var records = 0L
    private var bytes = 1_024L

    fun add(count: Int, bytesEach: Int) {
        inventoryPostingCheck(count in 0..InventoryPostingCodec.MAX_ARRAY_ENTRIES, InventoryPostingFailure.TOO_LARGE)
        records += count
        bytes += count.toLong() * bytesEach
        inventoryPostingCheck(records <= InventoryPostingCodec.MAX_STORED_RECORDS && bytes <= InventoryPostingCodec.MAX_ENCODED_BYTES,
            InventoryPostingFailure.TOO_LARGE)
    }

    fun reservation(allocations: Int, holds: Int) {
        inventoryPostingCheck(allocations <= InventoryPlanner.MAX_ALLOCATIONS &&
            holds <= InventoryPlanner.MAX_ALLOCATIONS * ReservationHoldReason.entries.size + 1, InventoryPostingFailure.TOO_LARGE)
        add(1, 1_024); add(allocations, 1_536); add(holds, 1_024)
    }

    fun issue(allocations: Int) {
        inventoryPostingCheck(allocations <= InventoryPlanner.MAX_ALLOCATIONS, InventoryPostingFailure.TOO_LARGE)
        add(1, 1_024); add(allocations, 1_792)
    }

    fun slices(count: Int) {
        inventoryPostingCheck(count <= MovingAveragePlanner.MAX_SLICES, InventoryPostingFailure.TOO_LARGE)
        add(count, 512)
    }
}

internal fun checkInventoryPostingPlanBudget(plan: InventoryPlan) {
    inventoryPostingCheck(plan.effects.size <= InventoryPostingCodec.MAX_EFFECTS, InventoryPostingFailure.TOO_LARGE)
    val budget = InventoryPostingBudget()
    for (effect in plan.effects) when (effect) {
        is InventoryEffect.Movement -> budget.add(1, 1_024)
        is InventoryEffect.Valuation -> { budget.add(1, 1_536); budget.fact(effect.plan.fact) }
        is InventoryEffect.ReservationRecorded -> budget.reservation(effect.reservation.allocations.size, effect.reservation.holds.size)
        is InventoryEffect.SafetyChanged -> budget.add(2, 1_024)
        is InventoryEffect.IssueRecorded -> budget.issue(effect.issue.allocations.size)
        else -> throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
    }
}

internal fun checkInventoryPostingBatchBudget(batch: InventoryPostingBatch) {
    inventoryPostingCheck(batch.effects.size <= InventoryPostingCodec.MAX_EFFECTS, InventoryPostingFailure.TOO_LARGE)
    val budget = InventoryPostingBudget()
    for (effect in batch.effects) when (effect) {
        is InventoryPostingEffect.Movement -> budget.add(1, 1_024)
        is InventoryPostingEffect.Valuation -> { budget.add(1, 1_536); budget.fact(effect.fact) }
        is InventoryPostingEffect.ReservationRecorded -> budget.reservation(effect.reservation.allocations.size, effect.reservation.holds.size)
        is InventoryPostingEffect.SafetyChanged -> budget.add(2, 1_024)
        is InventoryPostingEffect.IssueRecorded -> budget.issue(effect.issue.allocations.size)
    }
}

private fun InventoryPostingBudget.fact(fact: ValuationFact?) {
    when (fact) {
        is ValuationFact.Receipt -> add(1, 640)
        is ValuationFact.Issue -> slices(fact.slices.size)
        else -> throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
    }
}

private fun checkStoredInventoryPostingBudget(stored: StoredInventoryPosting) {
    inventoryPostingCheck(stored.effects.size <= InventoryPostingCodec.MAX_EFFECTS, InventoryPostingFailure.TOO_LARGE)
    val budget = InventoryPostingBudget()
    for (effect in stored.effects) when (effect) {
        is StoredInventoryPostingEffect.Movement -> budget.add(1, 1_024)
        is StoredInventoryPostingEffect.Valuation -> {
            budget.add(1, 1_536)
            when (val fact = effect.fact) {
                is StoredValueFact.Receipt -> budget.add(1, 640)
                is StoredValueFact.Issue -> budget.slices(fact.slices.size)
                is StoredValueFact.Restoration, is StoredValueFact.TransferOut, is StoredValueFact.TransferIn ->
                    throw InventoryPostingRejectedException(InventoryPostingFailure.UNSUPPORTED_STATE)
            }
        }
        is StoredInventoryPostingEffect.ReservationRecorded -> budget.reservation(effect.reservation.allocations.size, effect.reservation.holds.size)
        is StoredInventoryPostingEffect.SafetyChanged -> budget.add(2, 1_024)
        is StoredInventoryPostingEffect.IssueRecorded -> {
            inventoryPostingCheck(effect.issue.kitchen == null, InventoryPostingFailure.UNSUPPORTED_STATE)
            budget.issue(effect.issue.allocations.size)
        }
    }
}

internal fun inventoryPostingCheck(condition: Boolean, failure: InventoryPostingFailure = InventoryPostingFailure.INCONSISTENT_STATE) {
    if (!condition) throw InventoryPostingRejectedException(failure)
}

internal inline fun <T> checkedInventoryPosting(block: () -> T): T = try {
    block()
} catch (failure: InventoryPostingRejectedException) {
    throw failure
} catch (failure: InventorySnapshotRejectedException) {
    throw InventoryPostingRejectedException(InventoryPostingFailure.valueOf(failure.failure.name))
} catch (_: SerializationException) {
    throw InventoryPostingRejectedException(InventoryPostingFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
} catch (_: ArithmeticException) {
    throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
} catch (_: IllegalStateException) {
    throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
} catch (_: NoSuchElementException) {
    throw InventoryPostingRejectedException(InventoryPostingFailure.INCONSISTENT_STATE)
}
