package com.ukkera.pos.security

/**
 * Windows/macOS provisioner registry store. The platform supplies only actual file custody of the
 * exact V2 bootstrap, the optional V2 company bootstrap and the retained V2 registry envelope bytes
 * in its OS-protected provisioner namespace; authentication, decryption ordering, transitions and
 * every consistency rule run in the core ([SelfOwnedProvisionerRegistryV2],
 * [SelfOwnedCompanyAuthorityV2], [SelfOwnedDesktopProvisionerV2]). No V1 record is read or adapted.
 *
 * Platform contract for the write methods: each retains the exact bytes atomically (write, flush,
 * fsync, atomic replace, directory fsync) inside the namespace owned by the actual principal, never
 * restores or truncates older bytes, and refuses (throws) on any custody doubt. When a company
 * bootstrap is retained, [bootstrapRecordV2] is exactly its `original`, and the registry envelope
 * holds the company container instead of the bare registry.
 */
internal interface SelfOwnedProvisionerRegistryStoreV2 : SelfOwnedOriginalRegistryStore {
    fun bootstrapRecordV2(): SelfOwnedBootstrapV2?
    /** Exact retained V2 envelope bytes, or null when no registry was retained. */
    fun registryEnvelopeBytes(): ByteArray?
    /** Retained seven-key desktop company bootstrap, or null for a four-key original-only issuer. */
    fun companyBootstrapRecordV2(): LocalCompanyAuthorityBootstrapV2?
    /** Actual OS identity of this provisioner namespace (owner SID/uid, volume, file IDs/inodes). */
    fun namespaceIdentityV2(): SelfOwnedRegistryNamespaceV2
    fun retainBootstrapV2(next: SelfOwnedBootstrapV2)
    fun retainCompanyBootstrapV2(next: LocalCompanyAuthorityBootstrapV2)
    /** Atomically replaces the registry envelope with the exact core-sealed [bytes]. */
    fun replaceRegistryEnvelope(bytes: ByteArray)
    /**
     * Exact retained V2 company ledger envelope bytes of [kind], or null when that ledger was never
     * retained. Same custody contract as [registryEnvelopeBytes]; a store without desktop company
     * ledger custody refuses (UNSUPPORTED_PLATFORM), never reports absence.
     */
    fun companyLedgerEnvelopeBytes(kind: SelfOwnedCompanyLedgerKindV2): ByteArray? =
        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    /** Atomically replaces the [kind] ledger envelope with the exact core-sealed [bytes]. */
    fun replaceCompanyLedgerEnvelope(kind: SelfOwnedCompanyLedgerKindV2, bytes: ByteArray): Unit =
        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    override fun originalBootstrap(): SelfOwnedOriginalBootstrap? = bootstrapRecordV2()?.let { SelfOwnedOriginalBootstrap.V2(it) }
    override fun readOriginalRegistryState(keys: SelfOwnedOriginalProvisionerKeys): SelfOwnedOriginalRegistryState? {
        val desktopKeys = SelfOwnedProvisionerRegistryV2.requireDesktopKeys(keys)
        val bootstrap = bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val company = companyBootstrapRecordV2()
        val bytes = registryEnvelopeBytes() ?: return null
        return try {
            if (company == null) SelfOwnedOriginalRegistryState.V2(SelfOwnedProvisionerRegistryV2.read(bootstrap, bytes, desktopKeys))
            else {
                if (!SelfOwnedProvisionerRegistryV2.sameBootstrap(company.original, bootstrap)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                // Original preparation read only: no credential is authenticated, so no throttle-boot bound.
                SelfOwnedOriginalRegistryState.V2(SelfOwnedCompanyAuthorityV2.read(company, bytes,
                    SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys), null).original)
            }
        } finally { bytes.fill(0) }
    }
}

/**
 * Core-side authenticated V2 registry codec. The issuer key signs
 * `registrySignature(registryAad(bootstrap), nonce, ciphertext)`; that signature is verified BEFORE
 * the platform decrypts with the registry key. The decrypted state must be canonical and consistent
 * with the actual root/issuer points and the exact bootstrap. Every mismatch is STATE_UNCERTAIN.
 */
