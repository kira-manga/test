package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Original native cut, earlier fully joined writers and literal ordered effects only.
 * Today's branch state, reconstructed read views and supplied histories cannot reopen it. */
internal class LocalCatalogReleaseHistoryProducer(private val publications: LocalCompanyBusinessPublications?) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalCatalogReleaseProfileV1.ACTIVATE)
    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || publications == null) return false
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        val request = LocalCatalogReleaseCodecV1.decode(command.normalized)
        check(LocalCatalogReleaseCodecV1.encode(request) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val signed = request.publication; val reference = localCatalogReleaseReference(signed.definition)
        val native = CatalogReleaseNativeCut.read(command.scope, publications, command.completedNativeBusinessPublicationsBefore())
        native.requiresCandidate(signed)
        if (retained.committed) {
            requireCatalogReleaseProducer(retained, publications)
            command.requireLegacyReadEvidence()
        }
        val reader = HistoricalCatalogReleaseReader(command, publications, native)
        val previous = reader.active()
        val existing = reader.row(LocalCatalogReleaseProfileV1.PUBLICATION, reference.publicationId) != null
        val owner = command.claim(catalogReleaseVersionClaim(reference))
        owner?.let {
            val owned = reader.row(LocalCatalogReleaseProfileV1.PUBLICATION, it) ?: error("Original release version lost")
            val named = localCatalogReleaseReference((owned.second.body as CatalogReleaseBody.Publication).request.publication.definition)
            check(named.catalogId == reference.catalogId && named.revision == reference.revision)
        }
        val matching = reader.membersMatch(signed)
        check(previous?.active?.revision?.value != Long.MAX_VALUE)
        command.requireRequirement(catalogReleaseRequirement(command.scope, reference.catalogId,
            request.expectedActiveStorageRevision, command.operationId, command.semanticDigest))
        val resources = catalogReleaseResources(signed) + releaseResource(command.actorId) + reader.resources
        val refusal = catalogReleaseRefusal(request, previous, existing, owner != null, matching)
        if (refusal != null) {
            if (retained.committed) return false
            command.requireCatalogReleaseRejection(ProposedOutcome.Reject(refusal, catalogReleaseResult(refusal, reference, null)), resources)
            return true
        }
        if (!retained.committed) return false
        command.requireLegacyEffects(catalogReleaseApply(command.scope, command.operationId, command.actorId,
            command.semanticDigest, command.recordedAt, command.policyRevision, request, previous?.active?.revision), resources)
        return true
    }
}

/** Actual core-selected BEFORE sources; does not implement or fabricate CommandReadView. */
internal class HistoricalCatalogReleaseReader(private val command: RuntimeHistoricalCommand,
    private val publications: LocalCompanyBusinessPublications, private val native: CatalogReleaseNativeCut) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(releaseResource(command.scope.branchId))
    suspend fun row(kind: String, id: DomainId): Pair<StoredAggregate, CatalogReleaseRecord>? {
        resources += releaseResource(id)
        val claim = when (kind) {
            LocalCatalogReleaseProfileV1.ACTIVE -> catalogReleaseBranchClaim(command.scope)
            LocalCatalogReleaseProfileV1.PUBLICATION -> catalogReleasePublicationClaim(id)
            else -> error("Unsupported historical release row")
        }
        val selected = command.aggregateSource(catalogReleaseKey(kind, id))
        if (selected == null) { check(command.claim(claim) == null); return null }
        val record = decodeCatalogReleaseRecord(command.scope, selected.row)
        val original = (selected.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("Unsupported historical release producer")
        requireCatalogReleaseProducer(original, publications)
        check(record.producer == CatalogReleaseProducer(original.operationId, original.originalActorId, original.command.name, original.semanticDigest))
        val write = original.aggregateWrites.single { it.key == selected.row.key }
        check(write.storageGeneration == selected.row.revision && write.schema == selected.row.schema &&
            write.payload == selected.row.payload && write.payloadDigest == selected.row.digest)
        resources += releaseResource(record.producer.actorId)
        when (val body = record.body) {
            is CatalogReleaseBody.Active -> check(command.claim(claim) == body.reference.catalogId)
            is CatalogReleaseBody.Publication -> {
                val reference = localCatalogReleaseReference(body.request.publication.definition)
                native.requiresCandidate(body.request.publication)
                resources += catalogReleaseResources(body.request.publication) + native.resources(body.request.publication)
                check(command.claim(claim) == id && command.claim(catalogReleaseVersionClaim(reference)) == id &&
                    command.claim(catalogReleaseBranchClaim(command.scope)) == reference.catalogId)
            }
        }
        return selected.row to record
    }
    suspend fun active(): LoadedCatalogRelease? {
        val (active, record) = row(LocalCatalogReleaseProfileV1.ACTIVE, command.scope.branchId) ?: return null
        val reference = (record.body as CatalogReleaseBody.Active).reference
        val (publication, retained) = row(LocalCatalogReleaseProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Original active catalog release unavailable")
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
            val current = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, member.skuId))?.row
                ?.let { decodeRecord(command.scope, it) }
            val retained = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, member.publicationId))?.row
                ?.let { decodeRecord(command.scope, it) }
            val actualPublication = current?.signedSku()?.definition?.publicationId?.let { id ->
                if (id == member.publicationId) retained else command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, id))?.row
                    ?.let { decodeRecord(command.scope, it) }
            }
            if (!catalogReleaseBranchMemberMatches(expected, current, retained, actualPublication, native, resources)) matches = false
        }
        return matches
    }
}
