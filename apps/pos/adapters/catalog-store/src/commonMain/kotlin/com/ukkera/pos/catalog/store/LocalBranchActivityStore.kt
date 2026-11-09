package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One initial activity application in the actual atomic Master transaction. Native company
 * trust is explicit; registering these services grants no employee or business capability.
 */
public class LocalBranchActivityServices(signatures: CompanyPublicationSignatureVerifier) {
    private val commandValues: List<MasterCommandHandler> = listOf(BranchActivityActivationHandler(signatures))
    private val readValues: List<MasterReadHandler> = listOf(BranchActivityCurrentReadHandler(signatures))
    public val commands: List<MasterCommandHandler> get() = commandValues.toList()
    public val reads: List<MasterReadHandler> get() = readValues.toList()
    internal val history: RuntimeHistoryBusinessProducer = LocalBranchActivityHistoryProducer(signatures)
}

@Serializable
internal data class BranchActivityProducerV1(
    val operationId: OperationId,
    val actorId: DomainId,
    val commandName: String,
    val commandVersion: Int,
    val semanticDigest: PayloadDigest,
)

@Serializable
internal sealed interface BranchActivityBodyV1 {
    @Serializable @SerialName("ACTIVE")
    data class Active(val reference: LocalBranchActivityReferenceV1) : BranchActivityBodyV1
    @Serializable @SerialName("PUBLICATION")
    data class Publication(val request: LocalActivateInitialBranchActivityV1) : BranchActivityBodyV1
}

@Serializable
internal data class BranchActivityRecordV1(
    val scope: BranchScope,
    val kind: String,
    val id: DomainId,
    val storageRevision: Revision,
    val producer: BranchActivityProducerV1,
    val recordedAt: UtcInstant,
    val permissionPolicyRevision: Revision,
    val body: BranchActivityBodyV1,
) {
    override fun toString(): String = "BranchActivityRecordV1([REDACTED])"
}

internal fun branchActivityKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun branchActivityBranchClaim(scope: BranchScope): ClaimKey =
    ClaimKey(LocalBranchActivityProfileV1.BRANCH_CLAIM, scope.branchId.value)
internal fun branchActivityPublicationClaim(id: DomainId): ClaimKey =
    ClaimKey(LocalBranchActivityProfileV1.PUBLICATION_CLAIM, id.value)
internal fun branchActivityResource(id: DomainId): RecoveryResourceKey =
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)

internal fun branchActivitySourceResources(reference: LocalBranchActivityReferenceV1): Set<RecoveryResourceKey> =
    setOf(branchActivityResource(reference.profileId), branchActivityResource(reference.publicationId),
        branchActivityResource(reference.signingKeyId))

internal fun branchActivityCommandResources(scope: BranchScope, actor: DomainId, operation: OperationId,
    reference: LocalBranchActivityReferenceV1): Set<RecoveryResourceKey> =
    setOf(branchActivityResource(scope.branchId), branchActivityResource(actor),
        branchActivityResource(DomainId(operation.value))) + branchActivitySourceResources(reference)

internal fun branchActivityRecordResources(record: BranchActivityRecordV1): Set<RecoveryResourceKey> {
    val reference = when (val body = record.body) {
        is BranchActivityBodyV1.Active -> body.reference
        is BranchActivityBodyV1.Publication -> branchActivityReference(branchActivitySigned(body.request))
    }
    return branchActivityCommandResources(record.scope, record.producer.actorId, record.producer.operationId, reference) +
        branchActivityResource(record.id)
}

internal fun branchActivitySigned(request: LocalActivateInitialBranchActivityV1): LocalSignedCompanyActivityProfileV1 =
    LocalCompanyActivityPublicationCodecV1.decodeSignedPublication(request.signedPublication)

internal fun authenticBranchActivity(signatures: CompanyPublicationSignatureVerifier, scope: BranchScope,
    signed: LocalSignedCompanyActivityProfileV1): Boolean = signed.definition.company == scope.company() &&
    LocalCompanyActivityPublicationCodecV1.verify(signed, signatures)

/** A closed ordinary unavailable source, not an exception-based kernel business refusal. */
internal class BranchActivitySourceUnavailable : RuntimeException("Local branch activity source unavailable")

internal fun requireBranchActivitySource(available: Boolean) {
    if (!available) throw BranchActivitySourceUnavailable()
}

