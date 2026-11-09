package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Genuine policy-retention prerequisite over the branch's actual atomic result/effects/audit/
 * journal/outbox transaction. No documentary intake, selector qualification, evidence linking,
 * arrival authority, stock ownership, invoice or payment effect is supplied by these services.
 * The native trust is explicit; an unavailable/retired handle never becomes an accepting source.
 */
public class LocalPurchasingPolicyServices(trust: NativePurchasingPolicyTrust?) {
    public val commands: List<MasterCommandHandler> = listOf(PurchasingPolicyActivationHandler(trust))
    public val reads: List<MasterReadHandler> = listOf(PurchasingPolicyReadHandler(trust))
    internal val history: RuntimeHistoryBusinessProducer = LocalPurchasingPolicyHistoryProducer(trust)
}

@Serializable
internal data class PurchasingPolicyProducer(
    val operationId: OperationId, val actorId: DomainId, val command: String, val semanticDigest: PayloadDigest,
)

@Serializable
internal sealed interface PurchasingPolicyBody {
    @Serializable @SerialName("ACTIVE") data class Active(val reference: LocalPurchasingPolicyReferenceV1) : PurchasingPolicyBody
    @Serializable @SerialName("PUBLICATION") data class Publication(val request: LocalActivatePurchasingPolicyV1) : PurchasingPolicyBody
}

@Serializable
internal data class PurchasingPolicyRecord(
    val scope: BranchScope, val kind: String, val id: DomainId, val storageRevision: Revision,
    val producer: PurchasingPolicyProducer, val recordedAt: UtcInstant, val employeePolicyRevision: Revision,
    val body: PurchasingPolicyBody,
    val qualification: LocalPurchasingPolicyQualificationV1 = LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS,
) { override fun toString(): String = "PurchasingPolicyRecord([REDACTED])" }

internal fun purchasingPolicyKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun purchasingPolicyBranchClaim(scope: BranchScope): ClaimKey = ClaimKey(LocalPurchasingPolicyProfileV1.POLICY_CLAIM, scope.branchId.value)
internal fun purchasingPolicyPublicationClaim(id: DomainId): ClaimKey = ClaimKey(LocalPurchasingPolicyProfileV1.PUBLICATION_CLAIM, id.value)
internal fun purchasingPolicyVersionClaim(reference: LocalPurchasingPolicyReferenceV1): ClaimKey =
    ClaimKey(LocalPurchasingPolicyProfileV1.VERSION_CLAIM, "${reference.policyId.value}/${reference.revision.value}")
internal fun purchasingPolicyAuthResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)

internal val purchasingPolicyBarriers: Set<RecoveryResourceKind> = setOf(
    RecoveryResourceKind.AUTHORIZATION, RecoveryResourceKind.STOCK,
    RecoveryResourceKind.PURCHASE_CUSTODY, RecoveryResourceKind.SUPPLIER_SETTLEMENT,
)

/** Literal affected identities only, not equivalence of signed selectors and local sources. */
internal fun purchasingPolicyResources(policy: PurchasingSignedPolicyV1): Set<RecoveryResourceKey> = buildSet {
    val body = policy.body
    listOf(body.branch.branchId, body.policyId, body.publicationId, body.root.keyId, body.author.authorityId,
        body.author.keyId, body.author.delegationId, body.author.delegationPublication.publicationId)
        .forEach { add(purchasingPolicyAuthResource(it)) }
    body.retentionClasses.forEach { add(purchasingPolicyAuthResource(it.classId)) }
    for (rule in body.rules) {
        add(purchasingPolicyAuthResource(rule.ruleId)); add(purchasingPolicyAuthResource(rule.delegationGrantId))
        rule.selection.suppliers.forEach { add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it.supplierId)) }
        rule.selection.products.skus.forEach { sku ->
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, sku.skuId))
            add(purchasingPolicyAuthResource(sku.productId)); add(purchasingPolicyAuthResource(sku.catalog.publicationId))
        }
        rule.selection.products.categories.forEach {
            add(purchasingPolicyAuthResource(it.categoryId)); add(purchasingPolicyAuthResource(it.catalog.publicationId))
        }
        fun actors(selection: PurchasingActorSelectionV1) {
            if (selection is PurchasingActorSelectionV1.NamedEmployees)
                selection.employeeIds.forEach { add(purchasingPolicyAuthResource(it)) }
        }
        when (val route = rule.approval) {
            is PurchasingApprovalRouteV1.Direct -> actors(route.performers)
            is PurchasingApprovalRouteV1.Separate -> { actors(route.requesters); actors(route.approvers) }
            is PurchasingApprovalRouteV1.NamedSelf -> {
                actors(route.requesters); actors(route.approvers)
                route.namedEmployeeIds.forEach { add(purchasingPolicyAuthResource(it)) }
            }
        }
    }
}

