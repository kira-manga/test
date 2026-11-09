package com.ukkera.pos.database

import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strict finite wire checks. Synthetic bytes here are not trusted current state or provenance. */
class CheckoutContractTest {
    @Test fun everySupportedRequestAndOutcomeModeRoundTripsWithItsExactCommandKey() {
        for (request in contractRequests()) {
            val key = CheckoutFixtureCodec.keyOf(request)
            val bytes = CheckoutFixtureCodec.encode(request)
            assertEquals(request, CheckoutFixtureCodec.decode(key, bytes))
            assertEquals(bytes, CheckoutFixtureCodec.normalize(key, bytes))
            assertTrue(bytes.size <= CheckoutFixtureProfile.MAX_REQUEST_BYTES)
        }
        assertEquals(CheckoutFixtureProfile.COMMANDS, contractRequests().map { CheckoutFixtureCodec.keyOf(it) }.toSet())
    }

    @Test fun everySupportedResultRoundTripsAndBelongsOnlyToItsFiniteCommand() {
        val results = listOf(
            CheckoutFixtureProfile.OPEN to CheckoutResult.Opened(CONTRACT_CASH.copy(session = CONTRACT_CASH.session.copy(revision = Revision(0)))),
            CheckoutFixtureProfile.CREATE to CheckoutResult.Created(CheckoutRef(testId(430_020), Revision(0))),
            CheckoutFixtureProfile.BEGIN to CheckoutResult.Begun(CONTRACT_SALES.copy(collection = CONTRACT_SALES.collection.copy(revision = Revision(0))), CONTRACT_STOCK),
            CheckoutFixtureProfile.ATTEMPT to CheckoutResult.Attempted(CONTRACT_SALES, CONTRACT_STOCK, testId(430_021)),
            CheckoutFixtureProfile.OUTCOME to CheckoutResult.OutcomeRecorded(CONTRACT_SALES, testId(430_021), null),
            CheckoutFixtureProfile.OUTCOME to CheckoutResult.OutcomeRecorded(CONTRACT_SALES, testId(430_021), CONTRACT_STOCK),
            CheckoutFixtureProfile.FINALIZE to CheckoutResult.Held(CONTRACT_SALES, CONTRACT_STOCK),
            CheckoutFixtureProfile.FINALIZE to CheckoutResult.Issued(CONTRACT_SALES, CONTRACT_STOCK, testId(430_022), testId(430_023)),
        )
        for ((key, result) in results) {
            val encoded = CheckoutFixtureCodec.encodeResult(result)
            assertEquals(result, CheckoutFixtureCodec.decodeResult(key, encoded))
            for (other in CheckoutFixtureProfile.COMMANDS - key)
                contractFailure(CheckoutContractFailure.BINDING_MISMATCH) { CheckoutFixtureCodec.decodeResult(other, encoded) }
        }
        for (key in CheckoutFixtureProfile.COMMANDS) {
            val rejected = CheckoutResult.Rejected(CheckoutRejectionCodes.CAPACITY)
            assertEquals(rejected, CheckoutFixtureCodec.decodeResult(key, CheckoutFixtureCodec.encodeResult(rejected)))
        }
    }

