package com.ukkera.pos.sales.store.independent.retailhandoffhistoryv1

import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.HandoffAccepted
import com.ukkera.pos.sales.HandoffCancelled
import com.ukkera.pos.sales.NormalizedRetailHistory
import com.ukkera.pos.sales.Orders
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.RetailStockDemandV2
import com.ukkera.pos.sales.RetailStockRequirements
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesPriceSourceReference
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesSnapshotRejectedException
import com.ukkera.pos.sales.SalesTransition
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCollectionStockCodecV2
import com.ukkera.pos.sales.store.LocalCollectionStockRequestV2
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.CurrentAuthorizationProvider
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Frozen RHH v1 intrinsic snapshot/history-helper, domain and codec slices.
 * Scripted identity facts reach the shared domain entry points. These checks do
 * not create native originals or run production persistence, RPC or restart.
 */
class RetailHandoffFrozenHistorySlicesTest {
    @Test
    fun offeredHistoryKeepsOwnerCreatorRecipientAndQuote() = runTest {
        // RHH-001 expected offered stage; RHH-011 coherent offered control; I0/I1 subset.
        val fixture = Fixture()
        val before = fixture.baseline()
        val original = SalesSnapshotCodec.encodeOrder(before)
        val offered = fixture.offer(before).value

        fixture.requireHistory(Stage.OFFERED, before, offered)
        assertEquals(fixture.actorA, offered.originalCreatorId)
        assertEquals(fixture.actorA, offered.ownerId)
        val handoff = assertNotNull(offered.handoff)
        assertEquals(fixture.actorA, handoff.previousOwnerId)
        assertEquals(fixture.actorB, handoff.intendedRecipientId)
        assertEquals(fixture.actorA, handoff.evidence.actorId)
        fixture.assertOrderInvariant(before, offered)
        assertEquals(original, SalesSnapshotCodec.encodeOrder(before))
    }

    @Test
    fun cancelledHistoryKeepsOwnerAndRejectsFreshAcceptance() = runTest {
        // RHH-002/RHH-005 cancelled terminal state; RHH-011 coherent cancelled control.
        val fixture = Fixture()
        val baseline = fixture.baseline()
        val offered = fixture.offer(baseline).value
        val originalOffer = SalesSnapshotCodec.encodeOrder(offered)
        val cancellation = fixture.cancel(offered)
        val cancelled = cancellation.value

        fixture.requireHistory(Stage.CANCELLED, offered, cancelled)
        assertEquals(fixture.actorA, cancelled.originalCreatorId)
        assertEquals(fixture.actorA, cancelled.ownerId)
        assertNull(cancelled.handoff)
        assertEquals(fixture.actorA, cancellation.effects.filterIsInstance<HandoffCancelled>().single().actorId)
        fixture.assertOrderInvariant(baseline, cancelled)

        val attempt = fixture.handoffCommand(fixture.actorB, fixture.acceptRequest(cancelled), "fresh-after-cancel")
        assertFailsWith<SalesRuleViolation> {
            Orders.acceptHandoff(attempt, cancelled, cancelled.revision, fixture.handoffId, fixture.evidence(fixture.actorB))
        }
        assertEquals(originalOffer, SalesSnapshotCodec.encodeOrder(offered))
        fixture.assertOrderInvariant(baseline, cancelled)
    }

    @Test
    fun acceptedHistoryUsesIntendedActorAndPreservesCreator() = runTest {
        // RHH-003 accepted truth table; RHH-011 coherent A-to-B control; I0/I1 subset.
        val fixture = Fixture()
        val baseline = fixture.baseline()
        val offered = fixture.offer(baseline).value
        val originalOffer = SalesSnapshotCodec.encodeOrder(offered)
        val acceptance = fixture.accept(offered)
        val accepted = acceptance.value

        fixture.requireHistory(Stage.ACCEPTED, offered, accepted)
        assertEquals(fixture.actorA, accepted.originalCreatorId)
        assertEquals(fixture.actorB, accepted.ownerId)
        assertNull(accepted.handoff)
        val actors = acceptance.effects.filterIsInstance<HandoffAccepted>().single()
        assertEquals(fixture.actorA, actors.handoff.evidence.actorId)
        assertEquals(fixture.actorB, actors.acceptance.actorId)
        fixture.assertOrderInvariant(baseline, accepted)
        assertEquals(originalOffer, SalesSnapshotCodec.encodeOrder(offered))
    }

