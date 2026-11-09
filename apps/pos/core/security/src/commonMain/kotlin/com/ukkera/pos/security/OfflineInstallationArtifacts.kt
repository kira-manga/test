package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer

/** Only [read] creates this module-private, verified snapshot. No caller-supplied proof flags. */
internal class OfflineVerifiedArtifacts private constructor(
    val roots: OfflineRootPinsWire,
    val rootDigest: PayloadDigest,
    val trust: OfflineTrustStateBody,
    val trustDigest: PayloadDigest,
    val binding: OfflineCustodyBindingBody,
    val bindingDigest: PayloadDigest,
    val lease: OfflineCustodyLeaseBody,
    val leaseDigest: PayloadDigest,
    val source: OfflineSourceBody,
    val sourceDigest: PayloadDigest,
    val delegation: OfflineHumanDelegationBody,
    val delegationDigest: PayloadDigest,
    val approval: OfflineExactRequestApprovalBody,
    val approvalDigest: PayloadDigest,
    private val windows: List<OfflineInstallationUtcWindow>,
) {
    val request: LocalBranchInstallationRequest get() = approval.request

    /**
     * Immutable equality against actual CURRENT native state; observation changes never enrol
     * replacement roots, update the trust generation, adopt a lease or create a new namespace.
     */
    fun validAt(current: OfflineInstallationNativeObservation): Boolean =
        current.scope == source.scope && current.installationId == source.installationId &&
            current.nativeOwnerId == binding.nativeOwnerId &&
            current.rootEnrollmentId == roots.enrollmentId && current.rootRevision == roots.revision &&
            current.rootPinsDigest == rootDigest && current.trustGeneration == trust.generation &&
            current.trustStateDigest == trustDigest && current.custodyBindingDigest == bindingDigest &&
            current.custodyLeaseId == lease.documentId && current.custodyLeaseDigest == leaseDigest &&
            current.storageInstanceId == binding.storageInstanceId && current.storageKeyId == binding.storageKeyId &&
            current.pepperKeyId == binding.pepperKeyId && current.deviceKeyId == binding.deviceKeyId &&
            current.custodyKeyId == binding.custodyKeyId && current.namespace == OfflineInstallationNamespace.FRESH_LIVE &&
            current.freshOriginId == binding.freshOriginId && current.monotonicContextId == binding.monotonicContextId &&
            current.humanId == delegation.humanId && current.humanKeyId == delegation.humanKeyId &&
            current.humanSessionId == lease.humanSessionId && current.issuerContextId == lease.issuerContextId &&
            current.moment.bootId == lease.validFrom.bootId &&
            current.moment.elapsedMillis >= lease.validFrom.elapsedMillis &&
            current.moment.elapsedMillis < lease.expiresAtElapsedMillis && windows.all { it.contains(current.utc) }

    /** Supplemental admission comparison; full policy/configuration commitment stays service-computed. */
    fun matchesDefinition(definition: LocalBranchInstallationDefinition): Boolean {
        if (definition.identity != request.identity) return false
        val actual = definition.masterAdmission
        val expected = source.device
        return actual.scope == source.scope && actual.deviceId == expected.deviceId &&
            actual.revision == Revision(0) && actual.admitted &&
            actual.publicKeyIdentifier == expected.keyIdentifier() && actual.publicFingerprint == expected.fingerprint() &&
            actual.capabilities == expected.capabilities.toSet()
    }

    companion object {
        private const val DAY: Long = 86_400_000
        private const val YEAR: Long = 366 * DAY

        fun read(evidence: OfflineInstallationInstalledEvidence, current: OfflineInstallationNativeObservation): OfflineVerifiedArtifacts {
            val parsed = mutableListOf<OfflineParsedDocument<*>>()
            fun <T : OfflineSignedBody> decode(document: OfflineInstallationSignedDocument,
                serializer: KSerializer<T>, maximum: Int = 8192): OfflineParsedDocument<T> =
                OfflineInstallationWire.decode(document, serializer, maximum).also { parsed.add(it) }
            try {
                // Anchor origin is the separately qualified native installation, never the package.
                val pins = evidence.roots.pins
                check(pins.size in 1..OfflineInstallationFormat.MAX_ROOT_PINS)
                check(pins.map { it.keyId.value } == pins.map { it.keyId.value }.distinct().sorted())
                check(pins.map { it.publicKeyHex }.distinct().size == pins.size)
                check(pins.all { offlinePublicKeySyntax(it.publicKeyHex) && it.validity.atMost(YEAR) &&
                    it.company == current.scope.company() })
                val roots = OfflineRootPinsWire("ukkera.offline-installation/installed-roots/v1",
                    evidence.roots.enrollmentId, evidence.roots.revision, evidence.roots.minimumTrustGeneration,
                    pins.map { OfflineRootPinWire(it.keyId, it.company, it.publicKeyHex, it.validity) })
                val rootBytes = OfflineInstallationWire.encode(OfflineRootPinsWire.serializer(), roots, 8192)
                val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
                check(current.rootEnrollmentId == roots.enrollmentId && current.rootRevision == roots.revision &&
                    current.rootPinsDigest == rootDigest)

                val trustDocument = decode(evidence.trustState, OfflineTrustStateBody.serializer(), 32 * 1024)
                val trust = trustDocument.body
                check(trust.schema == "ukkera.offline-installation/trust-state/v1" && trust.scope == current.scope)
                check(trust.rootEnrollmentId == roots.enrollmentId && trust.rootRevision == roots.revision &&
                    trust.rootPinsDigest == rootDigest && trust.generation >= roots.minimumTrustGeneration &&
                    trust.generation.value < Long.MAX_VALUE && trust.validity.atMost(DAY))
                check(current.trustStateDigest == trustDocument.digest && current.trustGeneration == trust.generation)
                val root = checkNotNull(pins.singleOrNull { it.keyId == trust.issuerKeyId })
                check(root.validity.contains(trust.validity) && root.validity.contains(current.utc) && trust.validity.contains(current.utc))
                check(trustDocument.verify(root.publicKeyHex, OfflineDocumentRole.TRUST_STATE))
                check(trust.issuers.size in 4..OfflineInstallationFormat.MAX_ISSUERS)
                check(trust.issuers.map { it.keyId.value } == trust.issuers.map { it.keyId.value }.distinct().sorted())
                check(trust.issuers.map { it.publicKeyHex }.distinct().size == trust.issuers.size)
                check(trust.issuers.map { it.role }.toSet() == OfflineIssuerRole.entries.toSet())
                check(trust.issuers.all { issuer -> issuer.scope == trust.scope && offlinePublicKeySyntax(issuer.publicKeyHex) &&
                    issuer.validity.atMost(YEAR) && root.validity.contains(issuer.validity) &&
                    pins.none { it.keyId == issuer.keyId || it.publicKeyHex == issuer.publicKeyHex } })
                listOf(trust.revokedKeyIds, trust.revokedDocumentIds, trust.revokedInstallationIds,
                    trust.revokedAssignmentIds, trust.revokedHumanIds).forEach(::requireRevocations)
                check(root.keyId !in trust.revokedKeyIds && trust.documentId !in trust.revokedDocumentIds)

                val windows = mutableListOf(root.validity, trust.validity)
                fun <T : OfflineSignedBody> verify(document: OfflineParsedDocument<T>, role: OfflineDocumentRole,
                    issuerRole: OfflineIssuerRole, schema: String, maximumLifetime: Long) {
                    val body = document.body
                    check(body.schema == schema && body.scope == trust.scope && body.documentId !in trust.revokedDocumentIds)
                    val issuer = checkNotNull(trust.issuers.singleOrNull { it.keyId == body.issuerKeyId && it.role == issuerRole })
                    check(issuer.keyId !in trust.revokedKeyIds && issuer.validity.contains(body.validity) &&
                        body.validity.atMost(maximumLifetime) && issuer.validity.contains(current.utc) && body.validity.contains(current.utc))
                    check(document.verify(issuer.publicKeyHex, role))
                    windows.add(issuer.validity); windows.add(body.validity)
                }

                val bindingDocument = decode(evidence.custodyBinding, OfflineCustodyBindingBody.serializer())
                verify(bindingDocument, OfflineDocumentRole.CUSTODY_BINDING, OfflineIssuerRole.CUSTODY,
                    "ukkera.offline-installation/custody-binding/v1", YEAR)
                val binding = bindingDocument.body
                check(binding.namespace == OfflineInstallationNamespace.FRESH_LIVE && binding.authorityEpoch.value < Long.MAX_VALUE)
                check(offlinePublicKeySyntax(binding.devicePublicKeyHex) && offlinePublicKeySyntax(binding.custodyPublicKeyHex))
                // Constructor checks the existing calibrated-work-factor domain, not qualification.
                PasswordHashParameters(binding.passwordHashIterations)

                val sourceDocument = decode(evidence.source, OfflineSourceBody.serializer())
                verify(sourceDocument, OfflineDocumentRole.SOURCE, OfflineIssuerRole.SOURCE,
                    "ukkera.offline-installation/source/v1", 31 * DAY)
                val source = sourceDocument.body
                source.device.requireValid()
                check(source.configurationProfile == "ukkera.fresh-live/master-schema-6/required-password/v1")
                check(source.authorityEpoch.value < Long.MAX_VALUE && source.installationId == binding.installationId &&
                    source.assignmentId == binding.assignmentId && source.authorityEpoch == binding.authorityEpoch &&
                    source.device.deviceId == binding.masterDeviceId && source.device.keyId == binding.deviceKeyId &&
                    source.device.publicKeyHex == binding.devicePublicKeyHex && source.storageInstanceId == binding.storageInstanceId &&
                    source.storageKeyId == binding.storageKeyId && source.pepperKeyId == binding.pepperKeyId &&
                    source.custodyReference == bindingDocument.digest)
                check(source.installationId !in trust.revokedInstallationIds && source.assignmentId !in trust.revokedAssignmentIds &&
                    source.responsibleHumanId !in trust.revokedHumanIds)
                val freshOrigin = reference("ukkera.offline-installation/fresh-origin/v1", binding,
                    listOf(binding.installationId.value, binding.freshOriginId.value, "FRESH_LIVE"))
                val monotonicContext = reference("ukkera.offline-installation/monotonic-context/v1", binding,
                    listOf(binding.monotonicContextId.value))
                check(source.freshHistoryOriginReference == freshOrigin && source.monotonicContextReference == monotonicContext)

                val delegationDocument = decode(evidence.humanDelegation, OfflineHumanDelegationBody.serializer())
                verify(delegationDocument, OfflineDocumentRole.HUMAN_DELEGATION, OfflineIssuerRole.HUMAN_DELEGATION,
                    "ukkera.offline-installation/human-delegation/v1", 31 * DAY)
                val delegation = delegationDocument.body
                check(delegation.action == "INSTALL_EXACT_LOCAL_BRANCH" && delegation.installationId == source.installationId &&
                    delegation.assignmentId == source.assignmentId && delegation.masterDeviceId == source.device.deviceId &&
                    delegation.authorityEpoch == source.authorityEpoch && delegation.sourceId == source.documentId &&
                    delegation.sourceDigest == sourceDocument.digest && delegation.humanId == source.responsibleHumanId &&
                    source.validity.contains(delegation.validity) &&
                    offlinePublicKeySyntax(delegation.humanPublicKeyHex))

                val approvalDocument = decode(evidence.exactRequestApproval, OfflineExactRequestApprovalBody.serializer())
                verify(approvalDocument, OfflineDocumentRole.EXACT_REQUEST_APPROVAL, OfflineIssuerRole.EXACT_REQUEST_APPROVAL,
                    "ukkera.offline-installation/exact-request-approval/v1", 7 * DAY)
                val approval = approvalDocument.body
                val identity = LocalBranchInstallationIdentity(source.scope, source.installationId, source.assignmentId,
                    source.device.deviceId, source.authorityEpoch, source.firstActorId, delegation.humanId, delegationDocument.digest,
                    LocalInstallationSourceKind.AUTHENTICATED_OFFLINE_PACKAGE, source.documentId, sourceDocument.digest,
                    source.device.digest(), source.storageInstanceId, source.storageKeyId, source.pepperKeyId,
                    bindingDocument.digest, freshOrigin, monotonicContext)
                check(approval.delegationId == delegation.documentId && approval.request.identity == identity &&
                    delegation.validity.contains(approval.validity))
                // This is an AUTHENTICATED exact digest. The Room service, not this verifier, owns
                // the complete definition codecs and computes the submitted request independently.

                val leaseDocument = decode(evidence.custodyLease, OfflineCustodyLeaseBody.serializer())
                verify(leaseDocument, OfflineDocumentRole.CUSTODY_LEASE, OfflineIssuerRole.CUSTODY,
                    "ukkera.offline-installation/custody-lease/v1", 15 * 60_000L)
                val lease = leaseDocument.body
                check(lease.installationId == source.installationId && lease.nativeOwnerId == binding.nativeOwnerId &&
                    lease.custodyBindingDigest == bindingDocument.digest && lease.rootPinsDigest == rootDigest &&
                    lease.trustGeneration == trust.generation && lease.trustStateDigest == trustDocument.digest &&
                    binding.validity.contains(lease.validity) && lease.expiresAtElapsedMillis > lease.validFrom.elapsedMillis &&
                    lease.expiresAtElapsedMillis - lease.validFrom.elapsedMillis <= 15 * 60_000L &&
                    lease.humanId == delegation.humanId && lease.humanKeyId == delegation.humanKeyId &&
                    lease.humanPublicKeyHex == delegation.humanPublicKeyHex)
                val operationalIds = listOf(source.device.keyId, delegation.humanKeyId, binding.custodyKeyId,
                    binding.storageKeyId, binding.pepperKeyId)
                val publicKeys = listOf(source.device.publicKeyHex, delegation.humanPublicKeyHex, binding.custodyPublicKeyHex)
                check(operationalIds.distinct().size == operationalIds.size && publicKeys.distinct().size == publicKeys.size)
                check(operationalIds.none { id -> id in trust.revokedKeyIds || pins.any { it.keyId == id } || trust.issuers.any { it.keyId == id } })
                check(publicKeys.none { key -> pins.any { it.publicKeyHex == key } || trust.issuers.any { it.publicKeyHex == key } })
                check(parsed.map { it.body.documentId }.distinct().size == parsed.size)

                return OfflineVerifiedArtifacts(roots, rootDigest, trust, trustDocument.digest, binding, bindingDocument.digest,
                    lease, leaseDocument.digest, source, sourceDocument.digest, delegation, delegationDocument.digest,
                    approval, approvalDocument.digest, windows.toList()).also { check(it.validAt(current)) }
            } finally { parsed.forEach { it.clear() } }
        }

        private fun requireRevocations(values: List<DomainId>) {
            check(values.size <= OfflineInstallationFormat.MAX_REVOCATIONS_PER_KIND)
            check(values.map { it.value } == values.map { it.value }.distinct().sorted())
        }

        private fun reference(domain: String, binding: OfflineCustodyBindingBody, suffix: List<String>): PayloadDigest {
            val bytes = offlineFrameStrings(domain, listOf(binding.scope.organizationId.value, binding.scope.companyId.value,
                binding.scope.branchId.value, binding.nativeOwnerId.value, binding.storageInstanceId.value) + suffix)
            return try { sha256(bytes) } finally { bytes.fill(0) }
        }
    }
}
