package com.ukkera.pos.security

/**
 * Windows/macOS seven-role provisioner and company key custody over [DesktopKeyCustody]. Each key
 * is created only after its STARTED intent (fresh key ID, current boot session) is durably retained,
 * and is bound to that retained key ID by its alias. A COMPLETE intent carries the SHA-256 proof of
 * the public point (P-256) or of the key-check value (AES/HMAC); every use re-checks it.
 *
 * Crash recovery: a STARTED intent whose alias already exists is adopted only when it was started in
 * the CURRENT boot session (the same single-owner worker that retained it); a key from another boot
 * or a key without any retained intent is never adopted (STATE_UNCERTAIN).
 */
internal class DesktopProvisionerKeysV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val store: SelfOwnedProvisionerRegistryStoreV2,
    private val custody: DesktopKeyCustody,
    private val security: SelfOwnedNativeSecurityV1,
) : SelfOwnedCompanyNativeKeysV2 {
    private val profile get() = environment.profile

    override fun assertOriginalNamespace() {
        environment.requireCurrent()
        store.assertContinuity()
        if (store.bootstrapRecordV2()?.let { it.namespace == store.namespaceIdentityV2() } == false) uncertain()
    }

    override fun assertCompletedOriginalKeys() {
        val retained = store.bootstrapRecordV2() ?: return
        for (intent in retained.keys) {
            val material = originalMaterial(intent.role, intent)
            when (intent.phase) {
                SelfOwnedKeyIntentPhaseV1.COMPLETE -> if (material == null || proof(material) != intent.proof) uncertain()
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> if (material != null) uncertain()
                SelfOwnedKeyIntentPhaseV1.STARTED -> Unit
            }
        }
    }

    override fun prepareOriginalKeys() {
        assertOriginalNamespace()
        for (role in SelfOwnedNativeKeyRoleV1.entries) {
            var intent = bootstrap().key(role)
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) continue
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED) {
                if (originalMaterial(role, intent) != null) uncertain()
                retainOriginal(bootstrap().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                    startedBoot = currentSession())))
                intent = bootstrap().key(role)
            } else if (intent.startedBoot != currentSession()) uncertain()
            val material = originalMaterial(role, intent) ?: createOriginal(role, intent)
            retainOriginal(bootstrap().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                security = security, proof = proof(material))))
        }
        assertAllOriginalKeys()
    }

    override fun assertAllOriginalKeys() {
        if (!bootstrap().complete()) uncertain()
        assertCompletedOriginalKeys()
    }

    override fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray = when (role) {
        SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> checkedOriginal(role)
        else -> uncertain()
    }

    override fun rootBody(): SelfOwnedRootBodyV1 {
        val b = bootstrap()
        return SelfOwnedRootBodyV1(b.rootId, b.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, b.organizationId, b.companyId,
            b.installerId, b.facts.companyName, b.facts.responsiblePersonName, b.facts.installerLogin,
            SelfOwnedOctetsV1(originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)), b.facts.protection, b.actionId)
    }

    override fun rootAnnouncement(): SelfOwnedSignedDocumentV1 =
        signDocument(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT, SelfOwnedInstallationWireV1.encodeRoot(rootBody()))

    override fun issuerDelegation(root: SelfOwnedSignedDocumentV1): SelfOwnedSignedDocumentV1 {
        val b = bootstrap()
        val body = SelfOwnedDelegationBodyV1(b.rootId, JvmP256Primitives.documentDigest(root), b.organizationId, b.companyId,
            b.issuerId, b.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId, SelfOwnedOctetsV1(originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)),
            b.installerId, 0L, 0L, b.facts.protection)
        return signDocument(SelfOwnedDocumentRoleV1.ISSUER_DELEGATION, SelfOwnedInstallationWireV1.encodeDelegation(body))
    }

    override fun signOriginalPreparationV2(body: SelfOwnedInstalledIdentityPreparationV2): SelfOwnedInstalledIdentitySignedDocumentV2 {
        val signed = SelfOwnedInstalledIdentitySignedBodyV2(body)
        val message = signed.copyWireBytes()
        val signature = try { sign(SelfOwnedNativeKeyRoleV1.ISSUER, message) } finally { message.fill(0) }
        return try { SelfOwnedInstalledIdentitySignedDocumentV2(signed, signature) } finally { signature.fill(0) }
    }

    override fun installerTag(message: ByteArray): ByteArray {
        checkedOriginal(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER).fill(0)
        return custody.hmac(originalAlias(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER), message)
    }

    override fun sealRegistryV2(aad: ByteArray, plaintext: ByteArray): SelfOwnedRegistryEnvelopeV2 {
        checkedOriginal(SelfOwnedNativeKeyRoleV1.REGISTRY).fill(0)
        val (nonce, ciphertext) = custody.sealAes(originalAlias(SelfOwnedNativeKeyRoleV1.REGISTRY), aad, plaintext)
        if (nonce.size != SelfOwnedNativeOriginalProfileV2.REGISTRY_NONCE_BYTES || ciphertext.size != plaintext.size + 16) uncertain()
        val message = SelfOwnedNativeProvisionerWireV2.registrySignature(aad, nonce, ciphertext)
        val signature = try { sign(SelfOwnedNativeKeyRoleV1.ISSUER, message) } finally { message.fill(0) }
        return SelfOwnedRegistryEnvelopeV2(nonce, ciphertext, signature)
    }

    override fun openRegistryV2(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV2): ByteArray {
        checkedOriginal(SelfOwnedNativeKeyRoleV1.REGISTRY).fill(0)
        return custody.openAes(originalAlias(SelfOwnedNativeKeyRoleV1.REGISTRY), aad, envelope.nonce, envelope.ciphertext)
    }

    // ---- Company (head) roles ----

    override fun prepareCompanyKeys() {
        assertAllOriginalKeys()
        for (role in LocalCompanyHeadKeyRoleV1.entries) {
            var intent = company().key(role)
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) continue
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED) {
                if (headMaterial(role, intent) != null) uncertain()
                store.retainCompanyBootstrapV2(company().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                    startedBoot = currentSession())))
                intent = company().key(role)
            } else if (intent.startedBoot != currentSession()) uncertain()
            val material = headMaterial(role, intent) ?: createHead(role, intent)
            store.retainCompanyBootstrapV2(company().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                security = security, proof = proof(material))))
        }
        assertAllCompanyKeys()
    }

    override fun assertAllCompanyKeys() {
        assertAllOriginalKeys()
        val c = company()
        if (!c.complete()) uncertain()
        for (intent in c.headKeys) {
            val material = headMaterial(intent.role, intent) ?: uncertain()
            if (proof(material) != intent.proof) uncertain()
        }
    }

    override fun headPoint(role: LocalCompanyHeadKeyRoleV1): ByteArray {
        if (role == LocalCompanyHeadKeyRoleV1.PEPPER) uncertain()
        return checkedHead(role)
    }

    override fun headEnrollmentBodyV2(): LocalCompanyHeadEnrollmentBodyV1 {
        val c = company(); val o = c.original
        return LocalCompanyHeadEnrollmentBodyV1(o.rootId, o.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, o.organizationId, o.companyId,
            o.actionId, c.headAuthorityId, c.headEnrollmentId, c.headName, c.headLogin, c.lifetime,
            c.key(LocalCompanyHeadKeyRoleV1.POLICY).keyId, SelfOwnedOctetsV1(headPoint(LocalCompanyHeadKeyRoleV1.POLICY)),
            c.key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyId, SelfOwnedOctetsV1(headPoint(LocalCompanyHeadKeyRoleV1.INSTALLATION)))
    }

    override fun signFreshHeadEnrollmentV2(): LocalCompanyHeadEnrollmentV1 {
        val body = headEnrollmentBodyV2()
        val bytes = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody(body)
        val signature = try { sign(SelfOwnedNativeKeyRoleV1.ROOT, bytes) } finally { bytes.fill(0) }
        return LocalCompanyHeadEnrollmentV1(body, SelfOwnedOctetsV1(signature))
    }

    override fun headTag(message: ByteArray): ByteArray {
        checkedHead(LocalCompanyHeadKeyRoleV1.PEPPER).fill(0)
        return custody.hmac(headAlias(LocalCompanyHeadKeyRoleV1.PEPPER), message)
    }

    override fun signRootEndorsement(message: ByteArray): ByteArray {
        SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage(message)
        return sign(SelfOwnedNativeKeyRoleV1.ROOT, message)
    }

    override fun signInstallationApproval(frame: LocalInstallationApprovalFrameV2): ByteArray {
        val intent = company().key(LocalCompanyHeadKeyRoleV1.INSTALLATION)
        if (frame.kind != LocalInstallationApprovalKindV2.HUMAN || frame.signerKeyId != intent.keyId) uncertain()
        val expected = frame.copySignerPublicKey()
        val point = checkedHead(LocalCompanyHeadKeyRoleV1.INSTALLATION)
        val message = frame.copyWireBytes()
        try {
            if (!point.contentEquals(expected)) uncertain()
            val raw = custody.signP256(headAlias(LocalCompanyHeadKeyRoleV1.INSTALLATION), message)
            try {
                if (raw.size != 64) uncertain()
                val canonical = JvmP256Primitives.canonicalize(raw)
                if (!JvmP256Primitives.verify(point, message, canonical)) { canonical.fill(0); uncertain() }
                return canonical
            } finally { raw.fill(0) }
        } finally { expected.fill(0); point.fill(0); message.fill(0) }
    }

    override fun signInstallationAcknowledgement(frame: LocalInstallationCommitAcknowledgementV2): ByteArray {
        if (frame.rootKeyId != bootstrap().key(SelfOwnedNativeKeyRoleV1.ROOT).keyId) uncertain()
        val message = frame.copyWireBytes()
        return try { sign(SelfOwnedNativeKeyRoleV1.ROOT, message) } finally { message.fill(0) }
    }

    override fun toString(): String = "DesktopProvisionerKeysV2([REDACTED])"

    // ---- Internals ----

    private fun bootstrap(): SelfOwnedBootstrapV2 = store.bootstrapRecordV2() ?: uncertain()
    private fun company(): LocalCompanyAuthorityBootstrapV2 = store.companyBootstrapRecordV2() ?: uncertain()

    private fun retainOriginal(next: SelfOwnedBootstrapV2) {
        val enclosing = store.companyBootstrapRecordV2()
        if (enclosing == null) store.retainBootstrapV2(next) else store.retainCompanyBootstrapV2(enclosing.nextOriginal(next))
    }

    private fun currentSession(): SelfOwnedNativeBootSessionV2 =
        (environment.bootMark as? SelfOwnedBootMark.Session)?.session ?: uncertain()

    private fun originalAlias(role: SelfOwnedNativeKeyRoleV1): String =
        DesktopKeyAliases.provisioner(profile, bootstrap().key(role).keyId)
    private fun headAlias(role: LocalCompanyHeadKeyRoleV1): String = DesktopKeyAliases.head(profile, company().key(role).keyId)

    private fun originalMaterial(role: SelfOwnedNativeKeyRoleV1, intent: SelfOwnedProvisionerKeyIntentV2): ByteArray? {
        val alias = DesktopKeyAliases.provisioner(profile, intent.keyId)
        return when (role) {
            SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> custody.p256Point(alias)
            SelfOwnedNativeKeyRoleV1.REGISTRY, SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> custody.symmetricCheck(alias)
        }
    }

    private fun createOriginal(role: SelfOwnedNativeKeyRoleV1, intent: SelfOwnedProvisionerKeyIntentV2): ByteArray {
        val alias = DesktopKeyAliases.provisioner(profile, intent.keyId)
        return when (role) {
            SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> custody.createP256(alias)
            SelfOwnedNativeKeyRoleV1.REGISTRY -> { custody.createAes(alias); custody.symmetricCheck(alias) ?: uncertain() }
            SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> { custody.createHmac(alias); custody.symmetricCheck(alias) ?: uncertain() }
        }
    }

    private fun headMaterial(role: LocalCompanyHeadKeyRoleV1, intent: LocalCompanyHeadKeyIntentV2): ByteArray? {
        val alias = DesktopKeyAliases.head(profile, intent.keyId)
        return if (role == LocalCompanyHeadKeyRoleV1.PEPPER) custody.symmetricCheck(alias) else custody.p256Point(alias)
    }

    private fun createHead(role: LocalCompanyHeadKeyRoleV1, intent: LocalCompanyHeadKeyIntentV2): ByteArray {
        val alias = DesktopKeyAliases.head(profile, intent.keyId)
        return if (role == LocalCompanyHeadKeyRoleV1.PEPPER) { custody.createHmac(alias); custody.symmetricCheck(alias) ?: uncertain() }
            else custody.createP256(alias)
    }

    /** Material of a COMPLETE original role whose proof still matches; never an unproved key. */
    private fun checkedOriginal(role: SelfOwnedNativeKeyRoleV1): ByteArray {
        val intent = bootstrap().key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE) uncertain()
        val material = originalMaterial(role, intent) ?: uncertain()
        if (proof(material) != intent.proof) { material.fill(0); uncertain() }
        return material
    }

    private fun checkedHead(role: LocalCompanyHeadKeyRoleV1): ByteArray {
        val intent = company().key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE) uncertain()
        val material = headMaterial(role, intent) ?: uncertain()
        if (proof(material) != intent.proof) { material.fill(0); uncertain() }
        return material
    }

    private fun sign(role: SelfOwnedNativeKeyRoleV1, message: ByteArray): ByteArray {
        val point = checkedOriginal(role)
        val raw = custody.signP256(DesktopKeyAliases.provisioner(profile, bootstrap().key(role).keyId), message)
        try {
            if (raw.size != 64) uncertain()
            val canonical = JvmP256Primitives.canonicalize(raw)
            if (!JvmP256Primitives.verify(point, message, canonical)) { canonical.fill(0); uncertain() }
            return canonical
        } finally { raw.fill(0) }
    }

    private fun signDocument(role: SelfOwnedDocumentRoleV1, body: ByteArray): SelfOwnedSignedDocumentV1 {
        val message = SelfOwnedInstallationWireV1.signingMessage(role, body)
        val signature = try { sign(SelfOwnedNativeKeyRoleV1.ROOT, message) } finally { message.fill(0) }
        return try { SelfOwnedSignedDocumentV1(role, body, signature) } finally { signature.fill(0) }
    }

    private fun proof(material: ByteArray): SelfOwnedOctetsV1 = SelfOwnedOctetsV1(JvmP256Primitives.hash(material))
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}
