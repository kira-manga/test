package com.ukkera.pos.inventory

import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventorySnapshotTamperingTest {
    @Test fun branchCompanyRowIdentityAndRevisionMustComeFromIndependentMetadata() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val original = storedInventoryBinding(f.snapshot)
        val alternatives = listOf(
            original.copy(scope = original.scope.copy(companyId = id(700))),
            original.copy(scope = original.scope.copy(branchId = id(701))),
            original.copy(id = id(702)),
            original.copy(revision = Revision(0)),
        )
        for (expected in alternatives) assertEquals(InventorySnapshotFailure.BINDING_MISMATCH,
            assertFailsWith<InventorySnapshotRejectedException> { InventorySnapshotCodec.decode(encoded, expected) }.failure)
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("kind"), JsonPrimitive("VALUATION_POOL")),
            InventorySnapshotFailure.BINDING_MISMATCH)
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("id"), JsonPrimitive(id(703).value)),
            InventorySnapshotFailure.BINDING_MISMATCH)
    }

    @Test fun unknownSchemaCalculationVersionStatusAndFactKindsCannotSilentlyFallback() {
        val fixture = storedFreightFixture()
        val snapshot = fixture.fixture.snapshot
        val encoded = InventorySnapshotCodec.encode(snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("schemaVersion"), JsonPrimitive(2)),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "calculationVersion"), JsonPrimitive("future/v2")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "adjustments", "0", "calculationVersion"), JsonPrimitive("future/v2")),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "status"), JsonPrimitive("CLEARED")),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "binding", "exposure"), JsonPrimitive("ASSUMED_UNPAID")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "safety"), JsonPrimitive("SELLABLE_ANYWAY")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "0", "type"), JsonPrimitive("transfer_in")),
        )
        variants.forEach { storedInventoryFailure(snapshot, it) }
    }

    @Test fun transferWipAndProductionOriginsRemainExplicitlyUnsupportedOnRestore() {
        val fixture = storedFreightFixture()
        val snapshot = fixture.fixture.snapshot
        val encoded = InventorySnapshotCodec.encode(snapshot)
        for (origin in listOf("TRANSFER_IN", "PRODUCTION_OUTPUT")) {
            storedInventoryFailure(snapshot, inventoryJsonEdit(encoded,
                listOf("state", "pools", "0", "value", "facts", "0", "origin"), JsonPrimitive(origin)))
        }
        for (purpose in listOf("TRANSFER", "WORK_IN_PROGRESS")) {
            storedInventoryFailure(snapshot, inventoryJsonEdit(encoded,
                listOf("state", "pools", "0", "value", "facts", "2", "purpose"), JsonPrimitive(purpose)))
        }
    }

    @Test fun unknownDuplicateMissingFieldsAndAlternateJsonRepresentationsReject() {
        val snapshot = InventorySnapshot.empty(scope)
        val encoded = InventorySnapshotCodec.encode(snapshot)
        val root = inventoryJsonAt(encoded) as JsonObject
        val variants = listOf(
            JsonObject(root + ("authorizationToken" to JsonPrimitive("not-authority"))).toString(),
            JsonObject(root.filterKeys { it != "state" }).toString(),
            "{\"schemaVersion\":1," + encoded.drop(1),
            encoded + " ",
            inventoryJsonEdit(encoded, listOf("revision"), JsonPrimitive(0)),
            inventoryJsonEdit(encoded, listOf("revision"), JsonPrimitive("00")),
            inventoryJsonEdit(encoded, listOf("schemaVersion"), JsonPrimitive("1")),
            encoded.replace("\"BRANCH_INVENTORY\"", "\"\\u0042RANCH_INVENTORY\""),
        )
        variants.forEach { storedInventoryFailure(snapshot, it) }
    }

    @Test fun byteArrayDepthContainerAndUnicodeBoundsRunBeforeRecursiveParsing() {
        val snapshot = InventorySnapshot.empty(scope)
        val tooManyContainers = "[" + List(4) { "[" + "{},".repeat(16_383) + "{}]" }.joinToString(",") + "]"
        val variants = listOf(
            " ".repeat(InventorySnapshotCodec.MAX_ENCODED_BYTES + 1),
            "\"" + "\u0800".repeat(InventorySnapshotCodec.MAX_ENCODED_BYTES / 3 + 1) + "\"",
            "[" + "0,".repeat(InventorySnapshotCodec.MAX_ARRAY_ENTRIES) + "0]",
            "[".repeat(InventorySnapshotCodec.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(InventorySnapshotCodec.MAX_JSON_DEPTH + 1),
            tooManyContainers,
        )
        variants.forEach { storedInventoryFailure(snapshot, it, InventorySnapshotFailure.TOO_LARGE) }
        for (malformed in listOf("[}", "{", "\"\uD800\"", "\"\uDC00\"")) {
            storedInventoryFailure(snapshot, malformed, InventorySnapshotFailure.MALFORMED)
        }
    }

    @Test fun conservativePreEncodeRecordAndByteBudgetsRejectBeforeInvalidRowValidation() {
        val f = InventoryFixture()
        f.receive(1, 100)
        f.reserve(1)
        val source = f.snapshot
        val manySources = unsafeInventoryLists(source, sourceIds = List(InventorySnapshotCodec.MAX_STORED_RECORDS) { id(10_000 + it) })
        assertEquals(InventorySnapshotFailure.TOO_LARGE,
            assertFailsWith<InventorySnapshotRejectedException> { InventorySnapshotCodec.encode(manySources) }.failure)
        val original = source.reservations.single()
        val largeReservation = StockReservation(original.reservationId, original.binding, List(256) { original.allocations.single() },
            original.revision, original.status)
        val oversized = unsafeInventoryLists(source, reservations = List(22) { largeReservation })
        assertEquals(InventorySnapshotFailure.TOO_LARGE,
            assertFailsWith<InventorySnapshotRejectedException> { InventorySnapshotCodec.encode(oversized) }.failure)
    }

    @Test fun historicalVerificationHasItsOwnFailClosedWorkBudget() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val value = f.snapshot.pools.single().value
        val huge = MovingAverageState(value.unitId, value.scale, value.currency, Revision(600), value.onHand, value.carryingValue,
            List(600) { value.facts.single() }, value.costTargets, value.linkedCorrectionBalances, value.adjustments, List(600) { id(20_000 + it) })
        val source = unsafeInventoryLists(f.snapshot, pools = listOf(OwnedValuationPool(pool, huge)))
        assertEquals(InventorySnapshotFailure.TOO_LARGE,
            assertFailsWith<InventorySnapshotRejectedException> { InventorySnapshotCodec.encode(source) }.failure)
    }

    @Test fun negativeOverflowingNoncanonicalMoneyAndWrongUnitScaleReject() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "onHand", "scaled"), JsonPrimitive("-1")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "onHand", "scaled"), JsonPrimitive("9223372036854775808")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "onHand", "scale"), JsonPrimitive(7)),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "onHand", "unitId"), JsonPrimitive(id(720).value)),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "carryingValue", "minor"), JsonPrimitive(100)),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "carryingValue", "minor"), JsonPrimitive("0100")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "0", "originalValue", "minor"), JsonPrimitive("-1")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "scale"), JsonPrimitive(1)),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun duplicatePositionPoolOperationSourceAndNullLotKeysAreNotNormalizedAway() {
        val f = InventoryFixture()
        f.receive(1, 100, key = f.key.copy(lotId = null))
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        for (field in listOf("positions", "pools", "operationIds", "sourceIds")) {
            val values = inventoryJsonAt(encoded, "state", field) as JsonArray
            storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("state", field), JsonArray(values + values.first())))
        }
        val values = inventoryJsonAt(encoded, "state", "positions") as JsonArray
        val extra = JsonObject((values.single() as JsonObject).toMutableMap())
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("state", "positions"), JsonArray(values + extra)))
    }

    @Test fun foreignScopeOwnedRejectedSupplierReleasedAndPhysicalPoolMismatchReject() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "key", "pool", "scope", "branchId"), JsonPrimitive(id(721).value)),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "key", "custody"), JsonPrimitive("REJECTED_HELD")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "key", "pool", "ownership"), JsonPrimitive("SUPPLIER_OWNED")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "onHand", "scaled"), JsonPrimitive("2")),
            inventoryJsonEdit(encoded, listOf("state", "pools"), JsonArray(emptyList())),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun unknownOrNoncanonicalExpiryAndMissingLotIdentityReject() {
        val f = InventoryFixture()
        f.receive(1, 100, expires = 9)
        f.reserve(1, expires = 8)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "expiresAtEpochMillis"), JsonPrimitive("09")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "expiresAtEpochMillis"), JsonPrimitive("-1")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "expiresAtEpochMillis"), JsonPrimitive("9223372036854775808")),
            inventoryJsonEdit(encoded, listOf("state", "positions", "0", "key", "lotId"), JsonNull),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "expiresAtEpochMillis"), JsonPrimitive("0")),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun aLotCannotHaveDifferentExpiryOrClearStockBesideRecordedRecall() {
        val f = InventoryFixture()
        val second = f.key.copy(pool = pool.copy(warehouseId = id(722)))
        f.receive(1, 100, expires = 9)
        f.receive(1, 100, key = second, expires = 9)
        var encoded = InventorySnapshotCodec.encode(f.snapshot)
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("state", "positions", "0", "expiresAtEpochMillis"), JsonPrimitive("10")))
        f.snapshot = InventoryPlanner.recallLot(f.snapshot, f.snapshot.revision, f.ids.operation(), pool.skuId, f.key.lotId!!).nextSnapshot
        encoded = InventorySnapshotCodec.encode(f.snapshot)
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("state", "positions", "0", "safety"), JsonPrimitive("CLEAR")))
    }

    @Test fun reservationCannotClaimMissingForeignQuarantinedOrOverallocatedStock() {
        val f = InventoryFixture()
        f.receive(1, 100)
        f.reserve(1)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val allocation = listOf("state", "reservations", "0", "allocations", "0")
        val variants = listOf(
            inventoryJsonEdit(encoded, allocation + listOf("position", "lotId"), JsonPrimitive(id(723).value)),
            inventoryJsonEdit(encoded, allocation + listOf("position", "pool", "scope", "branchId"), JsonPrimitive(id(724).value)),
            inventoryJsonEdit(encoded, allocation + listOf("position", "custody"), JsonPrimitive("QUARANTINE")),
            inventoryJsonEdit(encoded, allocation + listOf("quantity", "scaled"), JsonPrimitive("2")),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun statusHoldReasonsAndShortageClaimsCannotBeErasedIndependently() {
        val f = InventoryFixture()
        f.receive(2, 200)
        f.reserve(2, CollectionExposure.PAID)
        f.snapshot = InventoryPlanner.recordCountShortage(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), f.key,
            f.snapshot.revision, quantity(1)).nextSnapshot
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "holds"), JsonArray(emptyList())),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "status"), JsonPrimitive("RESERVED")),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "status"), JsonPrimitive("ISSUED")),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "holds", "0", "position"), JsonNull),
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "holds", "0", "reason"), JsonPrimitive("RECALL")),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun historicalAllocationIdsCannotBeReusedByASecondReservation() {
        val f = InventoryFixture()
        f.receive(2, 200)
        val first = f.reserve(1)
        f.reserve(1)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded,
            listOf("state", "reservations", "1", "allocations", "0", "allocationId"), JsonPrimitive(first.proof.allocations.single().allocationId.value)))
        val reservations = inventoryJsonAt(encoded, "state", "reservations") as JsonArray
        storedInventoryFailure(f.snapshot, inventoryJsonEdit(encoded, listOf("state", "reservations"), JsonArray(reservations + reservations.first())))
    }

    @Test fun issueRequiresItsIssuedReservationExactBindingAndValuationSlices() {
        val fixture = storedFreightFixture()
        val snapshot = fixture.fixture.snapshot
        val encoded = InventorySnapshotCodec.encode(snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "reservations", "0", "status"), JsonPrimitive("RELEASED")),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "binding", "quoteRevision"), JsonPrimitive("3")),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "binding", "originalActorId"), JsonPrimitive(id(725).value)),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "allocations", "0", "originalValue", "minor"), JsonPrimitive("12501")),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "allocations", "0", "restoredQuantity", "scaled"), JsonPrimitive("1")),
            inventoryJsonEdit(encoded, listOf("state", "issues"), JsonArray(emptyList())),
        )
        variants.forEach { storedInventoryFailure(snapshot, it) }
    }

    @Test fun balancedRetrospectiveRepricingOfOriginalIssueStillRejects() {
        val fixture = storedFreightFixture()
        val snapshot = fixture.fixture.snapshot
        var encoded = InventorySnapshotCodec.encode(snapshot)
        encoded = inventoryJsonEdit(encoded, listOf("state", "issues", "0", "allocations", "0", "originalValue", "minor"), JsonPrimitive("13000"))
        encoded = inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "2", "slices", "0", "originalValue", "minor"), JsonPrimitive("13000"))
        for ((index, amount) in listOf("0" to "9500", "1" to "2500")) {
            encoded = inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "linkedCorrectionBalances", index, "value", "minor"), JsonPrimitive(amount))
            encoded = inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "adjustments", "0", "deltas", index, "value", "minor"), JsonPrimitive(amount))
        }
        // Current quantity, current value, total charge and original-plus-linked destinations balance.
        // The captured issue was nevertheless 12500 before that charge was known, NOT 13000.
        storedInventoryFailure(snapshot, encoded, InventorySnapshotFailure.INCONSISTENT_STATE)
    }

    @Test fun balancedButWrongHeldConsumedCorrectionSplitRejects() {
        val fixture = storedFreightFixture()
        val snapshot = fixture.fixture.snapshot
        var encoded = InventorySnapshotCodec.encode(snapshot)
        for ((index, amount) in listOf("0" to "6000", "1" to "6000")) {
            encoded = inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "linkedCorrectionBalances", index, "value", "minor"), JsonPrimitive(amount))
            encoded = inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "adjustments", "0", "deltas", index, "value", "minor"), JsonPrimitive(amount))
        }
        encoded = inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "carryingValue", "minor"), JsonPrimitive("43500"))
        // Physical receipt residue 60/60 is wrong; this pooled lineage requires 90/30.
        storedInventoryFailure(snapshot, encoded, InventorySnapshotFailure.INCONSISTENT_STATE)
    }

    @Test fun duplicateReorderedOrUnindexedValuationFactsAndStaleBasisReject() {
        val fixture = storedFreightFixture()
        val snapshot = fixture.fixture.snapshot
        val encoded = InventorySnapshotCodec.encode(snapshot)
        val factPath = listOf("state", "pools", "0", "value", "facts")
        val facts = inventoryJsonAt(encoded, *factPath.toTypedArray()) as JsonArray
        val events = inventoryJsonAt(encoded, "state", "pools", "0", "value", "eventIds") as JsonArray
        val variants = listOf(
            inventoryJsonEdit(encoded, factPath, JsonArray(facts + facts.first())),
            inventoryJsonEdit(encoded, factPath, JsonArray(facts.reversed())),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "eventIds"), JsonArray(events.drop(1))),
            inventoryJsonEdit(encoded, listOf("state", "sourceIds"), JsonArray(emptyList())),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "adjustments", "0", "basisRevision"), JsonPrimitive("0")),
        )
        variants.forEach { storedInventoryFailure(snapshot, it) }
    }

    @Test fun originalReturnCannotRegenerateQuantityOrUseAChangedOriginalCost() {
        val f = InventoryFixture()
        f.receive(3, 2)
        val reservation = f.reserve(3)
        val issue = f.issue(reservation.proof.reservationId) as ReservationIssueResult.Issued
        storedInventoryReturn(f, issue.proof.issueId, 1)
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "allocations", "0", "restoredQuantity", "scaled"), JsonPrimitive("0")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "2", "restorations", "0", "originalValue", "minor"), JsonPrimitive("0")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "2", "restorations", "0", "quantity", "scaled"), JsonPrimitive("4")),
            inventoryJsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "2", "restorations", "0", "issueId"), JsonPrimitive(id(726).value)),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "revision"), JsonPrimitive("1")),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun cancelledChargeSourceCannotBeReboundToAnotherPoolEvenWhenBothLocalHistoriesBalance() {
        val f = InventoryFixture()
        val second = f.key.copy(pool = pool.copy(warehouseId = id(727)))
        val firstReceipt = f.receive(1, 100)
        val secondReceipt = f.receive(1, 100, second)
        val firstSource = storedInventoryCorrection(f, firstReceipt, 0)
        storedInventoryCorrection(f, secondReceipt, 0, poolKey = second.pool)
        val index = f.snapshot.pools.indexOfFirst { it.key == second.pool }.toString()
        var encoded = InventorySnapshotCodec.encode(f.snapshot)
        for (path in listOf(
            listOf("costTargets", "0", "sourceId"),
            listOf("adjustments", "0", "changedSource", "sourceId"),
            listOf("adjustments", "0", "targetBasis", "0", "sourceId"),
        )) encoded = inventoryJsonEdit(encoded, listOf("state", "pools", index, "value") + path, JsonPrimitive(firstSource.value))
        storedInventoryFailure(f.snapshot, encoded, InventorySnapshotFailure.INCONSISTENT_STATE)
    }

    @Test fun recipeStationSetsDispositionRevisionAndRestorationMustAgree() {
        val f = InventoryFixture()
        f.receive(2, 200)
        val reserved = f.reserve(2)
        val station = id(728)
        val sent = f.issue(reserved.proof.reservationId, recipe = RecipeIssueRequest(id(729), listOf(station, id(730)))) as ReservationIssueResult.Issued
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        f.snapshot = InventoryPlanner.acknowledgeKitchenStart(f.snapshot, f.snapshot.revision, f.ids.operation(), issue.issueId,
            issue.revision, station).nextSnapshot
        val encoded = InventorySnapshotCodec.encode(f.snapshot)
        val variants = listOf(
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "kitchen", "startedStationIds"), JsonArray(listOf(JsonPrimitive(id(731).value)))),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "kitchen", "requiredStationIds"), JsonArray(listOf(JsonPrimitive(station.value), JsonPrimitive(station.value)))),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "kitchen", "disposition"), JsonPrimitive("CANCELLED_BEFORE_START")),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "kitchen", "startedStationIds"), JsonArray(emptyList())),
            inventoryJsonEdit(encoded, listOf("state", "issues", "0", "kitchen"), JsonNull),
        )
        variants.forEach { storedInventoryFailure(f.snapshot, it) }
    }

    @Test fun codecDoesNotPretendToAuthenticateASelfConsistentAlternativeHistory() {
        val first = InventoryFixture()
        first.receive(1, 100)
        val alternative = InventoryFixture()
        alternative.receive(1, 200)
        // Identical identities/revision with a DIFFERENT but arithmetically valid receipt.
        // A protected hash/journal/authorization/recovery admission must decide provenance.
        val encoded = InventorySnapshotCodec.encode(alternative.snapshot)
        val decoded = InventorySnapshotCodec.decode(encoded, storedInventoryBinding(first.snapshot))
        assertEquals(money(200), decoded.pools.single().value.carryingValue)
        assertTrue(first.snapshot.pools.single().value.carryingValue != decoded.pools.single().value.carryingValue)
    }
}

private fun unsafeInventoryLists(
    original: InventorySnapshot,
    pools: List<OwnedValuationPool> = original.pools,
    reservations: List<StockReservation> = original.reservations,
    sourceIds: List<com.ukkera.pos.types.DomainId> = original.sourceIds,
): InventorySnapshot = InventorySnapshot(original.scope, original.revision, original.positions, pools, reservations, original.issues,
    original.operationIds, sourceIds)
