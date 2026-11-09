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
import kotlin.coroutines.cancellation.CancellationException

/** Genuine Master documentary retention. No stock, money, claim eligibility or sale promise. */
public class LocalReturnRightsActivationServices(signatures: CompanyPublicationSignatureVerifier) {
    private val commandValues: List<MasterCommandHandler> = listOf(ReturnRightsActivationHandler(signatures))
    private val readValues: List<MasterReadHandler> = listOf(ReturnRightsReadHandler(signatures))
    public val commands: List<MasterCommandHandler> get() = commandValues.toList()
    public val reads: List<MasterReadHandler> get() = readValues.toList()
    internal val history: RuntimeHistoryBusinessProducer = LocalReturnRightsActivationHistoryProducer(signatures)
}

@Serializable
internal data class ReturnRightsProducerV1(
    val operationId: OperationId,
    val actorId: DomainId,
    val commandName: String,
    val commandVersion: Int,
    val semanticDigest: PayloadDigest,
)

@Serializable
internal sealed interface ReturnRightsBodyV1 {
    @Serializable @SerialName("ACTIVE")
    data class Active(val reference: LocalReturnRightsReferenceV1) : ReturnRightsBodyV1
    @Serializable @SerialName("PUBLICATION")
    data class Publication(val request: LocalActivateReturnRightsV1) : ReturnRightsBodyV1
}

@Serializable
internal data class ReturnRightsRecordV1(
    val scope: BranchScope,
    val kind: String,
    val id: DomainId,
    val storageRevision: Revision,
    val producer: ReturnRightsProducerV1,
    val recordedAt: UtcInstant,
    val permissionPolicyRevision: Revision,
    val body: ReturnRightsBodyV1,
) {
    override fun toString(): String = "ReturnRightsRecordV1([REDACTED])"
}

internal fun returnRightsKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun returnRightsBranchClaim(scope: BranchScope): ClaimKey =
    ClaimKey(LocalReturnRightsActivationProfileV1.POLICY_CLAIM, scope.branchId.value)
internal fun returnRightsPublicationClaim(id: DomainId): ClaimKey =
    ClaimKey(LocalReturnRightsActivationProfileV1.PUBLICATION_CLAIM, id.value)
internal fun returnRightsVersionClaim(reference: LocalReturnRightsReferenceV1): ClaimKey =
    ClaimKey(LocalReturnRightsActivationProfileV1.VERSION_CLAIM, "${reference.policyId.value}/${reference.companyRevision.value}")
internal fun returnRightsResource(id: DomainId): RecoveryResourceKey =
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)

internal fun returnRightsCommandResources(scope: BranchScope, actor: DomainId, operation: OperationId,
    reference: LocalReturnRightsReferenceV1): Set<RecoveryResourceKey> =
    setOf(returnRightsResource(scope.branchId), returnRightsResource(actor), returnRightsResource(DomainId(operation.value)),
        returnRightsResource(reference.policyId), returnRightsResource(reference.publicationId),
        returnRightsResource(reference.signingKeyId))

internal fun returnRightsRecordResources(record: ReturnRightsRecordV1): Set<RecoveryResourceKey> {
    val reference = when (val body = record.body) {
        is ReturnRightsBodyV1.Active -> body.reference
        is ReturnRightsBodyV1.Publication -> returnRightsReference(returnRightsSigned(body.request))
    }
    return returnRightsCommandResources(record.scope, record.producer.actorId, record.producer.operationId, reference) +
        returnRightsResource(record.id)
}

internal fun returnRightsSigned(request: LocalActivateReturnRightsV1): LocalSignedReturnRightsPolicy =
    LocalReturnRightsPublicationCodec.decodeSignedPublication(request.signedPublication)

internal fun authenticReturnRights(signatures: CompanyPublicationSignatureVerifier, scope: BranchScope,
    signed: LocalSignedReturnRightsPolicy): Boolean = signed.definition.company == scope.company() &&
    LocalReturnRightsPublicationCodec.verify(signed, signatures)

/** Unknown/negative trust is not an authentically replayable negative signature verdict. */
internal class ReturnRightsSourceUnavailable : RuntimeException("Local return rights source unavailable")
internal fun requireReturnRightsSource(available: Boolean) {
    if (!available) throw ReturnRightsSourceUnavailable()
}