    @Test fun supportedVersionWithAnotherBodyAndUnsupportedVersionBothReject() {
        val request = contractRequests().first()
        val bytes = CheckoutFixtureCodec.encode(request)
        contractFailure(CheckoutContractFailure.BINDING_MISMATCH) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, bytes) }
        contractFailure(CheckoutContractFailure.UNSUPPORTED) { CheckoutFixtureCodec.decode(CommandKey(CheckoutFixtureProfile.OPEN.name, 2), bytes) }
        contractFailure(CheckoutContractFailure.UNSUPPORTED) { CheckoutFixtureCodec.decode(CommandKey("fixture.checkout.unknown", 1), bytes) }
    }

    @Test fun requestsRejectWhitespaceUnknownDuplicateAndMissingFields() {
        val request = contractRequests().first()
        val key = CheckoutFixtureCodec.keyOf(request)
        val text = CheckoutFixtureCodec.encode(request).copyBytes().decodeToString()
        contractFailure(CheckoutContractFailure.NON_CANONICAL) { CheckoutFixtureCodec.decode(key, text.bytesWith(" ")) }
        contractFailure(CheckoutContractFailure.MALFORMED) {
            CheckoutFixtureCodec.decode(key, StoredBytes(text.replaceFirst("{", "{\"ownerApproved\":true,").encodeToByteArray()))
        }
        assertFailsWith<CheckoutContractRejected> {
            CheckoutFixtureCodec.decode(key, StoredBytes(text.replaceFirst("{", "{\"type\":\"OPEN\",").encodeToByteArray()))
        }
        contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.decode(key, StoredBytes("{\"type\":\"OPEN\"}".encodeToByteArray())) }
    }

    @Test fun malformedUtf8IsRejectedBeforeJsonParsing() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.OPEN, StoredBytes(bytes)) }
        }
    }

    @Test fun loneSurrogatesAreRejectedButArabicAndSupplementaryPayerTextRoundTrip() {
        val request = contractRequests().filterIsInstance<CheckoutRequest.Attempt>().first()
        for (invalid in listOf("\uD800", "\uDC00")) {
            contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.encode(request.copy(payerReference = invalid)) }
            val normal = CheckoutFixtureCodec.encode(request.copy(payerReference = "SENTINEL")).copyBytes().decodeToString()
            val escaped = if (invalid[0].isHighSurrogate()) "\\uD800" else "\\uDC00"
            assertFailsWith<CheckoutContractRejected> {
                CheckoutFixtureCodec.decode(CheckoutFixtureProfile.ATTEMPT, StoredBytes(normal.replace("SENTINEL", escaped).encodeToByteArray()))
            }
        }
        val valid = request.copy(payerReference = "عميل ١٢٣ \uD83D\uDE00")
        assertEquals(valid, CheckoutFixtureCodec.decode(CheckoutFixtureProfile.ATTEMPT, CheckoutFixtureCodec.encode(valid)))
    }

    @Test fun exactRequestByteCapReachesCanonicalGuardAndOneMoreByteFailsSizeGuard() {
        val request = contractRequests().first()
        val bytes = CheckoutFixtureCodec.encode(request).copyBytes()
        fun padded(size: Int) = StoredBytes(bytes + ByteArray(size - bytes.size) { 0x20 })
        contractFailure(CheckoutContractFailure.NON_CANONICAL) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.OPEN,
            padded(CheckoutFixtureProfile.MAX_REQUEST_BYTES)) }
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.OPEN,
            padded(CheckoutFixtureProfile.MAX_REQUEST_BYTES + 1)) }
    }

    @Test fun exactResultByteCapReachesCanonicalGuardAndOneMoreByteFailsSizeGuard() {
        val bytes = CheckoutFixtureCodec.encodeResult(CheckoutResult.Created(CheckoutRef(testId(430_020), Revision(0)))).copyBytes()
        fun padded(size: Int) = StoredBytes(bytes + ByteArray(size - bytes.size) { 0x20 })
        contractFailure(CheckoutContractFailure.NON_CANONICAL) { CheckoutFixtureCodec.decodeResult(CheckoutFixtureProfile.CREATE,
            padded(CheckoutFixtureProfile.MAX_RESULT_BYTES)) }
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.decodeResult(CheckoutFixtureProfile.CREATE,
            padded(CheckoutFixtureProfile.MAX_RESULT_BYTES + 1)) }
    }

    @Test fun prescanDistinguishesThreeUnknownArrayElementsFromFourBeforeRecursiveDecode() {
        fun array(n: Int) = StoredBytes(("{\"unknown\":[" + List(n) { "0" }.joinToString(",") + "]}").encodeToByteArray())
        contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, array(3)) }
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, array(4)) }
    }

    @Test fun prescanDistinguishesDepthTwelveFromThirteenEvenForUnknownFields() {
        fun nested(n: Int) = StoredBytes(("{\"unknown\":" + "[".repeat(n) + "0" + "]".repeat(n) + "}").encodeToByteArray())
        contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, nested(11)) }
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, nested(12)) }
    }

    @Test fun prescanContainerBoundCannotBeHiddenInUnknownFieldsOrStrings() {
        fun objects(n: Int) = StoredBytes(("{" + List(n) { "\"p$it\":{}" }.joinToString(",") + "}").encodeToByteArray())
        contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, objects(127)) }
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.CREATE, objects(128)) }
        val request = contractRequests().filterIsInstance<CheckoutRequest.Attempt>().first().copy(payerReference = "[,{\\\"".repeat(10))
        assertEquals(request, CheckoutFixtureCodec.decode(CheckoutFixtureProfile.ATTEMPT, CheckoutFixtureCodec.encode(request)))
    }

    @Test fun minorAmountIsCanonicalQuotedIntegerNeverFloatOrPlusOrLeadingZero() {
        val request = contractRequests().filterIsInstance<CheckoutRequest.Attempt>().first()
        val text = CheckoutFixtureCodec.encode(request).copyBytes().decodeToString()
        assertTrue("\"minor\":\"100\"" in text)
        for (invalid in listOf("100", "100.0", "\"+100\"", "\"0100\"", "\"1e2\"", "null"))
            assertFailsWith<CheckoutContractRejected>(invalid) {
                CheckoutFixtureCodec.decode(CheckoutFixtureProfile.ATTEMPT,
                    StoredBytes(text.replace("\"minor\":\"100\"", "\"minor\":$invalid").encodeToByteArray()))
            }
    }

    @Test fun explicitNullAndExposureModeFieldsCannotBeOmittedOrMixed() {
        val request = contractRequests().filterIsInstance<CheckoutRequest.Outcome>().first { it.exposure == CheckoutExposureUpdate.Preserve }
        val text = CheckoutFixtureCodec.encode(request).copyBytes().decodeToString()
        assertFailsWith<CheckoutContractRejected> {
            CheckoutFixtureCodec.decode(CheckoutFixtureProfile.OUTCOME,
                StoredBytes(text.replace("\"type\":\"PRESERVE\"", "\"type\":\"PRESERVE\",\"stock\":null").encodeToByteArray()))
        }
        val attempt = contractRequests().filterIsInstance<CheckoutRequest.Attempt>().first().copy(payerReference = null)
        val nullable = CheckoutFixtureCodec.encode(attempt).copyBytes().decodeToString()
        assertTrue(",\"payerReference\":null" in nullable)
        assertFailsWith<CheckoutContractRejected> {
            CheckoutFixtureCodec.decode(CheckoutFixtureProfile.ATTEMPT,
                StoredBytes(nullable.replace(",\"payerReference\":null", "").encodeToByteArray()))
        }
    }

    @Test fun zeroOrNegativeTenderAttemptAndNonEgpMoneyDoNotEnterFixture() {
        val request = contractRequests().filterIsInstance<CheckoutRequest.Attempt>().first()
        for (minor in listOf(0L, -1L)) assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encode(request.copy(amount = checkoutMoney(minor))) }
        val wire = CheckoutFixtureCodec.encode(request).copyBytes().decodeToString()
        contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutFixtureCodec.decode(CheckoutFixtureProfile.ATTEMPT,
            StoredBytes(wire.replace("\"currency\":\"EGP\"", "\"currency\":\"USD\"").encodeToByteArray())) }
    }

    @Test fun textLengthsHaveExactInclusiveLimitsAndBlankIsNotEvidence() {
        val request = contractRequests().filterIsInstance<CheckoutRequest.Attempt>().first()
        CheckoutFixtureCodec.encode(request.copy(payerReference = "أ".repeat(128)))
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.encode(request.copy(payerReference = "a".repeat(129))) }
        assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encode(request.copy(payerReference = " ")) }
        val outcome = CheckoutRequest.Outcome(CONTRACT_SALES, testId(430_021),
            CheckoutOutcome.NotPaid(CONTRACT_EVIDENCE, "r".repeat(256)), CheckoutExposureUpdate.Preserve)
        CheckoutFixtureCodec.encode(outcome)
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.encode(outcome.copy(
            outcome = CheckoutOutcome.NotPaid(CONTRACT_EVIDENCE, "r".repeat(257)))) }
    }

    @Test fun nullableExpiryIsBoundedCanonicalDecimalString() {
        val request = contractRequests().filterIsInstance<CheckoutRequest.Begin>().first()
        for (value in listOf(null, 0L, CheckoutFixtureProfile.MAX_RESERVATION_EPOCH_MILLIS)) {
            val valid = request.copy(reservationExpiresAtEpochMillis = value)
            val bytes = CheckoutFixtureCodec.encode(valid)
            assertEquals(valid, CheckoutFixtureCodec.decode(CheckoutFixtureProfile.BEGIN, bytes))
            if (value != null) assertTrue("\"reservationExpiresAtEpochMillis\":\"$value\"" in bytes.copyBytes().decodeToString())
        }
        for (value in listOf(-1L, CheckoutFixtureProfile.MAX_RESERVATION_EPOCH_MILLIS + 1L))
            assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encode(request.copy(reservationExpiresAtEpochMillis = value)) }
    }

    @Test fun resultGenerationsCannotWrapAndOnlyZeroRevisionOrdersCanBeCreated() {
        assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encodeResult(CheckoutResult.Created(CheckoutRef(testId(430_020), Revision(1)))) }
        assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encodeResult(CheckoutResult.Held(
            CONTRACT_SALES.copy(order = CONTRACT_SALES.order.copy(revision = Revision(Long.MAX_VALUE))), CONTRACT_STOCK)) }
        assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encodeResult(CheckoutResult.Held(
            CONTRACT_SALES, CONTRACT_STOCK.copy(reservation = CONTRACT_STOCK.reservation.copy(revision = Revision(0))))) }
        contractFailure(CheckoutContractFailure.UNSUPPORTED) { CheckoutFixtureCodec.encodeResult(CheckoutResult.Rejected("arbitrary.error")) }
    }

    @Test fun exactRowByteBoundHashStrictUtfAndStandaloneGenerationAreIndependent() {
        fun row(bytes: ByteArray, generation: Long = 1) = CheckoutRow(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.PAID_SALE, testId(430_023)),
            Revision(generation), CheckoutFixtureProfile.SALE_SCHEMA, StoredBytes(bytes), sha256(bytes))
        assertEquals(65_536, CheckoutRows.text(row(ByteArray(65_536) { 0x61 })).length)
        // StoredBytes refuses the oversized container before any CheckoutRows decoder can see it.
        assertEquals("Stored value exceeds bounded command profile", assertFailsWith<IllegalArgumentException> {
            row(ByteArray(65_537) { 0x61 })
        }.message)
        val ordinary = row("{}".encodeToByteArray())
        contractFailure(CheckoutContractFailure.BINDING_MISMATCH) { CheckoutRows.text(ordinary.copy(payloadDigest = sha256(byteArrayOf(1)))) }
        contractFailure(CheckoutContractFailure.MALFORMED) { CheckoutRows.text(row(byteArrayOf(0xff.toByte()))) }
        for (generation in listOf(0L, 2L)) contractFailure(CheckoutContractFailure.BINDING_MISMATCH) {
            CheckoutRows.binding(row("{}".encodeToByteArray(), generation), TEST_SCOPE, SalesSnapshotKind.PAID_SALE, testId(430_023))
        }
    }

    @Test fun namedBasketsUseActualPricingEngineAndLiteralPricesWithoutCallerQuote() {
        for (basket in CheckoutBasket.entries) {
            val request = contractCreate(basket)
            val (quote, stock) = checkoutBasket(TEST_SCOPE, request)
            assertEquals(checkoutMoney(if (basket == CheckoutBasket.ZERO) 0 else 100), quote.payable)
            assertEquals(request.lines.map { it.toAllocation(TEST_SCOPE) }, stock.allocations)
            assertEquals(if (basket == CheckoutBasket.SPLIT_33_33_34) listOf(33L, 33L, 34L) else
                listOf(if (basket == CheckoutBasket.ZERO) 0L else 100L), quote.lines.map { it.payable.minor })
        }
    }

    @Test fun basketsRejectExtraLinesRepeatedAllocationsAndFractionalFixtureQuantity() {
        val request = contractCreate(CheckoutBasket.SPLIT_33_33_34)
        contractFailure(CheckoutContractFailure.TOO_LARGE) { CheckoutFixtureCodec.encode(request.copy(lines = request.lines + request.lines.first())) }
        assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encode(request.copy(lines = request.lines.map { it.copy(allocationId = request.lines.first().allocationId) })) }
        assertFailsWith<CheckoutContractRejected> { CheckoutFixtureCodec.encode(contractCreate(CheckoutBasket.SIMPLE_100).let {
            it.copy(lines = listOf(it.lines.single().copy(quantity = Quantity(testId(430_009), 1, 1))))
        }) }
    }

    @Test fun reusedCashNamespaceAndNonfinancialCollectionClaimsStayDistinctFromInventorySources() {
        assertEquals("sales.cash.source", CheckoutFixtureProfile.claim(SalesUniqueKind.CASH_SOURCE, testId(430_021)).namespace)
        assertFalse(CheckoutFixtureProfile.claim(SalesUniqueKind.COLLECTION, testId(430_021)).namespace == InventoryRetailStoreProfile.SOURCE_NAMESPACE)
        assertFalse(CheckoutFixtureProfile.claim(SalesUniqueKind.PAYMENT_RECORD, testId(430_021)) ==
            CheckoutFixtureProfile.claim(SalesUniqueKind.PAYMENT_ATTEMPT, testId(430_021)))
    }
}