internal fun purchasingPolicyRecordResources(record: PurchasingPolicyRecord): Set<RecoveryResourceKey> = buildSet {
    add(purchasingPolicyAuthResource(record.id)); add(purchasingPolicyAuthResource(record.producer.actorId))
    when (val body = record.body) {
        is PurchasingPolicyBody.Active -> {
            add(purchasingPolicyAuthResource(body.reference.policyId)); add(purchasingPolicyAuthResource(body.reference.publicationId))
        }
        is PurchasingPolicyBody.Publication -> addAll(purchasingPolicyResources(decodePurchasingPolicy(body.request)))
    }
}

internal fun decodePurchasingPolicy(request: LocalActivatePurchasingPolicyV1): PurchasingSignedPolicyV1 {
    val bytes = purchasingPolicyWireBytes(request.policyWireHex)
    try {
        val policy = LocalCompanyPolicyWireV1.decodeSignedPolicy(bytes)
        val canonical = LocalCompanyPolicyWireV1.encodeSignedPolicy(policy)
        try { require(canonical.contentEquals(bytes)) { "Noncanonical purchasing policy envelope" } }
        finally { canonical.fill(0) }
        return policy
    } finally { bytes.fill(0) }
}

internal fun authenticPurchasingPolicy(trust: NativePurchasingPolicyTrust?, scope: BranchScope, request: LocalActivatePurchasingPolicyV1): Boolean {
    val bytes = purchasingPolicyWireBytes(request.policyWireHex)
    return try { trust?.verify(scope, bytes) == true }
    finally { bytes.fill(0) }
}

internal fun decodePurchasingPolicyRecord(scope: BranchScope, row: StoredAggregate): PurchasingPolicyRecord {
    check(sha256(row.payload.copyBytes()) == row.digest && row.schema == LocalPurchasingPolicyProfileV1.schema(row.key.kind))
    val record = purchasingPolicyDecode(row.payload, PurchasingPolicyRecord.serializer())
    check(purchasingPolicyEncode(record, PurchasingPolicyRecord.serializer()) == row.payload && record.scope == scope &&
        record.kind == row.key.kind && record.id == row.key.id && record.storageRevision == row.revision && row.revision.value >= 1 &&
        record.qualification == LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS)
    when (val body = record.body) {
        is PurchasingPolicyBody.Active -> check(record.kind == LocalPurchasingPolicyProfileV1.ACTIVE && record.id == scope.branchId &&
            record.storageRevision == body.reference.revision)
        is PurchasingPolicyBody.Publication -> {
            val policy = decodePurchasingPolicy(body.request)
            check(record.kind == LocalPurchasingPolicyProfileV1.PUBLICATION && record.id == policy.body.publicationId &&
                record.storageRevision == Revision(1) && policy.body.branch == scope)
        }
    }
    return record
}

internal fun purchasingPolicyChange(scope: BranchScope, operationId: OperationId, actorId: DomainId, key: CommandKey,
    digest: PayloadDigest, now: UtcInstant, employeePolicy: Revision, kind: String, id: DomainId,
    before: Revision?, body: PurchasingPolicyBody): AggregateChange {
    val record = PurchasingPolicyRecord(scope, kind, id, before?.next() ?: Revision(1),
        PurchasingPolicyProducer(operationId, actorId, key.name, digest), now, employeePolicy, body)
    return AggregateChange(purchasingPolicyKey(kind, id), before, LocalPurchasingPolicyProfileV1.schema(kind),
        purchasingPolicyEncode(record, PurchasingPolicyRecord.serializer()))
}