/** Malformed or partial protected material is never absence or an ordinary business refusal. */
internal class ReturnRightsStorageUnavailable : IllegalStateException("Local return rights storage unavailable")
internal fun requireReturnRightsStored(valid: Boolean) {
    if (!valid) throw ReturnRightsStorageUnavailable()
}

internal fun returnRightsNextActive(expected: Revision): Revision {
    requireReturnRightsStored(expected.value < Long.MAX_VALUE)
    return expected.next()
}

internal fun returnRightsActivationRequirement(scope: BranchScope, expected: Revision, operation: OperationId,
    digest: PayloadDigest): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope,
        ActionBinding(scope.branchId, expected, digest, operation, "return.rights.activate"))

internal fun returnRightsActivationClaims(scope: BranchScope, reference: LocalReturnRightsReferenceV1,
    initial: Boolean): List<ClaimChange> =
    (listOf(ClaimChange.Acquire(returnRightsPublicationClaim(reference.publicationId), reference.publicationId),
        ClaimChange.Acquire(returnRightsVersionClaim(reference), reference.publicationId)) +
        if (initial) listOf(ClaimChange.Acquire(returnRightsBranchClaim(scope), reference.policyId)) else emptyList())
        .sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })

internal fun returnRightsChanges(scope: BranchScope, operation: OperationId, actor: DomainId,
    digest: PayloadDigest, recordedAt: UtcInstant, policyRevision: Revision,
    request: LocalActivateReturnRightsV1, reference: LocalReturnRightsReferenceV1): List<AggregateChange> {
    val producer = ReturnRightsProducerV1(operation, actor, LocalReturnRightsActivationProfileV1.ACTIVATE.name,
        LocalReturnRightsActivationProfileV1.ACTIVATE.version, digest)
    val before = request.expectedActiveRevision.takeUnless { it == Revision(0) }
    fun change(kind: String, id: DomainId, expected: Revision?, generation: Revision,
        body: ReturnRightsBodyV1): AggregateChange = AggregateChange(returnRightsKey(kind, id), expected,
        LocalReturnRightsActivationProfileV1.schema(kind), returnRightsEncode(ReturnRightsRecordV1(scope, kind, id,
            generation, producer, recordedAt, policyRevision, body), ReturnRightsRecordV1.serializer()))
    return listOf(change(LocalReturnRightsActivationProfileV1.ACTIVE, scope.branchId, before,
        returnRightsNextActive(request.expectedActiveRevision), ReturnRightsBodyV1.Active(reference)),
        change(LocalReturnRightsActivationProfileV1.PUBLICATION, reference.publicationId, null,
            Revision(1), ReturnRightsBodyV1.Publication(request)))
        .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
}

internal fun returnRightsSuccess(reference: LocalReturnRightsReferenceV1, activeRevision: Revision): StoredBytes =
    returnRightsEncode(LocalReturnRightsActivationResultV1(LocalReturnRightsActivationProfileV1.ACTIVATED,
        reference.policyId, reference.publicationId, activeRevision, reference), LocalReturnRightsActivationResultV1.serializer())

internal fun returnRightsRejected(reference: LocalReturnRightsReferenceV1, code: String): ProposedOutcome.Reject {
    require(code in setOf(LocalReturnRightsActivationProfileV1.REVISION_CONFLICT,
        LocalReturnRightsActivationProfileV1.PUBLICATION_ALREADY_USED,
        LocalReturnRightsActivationProfileV1.POLICY_SEQUENCE_REQUIRED))
    return ProposedOutcome.Reject(code, returnRightsEncode(LocalReturnRightsActivationResultV1(code,
        reference.policyId, reference.publicationId, null, reference), LocalReturnRightsActivationResultV1.serializer()))
}