internal fun branchActivityActivationRequirement(scope: BranchScope, previous: Revision?, operation: OperationId,
    digest: PayloadDigest): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER), scope,
        ActionBinding(scope.branchId, previous ?: Revision(0), digest, operation, "branch.activity.activate_initial"))

internal fun branchActivityClaims(scope: BranchScope, reference: LocalBranchActivityReferenceV1): List<ClaimChange> =
    listOf(ClaimChange.Acquire(branchActivityBranchClaim(scope), reference.publicationId),
        ClaimChange.Acquire(branchActivityPublicationClaim(reference.publicationId), reference.publicationId))
        .sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })

internal fun branchActivityApplied(scope: BranchScope, reference: LocalBranchActivityReferenceV1,
    request: LocalActivateInitialBranchActivityV1, operation: OperationId, actor: DomainId,
    policyRevision: Revision, recordedAt: UtcInstant): LocalAppliedBranchActivityV1 =
    LocalAppliedBranchActivityV1(scope, reference, request.signedPublication, Revision(1), operation, actor,
        policyRevision, recordedAt)

internal fun branchActivitySuccess(applied: LocalAppliedBranchActivityV1): StoredBytes =
    branchActivityEncode(LocalBranchActivityResultV1(LocalBranchActivityProfileV1.ACTIVATED, applied.reference, applied),
        LocalBranchActivityResultV1.serializer())

internal fun branchActivityAlreadyActivated(candidate: LocalBranchActivityReferenceV1,
    previous: LocalAppliedBranchActivityV1): ProposedOutcome.Reject =
    ProposedOutcome.Reject(LocalBranchActivityProfileV1.ALREADY_ACTIVATED,
        branchActivityEncode(LocalBranchActivityResultV1(LocalBranchActivityProfileV1.ALREADY_ACTIVATED, candidate, previous),
            LocalBranchActivityResultV1.serializer()))

internal fun branchActivityChanges(scope: BranchScope, operation: OperationId, actor: DomainId,
    digest: PayloadDigest, recordedAt: UtcInstant, policyRevision: Revision,
    request: LocalActivateInitialBranchActivityV1, reference: LocalBranchActivityReferenceV1): List<AggregateChange> {
    val producer = BranchActivityProducerV1(operation, actor, LocalBranchActivityProfileV1.ACTIVATE_INITIAL.name,
        LocalBranchActivityProfileV1.ACTIVATE_INITIAL.version, digest)
    fun change(kind: String, id: DomainId, body: BranchActivityBodyV1): AggregateChange =
        AggregateChange(branchActivityKey(kind, id), null, LocalBranchActivityProfileV1.schema(kind),
            branchActivityEncode(BranchActivityRecordV1(scope, kind, id, Revision(1), producer, recordedAt,
                policyRevision, body), BranchActivityRecordV1.serializer()))
    return listOf(change(LocalBranchActivityProfileV1.ACTIVE, scope.branchId, BranchActivityBodyV1.Active(reference)),
        change(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId, BranchActivityBodyV1.Publication(request)))
        .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
}

/** Exact stored shape; partial, foreign or malformed rows are actual failures, not absence. */
internal fun decodeBranchActivityRecord(scope: BranchScope, row: StoredAggregate): BranchActivityRecordV1 {
    return decodeBranchActivityRecord(scope, row.key, row.revision, row.schema, row.payload, row.digest)
}

/** Same exact decoder for a literal retained tuple; this does not construct a current row. */
internal fun decodeBranchActivityRecord(scope: BranchScope, key: AggregateKey, generation: Revision,
    schema: String, payload: StoredBytes, digest: PayloadDigest): BranchActivityRecordV1 {
    check(key.kind in setOf(LocalBranchActivityProfileV1.ACTIVE, LocalBranchActivityProfileV1.PUBLICATION) &&
        schema == LocalBranchActivityProfileV1.schema(key.kind) && generation == Revision(1) &&
        branchActivityDigest(payload) == digest)
    val record = branchActivityDecode(payload, BranchActivityRecordV1.serializer())
    check(record.scope == scope && record.kind == key.kind && record.id == key.id &&
        record.storageRevision == generation && record.producer.commandName == LocalBranchActivityProfileV1.ACTIVATE_INITIAL.name &&
        record.producer.commandVersion == LocalBranchActivityProfileV1.ACTIVATE_INITIAL.version)
    when (val body = record.body) {
        is BranchActivityBodyV1.Active -> check(record.kind == LocalBranchActivityProfileV1.ACTIVE &&
            record.id == scope.branchId && body.reference.company == scope.company())
        is BranchActivityBodyV1.Publication -> {
            val signed = branchActivitySigned(body.request)
            check(record.kind == LocalBranchActivityProfileV1.PUBLICATION && record.id == signed.definition.publicationId &&
                signed.definition.company == scope.company())
        }
    }
    return record
}

