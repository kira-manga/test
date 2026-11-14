package com.ukkera.pos.security.desktopseamv2

import com.ukkera.pos.security.AndroidOwnedInstallationWork
import com.ukkera.pos.security.AndroidProvisionerProcessLease
import com.ukkera.pos.security.JvmP256Primitives
import com.ukkera.pos.security.LocalCompanyAuthorityBootstrapV2
import com.ukkera.pos.security.LocalCompanyHeadEnrollmentBodyV1
import com.ukkera.pos.security.LocalCompanyHeadEnrollmentV1
import com.ukkera.pos.security.LocalCompanyHeadKeyIntentV2
import com.ukkera.pos.security.LocalCompanyHeadKeyRoleV1
import com.ukkera.pos.security.SelfOwnedBootMark
import com.ukkera.pos.security.SelfOwnedBootOrdinalLedgerV2
import com.ukkera.pos.security.SelfOwnedBootOrdinalStepV2
import com.ukkera.pos.security.SelfOwnedBootstrapV2
import com.ukkera.pos.security.SelfOwnedCompanyLedgerKindV2
import com.ukkera.pos.security.SelfOwnedCompanyNativeKeysV2
import com.ukkera.pos.security.SelfOwnedNativeCompanyLedgerWireV2
import com.ukkera.pos.security.SelfOwnedDelegationBodyV1
import com.ukkera.pos.security.SelfOwnedDocumentRoleV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityPreparationV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedBodyV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedDocumentV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedKeyIntentPhaseV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeBootSessionV2
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeProfileV1
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.SelfOwnedNativeSecurityV1
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedOriginalEnvironment
import com.ukkera.pos.security.SelfOwnedProcessPlatform
import com.ukkera.pos.security.SelfOwnedProcessPrincipal
import com.ukkera.pos.security.SelfOwnedProvisionerRegistryStoreV2
import com.ukkera.pos.security.SelfOwnedRegistryEnvelopeV2
import com.ukkera.pos.security.SelfOwnedRegistryNamespaceV2
import com.ukkera.pos.security.SelfOwnedRootBodyV1
import com.ukkera.pos.security.SelfOwnedSignedDocumentV1
import com.ukkera.pos.security.selfOwnedFail
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * jvmTest-ONLY in-memory stand-in for the Windows/macOS (V2) provisioner seam. Software P-256 /
 * AES-GCM / HMAC keys, an in-memory "OS-private namespace" holding the exact retained BYTES with
 * synthetic file identities, and a deterministic boot-ordinal chain. It proves no custody, OS
 * protection or anti-rollback; it exists to drive the shared core end to end. Never main code.
 */