internal fun purchasingPolicyActivationRequirement(scope: BranchScope, policyId: DomainId, expected: Revision?,
    operation: OperationId, digest: PayloadDigest): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope,
        ActionBinding(policyId, expected ?: Revision(0), digest, operation, "purchasing.policy.activate"))

internal fun purchasingPolicyActivationClaims(scope: BranchScope, reference: LocalPurchasingPolicyReferenceV1): List<ClaimChange> =
    (listOf(ClaimChange.Acquire(purchasingPolicyPublicationClaim(reference.publicationId), reference.publicationId),
        ClaimChange.Acquire(purchasingPolicyVersionClaim(reference), reference.publicationId)) +
        if (reference.revision == Revision(1)) listOf(ClaimChange.Acquire(purchasingPolicyBranchClaim(scope), reference.policyId)) else emptyList())
        .sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })

internal fun purchasingPolicySuccess(id: DomainId, revision: Revision, policy: LocalPurchasingPolicyReferenceV1): StoredBytes =
    purchasingPolicyEncode(LocalPurchasingPolicyResultV1("purchasing.policy.activated_unqualified_selectors", id, revision, policy),
        LocalPurchasingPolicyResultV1.serializer())

internal class PurchasingPolicyRefusal(val failure: LocalPurchasingPolicyFailureV1) : RuntimeException("Local purchasing policy unavailable")
internal fun purchasingPolicyRequire(condition: Boolean, failure: LocalPurchasingPolicyFailureV1) {
    if (!condition) throw PurchasingPolicyRefusal(failure)
}

internal fun purchasingPolicyRejected(id: DomainId, policy: LocalPurchasingPolicyReferenceV1,
    failure: LocalPurchasingPolicyFailureV1): ProposedOutcome.Reject {
    val code = "purchasing.policy.${failure.name.lowercase()}"
    return ProposedOutcome.Reject(code, purchasingPolicyEncode(LocalPurchasingPolicyResultV1(code, id, null, policy, failure),
        LocalPurchasingPolicyResultV1.serializer()))
}

