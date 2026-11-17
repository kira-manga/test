package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.NativeOriginalDomain
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Canonical references only. Neither a decoded demand nor a native B receipt qualifies
 * A's original, a current permission, or financial application. The actual Master owner
 * must separately join its original UNKNOWN and the current resolver's native invocation. */
internal data class LocalForeignResolutionReferencesV1(
    val authority: AuthorityIdentity,
    val master: LocalPhysicalCatalogueBinding,
    val resolver: LocalPhysicalCatalogueBinding,
    val resolverActorId: DomainId,
    val resolverIntentJournalId: DomainId,
    val sourceDomainProfile: String,
    val sourceClientDeviceId: DomainId,
    val sourceClientAllocationId: DomainId,
    val sourceRequirementDigest: PayloadDigest,
    val sourceOriginalDigest: PayloadDigest,
    val resolutionSemanticDigest: PayloadDigest,
) {
    val bytes: StoredBytes get() = StoredBytes(journalFrame(listOf(
        journalText(TAG), journalText(authority.assignmentId.value), journalText(authority.deviceId.value),
        journalText(authority.epoch.value.toString()), journalOwnerBytes(master), journalOwnerBytes(resolver),
        journalText(resolverActorId.value), journalText(resolverIntentJournalId.value), journalText(sourceDomainProfile),
        journalText(sourceClientDeviceId.value), journalText(sourceClientAllocationId.value),
        journalText(sourceRequirementDigest.value), journalText(sourceOriginalDigest.value),
        journalText(resolutionSemanticDigest.value),
    ), LocalPhysicalJournalFormatV1.MAX_REQUIREMENT_BYTES))

    fun allocationId(requirement: LocalPhysicalJournalRequirementV1): DomainId {
        val hash = journalBodyDigest(StoredBytes(journalFrame(listOf(
            TAG, resolver.catalogueId.value, sourceClientAllocationId.value, requirement.resolutionOperationId.value,
        ).map(::journalText)))).value.take(32)
        return DomainId("${hash.take(8)}-${hash.substring(8, 12)}-${hash.substring(12, 16)}-${hash.substring(16, 20)}-${hash.substring(20)}")
    }

    fun validate(requirement: LocalPhysicalJournalRequirementV1, domain: LocalPhysicalForeignResolutionDomain) {
        require(requirement.domainProfile == domain.domainProfile && requirement.command == domain.command &&
            sourceDomainProfile == domain.source.domainProfile &&
            master.role == LocalPhysicalSpoolRole.MASTER && resolver.role == LocalPhysicalSpoolRole.CLIENT &&
            master.scope == requirement.scope && resolver.scope == requirement.scope &&
            authority.deviceId == master.deviceId && requirement.masterDeviceId == master.deviceId &&
            requirement.clientDeviceId == resolver.deviceId && sourceClientDeviceId != resolver.deviceId &&
            requirement.clientAllocationId == allocationId(requirement) &&
            requirement.canonicalDomainRequirement == bytes && requirement.targetBinding == journalBodyDigest(bytes))
    }

    fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes) {
        val request = MutationRequest(requirement.resolutionOperationId, requirement.command, body)
        require(distributedResolutionRequest(request) && distributedResolutionActor(request) == resolverActorId &&
            semanticDigest(requirement.scope, resolverActorId, requirement.command, body) == resolutionSemanticDigest)
    }

    companion object {
        private const val TAG = "ukkera.foreign-linked-resolution/references-v1"
        fun read(body: StoredBytes): LocalForeignResolutionReferencesV1 = body.copyBytes().withSpoolBytes { wire ->
            val f = journalFields(wire, 14)
            try {
                require(journalString(f[0]) == TAG)
                LocalForeignResolutionReferencesV1(
                    AuthorityIdentity(DomainId(journalString(f[1])), DomainId(journalString(f[2])), Revision(journalString(f[3]).toLong())),
                    journalReadOwner(f[4]), journalReadOwner(f[5]), DomainId(journalString(f[6])),
                    DomainId(journalString(f[7])), journalString(f[8]), DomainId(journalString(f[9])),
                    DomainId(journalString(f[10])), PayloadDigest(journalString(f[11])), PayloadDigest(journalString(f[12])),
                    PayloadDigest(journalString(f[13])),
                ).also { require(it.bytes == body) }
            } finally { f.forEach { it.fill(0) } }
        }
    }
}

/** Distinct native retention purpose. All three source operation IDs remain A's actual
 * original IDs; B is explicitly the counterparty device/key and resolver, never A's owner.
 * This domain cannot create a Start, original observation, or payment instruction. */
