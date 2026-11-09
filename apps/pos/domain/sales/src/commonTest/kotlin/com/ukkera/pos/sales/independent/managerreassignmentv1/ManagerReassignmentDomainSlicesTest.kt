package com.ukkera.pos.sales.independent.managerreassignmentv1

import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/** Pure proposals from public APIs only. Native activity/recipient/history/index/commit proof is absent. */
class ManagerReassignmentDomainSlicesTest {
    @Test
    fun mra002And009And026ProposalMovesOnlyResponsibilityAndCarriesExactManagerFacts() = runTest {
        val f = DomainReassignmentInputs()
        val base = f.base()
        val before = base.last().value
        assertEquals(listOf(f.operation("OP-CREATE-ORIGINAL"), f.operation("OP-HANDOFF-OFFER-ORIGINAL"),
            f.operation("OP-HANDOFF-ACCEPT-ORIGINAL")), base.map { it.command.operationId })
        assertEquals(f.creator, base[0].value.ownerId)
        assertEquals(f.creator, base[1].value.ownerId)
        assertEquals(f.previousOwner, before.ownerId)
        assertEquals(f.r17, before.revision)
        assertNotEquals(before.originalCreatorId, before.ownerId)
        f.assertFrozenPricing(before.quote)

        val command = f.command()
        val proposal = Orders.reassign(command, before, before.revision, f.previousOwner, f.recipient, f.evidence())
        val after = proposal.value
        assertEquals(f.recipient, after.ownerId)
        assertEquals(f.r18, after.revision)
        assertEquals(before.id, after.id)
        assertEquals(before.scope, after.scope)
        assertEquals(f.creator, after.originalCreatorId)
        assertSame(before.quote, after.quote)
        assertSame(before.stockRequirements, after.stockRequirements)
        assertEquals(before.stockRequirements.normalizedLines, after.stockRequirements.normalizedLines)
        assertEquals(before.priceSourceReference, after.priceSourceReference)
        assertEquals(before.returnPolicySourceReference, after.returnPolicySourceReference)
        assertNull(after.handoff)
        assertNull(after.collectionId)
        assertNull(after.paidSaleId)
        assertEquals(listOf(SalesVersionCheck(SalesAggregate.ORDER, f.target, f.r17)), proposal.versions)
        assertTrue(proposal.unique.isEmpty())
        assertTrue(proposal.inventoryDependencies.isEmpty())
        assertEquals(2, proposal.effects.size)
        assertSame(after, assertIs<OrderChanged>(proposal.effects[0]).order)
        val event = assertIs<OrderReassigned>(proposal.effects[1])
        f.assertOriginalEvent(event)
        assertEquals(command.recordedAt, event.recordedAt)
        assertNotEquals(event.evidence.occurredAt, event.recordedAt)
        assertTrue(proposal.effects.none { it is HandoffAccepted || it is HandoffOffered })
        assertEquals(f.previousOwner, before.ownerId)
        assertEquals(f.creator, base[1].value.ownerId)
        // Complete quote equality is intrinsic; current P6 catalog, resolved-not-paid history and cash are unbound.
    }

    @Test
    fun mra004FrozenValidReasonsAreExactAndInvalidReasonsCannotYieldAProposal() = runTest {
        val f = DomainReassignmentInputs()
        for (reason in listOf("A", "a".repeat(128), f.reason, "غياب_الموظف١")) {
            val before = f.base().last().value
            val proposal = Orders.reassign(f.command(reason = reason), before, before.revision,
                f.previousOwner, f.recipient, f.evidence())
            assertEquals(reason, proposal.effects.filterIsInstance<OrderReassigned>().single().reasonCode)
            assertEquals(f.recipient, proposal.value.ownerId)
        }
        for (reason in listOf("", "a".repeat(129), "OWNER ABSENT", " OWNER_ABSENT", "OWNER_ABSENT\n",
            "OWNER/ABSENT", "ABSENT🙂", "e\u0301")) {
            val before = f.base().last().value
            val command = f.command().copy(reasonCode = reason)
            rejects(SalesRule.INVALID_EVIDENCE) {
                Orders.reassign(command, before, before.revision, f.previousOwner, f.recipient, f.evidence())
            }
            assertEquals(f.previousOwner, before.ownerId)
            assertEquals(f.r17, before.revision)
        }
        // Missing fields are codec/gateway cuts; no durable MOVE_B or rejected operation registry is observed.
    }

