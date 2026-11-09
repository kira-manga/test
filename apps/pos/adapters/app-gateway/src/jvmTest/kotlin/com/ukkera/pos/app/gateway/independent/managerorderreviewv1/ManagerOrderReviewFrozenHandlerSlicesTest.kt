package com.ukkera.pos.app.gateway.independent.managerorderreviewv1

import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.sales.store.LocalManagerOrderReviewCodec
import com.ukkera.pos.sales.store.LocalManagerOrderReviewQueryV1
import com.ukkera.pos.sales.store.LocalOrderReadCodec
import com.ukkera.pos.sales.store.LocalOrderReadCodecV2
import com.ukkera.pos.sales.store.LocalOrderStockProfileV2
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Partial frozen manager-review semantic/binding slices through the production read factory.
 * Controlled rows and scripted proofs are not native authorization, a committed business cut,
 * persistence/restart, protected adapter/RPC or full-case evidence. No canonical byte oracle is used.
 */
class ManagerOrderReviewFrozenHandlerSlicesTest {
    @Test
    fun mrr001ReadFactoryKeepsSeparateManagerV1AndBothOldIdentities() {
        val keys = installedManagerReviewReaders().map { it.key }
        val manager = ReadKey("sales.order.manager-review", 1)
        assertEquals(manager, LocalManagerOrderReviewCodec.QUERY)
        assertEquals(1, keys.count { it == manager })
        assertEquals(1, keys.count { it == ReadKey("sales.order.read", 1) })
        assertEquals(1, keys.count { it == ReadKey("sales.order.read", 2) })
        assertTrue(ReadKey("sales.order.manager-review", 2) !in keys)
        assertNotEquals(manager, LocalOrderReadCodec.QUERY)
        assertNotEquals(manager, LocalOrderReadCodecV2.QUERY)
        // Factory identity only: no unsupported-version reply or old access/schema oracle is bound.
    }

