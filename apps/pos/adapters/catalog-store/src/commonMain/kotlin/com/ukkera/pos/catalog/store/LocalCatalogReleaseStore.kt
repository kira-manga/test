package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A documentary release is not an assortment, price, stock or purchasing-policy grant. */
public class LocalCatalogReleaseServices(private val publications: LocalCompanyBusinessPublications?) {
    public val commands: List<MasterCommandHandler> = listOf(CatalogReleaseActivationHandler(publications))
    public val reads: List<MasterReadHandler> = listOf(CatalogReleaseReadHandler(publications))
    internal val history: RuntimeHistoryBusinessProducer = LocalCatalogReleaseHistoryProducer(publications)
}

@Serializable
internal data class CatalogReleaseProducer(val operationId: OperationId, val actorId: DomainId,
    val command: String, val semanticDigest: PayloadDigest)
@Serializable
internal sealed interface CatalogReleaseBody {
    @Serializable @SerialName("ACTIVE") data class Active(val reference: LocalCatalogReleaseReferenceV1) : CatalogReleaseBody
    @Serializable @SerialName("PUBLICATION") data class Publication(val request: LocalActivateCatalogReleaseV1) : CatalogReleaseBody
}
@Serializable
internal data class CatalogReleaseRecord(val scope: BranchScope, val kind: String, val id: DomainId,
    val storageRevision: Revision, val producer: CatalogReleaseProducer, val recordedAt: UtcInstant,
    val employeePolicyRevision: Revision, val body: CatalogReleaseBody) {
    override fun toString(): String = "CatalogReleaseRecord([REDACTED])"
}
internal const val RELEASE_BRANCH_CLAIM = "catalog.release.branch"
internal const val RELEASE_PUBLICATION_CLAIM = "catalog.release.publication"
internal const val RELEASE_VERSION_CLAIM = "catalog.release.version"
internal fun catalogReleaseKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun catalogReleaseBranchClaim(scope: BranchScope): ClaimKey = ClaimKey(RELEASE_BRANCH_CLAIM, scope.branchId.value)
internal fun catalogReleasePublicationClaim(id: DomainId): ClaimKey = ClaimKey(RELEASE_PUBLICATION_CLAIM, id.value)
internal fun catalogReleaseVersionClaim(value: LocalCatalogReleaseReferenceV1): ClaimKey =
    ClaimKey(RELEASE_VERSION_CLAIM, "${value.catalogId.value}/${value.revision.value}")
internal fun releaseResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
internal fun catalogReleaseResources(value: LocalSignedCatalogReleaseV1): Set<RecoveryResourceKey> = buildSet {
    val body = value.definition
    add(releaseResource(body.catalogId)); add(releaseResource(body.publicationId)); add(releaseResource(value.signingKeyId))
    body.previous?.let { add(releaseResource(it.catalogId)); add(releaseResource(it.publicationId)) }
    for (member in body.members) {
        add(releaseResource(member.publicationId)); add(releaseResource(member.productId)); add(releaseResource(member.skuId))
        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, member.skuId))
    }
}
internal fun decodeCatalogReleaseRecord(scope: BranchScope, row: StoredAggregate): CatalogReleaseRecord {
    check(row.schema == LocalCatalogReleaseProfileV1.schema(row.key.kind) && sha256(row.payload.copyBytes()) == row.digest)
    val record = catalogReleaseDecode(row.payload, CatalogReleaseRecord.serializer())
    check(record.scope == scope && record.kind == row.key.kind && record.id == row.key.id &&
        record.storageRevision == row.revision && row.revision >= Revision(1) &&
        catalogReleaseEncode(record, CatalogReleaseRecord.serializer()) == row.payload)
    check(when (record.body) {
        is CatalogReleaseBody.Active -> record.kind == LocalCatalogReleaseProfileV1.ACTIVE && record.id == scope.branchId
        is CatalogReleaseBody.Publication -> record.kind == LocalCatalogReleaseProfileV1.PUBLICATION &&
            record.id == record.body.request.publication.definition.publicationId && record.storageRevision == Revision(1)
    })
    return record
}
internal fun catalogReleaseRequirement(scope: BranchScope, catalogId: DomainId, expected: Revision?,
    operationId: OperationId, digest: PayloadDigest): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope,
        ActionBinding(catalogId, expected ?: Revision(0), digest, operationId, "catalog.release.activate"))