    @Test
    fun oldOwnerAndOtherEmployeeCannotReplaceIntendedAcceptingActor() = runTest {
        // RHH-004(c,d) actor-only subset; RHH-011 mismatched actual terminal actor subset.
        val fixture = Fixture()
        val baseline = fixture.baseline()
        val offered = fixture.offer(baseline).value
        val accepted = fixture.accept(offered).value
        val originalOffer = SalesSnapshotCodec.encodeOrder(offered)
        for (wrongActor in listOf(fixture.actorA, fixture.actorC)) {
            val command = fixture.handoffCommand(wrongActor, fixture.acceptRequest(offered), "wrong-${wrongActor.value}")
            assertFailsWith<SalesRuleViolation> {
                Orders.acceptHandoff(command, offered, offered.revision, fixture.handoffId, fixture.evidence(wrongActor))
            }
            assertFailsWith<SalesRuleViolation> {
                NormalizedRetailHistory.requireHandoffAccepted(offered, accepted, wrongActor,
                    fixture.handoffId, fixture.evidence(wrongActor))
            }
            assertEquals(originalOffer, SalesSnapshotCodec.encodeOrder(offered))
            assertEquals(fixture.actorA, offered.ownerId)
            fixture.assertOrderInvariant(baseline, offered)
        }
    }

    @Test
    fun obsoleteVersionAndAcceptedTerminalMutationPreserveOriginalSnapshots() = runTest {
        // RHH-005 obsolete expected version and fresh cancellation after acceptance subsets.
        val fixture = Fixture()
        val baseline = fixture.baseline()
        val offered = fixture.offer(baseline).value
        val originalOffer = SalesSnapshotCodec.encodeOrder(offered)
        val staleRequest = fixture.acceptRequest(offered).copy(expectedOrderRevision = baseline.revision)
        val staleCommand = fixture.handoffCommand(fixture.actorB, staleRequest, "obsolete-version")
        assertFailsWith<SalesRuleViolation> {
            Orders.acceptHandoff(staleCommand, offered, baseline.revision, fixture.handoffId, fixture.evidence(fixture.actorB))
        }

        val accepted = fixture.accept(offered).value
        val originalAccepted = SalesSnapshotCodec.encodeOrder(accepted)
        val terminalRequest = LocalCollectionStockRequestV2.CancelHandoff(accepted.id, accepted.revision, fixture.handoffId)
        val terminalCommand = fixture.handoffCommand(fixture.actorB, terminalRequest, "fresh-after-accept")
        assertFailsWith<SalesRuleViolation> {
            Orders.cancelHandoff(terminalCommand, accepted, accepted.revision, fixture.handoffId)
        }
        assertEquals(originalOffer, SalesSnapshotCodec.encodeOrder(offered))
        assertEquals(originalAccepted, SalesSnapshotCodec.encodeOrder(accepted))
        assertEquals(fixture.actorB, accepted.ownerId)
        fixture.assertOrderInvariant(baseline, accepted)
    }

    @Test
    fun olderOfferRemainsItsOriginalSnapshotAfterBothTerminalBranches() = runTest {
        // RHH-007 accepted and cancelled branches, intrinsic helper/codec boundary only.
        for (terminal in listOf(Stage.CANCELLED, Stage.ACCEPTED)) {
            val fixture = Fixture()
            val baseline = fixture.baseline()
            val offered = fixture.offer(baseline).value
            val originalOffer = SalesSnapshotCodec.encodeOrder(offered)
            val after = if (terminal == Stage.ACCEPTED) fixture.accept(offered).value else fixture.cancel(offered).value

            fixture.requireHistory(Stage.OFFERED, baseline, offered)
            fixture.requireHistory(terminal, offered, after)
            assertEquals(originalOffer, SalesSnapshotCodec.encodeOrder(offered))
            assertEquals(fixture.actorA, offered.ownerId)
            assertEquals(fixture.actorA, offered.handoff?.evidence?.actorId)
            assertEquals(fixture.actorB, offered.handoff?.intendedRecipientId)
            assertEquals(if (terminal == Stage.ACCEPTED) fixture.actorB else fixture.actorA, after.ownerId)
            assertNull(after.handoff)
            fixture.assertOrderInvariant(baseline, after)
        }
    }