/** Literal whole retained producer; native provenance/current authorization remain separate owners. */
internal fun requirePurchasingPolicyProducer(evidence: RetainedCommandEvidence, trust: NativePurchasingPolicyTrust?) {
    check(evidence.committed && evidence.nativeOriginalProvenance == null && evidence.jobs.isEmpty() &&
        evidence.command == LocalPurchasingPolicyProfileV1.ACTIVATE && evidence.aggregateWrites.size == 2 &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
    val request = purchasingPolicyDecode(evidence.normalized, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES)
    check(purchasingPolicyEncode(request, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES) == evidence.normalized)
    val policy = decodePurchasingPolicy(request); val reference = purchasingPolicyReference(policy.body)
    purchasingPolicyRequire(authenticPurchasingPolicy(trust, evidence.scope, request), LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
    requirePurchasingPolicyValidity(policy.body, evidence.recordedAt)
    fun row(kind: String): PurchasingPolicyRecord {
        val write = evidence.aggregateWrites.singleOrNull { it.key.kind == kind } ?: error("Purchasing policy producer row lost")
        check(sha256(write.payload.copyBytes()) == write.payloadDigest)
        val value = purchasingPolicyDecode(write.payload, PurchasingPolicyRecord.serializer())
        check(purchasingPolicyEncode(value, PurchasingPolicyRecord.serializer()) == write.payload && value.scope == evidence.scope &&
            value.kind == kind && value.id == write.key.id && value.storageRevision == write.storageGeneration &&
            write.schema == LocalPurchasingPolicyProfileV1.schema(kind) &&
            value.qualification == LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS &&
            value.producer == PurchasingPolicyProducer(evidence.operationId, evidence.originalActorId, evidence.command.name, evidence.semanticDigest) &&
            value.recordedAt == evidence.recordedAt && value.employeePolicyRevision == evidence.policyRevision)
        return value
    }
    val active = row(LocalPurchasingPolicyProfileV1.ACTIVE); val publication = row(LocalPurchasingPolicyProfileV1.PUBLICATION)
    check(policy.body.branch == evidence.scope && active.id == evidence.scope.branchId && active.body == PurchasingPolicyBody.Active(reference) &&
        active.storageRevision == (request.expectedActiveStorageRevision?.next() ?: Revision(1)) && active.storageRevision == reference.revision &&
        publication.id == reference.publicationId && publication.storageRevision == Revision(1) && publication.body == PurchasingPolicyBody.Publication(request) &&
        evidence.result == purchasingPolicySuccess(reference.policyId, active.storageRevision, reference))
    val claims = purchasingPolicyActivationClaims(evidence.scope, reference)
    check(evidence.aggregateWrites == evidence.aggregateWrites.sortedWith(compareBy<RetainedAggregateWrite> { it.key.kind }.thenBy { it.key.id.value }) &&
        evidence.claimChanges.size == claims.size && evidence.claimChanges.zip(claims).all { (actual, wanted) ->
            actual.action == RetainedClaimAction.ACQUIRE && actual.key == wanted.key && actual.ownerId == wanted.ownerId })
}

internal data class LoadedPurchasingPolicy(
    val active: StoredAggregate, val activeRecord: PurchasingPolicyRecord,
    val publication: StoredAggregate, val publicationRecord: PurchasingPolicyRecord, val policy: PurchasingSignedPolicyV1,
) {
    val reference: LocalPurchasingPolicyReferenceV1 get() = (activeRecord.body as PurchasingPolicyBody.Active).reference
}

/** Same-loan reader: missing claimed rows or missing originals are uncertainty, not recreation. */
internal class CurrentPurchasingPolicyReader(private val view: CommandReadView, private val trust: NativePurchasingPolicyTrust?) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(purchasingPolicyAuthResource(view.scope.branchId))
    private val rows = mutableMapOf<AggregateKey, Pair<StoredAggregate, PurchasingPolicyRecord>?>()
    suspend fun row(kind: String, id: DomainId): Pair<StoredAggregate, PurchasingPolicyRecord>? {
        val key = purchasingPolicyKey(kind, id)
        resources += purchasingPolicyAuthResource(id)
        if (key in rows) return rows.getValue(key)
        check(rows.size < 64)
        val stored = view.aggregate(key)
        val claim = when (kind) {
            LocalPurchasingPolicyProfileV1.ACTIVE -> purchasingPolicyBranchClaim(view.scope)
            LocalPurchasingPolicyProfileV1.PUBLICATION -> purchasingPolicyPublicationClaim(id)
            else -> error("Unknown purchasing policy kind")
        }
        if (stored == null) { check(view.claim(claim) == null); rows[key] = null; return null }
        val record = decodePurchasingPolicyRecord(view.scope, stored)
        val original = view.retainedCommand(record.producer.operationId) ?: error("Purchasing policy producer unavailable")
        requirePurchasingPolicyProducer(original, trust)
        val write = original.aggregateWrites.singleOrNull { it.key == key } ?: error("Purchasing policy producer row lost")
        check(write.storageGeneration == stored.revision && write.schema == stored.schema && write.payload == stored.payload && write.payloadDigest == stored.digest)
        resources += purchasingPolicyRecordResources(record)
        when (val body = record.body) {
            is PurchasingPolicyBody.Active -> check(view.claim(claim) == body.reference.policyId)
            is PurchasingPolicyBody.Publication -> {
                val reference = purchasingPolicyReference(decodePurchasingPolicy(body.request).body)
                check(view.claim(claim) == id && view.claim(purchasingPolicyVersionClaim(reference)) == id &&
                    view.claim(purchasingPolicyBranchClaim(view.scope)) == reference.policyId)
            }
        }
        return (stored to record).also { rows[key] = it }
    }

    suspend fun active(): LoadedPurchasingPolicy? {
        val (active, record) = row(LocalPurchasingPolicyProfileV1.ACTIVE, view.scope.branchId) ?: return null
        val reference = (record.body as PurchasingPolicyBody.Active).reference
        val (publication, published) = row(LocalPurchasingPolicyProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Active purchasing publication lost")
        val request = (published.body as PurchasingPolicyBody.Publication).request
        val policy = decodePurchasingPolicy(request)
        check(purchasingPolicyReference(policy.body) == reference && policy.body.branch == view.scope &&
            record.producer == published.producer && record.recordedAt == published.recordedAt &&
            record.employeePolicyRevision == published.employeePolicyRevision)
        purchasingPolicyRequire(authenticPurchasingPolicy(trust, view.scope, request), LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
        resources += purchasingPolicyResources(policy)
        return LoadedPurchasingPolicy(active, record, publication, published, policy)
    }
}

private class PurchasingPolicyActivationHandler(private val trust: NativePurchasingPolicyTrust?) : MasterCommandHandler {
    override val key: CommandKey = LocalPurchasingPolicyProfileV1.ACTIVATE
    override val writableKinds: Set<String> = setOf(LocalPurchasingPolicyProfileV1.ACTIVE, LocalPurchasingPolicyProfileV1.PUBLICATION)
    override val claimNamespaces: Set<String> = setOf(LocalPurchasingPolicyProfileV1.POLICY_CLAIM,
        LocalPurchasingPolicyProfileV1.PUBLICATION_CLAIM, LocalPurchasingPolicyProfileV1.VERSION_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes {
        val request = purchasingPolicyDecode(input, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES)
        decodePurchasingPolicy(request)
        return purchasingPolicyEncode(request, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES)
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = purchasingPolicyDecode(normalized, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES)
        val policy = decodePurchasingPolicy(request); val reference = purchasingPolicyReference(policy.body)
        val reader = CurrentPurchasingPolicyReader(view, trust)
        val resources = setOf(purchasingPolicyAuthResource(context.originalActorId)) + purchasingPolicyResources(policy)
        var failure: LocalPurchasingPolicyFailureV1? = null
        var previous: LoadedPurchasingPolicy? = null
        var existing = false; var versionUsed = false
        if (authenticPurchasingPolicy(trust, view.scope, request)) {
            try {
                previous = reader.active()
                existing = reader.row(LocalPurchasingPolicyProfileV1.PUBLICATION, reference.publicationId) != null
                val versionOwner = view.claim(purchasingPolicyVersionClaim(reference))
                versionUsed = versionOwner != null
                versionOwner?.let { owner ->
                    val published = reader.row(LocalPurchasingPolicyProfileV1.PUBLICATION, owner) ?: error("Policy version publication lost")
                    val named = purchasingPolicyReference(decodePurchasingPolicy((published.second.body as PurchasingPolicyBody.Publication).request).body)
                    check(named.policyId == reference.policyId && named.revision == reference.revision)
                }
            } catch (refusal: PurchasingPolicyRefusal) { failure = refusal.failure }
        } else failure = LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE
        val required = purchasingPolicyActivationRequirement(context.scope, reference.policyId,
            request.expectedActiveStorageRevision, context.operationId, context.semanticDigest)
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> = resources + reader.resources
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingPolicyBarriers
            override val allowsActionApproval: Boolean = false
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(required, context.originalActorId))
                return try {
                    failure?.let { throw PurchasingPolicyRefusal(it) }
                    purchasingPolicyRequire(authenticPurchasingPolicy(trust, context.scope, request) && policy.body.branch == context.scope,
                        LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
                    purchasingPolicyRequire(previous?.active?.revision == request.expectedActiveStorageRevision, LocalPurchasingPolicyFailureV1.STALE_REVISION)
                    purchasingPolicyRequire(!existing && !versionUsed, LocalPurchasingPolicyFailureV1.PUBLICATION_ALREADY_USED)
                    requirePurchasingPolicySequence(policy.body, previous?.reference)
                    requirePurchasingPolicyValidity(policy.body, recordedAt)
                    val changes = listOf(
                        purchasingPolicyChange(context.scope, context.operationId, context.originalActorId, key, context.semanticDigest, recordedAt,
                            authorization.policyRevision, LocalPurchasingPolicyProfileV1.ACTIVE, context.scope.branchId, previous?.active?.revision,
                            PurchasingPolicyBody.Active(reference)),
                        purchasingPolicyChange(context.scope, context.operationId, context.originalActorId, key, context.semanticDigest, recordedAt,
                            authorization.policyRevision, LocalPurchasingPolicyProfileV1.PUBLICATION, reference.publicationId, null,
                            PurchasingPolicyBody.Publication(request)))
                        .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
                    ProposedOutcome.Apply(purchasingPolicySuccess(reference.policyId, previous?.active?.revision?.next() ?: Revision(1), reference),
                        changes, purchasingPolicyActivationClaims(context.scope, reference), mustCommitBefore = policy.body.validity.validUntil)
                } catch (refusal: PurchasingPolicyRefusal) { purchasingPolicyRejected(reference.policyId, reference, refusal.failure) }
            }
        }
    }
}

internal fun requirePurchasingPolicySequence(policy: PurchasingPolicyPublicationV1, previous: LocalPurchasingPolicyReferenceV1?) {
    if (previous == null) purchasingPolicyRequire(policy.revision == Revision(1) && policy.predecessor == null, LocalPurchasingPolicyFailureV1.STALE_REVISION)
    else {
        val predecessor = policy.predecessor
        purchasingPolicyRequire(policy.policyId == previous.policyId && policy.revision == previous.revision.next() && predecessor != null &&
            predecessor.publicationId == previous.publicationId && predecessor.revision == previous.revision && predecessor.bodyDigest == previous.bodyDigest,
            LocalPurchasingPolicyFailureV1.STALE_REVISION)
    }
}

internal fun requirePurchasingPolicyValidity(policy: PurchasingPolicyPublicationV1, now: UtcInstant) {
    purchasingPolicyRequire(now >= policy.validity.validFrom && (policy.validity.validUntil?.let { now < it } ?: true),
        LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
}

private class PurchasingPolicyReadHandler(private val trust: NativePurchasingPolicyTrust?) : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingPolicyProfileV1.READ
    override fun normalize(input: StoredBytes): StoredBytes = purchasingPolicyEncode(
        purchasingPolicyDecode(input, LocalPurchasingPolicyQueryV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_QUERY_BYTES),
        LocalPurchasingPolicyQueryV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_QUERY_BYTES)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = purchasingPolicyDecode(normalized, LocalPurchasingPolicyQueryV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_QUERY_BYTES)
        val reader = CurrentPurchasingPolicyReader(view, trust)
        var binding = (query.id ?: context.scope.branchId) to Revision(0)
        var nativePolicy: LocalActivatePurchasingPolicyV1? = null
        val result = try {
            if (trust == null) throw PurchasingPolicyRefusal(LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
            when (query.kind) {
                LocalPurchasingPolicyReadKindV1.ACTIVE_POLICY -> {
                    val policy = reader.active() ?: throw PurchasingPolicyRefusal(LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
                    binding = policy.reference.policyId to policy.active.revision
                    val request = (policy.publicationRecord.body as PurchasingPolicyBody.Publication).request
                    nativePolicy = request
                    LocalPurchasingPolicyReadResultV1(policy.reference, request.policyWireHex)
                }
                LocalPurchasingPolicyReadKindV1.POLICY_PUBLICATION -> {
                    val (row, record) = reader.row(LocalPurchasingPolicyProfileV1.PUBLICATION, checkNotNull(query.id))
                        ?: throw PurchasingPolicyRefusal(LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
                    val request = (record.body as PurchasingPolicyBody.Publication).request
                    val reference = purchasingPolicyReference(decodePurchasingPolicy(request).body)
                    binding = reference.policyId to row.revision
                    nativePolicy = request
                    LocalPurchasingPolicyReadResultV1(reference, request.policyWireHex)
                }
            }
        } catch (refusal: PurchasingPolicyRefusal) { LocalPurchasingPolicyReadResultV1(null, null, refusal.failure) }
        val selectedNativePolicy = nativePolicy
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(
                Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER),
                Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
            ).map { permission -> AuthorizationRequirement(permission, context.scope,
                ActionBinding(binding.first, binding.second, context.semanticDigest, reasonCode = "purchasing.policy.read")) }

            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (proof, requirement) ->
                    proof.principalKind == PrincipalKind.LOCAL_EMPLOYEE && proof.isBoundTo(requirement, context.actorId) })
                val visible = if (selectedNativePolicy != null && !authenticPurchasingPolicy(trust, context.scope, selectedNativePolicy))
                    LocalPurchasingPolicyReadResultV1(null, null, LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
                else result
                return purchasingPolicyEncode(visible, LocalPurchasingPolicyReadResultV1.serializer())
            }
        }
    }
}