/**
 * Whole literal committed shape, not source authority or a row/count/hash certificate.
 * Signature support is checked separately after the selected companion graph is complete.
 * Native original intake is a different authority and stays unsupported by this profile.
 */
internal fun requireBranchActivityProducerShape(evidence: RetainedCommandEvidence): LocalSignedCompanyActivityProfileV1 {
    check(evidence.nativeOriginalProvenance == null && evidence.committed &&
        evidence.command == LocalBranchActivityProfileV1.ACTIVATE_INITIAL && evidence.jobs.isEmpty() &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest &&
        branchActivityDigest(evidence.result) == evidence.resultHash)
    val request = branchActivityDecode(evidence.normalized, LocalActivateInitialBranchActivityV1.serializer(),
        LocalBranchActivityProfileV1.MAX_INPUT_BYTES)
    val signed = branchActivitySigned(request)
    check(signed.definition.company == evidence.scope.company())
    val reference = branchActivityReference(signed)
    val changes = branchActivityChanges(evidence.scope, evidence.operationId, evidence.originalActorId,
        evidence.semanticDigest, evidence.recordedAt, evidence.policyRevision, request, reference)
    val writes = evidence.aggregateWrites
    check(writes.size == changes.size && writes.zip(changes).all { (actual, expected) ->
        actual.key == expected.key && actual.storageGeneration == Revision(1) && actual.schema == expected.schema &&
            actual.payload == expected.payload && actual.payloadDigest == branchActivityDigest(expected.payload)
    })
    val expectedClaims = branchActivityClaims(evidence.scope, reference)
    val claims = evidence.claimChanges
    check(claims.size == expectedClaims.size && claims.zip(expectedClaims).all { (actual, expected) ->
        actual.action == RetainedClaimAction.ACQUIRE && actual.key == expected.key && actual.ownerId == expected.ownerId
    })
    check(evidence.result == branchActivitySuccess(branchActivityApplied(evidence.scope, reference, request,
        evidence.operationId, evidence.originalActorId, evidence.policyRevision, evidence.recordedAt)))
    return signed
}

internal fun requireBranchActivityProducer(evidence: RetainedCommandEvidence,
    signatures: CompanyPublicationSignatureVerifier) {
    requireBranchActivitySource(evidence.nativeOriginalProvenance == null)
    val signed = requireBranchActivityProducerShape(evidence)
    requireBranchActivitySource(authenticBranchActivity(signatures, evidence.scope, signed))
}

internal fun requireBranchActivityStoredWrite(row: StoredAggregate, record: BranchActivityRecordV1,
    original: RetainedCommandEvidence) {
    requireBranchActivityStoredWrite(record.scope, row.key, row.revision, row.schema, row.payload, row.digest, record, original)
}

/** Literal original AFTER comparison, without promoting the tuple to a current aggregate. */
internal fun requireBranchActivityStoredWrite(scope: BranchScope, key: AggregateKey, generation: Revision,
    schema: String, payload: StoredBytes, digest: PayloadDigest, record: BranchActivityRecordV1,
    original: RetainedCommandEvidence) {
    check(record.scope == scope)
    check(original.scope == record.scope && original.operationId == record.producer.operationId &&
        original.originalActorId == record.producer.actorId && original.command.name == record.producer.commandName &&
        original.command.version == record.producer.commandVersion && original.semanticDigest == record.producer.semanticDigest &&
        original.recordedAt == record.recordedAt)
    if (original.nativeOriginalProvenance == null) check(original.policyRevision == record.permissionPolicyRevision)
    val write = original.aggregateWrites.singleOrNull { it.key == key } ?: error("Branch activity producer row lost")
    check(write.storageGeneration == generation && write.schema == schema && write.payload == payload &&
        write.payloadDigest == digest)
}