    @Test
    fun mra005StaleRevisionWrongPreviousOwnerSameRecipientAndWrongEvidenceActorRefuse() = runTest {
        val f = DomainReassignmentInputs()
        val before = f.base().last().value
        rejects(SalesRule.REVISION_CONFLICT) {
            Orders.reassign(f.command(), before, f.r16, f.previousOwner, f.recipient, f.evidence())
        }
        rejects(SalesRule.OWNER_REQUIRED) {
            Orders.reassign(f.command(), before, before.revision, f.creator, f.recipient, f.evidence())
        }
        rejects(SalesRule.HANDOFF_CONFLICT) {
            Orders.reassign(f.command(), before, before.revision, f.previousOwner, f.previousOwner, f.evidence())
        }
        rejects(SalesRule.INVALID_EVIDENCE) {
            Orders.reassign(f.command(), before, before.revision, f.previousOwner, f.recipient,
                f.evidence().copy(actorId = f.previousOwner))
        }
        assertEquals(f.previousOwner, before.ownerId)
        assertEquals(f.r17, before.revision)
        // The domain has no account directory: unknown/foreign/closing/full recipient checks remain unbound.
    }

    @Test
    fun mra006And008MissingSourceReferenceOrWrongCompleteAuthorizationBindingRefuse() = runTest {
        val f = DomainReassignmentInputs()
        val missing = f.base(withPriceReference = false).last().value
        rejects(SalesRule.UNSUPPORTED_STOCK_TIMING) {
            Orders.reassign(f.command(), missing, missing.revision, f.previousOwner, f.recipient, f.evidence())
        }
        val before = f.base().last().value
        val wrongBindings = listOf(
            f.command(action = SecurityAction.ORDER_HANDOFF),
            f.command(target = f.id("ORDER-A-OTHER")),
            f.command().copy(payloadDigest = PayloadDigest("3".repeat(64))),
            f.command().copy(operationId = f.operation("OP-MRA-LATER")),
            f.command().copy(reasonCode = "A"),
        )
        for (command in wrongBindings) rejects(SalesRule.PERMISSION_DENIED) {
            Orders.reassign(command, before, before.revision, f.previousOwner, f.recipient, f.evidence())
        }
        assertNull(missing.priceSourceReference)
        assertEquals(f.previousOwner, missing.ownerId)
        assertEquals(f.previousOwner, before.ownerId)
        // Reference presence is only a domain guard, not genuine/complete readable source or native authority.
    }

