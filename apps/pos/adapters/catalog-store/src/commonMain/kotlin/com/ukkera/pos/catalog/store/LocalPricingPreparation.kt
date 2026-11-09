package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Producer identity and audit DATA only. These cannot construct a current grant,
 * historical CommandReadView, employee session or native-retention instruction. */
internal data class LocalPricingIdentity(
    val scope: BranchScope,
    val actorId: DomainId,
    val operationId: OperationId,
    val semanticDigest: PayloadDigest,
) {
    companion object {
        fun current(context: CommandContext): LocalPricingIdentity {
            check(context.key == LocalPricingProfile.COMMAND)
            return LocalPricingIdentity(context.scope, context.originalActorId, context.operationId, context.semanticDigest)
        }
        fun original(command: RuntimeHistoricalCommand): LocalPricingIdentity =
            LocalPricingIdentity(command.scope, command.actorId, command.operationId, command.semanticDigest)
    }
}

internal data class LocalPricingActorAudit(val actorId: DomainId, val policyRevision: Revision, val actionApprovalId: DomainId?)

/** One decision for both the actual writer and the exact original BEFORE observations.
 * The live wrapper alone consumes AuthorizedAction; the historical caller must first
 * compare the entire original requirement using the active core-owned command. */
internal class LocalPricingPreparation(
    private val identity: LocalPricingIdentity,
    val requirement: AuthorizationRequirement,
    val resources: Set<RecoveryResourceKey>,
    val sourceUnavailable: Boolean = false,
    val allowsActionApproval: Boolean = true,
    private val decide: (LocalPricingActorAudit, UtcInstant) -> ProposedOutcome,
) {
    fun outcome(actor: LocalPricingActorAudit, at: UtcInstant): ProposedOutcome {
        check(!sourceUnavailable && actor.actorId == identity.actorId)
        return decide(actor, at)
    }

    fun currentMutation(): PreparedMutation = object : PreparedMutation() {
        override val requirement: AuthorizationRequirement = this@LocalPricingPreparation.requirement
        override val affectedResources: Set<RecoveryResourceKey> = resources
        override val admissionRestriction: PreparedMutationAdmissionRestriction? =
            if (sourceUnavailable) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
        override val allowsActionApproval: Boolean = this@LocalPricingPreparation.allowsActionApproval
        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
            check(authorization.isBoundTo(requirement, identity.actorId))
            return outcome(LocalPricingActorAudit(authorization.actorId, authorization.policyRevision, authorization.approvalId), recordedAt)
        }
    }
}

internal suspend fun prepareLocalPricing(
    request: LocalPricingRequestV1, sources: LocalPricingSourceReader, identity: LocalPricingIdentity,
): LocalPricingPreparation {
    check(sources.scope == identity.scope)
    return when (request) {
        is LocalPricingRequestV1.ReceivePolicy -> preparePricingReceipt(request, sources, identity)
        is LocalPricingRequestV1.ActivatePolicy -> preparePricingActivation(request, sources, identity)
        is LocalPricingRequestV1.ChangeBranchPrice -> preparePricingBranchChange(request, sources, identity)
        is LocalPricingRequestV1.IndexRetainedCompanyHistory -> preparePricingIndex(request, sources, identity)
    }
}

private suspend fun preparePricingReceipt(request: LocalPricingRequestV1.ReceivePolicy,
    sources: LocalPricingSourceReader, identity: LocalPricingIdentity): LocalPricingPreparation {
    val signed = request.publication
    val definition = signed.definition
    val existing = sources.policyPublication(definition.publicationId)
    val authentication = sources.authenticateCandidate(signed)
    val required = AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), identity.scope,
        ActionBinding(definition.publicationId, Revision(0), identity.semanticDigest, identity.operationId, "catalog.receive_price_policy"))
    val resources = setOf(priceResource(identity.actorId), priceResource(definition.publicationId), priceResource(definition.entryId),
        priceResource(definition.skuId), priceResource(definition.ruleId), priceResource(signed.signingKeyId))
    return LocalPricingPreparation(identity, required, resources, sourceUnavailable = authentication.sourceUnavailable) { actor, at ->
        if (!authentication.authenticated) pricingReject("catalog.company_policy_required")
        else if (existing != null) pricingReject("catalog.policy_publication_already_received")
        else {
            val record = PricePolicyPublicationV1(identity.scope, signed, identity.operationId, actor.actorId, actor.policyRevision, at)
            ProposedOutcome.Apply(pricingResult("catalog.price_policy_received", definition.publicationId, Revision(1)), listOf(
                AggregateChange(LocalPricingProfile.key(LocalPricingProfile.POLICY_PUBLICATION, definition.publicationId), null,
                    LocalPricingProfile.schema(LocalPricingProfile.POLICY_PUBLICATION), encodeValue(record, PricePolicyPublicationV1.serializer()))))
        }
    }
}