internal data class BranchActivityRowV1(val row: StoredAggregate, val record: BranchActivityRecordV1)

internal data class LoadedBranchActivityV1(
    val active: BranchActivityRowV1,
    val publication: BranchActivityRowV1,
    val signed: LocalSignedCompanyActivityProfileV1,
) {
    val reference: LocalBranchActivityReferenceV1 get() = (active.record.body as BranchActivityBodyV1.Active).reference
    fun applied(): LocalAppliedBranchActivityV1 = branchActivityApplied(active.record.scope, reference,
        (publication.record.body as BranchActivityBodyV1.Publication).request, active.record.producer.operationId,
        active.record.producer.actorId, active.record.permissionPolicyRevision, active.record.recordedAt)
}

/** Same-loan fixed-key reads; a missing claimed row/original is never safe recreation. */
internal class CurrentBranchActivityReader(private val view: CommandReadView,
    private val signatures: CompanyPublicationSignatureVerifier) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(branchActivityResource(view.scope.branchId))
    var observedActiveRevision: Revision? = null
        private set
    private val rows: MutableMap<AggregateKey, BranchActivityRowV1?> = mutableMapOf()
    private val signedSources: MutableList<LocalSignedCompanyActivityProfileV1> = mutableListOf()
    private var unsupportedOriginal: Boolean = false

    suspend fun row(kind: String, id: DomainId): BranchActivityRowV1? {
        val key = branchActivityKey(kind, id)
        resources += branchActivityResource(id)
        if (key in rows) return rows.getValue(key)
        check(rows.size < 3)
        val stored = view.aggregate(key)
        if (kind == LocalBranchActivityProfileV1.ACTIVE) observedActiveRevision = stored?.revision
        val claim = when (kind) {
            LocalBranchActivityProfileV1.ACTIVE -> branchActivityBranchClaim(view.scope)
            LocalBranchActivityProfileV1.PUBLICATION -> branchActivityPublicationClaim(id)
            else -> error("Unknown branch activity kind")
        }
        val owner = view.claim(claim)
        if (stored == null) {
            check(owner == null)
            rows[key] = null
            return null
        }
        val record = decodeBranchActivityRecord(view.scope, stored)
        resources += branchActivityRecordResources(record)
        val original = view.retainedCommand(record.producer.operationId) ?: error("Branch activity producer lost")
        requireBranchActivityStoredWrite(stored, record, original)
        if (original.nativeOriginalProvenance == null) signedSources += requireBranchActivityProducerShape(original)
        else unsupportedOriginal = true
        when (val body = record.body) {
            is BranchActivityBodyV1.Active -> check(owner == body.reference.publicationId)
            is BranchActivityBodyV1.Publication -> check(owner == id && view.claim(branchActivityBranchClaim(view.scope)) == id)
        }
        return BranchActivityRowV1(stored, record).also { rows[key] = it }
    }

    suspend fun active(): LoadedBranchActivityV1? {
        val active = row(LocalBranchActivityProfileV1.ACTIVE, view.scope.branchId) ?: return null
        val reference = (active.record.body as BranchActivityBodyV1.Active).reference
        val publication = row(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Applied branch activity publication lost")
        val signed = branchActivitySigned((publication.record.body as BranchActivityBodyV1.Publication).request)
        check(branchActivityReference(signed) == reference && active.record.producer == publication.record.producer &&
            active.record.recordedAt == publication.record.recordedAt &&
            active.record.permissionPolicyRevision == publication.record.permissionPolicyRevision)
        return LoadedBranchActivityV1(active, publication, signed)
    }

    /** Called only after the entire selected fixed-key row/claim/original graph is checked. */
    fun requireAuthenticSources() {
        requireBranchActivitySource(!unsupportedOriginal)
        signedSources.forEach { requireBranchActivitySource(authenticBranchActivity(signatures, view.scope, it)) }
    }
}

private class BranchActivityActivationHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterCommandHandler {
    override val key: CommandKey = LocalBranchActivityProfileV1.ACTIVATE_INITIAL
    override val writableKinds: Set<String> get() = setOf(LocalBranchActivityProfileV1.ACTIVE, LocalBranchActivityProfileV1.PUBLICATION)
    override val claimNamespaces: Set<String> get() = setOf(LocalBranchActivityProfileV1.BRANCH_CLAIM, LocalBranchActivityProfileV1.PUBLICATION_CLAIM)