    @Test
    fun historicalSnapshotsDoNotRestoreRevokedCurrentHandoffPermission() = runTest {
        // RHH-008 action-revocation variant for each stage: helper/current-policy subset.
        for (stage in Stage.entries) {
            val fixture = Fixture()
            val baseline = fixture.baseline()
            val offered = fixture.offer(baseline).value
            val before = if (stage == Stage.OFFERED) baseline else offered
            val after = when (stage) {
                Stage.OFFERED -> offered
                Stage.CANCELLED -> fixture.cancel(offered).value
                Stage.ACCEPTED -> fixture.accept(offered).value
            }
            val original = SalesSnapshotCodec.encodeOrder(after)
            val actor = if (stage == Stage.ACCEPTED) fixture.actorB else fixture.actorA
            fixture.handoffEnabled = false

            fixture.requireHistory(stage, before, after)
            val request = fixture.acceptRequest(after)
            assertIs<AuthorizationDecision.Denied>(fixture.handoffDecision(actor, request, "revoked-fresh-work"))
            assertEquals(original, SalesSnapshotCodec.encodeOrder(after))
            fixture.assertOrderInvariant(baseline, after)
        }
    }

    @Test
    fun mismatchedBusinessSnapshotsFailForEveryHandoffStage() = runTest {
        // RHH-011 creator/current-owner/foreign-branch/version/recipient mismatches.
        for (stage in Stage.entries) {
            val fixture = Fixture()
            val baseline = fixture.baseline()
            val offered = fixture.offer(baseline).value
            val before = if (stage == Stage.OFFERED) baseline else offered
            val after = when (stage) {
                Stage.OFFERED -> offered
                Stage.CANCELLED -> fixture.cancel(offered).value
                Stage.ACCEPTED -> fixture.accept(offered).value
            }
            val original = SalesSnapshotCodec.encodeOrder(after)
            val binding = fixture.binding(after)
            val variants = listOf(
                SnapshotVariant("creator", binding) { _, state -> state["originalCreatorId"] = JsonPrimitive(fixture.actorC.value) },
                SnapshotVariant("current-owner", binding, intrinsicRejection = stage == Stage.OFFERED) { _, state ->
                    state["ownerId"] = JsonPrimitive(fixture.actorC.value)
                },
                SnapshotVariant("foreign-branch", binding.copy(scope = binding.scope.copy(branchId = fixture.foreignBranch))) { envelope, _ ->
                    val scope = envelope.getValue("scope").jsonObject.toMutableMap()
                    scope["branchId"] = JsonPrimitive(fixture.foreignBranch.value)
                    envelope["scope"] = JsonObject(scope)
                },
                SnapshotVariant("obsolete-version", binding.copy(revision = before.revision), intrinsicRejection = stage == Stage.OFFERED) { envelope, _ ->
                    envelope["revision"] = JsonPrimitive(before.revision.value.toString())
                },
            ) + if (stage == Stage.OFFERED) listOf(
                SnapshotVariant("intended-recipient", binding) { _, state ->
                    val handoff = state.getValue("handoff").jsonObject.toMutableMap()
                    handoff["intendedRecipientId"] = JsonPrimitive(fixture.actorC.value)
                    state["handoff"] = JsonObject(handoff)
                },
            ) else emptyList()

            for (variant in variants) {
                // Corrupt only a disposable snapshot; the coherent control remains unchanged.
                // Construct bytes and independent synthetic row metadata OUTSIDE rejection assertions.
                val encoded = fixture.alteredSnapshotBytes(after, variant.alter)
                val label = "$stage/${variant.label}"
                if (variant.intrinsicRejection) {
                    // Offered owner/version mutations disagree with the retained handoff itself.
                    assertFailsWith<SalesSnapshotRejectedException>(label) {
                        SalesSnapshotCodec.decodeOrder(encoded, variant.binding)
                    }
                } else {
                    // These disposable variants must decode coherently before helper comparison rejects them.
                    val changed = SalesSnapshotCodec.decodeOrder(encoded, variant.binding)
                    assertFailsWith<SalesRuleViolation>(label) {
                        fixture.requireHistory(stage, before, changed)
                    }
                }
                fixture.requireHistory(stage, before, after)
                assertEquals(original, SalesSnapshotCodec.encodeOrder(after))
            }
        }
    }