internal fun catalogReleaseClaims(scope: BranchScope, reference: LocalCatalogReleaseReferenceV1,
    firstActivation: Boolean): List<ClaimChange> = buildList {
    add(ClaimChange.Acquire(catalogReleasePublicationClaim(reference.publicationId), reference.publicationId))
    add(ClaimChange.Acquire(catalogReleaseVersionClaim(reference), reference.publicationId))
    if (firstActivation) add(ClaimChange.Acquire(catalogReleaseBranchClaim(scope), reference.catalogId))
}.sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })
internal fun catalogReleaseResult(code: String, reference: LocalCatalogReleaseReferenceV1, storageRevision: Revision?): StoredBytes =
    catalogReleaseEncode(LocalCatalogReleaseResultV1(code, reference.catalogId, reference.publicationId, storageRevision), LocalCatalogReleaseResultV1.serializer())
internal fun catalogReleaseChange(scope: BranchScope, operationId: OperationId, actorId: DomainId,
    digest: PayloadDigest, now: UtcInstant, policyRevision: Revision, kind: String, id: DomainId,
    before: Revision?, body: CatalogReleaseBody): AggregateChange = AggregateChange(catalogReleaseKey(kind, id), before,
    LocalCatalogReleaseProfileV1.schema(kind), catalogReleaseEncode(CatalogReleaseRecord(scope, kind, id,
        before?.next() ?: Revision(1), CatalogReleaseProducer(operationId, actorId, LocalCatalogReleaseProfileV1.ACTIVATE.name, digest),
        now, policyRevision, body), CatalogReleaseRecord.serializer()))
internal fun catalogReleaseApply(scope: BranchScope, operationId: OperationId, actorId: DomainId, digest: PayloadDigest,
    now: UtcInstant, policyRevision: Revision, request: LocalActivateCatalogReleaseV1, before: Revision?): ProposedOutcome.Apply {
    val reference = localCatalogReleaseReference(request.publication.definition)
    return ProposedOutcome.Apply(catalogReleaseResult("catalog.release.activated", reference, before?.next() ?: Revision(1)), listOf(
        catalogReleaseChange(scope, operationId, actorId, digest, now, policyRevision, LocalCatalogReleaseProfileV1.ACTIVE,
            scope.branchId, before, CatalogReleaseBody.Active(reference)),
        catalogReleaseChange(scope, operationId, actorId, digest, now, policyRevision, LocalCatalogReleaseProfileV1.PUBLICATION,
            reference.publicationId, null, CatalogReleaseBody.Publication(request)))
        .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value }),
        catalogReleaseClaims(scope, reference, before == null))
}

