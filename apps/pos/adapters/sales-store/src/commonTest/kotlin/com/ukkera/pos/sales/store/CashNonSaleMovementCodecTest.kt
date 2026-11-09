package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CashNonSaleMovementCodecTest {
    @Test fun historicalInventoryCashProjectionAndResultStillDecodeWithoutEnablingANewSettlement() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before, NonSaleCashKind.INVENTORY_SETTLEMENT)
        // Synthetic historical projection of the earlier cash-only profile, not
        // a new handler success, supplier invoice settlement or durable fixture.
        val historical = fixture.transition(before, request)
        val movement = historical.effects.filterIsInstance<CashMovement>().single()
        val context = fixture.context(request, operation = historical.command.operationId)
        val bytes = CashNonSaleMovementCodec.encode(context, before, historical.value, request, movement, fixture.now)
        val restored = CashNonSaleMovementCodec.decode(bytes, fixture.scope, request.sourceId, Revision(1))
        assertEquals(NonSaleCashKind.INVENTORY_SETTLEMENT, restored.kind)
        assertEquals(-request.amount, restored.delta)
        assertEquals(historical.command.operationId, restored.operationId)
        val oldResult = CashNonSaleResultV1.Applied(before.id, historical.value.revision, request.sourceId,
            historical.value.cashLedgerRevision, historical.value.expectedCash)
        assertEquals(oldResult, CashNonSaleResultCodec.decode(CashNonSaleResultCodec.encode(oldResult)))
        assertUnsupported(CashNonSaleUnsupported.INVENTORY_SETTLEMENT_SOURCE_REQUIRED,
            fixture.evaluate(request, fixture.cut(before), context))
    }

    @Test fun protectedMovementDecodeRequiresIndependentScopeSourceAndInsertRevision() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val outcome = assertIs<ProposedOutcome.Apply>(fixture.evaluate(request, fixture.cut(before)))
        val bytes = outcome.changes.single { it.key.kind == CashNonSaleStoreProfile.MOVEMENT_KIND }.payload
        val restored = CashNonSaleMovementCodec.decode(bytes, fixture.scope, request.sourceId, Revision(1))
        assertEquals(request.sourceId, restored.sourceId)
        assertEquals(fixture.scope, restored.scope)
        assertFailsWith<IllegalArgumentException> {
            CashNonSaleMovementCodec.decode(bytes, fixture.scope.copy(branchId = fixture.nextId()), request.sourceId, Revision(1))
        }
        assertFailsWith<IllegalArgumentException> { CashNonSaleMovementCodec.decode(bytes, fixture.scope, fixture.nextId(), Revision(1)) }
        for (revision in listOf(Revision(0), Revision(2))) assertFailsWith<IllegalArgumentException> {
            CashNonSaleMovementCodec.decode(bytes, fixture.scope, request.sourceId, revision)
        }
    }

    @Test fun unsupportedSchemasAndForgedCategoryRevisionOrDeltaDoNotBecomeDomainEffects() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val outcome = assertIs<ProposedOutcome.Apply>(fixture.evaluate(request, fixture.cut(before)))
        val canonical = outcome.changes.single { it.key.kind == CashNonSaleStoreProfile.MOVEMENT_KIND }.payload.cashText()
        val invalid = listOf(
            canonical.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            canonical.replace("NON_SALE_PAY_IN", "CUSTOMER_RECEIPT"),
            canonical.replace("NON_SALE_PAY_IN", "OPERATING_EXPENSE"),
            canonical.replace("\"domainRevision\":\"1\"", "\"domainRevision\":\"2\""),
            canonical.replace("\"cashLedgerRevision\":\"1\"", "\"cashLedgerRevision\":\"0\""),
            canonical.replace("\"delta\":{\"currency\":\"EGP\",\"minor\":\"100\"}", "\"delta\":{\"currency\":\"EGP\",\"minor\":\"99\"}"),
            canonical.replace("\"cashReceived\":{\"currency\":\"EGP\",\"minor\":\"0\"}", "\"cashReceived\":{\"currency\":\"EGP\",\"minor\":\"100\"}"),
            canonical.dropLast(1) + ",\"sourceId\":\"${request.sourceId.value}\"}",
            canonical.dropLast(1) + ",\"authorization\":{}}",
        )
        for (text in invalid) {
            assertTrue(text != canonical)
            assertFailsWith<IllegalArgumentException> {
                CashNonSaleMovementCodec.decode(cashBytes(text), fixture.scope, request.sourceId, Revision(1))
            }
        }
    }

    @Test fun maximumBoundedPrivateEvidenceRemainsExactAndFitsTheKernelRowProfile() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before).copy(reason = "é" + "\u0001".repeat(255))
        val outcome = assertIs<ProposedOutcome.Apply>(fixture.evaluate(request, fixture.cut(before)))
        val bytes = outcome.changes.single { it.key.kind == CashNonSaleStoreProfile.MOVEMENT_KIND }.payload
        assertTrue(bytes.size <= CashNonSaleStoreProfile.MAX_ROW_BYTES)
        assertEquals(request.reason, CashNonSaleMovementCodec.decode(bytes, fixture.scope, request.sourceId, Revision(1)).reason)
    }
}
