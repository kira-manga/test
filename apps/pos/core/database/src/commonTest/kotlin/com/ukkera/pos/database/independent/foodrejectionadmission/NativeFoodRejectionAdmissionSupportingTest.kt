package com.ukkera.pos.database.independent.foodrejectionadmission

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.PreparedStreamingMutation
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedCommandSink
import com.ukkera.pos.database.StreamedMutationDecision
import com.ukkera.pos.database.nativeStreamingRejectionAdmission
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Frozen support-v1 selection only; no native producer, recorder, grant or durable view. */
class NativeFoodRejectionAdmissionSupportingTest {
    @Test
    fun fnraS001UnsupportedOrdinaryFoodSelectsNonrecordingHold() {
        val refusal = refusal("food.stock.capacity_required")
        assertEquals(DeferredReason.CURRENT_SOURCE_UNAVAILABLE,
            nativeStreamingRejectionAdmission(StockStreamingCommandsV2.FOOD, MissingSupport(), refusal))
        assertEquals(DeferredReason.CURRENT_SOURCE_UNAVAILABLE,
            nativeStreamingRejectionAdmission(StockStreamingCommandsV2.FOOD, ControlledSupport(false), refusal))
    }

    @Test
    fun fnraS002UnsupportedExpirySelectsPreconditionExpired() {
        val refusal = refusal("precondition.expired")
        assertEquals(DeferredReason.PRECONDITION_EXPIRED,
            nativeStreamingRejectionAdmission(StockStreamingCommandsV2.FOOD, MissingSupport(), refusal))
        assertEquals(DeferredReason.PRECONDITION_EXPIRED,
            nativeStreamingRejectionAdmission(StockStreamingCommandsV2.FOOD, ControlledSupport(false), refusal))
    }

    @Test
    fun fnraS003ExplicitSupportSelectsContinuationNotTerminalSuccess() {
        for (code in listOf("food.stock.capacity_required", "precondition.expired")) {
            // Null selects the existing protected path; it does not record or publish a result.
            assertNull(nativeStreamingRejectionAdmission(StockStreamingCommandsV2.FOOD,
                ControlledSupport(true), refusal(code)))
        }
        // Existing RETURN/other-command classification is preserved, not FOOD native proof.
        assertEquals(DeferredReason.CURRENT_SOURCE_UNAVAILABLE,
            nativeStreamingRejectionAdmission(StockStreamingCommandsV2.RETURN,
                MissingSupport(), refusal("food.stock.capacity_required")))
        assertEquals(DeferredReason.PRECONDITION_EXPIRED,
            nativeStreamingRejectionAdmission(StockStreamingCommandsV2.RETURN,
                MissingSupport(), refusal("precondition.expired")))
        assertNull(nativeStreamingRejectionAdmission(StockStreamingCommandsV2.RETURN,
            ControlledSupport(true), refusal("food.stock.capacity_required")))
        for (key in listOf(StockStreamingCommandsV2.STOCK, StockStreamingCommandsV2.COLLECTION,
            CommandKey("sales.food.stock", 1))) {
            assertNull(nativeStreamingRejectionAdmission(key, MissingSupport(), refusal("precondition.expired")))
        }
    }

    private fun refusal(code: String): StreamedMutationDecision.Reject =
        StreamedMutationDecision.Reject(code, StoredBytes(byteArrayOf()))

    private open class MissingSupport : PreparedStreamingMutation() {
        override val requirement: AuthorizationRequirement get() = error("No authority in selection fixture")
        override val affectedResources: Set<RecoveryResourceKey> get() = error("No resource proof in selection fixture")
        override suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant,
            sink: StreamedCommandSink): StreamedMutationDecision = error("Selection fixture cannot propose")
    }

    /** Controlled selector is deliberately not a genuine native qualification. */
    private class ControlledSupport(private val supported: Boolean) : MissingSupport() {
        override fun supportsRecordedRejection(rejection: StreamedMutationDecision.Reject): Boolean = supported
    }
}
