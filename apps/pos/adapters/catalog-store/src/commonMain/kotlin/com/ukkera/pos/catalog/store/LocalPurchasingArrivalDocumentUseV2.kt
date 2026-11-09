package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Additive, immutable action-use facts. Neither this format nor its digest grants authority. */
internal object LocalPurchasingArrivalDocumentUseProfileV2 {
    const val KIND: String = "local.purchasing.arrival-document-use-v2"
    const val SCHEMA: String = "$KIND/v2"
    const val OPERATION_USE_CLAIM: String = "$KIND.operation-use"
    const val MAX_ROW_BYTES: Int = LocalUnpricedArrivalProfileV2.MAX_ROW_BYTES
}

@Serializable
internal data class PurchasingArrivalDocumentUseProducerV2(
    val operationId: OperationId, val actorId: DomainId,
    val commandName: String, val commandVersion: Int, val semanticDigest: PayloadDigest,
    val principalKind: String, val authenticationId: DomainId, val generation: SessionGeneration,
    val approvalId: DomainId?, val approverId: DomainId?, val approverPolicyRevision: Revision?,
) {
    val command: CommandKey get() = CommandKey(commandName, commandVersion)
}

@Serializable
internal data class PurchasingArrivalDocumentUseBindingV2(
    val documentId: DomainId, val revision: Revision, val payloadDigest: PayloadDigest,
    val operationId: OperationId, val reasonCode: String,
)

/** Complete admitted requirement; no executable requirement or capability is decoded. */
@Serializable
internal data class PurchasingArrivalDocumentUseRequirementV2(
    val action: String, val verb: String, val scope: BranchScope,
    val binding: PurchasingArrivalDocumentUseBindingV2, val limitUse: String, val owner: String,
)

@Serializable
internal data class PurchasingArrivalDocumentUseRetentionV2(
    val classId: DomainId, val revision: Revision,
    @Serializable(with = MinorUnitsSerializer::class) val minimumRetentionSeconds: Long,
    val obligations: List<String>,
)

/**
 * Historical facts only. The use digest is deliberately absent: this row is encoded first,
 * then its exact digest is placed in the companion custody evidence. Minimum retention is
 * not a disposal deadline; unresolved uses and longer obligations remain the owner's concern.
 */
@Serializable
internal data class PurchasingArrivalDocumentUseRecordV2(
    val scope: BranchScope, val kind: String, val id: DomainId, val revision: Revision,
    val producer: PurchasingArrivalDocumentUseProducerV2, val recordedAt: UtcInstant,
    val requesterPolicyRevision: Revision, val requirement: PurchasingArrivalDocumentUseRequirementV2,
    val selection: LocalArrivalEvidenceSelectionV2, val policyWireHex: String,
    val products: List<LocalArrivalProductReferenceV2>,
    val originalDocuments: List<LocalArrivalDocumentSelectionV2>,
    val sources: List<LocalArrivalSourceSelectionV2>, val retention: PurchasingArrivalDocumentUseRetentionV2,
) {
    override fun toString(): String = "PurchasingArrivalDocumentUseV2([HISTORICAL, REDACTED])"
}

/**
 * Pure completion after actual current/native sources and the complete rule have been checked.
 * A zero-file selection still produces one immutable row and one permanent operation-use claim.
 * The caller must combine these effects with custody in the same protected atomic Apply.
 */