internal object SelfOwnedProvisionerRegistryV2 {
    fun requireDesktopKeys(keys: SelfOwnedOriginalProvisionerKeys): SelfOwnedProvisionerNativeKeysV2 =
        (keys as? SelfOwnedProvisionerNativeKeysV2)?.takeIf { it.originalKeysFormat == SelfOwnedOriginalFormat.V2 }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)

    fun read(bootstrap: SelfOwnedBootstrapV2, envelopeBytes: ByteArray, keys: SelfOwnedProvisionerNativeKeysV2):
        LocalProvisionerRegistryStateV2 {
        known(bootstrap.complete() && bootstrap.enrollmentStarted)
        keys.assertAllOriginalKeys()
        val envelope = when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(envelopeBytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
            is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val aad = SelfOwnedNativeProvisionerWireV2.registryAad(bootstrap)
        try {
            requireIssuerSignature(keys, aad, envelope)
            val plaintext = keys.openRegistryV2(aad, envelope)
            try {
                val state = when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeRegistry(plaintext)) {
                    is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                    is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val canonical = SelfOwnedNativeProvisionerWireV2.encodeRegistry(state)
                try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
                validate(bootstrap, state, keys)
                keys.assertAllOriginalKeys()
                return state
            } finally { plaintext.fill(0) }
        } finally { aad.fill(0); envelope.clear() }
    }

    /** Validates [state] first, then seals and re-verifies the produced envelope. */
    fun seal(bootstrap: SelfOwnedBootstrapV2, state: LocalProvisionerRegistryStateV2,
        keys: SelfOwnedProvisionerNativeKeysV2): ByteArray {
        known(bootstrap.complete() && bootstrap.enrollmentStarted)
        validate(bootstrap, state, keys)
        val bytes = sealUnchecked(bootstrap, state, keys)
        val aad = SelfOwnedNativeProvisionerWireV2.registryAad(bootstrap)
        try {
            val envelope = when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(bytes)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            try { requireIssuerSignature(keys, aad, envelope) } finally { envelope.clear() }
        } catch (failure: Throwable) { bytes.fill(0); throw failure } finally { aad.fill(0) }
        return bytes
    }

    /** Envelope production only (no state rules); used by [seal] after validation. */
    internal fun sealUnchecked(bootstrap: SelfOwnedBootstrapV2, state: LocalProvisionerRegistryStateV2,
        keys: SelfOwnedProvisionerNativeKeysV2): ByteArray {
        val aad = SelfOwnedNativeProvisionerWireV2.registryAad(bootstrap)
        val plaintext = SelfOwnedNativeProvisionerWireV2.encodeRegistry(state)
        try {
            val envelope = keys.sealRegistryV2(aad, plaintext)
            return try { SelfOwnedNativeProvisionerWireV2.encodeRegistryEnvelope(envelope) } finally { envelope.clear() }
        } finally { aad.fill(0); plaintext.fill(0) }
    }

    internal fun requireIssuerSignature(keys: SelfOwnedProvisionerNativeKeysV2, aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV2) {
        val point = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)
        val message = SelfOwnedNativeProvisionerWireV2.registrySignature(aad, envelope.nonce, envelope.ciphertext)
        try { known(JvmP256Primitives.verify(point, message, envelope.signature)) } finally { point.fill(0); message.fill(0) }
    }

    internal fun validate(bootstrap: SelfOwnedBootstrapV2, state: LocalProvisionerRegistryStateV2, keys: SelfOwnedProvisionerNativeKeysV2) {
        // Founding profile: one installer, at most one allocation (as on Android V1).
        known(state.installers.size == 1 && state.allocations.size <= 1 && state.nextSerial == state.allocations.size.toLong() + 1L)
        val rootPoint = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        val issuerPoint = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)
        try {
            val expectedRoot = SelfOwnedInstallationWireV1.encodeRoot(keys.rootBody())
            val actualRoot = state.root.body()
            try { known(expectedRoot.contentEquals(actualRoot)) } finally { expectedRoot.fill(0); actualRoot.fill(0) }
            known(JvmP256Primitives.verifyDocument(state.root, rootPoint) && JvmP256Primitives.verifyDocument(state.delegation, rootPoint))
            val rootBody = keys.rootBody()
            known(rootBody.rootId == bootstrap.rootId && rootBody.organizationId == bootstrap.organizationId &&
                rootBody.companyId == bootstrap.companyId && rootBody.installerId == bootstrap.installerId &&
                rootBody.rootPublicKey.same(rootPoint) && rootBody.protection == bootstrap.facts.protection &&
                rootBody.bootstrapActionId == bootstrap.actionId)
            val delegationBytes = state.delegation.body()
            val delegated = try { SelfOwnedInstallationWireV1.decodeDelegation(delegationBytes) } finally { delegationBytes.fill(0) }
            val rootDigest = JvmP256Primitives.documentDigest(state.root)
            val delegationDigest = JvmP256Primitives.documentDigest(state.delegation)
            val account = state.installers.single()
            known(account.installerId == bootstrap.installerId && account.login == bootstrap.facts.installerLogin &&
                account.name == bootstrap.facts.responsiblePersonName)
            known(delegated.rootId == bootstrap.rootId && delegated.rootDocumentDigest == rootDigest &&
                delegated.organizationId == bootstrap.organizationId && delegated.companyId == bootstrap.companyId &&
                delegated.issuerId == bootstrap.issuerId && delegated.issuerKeyId == bootstrap.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId &&
                delegated.issuerPublicKey.same(issuerPoint) && delegated.installerId == account.installerId &&
                delegated.installerRevision == account.credentialRevision && delegated.delegationRevision == account.delegationRevision &&
                delegated.protection == bootstrap.facts.protection)
            for (allocation in state.allocations) {
                val body = allocation.body; val scope = body.coordinates.scope
                known(body.rootId == bootstrap.rootId && body.rootDocumentDigest == rootDigest && body.issuerId == bootstrap.issuerId &&
                    body.delegationDigest == delegationDigest && body.installerId == account.installerId &&
                    body.installerRevision == account.credentialRevision && body.delegationRevision == account.delegationRevision &&
                    body.serial == 1L && SelfOwnedInstalledIdentityV2.matches(bootstrap.installedTarget.target, body.target) &&
                    scope.organizationId == bootstrap.organizationId && scope.companyId == bootstrap.companyId)
                allocation.grant?.let { grant ->
                    val signedBody = grant.signedBody.copyWireBytes(); val signature = grant.copySignature()
                    try { known(JvmP256Primitives.verify(issuerPoint, signedBody, signature)) }
                    finally { signedBody.fill(0); signature.fill(0) }
                }
            }
        } finally { rootPoint.fill(0); issuerPoint.fill(0) }
    }


    fun sameBootstrap(left: SelfOwnedBootstrapV2, right: SelfOwnedBootstrapV2): Boolean {
        val a = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(left); val b = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(right)
        return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
    }

    /**
     * Mirror of the Android V1 original transition: first state is revision 0 with issuance enabled,
     * serial 1 and no allocation; later states advance exactly one revision, keep the exact root,
     * delegation, installer identity/credential, never re-enable issuance or an installer, never drop
     * an allocation, and move each allocation at most one phase forward keeping its exact grant.
     */
    fun requireTransition(previous: LocalProvisionerRegistryStateV2?, next: LocalProvisionerRegistryStateV2) {
        if (previous == null) {
            known(next.revision == 0L && next.issuanceEnabled && next.nextSerial == 1L && next.allocations.isEmpty())
            return
        }
        known(next.revision == previous.revision + 1L && sameDocument(next.root, previous.root) &&
            sameDocument(next.delegation, previous.delegation) && (previous.issuanceEnabled || !next.issuanceEnabled))
        val before = previous.installers.single(); val after = next.installers.single()
        known(before.installerId == after.installerId && before.name == after.name && before.login == after.login &&
            before.credentialRevision == after.credentialRevision && before.delegationRevision == after.delegationRevision &&
            before.salt == after.salt && before.verifier == after.verifier && (before.enabled || !after.enabled) &&
            after.throttleBoot >= before.throttleBoot)
        known(next.allocations.size >= previous.allocations.size && next.nextSerial >= previous.nextSerial)
        for (old in previous.allocations) {
            val updated = next.allocations.singleOrNull { it.body.coordinates.allocationId == old.body.coordinates.allocationId }
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val a = old.body.copyWireBytes(); val b = updated.body.copyWireBytes()
            try { known(a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
            known(updated.phase.ordinal in old.phase.ordinal..old.phase.ordinal + 1)
            old.grant?.let { grant ->
                val retained = updated.grant ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                val c = grant.copyWireBytes(); val d = retained.copyWireBytes()
                try { known(c.contentEquals(d)) } finally { c.fill(0); d.fill(0) }
            }
        }
    }

    private fun sameDocument(left: SelfOwnedSignedDocumentV1, right: SelfOwnedSignedDocumentV1): Boolean {
        val a = left.copyWireBytes(); val b = right.copyWireBytes()
        return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
    }

    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
}