private suspend fun preparePricingActivation(request: LocalPricingRequestV1.ActivatePolicy,
    sources: LocalPricingSourceReader, identity: LocalPricingIdentity): LocalPricingPreparation {
    val received = sources.policyPublication(request.publicationId)
    val definition = received?.second?.signed?.definition
    val currentSku = definition?.let { sources.sku(LocalConfigurationProfile.SKU, it.skuId) }
    val sku = currentSku?.second?.signedSku()?.definition
    val price = definition?.let { value -> sku?.prices?.singleOrNull { it.ruleId == value.ruleId } }
    val previous = definition?.let { sources.policy(it.skuId, it.ruleId) }
    val previousBranch = definition?.let { sources.branch(it.skuId, it.ruleId) }
    val index = sources.index()
    val append = definition?.let { sources.append(it.skuId, identity) }
    val cursor = sources.cursor()
    val contextId = definition?.let { priceContextId(identity.scope.company(), it.skuId, it.ruleId) } ?: request.publicationId
    val required = AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), identity.scope,
        ActionBinding(contextId, previous?.row?.revision ?: Revision(0), identity.semanticDigest, identity.operationId, "catalog.activate_price_policy"))
    val resources = setOf(priceResource(identity.actorId), priceResource(request.publicationId), priceResource(contextId), priceResource(identity.scope.branchId)) +
        previous?.resources.orEmpty() + previousBranch?.resources.orEmpty() + append?.resources.orEmpty() + definition?.let { value -> setOf(
            priceResource(value.entryId), priceResource(value.ruleId), priceResource(value.skuId), priceResource(checkNotNull(received).second.signed.signingKeyId))
        }.orEmpty() + received?.second?.let { setOf(priceResource(it.receivedBy), priceResource(it.signed.signingKeyId)) }.orEmpty() +
        currentSku?.second?.let { companySourceResources(it, listOfNotNull(price)) }.orEmpty()
    return LocalPricingPreparation(identity, required, resources) { actor, at ->
        when {
            definition == null || sku == null || price == null -> pricingReject("catalog.current_price_and_received_policy_required")
            index?.second?.state != PriceHistoryIndexState.COMPLETE -> pricingReject("catalog.price_history_index_required")
            previous?.row?.revision != request.expectedPolicyStorageRevision -> pricingReject("catalog.stale_price_policy")
            previous != null && (previous.definition.entryId != definition.entryId || definition.revision <= previous.definition.revision) ->
                pricingReject("catalog.price_policy_version_conflict")
            definition.authorizationReferenceQuantity.unitId != sku.baseUnitId || definition.authorizationReferenceQuantity.scale != sku.quantityScale ->
                pricingReject("catalog.price_policy_basis_unit_mismatch")
            at < definition.effectiveFrom -> pricingReject("catalog.price_policy_not_yet_effective")
            definition.effectiveUntil?.let { at >= it } == true -> pricingReject("catalog.price_policy_expired")
            append?.hasCapacity != true || previous?.row?.revision?.value == Long.MAX_VALUE || cursor == null || cursor.first.revision.value == Long.MAX_VALUE ->
                pricingReject("catalog.price_history_capacity_required")
            else -> {
                val activation = PricePolicyActivationV1(identity.scope, sku.skuId, price.ruleId, definition.publicationId,
                    sha256(LocalPricingCodec.canonicalPolicy(definition)), DomainId(identity.operationId.value), actor.actorId, at)
                val changes = listOf(AggregateChange(LocalPricingProfile.key(LocalPricingProfile.POLICY_CURRENT, contextId), previous?.row?.revision,
                    LocalPricingProfile.schema(LocalPricingProfile.POLICY_CURRENT), encodeValue(activation, PricePolicyActivationV1.serializer())),
                    incrementPriceCursor(cursor, actor, at)) + checkNotNull(append).changes(PriceHistoryEventKind.POLICY_ACTIVATION,
                    null, definition.publicationId, null, identity, actor, at)
                ProposedOutcome.Apply(pricingResult("catalog.price_policy_activated", contextId, previous?.row?.revision?.next() ?: Revision(1)),
                    changes, mustCommitBefore = definition.effectiveUntil)
            }
        }
    }
}