internal class DesktopSeamV2Double(
    val target: SelfOwnedInstalledTargetBindingV1 = N.binding(N.macIdentity),
    val namespace: SelfOwnedRegistryNamespaceV2 = N.macNamespace,
) {
    // ---- Deterministic boot chain ----

    private var bootSerial = 0
    private fun session(): SelfOwnedNativeBootSessionV2 =
        SelfOwnedNativeBootSessionV2.Mac("%08x-0000-4000-8000-%012x".format(0x5000 + bootSerial, bootSerial++))

    val chain: MutableList<SelfOwnedBootOrdinalLedgerV2> = mutableListOf(SelfOwnedBootOrdinalLedgerV2(0, session(), null))

    /** Simulated OS reboot: exactly one verified +1 ledger step. */
    fun nextBoot() {
        val step = chain.last().advance(session())
        chain.add((step as SelfOwnedBootOrdinalStepV2.Advanced).ledger)
    }

    // ---- Process / clock ----

    val elapsed = AtomicLong(10_000L)
    var principal: SelfOwnedProcessPrincipal = SelfOwnedProcessPrincipal.MacUid(501)

    val process = object : SelfOwnedProcessPlatform {
        override fun pid(): Int = 4242
        override fun principal(): SelfOwnedProcessPrincipal = SelfOwnedProcessPrincipal.MacUid(501)
        override fun elapsedMillis(): Long = elapsed.get()
        override fun elapsedRealtimeNanos(): Long = elapsed.get() * 1_000_000L
        override fun passwordKeyFactory(): SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        override fun terminate(pid: Int) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
    }

    fun openWork(): AndroidOwnedInstallationWork =
        AndroidOwnedInstallationWork(checkNotNull(AndroidProvisionerProcessLease.acquire(SelfOwnedNativeProfileV1.LIVE)), process)

    fun closeWork(work: AndroidOwnedInstallationWork) {
        runBlocking { withTimeout(30_000L) { work.requestClose { }.await() } }
    }

    // ---- Environment ----

    var installedTarget: SelfOwnedInstalledTargetBindingV1 = target
    var refusedProtection: SelfOwnedKeyProtectionV1? = SelfOwnedKeyProtectionV1.STRONGBOX
    /** Chain the environment presents; defaults to the live chain (tests may truncate/roll back). */
    var presentedChain: (() -> List<SelfOwnedBootOrdinalLedgerV2>)? = null
    var presentedCurrent: (() -> SelfOwnedBootOrdinalLedgerV2)? = null

    val environment = object : SelfOwnedOriginalEnvironment {
        override val profile: SelfOwnedNativeProfileV1 get() = SelfOwnedNativeProfileV1.LIVE
        override val installedTarget: SelfOwnedInstalledTargetBindingV1 get() = this@DesktopSeamV2Double.installedTarget
        override val bootMark: SelfOwnedBootMark get() = SelfOwnedBootMark.Session(presentedCurrent?.invoke() ?: chain.last())
        override val principal: SelfOwnedProcessPrincipal get() = this@DesktopSeamV2Double.principal
        override fun requireExistingOriginalObservation() = Unit
        override fun currentProvisionerVolumeUuid(): String = N.VOLUME
        override fun requireCurrent() = Unit
        override fun requireSupportedProtection(protection: SelfOwnedKeyProtectionV1) {
            if (protection == refusedProtection) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
        override fun bootLedgerChain(): List<SelfOwnedBootOrdinalLedgerV2> = presentedChain?.invoke() ?: chain.toList()
    }

    // ---- In-memory OS-private namespace (exact retained bytes) ----

    var bootstrapBytes: ByteArray? = null
    var companyBytes: ByteArray? = null
    var envelopeBytes: ByteArray? = null
    /** Retained V2 company ledger envelopes (tests may tamper/swap them). */
    val ledgerBytes = mutableMapOf<SelfOwnedCompanyLedgerKindV2, ByteArray>()
    var continuityBroken = false

    private fun <T> decoded(result: SelfOwnedInstalledIdentityResultV2<T>): T = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Data -> result.value
        is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    val store = object : SelfOwnedProvisionerRegistryStoreV2 {
        override fun openNative() = Unit
        override fun openExistingNative() = Unit
        override fun assertContinuity() { if (continuityBroken) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        override fun closeNative() = Unit
        override fun bootstrapRecordV2(): SelfOwnedBootstrapV2? =
            bootstrapBytes?.let { decoded(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(it.copyOf())) }
        override fun registryEnvelopeBytes(): ByteArray? = envelopeBytes?.copyOf()
        override fun companyBootstrapRecordV2(): LocalCompanyAuthorityBootstrapV2? =
            companyBytes?.let { decoded(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(it.copyOf())) }
        override fun namespaceIdentityV2(): SelfOwnedRegistryNamespaceV2 = namespace
        override fun retainBootstrapV2(next: SelfOwnedBootstrapV2) {
            check(companyBytes == null)
            bootstrapBytes = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(next)
        }
        override fun retainCompanyBootstrapV2(next: LocalCompanyAuthorityBootstrapV2) {
            // One atomic platform write: the company record and its embedded original bootstrap.
            companyBytes = SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(next)
            bootstrapBytes = SelfOwnedNativeProvisionerWireV2.encodeBootstrap(next.original)
        }
        override fun replaceRegistryEnvelope(bytes: ByteArray) { envelopeBytes = bytes.copyOf() }
        override fun companyLedgerEnvelopeBytes(kind: SelfOwnedCompanyLedgerKindV2): ByteArray? = ledgerBytes[kind]?.copyOf()
        override fun replaceCompanyLedgerEnvelope(kind: SelfOwnedCompanyLedgerKindV2, bytes: ByteArray) { ledgerBytes[kind] = bytes.copyOf() }
    }

    // ---- Software key custody (stands in for Keychain / CNG non-exportable keys) ----

    private fun ec(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun hmac(): SecretKey = KeyGenerator.getInstance("HmacSHA256").generateKey()

    val signing = mutableMapOf<SelfOwnedNativeKeyRoleV1, KeyPair>()
    var registryKey: SecretKey? = null
    var pepperKey: SecretKey? = null
    val headSigning = mutableMapOf<LocalCompanyHeadKeyRoleV1, KeyPair>()
    var headPepper: SecretKey? = null
    /** Test hook: when set, grants are signed with the ROOT key instead of the issuer key. */
    var forgeGrantsWithRoot = false

    private fun proof(material: ByteArray): SelfOwnedOctetsV1 = SelfOwnedOctetsV1(JvmP256Primitives.hash(material))
    private fun pointOf(pair: KeyPair): ByteArray = JvmP256Primitives.point(pair.public as ECPublicKey)
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    private fun bootstrap(): SelfOwnedBootstrapV2 = store.bootstrapRecordV2() ?: uncertain()
    private fun company(): LocalCompanyAuthorityBootstrapV2 = store.companyBootstrapRecordV2() ?: uncertain()

    private fun createOriginal(role: SelfOwnedNativeKeyRoleV1): ByteArray = when (role) {
        SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> pointOf(ec().also { signing[role] = it })
        SelfOwnedNativeKeyRoleV1.REGISTRY -> aes().also { registryKey = it }.encoded
        SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> hmac().also { pepperKey = it }.encoded
    }

    private fun originalMaterial(role: SelfOwnedNativeKeyRoleV1): ByteArray? = when (role) {
        SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> signing[role]?.let(::pointOf)
        SelfOwnedNativeKeyRoleV1.REGISTRY -> registryKey?.encoded
        SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER -> pepperKey?.encoded
    }

    private fun headMaterial(role: LocalCompanyHeadKeyRoleV1): ByteArray? = when (role) {
        LocalCompanyHeadKeyRoleV1.PEPPER -> headPepper?.encoded
        else -> headSigning[role]?.let(::pointOf)
    }

    private fun retainOriginal(next: SelfOwnedBootstrapV2) {
        val enclosing = store.companyBootstrapRecordV2()
        if (enclosing == null) store.retainBootstrapV2(next) else store.retainCompanyBootstrapV2(enclosing.nextOriginal(next))
    }

    private fun currentSession(): SelfOwnedNativeBootSessionV2 = (environment.bootMark as SelfOwnedBootMark.Session).session

    val keys = object : SelfOwnedCompanyNativeKeysV2 {
        override fun assertOriginalNamespace() { if (store.namespaceIdentityV2() != namespace) uncertain() }

        override fun assertCompletedOriginalKeys() {
            // No retained bootstrap yet: no completed key can exist (an orphan key would be refused).
            val retained = store.bootstrapRecordV2()
                ?: return if (signing.isEmpty() && registryKey == null && pepperKey == null) Unit else uncertain()
            for (intent in retained.keys.filter { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }) {
                val material = originalMaterial(intent.role) ?: uncertain()
                if (proof(material) != intent.proof) uncertain()
            }
        }

        override fun prepareOriginalKeys() {
            for (role in SelfOwnedNativeKeyRoleV1.entries) {
                var intent = bootstrap().key(role)
                if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) continue
                if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED) {
                    retainOriginal(bootstrap().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                        startedBoot = currentSession())))
                    intent = bootstrap().key(role)
                }
                val material = createOriginal(role)
                retainOriginal(bootstrap().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                    security = SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED, proof = proof(material))))
            }
        }

        override fun assertAllOriginalKeys() {
            if (!bootstrap().complete()) uncertain()
            assertCompletedOriginalKeys()
        }

        override fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray = when (role) {
            SelfOwnedNativeKeyRoleV1.ROOT, SelfOwnedNativeKeyRoleV1.ISSUER -> pointOf(signing[role] ?: uncertain())
            else -> uncertain()
        }

        override fun rootBody(): SelfOwnedRootBodyV1 {
            val b = bootstrap()
            return SelfOwnedRootBodyV1(b.rootId, b.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, b.organizationId, b.companyId,
                b.installerId, b.facts.companyName, b.facts.responsiblePersonName, b.facts.installerLogin,
                SelfOwnedOctetsV1(originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)), b.facts.protection, b.actionId)
        }

        override fun rootAnnouncement(): SelfOwnedSignedDocumentV1 = JvmP256Primitives.signDocument(
            SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT, SelfOwnedInstallationWireV1.encodeRoot(rootBody()),
            (signing[SelfOwnedNativeKeyRoleV1.ROOT] ?: uncertain()).private)

        override fun issuerDelegation(root: SelfOwnedSignedDocumentV1): SelfOwnedSignedDocumentV1 {
            val b = bootstrap()
            val body = SelfOwnedDelegationBodyV1(b.rootId, JvmP256Primitives.documentDigest(root), b.organizationId, b.companyId,
                b.issuerId, b.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId, SelfOwnedOctetsV1(originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)),
                b.installerId, 0L, 0L, b.facts.protection)
            return JvmP256Primitives.signDocument(SelfOwnedDocumentRoleV1.ISSUER_DELEGATION,
                SelfOwnedInstallationWireV1.encodeDelegation(body), (signing[SelfOwnedNativeKeyRoleV1.ROOT] ?: uncertain()).private)
        }

        override fun signOriginalPreparationV2(body: SelfOwnedInstalledIdentityPreparationV2): SelfOwnedInstalledIdentitySignedDocumentV2 {
            val signed = SelfOwnedInstalledIdentitySignedBodyV2(body)
            val role = if (forgeGrantsWithRoot) SelfOwnedNativeKeyRoleV1.ROOT else SelfOwnedNativeKeyRoleV1.ISSUER
            return SelfOwnedInstalledIdentitySignedDocumentV2(signed,
                JvmP256Primitives.sign((signing[role] ?: uncertain()).private, signed.copyWireBytes()))
        }

        override fun installerTag(message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(pepperKey ?: uncertain()) }.doFinal(message)

        override fun sealRegistryV2(aad: ByteArray, plaintext: ByteArray): SelfOwnedRegistryEnvelopeV2 {
            val nonce = JvmP256Primitives.randomBytes(12)
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, registryKey ?: uncertain(), GCMParameterSpec(128, nonce)); updateAAD(aad) }.doFinal(plaintext)
            val signature = JvmP256Primitives.sign((signing[SelfOwnedNativeKeyRoleV1.ISSUER] ?: uncertain()).private,
                SelfOwnedNativeProvisionerWireV2.registrySignature(aad, nonce, ciphertext))
            return SelfOwnedRegistryEnvelopeV2(nonce, ciphertext, signature)
        }

        override fun openRegistryV2(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV2): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, registryKey ?: uncertain(), GCMParameterSpec(128, envelope.nonce)); updateAAD(aad) }
                .doFinal(envelope.ciphertext)

        override fun prepareCompanyKeys() {
            for (role in LocalCompanyHeadKeyRoleV1.entries) {
                var intent = company().key(role)
                if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) continue
                if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED) {
                    store.retainCompanyBootstrapV2(company().replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                        startedBoot = currentSession())))
                    intent = company().key(role)
                }
                val material = if (role == LocalCompanyHeadKeyRoleV1.PEPPER) hmac().also { headPepper = it }.encoded
                    else pointOf(ec().also { headSigning[role] = it })
                store.retainCompanyBootstrapV2(company().replaceKey(completeHead(intent, material)))
            }
        }

        private fun completeHead(intent: LocalCompanyHeadKeyIntentV2, material: ByteArray): LocalCompanyHeadKeyIntentV2 =
            intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE, security = SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED,
                proof = proof(material))

        override fun assertAllCompanyKeys() {
            assertAllOriginalKeys()
            val c = company()
            if (!c.complete()) uncertain()
            for (intent in c.headKeys) if (proof(headMaterial(intent.role) ?: uncertain()) != intent.proof) uncertain()
        }

        override fun headPoint(role: LocalCompanyHeadKeyRoleV1): ByteArray =
            if (role == LocalCompanyHeadKeyRoleV1.PEPPER) uncertain() else pointOf(headSigning[role] ?: uncertain())

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
            return LocalCompanyHeadEnrollmentV1(body, SelfOwnedOctetsV1(
                JvmP256Primitives.sign((signing[SelfOwnedNativeKeyRoleV1.ROOT] ?: uncertain()).private, bytes)))
        }

        override fun headTag(message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(headPepper ?: uncertain()) }.doFinal(message)

        override fun signRootEndorsement(message: ByteArray): ByteArray {
            SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage(message)
            return JvmP256Primitives.sign((signing[SelfOwnedNativeKeyRoleV1.ROOT] ?: uncertain()).private, message)
        }

        override fun toString(): String = "DesktopSeamV2Double.keys([REDACTED])"
    }
}