internal class LocalPhysicalForeignResolutionDomain private constructor(val source: NativeOriginalDomain) : LocalPhysicalJournalDomain {
    override val domainProfile: String = "ukkera.foreign-linked-resolution/v1/" + source.name.lowercase()
    override val command: CommandKey get() = source.command
    override val startKind: LocalPhysicalJournalStartKind get() = error("physical.foreign_resolution.no_start")
    override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) { references(requirement) }
    override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): LocalPhysicalOriginalKind =
        error("physical.foreign_resolution.no_original")
    override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1, original: StoredBytes,
        resolverActorId: DomainId, resolverAuthorization: StoredBytes, body: StoredBytes): Unit =
        error("physical.foreign_resolution.distinct_retention_required")

    fun references(requirement: LocalPhysicalJournalRequirementV1): LocalForeignResolutionReferencesV1 =
        LocalForeignResolutionReferencesV1.read(requirement.canonicalDomainRequirement).also { it.validate(requirement, this) }

    companion object {
        private val installed = NativeOriginalDomain.entries.map(::LocalPhysicalForeignResolutionDomain)
        fun fromRequirement(requirement: LocalPhysicalJournalRequirementV1): LocalPhysicalForeignResolutionDomain? =
            installed.singleOrNull { it.command == requirement.command && it.domainProfile == requirement.domainProfile }
        fun forSource(requirement: LocalPhysicalJournalRequirementV1): LocalPhysicalForeignResolutionDomain =
            installed.single { it.command == requirement.command && it.source.domainProfile == requirement.domainProfile }
    }
}

/** Pure native-counterparty plan; callers still need the real B catalogue and an actual
 * freshly authorized Master original. No certificate/authority is manufactured here. */
internal fun foreignResolutionRequirement(source: LocalPhysicalJournalRequirementV1, original: StoredBytes,
    owners: LocalPhysicalDomainOwners, resolver: LocalPhysicalCatalogueBinding, resolverJournalId: DomainId,
    resolverActorId: DomainId, profile: LocalPhysicalSpoolStorageProfileV1,
    request: MutationRequest): LocalPhysicalJournalRequirementV1 {
    val domain = LocalPhysicalForeignResolutionDomain.forSource(source)
    require(request.command == source.command && request.operationId == source.resolutionOperationId &&
        request.actionApprovalId == null && distributedResolutionActor(request) == resolverActorId &&
        owners.master.scope == source.scope && owners.client.deviceId == source.clientDeviceId &&
        owners.master.deviceId == source.masterDeviceId && resolver.scope == source.scope)
    val refs = LocalForeignResolutionReferencesV1(owners.authority, owners.master, resolver, resolverActorId,
        resolverJournalId, source.domainProfile, source.clientDeviceId, source.clientAllocationId,
        journalBodyDigest(source.canonicalBytes), journalBodyDigest(original),
        semanticDigest(source.scope, resolverActorId, request.command, request.payload))
    return LocalPhysicalJournalRequirementV1(source.scope, source.originalActorId, source.intentOperationId,
        source.intentSemanticDigest, source.recordingOperationId, source.resolutionOperationId, source.attemptId,
        domain.domainProfile, source.command, journalBodyDigest(refs.bytes), source.masterAllocationId,
        refs.allocationId(source), source.masterDeviceId, resolver.deviceId,
        source.masterStorageProfile, profile, refs.bytes).also(domain::validateRequirement)
}

internal sealed interface LocalForeignResolutionRead {
    class Read(val body: StoredBytes?) : LocalForeignResolutionRead
    data object Busy : LocalForeignResolutionRead
    data object Held : LocalForeignResolutionRead
}

internal fun foreignResolutionRecord(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): StoredBytes {
    val domain = checkNotNull(LocalPhysicalForeignResolutionDomain.fromRequirement(requirement))
    domain.references(requirement).validateResolution(requirement, body)
    return StoredBytes(journalFrame(listOf(journalText("ukkera.foreign-linked-resolution/retained-v1"),
        journalText(journalBodyDigest(requirement.canonicalBytes).value), body.copyBytes()),
        LocalPhysicalJournalFormatV1.MAX_RESOLUTION_BYTES))
}

internal fun readForeignResolutionRecord(requirement: LocalPhysicalJournalRequirementV1, record: StoredBytes): StoredBytes =
    record.copyBytes().withSpoolBytes { wire ->
        val fields = journalFields(wire, 3)
        try {
            val body = StoredBytes(fields[2])
            require(record == foreignResolutionRecord(requirement, body))
            body
        } finally { fields.forEach { it.fill(0) } }
    }

internal fun foreignResolutionAcknowledgment(requirement: LocalPhysicalJournalRequirementV1, body: StoredBytes): StoredBytes {
    val refs = checkNotNull(LocalPhysicalForeignResolutionDomain.fromRequirement(requirement)).references(requirement)
    refs.validateResolution(requirement, body)
    return StoredBytes(journalFrame(listOf("ukkera.distributed-physical-foreign-resolution/v1",
        requirement.clientAllocationId.value, journalBodyDigest(requirement.canonicalBytes).value,
        refs.sourceRequirementDigest.value, refs.sourceOriginalDigest.value, journalBodyDigest(body).value).map(::journalText), 4_096))
}
