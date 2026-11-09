package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.NativePurchasingPolicyTrust
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Original-native BEFORE-source joins for the new policy-activation profile only. No handler
 * re-preparation, historical CommandReadView, current employee session or caller-supplied
 * producer is constructed. Core's exact present row must have its already fully joined,
 * strictly earlier original. Deterministic stale/used refusals close only their five native
 * non-domain mutations. Missing/retired trust and every unavailable refusal stay held.
 * This does not qualify selector, documentary, arrival or replacement-SKU histories.
 */
internal class LocalPurchasingPolicyHistoryProducer(private val trust: NativePurchasingPolicyTrust?) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalPurchasingPolicyProfileV1.ACTIVATE)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy || trust == null) return false
        try {
            val retained = (command.original as RetainedOriginalV2.Legacy).evidence
            if (retained.committed) {
                requirePurchasingPolicyProducer(retained, trust)
                command.requireLegacyReadEvidence() // The v1 aggregate-write trace is Apply-only.
            }
            val request = purchasingPolicyDecode(command.normalized, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES)
            check(purchasingPolicyEncode(request, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES) == command.normalized &&
                semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
            val policy = decodePurchasingPolicy(request); val reference = purchasingPolicyReference(policy.body)
            if (!authenticPurchasingPolicy(trust, command.scope, request) || policy.body.branch != command.scope) return false
            val reader = HistoricalPurchasingPolicyReader(command, trust)
            val previous = reader.active()
            val existing = reader.row(LocalPurchasingPolicyProfileV1.PUBLICATION, reference.publicationId) != null
            val versionOwner = command.claim(purchasingPolicyVersionClaim(reference))
            versionOwner?.let { owner ->
                val published = reader.row(LocalPurchasingPolicyProfileV1.PUBLICATION, owner) ?: error("Historical policy version publication lost")
                val named = purchasingPolicyReference(decodePurchasingPolicy((published.second.body as PurchasingPolicyBody.Publication).request).body)
                check(named.policyId == reference.policyId && named.revision == reference.revision)
            }
            command.requireRequirement(purchasingPolicyActivationRequirement(command.scope, reference.policyId,
                request.expectedActiveStorageRevision, command.operationId, command.semanticDigest))
            // Exact native handler precedence, after its complete successful source reads.
            // Today's missing trust cannot prove an original POLICY_UNAVAILABLE decision.
            val refusal = try {
                purchasingPolicyRequire(previous?.active?.revision == request.expectedActiveStorageRevision, LocalPurchasingPolicyFailureV1.STALE_REVISION)
                purchasingPolicyRequire(!existing && versionOwner == null, LocalPurchasingPolicyFailureV1.PUBLICATION_ALREADY_USED)
                requirePurchasingPolicySequence(policy.body, previous?.reference)
                requirePurchasingPolicyValidity(policy.body, command.recordedAt)
                null
            } catch (failure: PurchasingPolicyRefusal) { failure.failure }
            val resources = setOf(purchasingPolicyAuthResource(command.actorId)) + purchasingPolicyResources(policy) + reader.resources
            if (refusal != null) {
                if (retained.committed || refusal !in setOf(LocalPurchasingPolicyFailureV1.STALE_REVISION,
                        LocalPurchasingPolicyFailureV1.PUBLICATION_ALREADY_USED)) return false
                command.requirePurchasingPolicyRejection(purchasingPolicyRejected(reference.policyId, reference, refusal), resources)
                return true
            }
            if (!retained.committed) return false
            val changes = listOf(
                purchasingPolicyChange(command.scope, command.operationId, command.actorId, command.command, command.semanticDigest, command.recordedAt,
                    command.policyRevision, LocalPurchasingPolicyProfileV1.ACTIVE, command.scope.branchId, previous?.active?.revision,
                    PurchasingPolicyBody.Active(reference)),
                purchasingPolicyChange(command.scope, command.operationId, command.actorId, command.command, command.semanticDigest, command.recordedAt,
                    command.policyRevision, LocalPurchasingPolicyProfileV1.PUBLICATION, reference.publicationId, null,
                    PurchasingPolicyBody.Publication(request)))
                .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
            command.requireLegacyEffects(ProposedOutcome.Apply(
                purchasingPolicySuccess(reference.policyId, previous?.active?.revision?.next() ?: Revision(1), reference),
                changes, purchasingPolicyActivationClaims(command.scope, reference), mustCommitBefore = policy.body.validity.validUntil), resources)
            return true
        } catch (_: PurchasingPolicyRefusal) {
            return false // Missing/retired native trust or unavailable original policy is not acceptance.
        }
    }
}

/** Uses actual core-selected BEFORE sources directly, never a synthetic historical read view. */
internal class HistoricalPurchasingPolicyReader(private val command: RuntimeHistoricalCommand, private val trust: NativePurchasingPolicyTrust?) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(purchasingPolicyAuthResource(command.scope.branchId))

    suspend fun row(kind: String, id: DomainId): Pair<StoredAggregate, PurchasingPolicyRecord>? {
        resources += purchasingPolicyAuthResource(id)
        val selected = command.aggregateSource(purchasingPolicyKey(kind, id))
        val claim = when (kind) {
            LocalPurchasingPolicyProfileV1.ACTIVE -> purchasingPolicyBranchClaim(command.scope)
            LocalPurchasingPolicyProfileV1.PUBLICATION -> purchasingPolicyPublicationClaim(id)
            else -> error("Unknown historical purchasing policy kind")
        }
        if (selected == null) { check(command.claim(claim) == null); return null }
        val record = decodePurchasingPolicyRecord(command.scope, selected.row)
        val original = selected.original as? RetainedOriginalV2.Legacy ?: error("Unsupported historical purchasing policy profile")
        requirePurchasingPolicyProducer(original.evidence, trust)
        check(original.evidence.operationId == record.producer.operationId)
        val write = original.evidence.aggregateWrites.single { it.key == selected.row.key }
        check(write.storageGeneration == selected.row.revision && write.schema == selected.row.schema &&
            write.payload == selected.row.payload && write.payloadDigest == selected.row.digest)
        resources += purchasingPolicyRecordResources(record)
        when (val body = record.body) {
            is PurchasingPolicyBody.Active -> check(command.claim(claim) == body.reference.policyId)
            is PurchasingPolicyBody.Publication -> {
                val reference = purchasingPolicyReference(decodePurchasingPolicy(body.request).body)
                check(command.claim(claim) == id && command.claim(purchasingPolicyVersionClaim(reference)) == id &&
                    command.claim(purchasingPolicyBranchClaim(command.scope)) == reference.policyId)
            }
        }
        return selected.row to record
    }

    suspend fun active(): LoadedPurchasingPolicy? {
        val (active, record) = row(LocalPurchasingPolicyProfileV1.ACTIVE, command.scope.branchId) ?: return null
        val reference = (record.body as PurchasingPolicyBody.Active).reference
        val (publication, published) = row(LocalPurchasingPolicyProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Historical active purchasing publication lost")
        val request = (published.body as PurchasingPolicyBody.Publication).request
        val policy = decodePurchasingPolicy(request)
        check(purchasingPolicyReference(policy.body) == reference && policy.body.branch == command.scope &&
            record.producer == published.producer && record.recordedAt == published.recordedAt &&
            record.employeePolicyRevision == published.employeePolicyRevision)
        purchasingPolicyRequire(authenticPurchasingPolicy(trust, command.scope, request), LocalPurchasingPolicyFailureV1.POLICY_UNAVAILABLE)
        resources += purchasingPolicyResources(policy)
        return LoadedPurchasingPolicy(active, record, publication, published, policy)
    }
}