    @Test
    fun mrr005PreparedLegacyRequirementKeepsExactFrozenReviewBinding() = runTest {
        val read = prepareControlledReview()
        val requirement = read.prepared.requirements.single()
        assertEquals(Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(controlledManagerReviewScope, requirement.scope)
        assertEquals(OwnerConstraint.Unrestricted, requirement.owner)
        assertEquals(LimitUse.NotApplicable, requirement.limitUse)
        val binding = assertNotNull(requirement.binding)
        assertEquals(FrozenManagerReview.legacyOrder, binding.documentId)
        assertEquals(Revision(7), binding.revision)
        assertEquals(read.context.semanticDigest, binding.payloadDigest,
            "Supplied computed digest is forwarded; independent D_L remains unbound")
        assertEquals("order.manager-review", binding.reasonCode)
        assertNull(binding.operationId, "The read is not a mutation operation identity")
        assertNotEquals(FrozenManagerReview.owner, read.context.actorId)
    }

    @Test
    fun mrr005LegacyForeignOwnerProjectionKeepsFrozenRevisionQuoteAndNullReferences() = runTest {
        val read = prepareControlledReview()
        val principal = ControlledReviewAuthority.employee()
        val proof = ControlledReviewAuthority.proof(principal, read.prepared.requirements.single())
        val result = LocalManagerOrderReviewCodec.decode(
            read.prepared.project(listOf(proof), controlledManagerReviewObservedAt), read.query)
        assertNull(result.nextAfter)
        val profiled = result.orders.single()
        assertEquals(LocalOrderStockProfileV2.LEGACY_BOUNDED, profiled.stockProfile)
        val order = profiled.order
        assertEquals(FrozenManagerReview.legacyOrder, order.orderId)
        assertEquals(FrozenManagerReview.owner, order.ownerId)
        assertEquals(Revision(7), order.revision)
        assertNotEquals(principal.actorId, order.ownerId, "Explicit review is not restricted to the current owner")
        assertEquals(Money(Currency.EGP, 20000), order.beforeDiscount)
        assertEquals(Money(Currency.EGP, 0), order.discount)
        assertEquals(Money(Currency.EGP, 20000), order.payable)
        val selected = order.selection.lines.single().quantity
        assertEquals(Quantity.parse(selected.unitId, "2", selected.scale), selected)
        val priced = order.lines.single()
        assertEquals(Quantity.parse(priced.quantity.unitId, "2", priced.quantity.scale), priced.quantity)
        assertEquals(Money(Currency.EGP, 20000), priced.payable)
        assertEquals(20000L, order.components.sumOf { it.amount.minor })
        assertEquals(0L, order.components.filter { it.kind == "ITEM_TAX" || it.kind == "CHARGE_TAX" }
            .sumOf { it.amount.minor })
        assertTrue(order.components.all { it.amount.currency == Currency.EGP })
        assertNull(order.collectionId)
        assertNull(order.saleId)
        assertNull(order.handoffId)
        assertNull(order.handoffRecipientId)
        // Names, full selection/quote schema and live catalog-150 proof are unbound, not inferred.
    }

    @Test
    fun mrr009OrdinaryReadHandoffAndNonPerformGrantsCannotAuthorizeControlledReviewRequirement() = runTest {
        val read = prepareControlledReview()
        val requirement = read.prepared.requirements.single()
        val principal = ControlledReviewAuthority.employee()
        val variants = listOf(
            emptyList(),
            listOf(ControlledReviewAuthority.grant(principal,
                Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), FrozenManagerReview.legacyOrder)),
            listOf(ControlledReviewAuthority.grant(principal,
                Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM), FrozenManagerReview.legacyOrder)),
            listOf(ControlledReviewAuthority.grant(principal,
                Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.REQUEST), FrozenManagerReview.legacyOrder)),
        )
        for (grants in variants) {
            assertIs<AuthorizationDecision.Denied>(AuthorizationEvaluator.evaluate(principal,
                ControlledReviewAuthority.snapshot(principal, grants), requirement))
            assertFailsWith<IllegalStateException> {
                read.prepared.project(emptyList(), controlledManagerReviewObservedAt)
            }
        }
        // These are controlled policy snapshots, not activated-title/current-policy tests.
    }

    @Test
    fun mrr009OrdinaryOwnerReadGrantStillCannotAuthorizeControlledReviewRequirement() = runTest {
        val read = prepareControlledReview(actorId = FrozenManagerReview.owner)
        val principal = ControlledReviewAuthority.employee(FrozenManagerReview.owner)
        val grant = ControlledReviewAuthority.grant(principal,
            Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), FrozenManagerReview.legacyOrder)
        assertIs<AuthorizationDecision.Denied>(AuthorizationEvaluator.evaluate(principal,
            ControlledReviewAuthority.snapshot(principal, listOf(grant)), read.prepared.requirements.single()))
        assertFailsWith<IllegalStateException> { read.prepared.project(emptyList(), controlledManagerReviewObservedAt) }
    }

    @Test
    fun mrr010ProjectionRejectsProofBoundToAnotherTarget() = runTest {
        val read = prepareControlledReview()
        val principal = ControlledReviewAuthority.employee()
        val expected = read.prepared.requirements.single()
        val wrong = expected.copy(binding = assertNotNull(expected.binding).copy(documentId = FrozenManagerReview.normalizedOrder))
        val proof = ControlledReviewAuthority.proof(principal, wrong)
        assertFailsWith<IllegalStateException> { read.prepared.project(listOf(proof), controlledManagerReviewObservedAt) }
        // Existing target-domain representation and native target grant provisioning remain unbound.
    }

    @Test
    fun mrr011LegacyProjectionRejectsZeroStaleFutureAndOtherWrongRevisionProofs() = runTest {
        val read = prepareControlledReview()
        val principal = ControlledReviewAuthority.employee()
        val expected = read.prepared.requirements.single()
        val binding = assertNotNull(expected.binding)
        assertEquals(Revision(7), binding.revision)
        for (value in listOf(0L, 6L, 8L, 9001L)) {
            val wrong = expected.copy(binding = binding.copy(revision = Revision(value)))
            val proof = ControlledReviewAuthority.proof(principal, wrong)
            assertFailsWith<IllegalStateException>("Different-requirement proof at revision $value cannot disclose F_L") {
                read.prepared.project(listOf(proof), controlledManagerReviewObservedAt)
            }
        }
        // 9001 is only a wrong proof revision, never a claimed native generation.
    }

    @Test
    fun mrr012ProjectionRejectsProofForUnequalComputedRequestDigest() = runTest {
        val read = prepareControlledReview()
        val principal = ControlledReviewAuthority.employee()
        val expected = read.prepared.requirements.single()
        val normalizedOther = installedManagerReviewReader().normalize(
            LocalManagerOrderReviewCodec.encode(LocalManagerOrderReviewQueryV1(FrozenManagerReview.normalizedOrder)))
        val otherDigest = queryDigest(read.context.scope, read.context.actorId, read.context.key, normalizedOther)
        assertNotEquals(read.context.semanticDigest, otherDigest,
            "Computed wiring values are unequal; neither is an independent digest vector")
        val wrong = expected.copy(binding = assertNotNull(expected.binding).copy(payloadDigest = otherDigest))
        val proof = ControlledReviewAuthority.proof(principal, wrong)
        assertFailsWith<IllegalStateException> { read.prepared.project(listOf(proof), controlledManagerReviewObservedAt) }
        // D_L/D_N, accepted-equivalent frames and an independently missing-digest binding stay unbound.
    }

    @Test
    fun mrr013ProjectionRejectsOrdinaryReadAndHandoffPurposeProofs() = runTest {
        val read = prepareControlledReview()
        val principal = ControlledReviewAuthority.employee()
        val expected = read.prepared.requirements.single()
        val binding = assertNotNull(expected.binding)
        assertEquals("order.manager-review", binding.reasonCode)
        for (purpose in listOf("order.read", "order.handoff")) {
            val proof = ControlledReviewAuthority.proof(principal, expected.copy(binding = binding.copy(reasonCode = purpose)))
            assertFailsWith<IllegalStateException> { read.prepared.project(listOf(proof), controlledManagerReviewObservedAt) }
        }
        // A missing-purpose representation is not independently bound by the frozen public contract.
    }

    @Test
    fun mrr015ContextAndBorrowedScopeMustAgreeBeforeControlledSourceRead() = runTest {
        val handler = installedManagerReviewReader()
        val view = ControlledManagerReviewView(scope = controlledForeignReviewScope)
        val normalized = handler.normalize(LocalManagerOrderReviewCodec.encode(
            LocalManagerOrderReviewQueryV1(FrozenManagerReview.foreignBranchOrder)))
        val context = ReadContext(controlledManagerReviewScope, FrozenManagerReview.manager, handler.key,
            queryDigest(controlledManagerReviewScope, FrozenManagerReview.manager, handler.key, normalized))
        assertFailsWith<IllegalStateException> { handler.prepare(normalized, view, context) }
        assertTrue(view.aggregateCalls.isEmpty())
        assertTrue(view.claimCalls.isEmpty())
        // Context wiring only; no native F_BRANCH_B source/current branch grant is supplied.
    }

    @Test
    fun mrr018AbsentTargetProducesRevisionZeroRequirementAndAuthorizedEmptyProjection() = runTest {
        val read = prepareControlledReview(FrozenManagerReview.absentOrder, view = ControlledManagerReviewView())
        val requirement = read.prepared.requirements.single()
        assertEquals(Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(OwnerConstraint.Unrestricted, requirement.owner)
        assertEquals(controlledManagerReviewScope, requirement.scope)
        val binding = assertNotNull(requirement.binding)
        assertEquals(FrozenManagerReview.absentOrder, binding.documentId)
        assertEquals(Revision(0), binding.revision)
        assertEquals(read.context.semanticDigest, binding.payloadDigest)
        assertEquals("order.manager-review", binding.reasonCode)
        val principal = ControlledReviewAuthority.employee()
        val proof = ControlledReviewAuthority.proof(principal, requirement)
        val result = LocalManagerOrderReviewCodec.decode(
            read.prepared.project(listOf(proof), controlledManagerReviewObservedAt), read.query)
        assertTrue(result.orders.isEmpty())
        assertNull(result.nextAfter)
        // This controlled empty view is not independently established native absence or D_A.
    }

    @Test
    fun mrr019AbsenceRejectsMissingAndMismatchedControlledProofs() = runTest {
        val read = prepareControlledReview(FrozenManagerReview.absentOrder, view = ControlledManagerReviewView())
        val principal = ControlledReviewAuthority.employee()
        val requirement = read.prepared.requirements.single()
        val binding = assertNotNull(requirement.binding)
        assertIs<AuthorizationDecision.Denied>(AuthorizationEvaluator.evaluate(principal,
            ControlledReviewAuthority.snapshot(principal, emptyList()), requirement))
        assertFailsWith<IllegalStateException> { read.prepared.project(emptyList(), controlledManagerReviewObservedAt) }
        val legacyNormalized = installedManagerReviewReader().normalize(
            LocalManagerOrderReviewCodec.encode(LocalManagerOrderReviewQueryV1(FrozenManagerReview.legacyOrder)))
        val legacyDigest = queryDigest(read.context.scope, read.context.actorId, read.context.key, legacyNormalized)
        assertNotEquals(binding.payloadDigest, legacyDigest, "Unequal computed wiring digests, not D_A/D_L vectors")
        val wrongBindings = listOf(
            binding.copy(revision = Revision(1)),
            binding.copy(documentId = FrozenManagerReview.legacyOrder),
            binding.copy(payloadDigest = legacyDigest),
            binding.copy(reasonCode = "order.read"),
        )
        for (wrong in wrongBindings) {
            val proof = ControlledReviewAuthority.proof(principal, requirement.copy(binding = wrong))
            assertFailsWith<IllegalStateException> { read.prepared.project(listOf(proof), controlledManagerReviewObservedAt) }
        }
    }
}