private suspend fun preparePricingBranchChange(request: LocalPricingRequestV1.ChangeBranchPrice,
    sources: LocalPricingSourceReader, identity: LocalPricingIdentity): LocalPricingPreparation {
    val source = sources.sku(LocalConfigurationProfile.SKU, request.skuId)
    val sku = source?.second?.signedSku()?.definition
    val price = sku?.prices?.singleOrNull { it.ruleId == request.ruleId }
    val high = sources.high(request.skuId, request.ruleId)
    val policy = sources.policy(request.skuId, request.ruleId)
    val previous = sources.branch(request.skuId, request.ruleId)
    val index = sources.index()
    val cursor = sources.cursor()
    val append = sources.append(request.skuId, identity)
    val id = priceContextId(identity.scope.company(), request.skuId, request.ruleId)
    var amountCode: String? = null
    val limitUse = if (sku == null || price == null || policy == null) null else try {
        val quantity = policy.definition.authorizationReferenceQuantity
        if (quantity.unitId != sku.baseUnitId || quantity.scale != sku.quantityScale) {
            amountCode = "catalog.price_policy_basis_unit_mismatch"
            null
        } else {
            val base = (price.unitPrice.amount.toFraction() * quantity.toFraction()).toScaledLongExact(Currency.EGP.fractionDigits)
            val next = ((request.unitPrice ?: price.unitPrice).amount.toFraction() * quantity.toFraction()).toScaledLongExact(Currency.EGP.fractionDigits)
            if (base <= 0) { amountCode = "catalog.zero_price_override_basis_unsupported"; null }
            else LimitUse.Proportional(Money(Currency.EGP, if (next >= base) next - base else base - next), Money(Currency.EGP, base))
        }
    } catch (_: ArithmeticException) {
        // Authority is exact. Posting rounding cannot turn a sub-minor deviation into
        // a smaller permitted grant or make an unrepresentable basis usable.
        amountCode = "catalog.price_override_basis_unrepresentable"
        null
    }
    val required = AuthorizationRequirement(Permission(SecurityAction.BRANCH_PRICE_EDIT, PermissionVerb.PERFORM), identity.scope,
        ActionBinding(id, previous?.row?.revision ?: Revision(0), identity.semanticDigest, identity.operationId, request.reasonCode),
        limitUse ?: LimitUse.NotApplicable)
    val resources = setOf(priceResource(identity.actorId), priceResource(id), priceResource(request.skuId),
        priceResource(request.ruleId), priceResource(identity.scope.branchId)) + append.resources + policy?.resources.orEmpty() + previous?.resources.orEmpty() +
        source?.second?.let { companySourceResources(it, listOfNotNull(price)) }.orEmpty() + high?.resources.orEmpty()
    return LocalPricingPreparation(identity, required, resources, allowsActionApproval = false) { actor, at ->
        if (actor.actionApprovalId != null) pricingReject("catalog.direct_price_authority_required")
        else if (sku == null || price == null || high == null || policy == null) pricingReject("catalog.price_source_required")
        else if (index?.second?.state != PriceHistoryIndexState.COMPLETE) pricingReject("catalog.price_history_index_required")
        else {
            check(high.value.price == price) { "Current company price disagrees with retained high-water" }
            when {
                !sku.active || !sku.canSell -> pricingReject("catalog.sku_unavailable")
                price.revision != request.expectedCompanyPriceRevision || policy.definition.revision != request.expectedPolicyEntryRevision ||
                    previous?.row?.revision != request.expectedOverrideStorageRevision -> pricingReject("catalog.stale_price_context")
                at < price.effectiveFrom || price.effectiveUntil?.let { at >= it } == true -> pricingReject("catalog.price_context_not_effective")
                at < policy.definition.effectiveFrom || policy.definition.effectiveUntil?.let { at >= it } == true -> pricingReject("catalog.price_policy_not_effective")
                request.unitPrice != null && !policy.definition.branchOverridesEnabled -> pricingReject("catalog.branch_prices_disabled")
                request.unitPrice == null && previous?.change?.unitPrice == null -> pricingReject("catalog.no_branch_price_to_reset")
                request.effectiveUntil?.let { at >= it } == true -> pricingReject("catalog.branch_price_expired")
                amountCode != null || limitUse == null -> pricingReject(amountCode ?: "catalog.price_override_basis_required")
                !append.hasCapacity || previous?.row?.revision?.value == Long.MAX_VALUE || cursor == null || cursor.first.revision.value == Long.MAX_VALUE ->
                    pricingReject("catalog.price_history_capacity_required")
                else -> {
                    val revision = previous?.row?.revision?.next() ?: Revision(1)
                    val change = PriceBranchChangeV1(request.ruleId, revision, price.revision, localPriceDigest(price), sku.publicationId,
                        policy.definition.entryId, policy.definition.revision, policy.definition.publicationId, policy.value.definitionDigest,
                        request.unitPrice, policy.definition.authorizationReferenceQuantity, limitUse.baseAmount, limitUse.amount,
                        at, request.effectiveUntil, request.reasonCode)
                    val history = append.changes(PriceHistoryEventKind.BRANCH_CHANGE, null, null, change, identity, actor, at)
                    val event = history.single { it.key.kind == LocalPricingProfile.HISTORY_EVENT }
                    val head = BranchPriceHeadV1(identity.scope, request.skuId, request.ruleId, event.key.id, sha256(event.payload.copyBytes()), revision)
                    val changes = history + listOf(AggregateChange(LocalPricingProfile.key(LocalPricingProfile.OVERRIDE_CURRENT, id), previous?.row?.revision,
                        LocalPricingProfile.schema(LocalPricingProfile.OVERRIDE_CURRENT), encodeValue(head, BranchPriceHeadV1.serializer())),
                        incrementPriceCursor(cursor, actor, at))
                    val deadline = listOfNotNull(price.effectiveUntil, policy.definition.effectiveUntil, request.effectiveUntil).minOrNull()
                    ProposedOutcome.Apply(pricingResult(if (request.unitPrice == null) "catalog.branch_price_reset" else "catalog.branch_price_changed", id, revision),
                        changes, mustCommitBefore = deadline)
                }
            }
        }
    }
}