internal fun purchasingArrivalDocumentaryEffectsV2(
    context: CommandContext,
    requirement: AuthorizationRequirement,
    expectedRequesterPolicyRevision: Revision,
    authorization: AuthorizedAction,
    recordedAt: UtcInstant,
    selection: LocalArrivalEvidenceSelectionV2,
    policy: PurchasingSignedPolicyV1,
    products: List<LocalArrivalProductReferenceV2>,
    originalDocuments: List<LocalArrivalDocumentSelectionV2>,
    sources: List<LocalStockSourceStamp>,
): LocalArrivalDocumentaryEffectsV2 {
    check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
        authorization.isBoundTo(requirement, context.originalActorId) &&
        authorization.policyRevision == expectedRequesterPolicyRevision)
    val business = context.businessAttribution(authorization, requirement)
    return purchasingArrivalDocumentaryProjectionV2(context.scope, context.operationId, context.originalActorId,
        context.key, context.semanticDigest, business.authenticationId, business.generation,
        requirement, expectedRequesterPolicyRevision, recordedAt, selection, policy, products, originalDocuments, sources,
        authorization.approvalId, authorization.approverId, authorization.approverPolicyRevision)
}

/** Literal prospective content from original native identity DATA. This does not construct
 * an employee grant, execute a command, or authorize documentary use. Recovery compares
 * these bytes to the original proposal (or its independently derived capacity refusal). */
internal fun purchasingArrivalDocumentaryProjectionV2(
    scope: BranchScope, operationId: OperationId, actorId: DomainId, command: CommandKey,
    digest: PayloadDigest, authenticationId: DomainId, generation: SessionGeneration,
    requirement: AuthorizationRequirement, expectedRequesterPolicyRevision: Revision,
    recordedAt: UtcInstant, selection: LocalArrivalEvidenceSelectionV2, policy: PurchasingSignedPolicyV1,
    products: List<LocalArrivalProductReferenceV2>, originalDocuments: List<LocalArrivalDocumentSelectionV2>,
    sources: List<LocalStockSourceStamp>,
    approvalId: DomainId? = null, approverId: DomainId? = null, approverPolicyRevision: Revision? = null,
): LocalArrivalDocumentaryEffectsV2 {
    val requirementSnapshot = purchasingArrivalUseRequirementV2(requirement, scope,
        operationId, digest)
    val signedBytes = LocalCompanyPolicyWireV1.encodeSignedPolicy(policy)
    val policyHex = try {
        purchasingArrivalUseCapacityV2(signedBytes.size in 1..LocalPurchasingPolicyProfileV1.MAX_POLICY_WIRE_BYTES)
        purchasingArrivalUseHexV2(signedBytes)
    } finally { signedBytes.fill(0) }
    val frozenSelection = selection.copy(documents = purchasingArrivalUseFrozenV2(selection.documents))
    val selectedRetention = policy.body.retentionClasses.singleOrNull {
        it.classId == selection.retention.classId && it.revision == selection.retention.revision
    } ?: error("Arrival use retention selection unavailable")
    val record = PurchasingArrivalDocumentUseRecordV2(scope, LocalPurchasingArrivalDocumentUseProfileV2.KIND,
        DomainId(operationId.value), Revision(1),
        PurchasingArrivalDocumentUseProducerV2(operationId, actorId, command.name,
            command.version, digest, PrincipalKind.LOCAL_EMPLOYEE.name,
            authenticationId, generation, approvalId, approverId, approverPolicyRevision), recordedAt, expectedRequesterPolicyRevision,
        requirementSnapshot, frozenSelection, policyHex, purchasingArrivalUseFrozenV2(products),
        purchasingArrivalUseFrozenV2(originalDocuments),
        purchasingArrivalUseFrozenV2(sources.map(LocalArrivalSourceSelectionV2::from)),
        purchasingArrivalUseRetentionV2(selectedRetention))
    purchasingArrivalUseRequireRecordV2(record)
    return purchasingArrivalUseEffectsV2(record, purchasingArrivalUseEncodeV2(record))
}

/**
 * Validates the exact original documentary AFTER and claim, not historical authority. Never
 * reconstructs AuthorizedAction, uses today's sources, or treats a decoded signature as valid.
 * Native original source membership and the whole custody/use effect comparison are separate.
 */