/** Exact first ordinary refusal after complete positive signed-source closure. */
internal fun returnRightsActivationRefusal(request: LocalActivateReturnRightsV1,
    reference: LocalReturnRightsReferenceV1, previous: LoadedReturnRightsV1?,
    publicationUsed: Boolean, versionUsed: Boolean): String? {
    if (request.expectedActiveRevision != (previous?.active?.row?.revision ?: Revision(0)))
        return LocalReturnRightsActivationProfileV1.REVISION_CONFLICT
    if (publicationUsed) return LocalReturnRightsActivationProfileV1.PUBLICATION_ALREADY_USED
    val prior = previous?.publication?.reference
    if (versionUsed || prior != null && (reference.policyId != prior.policyId ||
            prior.companyRevision.value == Long.MAX_VALUE || reference.companyRevision != prior.companyRevision.next()))
        return LocalReturnRightsActivationProfileV1.POLICY_SEQUENCE_REQUIRED
    return null
}

internal fun decodeReturnRightsRecord(scope: BranchScope, row: StoredAggregate): ReturnRightsRecordV1 {
    requireReturnRightsStored(row.key.kind in setOf(LocalReturnRightsActivationProfileV1.ACTIVE,
        LocalReturnRightsActivationProfileV1.PUBLICATION) && row.schema == LocalReturnRightsActivationProfileV1.schema(row.key.kind) &&
        row.revision.value >= 1 && returnRightsDigest(row.payload) == row.digest)
    val record = try { returnRightsDecode(row.payload, ReturnRightsRecordV1.serializer()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { throw ReturnRightsStorageUnavailable() }
    requireReturnRightsStored(record.scope == scope && record.kind == row.key.kind && record.id == row.key.id &&
        record.storageRevision == row.revision && record.producer.commandName == LocalReturnRightsActivationProfileV1.ACTIVATE.name &&
        record.producer.commandVersion == LocalReturnRightsActivationProfileV1.ACTIVATE.version)
    when (val body = record.body) {
        is ReturnRightsBodyV1.Active -> requireReturnRightsStored(record.kind == LocalReturnRightsActivationProfileV1.ACTIVE &&
            record.id == scope.branchId && body.reference.company == scope.company())
        is ReturnRightsBodyV1.Publication -> {
            val signed = returnRightsSigned(body.request)
            requireReturnRightsStored(record.kind == LocalReturnRightsActivationProfileV1.PUBLICATION &&
                record.storageRevision == Revision(1) && record.id == signed.definition.publicationId &&
                signed.definition.company == scope.company())
        }
    }
    return record
}

/** Whole canonical original producer, not a hash/count certificate or today's active pointer. */
internal fun requireReturnRightsProducerShape(evidence: RetainedCommandEvidence): LocalSignedReturnRightsPolicy {
    requireReturnRightsSource(evidence.nativeOriginalProvenance == null)
    requireReturnRightsStored(evidence.committed && evidence.command == LocalReturnRightsActivationProfileV1.ACTIVATE &&
        evidence.jobs.isEmpty() && semanticDigest(evidence.scope, evidence.originalActorId, evidence.command,
            evidence.normalized) == evidence.semanticDigest && returnRightsDigest(evidence.result) == evidence.resultHash)
    val request = try {
        returnRightsDecode(evidence.normalized, LocalActivateReturnRightsV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { throw ReturnRightsStorageUnavailable() }
    val signed = returnRightsSigned(request)
    requireReturnRightsStored(signed.definition.company == evidence.scope.company())
    val reference = returnRightsReference(signed)
    val changes = returnRightsChanges(evidence.scope, evidence.operationId, evidence.originalActorId,
        evidence.semanticDigest, evidence.recordedAt, evidence.policyRevision, request, reference)
    val writes = evidence.aggregateWrites
    requireReturnRightsStored(writes.size == changes.size && writes.zip(changes).all { (actual, expected) ->
        actual.key == expected.key && actual.storageGeneration == (expected.expectedRevision ?: Revision(0)).next() &&
            actual.schema == expected.schema && actual.payload == expected.payload &&
            actual.payloadDigest == returnRightsDigest(expected.payload)
    })
    val expectedClaims = returnRightsActivationClaims(evidence.scope, reference, request.expectedActiveRevision == Revision(0))
    val claims = evidence.claimChanges
    requireReturnRightsStored(claims.size == expectedClaims.size && claims.zip(expectedClaims).all { (actual, expected) ->
        actual.action == RetainedClaimAction.ACQUIRE && actual.key == expected.key && actual.ownerId == expected.ownerId
    })
    requireReturnRightsStored(evidence.result == returnRightsSuccess(reference, returnRightsNextActive(request.expectedActiveRevision)))
    return signed
}

internal fun requireReturnRightsStoredWrite(row: StoredAggregate, record: ReturnRightsRecordV1,
    original: RetainedCommandEvidence) {
    requireReturnRightsSource(original.nativeOriginalProvenance == null)
    requireReturnRightsStored(original.scope == record.scope && original.operationId == record.producer.operationId &&
        original.originalActorId == record.producer.actorId && original.command.name == record.producer.commandName &&
        original.command.version == record.producer.commandVersion && original.semanticDigest == record.producer.semanticDigest &&
        original.recordedAt == record.recordedAt && original.policyRevision == record.permissionPolicyRevision)
    val write = original.aggregateWrites.singleOrNull { it.key == row.key } ?: throw ReturnRightsStorageUnavailable()
    requireReturnRightsStored(write.storageGeneration == row.revision && write.schema == row.schema &&
        write.payload == row.payload && write.payloadDigest == row.digest)
}

internal data class ReturnRightsRowV1(val row: StoredAggregate, val record: ReturnRightsRecordV1)
internal data class ReturnRightsSelectedRowV1(val row: StoredAggregate, val record: ReturnRightsRecordV1,
    val original: RetainedCommandEvidence)

internal data class LoadedReturnRightsPublicationV1(
    val publication: ReturnRightsRowV1,
    val signed: LocalSignedReturnRightsPolicy,
    val reference: LocalReturnRightsReferenceV1,
) {
    fun activated(): LocalActivatedReturnRightsPolicyV1 {
        val record = publication.record
        val request = (record.body as ReturnRightsBodyV1.Publication).request
        return LocalActivatedReturnRightsPolicyV1(record.scope, reference, request.signedPublication,
            returnRightsNextActive(request.expectedActiveRevision), record.producer.operationId, record.producer.actorId,
            record.permissionPolicyRevision, record.recordedAt)
    }
}

internal data class LoadedReturnRightsV1(val active: ReturnRightsRowV1, val publication: LoadedReturnRightsPublicationV1)

/**
 * Fixed selected source validation shared by the actual current reader and native BEFORE
 * reader. It is not a CommandReadView and supplies no historical writer, session or authority.
 * Publication validation deliberately joins its own retained ACTIVE write, not today's pointer.
 */
internal abstract class ReturnRightsSourceReaderV1(protected val scope: BranchScope) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(returnRightsResource(scope.branchId))
    var observedActiveRevision: Revision? = null
        protected set
    private val rows: MutableMap<AggregateKey, ReturnRightsRowV1?> = mutableMapOf()
    private val signedSources: MutableList<LocalSignedReturnRightsPolicy> = mutableListOf()

    protected abstract suspend fun select(key: AggregateKey): ReturnRightsSelectedRowV1?
    protected abstract suspend fun claim(key: ClaimKey): DomainId?
    protected abstract suspend fun requireEmptyProfileKinds()

    suspend fun row(kind: String, id: DomainId): ReturnRightsRowV1? {
        val key = returnRightsKey(kind, id)
        resources += returnRightsResource(id)
        if (key in rows) return rows.getValue(key)
        requireReturnRightsStored(rows.size < 4)
        val selected = select(key)
        val rowClaim = when (kind) {
            LocalReturnRightsActivationProfileV1.ACTIVE -> returnRightsBranchClaim(scope)
            LocalReturnRightsActivationProfileV1.PUBLICATION -> returnRightsPublicationClaim(id)
            else -> throw ReturnRightsStorageUnavailable()
        }
        val owner = claim(rowClaim)
        if (selected == null) {
            requireReturnRightsStored(owner == null)
            rows[key] = null
            return null
        }
        val record = selected.record
        requireReturnRightsStoredWrite(selected.row, record, selected.original)
        signedSources += requireReturnRightsProducerShape(selected.original)
        resources += returnRightsRecordResources(record)
        when (val body = record.body) {
            is ReturnRightsBodyV1.Active -> requireReturnRightsStored(owner == body.reference.policyId)
            is ReturnRightsBodyV1.Publication -> {
                val reference = returnRightsReference(returnRightsSigned(body.request))
                requireReturnRightsStored(owner == id && claim(returnRightsVersionClaim(reference)) == id &&
                    claim(returnRightsBranchClaim(scope)) == reference.policyId)
            }
        }
        return ReturnRightsRowV1(selected.row, record).also { rows[key] = it }
    }

    suspend fun publication(id: DomainId): LoadedReturnRightsPublicationV1? {
        val row = row(LocalReturnRightsActivationProfileV1.PUBLICATION, id) ?: return null
        val signed = returnRightsSigned((row.record.body as ReturnRightsBodyV1.Publication).request)
        return LoadedReturnRightsPublicationV1(row, signed, returnRightsReference(signed))
    }

    suspend fun active(): LoadedReturnRightsV1? {
        val active = row(LocalReturnRightsActivationProfileV1.ACTIVE, scope.branchId)
        if (active == null) {
            // A native-qualified writer also closes every raw claim/row and original effect.
            // No orphaned publication or wrong-key pointer may be called initial absence.
            requireEmptyProfileKinds()
            return null
        }
        val reference = (active.record.body as ReturnRightsBodyV1.Active).reference
        val publication = publication(reference.publicationId) ?: throw ReturnRightsStorageUnavailable()
        val published = publication.publication.record
        requireReturnRightsStored(publication.reference == reference && active.record.producer == published.producer &&
            active.record.recordedAt == published.recordedAt &&
            active.record.permissionPolicyRevision == published.permissionPolicyRevision &&
            active.row.revision == returnRightsNextActive((published.body as ReturnRightsBodyV1.Publication).request.expectedActiveRevision))
        return LoadedReturnRightsV1(active, publication)
    }

    suspend fun versionOwner(reference: LocalReturnRightsReferenceV1): DomainId? {
        val owner = claim(returnRightsVersionClaim(reference)) ?: return null
        val publication = publication(owner) ?: throw ReturnRightsStorageUnavailable()
        requireReturnRightsStored(publication.reference.policyId == reference.policyId &&
            publication.reference.companyRevision == reference.companyRevision)
        return owner
    }

    /** Detached immutable policies only; prepared projections never retain the borrowed view. */
    fun sourcePolicies(): List<LocalSignedReturnRightsPolicy> = signedSources.toList()
}

internal class CurrentReturnRightsReaderV1(private val view: CommandReadView) : ReturnRightsSourceReaderV1(view.scope) {
    override suspend fun select(key: AggregateKey): ReturnRightsSelectedRowV1? {
        val row = view.aggregate(key)
        if (key.kind == LocalReturnRightsActivationProfileV1.ACTIVE) observedActiveRevision = row?.revision
        if (row == null) return null
        val record = decodeReturnRightsRecord(scope, row)
        val original = view.retainedCommand(record.producer.operationId) ?: throw ReturnRightsStorageUnavailable()
        return ReturnRightsSelectedRowV1(row, record, original)
    }

    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)

    override suspend fun requireEmptyProfileKinds() {
        for (kind in setOf(LocalReturnRightsActivationProfileV1.ACTIVE, LocalReturnRightsActivationProfileV1.PUBLICATION)) {
            val page = view.aggregateKeys(kind, null, 1)
            requireReturnRightsStored(page.keys.isEmpty() && page.nextAfter == null)
        }
    }
}

private class ReturnRightsActivationHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterCommandHandler {
    override val key: CommandKey = LocalReturnRightsActivationProfileV1.ACTIVATE
    override val writableKinds: Set<String> get() = setOf(LocalReturnRightsActivationProfileV1.ACTIVE,
        LocalReturnRightsActivationProfileV1.PUBLICATION)
    override val claimNamespaces: Set<String> get() = setOf(LocalReturnRightsActivationProfileV1.POLICY_CLAIM,
        LocalReturnRightsActivationProfileV1.PUBLICATION_CLAIM, LocalReturnRightsActivationProfileV1.VERSION_CLAIM)

    override fun normalize(input: StoredBytes): StoredBytes = returnRightsEncode(
        returnRightsDecode(input, LocalActivateReturnRightsV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES),
        LocalActivateReturnRightsV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = returnRightsDecode(normalized, LocalActivateReturnRightsV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)
        val signed = returnRightsSigned(request)
        val reference = returnRightsReference(signed)
        val reader = CurrentReturnRightsReaderV1(view)
        val previous = reader.active()
        val publicationUsed = reader.publication(reference.publicationId) != null
        val versionUsed = reader.versionOwner(reference) != null
        val sources = reader.sourcePolicies() + signed
        val unavailable = sources.any { !authenticReturnRights(signatures, context.scope, it) }
        val refusal = returnRightsActivationRefusal(request, reference, previous, publicationUsed, versionUsed)
        val overflow = refusal == null && request.expectedActiveRevision.value == Long.MAX_VALUE
        val required = returnRightsActivationRequirement(context.scope, request.expectedActiveRevision,
            context.operationId, context.semanticDigest)
        val footprint = returnRightsCommandResources(context.scope, context.originalActorId, context.operationId, reference) + reader.resources
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> get() = footprint.toSet()
            override val allowsActionApproval: Boolean = false
            override val admissionRestriction: PreparedMutationAdmissionRestriction?
                get() = if (unavailable || overflow || sources.any { !authenticReturnRights(signatures, context.scope, it) })
                    PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null

            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
                    authorization.isBoundTo(required, context.originalActorId))
                requireReturnRightsSource(!unavailable && !overflow && sources.all { authenticReturnRights(signatures, context.scope, it) })
                refusal?.let { return returnRightsRejected(reference, it) }
                return ProposedOutcome.Apply(returnRightsSuccess(reference, returnRightsNextActive(request.expectedActiveRevision)),
                    returnRightsChanges(context.scope, context.operationId, context.originalActorId, context.semanticDigest,
                        recordedAt, authorization.policyRevision, request, reference),
                    returnRightsActivationClaims(context.scope, reference, previous == null))
            }
        }
    }
}