private val CONTRACT_CASH = CheckoutCashRef(CheckoutRef(testId(430_001), Revision(2)), Revision(1), Revision(1))
private val CONTRACT_SALES = CheckoutSalesRef(CheckoutRef(testId(430_002), Revision(1)), CheckoutRef(testId(430_003), Revision(2)), CONTRACT_CASH)
private val CONTRACT_STOCK = CheckoutReservationRef(Revision(3), CheckoutRef(testId(430_004), Revision(1)))
private val CONTRACT_EVIDENCE = CheckoutEvidence(testId(430_005), TEST_TIME)

private fun contractCreate(basket: CheckoutBasket) = CheckoutRequest.Create(testId(430_020), testId(430_030), basket,
    List(if (basket == CheckoutBasket.SPLIT_33_33_34) 3 else 1) { index ->
        CheckoutLine(testId(430_040 + index), testId(430_050 + index), testId(430_060 + index), testId(430_070 + index),
            testId(430_080 + index), Quantity(testId(430_009), 1, 0))
    })

private fun contractRequests(): List<CheckoutRequest> = buildList {
    add(CheckoutRequest.Open(CHECKOUT_DRAWER, Revision(0), Revision(0), CONTRACT_CASH.session.id,
        BusinessDate.parseCanonical("2026-10-23"), checkoutMoney(500), testId(430_006), CONTRACT_EVIDENCE))
    CheckoutBasket.entries.forEach { add(contractCreate(it)) }
    add(CheckoutRequest.Begin(CONTRACT_SALES.order, CONTRACT_CASH, CONTRACT_SALES.collection.id, Revision(1), CONTRACT_STOCK.reservation.id, 1000))
    add(CheckoutRequest.Attempt(CONTRACT_SALES, CONTRACT_STOCK, testId(430_021), CheckoutTender.Cash, checkoutMoney(100), "synthetic payer"))
    add(CheckoutRequest.Attempt(CONTRACT_SALES, CONTRACT_STOCK, testId(430_021),
        CheckoutTender.External(testId(430_022), Revision(3), CheckoutExternalKind.CARD, true, true), checkoutMoney(100), null))
    val outcomes = listOf(CheckoutOutcome.Unknown(CONTRACT_EVIDENCE), CheckoutOutcome.NotPaid(CONTRACT_EVIDENCE, "synthetic no payment"),
        CheckoutOutcome.CashReceived(checkoutMoney(150), checkoutMoney(50), CONTRACT_EVIDENCE),
        CheckoutOutcome.ExternalAttested(CONTRACT_EVIDENCE, "terminal", "reference"))
    for (outcome in outcomes) for (mode in listOf(CheckoutExposureUpdate.Preserve, CheckoutExposureUpdate.Derive(CONTRACT_STOCK)))
        add(CheckoutRequest.Outcome(CONTRACT_SALES, testId(430_021), outcome, mode))
    add(CheckoutRequest.Finalize(CONTRACT_SALES, CONTRACT_STOCK, testId(430_022), testId(430_023)))
}

private fun String.bytesWith(suffix: String) = StoredBytes((this + suffix).encodeToByteArray())
private fun contractFailure(expected: CheckoutContractFailure, action: () -> Unit) {
    assertEquals(expected, assertFailsWith<CheckoutContractRejected>(block = action).failure)
}