internal fun retainedPurchasingArrivalDocumentaryEffectsV2(
    original: RetainedCommandEvidence,
    evidence: LocalArrivalPurchasingEvidenceV2,
    requirement: AuthorizationRequirement,
    expectedRequesterPolicyRevision: Revision,
): LocalArrivalDocumentaryEffectsV2 {
    check(original.committed && original.nativeOriginalProvenance == null && original.jobs.isEmpty())
    check(LocalUnpricedArrivalCommandsV2.normalize(original.command, original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
        purchasingArrivalUseDigestV2(original.result) == original.resultHash)
    check(original.aggregateWrites.map { it.key }.distinct().size == original.aggregateWrites.size &&
        original.claimChanges.map { it.key }.distinct().size == original.claimChanges.size)
    val id = DomainId(original.operationId.value)
    val key = AggregateKey(LocalPurchasingArrivalDocumentUseProfileV2.KIND, id)
    val writes = original.aggregateWrites.filter { it.key.kind == LocalPurchasingArrivalDocumentUseProfileV2.KIND }
    check(writes.size == 1)
    val write = writes.single()
    check(write.key == key && write.storageGeneration == Revision(1) &&
        write.schema == LocalPurchasingArrivalDocumentUseProfileV2.SCHEMA &&
        purchasingArrivalUseDigestV2(write.payload) == write.payloadDigest)
    val record = purchasingDocumentDecode(write.payload, PurchasingArrivalDocumentUseRecordV2.serializer(),
        LocalPurchasingArrivalDocumentUseProfileV2.MAX_ROW_BYTES)
    purchasingArrivalUseRequireRecordV2(record)
    check(purchasingArrivalUseEncodeV2(record) == write.payload && record.scope == original.scope &&
        record.id == id && record.recordedAt == original.recordedAt &&
        record.requesterPolicyRevision == expectedRequesterPolicyRevision &&
        record.requesterPolicyRevision == original.policyRevision)
    val producer = record.producer
    check(producer.operationId == original.operationId && producer.actorId == original.originalActorId &&
        producer.command == original.command && producer.semanticDigest == original.semanticDigest &&
        producer.authenticationId == original.authenticationId)
    check(record.requirement == purchasingArrivalUseRequirementV2(requirement, original.scope,
        original.operationId, original.semanticDigest))
    purchasingArrivalUseRequireCommandV2(original, record, requirement, expectedRequesterPolicyRevision)
    val effects = purchasingArrivalUseEffectsV2(record, write.payload)
    check(effects.evidence == evidence && evidence.documentaryUse ==
        LocalArrivalSourceSelectionV2(key.kind, id, Revision(1), write.payloadDigest))
    val claims = original.claimChanges.filter {
        it.key.namespace == LocalPurchasingArrivalDocumentUseProfileV2.OPERATION_USE_CLAIM
    }
    val expectedClaim = effects.claims.single()
    check(claims.size == 1 && claims.single().action == RetainedClaimAction.ACQUIRE &&
        claims.single().key == expectedClaim.key && claims.single().ownerId == id)
    return effects
}

private fun purchasingArrivalUseRequirementV2(
    requirement: AuthorizationRequirement, scope: BranchScope, operationId: OperationId, digest: PayloadDigest,
): PurchasingArrivalDocumentUseRequirementV2 {
    check(requirement.permission == Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM) &&
        requirement.scope == scope && requirement.limitUse == LimitUse.NotApplicable &&
        requirement.owner == OwnerConstraint.Unrestricted)
    val binding = requireNotNull(requirement.binding)
    check(binding.operationId == operationId && binding.payloadDigest == digest)
    return PurchasingArrivalDocumentUseRequirementV2(requirement.permission.action.name,
        requirement.permission.verb.name, scope, PurchasingArrivalDocumentUseBindingV2(binding.documentId,
            binding.revision, binding.payloadDigest, operationId, binding.reasonCode), "NOT_APPLICABLE", "UNRESTRICTED")
}