private class ReturnRightsReadHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterReadHandler {
    override val key: ReadKey = LocalReturnRightsActivationProfileV1.READ
    override fun normalize(input: StoredBytes): StoredBytes = returnRightsEncode(
        returnRightsDecode(input, LocalReturnRightsQueryV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_QUERY_BYTES),
        LocalReturnRightsQueryV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_QUERY_BYTES)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = returnRightsDecode(normalized, LocalReturnRightsQueryV1.serializer(), LocalReturnRightsActivationProfileV1.MAX_QUERY_BYTES)
        val reader = CurrentReturnRightsReaderV1(view)
        val result = try {
            val publication = when (query.kind) {
                LocalReturnRightsReadKindV1.CURRENT -> reader.active()?.publication
                LocalReturnRightsReadKindV1.PUBLICATION -> reader.publication(checkNotNull(query.id))
            }
            requireReturnRightsSource(reader.sourcePolicies().all { authenticReturnRights(signatures, context.scope, it) })
            if (publication == null) LocalReturnRightsReadResultV1(LocalReturnRightsReadStatusV1.ABSENT, null)
            else LocalReturnRightsReadResultV1(LocalReturnRightsReadStatusV1.PUBLISHED, publication.activated())
        } catch (_: ReturnRightsSourceUnavailable) {
            LocalReturnRightsReadResultV1(LocalReturnRightsReadStatusV1.SOURCE_UNAVAILABLE, null)
        } catch (_: ReturnRightsStorageUnavailable) {
            LocalReturnRightsReadResultV1(LocalReturnRightsReadStatusV1.SOURCE_UNAVAILABLE, null)
        }
        val sources = reader.sourcePolicies()
        val target = query.id ?: context.scope.branchId
        val generation = if (query.kind == LocalReturnRightsReadKindV1.CURRENT)
            reader.observedActiveRevision ?: Revision(0) else if (result.activation != null) Revision(1) else Revision(0)
        val required = AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), context.scope,
            ActionBinding(target, generation, context.semanticDigest, reasonCode = "return.rights.read"))
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> get() = listOf(required)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().approvalId == null && authorizations.single().isBoundTo(required, context.actorId))
                val visible = if (sources.any { !authenticReturnRights(signatures, context.scope, it) })
                    LocalReturnRightsReadResultV1(LocalReturnRightsReadStatusV1.SOURCE_UNAVAILABLE, null) else result
                return returnRightsEncode(visible, LocalReturnRightsReadResultV1.serializer())
            }
        }
    }
}