    @Test
    fun truncatedOrForeignBoundSnapshotsCannotDecodeAsOriginalOrder() = runTest {
        // RHH-011 truncation/another-order/foreign-branch/bound-version intrinsic codec subset.
        for (stage in Stage.entries) {
            val fixture = Fixture()
            val baseline = fixture.baseline()
            val offered = fixture.offer(baseline).value
            val after = when (stage) {
                Stage.OFFERED -> offered
                Stage.CANCELLED -> fixture.cancel(offered).value
                Stage.ACCEPTED -> fixture.accept(offered).value
            }
            val original = SalesSnapshotCodec.encodeOrder(after)
            val binding = fixture.binding(after)
            assertFailsWith<SalesSnapshotRejectedException> { SalesSnapshotCodec.decodeOrder(original.dropLast(1), binding) }
            for (wrong in listOf(
                binding.copy(id = fixture.otherOrderId),
                binding.copy(scope = binding.scope.copy(branchId = fixture.foreignBranch)),
                binding.copy(revision = baseline.revision),
            )) {
                assertFailsWith<SalesSnapshotRejectedException> { SalesSnapshotCodec.decodeOrder(original, wrong) }
            }
            assertEquals(original, SalesSnapshotCodec.encodeOrder(SalesSnapshotCodec.decodeOrder(original, binding)))
        }
    }

    @Test
    fun exactPayloadsKeepSemanticIdentityAndChangedPayloadsRemainDistinct() = runTest {
        // RHH-006 codec/digest subset only: no original-result recovery or store conflict asserted.
        val fixture = Fixture()
        val baseline = fixture.baseline()
        val offered = fixture.offer(baseline).value
        val requests = listOf<Pair<DomainId, LocalCollectionStockRequestV2>>(
            fixture.actorA to fixture.offerRequest(baseline),
            fixture.actorA to LocalCollectionStockRequestV2.CancelHandoff(offered.id, offered.revision, fixture.handoffId),
            fixture.actorB to fixture.acceptRequest(offered),
        )
        for ((actor, request) in requests) {
            val operation = fixture.operation("original-${request::class.simpleName}")
            val original = LocalCollectionStockCodecV2.command(operation, request)
            val normalized = LocalCollectionStockCodecV2.normalize(original.payload)
            val digest = semanticDigest(fixture.scope, actor, original.command, normalized)
            val duplicate = LocalCollectionStockCodecV2.command(operation, LocalCollectionStockCodecV2.decode(normalized))
            assertEquals(operation, duplicate.operationId)
            assertEquals(original.command, duplicate.command)
            assertEquals(normalized, duplicate.payload)
            assertEquals(digest, semanticDigest(fixture.scope, actor, duplicate.command, duplicate.payload))

            val changes = when (request) {
                is LocalCollectionStockRequestV2.OfferHandoff -> listOf(
                    request.copy(orderId = fixture.otherOrderId),
                    request.copy(recipientId = fixture.actorC),
                    request.copy(expectedOrderRevision = offered.revision),
                    request.copy(evidence = request.evidence.copy(evidenceId = fixture.otherEvidenceId)),
                )
                is LocalCollectionStockRequestV2.CancelHandoff -> listOf(
                    request.copy(orderId = fixture.otherOrderId),
                    request.copy(expectedOrderRevision = baseline.revision),
                    request.copy(handoffId = fixture.otherHandoffId),
                )
                is LocalCollectionStockRequestV2.AcceptHandoff -> listOf(
                    request.copy(orderId = fixture.otherOrderId),
                    request.copy(expectedOrderRevision = baseline.revision),
                    request.copy(handoffId = fixture.otherHandoffId),
                    request.copy(evidence = request.evidence.copy(evidenceId = fixture.otherEvidenceId)),
                )
                else -> error("Only the frozen handoff variants belong in this slice")
            }
            for (changed in changes) {
                val collision = LocalCollectionStockCodecV2.command(operation, changed)
                assertEquals(operation, collision.operationId)
                assertNotEquals(digest, semanticDigest(fixture.scope, actor, collision.command, collision.payload))
            }
            assertNotEquals(digest, semanticDigest(fixture.scope, fixture.actorC, original.command, normalized))
        }
    }

    private enum class Stage { OFFERED, CANCELLED, ACCEPTED }

    private data class SnapshotVariant(
        val label: String,
        val binding: SalesSnapshotBinding,
        val intrinsicRejection: Boolean = false,
        val alter: (MutableMap<String, JsonElement>, MutableMap<String, JsonElement>) -> Unit,
    )