/** Inert content consistency, including the entire selected retention class. No source admission. */
private fun purchasingArrivalUseRequireRecordV2(record: PurchasingArrivalDocumentUseRecordV2) {
    val producer = record.producer
    val action = purchasingArrivalUseActionV2(producer.command)
    check(record.kind == LocalPurchasingArrivalDocumentUseProfileV2.KIND &&
        record.id == DomainId(producer.operationId.value) && record.revision == Revision(1) &&
        producer.principalKind == PrincipalKind.LOCAL_EMPLOYEE.name)
    val requirement = record.requirement
    check(requirement.action == SecurityAction.INVENTORY_ADJUST.name && requirement.verb == PermissionVerb.PERFORM.name &&
        requirement.scope == record.scope && requirement.limitUse == "NOT_APPLICABLE" && requirement.owner == "UNRESTRICTED" &&
        requirement.binding.operationId == producer.operationId && requirement.binding.payloadDigest == producer.semanticDigest)
    // Reapply the exact reason grammar, without constructing an authorization or approval.
    check(requirement.binding.reasonCode.length in 1..128 &&
        requirement.binding.reasonCode.all { it.isLetterOrDigit() || it in "._-" })
    val selection = record.selection
    check(selection.policy.scope == record.scope && selection.catalog.scope == record.scope &&
        record.originalDocuments == selection.documents && record.products.size in 1..16 &&
        record.products.map { it.skuId }.distinct().size == record.products.size && record.sources.size in 1..LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES &&
        record.sources.map { it.kind to it.id }.distinct().size == record.sources.size &&
        record.sources.none { it.kind == record.kind && it.id == record.id })
    check(listOf(selection.policy.activePointer, selection.policy.publication,
        selection.catalog.activePointer, selection.catalog.publication).all { it in record.sources })
    val policy = purchasingArrivalUsePolicyV2(record.policyWireHex).body
    check(policy.branch == record.scope && policy.policyId == selection.policy.policyId &&
        policy.publicationId == selection.policy.publicationId && policy.revision == selection.policy.publicationRevision &&
        policy.validity.validFrom == selection.policy.validFrom && policy.validity.validUntil == selection.policy.validUntil &&
        record.recordedAt >= policy.validity.validFrom &&
        (policy.validity.validUntil?.let { record.recordedAt < it } ?: true))
    val rule = policy.rules.singleOrNull { it.ruleId == selection.ruleId }
        ?: error("Arrival use rule selection unavailable")
    check(rule.action == action && rule.limits is PurchasingLimitsV1.NoMonetaryEffect &&
        rule.acquisition == PurchasingAcquisitionProfileV1.NOT_APPLICABLE)
    check(purchasingArrivalUseRouteMatchesV2(rule.approval, producer.actorId,
        producer.approvalId, producer.approverId, producer.approverPolicyRevision))
    val supplier = record.sources.singleOrNull { it.kind == LocalConfigurationProfile.SUPPLIER }
        ?: error("Arrival use supplier coordinate unavailable")
    check(rule.selection.suppliers.any { it.supplierId == supplier.id && it.revision == supplier.storageRevision } &&
        record.products.all { actual -> rule.selection.products.skus.any { selected ->
            actual.skuId == selected.skuId && actual.skuRevision == selected.skuRevision &&
                actual.productId == selected.productId && actual.productRevision == selected.productRevision &&
                selected.catalog.publicationId == selection.catalog.publicationId &&
                selected.catalog.revision == selection.catalog.revision
        } })
    val ruleEvidence = rule.evidence
    check(selection.retention.classId == ruleEvidence.retentionClassId &&
        selection.retention.revision == ruleEvidence.retentionClassRevision &&
        record.originalDocuments.size in ruleEvidence.minimumCount..ruleEvidence.maximumCount)
    for (document in record.originalDocuments) {
        check(document.scope == record.scope && document.supplierId == supplier.id &&
            document.media in ruleEvidence.media.map { it.name } && document.purpose in ruleEvidence.purposes.map { it.name })
        val documentKind = if (document.parentDocumentId == null) LocalPurchasingDocumentProfileV1.ORIGINAL
            else LocalPurchasingDocumentProfileV1.EVIDENCE
        check(document.storageRevision == Revision(1) && record.sources.any {
            it.kind == documentKind && it.id == document.documentId && it.storageRevision == document.storageRevision
        })
        document.parentDocumentId?.let { parentId ->
            val head = requireNotNull(document.parentHead)
            check(head.kind == LocalPurchasingDocumentProfileV1.ATTACHMENTS && head.id == parentId &&
                head.storageRevision.value >= 1 && head in record.sources && record.sources.any {
                    it.kind == LocalPurchasingDocumentProfileV1.ORIGINAL && it.id == parentId && it.storageRevision == Revision(1)
                })
        }
    }
    val retention = policy.retentionClasses.singleOrNull {
        it.classId == selection.retention.classId && it.revision == selection.retention.revision
    } ?: error("Arrival use retention class unavailable")
    check(record.retention == purchasingArrivalUseRetentionV2(retention))
}

