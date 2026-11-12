package com.ukkera.pos.database

import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.LocalSessionViewResult
import kotlinx.coroutines.*
import kotlin.test.*

/** STC-S10/S11 auth/client supporting slices. The delivery barriers hold genuine Room
 * results; they never create a Current/Denied response or authorize a protected call.
 * Full private-resource, financial, native and RPC cases remain unqualified. */
class RoomAuthenticationObservationRaceFrozenSlicesTest {
    @Test fun stcS10_logoutAfterCredentialAcquisitionRetiresBeforeAnotherObservation() = withFixture { f ->
        val baseline = f.businessRows()
        val gate = f.holdSecondWorkflowObservation()
        try {
            coroutineScope {
                val read = async { f.assertClientReadDenied() }
                try {
                    withTimeout(10_000) { gate.secondEntered.await() }
                    assertIs<LocalSessionViewResult.Current>(gate.firstObserved.await())
                    assertEquals(LocalSignOutResult.Revoked, f.endpoint.logout(f.originalAccess()))
                    gate.allowObservation.complete(Unit)
                    assertEquals(LocalSessionViewResult.Denied, withTimeout(10_000) { gate.secondObserved.await() })
                    gate.allowDelivery.complete(Unit)
                    withTimeout(10_000) { read.await() }
                    f.assertRetiredBeforeAnotherObservation()
                } finally { gate.release() }
            }
            f.assertOriginalDenied()
            f.assertClientRetired()
            f.assertOtherAccountCurrent()
            assertEquals(baseline, f.businessRows())
        } finally { gate.release() }
    }

    @Test fun stcS11_callerCancellationCannotDiscardActualSecondObservationDenial() = withFixture { f ->
        val baseline = f.businessRows()
        val gate = f.holdSecondWorkflowObservation()
        try {
            coroutineScope {
                val read = async { f.assertClientReadDenied() }
                try {
                    withTimeout(10_000) { gate.secondEntered.await() }
                    assertIs<LocalSessionViewResult.Current>(gate.firstObserved.await())
                    assertEquals(LocalSignOutResult.Revoked, f.endpoint.logout(f.originalAccess()))
                    gate.allowObservation.complete(Unit)
                    assertEquals(LocalSessionViewResult.Denied, withTimeout(10_000) { gate.secondObserved.await() })
                    read.cancel()
                    gate.allowDelivery.complete(Unit)
                    withTimeout(10_000) { read.join() }
                    assertTrue(read.isCancelled)
                    f.assertRetiredBeforeAnotherObservation()
                } finally { gate.release() }
            }
            f.assertOriginalDenied()
            f.assertClientRetired()
            f.assertOtherAccountCurrent()
            assertEquals(baseline, f.businessRows())
        } finally { gate.release() }
    }

    private fun withFixture(body: suspend (RoomAuthenticationClientFixture) -> Unit) = runBlocking {
        val fixture = RoomAuthenticationClientFixture()
        try { fixture.start(); body(fixture) }
        finally { withContext(NonCancellable) { fixture.close() } }
    }
}
