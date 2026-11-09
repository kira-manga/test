package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotRejectedException
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationDenial
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CashNonSaleHandlerTest {
    @Test fun registrationDeclaresOnlyTheImplementedCashRowsAndSourceClaim() {
        val handler = CashNonSaleMovementHandler()
        assertEquals(CommandKey("sales.cash.move", 1), handler.key)
        assertEquals(setOf("sales.cash.session", "sales.cash.movement"), handler.writableKinds)
        assertEquals(setOf("sales.cash.source"), handler.claimNamespaces)
    }

    @Test fun supportedCategoriesUseRealDomainCashCasInsertOnlyMovementAndSameSourceClaim() = runTest {
        for (kind in listOf(NonSaleCashKind.NON_SALE_PAY_IN, NonSaleCashKind.OPERATING_EXPENSE)) {
            val fixture = CashStoreFixture()
            val before = fixture.open()
            val request = fixture.request(before, kind)
            val context = fixture.context(request)
            val outcome = assertIs<ProposedOutcome.Apply>(fixture.evaluate(request, fixture.cut(before), context))
            val result = assertIs<CashNonSaleResultV1.Applied>(CashNonSaleResultCodec.decode(outcome.result))
            assertEquals(2, outcome.changes.size)
            val cash = outcome.changes.single { it.key.kind == CashNonSaleStoreProfile.SESSION_KIND }
            assertEquals(CashNonSaleStoreProfile.sessionKey(before.id), cash.key)
            assertEquals(Revision(1), cash.expectedRevision)
            assertEquals(CashNonSaleStoreProfile.SESSION_SCHEMA, cash.schema)
            val after = fixture.sessionAfter(cash)
            assertEquals(Revision(1), after.revision)
            assertEquals(Revision(1), after.cashLedgerRevision)
            assertEquals(if (kind == NonSaleCashKind.NON_SALE_PAY_IN) cashMoney(1_100) else cashMoney(900), after.expectedCash)
            assertEquals(before.opening, after.opening)
            assertEquals(before.openingSourceId, after.openingSourceId)
            assertEquals(before.custodianId, after.custodianId)
            assertEquals(after.id, result.sessionId)
            assertEquals(after.expectedCash, result.expectedCash)
            assertEquals(after.revision, result.domainRevision)
            assertEquals(request.sourceId, result.sourceId)
            val movement = outcome.changes.single { it.key.kind == CashNonSaleStoreProfile.MOVEMENT_KIND }
            assertEquals(CashNonSaleStoreProfile.movementKey(request.sourceId), movement.key)
            assertEquals(CashNonSaleStoreProfile.MOVEMENT_SCHEMA, movement.schema)
            assertNull(movement.expectedRevision)
            val projection = CashNonSaleMovementCodec.decode(movement.payload, fixture.scope, request.sourceId, Revision(1))
            assertEquals(context.operationId, projection.operationId)
            assertEquals(context.originalActorId, projection.originalActorId)
            assertEquals(context.semanticDigest, projection.semanticDigest)
            assertEquals(before.id, projection.sessionId)
            assertEquals(before.drawerId, projection.drawerId)
            assertEquals(before.revision, projection.previousDomainRevision)
            assertEquals(after.revision, projection.domainRevision)
            assertEquals(kind, projection.kind)
            assertEquals(request.amount, projection.amount)
            assertEquals(after.expectedCash - before.expectedCash, projection.delta)
            assertEquals(cashMoney(0), projection.cashReceived)
            assertEquals(cashMoney(0), projection.changeGiven)
            assertEquals(request.evidenceId, projection.evidenceId)
            assertEquals(request.occurredAt, projection.occurredAt)
            assertEquals(request.reason, projection.reason)
            assertEquals(fixture.now, projection.recordedAt)
            assertNotEquals(projection.occurredAt, projection.recordedAt)
            assertEquals(ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(request.sourceId), before.id), outcome.claims.single())
            assertTrue(outcome.jobs.isEmpty()) // The kernel, not this pure test, owns durable audit/upload intent.
            assertNull(outcome.mustCommitBefore)
            assertTrue(outcome.changes.all { it.payload.size <= CashNonSaleStoreProfile.MAX_ROW_BYTES })
            assertEquals(cashMoney(1_000), before.expectedCash) // Immutable input, not an in-memory commit.
        }
    }

    @Test fun exactCashBeyondBinary64SurvivesRealTransitionAndStoredSnapshot() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open(cashMoney(9_007_199_254_740_992))
        val request = fixture.request(before, amount = cashMoney(1))
        val outcome = assertIs<ProposedOutcome.Apply>(fixture.evaluate(request, fixture.cut(before)))
        val result = assertIs<CashNonSaleResultV1.Applied>(CashNonSaleResultCodec.decode(outcome.result))
        assertEquals(9_007_199_254_740_993, result.expectedCash.minor)
        assertEquals(result.expectedCash, fixture.sessionAfter(outcome.changes.single {
            it.key.kind == CashNonSaleStoreProfile.SESSION_KIND
        }).expectedCash)
    }

    @Test fun requirementUsesTrustedActorActualCustodianExactBindingAndConservativeRecoveryResources() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before, NonSaleCashKind.OPERATING_EXPENSE)
        val context = fixture.context(request)
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), context, fixture.cut(before))
        val requirement = prepared.requirement
        assertEquals(Permission(SecurityAction.CASH_MOVE, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(fixture.scope, requirement.scope)
        assertEquals(OwnerConstraint.OwnerPrivate(before.custodianId), requirement.owner)
        assertEquals(LimitUse.Monetary(request.amount), requirement.limitUse)
        assertEquals(request.sessionId, requirement.binding?.documentId)
        assertEquals(request.expectedDomainRevision, requirement.binding?.revision)
        assertEquals(context.operationId, requirement.binding?.operationId)
        assertEquals(context.semanticDigest, requirement.binding?.payloadDigest)
        assertEquals("cash.operating_expense", requirement.binding?.reasonCode)
        assertNotEquals(request.reason, requirement.binding?.reasonCode)
        assertEquals(setOf(
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
            RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, before.id),
            RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, before.drawerId),
        ), prepared.affectedResources)
    }

    @Test fun foreignCustodianIsDeniedAndEvenAnAnyOwnerGrantCannotMoveTheirDrawer() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), fixture.context(request, fixture.other), fixture.cut(before))
        assertEquals(OwnerConstraint.OwnerPrivate(fixture.owner), prepared.requirement.owner)
        assertEquals(AuthorizationDenial.OWNER_REQUIRED,
            assertIs<AuthorizationDecision.Denied>(fixture.decision(prepared.requirement, fixture.other)).reason)
        val broad = assertIs<AuthorizationDecision.Allowed>(fixture.decision(prepared.requirement, fixture.other,
            ownerAccess = OwnerAccess.ANY_OWNER)).authorization
        assertFailsWith<IllegalArgumentException> { prepared.evaluate(broad, fixture.now) }
        assertEquals(AuthorizationDenial.LIMIT_EXCEEDED,
            assertIs<AuthorizationDecision.Denied>(fixture.decision(prepared.requirement, maximum = cashMoney(99))).reason)
    }

    @Test fun aSessionMissingResultStillRequiresAnExactActorCapability() = runTest {
        val fixture = CashStoreFixture()
        val request = fixture.request(fixture.open())
        val context = fixture.context(request)
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), context, fixture.cut(null))
        val outcome = assertIs<ProposedOutcome.Reject>(prepared.evaluate(fixture.authorization(prepared.requirement), fixture.now))
        assertEquals("sales.cash.session_missing", outcome.code)
        assertEquals(CashNonSaleResultV1.SessionMissing, CashNonSaleResultCodec.decode(outcome.result))
        assertEquals(2, prepared.affectedResources.size)
        val unrelated = prepared.requirement.copy(binding = checkNotNull(prepared.requirement.binding).copy(documentId = fixture.nextId()))
        assertFailsWith<IllegalArgumentException> { prepared.evaluate(fixture.authorization(unrelated), fixture.now) }
    }

    @Test fun staleDomainRevisionIsARecordedDomainRejectionNotKernelRevisionGuessing() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        for (revision in listOf(Revision(1), Revision(Long.MAX_VALUE))) {
            val request = fixture.request(before).copy(expectedDomainRevision = revision)
            assertRejectedRule(SalesRule.REVISION_CONFLICT, fixture.evaluate(request, fixture.cut(before)))
        }
    }

    @Test fun payoutsRequireAvailableCashAndAnExactFinalMinorUnitMayBeSpent() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open(cashMoney(100))
        val kind = NonSaleCashKind.OPERATING_EXPENSE
        assertRejectedRule(SalesRule.INSUFFICIENT_DRAWER_CASH,
            fixture.evaluate(fixture.request(before, kind, cashMoney(101)), fixture.cut(before)))
        val exact = assertIs<ProposedOutcome.Apply>(fixture.evaluate(fixture.request(before, kind, cashMoney(100)), fixture.cut(before)))
        assertEquals(cashMoney(0), assertIs<CashNonSaleResultV1.Applied>(CashNonSaleResultCodec.decode(exact.result)).expectedCash)
    }

    @Test fun inventorySettlementRequiresAnAuthoritativeSourceAdapterNotOnlyACashGrantAndAvailableMoney() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val original = fixture.row(before).payload
        val request = fixture.request(before, NonSaleCashKind.INVENTORY_SETTLEMENT)
        val context = fixture.context(request)
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), context, fixture.cut(before))
        assertEquals("cash.inventory_settlement", prepared.requirement.binding?.reasonCode)
        assertEquals(LimitUse.Monetary(request.amount), prepared.requirement.limitUse)
        assertUnsupported(CashNonSaleUnsupported.INVENTORY_SETTLEMENT_SOURCE_REQUIRED,
            prepared.evaluate(fixture.authorization(prepared.requirement), fixture.now))
        assertEquals(original, fixture.row(before).payload)
        // The explicit unsupported path is not an unauthenticated status route.
        val unrelated = prepared.requirement.copy(binding = checkNotNull(prepared.requirement.binding).copy(operationId = fixture.nextOperation()))
        assertFailsWith<IllegalArgumentException> { prepared.evaluate(fixture.authorization(unrelated), fixture.now) }
    }

    @Test fun closedMutationCutCannotBeReopenedByANonSaleMovement() = runTest {
        val fixture = CashStoreFixture()
        val closing = fixture.closing(fixture.open())
        assertRejectedRule(SalesRule.INVALID_STATE, fixture.evaluate(fixture.request(closing), fixture.cut(closing)))
    }

    @Test fun aFreshOperationCannotReuseAClaimedSourceEvenWhenItsOwnerMatches() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        for (owner in listOf(before.id, fixture.nextId())) {
            assertRejectedRule(SalesRule.DUPLICATE_SOURCE_ALLOCATION,
                fixture.evaluate(request, fixture.cut(before, sourceOwner = owner)))
        }
    }

    @Test fun anExistingMovementProjectionIsNeverUpdatedEvenIfItsClaimWasLost() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val original = fixture.request(before)
        val first = assertIs<ProposedOutcome.Apply>(fixture.evaluate(original, fixture.cut(before)))
        val changedCash = first.changes.single { it.key.kind == CashNonSaleStoreProfile.SESSION_KIND }
        val after = fixture.sessionAfter(changedCash)
        val projection = first.changes.single { it.key.kind == CashNonSaleStoreProfile.MOVEMENT_KIND }
        val row = CashNonSaleRow(projection.key, Revision(1), projection.schema, projection.payload)
        val freshRequest = original.copy(expectedDomainRevision = after.revision)
        assertRejectedRule(SalesRule.DUPLICATE_SOURCE_ALLOCATION,
            fixture.evaluate(freshRequest, fixture.cut(after, movement = row)))
    }

    @Test fun pendingAttemptWithZeroReservedCashIsExplicitlyOutsideThisStorageProfile() = runTest {
        val fixture = CashStoreFixture()
        val pending = fixture.pending(fixture.open()).session
        assertTrue(pending.pendingAttemptIds.isNotEmpty())
        assertEquals(cashMoney(0), pending.reservedCashOut)
        val request = fixture.request(pending)
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), fixture.context(request), fixture.cut(pending))
        assertUnsupported(CashNonSaleUnsupported.PENDING_FINANCIAL_OR_CUSTODY_WORK,
            prepared.evaluate(fixture.authorization(prepared.requirement), fixture.now))
        val unrelated = prepared.requirement.copy(binding = checkNotNull(prepared.requirement.binding).copy(reasonCode = "cash.other"))
        assertFailsWith<IllegalArgumentException> { prepared.evaluate(fixture.authorization(unrelated), fixture.now) }
    }

    @Test fun pendingRealSafeDropIsNotSilentlyAcceptedWithoutACompleteSourceIndex() = runTest {
        val fixture = CashStoreFixture()
        val dropped = fixture.dropped(fixture.open())
        assertEquals(listOf(dropped.transfer.id), dropped.session.pendingTransferIds)
        assertUnsupported(CashNonSaleUnsupported.PENDING_FINANCIAL_OR_CUSTODY_WORK,
            fixture.evaluate(fixture.request(dropped.session), fixture.cut(dropped.session)))
    }

    @Test fun aRealPendingCashRefundKeepsItsPositiveReserveAndCannotBecomeANonSalePayout() = runTest {
        val fixture = CashStoreFixture()
        val refund = fixture.pendingRefund(fixture.open())
        val before = refund.session
        val attempt = refund.acceptedReturn.attempts.single()
        assertEquals(AttemptStatus.AWAITING_ATTESTATION, attempt.status)
        assertNull(attempt.latestObservation)
        assertNull(attempt.refund)
        assertEquals(cashMoney(0), refund.acceptedReturn.refunded)
        assertEquals(cashMoney(100), attempt.amount)
        assertEquals(attempt.amount, refund.acceptedReturn.due)
        assertEquals(listOf(attempt.id), before.pendingAttemptIds)
        assertEquals(attempt.amount, before.reservedCashOut)
        assertEquals(cashMoney(1_100), before.expectedCash)
        assertEquals(cashMoney(1_000), before.availableCash)
        assertTrue(before.pendingTransferIds.isEmpty())
        val cut = fixture.cut(before) // Canonical, codec-valid cash row with its real pending refund ID intact.
        val original = checkNotNull(cut.session).payload
        for (amount in listOf(before.availableCash, before.expectedCash)) {
            // Test both an otherwise affordable expense and one which would consume the refund reserve.
            val request = fixture.request(before, NonSaleCashKind.OPERATING_EXPENSE, amount)
            val outcome = fixture.evaluate(request, cut)
            // Reject has no session/projection/claim effects. This is proposal-level, not a SQLite commit assertion.
            assertUnsupported(CashNonSaleUnsupported.PENDING_FINANCIAL_OR_CUSTODY_WORK, outcome)
            assertEquals(original, checkNotNull(cut.session).payload)
            assertEquals(original, fixture.row(before).payload)
            assertNull(cut.movement)
            assertNull(cut.sourceClaimOwner)
            assertEquals(listOf(attempt.id), before.pendingAttemptIds)
            assertEquals(attempt.amount, before.reservedCashOut)
            assertEquals(cashMoney(1_100), before.expectedCash)
            assertEquals(cashMoney(1_000), before.availableCash)
            assertEquals(cashMoney(0), refund.acceptedReturn.refunded)
            assertNull(refund.acceptedReturn.attempts.single().refund)
        }
    }

    @Test fun exhaustedRowRevisionIsUnsupportedBeforeDomainMutationCanOverflowTheStorageMapping() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        // Explicit adversarial protected-row fixture: a bound large revision, NOT
        // a claim that 9e18 domain operations or a database commit occurred.
        val revision = Revision(Long.MAX_VALUE - 1)
        val row = fixture.row(before).copy(storageRevision = Revision(Long.MAX_VALUE), payload = cashBytes(
            fixture.row(before).payload.cashText().replace("\"revision\":\"0\"", "\"revision\":\"${revision.value}\"")))
        val request = fixture.request(before).copy(expectedDomainRevision = revision)
        assertUnsupported(CashNonSaleUnsupported.REVISION_EXHAUSTED,
            fixture.evaluate(request, fixture.cut(before).copy(session = row)))
    }

    @Test fun corruptSchemaBodyScopeKeyOrStorageRevisionIsNotDefaultOpenedOrReportedMissing() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val context = fixture.context(request)
        val valid = fixture.row(before)
        val wrongRows = listOf(
            valid.copy(schema = "sales.cash.session/v2"),
            valid.copy(key = CashNonSaleStoreProfile.sessionKey(fixture.nextId())),
            valid.copy(storageRevision = Revision(0)),
            valid.copy(storageRevision = Revision(2)),
            valid.copy(payload = cashBytes(valid.payload.cashText().replace(fixture.scope.branchId.value, fixture.nextId().value))),
            valid.copy(payload = cashBytes(valid.payload.cashText().replace("\"status\":\"OPEN\"", "\"status\":\"UNKNOWN\""))),
        )
        for (row in wrongRows) assertFailsWith<IllegalArgumentException> {
            prepareCashNonSale(CashNonSaleCommandCodec.encode(request), context, fixture.cut(before).copy(session = row))
        }
        val badBody = valid.copy(payload = cashBytes("{}"))
        assertFailsWith<SalesSnapshotRejectedException> {
            prepareCashNonSale(CashNonSaleCommandCodec.encode(request), context, fixture.cut(before).copy(session = badBody))
        }
    }

    @Test fun aWrongContextScopeCommandVersionDigestOrReadKeyCannotReachDomainEvaluation() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val context = fixture.context(request)
        val bytes = CashNonSaleCommandCodec.encode(request)
        for (bad in listOf(context.copy(key = CommandKey("sales.cash.move", 2)),
            context.copy(key = CommandKey("sales.cash.open", 1)),
            context.copy(semanticDigest = PayloadDigest("f".repeat(64))),
            context.copy(originalActorId = fixture.other))) {
            assertFailsWith<IllegalArgumentException> { prepareCashNonSale(bytes, bad, fixture.cut(before)) }
        }
        assertFailsWith<IllegalArgumentException> {
            prepareCashNonSale(bytes, context, fixture.cut(before).copy(scope = fixture.scope.copy(branchId = fixture.nextId())))
        }
        assertFailsWith<IllegalArgumentException> {
            prepareCashNonSale(CashNonSaleCommandCodec.encode(request.copy(reason = "Changed evidence")), context, fixture.cut(before))
        }
        val wrongMovement = fixture.row(before).copy(key = CashNonSaleStoreProfile.movementKey(fixture.nextId()))
        assertFailsWith<IllegalArgumentException> { prepareCashNonSale(bytes, context, fixture.cut(before, movement = wrongMovement)) }
    }

    @Test fun everyLiveCapabilityBindingComponentIsCheckedAtEvaluation() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open()
        val request = fixture.request(before)
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), fixture.context(request), fixture.cut(before))
        val requirement = prepared.requirement
        val binding = checkNotNull(requirement.binding)
        val altered = listOf(
            requirement.copy(binding = binding.copy(operationId = fixture.nextOperation())),
            requirement.copy(binding = binding.copy(payloadDigest = PayloadDigest("2".repeat(64)))),
            requirement.copy(binding = binding.copy(documentId = fixture.nextId())),
            requirement.copy(binding = binding.copy(revision = Revision(1))),
            requirement.copy(binding = binding.copy(reasonCode = "cash.changed")),
            requirement.copy(limitUse = LimitUse.Monetary(cashMoney(101))),
            requirement.copy(scope = fixture.scope.copy(branchId = fixture.nextId())),
        )
        for (other in altered) assertFailsWith<IllegalArgumentException> {
            prepared.evaluate(fixture.authorization(other), fixture.now)
        }
        val actorRequirement = requirement.copy(owner = OwnerConstraint.OwnerPrivate(fixture.other))
        assertFailsWith<IllegalArgumentException> {
            prepared.evaluate(fixture.authorization(actorRequirement, fixture.other), fixture.now)
        }
    }

    @Test fun checkedMoneyOverflowEscapesWithoutARecordedSuccessOrInventedNonPayment() = runTest {
        val fixture = CashStoreFixture()
        val before = fixture.open(cashMoney(Long.MAX_VALUE))
        val request = fixture.request(before, amount = cashMoney(1))
        val original = fixture.row(before).payload
        assertFailsWith<ArithmeticException> { fixture.evaluate(request, fixture.cut(before)) }
        assertEquals(original, fixture.row(before).payload)
    }
}

internal fun assertRejectedRule(rule: SalesRule, outcome: ProposedOutcome) {
    val rejected = assertIs<ProposedOutcome.Reject>(outcome)
    assertEquals("sales.cash.domain_rejected", rejected.code)
    assertEquals(CashNonSaleResultV1.DomainRejected(rule), CashNonSaleResultCodec.decode(rejected.result))
}

internal fun assertUnsupported(reason: CashNonSaleUnsupported, outcome: ProposedOutcome) {
    val rejected = assertIs<ProposedOutcome.Reject>(outcome)
    assertEquals("sales.cash.unsupported", rejected.code)
    assertEquals(CashNonSaleResultV1.Unsupported(reason), CashNonSaleResultCodec.decode(rejected.result))
}