/** The initial refusal controls whether production reads a publication page. Later
 * conflicts/capacity retain the existing writer precedence and complete source reads. */
private fun pricingIndexInitialRejection(request: LocalPricingRequestV1.IndexRetainedCompanyHistory,
    index: Pair<StoredAggregate, PriceHistoryIndexV1>?, cursor: StoredAggregate?): String? = when {
    index?.first?.revision != request.expectedIndexStorageRevision -> "catalog.stale_history_index"
    index?.second?.state == PriceHistoryIndexState.COMPLETE -> "catalog.price_history_already_indexed"
    index != null && (index.second.capturedCursorRevision != cursor?.revision || index.second.capturedCursorDigest != cursor?.digest) ->
        "catalog.price_history_source_changed"
    index?.first?.revision?.value == Long.MAX_VALUE -> "catalog.price_history_capacity_required"
    else -> null
}

private suspend fun preparePricingIndex(request: LocalPricingRequestV1.IndexRetainedCompanyHistory,
    sources: LocalPricingSourceReader, identity: LocalPricingIdentity): LocalPricingPreparation {
    val index = sources.index()
    val cursor = sources.cursor()?.first
    val before = index?.second
    var rejection = pricingIndexInitialRejection(request, index, cursor)
    val page = if (rejection == null) sources.publicationPage(before?.afterPublicationId) else null
    val selected = page?.key?.let { sources.sku(it.kind, it.id) ?: error("Pricing backfill lost its selected original publication") }
    val definition = selected?.second?.signedSku()?.definition
    val append = definition?.let { sources.append(it.skuId, identity) }
    if (append?.hasCapacity == false || before?.indexedPublications?.value == Long.MAX_VALUE) rejection = "catalog.price_history_capacity_required"
    val resources = mutableSetOf(priceResource(identity.actorId), priceResource(identity.scope.branchId))
    resources += append?.resources.orEmpty()
    val changes = mutableListOf<AggregateChange>()
    val claims = mutableListOf<ClaimChange>()
    if (definition != null) {
        val active = sources.sku(LocalConfigurationProfile.SKU, definition.skuId) ?: error("Retained company publication lost its original SKU")
        val activeDefinition = active.second.signedSku().definition
        resources += companySourceResources(checkNotNull(selected).second, definition.prices)
        val selectedRuleIds = definition.prices.map { it.ruleId }.toSet()
        resources += companySourceResources(active.second, activeDefinition.prices.filter { it.ruleId in selectedRuleIds })
        for (price in definition.prices) {
            val version = sources.version(definition.skuId, definition.publicationId, price)
            resources += version.resources
            version.acquisition?.let { claims += it }
            if (version.conflict) rejection = "catalog.legacy_price_history_conflict"
            val id = priceContextId(identity.scope.company(), definition.skuId, price.ruleId)
            val high = sources.high(definition.skuId, price.ruleId)
            resources += priceResource(id)
            resources += high?.resources.orEmpty()
            val current = activeDefinition.prices.singleOrNull { it.ruleId == price.ruleId }
            if (current != null && (current.revision < price.revision || current.revision == price.revision && current != price))
                rejection = "catalog.legacy_price_history_conflict"
            if (high != null && high.value.price.revision == price.revision && high.value.price != price)
                rejection = "catalog.legacy_price_history_conflict"
            if (high == null || price.revision > high.value.price.revision) {
                if (high?.row?.revision?.value == Long.MAX_VALUE) rejection = "catalog.price_history_capacity_required"
                changes += AggregateChange(LocalPricingProfile.key(LocalPricingProfile.COMPANY_HIGH_WATER, id), high?.row?.revision,
                    LocalPricingProfile.schema(LocalPricingProfile.COMPANY_HIGH_WATER), encodeValue(
                        PriceHighWaterV1(identity.scope, definition.skuId, price.ruleId, price, definition.publicationId), PriceHighWaterV1.serializer()))
            }
        }
    }
    val required = AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), identity.scope,
        ActionBinding(identity.scope.branchId, index?.first?.revision ?: Revision(0), identity.semanticDigest, identity.operationId, "catalog.index_price_history"))
    return LocalPricingPreparation(identity, required, resources.toSet()) { actor, at ->
        val refused = rejection
        if (refused != null) pricingReject(refused)
        else {
            val next = PriceHistoryIndexV1(identity.scope, if (page?.hasMore == true) PriceHistoryIndexState.INDEXING else PriceHistoryIndexState.COMPLETE,
                before?.capturedCursorRevision ?: cursor?.revision, before?.capturedCursorDigest ?: cursor?.digest,
                selected?.first?.key?.id ?: before?.afterPublicationId,
                (before?.indexedPublications ?: Revision(0)).let { if (selected == null) it else it.next() })
            val allChanges = changes + listOf(AggregateChange(LocalPricingProfile.key(LocalPricingProfile.HISTORY_INDEX, identity.scope.branchId),
                index?.first?.revision, LocalPricingProfile.schema(LocalPricingProfile.HISTORY_INDEX), encodeValue(next, PriceHistoryIndexV1.serializer()))) +
                if (append == null) emptyList() else append.changes(PriceHistoryEventKind.INDEXED_COMPANY_PUBLICATION,
                    checkNotNull(definition).publicationId, null, null, identity, actor, at)
            ProposedOutcome.Apply(pricingResult(if (next.state == PriceHistoryIndexState.COMPLETE) "catalog.price_history_indexed"
                else "catalog.price_history_index_progress", identity.scope.branchId, index?.first?.revision?.next() ?: Revision(1)), allChanges, claims)
        }
    }
}
