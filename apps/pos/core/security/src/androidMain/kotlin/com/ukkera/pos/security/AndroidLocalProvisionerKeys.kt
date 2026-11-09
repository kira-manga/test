package com.ukkera.pos.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Four legacy roles, or the explicit seven-role company profile; private keys NEVER export. */
internal class AndroidLocalProvisionerKeys(
    private val environment: AndroidSelfOwnedEnvironment,
    private val registry: AndroidLocalProvisionerRegistry,
    private val work: AndroidOwnedInstallationWork,
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }

    /** Reads only. An orphan prefixed alias forbids creating a new identity from empty files. */
    fun assertOriginalNamespace() {
        guard()
        val bootstrap = registry.bootstrapRecord()
        val company = registry.companyBootstrapRecord()
        val allowed = (if (bootstrap == null) emptyList() else bootstrap.keys
            .filter { it.phase != SelfOwnedKeyIntentPhaseV1.NOT_STARTED }.map { alias(bootstrap, it) }) +
            (company?.headKeys?.filter { it.phase != SelfOwnedKeyIntentPhaseV1.NOT_STARTED }
                ?.map { headAlias(company, it) } ?: emptyList())
        val observed = mutableSetOf<String>()
        val aliases = store.aliases()
        var count = 0
        while (aliases.hasMoreElements()) {
            if (++count > 65_536) keyFailure()
            val next = aliases.nextElement()
            if (next.startsWith(ALIAS_PREFIX)) {
                if (next !in allowed || !observed.add(next) || observed.size > (if (company == null) 4 else 7)) keyFailure()
            }
        }
        if (bootstrap != null) for (intent in bootstrap.keys.filter { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }) {
            if (alias(bootstrap, intent) !in observed) keyFailure()
        }
        if (company != null) for (intent in company.headKeys.filter { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }) {
            if (headAlias(company, intent) !in observed) keyFailure()
        }
        guard()
    }

    fun assertCompletedOriginalKeys() {
        assertOriginalNamespace()
        val original = registry.bootstrapRecord() ?: return
        for (intent in original.keys) if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) checkedKey(intent.role)
        registry.companyBootstrapRecord()?.headKeys?.filter { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
            ?.forEach { checkedHeadKey(it.role) }
        guard()
    }

    /** Explicit founding only; prior original intent is forced BEFORE every native generation. */
    fun prepareOriginalKeys() {
        assertOriginalNamespace()
        for (role in SelfOwnedNativeKeyRoleV1.entries) {
            var original = requireBootstrap()
            var intent = original.key(role)
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) {
                checkedKey(role)
                continue
            }
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.STARTED &&
                environment.bootCount <= requireNotNull(intent.startedBoot)) {
                // A prior Binder generation could still complete on this boot. No replay or
                // alternate alias even if containsAlias happens to report absence now.
                keyFailure()
            }
            val present = store.containsAlias(alias(original, intent))
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED && present) keyFailure()
            if (!present) {
                val started = intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                    startedBoot = environment.bootCount, security = null, proof = SelfOwnedOctetsV1(ByteArray(0)))
                registry.retainBootstrap(original.replaceKey(started))
                original = requireBootstrap(); intent = original.key(role)
                guard()
                if (store.containsAlias(alias(original, intent))) keyFailure()
                try { generate(original, intent) } catch (failure: SelfOwnedFailureV1) { throw failure }
                catch (_: Exception) { keyFailure() }
            }
            // STARTED + actually newer boot may inspect the original existing alias, never
            // overwrite it. Boot count is OS-observed; an epoch/UUID is not this exclusion fact.
            val key = load(original, intent)
            val security = inspect(original, intent, key)
            val proof = createProof(original, intent, key)
            try {
                guard()
                val after = load(original, intent)
                if (inspect(original, intent, after) != security) keyFailure()
                verifyProof(original, intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                    security = security, proof = SelfOwnedOctetsV1(proof)), after)
                registry.retainBootstrap(original.replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                    security = security, proof = SelfOwnedOctetsV1(proof))))
            } finally { proof.fill(0) }
            checkedKey(role)
        }
        assertAllOriginalKeys()
    }

    fun assertAllOriginalKeys() {
        assertOriginalNamespace()
        if (!requireBootstrap().complete()) keyFailure()
        for (role in SelfOwnedNativeKeyRoleV1.entries) checkedKey(role)
        guard()
    }

    /** Public point still comes from the proved original native private key, not an import. */
    fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray {
        require(role == SelfOwnedNativeKeyRoleV1.ROOT || role == SelfOwnedNativeKeyRoleV1.ISSUER)
        checkedKey(role)
        val point = requireBootstrap().key(role).proof.copyBytes()
        try { checkedKey(role); return point } catch (failure: Throwable) { point.fill(0); throw failure }
    }

    fun rootBody(): SelfOwnedRootBodyV1 {
        val original = requireBootstrap()
        val point = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        return try {
            SelfOwnedRootBodyV1(original.rootId, original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId,
                original.organizationId, original.companyId, original.installerId, original.facts.companyName,
                original.facts.responsiblePersonName, original.facts.installerLogin, SelfOwnedOctetsV1(point),
                original.facts.protection, original.actionId)
        } finally { point.fill(0) }
    }

    fun rootAnnouncement(): SelfOwnedSignedDocumentV1 {
        val body = SelfOwnedInstallationWireV1.encodeRoot(rootBody())
        return try {
            val signed = AndroidP256Primitives.signDocument(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT,
                body, checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey)
            checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
            signed
        } finally { body.fill(0) }
    }

    fun issuerDelegation(root: SelfOwnedSignedDocumentV1): SelfOwnedSignedDocumentV1 {
        val original = requireBootstrap()
        val expected = SelfOwnedInstallationWireV1.encodeRoot(rootBody()); val supplied = root.body()
        val rootPoint = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        try {
            if (root.role != SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT || !expected.contentEquals(supplied) ||
                !AndroidP256Primitives.verifyDocument(root, rootPoint)) keyFailure()
        } finally { expected.fill(0); supplied.fill(0); rootPoint.fill(0) }
        val issuerPoint = originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)
        val body = try {
            SelfOwnedInstallationWireV1.encodeDelegation(SelfOwnedDelegationBodyV1(original.rootId,
                AndroidP256Primitives.documentDigest(root), original.organizationId, original.companyId,
                original.issuerId, original.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId, SelfOwnedOctetsV1(issuerPoint),
                original.installerId, 0L, 0L, original.facts.protection))
        } finally { issuerPoint.fill(0) }
        return try {
            val signed = AndroidP256Primitives.signDocument(SelfOwnedDocumentRoleV1.ISSUER_DELEGATION, body,
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey)
            checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
            signed
        } finally { body.fill(0) }
    }

    /** Called by the actual authenticated owner; only the durably retained original body can sign. */
    fun signOriginalPreparation(body: SelfOwnedPreparationBodyV1): SelfOwnedSignedDocumentV1 {
        val state = registry.readState(this) ?: keyFailure()
        val original = state.allocations.singleOrNull { it.body.coordinates.allocationId == body.coordinates.allocationId }
            ?: keyFailure()
        if (!state.issuanceEnabled || original.phase != LocalProvisionerAllocationPhaseV1.SIGNING_INTENT) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        }
        val expected = SelfOwnedInstallationWireV1.encodePreparation(original.body)
        val bytes = SelfOwnedInstallationWireV1.encodePreparation(body)
        try {
            if (!expected.contentEquals(bytes)) keyFailure()
            val signed = AndroidP256Primitives.signDocument(SelfOwnedDocumentRoleV1.NATIVE_PREPARATION,
                bytes, checkedKey(SelfOwnedNativeKeyRoleV1.ISSUER) as PrivateKey)
            checkedKey(SelfOwnedNativeKeyRoleV1.ISSUER)
            return signed
        } finally { expected.fill(0); bytes.fill(0) }
    }

    fun installerTag(message: ByteArray): ByteArray {
        require(message.size <= SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        val tag = hmac(checkedKey(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER) as SecretKey, message)
        try { checkedKey(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER); return tag }
        catch (failure: Throwable) { tag.fill(0); throw failure }
    }

    fun sealRegistry(aad: ByteArray, plaintext: ByteArray): SelfOwnedRegistryEnvelopeV1 {
        require(plaintext.size <= SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, checkedKey(SelfOwnedNativeKeyRoleV1.REGISTRY) as SecretKey)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        var signature: ByteArray? = null
        try {
            if (nonce == null || nonce.size != 12) keyFailure()
            checkedKey(SelfOwnedNativeKeyRoleV1.REGISTRY)
            val message = SelfOwnedInstallationWireV1.registrySignature(aad, nonce, ciphertext)
            signature = try { AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ISSUER) as PrivateKey, message) }
            finally { message.fill(0) }
            checkedKey(SelfOwnedNativeKeyRoleV1.ISSUER)
            return SelfOwnedRegistryEnvelopeV1(nonce.copyOf(), ciphertext.copyOf(), requireNotNull(signature).copyOf())
        } finally { nonce?.fill(0); ciphertext.fill(0); signature?.fill(0) }
    }

    fun openRegistry(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV1): ByteArray {
        val point = originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)
        val message = SelfOwnedInstallationWireV1.registrySignature(aad, envelope.nonce, envelope.ciphertext)
        try {
            if (!AndroidP256Primitives.verify(point, message, envelope.signature)) keyFailure()
        } finally { point.fill(0); message.fill(0) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, checkedKey(SelfOwnedNativeKeyRoleV1.REGISTRY) as SecretKey, GCMParameterSpec(128, envelope.nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(envelope.ciphertext)
        try { checkedKey(SelfOwnedNativeKeyRoleV1.REGISTRY); return plaintext }
        catch (failure: Throwable) { plaintext.fill(0); throw failure }
    }

    fun prepareCompanyKeys() {
        val initial = requireCompanyBootstrap()
        if (initial.original.enrollmentStarted) keyFailure()
        prepareOriginalKeys()
        for (role in LocalCompanyHeadKeyRoleV1.entries) {
            var original = requireCompanyBootstrap()
            var intent = original.key(role)
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) { checkedHeadKey(role); continue }
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.STARTED &&
                environment.bootCount <= requireNotNull(intent.startedBoot)) keyFailure()
            val present = store.containsAlias(headAlias(original, intent))
            if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED && present) keyFailure()
            if (!present) {
                registry.retainCompanyBootstrap(original.replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                    startedBoot = environment.bootCount, security = null, proof = SelfOwnedOctetsV1(ByteArray(0)))))
                original = requireCompanyBootstrap(); intent = original.key(role)
                guard()
                if (store.containsAlias(headAlias(original, intent))) keyFailure()
                try { generateHead(original, intent) } catch (failure: SelfOwnedFailureV1) { throw failure }
                catch (_: Exception) { keyFailure() }
            }
            val key = loadHead(original, intent)
            val security = inspectHead(original, intent, key)
            val proof = headProof(original, intent, key)
            try {
                guard()
                val after = loadHead(original, intent)
                if (inspectHead(original, intent, after) != security) keyFailure()
                val completed = intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                    security = security, proof = SelfOwnedOctetsV1(proof))
                verifyHeadProof(original, completed, after)
                registry.retainCompanyBootstrap(original.replaceKey(completed))
            } finally { proof.fill(0) }
            checkedHeadKey(role)
        }
        assertAllCompanyKeys()
    }

    fun assertAllCompanyKeys() {
        assertAllOriginalKeys()
        if (!requireCompanyBootstrap().complete()) keyFailure()
        for (role in LocalCompanyHeadKeyRoleV1.entries) checkedHeadKey(role)
        guard()
    }

    fun headPoint(role: LocalCompanyHeadKeyRoleV1): ByteArray {
        require(role != LocalCompanyHeadKeyRoleV1.PEPPER)
        checkedHeadKey(role)
        val bytes = requireCompanyBootstrap().key(role).proof.copyBytes()
        try { checkedHeadKey(role); return bytes } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    fun headEnrollmentBody(): LocalCompanyHeadEnrollmentBodyV1 {
        val original = requireCompanyBootstrap()
        val policy = headPoint(LocalCompanyHeadKeyRoleV1.POLICY)
        val installation = headPoint(LocalCompanyHeadKeyRoleV1.INSTALLATION)
        return try {
            LocalCompanyHeadEnrollmentBodyV1(original.original.rootId, original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId,
                original.original.organizationId, original.original.companyId, original.original.actionId,
                original.headAuthorityId, original.headEnrollmentId, original.headName, original.headLogin, original.lifetime,
                original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyId, SelfOwnedOctetsV1(policy),
                original.key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyId, SelfOwnedOctetsV1(installation))
        } finally { policy.fill(0); installation.fill(0) }
    }

    fun signFreshHeadEnrollment(): LocalCompanyHeadEnrollmentV1 {
        assertAllCompanyKeys()
        val original = requireCompanyBootstrap()
        if (original.original.enrollmentStarted || registry.readState(this) != null) keyFailure()
        val body = headEnrollmentBody()
        val bytes = LocalCompanyAuthorityWireV1.encodeEnrollmentBody(body)
        val signature = try { AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, bytes) }
        finally { bytes.fill(0) }
        return try {
            checkedKey(SelfOwnedNativeKeyRoleV1.ROOT); assertAllCompanyKeys()
            LocalCompanyHeadEnrollmentV1(body, SelfOwnedOctetsV1(signature))
        } finally { signature.fill(0) }
    }

    fun headTag(message: ByteArray): ByteArray {
        require(message.size <= SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        val tag = hmac(checkedHeadKey(LocalCompanyHeadKeyRoleV1.PEPPER) as SecretKey, message)
        try { checkedHeadKey(LocalCompanyHeadKeyRoleV1.PEPPER); return tag }
        catch (failure: Throwable) { tag.fill(0); throw failure }
    }

    /** Exact independent head action plus forced original intent; the native P-256 root never signs catalog bytes as Ed25519. */
    fun signBusinessPublicationEndorsement(record: NativeCompanyBusinessPublicationRecordV1,
        approval: AndroidLocalInstallerAuthentication.BusinessPublicationHeadApproval): ByteArray {
        val original = requireCompanyBootstrap()
        val retained = registry.readBusinessPublications(this)?.records?.singleOrNull { it.originalActionId == record.originalActionId }
            ?: keyFailure()
        if (retained != record || record.phase != CompanyBusinessPublicationPhase.SIGNING_INTENT) keyFailure()
        val head = requireNotNull(record.head)
        if (head.accountRevision != approval.accountRevision || head.credentialRevision != approval.credentialRevision ||
            head.boot != approval.boot || head.expiresElapsedMillis != approval.expires) keyFailure()
        val actionBytes = NativeCompanyBusinessPublicationWireV1.actionMessage(record, original)
        val digest = try { sha256(actionBytes) } finally { actionBytes.fill(0) }
        approval.owner.requireBusinessPublicationStillCurrent(approval,
            registry.readCompanyState(this) ?: keyFailure(), record.originalActionId, digest)
        val message = NativeCompanyBusinessPublicationWireV1.endorsementMessage(record, original)
        return try {
            val signature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, message)
            try {
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
                approval.owner.requireBusinessPublicationStillCurrent(approval,
                    registry.readCompanyState(this) ?: keyFailure(), record.originalActionId, digest)
                if (registry.readBusinessPublications(this)?.records?.singleOrNull { it.originalActionId == record.originalActionId } != record) keyFailure()
                signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { message.fill(0) }
    }

    /** Historical verification uses the actual original native root and unchanged Ed25519 content. */
    fun verifyBusinessPublication(record: NativeCompanyBusinessPublicationRecordV1) {
        if (record.phase != CompanyBusinessPublicationPhase.COMPLETED) keyFailure()
        val original = requireCompanyBootstrap()
        if (record.company != CompanyScope(original.original.organizationId, original.original.companyId)) keyFailure()
        val message = NativeCompanyBusinessPublicationWireV1.endorsementMessage(record, original)
        val rootPoint = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        val endorsement = record.rootEndorsement.copyBytes()
        val content = record.content.copyBytes()
        val point = record.publicKey.copyBytes()
        val signature = record.contentSignature.copyBytes()
        try {
            if (!AndroidP256Primitives.verify(rootPoint, message, endorsement) || sha256(content) != record.digest ||
                !nativeBusinessPublicationVerifyEd25519(point, content, signature)) keyFailure()
        } finally {
            message.fill(0); rootPoint.fill(0); endorsement.fill(0); content.fill(0); point.fill(0); signature.fill(0)
        }
    }

    /** Dedicated employee domain under the ORIGINAL root, after exact forced signing intent. */
    fun signEmployeeEnrollmentEndorsement(record: NativeEmployeeEnrollmentRecordV1,
        approval: AndroidLocalInstallerAuthentication.EmployeeEnrollmentHeadApproval): ByteArray {
        val original = requireCompanyBootstrap()
        val retained = registry.readEmployeeEnrollments(this)?.records?.singleOrNull { it.originalActionId == record.originalActionId }
            ?: keyFailure()
        if (retained != record || record.phase != LocalEmployeeEnrollmentPhase.SIGNING_INTENT) keyFailure()
        val head = requireNotNull(record.approvedHead)
        if (head.accountRevision != approval.accountRevision || head.credentialRevision != approval.credentialRevision ||
            head.osBootCount != approval.boot || head.deadlineElapsedMillis != approval.expires ||
            head.retainedRevision != approval.retainedRevision || record.target.rootId != original.original.rootId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        val action = NativeEmployeeEnrollmentWireV1.actionMessage(record)
        val digest = try { sha256(action) } finally { action.fill(0) }
        approval.owner.requireEmployeeEnrollmentStillCurrent(approval,
            registry.readCompanyState(this) ?: keyFailure(), record.originalActionId, digest)
        val message = NativeEmployeeEnrollmentWireV1.endorsementMessage(record)
        return try {
            val signature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, message)
            try {
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
                approval.owner.requireEmployeeEnrollmentStillCurrent(approval,
                    registry.readCompanyState(this) ?: keyFailure(), record.originalActionId, digest)
                if (registry.readEmployeeEnrollments(this)?.records?.singleOrNull { it.originalActionId == record.originalActionId } != record) keyFailure()
                signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { message.fill(0) }
    }

    /** Historical certificate verification is not approval renewal or permission to dispatch. */
    fun verifyEmployeeEnrollment(record: NativeEmployeeEnrollmentRecordV1) {
        if (record.rootEndorsement.size != 64) keyFailure()
        val original = requireCompanyBootstrap()
        if (record.target.rootId != original.original.rootId || record.target.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        val message = NativeEmployeeEnrollmentWireV1.endorsementMessage(record)
        val point = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT); val signature = record.rootEndorsement.copyBytes()
        try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }

    /** Dedicated complete employee-policy domain under the ORIGINAL root, after exact forced signing intent. */
    fun signEmployeePolicyActivationEndorsement(record: NativeEmployeePolicyActivationRecordV1,
        approval: AndroidLocalInstallerAuthentication.EmployeePolicyActivationHeadApproval): ByteArray {
        val original = requireCompanyBootstrap()
        val retained = registry.readEmployeePolicyActivations(this)?.records?.singleOrNull { it.originalActionId == record.originalActionId }
            ?: keyFailure()
        if (retained != record || record.phase != LocalEmployeePolicyActivationPhase.SIGNING_INTENT) keyFailure()
        val head = requireNotNull(record.approvedHead)
        if (head.accountRevision != approval.accountRevision || head.credentialRevision != approval.credentialRevision ||
            head.osBootCount != approval.boot || head.deadlineElapsedMillis != approval.expires ||
            head.retainedRevision != approval.retainedRevision || record.target.rootId != original.original.rootId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        val action = NativeEmployeePolicyActivationWireV1.actionMessage(record)
        val digest = try { sha256(action) } finally { action.fill(0) }
        approval.owner.requireEmployeePolicyActivationStillCurrent(approval,
            registry.readCompanyState(this) ?: keyFailure(), record.originalActionId, digest)
        val message = NativeEmployeePolicyActivationWireV1.endorsementMessage(record)
        return try {
            val signature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, message)
            try {
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
                approval.owner.requireEmployeePolicyActivationStillCurrent(approval,
                    registry.readCompanyState(this) ?: keyFailure(), record.originalActionId, digest)
                if (registry.readEmployeePolicyActivations(this)?.records?.singleOrNull { it.originalActionId == record.originalActionId } != record) keyFailure()
                signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { message.fill(0) }
    }

    /** Historical certificate verification is not approval renewal or permission to dispatch. */
    fun verifyEmployeePolicyActivation(record: NativeEmployeePolicyActivationRecordV1) {
        if (record.rootEndorsement.size != 64) keyFailure()
        val original = requireCompanyBootstrap()
        if (record.target.rootId != original.original.rootId || record.target.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        val message = NativeEmployeePolicyActivationWireV1.endorsementMessage(record)
        val point = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT); val signature = record.rootEndorsement.copyBytes()
        try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }

    // BEGIN isolated device-pairing original-root endorsement V1 (old methods unchanged).
    /** CLOSED new one-action producer. No signer callback, old-purpose approval or alias.
     * The actual same-worker pairing participant independently rejoins its native original
     * cut, real head approval and exact forced SIGNING_INTENT before AND after this sign. */
    fun signDevicePairingEndorsement(record: NativeDevicePairingRecordV1,
        approval: AndroidLocalInstallerAuthentication.DevicePairingHeadApproval,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray {
        val original = requireCompanyBootstrap()
        val retained = registry.readDevicePairings(this)?.records?.singleOrNull {
            it.body.originalActionId == record.body.originalActionId
        } ?: keyFailure()
        if (retained != record || record.phase != LocalDevicePairingPhase.SIGNING_INTENT) keyFailure()
        val head = requireNotNull(record.approvedHead); val origin = record.body.origin
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            approval.body != record.body || approval.possession != record.possession || approval.consent != record.consent ||
            head.accountRevision != approval.accountRevision || head.credentialRevision != approval.credentialRevision ||
            head.retainedRevision != approval.retainedRevision || head.osBootCount != approval.boot ||
            head.deadlineElapsedMillis != approval.expires) keyFailure()
        actualPairingsOwner.requireSigningCurrent(record, approval, this, registry)
        val message = NativeDevicePairingWireV1.endorsementMessage(record)
        return try {
            val signature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, message)
            try {
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
                actualPairingsOwner.requireSigningCurrent(record, approval, this, registry)
                if (registry.readDevicePairings(this)?.records?.singleOrNull {
                        it.body.originalActionId == record.body.originalActionId
                    } != record) keyFailure()
                signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { message.fill(0) }
    }

    /** Historical original-root DATA verification, never consent/cut/deadline renewal. */
    fun verifyDevicePairing(record: NativeDevicePairingRecordV1) {
        if (record.phase != LocalDevicePairingPhase.APPROVED || record.rootEndorsement.size != 64) keyFailure()
        val original = requireCompanyBootstrap(); val origin = record.body.origin
        val company = registry.readCompanyState(this) ?: keyFailure()
        val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        if (origin.coordinates.scope.organizationId != original.original.organizationId ||
            origin.coordinates.scope.companyId != original.original.companyId || origin.rootId != original.original.rootId ||
            origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId || origin.rootDocumentDigest != rootDigest ||
            origin.foundingActionId != original.original.actionId || origin.headAuthorityId != original.headAuthorityId ||
            origin.headEnrollmentId != original.headEnrollmentId ||
            origin.rootFingerprint != AndroidP256Primitives.rootFingerprint(rootBody())) keyFailure()
        val message = NativeDevicePairingWireV1.endorsementMessage(record)
        val point = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT); val signature = record.rootEndorsement.copyBytes()
        try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }
    /** Distinct known-unsigned terminal DATA domain. Never an old head-purpose signer,
     * pairing approval, revocation or authority to consume any signed/admitted original. */
    fun signUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray {
        val original = requireCompanyBootstrap()
        val origin = record.body.origin
        if (record.phase != LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT || record.approvedHead != null ||
            record.rootEndorsement.size != 0 || record.retirementEndorsement.size != 0 ||
            origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId) keyFailure()
        actualPairingsOwner.requireUnsignedRetirementSigningCurrent(record, this, registry)
        val message = NativeDevicePairingWireV1.retirementMessage(record)
        return try {
            val signature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, message)
            try {
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT)
                actualPairingsOwner.requireUnsignedRetirementSigningCurrent(record, this, registry)
                signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { message.fill(0) }
    }
    /** Historical terminal DATA verification only; no deadline/cut renewal or current grant. */
    fun verifyUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1) {
        if (record.phase != LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED || record.approvedHead != null ||
            record.rootEndorsement.size != 0 || record.retirementEndorsement.size != 64) keyFailure()
        val original = requireCompanyBootstrap(); val origin = record.body.origin
        val company = registry.readCompanyState(this) ?: keyFailure()
        val root = company.original.root.copyWireBytes(); val digest = try { sha256(root) } finally { root.fill(0) }
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != digest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            origin.rootFingerprint != AndroidP256Primitives.rootFingerprint(rootBody())) keyFailure()
        val message = NativeDevicePairingWireV1.retirementMessage(record)
        val point = originalPoint(SelfOwnedNativeKeyRoleV1.ROOT); val signature = record.retirementEndorsement.copyBytes()
        try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }
    // END isolated device-pairing original-root endorsement V1.

    /** Exact A0a bodies and a genuine purpose-bound invocation, NOT installation signDocument. */
    fun signCompanyPolicy(record: LocalCompanyPolicyRecordV1,
        approval: AndroidLocalInstallerAuthentication.HeadApproval): PurchasingSignedPolicyV1 {
        fun current(): LocalCompanyAuthorityStateV1 {
            val state = registry.readCompanyState(this) ?: keyFailure()
            val retained = state.policies.singleOrNull { it.originalActionId == record.originalActionId } ?: keyFailure()
            if (retained != record || retained.phase != LocalCompanyPolicyPhaseV1.SIGNING_INTENT) keyFailure()
            approval.owner.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.POLICY_PUBLISH,
                record.originalActionId, localCompanyPolicyActionDigestV1(record))
            return state
        }
        current()
        val delegationBytes = record.delegationBody.copyBytes(); val policyBytes = record.policyBody.copyBytes()
        var rootSignature: ByteArray? = null; var policySignature: ByteArray? = null
        return try {
            rootSignature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, delegationBytes)
            checkedKey(SelfOwnedNativeKeyRoleV1.ROOT); current()
            policySignature = AndroidP256Primitives.sign(checkedHeadKey(LocalCompanyHeadKeyRoleV1.POLICY) as PrivateKey, policyBytes)
            checkedHeadKey(LocalCompanyHeadKeyRoleV1.POLICY); current()
            PurchasingSignedPolicyV1(record.policy(), PurchasingSignatureV1(requireNotNull(policySignature)),
                PurchasingDelegationDocumentsV1(listOf(PurchasingSignedDelegationV1(record.delegation(),
                    PurchasingSignatureV1(requireNotNull(rootSignature))))))
        } finally { delegationBytes.fill(0); policyBytes.fill(0); rootSignature?.fill(0); policySignature?.fill(0) }
    }

    /** Full P3 snapshot is mandatory even though the resulting signed message is compact. */
    fun signOriginalInstallationApproval(snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2,
        approval: AndroidLocalInstallerAuthentication.HeadApproval): LocalInstallationSignedApprovalV2 {
        val freshSnapshot = LocalBranchInstallationWireV2.snapshotDefinition(snapshot.definition)
        if (freshSnapshot.definitionDigest != snapshot.definitionDigest || freshSnapshot.allocationDigest != snapshot.allocationDigest) keyFailure()
        val frame = LocalBranchInstallationWireV2.humanApprovalFrame(freshSnapshot, attempt)
        val bytes = frame.copyWireBytes()
        fun current() {
            val state = registry.readCompanyState(this) ?: keyFailure()
            val claim = state.installationClaims.singleOrNull { it.issuedAttemptId == attempt.issuedAttemptId } ?: keyFailure()
            if (claim.signedHumanApproval != null || claim.retirementEvidence != null || claim.acknowledgementFrame != null ||
                !claim.approvalFrame.same(bytes) || claim.definitionDigest != snapshot.definitionDigest ||
                claim.allocationDigest != snapshot.allocationDigest || claim.originalRequestId != snapshot.definition.originalRequestId ||
                claim.attemptSequence != attempt.attemptSequence) keyFailure()
            approval.owner.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
                snapshot.definition.originalRequestId, snapshot.definitionDigest)
            if (attempt.headAccountRevision != approval.accountRevision || attempt.headCredentialRevision != approval.credentialRevision ||
                attempt.installationPurposeRevision != approval.purposeRevision || attempt.installationKeyRevision != approval.keyRevision ||
                attempt.osBootCount != environment.bootCount || work.clock() >= attempt.deadlineElapsedMillis) keyFailure()
            approval.bindInstallationAttempt(attempt.issuedAttemptId)
            val original = requireCompanyBootstrap()
            if (frame.signerKeyId != original.key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyId) keyFailure()
            val point = frame.copySignerPublicKey()
            try { if (!original.key(LocalCompanyHeadKeyRoleV1.INSTALLATION).proof.same(point)) keyFailure() }
            finally { point.fill(0) }
        }
        return try {
            current()
            val signature = AndroidP256Primitives.sign(checkedHeadKey(LocalCompanyHeadKeyRoleV1.INSTALLATION) as PrivateKey, bytes)
            try {
                checkedHeadKey(LocalCompanyHeadKeyRoleV1.INSTALLATION); current()
                LocalBranchInstallationWireV2.signedApproval(frame, signature)
            } finally { signature.fill(0) }
        } finally { bytes.fill(0) }
    }

    /** Only an exact retained completion ACK intent can reach the ORIGINAL root bookkeeping key. */
    fun signOriginalInstallationAcknowledgement(frame: LocalInstallationCommitAcknowledgementV2): LocalInstallationSignedAcknowledgementV2 {
        val bytes = frame.copyWireBytes()
        fun current() {
            val state = registry.readCompanyState(this) ?: keyFailure()
            val claim = state.installationClaims.singleOrNull { it.issuedAttemptId == frame.issuedAttemptId } ?: keyFailure()
            if (claim.signedHumanApproval == null || claim.signedAcknowledgement != null || claim.retirementEvidence != null ||
                claim.acknowledgementFrame?.same(bytes) != true || claim.originalRequestId != frame.originalRequestId ||
                claim.definitionDigest != frame.definitionDigest || claim.allocationDigest != frame.allocationDigest ||
                frame.rootKeyId != requireBootstrap().key(SelfOwnedNativeKeyRoleV1.ROOT).keyId) keyFailure()
            // Deliberately no renewed head enablement, password, attempt expiry or new-work grant.
            guard()
        }
        return try {
            current()
            val signature = AndroidP256Primitives.sign(checkedKey(SelfOwnedNativeKeyRoleV1.ROOT) as PrivateKey, bytes)
            try {
                checkedKey(SelfOwnedNativeKeyRoleV1.ROOT); current()
                LocalBranchInstallationWireV2.signedAcknowledgement(frame, signature)
            } finally { signature.fill(0) }
        } finally { bytes.fill(0) }
    }

    private fun requireCompanyBootstrap(): LocalCompanyAuthorityBootstrapV1 {
        work.requireActive()
        environment.requireCurrent()
        // The getter performs the full fresh registry/filesystem scan; do not scan twice
        // without an intervening operation. Keep the original before/after work checks.
        val original = registry.companyBootstrapRecord()
        work.requireActive()
        return original ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    }

    private fun checkedHeadKey(role: LocalCompanyHeadKeyRoleV1): Key {
        val original = requireCompanyBootstrap(); val intent = original.key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE) keyFailure()
        val key = loadHead(original, intent)
        if (inspectHead(original, intent, key) != intent.security) keyFailure()
        verifyHeadProof(original, intent, key); guard()
        if (inspectHead(original, intent, loadHead(original, intent)) != intent.security) keyFailure()
        return key
    }

    private fun loadHead(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1): Key {
        val name = headAlias(original, intent)
        if (!store.containsAlias(name)) keyFailure()
        val key = store.getKey(name, null) ?: keyFailure()
        if (intent.role == LocalCompanyHeadKeyRoleV1.PEPPER) {
            if (key !is SecretKey || key.algorithm != KeyProperties.KEY_ALGORITHM_HMAC_SHA256) keyFailure()
        } else if (key !is PrivateKey || key.algorithm != KeyProperties.KEY_ALGORITHM_EC) keyFailure()
        return key
    }

    private fun inspectHead(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1, key: Key): SelfOwnedNativeSecurityV1 {
        requireNativeSelfOwnedProtection(original.original.facts.protection)
        val info = when (key) {
            is PrivateKey -> KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
            is SecretKey -> SecretKeyFactory.getInstance("HmacSHA256", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            else -> keyFailure()
        }
        if (info.keystoreAlias != headAlias(original, intent) || info.keySize != 256 ||
            info.purposes != (KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY) ||
            info.origin != KeyProperties.ORIGIN_GENERATED || AndroidNativeKeyInfo.hasServiceRestrictions(info) ||
            info.signaturePaddings.isNotEmpty() ||
            info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) || info.blockModes.isNotEmpty() ||
            info.encryptionPaddings.isNotEmpty()) keyFailure()
        val observed = try { AndroidNativeKeyInfo.observe(info) } catch (_: Exception) { keyFailure() }
        if (!AndroidNativeKeyPolicy.permits(original.original.facts.protection, observed)) keyFailure()
        return observed.selfOwnedSecurity().originalWitness(intent.security)
    }

    private fun generateHead(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1) {
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.STARTED || intent.startedBoot != environment.bootCount) keyFailure()
        val builder = KeyGenParameterSpec.Builder(headAlias(original, intent), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256)
        AndroidNativeKeyInfo.configureService(builder, original.original.facts.protection)
        if (intent.role == LocalCompanyHeadKeyRoleV1.PEPPER) {
            KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
        } else {
            builder.setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(builder.build()) }.generateKeyPair()
        }
    }

    private fun headBinding(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1): ByteArray =
        selfOwnedEncode("original-company-head-key-possession/v1") {
            string("EXPLICIT_FRESH_COMPANY_AND_HEAD_V1")
            val base = original.original
            id(base.actionId); id(base.rootId); id(base.organizationId); id(base.companyId)
            id(original.headAuthorityId); id(original.headEnrollmentId); string(intent.role.wireTag); id(intent.keyId); long(intent.keyRevision)
            target(base.target); registryNamespace(base.namespace); octets(base.nonce); int(base.facts.protection.ordinal)
            string(base.facts.companyName); string(original.headName); string(original.headLogin); string("UNTIL_LOCAL_DISABLE")
            string("POLICY_PUBLISH"); string("FULL_D7_INSTALLATION_APPROVE")
        }

    private fun headProof(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1, key: Key): ByteArray {
        val binding = headBinding(original, intent)
        return try {
            if (intent.role == LocalCompanyHeadKeyRoleV1.PEPPER) hmac(key as SecretKey, binding) else {
                val public = store.getCertificate(headAlias(original, intent))?.publicKey as? ECPublicKey ?: keyFailure()
                val point = AndroidP256Primitives.point(public)
                try { proveEc(key as PrivateKey, point, binding); point }
                catch (failure: Throwable) { point.fill(0); throw failure }
            }
        } finally { binding.fill(0) }
    }

    private fun verifyHeadProof(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1, key: Key) {
        val binding = headBinding(original, intent); val proof = intent.proof.copyBytes()
        try {
            if (intent.role == LocalCompanyHeadKeyRoleV1.PEPPER) {
                val actual = hmac(key as SecretKey, binding)
                try { if (!AndroidP256Primitives.same(proof, actual)) keyFailure() } finally { actual.fill(0) }
            } else {
                val public = store.getCertificate(headAlias(original, intent))?.publicKey as? ECPublicKey ?: keyFailure()
                val point = AndroidP256Primitives.point(public)
                try { if (!point.contentEquals(proof)) keyFailure(); proveEc(key as PrivateKey, point, binding) }
                finally { point.fill(0) }
            }
        } finally { binding.fill(0); proof.fill(0) }
    }

    private fun headAlias(original: LocalCompanyAuthorityBootstrapV1, intent: LocalCompanyHeadKeyIntentV1): String =
        "$ALIAS_PREFIX${original.original.rootId.value}.${intent.role.aliasPart}.${intent.keyId.value}"

    private fun checkedKey(role: SelfOwnedNativeKeyRoleV1): Key {
        val original = requireBootstrap()
        val intent = original.key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE) keyFailure()
        val key = load(original, intent)
        if (inspect(original, intent, key) != intent.security) keyFailure()
        verifyProof(original, intent, key)
        guard()
        // Re-observe the alias's metadata after the actual possession operation as well.
        if (inspect(original, intent, load(original, intent)) != intent.security) keyFailure()
        return key
    }

    private fun requireBootstrap(): SelfOwnedBootstrapV1 {
        work.requireActive()
        environment.requireCurrent()
        // bootstrapRecord still verifies current physical continuity before exposing state.
        val original = registry.bootstrapRecord()
        work.requireActive()
        return original ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }
    private fun guard() { work.requireActive(); environment.requireCurrent(); registry.assertContinuity(); work.requireActive() }

    private fun load(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1): Key {
        val name = alias(original, intent)
        if (!store.containsAlias(name)) keyFailure()
        val key = store.getKey(name, null) ?: keyFailure()
        if (intent.role == SelfOwnedNativeKeyRoleV1.ROOT || intent.role == SelfOwnedNativeKeyRoleV1.ISSUER) {
            if (key !is PrivateKey || key.algorithm != KeyProperties.KEY_ALGORITHM_EC) keyFailure()
        } else if (key !is SecretKey || key.algorithm != algorithm(intent.role)) keyFailure()
        return key
    }

    private fun inspect(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1, key: Key): SelfOwnedNativeSecurityV1 {
        requireNativeSelfOwnedProtection(original.facts.protection)
        val info = when (key) {
            is PrivateKey -> KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
            is SecretKey -> SecretKeyFactory.getInstance(algorithm(intent.role), "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            else -> keyFailure()
        }
        if (info.keystoreAlias != alias(original, intent) || info.keySize != 256 || info.purposes != purposes(intent.role) ||
            info.origin != KeyProperties.ORIGIN_GENERATED || AndroidNativeKeyInfo.hasServiceRestrictions(info) ||
            info.signaturePaddings.isNotEmpty()) keyFailure()
        if (intent.role == SelfOwnedNativeKeyRoleV1.REGISTRY) {
            if (info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM) ||
                info.encryptionPaddings.toSet() != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) || info.digests.isNotEmpty()) keyFailure()
        } else if (info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) || info.blockModes.isNotEmpty() ||
            info.encryptionPaddings.isNotEmpty()) keyFailure()
        val observed = try { AndroidNativeKeyInfo.observe(info) } catch (_: Exception) { keyFailure() }
        if (!AndroidNativeKeyPolicy.permits(original.facts.protection, observed)) keyFailure()
        return observed.selfOwnedSecurity().originalWitness(intent.security)
    }

    private fun generate(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1) {
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.STARTED || intent.startedBoot != environment.bootCount) keyFailure()
        val builder = KeyGenParameterSpec.Builder(alias(original, intent), purposes(intent.role)).setKeySize(256)
        AndroidNativeKeyInfo.configureService(builder, original.facts.protection)
        when (intent.role) {
            SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> builder
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
            SelfOwnedNativeKeyRoleV1.REGISTRY -> builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true)
            SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> builder.setDigests(KeyProperties.DIGEST_SHA256)
        }
        val specification = builder.build()
        when (intent.role) {
            SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
                .apply { initialize(specification) }.generateKeyPair()
            else -> KeyGenerator.getInstance(algorithm(intent.role), "AndroidKeyStore").apply { init(specification) }.generateKey()
        }
    }

    private fun keyBinding(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1): ByteArray =
        selfOwnedEncode("original-native-key-possession/v1") {
            id(original.actionId); id(original.rootId); id(original.organizationId); id(original.companyId)
            id(original.issuerId); id(original.installerId); int(intent.role.wireId); id(intent.keyId)
            target(original.target); registryNamespace(original.namespace); octets(original.nonce); int(original.facts.protection.ordinal)
            string(original.facts.companyName); string(original.facts.responsiblePersonName); string(original.facts.installerLogin)
        }

    private fun createProof(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1, key: Key): ByteArray {
        val binding = keyBinding(original, intent)
        return try {
            when (intent.role) {
                SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> {
                    val public = store.getCertificate(alias(original, intent))?.publicKey as? ECPublicKey ?: keyFailure()
                    val point = AndroidP256Primitives.point(public)
                    try { proveEc(key as PrivateKey, point, binding); point }
                    catch (failure: Throwable) { point.fill(0); throw failure }
                }
                SelfOwnedNativeKeyRoleV1.REGISTRY -> {
                    val expected = AndroidP256Primitives.hash(binding)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val encrypted = try { cipher.init(Cipher.ENCRYPT_MODE, key as SecretKey); cipher.updateAAD(binding); cipher.doFinal(expected) }
                    finally { expected.fill(0) }
                    val nonce = cipher.iv
                    try {
                        if (nonce == null || nonce.size != 12 || encrypted.size != 48) keyFailure()
                        byteArrayOf(1) + nonce + encrypted
                    } finally { nonce?.fill(0); encrypted.fill(0) }
                }
                SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> hmac(key as SecretKey, binding)
            }
        } finally { binding.fill(0) }
    }

    private fun verifyProof(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1, key: Key) {
        val binding = keyBinding(original, intent); val proof = intent.proof.copyBytes()
        try {
            when (intent.role) {
                SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> {
                    val public = store.getCertificate(alias(original, intent))?.publicKey as? ECPublicKey ?: keyFailure()
                    val point = AndroidP256Primitives.point(public)
                    try {
                        if (!point.contentEquals(proof)) keyFailure()
                        proveEc(key as PrivateKey, point, binding)
                    } finally { point.fill(0) }
                }
                SelfOwnedNativeKeyRoleV1.REGISTRY -> {
                    if (proof.size != 61 || proof[0].toInt() != 1) keyFailure()
                    val nonce = proof.copyOfRange(1, 13); val ciphertext = proof.copyOfRange(13, 61)
                    val expected = AndroidP256Primitives.hash(binding)
                    try {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key as SecretKey, GCMParameterSpec(128, nonce)); cipher.updateAAD(binding)
                        val actual = cipher.doFinal(ciphertext)
                        try { if (!AndroidP256Primitives.same(expected, actual)) keyFailure() } finally { actual.fill(0) }
                    } finally { nonce.fill(0); ciphertext.fill(0); expected.fill(0) }
                }
                SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> {
                    val actual = hmac(key as SecretKey, binding)
                    try { if (!AndroidP256Primitives.same(proof, actual)) keyFailure() } finally { actual.fill(0) }
                }
            }
        } finally { binding.fill(0); proof.fill(0) }
    }

    private fun proveEc(key: PrivateKey, point: ByteArray, binding: ByteArray) {
        val nonce = AndroidP256Primitives.randomBytes(32)
        val message = try { selfOwnedEncode("fresh-native-ec-challenge/v1") { field(binding); field(nonce) } }
        finally { nonce.fill(0) }
        val signature = try { AndroidP256Primitives.sign(key, message) }
        catch (failure: Throwable) { message.fill(0); throw failure }
        try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { message.fill(0); signature.fill(0) }
    }

    private fun hmac(key: SecretKey, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key); doFinal(message).also { if (it.size != 32) { it.fill(0); keyFailure() } }
    }
    private fun alias(original: SelfOwnedBootstrapV1, intent: SelfOwnedNativeKeyIntentV1): String =
        "$ALIAS_PREFIX${original.rootId.value}.${intent.role.aliasPart}.${intent.keyId.value}"
    private fun algorithm(role: SelfOwnedNativeKeyRoleV1): String = when (role) {
        SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> KeyProperties.KEY_ALGORITHM_EC
        SelfOwnedNativeKeyRoleV1.REGISTRY -> KeyProperties.KEY_ALGORITHM_AES
        SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> KeyProperties.KEY_ALGORITHM_HMAC_SHA256
    }
    private fun purposes(role: SelfOwnedNativeKeyRoleV1): Int = when (role) {
        SelfOwnedNativeKeyRoleV1.REGISTRY -> KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        else -> KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    }
    private fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    companion object { private const val ALIAS_PREFIX = "ukkera.self-owned.p1.v1." }
}