    @Test
    fun mra008And024LiveLegacyOfferBlocksManagerProposalAndOnlySeparateAcceptanceChangesOwner() = runTest {
        val f = DomainReassignmentInputs()
        val before = f.base().last().value
        val offered = Orders.offerHandoff(f.command(SecurityAction.ORDER_HANDOFF, f.previousOwner,
            operation = f.operation("OP-OWNER-OFFER-PENDING"), recordedAt = Instant.parse("2026-10-30T09:01:00Z")),
            before, before.revision, f.id("LEGACY-OFFER-PENDING"), f.recipient,
            CashierEvidence(f.id("EVIDENCE-PENDING-OFFER"), f.previousOwner, Instant.parse("2026-10-30T09:01:00Z"))).value
        assertEquals(f.previousOwner, offered.ownerId)
        assertEquals(f.r18, offered.revision)
        val live = assertNotNull(offered.handoff)
        assertEquals(f.id("LEGACY-OFFER-PENDING"), live.id)
        rejects(SalesRule.HANDOFF_CONFLICT) {
            Orders.reassign(f.command(revision = offered.revision), offered, offered.revision,
                f.previousOwner, f.recipient, f.evidence())
        }
        assertSame(live, offered.handoff)
        val acceptance = Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, f.recipient,
            revision = offered.revision, operation = f.operation("OP-OWNER-ACCEPT-PENDING"),
            recordedAt = Instant.parse("2026-10-30T09:06:00Z")), offered, offered.revision, live.id,
            CashierEvidence(f.id("EVIDENCE-PENDING-ACCEPT"), f.recipient, Instant.parse("2026-10-30T09:06:00Z")))
        assertEquals(f.recipient, acceptance.value.ownerId)
        assertNull(acceptance.value.handoff)
        assertEquals(f.creator, acceptance.value.originalCreatorId)
        assertSame(before.quote, acceptance.value.quote)
        assertEquals(live, acceptance.effects.filterIsInstance<HandoffAccepted>().single().handoff)
        assertTrue(acceptance.effects.none { it is OrderReassigned })
        // These are public pure transitions, not atomic indexes, actual legacy persistence or installed clients.
    }

    @Test
    fun mra025And026LaterPureProposalDoesNotRewriteTheOriginalReassignmentEvent() = runTest {
        val f = DomainReassignmentInputs()
        val before = f.base().last().value
        val first = Orders.reassign(f.command(), before, before.revision, f.previousOwner, f.recipient, f.evidence())
        val original = first.effects.filterIsInstance<OrderReassigned>().single()
        val later = Orders.reassign(f.command(revision = first.value.revision, reason = "A",
            operation = f.operation("OP-MRA-LATER")), first.value, first.value.revision, f.recipient,
            f.alternateRecipient, f.evidence().copy(evidenceId = f.id("EVIDENCE-LATER")))
        assertEquals(f.alternateRecipient, later.value.ownerId)
        assertNotEquals(first.command.operationId, later.command.operationId)
        f.assertOriginalEvent(original)
        assertEquals(f.recipient, first.value.ownerId)
        assertSame(before.quote, later.value.quote)
        assertSame(before.stockRequirements, later.value.stockRequirements)
        // No real later commit, replay registry, immutable store audit or audit-read permission is established.
    }
}

private inline fun rejects(rule: SalesRule, block: () -> Unit) {
    assertEquals(rule, assertFailsWith<SalesRuleViolation>(block = block).rule)
}

/** Lean controlled public-domain setup; its verifier/provider are not real employee authentication. */
private class DomainReassignmentInputs {
    private val coordinates = mapOf("O1" to 1, "C1" to 2, "B1" to 3, "MGR-B1" to 4, "EMP-C" to 5,
        "EMP-A" to 6, "EMP-B" to 7, "EMP-D" to 8, "SESSION-MGR-ORIGINAL" to 9, "DEVICE-MGR-B1" to 10,
        "ORDER-MAIN" to 11, "OP-MRA-001" to 12, "EVIDENCE-MGR-001" to 13, "LINE-SHIRT" to 14,
        "PORTION-SHIRT" to 15, "SKU-SHIRT-M-BLUE" to 16, "LINE-FABRIC" to 17, "PORTION-FABRIC" to 18,
        "SKU-FABRIC" to 19, "UNIT-EACH" to 20, "UNIT-METRE" to 21, "WAREHOUSE-B1" to 22,
        "TAX-POLICY" to 26, "QUOTE-MAIN" to 27, "OP-CREATE-ORIGINAL" to 29,
        "LEGACY-HANDOFF-COMPLETED" to 30, "OP-HANDOFF-OFFER-ORIGINAL" to 31,
        "OP-HANDOFF-ACCEPT-ORIGINAL" to 32, "ORDER-A-OTHER" to 33, "CONNECTION" to 36,
        "OP-MRA-LATER" to 37, "EVIDENCE-LATER" to 38, "PRICE-SOURCE-MAIN" to 40,
        "OP-OWNER-OFFER-PENDING" to 42, "LEGACY-OFFER-PENDING" to 43, "EVIDENCE-PENDING-OFFER" to 44,
        "EVIDENCE-HISTORICAL-OFFER" to 45, "EVIDENCE-HISTORICAL-ACCEPT" to 46, "OP-OWNER-ACCEPT-PENDING" to 47,
        "SESSION-EMP-C" to 48, "DEVICE-EMP-C" to 49, "SESSION-EMP-A" to 50, "DEVICE-EMP-A" to 51,
        "SESSION-B" to 52, "DEVICE-EMP-B" to 53, "EVIDENCE-PENDING-ACCEPT" to 56)
    fun id(semantic: String): DomainId = DomainId("4d520000-0000-4000-8000-" +
        coordinates.getValue(semantic).toString().padStart(12, '0'))
    fun operation(semantic: String = "OP-MRA-001"): OperationId = OperationId(id(semantic).value)
    val scope = BranchScope(id("O1"), id("C1"), id("B1"))
    val manager = id("MGR-B1")
    val creator = id("EMP-C")
    val previousOwner = id("EMP-A")
    val recipient = id("EMP-B")
    val alternateRecipient = id("EMP-D")
    val target = id("ORDER-MAIN")
    val r16 = Revision(1)
    val r17 = Revision(2)
    val r18 = Revision(3)
    val reason = "OWNER_ABSENT.cover-20261030"
    val attestedAt = Instant.parse("2026-10-30T09:00:00Z")
    val committedAt = Instant.parse("2026-10-30T09:05:00Z")
    fun evidence(actor: DomainId = manager) = CashierEvidence(id("EVIDENCE-MGR-001"), actor, attestedAt)