    override fun normalize(input: StoredBytes): StoredBytes = branchActivityEncode(
        branchActivityDecode(input, LocalActivateInitialBranchActivityV1.serializer(), LocalBranchActivityProfileV1.MAX_INPUT_BYTES),
        LocalActivateInitialBranchActivityV1.serializer(), LocalBranchActivityProfileV1.MAX_INPUT_BYTES)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = branchActivityDecode(normalized, LocalActivateInitialBranchActivityV1.serializer(), LocalBranchActivityProfileV1.MAX_INPUT_BYTES)
        val signed = branchActivitySigned(request)
        val reference = branchActivityReference(signed)
        val reader = CurrentBranchActivityReader(view, signatures)
        var unavailable = !authenticBranchActivity(signatures, context.scope, signed)
        val previous = reader.active()
        val publication = reader.row(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
        check(publication == null || previous?.publication?.row?.key == publication.row.key)
        try {
            reader.requireAuthenticSources()
        } catch (_: BranchActivitySourceUnavailable) {
            unavailable = true
        }
        val required = branchActivityActivationRequirement(context.scope, reader.observedActiveRevision,
            context.operationId, context.semanticDigest)
        val footprint = branchActivityCommandResources(context.scope, context.originalActorId, context.operationId, reference) + reader.resources
        val selectedPrevious = previous
        val sourceUnavailable = unavailable
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> get() = footprint.toSet()
            override val allowsActionApproval: Boolean = false
            override val admissionRestriction: PreparedMutationAdmissionRestriction? =
                if (sourceUnavailable) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null

            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
                    authorization.isBoundTo(required, context.originalActorId) && !sourceUnavailable)
                selectedPrevious?.let { return branchActivityAlreadyActivated(reference, it.applied()) }
                val applied = branchActivityApplied(context.scope, reference, request, context.operationId,
                    context.originalActorId, authorization.policyRevision, recordedAt)
                return ProposedOutcome.Apply(branchActivitySuccess(applied), branchActivityChanges(context.scope,
                    context.operationId, context.originalActorId, context.semanticDigest, recordedAt,
                    authorization.policyRevision, request, reference), branchActivityClaims(context.scope, reference))
            }
        }
    }
}

private class BranchActivityCurrentReadHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterReadHandler {
    override val key: ReadKey = LocalBranchActivityProfileV1.READ_CURRENT
    override fun normalize(input: StoredBytes): StoredBytes = branchActivityEncode(
        branchActivityDecode(input, LocalBranchActivityQueryV1.serializer(), LocalBranchActivityProfileV1.MAX_QUERY_BYTES),
        LocalBranchActivityQueryV1.serializer(), LocalBranchActivityProfileV1.MAX_QUERY_BYTES)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        branchActivityDecode(normalized, LocalBranchActivityQueryV1.serializer(), LocalBranchActivityProfileV1.MAX_QUERY_BYTES)
        val reader = CurrentBranchActivityReader(view, signatures)
        var selected: LoadedBranchActivityV1? = null
        val result = try {
            selected = reader.active()
            reader.requireAuthenticSources()
            if (selected == null) LocalBranchActivityReadResultV1(LocalBranchActivityReadStatusV1.ABSENT, null)
            else LocalBranchActivityReadResultV1(LocalBranchActivityReadStatusV1.APPLIED, selected.applied())
        } catch (_: BranchActivitySourceUnavailable) {
            LocalBranchActivityReadResultV1(LocalBranchActivityReadStatusV1.SOURCE_UNAVAILABLE, null)
        }
        val required = AuthorizationRequirement(Permission(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER),
            context.scope, ActionBinding(context.scope.branchId, reader.observedActiveRevision ?: Revision(0),
                context.semanticDigest, reasonCode = "branch.activity.read_current"))
        val selectedSigned = selected?.signed
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> get() = listOf(required)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().approvalId == null &&
                    authorizations.single().isBoundTo(required, context.actorId))
                val visible = if (selectedSigned != null && !authenticBranchActivity(signatures, context.scope, selectedSigned))
                    LocalBranchActivityReadResultV1(LocalBranchActivityReadStatusV1.SOURCE_UNAVAILABLE, null)
                else result
                return branchActivityEncode(visible, LocalBranchActivityReadResultV1.serializer())
            }
        }
    }
}