    private class Fixture {
        val actorA = id("A")
        val actorB = id("B")
        val actorC = id("C")
        val scope = BranchScope(id("ORG-1"), id("RETAIL-1"), id("BRANCH-1"))
        val foreignBranch = id("BRANCH-2")
        val orderId = id("R")
        val otherOrderId = id("OTHER-R")
        val handoffId = id("R-B")
        val otherHandoffId = id("OTHER-HANDOFF")
        val otherEvidenceId = id("OTHER-EVIDENCE")
        private val lineId = id("LINE-R-1")
        private val unitId = id("UNIT-R-1")
        private val at = Instant.parse("2026-10-30T12:00:00Z")
        var handoffEnabled = true

        // Scripted adapters provide distinct A/B/C identities and current facts,
        // not genuine local sign-in, persisted grants or native authorization sources.
        private val factory = TrustedPrincipalFactory(object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
                val actor = listOf(actorA, actorB, actorC).singleOrNull { id("connection-${it.value}") == credentials.connectionId } ?: return null
                return VerifiedLocalIdentity(actor, scope, id("device-${actor.value}"), id("session-${actor.value}"),
                    SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
            }
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        })
        private val policy = AuthorizationPolicy(CurrentAuthorizationProvider { principal ->
            val grants = listOf(SecurityAction.ORDER_CREATE) + if (handoffEnabled) listOf(SecurityAction.ORDER_HANDOFF) else emptyList()
            AuthorizationSnapshot(principal.subjectId, PrincipalKind.LOCAL_EMPLOYEE, principal.scope,
                principal.authenticationId, principal.generation, principal.deviceId, principal.admissionRevision,
                Revision(1), true, true, true, AuthenticationPurpose.INTERACTIVE,
                grants.map { action -> PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope,
                    GrantEffect.ALLOW, ownerAccess = OwnerAccess.OWN_ONLY, namedSubjectId = principal.subjectId, targetIds = setOf(orderId)) })
        })

        suspend fun baseline(): OwnedOrder {
            val quantity = Quantity(unitId, 2, 0)
            val quote = OperationalPricingEngine.calculate(PricingRequest(orderId, Revision(0), Currency.EGP,
                listOf(ResolvedPriceLine(lineId, lineId, Revision(0), quantity, UnitPrice.parse(Currency.EGP, "100.00"),
                    TaxInclusion.INCLUSIVE, TaxSnapshot(id("TAX-R-1"), Revision(0), "synthetic-zero", PercentageRate.parse("0"))))))
            val operation = operation("create-R")
            val digest = sha256("synthetic-domain-fixture:create-R".encodeToByteArray())
            val requirement = salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, scope, orderId, Revision(0), operation,
                digest, "order.create", actorA)
            val authorization = assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal(actorA), requirement)).authorization
            return Orders.create(SalesCommand(operation, authorization, at, digest, "order.create"), scope, orderId, quote,
                RetailStockRequirements.normalized(listOf(RetailStockDemandV2(lineId, id("SKU-R-1"), id("WAREHOUSE-R-1"), quantity))),
                SalesPriceSourceReference(id("PRICE-R-1"), sha256("inert-price-reference:R".encodeToByteArray()))).value
        }

        fun offerRequest(order: OwnedOrder): LocalCollectionStockRequestV2.OfferHandoff =
            LocalCollectionStockRequestV2.OfferHandoff(order.id, order.revision, handoffId, actorB,
                LocalCashEvidenceV1(id("offer-evidence"), UtcInstant(at)))

        fun acceptRequest(order: OwnedOrder): LocalCollectionStockRequestV2.AcceptHandoff =
            LocalCollectionStockRequestV2.AcceptHandoff(order.id, order.revision, handoffId,
                LocalCashEvidenceV1(id("accept-evidence"), UtcInstant(at)))

        suspend fun offer(before: OwnedOrder): SalesTransition<OwnedOrder> = Orders.offerHandoff(
            handoffCommand(actorA, offerRequest(before), "OFFER-R-B"), before, before.revision, handoffId, actorB, evidence(actorA))

        suspend fun cancel(before: OwnedOrder): SalesTransition<OwnedOrder> = Orders.cancelHandoff(
            handoffCommand(actorA, LocalCollectionStockRequestV2.CancelHandoff(before.id, before.revision, handoffId), "CANCEL-R-B"),
            before, before.revision, handoffId)

        suspend fun accept(before: OwnedOrder): SalesTransition<OwnedOrder> = Orders.acceptHandoff(
            handoffCommand(actorB, acceptRequest(before), "ACCEPT-R-B"), before, before.revision, handoffId, evidence(actorB))

        fun evidence(actor: DomainId): CashierEvidence = CashierEvidence(id(if (actor == actorA) "offer-evidence" else "accept-evidence"), actor, at)

        suspend fun handoffCommand(actor: DomainId, request: LocalCollectionStockRequestV2, alias: String): SalesCommand {
            val operation = operation(alias)
            val payload = LocalCollectionStockCodecV2.encode(request)
            val digest = semanticDigest(scope, actor, StockStreamingCommandsV2.COLLECTION, payload)
            val authorization = assertIs<AuthorizationDecision.Allowed>(handoffDecision(actor, request, alias)).authorization
            return SalesCommand(operation, authorization, at, digest, reason(request))
        }

        suspend fun handoffDecision(actor: DomainId, request: LocalCollectionStockRequestV2, alias: String): AuthorizationDecision {
            val revision = when (request) {
                is LocalCollectionStockRequestV2.OfferHandoff -> request.expectedOrderRevision
                is LocalCollectionStockRequestV2.CancelHandoff -> request.expectedOrderRevision
                is LocalCollectionStockRequestV2.AcceptHandoff -> request.expectedOrderRevision
                else -> error("Only handoffs belong in this fixture")
            }
            val digest = semanticDigest(scope, actor, StockStreamingCommandsV2.COLLECTION, LocalCollectionStockCodecV2.encode(request))
            val requirement = salesAuthorizationRequirement(SecurityAction.ORDER_HANDOFF, scope, orderId, revision, operation(alias),
                digest, reason(request), actor)
            return policy.authorize(principal(actor), requirement)
        }

        fun requireHistory(stage: Stage, before: OwnedOrder, after: OwnedOrder) {
            when (stage) {
                Stage.OFFERED -> NormalizedRetailHistory.requireHandoffOffered(before, after, actorA, handoffId, actorB, evidence(actorA))
                Stage.CANCELLED -> NormalizedRetailHistory.requireHandoffCancelled(before, after, actorA, handoffId)
                Stage.ACCEPTED -> NormalizedRetailHistory.requireHandoffAccepted(before, after, actorB, handoffId, evidence(actorB))
            }
        }

        fun assertOrderInvariant(before: OwnedOrder, after: OwnedOrder) {
            assertEquals(orderId, after.id)
            assertEquals(scope, after.scope)
            assertEquals(actorA, after.originalCreatorId)
            assertEquals(Money(Currency.EGP, 20000), after.quote.payable)
            val line = after.quote.lines.single()
            assertEquals(lineId, line.lineId)
            assertEquals(lineId, line.portionId)
            assertEquals(Quantity(unitId, 2, 0), line.quantity)
            assertEquals(UnitPrice.parse(Currency.EGP, "100.00"), line.unitPrice)
            assertEquals(before.quote.components, after.quote.components)
            assertTrue(before.stockRequirements.sameSource(after.stockRequirements))
            assertEquals(before.priceSourceReference, after.priceSourceReference)
            assertNull(after.collectionId)
            assertNull(after.paidSaleId)
        }

        fun binding(order: OwnedOrder): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.ORDER, order.scope, order.id, order.revision)

        fun alteredSnapshotBytes(order: OwnedOrder, alter: (MutableMap<String, JsonElement>, MutableMap<String, JsonElement>) -> Unit): String {
            val envelope = Json.parseToJsonElement(SalesSnapshotCodec.encodeOrder(order)).jsonObject.toMutableMap()
            val state = envelope.getValue("state").jsonObject.toMutableMap()
            alter(envelope, state)
            envelope["state"] = JsonObject(state)
            return JsonObject(envelope).toString()
        }

        fun operation(alias: String): OperationId = OperationId(id("operation:$alias").value)

        private suspend fun principal(actor: DomainId): LocalEmployeePrincipal {
            val credential = OpaqueCredential(ByteArray(32) { 7 })
            return try { assertNotNull(factory.authenticateLocal(LocalGatewayCredentials(id("connection-${actor.value}"), credential))) }
            finally { credential.destroy() }
        }

        private fun reason(request: LocalCollectionStockRequestV2): String = when (request) {
            is LocalCollectionStockRequestV2.OfferHandoff -> "order.handoff.offer"
            is LocalCollectionStockRequestV2.CancelHandoff -> "order.handoff.cancel"
            is LocalCollectionStockRequestV2.AcceptHandoff -> "order.handoff.accept"
            else -> error("Only handoffs belong in this fixture")
        }

        private fun id(alias: String): DomainId = DomainId(UUID.nameUUIDFromBytes("retail-handoff:$alias".encodeToByteArray()).toString())
    }
}
