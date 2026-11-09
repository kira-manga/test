@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnstockrefusal

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedStreamingMutation
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedMutationDecision
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.UnavailableLocalStockAuthority
import com.ukkera.pos.sales.store.LocalReturnStockHandler
import com.ukkera.pos.sales.store.LocalReturnStockProfileV1
import com.ukkera.pos.sales.store.LocalReturnStockRefused
import com.ukkera.pos.sales.store.LocalReturnStockRequestV1
import com.ukkera.pos.sales.store.LocalReturnStockResultCodecV1
import com.ukkera.pos.sales.store.LocalReturnStockResultV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.ukkera.pos.sales.store.independent.returnstockrefusal.FrozenReturnStockRefusalInputs as F

/**
 * Supported result-schema/capability prerequisites for RSDF-003/004(a)/006(b).
 * The stale reason is captured from an isolated pure proposal before constructing
 * its supported rejection shape. It is never called the durable native R-STALE.
 * The machine-code spelling binds the existing public result schema to the frozen
 * stale CURRENT DISPOSITION family; no acceptance-specific error is invented.
 *
 * A directly constructed CommandContext is inert preparation DATA only. The view
 * contains no CASE/stock/producer, and the real unavailable authority grants nothing.
 * Neither propose(), authentication, authorization, a native sink nor a history
 * reader is invoked. supportsRecordedRejection is a capability predicate only,
 * never proof of atomic recording, recovery, result ownership or current permission.
 * The original actor/source/D0/refusal-time-D1/result identity and protected native
 * reads, exact retry and restart assertions remain UNBOUND.
 */
class ReturnStockRefusalFrozenPreparedPredicateTest {
    @Test
    fun rsdf003_004a_canonicalStaleReasonSchemaAndCapability_only() = runTest {
        val data = FrozenReturnStockRefusalData()
        val stale = capturePureStale(data)
        val prepared = prepareCapability(data, stale.request)
        val decoded = LocalReturnStockResultCodecV1.decode(stale.rejection.result)
        assertEquals(LocalReturnStockResultV1.Rejected("DISPOSITION_REVISION_CHANGED"), decoded)
        assertEquals(stale.rejection.result, LocalReturnStockResultCodecV1.encode(decoded))
        assertTrue(prepared.supportsRecordedRejection(stale.rejection))
    }

    @Test
    fun rsdf003_004a_006b_changedSuccessOrUnavailableBytesCannotQualifyAsCanonicalStaleResult() = runTest {
        val data = FrozenReturnStockRefusalData()
        val stale = capturePureStale(data)
        val prepared = prepareCapability(data, stale.request)
        val bytes = stale.rejection.result.copyBytes()
        val variants = listOf(
            "different stable code" to StreamedMutationDecision.Reject("sales.return.stock.changed", stale.rejection.result),
            "other operation's applied result" to StreamedMutationDecision.Reject(stale.rejection.code,
                LocalReturnStockResultCodecV1.encode(stale.firstApplied)),
            "truncated result bytes" to StreamedMutationDecision.Reject(stale.rejection.code,
                StoredBytes(bytes.copyOf(bytes.size - 1))),
            "unavailable result bytes" to StreamedMutationDecision.Reject(stale.rejection.code, StoredBytes(byteArrayOf())),
        )
        variants.forEach { (name, candidate) ->
            assertFalse(prepared.supportsRecordedRejection(candidate), name)
        }
        assertTrue(prepared.supportsRecordedRejection(stale.rejection))
        // No original-result store exists in this test; it cannot prove that any
        // stored original survived damage, a later C2 cut, query, retry or restart.
    }

    private fun capturePureStale(data: FrozenReturnStockRefusalData): PureStaleShape {
        val initial = data.request()
        val first = data.post(initial)
        assertEquals(F.quantity(F.cut("C1").literal("current_disposed_quantity")), first.after.postedQuantity)
        assertEquals(F.quantity(F.cut("C1").literal("remaining_disposition_quantity")), first.result.remainingQuantity)
        val request = data.request(first.after, restorationNumber = 2)
            .copy(expectedDispositionRevision = initial.expectedDispositionRevision)
        assertEquals(data.accepted.quantity, request.acceptedQuantity)
        assertEquals(first.after.postedQuantity, request.expectedPostedQuantity)
        val failure = assertFailsWith<LocalReturnStockRefused> {
            data.post(request, first.after, operationNumber = 2)
        }
        assertEquals("DISPOSITION_REVISION_CHANGED", failure.reason)
        return PureStaleShape(request, StreamedMutationDecision.Reject("sales.return.stock.rejected",
            LocalReturnStockResultCodecV1.encode(LocalReturnStockResultV1.Rejected(failure.reason))), first.result)
    }

    private suspend fun prepareCapability(data: FrozenReturnStockRefusalData,
        request: LocalReturnStockRequestV1): PreparedStreamingMutation {
        val normalized = data.normalized(request)
        val context = CommandContext(F.scope, data.actorId, F.operation(302), LocalReturnStockProfileV1.COMMAND,
            semanticDigest(F.scope, data.actorId, LocalReturnStockProfileV1.COMMAND, normalized))
        return LocalReturnStockHandler(LocalStockRepository(), UnavailableLocalStockAuthority)
            .prepare(normalized, AbsentCasePreparationView(), context)
    }

    private data class PureStaleShape(
        val request: LocalReturnStockRequestV1,
        val rejection: StreamedMutationDecision.Reject,
        val firstApplied: LocalReturnStockResultV1.Applied,
    )

    /** No positive original-source, absence census or native authority is supplied. */
    private class AbsentCasePreparationView : CommandReadView {
        override val scope: BranchScope = F.scope
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = null
        override suspend fun claim(key: ClaimKey): DomainId? = error("Predicate preparation must not read source claims")
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
            error("No original history is bound to this predicate test")
    }
}