    suspend fun command(action: SecurityAction = SecurityAction.ORDER_REASSIGN, actor: DomainId = manager,
        target: DomainId = this.target, revision: Revision = r17, operation: OperationId = this.operation(),
        reason: String = this.reason, recordedAt: Instant = committedAt): SalesCommand {
        val (device, session) = when (actor) {
            manager -> id("DEVICE-MGR-B1") to id("SESSION-MGR-ORIGINAL")
            creator -> id("DEVICE-EMP-C") to id("SESSION-EMP-C")
            previousOwner -> id("DEVICE-EMP-A") to id("SESSION-EMP-A")
            recipient -> id("DEVICE-EMP-B") to id("SESSION-B")
            else -> error("No controlled authentication fixture for this actor")
        }
        val identity = VerifiedLocalIdentity(actor, scope, device, session,
            SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier by DenyAllPrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity = identity
        }
        val credentials = LocalGatewayCredentials(id("CONNECTION"), OpaqueCredential(ByteArray(16) { 7 }))
        try {
            val principal = assertNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(credentials))
            // This digest tests binding only; it is not a Master-computed canonical command digest.
            val digest = PayloadDigest("1".repeat(64))
            val requirement = salesAuthorizationRequirement(action, scope, target, revision, operation, digest, reason, actor)
            val snapshot = AuthorizationSnapshot(actor, principal.kind, scope, identity.sessionId, identity.sessionGeneration,
                identity.deviceId, identity.admissionRevision, Revision(1), true, true, true, identity.purpose,
                listOf(PermissionGrant(requirement.permission, scope, GrantEffect.ALLOW, namedSubjectId = actor,
                    targetIds = setOf(target))))
            val policy = AuthorizationPolicy(CurrentAuthorizationProvider { snapshot })
            val authorization = assertIs<AuthorizationDecision.Allowed>(policy.authorize(principal, requirement)).authorization
            return SalesCommand(operation, authorization, recordedAt, digest, reason)
        } finally { credentials.bearer.destroy() }
    }

    suspend fun base(withPriceReference: Boolean = true): List<SalesTransition<OwnedOrder>> {
        val quantities = listOf(Quantity(id("UNIT-EACH"), 2, 0), Quantity(id("UNIT-METRE"), 15, 1))
        val tax = TaxSnapshot(id("TAX-POLICY"), Revision(5), "SYNTHETIC-ILLUSTRATIVE-14", PercentageRate.parse("14"))
        val quote = OperationalPricingEngine.calculate(PricingRequest(id("QUOTE-MAIN"), Revision(5), Currency.EGP, listOf(
            ResolvedPriceLine(id("LINE-SHIRT"), id("PORTION-SHIRT"), Revision(5), quantities[0],
                UnitPrice.parse(Currency.EGP, "100"), TaxInclusion.EXCLUSIVE, tax),
            ResolvedPriceLine(id("LINE-FABRIC"), id("PORTION-FABRIC"), Revision(5), quantities[1],
                UnitPrice.parse(Currency.EGP, "40"), TaxInclusion.EXCLUSIVE, tax))))
        val demand = RetailStockRequirements.normalized(listOf(
            RetailStockDemandV2(id("PORTION-SHIRT"), id("SKU-SHIRT-M-BLUE"), id("WAREHOUSE-B1"), quantities[0]),
            RetailStockDemandV2(id("PORTION-FABRIC"), id("SKU-FABRIC"), id("WAREHOUSE-B1"), quantities[1])))
        // Reference data is not proof of retained source provenance or readable history.
        val reference = if (withPriceReference) SalesPriceSourceReference(id("PRICE-SOURCE-MAIN"), PayloadDigest("2".repeat(64))) else null
        val created = Orders.create(command(SecurityAction.ORDER_CREATE, creator, revision = Revision(0),
            operation = operation("OP-CREATE-ORIGINAL"), recordedAt = Instant.parse("2026-10-30T08:00:00Z")),
            scope, target, quote, demand, reference)
        val offered = Orders.offerHandoff(command(SecurityAction.ORDER_HANDOFF, creator, revision = created.value.revision,
            operation = operation("OP-HANDOFF-OFFER-ORIGINAL"), recordedAt = Instant.parse("2026-10-30T08:10:00Z")),
            created.value, created.value.revision, id("LEGACY-HANDOFF-COMPLETED"), previousOwner,
            CashierEvidence(id("EVIDENCE-HISTORICAL-OFFER"), creator, Instant.parse("2026-10-30T08:10:00Z")))
        val accepted = Orders.acceptHandoff(command(SecurityAction.ORDER_HANDOFF, previousOwner, revision = offered.value.revision,
            operation = operation("OP-HANDOFF-ACCEPT-ORIGINAL"), recordedAt = Instant.parse("2026-10-30T08:20:00Z")),
            offered.value, offered.value.revision, id("LEGACY-HANDOFF-COMPLETED"),
            CashierEvidence(id("EVIDENCE-HISTORICAL-ACCEPT"), previousOwner, Instant.parse("2026-10-30T08:20:00Z")))
        return listOf(created, offered, accepted)
    }

    fun assertFrozenPricing(quote: PricingSnapshot) {
        assertEquals(Money(Currency.EGP, 29640), quote.payable)
        assertEquals(Money(Currency.EGP, 0), quote.advertisedDiscount)
        assertTrue(quote.charges.isEmpty() && quote.discounts.isEmpty())
        assertEquals(listOf(Money(Currency.EGP, 22800), Money(Currency.EGP, 6840)), quote.lines.map { it.payable })
        assertEquals(listOf(UnitPrice.parse(Currency.EGP, "100"), UnitPrice.parse(Currency.EGP, "40")), quote.lines.map { it.unitPrice })
        for ((portion, net, tax) in listOf(Triple("PORTION-SHIRT", 20000L, 2800L), Triple("PORTION-FABRIC", 6000L, 840L))) {
            val components = quote.components.filter { it.portionId == id(portion) }
            assertEquals(Money(Currency.EGP, net), components.single { it.kind == PriceComponentKind.ITEM_NET }.posted)
            assertEquals(Money(Currency.EGP, tax), components.single { it.kind == PriceComponentKind.ITEM_TAX }.posted)
        }
    }

    fun assertOriginalEvent(event: OrderReassigned) {
        assertEquals(scope, event.scope)
        assertEquals(operation(), event.operationId)
        assertEquals(target, event.orderId)
        assertEquals(r17, event.beforeRevision)
        assertEquals(r18, event.afterRevision)
        assertEquals(manager, event.managerId)
        assertEquals(previousOwner, event.previousOwnerId)
        assertEquals(recipient, event.recipientId)
        assertEquals(reason, event.reasonCode)
        assertEquals(evidence(), event.evidence)
        assertEquals(committedAt, event.recordedAt)
    }
}