private fun purchasingArrivalUseRetentionV2(value: PurchasingRetentionClassV1): PurchasingArrivalDocumentUseRetentionV2 =
    PurchasingArrivalDocumentUseRetentionV2(value.classId, value.revision, value.minimumRetentionSeconds,
        purchasingArrivalUseFrozenV2(value.obligations.map { it.name }))

private fun purchasingArrivalUsePolicyV2(hex: String): PurchasingSignedPolicyV1 {
    purchasingDocumentHex(hex, LocalPurchasingPolicyProfileV1.MAX_POLICY_WIRE_BYTES)
    val bytes = ByteArray(hex.length / 2) { index ->
        ((hex[index * 2].digitToInt(16) shl 4) or hex[index * 2 + 1].digitToInt(16)).toByte()
    }
    try {
        val policy = LocalCompanyPolicyWireV1.decodeSignedPolicy(bytes)
        val canonical = LocalCompanyPolicyWireV1.encodeSignedPolicy(policy)
        try { check(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return policy
    } finally { bytes.fill(0) }
}

/** Only command/result identity is joined here; inventory reconstructs every custody effect. */
private fun purchasingArrivalUseRequireCommandV2(
    original: RetainedCommandEvidence, record: PurchasingArrivalDocumentUseRecordV2,
    requirement: AuthorizationRequirement, expectedRequesterPolicyRevision: Revision,
) {
    val action = when (original.command) {
        LocalUnpricedArrivalProfileV2.RECORD -> LocalArrivalActionV1.RECORD
        LocalUnpricedArrivalProfileV2.QUALIFY -> LocalArrivalActionV1.QUALIFY
        LocalUnpricedArrivalProfileV2.REJECT -> LocalArrivalActionV1.REJECT
        LocalUnpricedArrivalProfileV2.DISPATCH -> LocalArrivalActionV1.DISPATCH
        else -> error("Unsupported arrival use command")
    }
    val arrivalId: DomainId
    val eventId: DomainId?
    val arrivalRevision: Revision
    val affectedCustodyUnitId: DomainId?
    val selected: LocalArrivalEvidenceSelectionV2
    val items: List<LocalArrivalItemSelectionV2>
    val revision: Revision
    val policyRevision: Revision
    val reason: String
    when (original.command) {
        LocalUnpricedArrivalProfileV2.RECORD -> {
            val request = LocalUnpricedArrivalCommandsV2.decodeRecordRequest(original.normalized)
            arrivalId = request.arrivalId; eventId = null; arrivalRevision = Revision(1); affectedCustodyUnitId = null
            selected = request.evidence; items = request.items; revision = Revision(0)
            policyRevision = request.expectedRequesterPolicyRevision; reason = request.reasonCode
            check(record.originalDocuments.all { it.supplierId == request.supplierId })
            val supplier = record.sources.single { it.kind == LocalConfigurationProfile.SUPPLIER }
            check(supplier.id == request.supplierId && supplier.storageRevision == request.expectedSupplierRevision)
        }
        LocalUnpricedArrivalProfileV2.QUALIFY -> {
            val request = LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(original.normalized)
            arrivalId = request.arrivalId; eventId = request.eventId; arrivalRevision = request.expectedArrivalRevision.next()
            affectedCustodyUnitId = request.split?.custodyUnitId ?: request.custodyUnitId
            selected = request.evidence; items = listOf(request.item); revision = request.expectedArrivalRevision
            policyRevision = request.expectedRequesterPolicyRevision; reason = request.reasonCode
            check(record.sources.single { it.kind == LocalConfigurationProfile.SUPPLIER }.storageRevision == request.expectedSupplierRevision)
        }
        LocalUnpricedArrivalProfileV2.REJECT -> {
            val request = LocalUnpricedArrivalCommandsV2.decodeRejectRequest(original.normalized)
            arrivalId = request.arrivalId; eventId = request.eventId; arrivalRevision = request.expectedArrivalRevision.next()
            affectedCustodyUnitId = request.split?.custodyUnitId ?: request.custodyUnitId
            selected = request.evidence; items = listOf(request.item); revision = request.expectedArrivalRevision
            policyRevision = request.expectedRequesterPolicyRevision; reason = request.reasonCode
            check(record.sources.single { it.kind == LocalConfigurationProfile.SUPPLIER }.storageRevision == request.expectedSupplierRevision)
        }
        LocalUnpricedArrivalProfileV2.DISPATCH -> {
            val request = LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(original.normalized)
            arrivalId = request.arrivalId; eventId = request.eventId; arrivalRevision = request.expectedArrivalRevision.next()
            affectedCustodyUnitId = request.split?.custodyUnitId ?: request.custodyUnitId
            selected = request.evidence; items = listOf(request.item); revision = request.expectedArrivalRevision
            policyRevision = request.expectedRequesterPolicyRevision; reason = request.reasonCode
            val supplier = record.sources.single { it.kind == LocalConfigurationProfile.SUPPLIER }
            check(supplier.id == request.recipientSupplierId && supplier.storageRevision == request.expectedSupplierRevision)
        }
        else -> error("Unsupported arrival use command")
    }
    val binding = requireNotNull(requirement.binding)
    check(binding.documentId == arrivalId && binding.revision == revision && binding.reasonCode == reason &&
        policyRevision == expectedRequesterPolicyRevision && selected == record.selection &&
        items.size == record.products.size && items.map { LocalArrivalProductReferenceV2(it.skuId, it.skuRevision,
            it.productId, it.productRevision) }.toSet() == record.products.toSet())
    val result = LocalArrivalResultV2(action, arrivalId, eventId, arrivalRevision, affectedCustodyUnitId,
        failure = null, financialStatus = LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED)
    check(original.result == purchasingDocumentEncode(result, LocalArrivalResultV2.serializer(),
        LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES))
}

private fun purchasingArrivalUseActionV2(command: CommandKey): PurchasingRuleActionV1 = when (command) {
    LocalUnpricedArrivalProfileV2.RECORD -> PurchasingRuleActionV1.ARRIVAL_RECORD
    LocalUnpricedArrivalProfileV2.QUALIFY -> PurchasingRuleActionV1.ARRIVAL_QUALIFY
    LocalUnpricedArrivalProfileV2.REJECT -> PurchasingRuleActionV1.ARRIVAL_REJECT
    LocalUnpricedArrivalProfileV2.DISPATCH -> PurchasingRuleActionV1.ARRIVAL_DISPATCH
    else -> error("Unsupported arrival use producer")
}

private fun purchasingArrivalUseEffectsV2(
    record: PurchasingArrivalDocumentUseRecordV2, payload: StoredBytes,
): LocalArrivalDocumentaryEffectsV2 {
    val key = AggregateKey(LocalPurchasingArrivalDocumentUseProfileV2.KIND, record.id)
    val selection = record.selection.copy(documents = purchasingArrivalUseFrozenV2(record.selection.documents))
    val evidence = LocalArrivalPurchasingEvidenceV2(selection, record.policyWireHex,
        purchasingArrivalUseFrozenV2(record.products), purchasingArrivalUseFrozenV2(record.originalDocuments),
        purchasingArrivalUseFrozenV2(record.sources), LocalArrivalSourceSelectionV2(key.kind, key.id, Revision(1),
            purchasingArrivalUseDigestV2(payload)))
    return LocalArrivalDocumentaryEffectsV2(evidence,
        listOf(AggregateChange(key, null, LocalPurchasingArrivalDocumentUseProfileV2.SCHEMA, payload)),
        listOf(ClaimChange.Acquire(ClaimKey(LocalPurchasingArrivalDocumentUseProfileV2.OPERATION_USE_CLAIM,
            record.producer.operationId.value), record.id)))
}

private fun purchasingArrivalUseEncodeV2(record: PurchasingArrivalDocumentUseRecordV2): StoredBytes {
    val bytes = purchasingDocumentJson.encodeToString(PurchasingArrivalDocumentUseRecordV2.serializer(), record)
        .encodeToByteArray(throwOnInvalidSequence = true)
    try {
        purchasingArrivalUseCapacityV2(bytes.size in 1..LocalPurchasingArrivalDocumentUseProfileV2.MAX_ROW_BYTES)
        return StoredBytes(bytes)
    } finally { bytes.fill(0) }
}

private fun purchasingArrivalUseDigestV2(value: StoredBytes): PayloadDigest {
    val bytes = value.copyBytes()
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

private fun purchasingArrivalUseHexV2(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) { bytes.forEach { value ->
        val octet = value.toInt() and 255
        append(alphabet[octet ushr 4]); append(alphabet[octet and 15])
    } }
}

private fun purchasingArrivalUseCapacityV2(available: Boolean) {
    if (!available) throw LocalArrivalEvidenceRejectedV2(LocalArrivalFailureV2.CAPACITY_EXCEEDED)
}

private fun <T> purchasingArrivalUseFrozenV2(values: List<T>): List<T> = object : AbstractList<T>() {
    private val owned = values.toList()
    override val size: Int get() = owned.size
    override fun get(index: Int): T = owned[index]
}

/** Literal actor/approval consistency only, never current authority or an original MATCH proof.
 * Core separately joins genuine issuance, consume/CAS and both actors' original dependencies. */
internal fun purchasingArrivalUseRouteMatchesV2(route: PurchasingApprovalRouteV1, requesterId: DomainId,
    approvalId: DomainId?, approverId: DomainId?, approverPolicyRevision: Revision?): Boolean {
    fun selected(selection: PurchasingActorSelectionV1, actorId: DomainId): Boolean = when (selection) {
        PurchasingActorSelectionV1.CurrentBranchGrantees -> true
        is PurchasingActorSelectionV1.NamedEmployees -> actorId in selection.employeeIds
    }
    val complete = approvalId != null && approverId != null && approverPolicyRevision != null
    return when (route) {
        is PurchasingApprovalRouteV1.Direct -> approvalId == null && approverId == null &&
            approverPolicyRevision == null && selected(route.performers, requesterId)
        is PurchasingApprovalRouteV1.Separate -> complete && requesterId != approverId &&
            selected(route.requesters, requesterId) && selected(route.approvers, requireNotNull(approverId))
        is PurchasingApprovalRouteV1.NamedSelf -> complete && selected(route.requesters, requesterId) &&
            selected(route.approvers, requireNotNull(approverId)) &&
            (requesterId != approverId || requesterId in route.namedEmployeeIds)
    }
}