/** Literal retained effect comparison, not a substitute for the native original join. */
internal fun requireCatalogReleaseProducer(evidence: RetainedCommandEvidence, publications: LocalCompanyBusinessPublications?) {
    check(evidence.committed && evidence.command == LocalCatalogReleaseProfileV1.ACTIVATE &&
        evidence.nativeOriginalProvenance == null && evidence.jobs.isEmpty() && evidence.aggregateWrites.size == 2 &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
    val request = LocalCatalogReleaseCodecV1.decode(evidence.normalized)
    check(LocalCatalogReleaseCodecV1.encode(request) == evidence.normalized)
    check(authenticCatalogRelease(publications, evidence.scope, request.publication))
    val expected = catalogReleaseApply(evidence.scope, evidence.operationId, evidence.originalActorId, evidence.semanticDigest,
        evidence.recordedAt, evidence.policyRevision, request, request.expectedActiveStorageRevision)
    check(evidence.result == expected.result && evidence.aggregateWrites.zip(expected.changes).all { (actual, wanted) ->
        actual.key == wanted.key && actual.schema == wanted.schema && actual.payload == wanted.payload &&
            actual.payloadDigest == sha256(wanted.payload.copyBytes()) &&
            actual.storageGeneration == (wanted.expectedRevision?.next() ?: Revision(1)) })
    check(evidence.claimChanges.size == expected.claims.size && evidence.claimChanges.zip(expected.claims).all { (actual, wanted) ->
        actual.action == RetainedClaimAction.ACQUIRE && actual.key == wanted.key && actual.ownerId == wanted.ownerId })
}
internal fun authenticCatalogRelease(publications: LocalCompanyBusinessPublications?, scope: BranchScope,
    signed: LocalSignedCatalogReleaseV1): Boolean {
    if (signed.definition.company != scope.company()) return false
    val bytes = LocalCatalogReleaseCodecV1.canonicalPublication(signed.definition)
    return try { publications?.trust?.verify(scope.company(), signed.signingKeyId, bytes, signed.signatureHex) == true }
    finally { bytes.fill(0) }
}

/** Supplied lists are used only after a genuine current/original native owner selected them. */
internal class CatalogReleaseNativeCut private constructor(
    val releases: Map<DomainId, LocalSignedCatalogReleaseV1>, val skus: Map<DomainId, LocalSignedSkuV1>,
) {
    fun requiresCandidate(signed: LocalSignedCatalogReleaseV1) {
        check(releases[signed.definition.publicationId] == signed) { "Catalog release native original unavailable" }
        check(releases.values.none { it.definition.publicationId == signed.definition.catalogId ||
            it.definition.catalogId == signed.definition.publicationId }) { "Catalog release coordinate identity changed" }
        val history = releases.values.filter { it.definition.catalogId == signed.definition.catalogId }.map { it.definition }
        check(localCatalogReleaseHistoryRejection(history) == null) { "Catalog release native sequence unavailable" }
        for (release in history) {
            check(localCatalogReleaseMemberRejection(release, skus.values.toList()) == null) { "Catalog release chain members unavailable" }
        }
    }
    fun resources(signed: LocalSignedCatalogReleaseV1): Set<RecoveryResourceKey> = buildSet {
        releases.values.filter { it.definition.catalogId == signed.definition.catalogId }.forEach { addAll(catalogReleaseResources(it)) }
        val chain = releases.values.filter { it.definition.catalogId == signed.definition.catalogId }
        val members = chain.flatMap { it.definition.members }
        val productIds = members.map { it.productId }.toSet()
        val skuIds = members.map { it.skuId }.toSet() + skus.values.filter {
            it.definition.productId in productIds || it.definition.productDefinition?.id in productIds
        }.map { it.definition.skuId }
        val relevant = skus.values.filter { it.definition.productId in productIds ||
            it.definition.productDefinition?.id in productIds || it.definition.skuId in skuIds }
        addAll(localCatalogProductComparisonResources(relevant.map { it.definition }))
        relevant.forEach { add(releaseResource(it.signingKeyId)); addAll(localCatalogUnitResources(it.definition)) }
    }
    companion object {
        fun read(scope: BranchScope, publications: LocalCompanyBusinessPublications?,
            outputs: List<CompanyBusinessPublicationOutput>): CatalogReleaseNativeCut {
            val owner = checkNotNull(publications) { "Catalog release native owner unavailable" }
            check(owner.trust.company == scope.company() && outputs.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
                outputs.all { it.proposal.company == scope.company() && it.rootFingerprint == owner.trust.rootFingerprint } &&
                outputs.map { it.proposal.publicationId }.distinct().size == outputs.size &&
                outputs.map { it.originalActionId }.distinct().size == outputs.size &&
                outputs.map { it.signingKeyId }.distinct().size == outputs.size)
            val releases = mutableMapOf<DomainId, LocalSignedCatalogReleaseV1>()
            val skus = mutableMapOf<DomainId, LocalSignedSkuV1>()
            for (output in outputs) when (output.proposal.kind) {
                CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 -> {
                    val value = output.readNativeCatalogRelease()
                    check(authenticCatalogRelease(owner, scope, value))
                    check(releases.put(value.definition.publicationId, value) == null)
                }
                CompanyBusinessPublicationKind.CATALOG_SKU_V1 -> {
                    val value = output.readNativeCompanySku()
                    val bytes = LocalConfigurationCodec.canonicalPublication(value.definition)
                    try { check(owner.trust.verify(scope.company(), value.signingKeyId, bytes, value.signatureHex)) }
                    finally { bytes.fill(0) }
                    check(skus.put(value.definition.publicationId, value) == null)
                }
                else -> Unit
            }
            return CatalogReleaseNativeCut(releases, skus)
        }
        fun current(scope: BranchScope, publications: LocalCompanyBusinessPublications?,
            snapshot: NativeCompanyBusinessPublicationSnapshot?): CatalogReleaseNativeCut {
            val observed = checkNotNull(snapshot) { "Catalog release native membership unavailable" }
            check(observed.company == scope.company() && observed.rootFingerprint == publications?.trust?.rootFingerprint)
            return read(scope, publications, observed.completedPublications)
        }
    }
}
internal data class LoadedCatalogRelease(val active: StoredAggregate, val activeRecord: CatalogReleaseRecord,
    val publication: StoredAggregate, val publicationRecord: CatalogReleaseRecord, val signed: LocalSignedCatalogReleaseV1) {
    val reference: LocalCatalogReleaseReferenceV1 get() = (activeRecord.body as CatalogReleaseBody.Active).reference
}

internal class CurrentCatalogReleaseReader(private val view: CommandReadView, private val publications: LocalCompanyBusinessPublications?,
    private val native: CatalogReleaseNativeCut) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(releaseResource(view.scope.branchId))
    suspend fun row(kind: String, id: DomainId): Pair<StoredAggregate, CatalogReleaseRecord>? {
        resources += releaseResource(id)
        val claim = when (kind) {
            LocalCatalogReleaseProfileV1.ACTIVE -> catalogReleaseBranchClaim(view.scope)
            LocalCatalogReleaseProfileV1.PUBLICATION -> catalogReleasePublicationClaim(id)
            else -> error("Unsupported release row")
        }
        val stored = view.aggregate(catalogReleaseKey(kind, id))
        if (stored == null) { check(view.claim(claim) == null); return null }
        val record = decodeCatalogReleaseRecord(view.scope, stored)
        val original = view.retainedCommand(record.producer.operationId) ?: error("Catalog release producer unavailable")
        requireCatalogReleaseProducer(original, publications)
        check(record.producer == CatalogReleaseProducer(original.operationId, original.originalActorId, original.command.name, original.semanticDigest))
        val write = original.aggregateWrites.single { it.key == stored.key }
        check(write.storageGeneration == stored.revision && write.schema == stored.schema && write.payload == stored.payload && write.payloadDigest == stored.digest)
        resources += releaseResource(record.producer.actorId)
        when (val body = record.body) {
            is CatalogReleaseBody.Active -> check(view.claim(claim) == body.reference.catalogId)
            is CatalogReleaseBody.Publication -> {
                val reference = localCatalogReleaseReference(body.request.publication.definition)
                native.requiresCandidate(body.request.publication)
                resources += catalogReleaseResources(body.request.publication) + native.resources(body.request.publication)
                check(view.claim(claim) == id && view.claim(catalogReleaseVersionClaim(reference)) == id &&
                    view.claim(catalogReleaseBranchClaim(view.scope)) == reference.catalogId)
            }
        }
        return stored to record
    }
    suspend fun active(): LoadedCatalogRelease? {
        val (active, record) = row(LocalCatalogReleaseProfileV1.ACTIVE, view.scope.branchId) ?: return null
        val reference = (record.body as CatalogReleaseBody.Active).reference
        val (publication, retained) = row(LocalCatalogReleaseProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Active catalog release original unavailable")
        val signed = (retained.body as CatalogReleaseBody.Publication).request.publication
        check(localCatalogReleaseReference(signed.definition) == reference && record.producer == retained.producer &&
            record.recordedAt == retained.recordedAt && record.employeePolicyRevision == retained.employeePolicyRevision)
        return LoadedCatalogRelease(active, record, publication, retained, signed)
    }
    suspend fun membersMatch(signed: LocalSignedCatalogReleaseV1): Boolean {
        native.requiresCandidate(signed)
        resources += catalogReleaseResources(signed) + native.resources(signed)
        var matches = true
        for (member in signed.definition.members) {
            val expected = native.skus.getValue(member.publicationId)
            val current = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, member.skuId))
                ?.let { decodeRecord(view.scope, it) }
            val retained = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, member.publicationId))
                ?.let { decodeRecord(view.scope, it) }
            val actualPublication = current?.signedSku()?.definition?.publicationId?.let { id ->
                if (id == member.publicationId) retained else view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, id))
                    ?.let { decodeRecord(view.scope, it) }
            }
            if (!catalogReleaseBranchMemberMatches(expected, current, retained, actualPublication, native, resources)) matches = false
        }
        return matches
    }

}
/** Literal branch copies; absence is not permitted to mask a dangling activated original. */
internal fun catalogReleaseBranchMemberMatches(expected: LocalSignedSkuV1, current: ConfigurationRecord?,
    expectedPublication: ConfigurationRecord?, actualPublication: ConfigurationRecord?, native: CatalogReleaseNativeCut,
    resources: MutableSet<RecoveryResourceKey>): Boolean {
    expectedPublication?.let {
        check(it.revision == Revision(0) && it.signedSku() == expected)
        resources += releaseResource(it.authorizedBy)
    }
    if (current == null) {
        check(expectedPublication == null && actualPublication == null) { "Activated release member lost its branch pointer" }
        return false
    }
    val active = current.signedSku()
    check(native.skus[active.definition.publicationId] == active)
    check(actualPublication != null && actualPublication.revision == Revision(0) && actualPublication.signedSku() == active)
    resources += releaseResource(current.authorizedBy); resources += releaseResource(actualPublication.authorizedBy)
    resources += localCatalogProductComparisonResources(listOf(active.definition)) + localCatalogUnitResources(active.definition)
    resources += releaseResource(active.signingKeyId)
    if (active == expected) check(expectedPublication != null)
    return active == expected && expectedPublication != null
}
internal fun catalogReleaseRefusal(request: LocalActivateCatalogReleaseV1, previous: LoadedCatalogRelease?, existing: Boolean,
    versionUsed: Boolean, membersMatch: Boolean): String? = when {
    previous?.active?.revision != request.expectedActiveStorageRevision -> "catalog.release.stale_revision"
    existing || versionUsed -> "catalog.release.publication_already_used"
    previous != null && previous.reference.catalogId != request.publication.definition.catalogId -> "catalog.release.catalog_identity_changed"
    previous?.let { request.publication.definition.revision <= it.reference.revision } == true -> "catalog.release.stale_release"
    !membersMatch -> "catalog.release.member_source_changed"
    else -> null
}
private class CatalogReleaseActivationHandler(private val publications: LocalCompanyBusinessPublications?) : MasterCommandHandler {
    override val key: CommandKey = LocalCatalogReleaseProfileV1.ACTIVATE
    override val writableKinds: Set<String> = setOf(LocalCatalogReleaseProfileV1.ACTIVE, LocalCatalogReleaseProfileV1.PUBLICATION)
    override val claimNamespaces: Set<String> = setOf(RELEASE_BRANCH_CLAIM, RELEASE_PUBLICATION_CLAIM, RELEASE_VERSION_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = LocalCatalogReleaseCodecV1.encode(LocalCatalogReleaseCodecV1.decode(input))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalCatalogReleaseCodecV1.decode(normalized)
        val signed = request.publication; val reference = localCatalogReleaseReference(signed.definition)
        val required = catalogReleaseRequirement(context.scope, reference.catalogId, request.expectedActiveStorageRevision, context.operationId, context.semanticDigest)
        var held = false; var refusal: String? = null; var previous: LoadedCatalogRelease? = null
        val resources = (catalogReleaseResources(signed) + releaseResource(context.originalActorId) + releaseResource(view.scope.branchId)).toMutableSet()
        try {
            val native = CatalogReleaseNativeCut.current(view.scope, publications, view.originalNativeBusinessPublications())
            native.requiresCandidate(signed)
            val reader = CurrentCatalogReleaseReader(view, publications, native)
            previous = reader.active()
            val existing = reader.row(LocalCatalogReleaseProfileV1.PUBLICATION, reference.publicationId) != null
            val owner = view.claim(catalogReleaseVersionClaim(reference))
            owner?.let {
                val owned = reader.row(LocalCatalogReleaseProfileV1.PUBLICATION, it) ?: error("Release version original lost")
                val named = localCatalogReleaseReference((owned.second.body as CatalogReleaseBody.Publication).request.publication.definition)
                check(named.catalogId == reference.catalogId && named.revision == reference.revision)
            }
            val matching = reader.membersMatch(signed)
            resources += reader.resources
            refusal = catalogReleaseRefusal(request, previous, existing, owner != null, matching)
            if (previous?.active?.revision?.value == Long.MAX_VALUE) held = true
        } catch (_: IllegalStateException) { held = true }
        catch (_: IllegalArgumentException) { held = true }
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> = resources.toSet()
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.AUTHORIZATION)
            override val admissionRestriction: PreparedMutationAdmissionRestriction? = if (held) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
            override val allowsActionApproval: Boolean = false
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(required, context.originalActorId) && !held)
                refusal?.let { return ProposedOutcome.Reject(it, catalogReleaseResult(it, reference, null)) }
                return catalogReleaseApply(context.scope, context.operationId, context.originalActorId, context.semanticDigest,
                    recordedAt, authorization.policyRevision, request, previous?.active?.revision)
            }
        }
    }
}
private class CatalogReleaseReadHandler(private val publications: LocalCompanyBusinessPublications?) : MasterReadHandler {
    override val key: ReadKey = LocalCatalogReleaseProfileV1.READ
    override fun normalize(input: StoredBytes): StoredBytes = LocalCatalogReleaseCodecV1.query(LocalCatalogReleaseCodecV1.decodeQuery(input)).payload
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = LocalCatalogReleaseCodecV1.decodeQuery(normalized)
        val native = CatalogReleaseNativeCut.current(view.scope, publications, publications?.currentCompletedSnapshot())
        val reader = CurrentCatalogReleaseReader(view, publications, native)
        val active = reader.active()
        check(active == null || active.reference.catalogId == query.catalogId) { "Different active catalog identity" }
        val publication = query.publicationId?.let { id -> reader.row(LocalCatalogReleaseProfileV1.PUBLICATION, id)?.second?.body }
            ?.let { (it as CatalogReleaseBody.Publication).request.publication }
        check(publication == null || publication.definition.catalogId == query.catalogId)
        val visible = active?.signed?.takeIf { reader.membersMatch(it) }
        val result = LocalCatalogReleaseReadReplyV1(query.catalogId, active?.active?.revision, visible, publication)
        val requirement = AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), context.scope,
            ActionBinding(query.catalogId, active?.active?.revision ?: Revision(0), context.semanticDigest, reasonCode = "catalog.release.read"))
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                return LocalCatalogReleaseCodecV1.encodeRead(result)
            }
        }
    }
}
