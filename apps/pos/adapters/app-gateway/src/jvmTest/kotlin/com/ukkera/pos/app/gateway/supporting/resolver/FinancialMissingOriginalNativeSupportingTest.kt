package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Blueprint5.3; frozen LAN-A07/D04/D06 at
 * qa/pos/designs/lan-master-client-v1/cases.json and v2/cases.json. Genuine native/Room
 * protected endpoint with an actually authorized original account. Missing new-key targets
 * must defer AFTER authorization, retain no known original/effects, and cold-open normally.
 * Bounded Linux software OS cohost support only; not additional-device/TCP qualification. */
class FinancialMissingOriginalNativeSupportingTest {
    @Test fun authorizedMissingSupervisorTargetsNeverInventKnownOriginals(): Unit = runBlocking {
        val f = FinancialResolverNativeFixture()
        try {
            f.install()
            val missing = OperationId.random()
            val requests = listOf(
                LocalSupplierCashSupervisorCodec.command(OperationId.random(), LocalSupplierCashSupervisorRequest.Review(missing)),
                LocalSupplierCashSupervisorCodec.command(OperationId.random(), LocalSupplierCashSupervisorRequest.Apply(missing, LocalSupplierCashPhase.ORIGINAL)),
                LocalSupplierCashSupervisorCodec.command(OperationId.random(), LocalSupplierCashSupervisorRequest.Apply(missing, LocalSupplierCashPhase.RESOLUTION)),
                LocalFoodRefundSupervisorCodec.command(OperationId.random(), LocalFoodRefundSupervisorRequest.Review(missing)),
                LocalFoodRefundSupervisorCodec.command(OperationId.random(), LocalFoodRefundSupervisorRequest.Apply(missing, LocalFoodRefundFactPhase.ORIGINAL)),
                LocalFoodRefundSupervisorCodec.command(OperationId.random(), LocalFoodRefundSupervisorRequest.Apply(missing, LocalFoodRefundFactPhase.RESOLUTION)),
            )
            for (request in requests) {
                repeat(3) {
                    val refused = assertIs<MutationReply.Deferred>(f.execute(request))
                    // This exact reason is reached after genuine per-action authority;
                    // AUTHORIZATION_DENIED cannot pass as a missing-source result.
                    assertEquals(DeferredReason.CURRENT_SOURCE_UNAVAILABLE, refused.reason)
                    assertEquals(request.operationId, refused.operationId)
                }
                assertTrue(f.operationCounts(request.operationId).values.all { it == 0L })
            }
            f.reopen()
            for (request in requests) {
                val same = assertIs<MutationReply.Deferred>(f.execute(request))
                assertEquals(DeferredReason.CURRENT_SOURCE_UNAVAILABLE, same.reason)
                assertTrue(f.operationCounts(request.operationId).values.all { it == 0L })
            }
        } finally { f.close() }
    }
}
