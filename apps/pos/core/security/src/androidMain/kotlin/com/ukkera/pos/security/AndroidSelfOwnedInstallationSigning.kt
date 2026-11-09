package com.ukkera.pos.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Original P2 signing identity. Only fixed native-binding/journal purposes, never arbitrary signing. */
internal class AndroidSelfOwnedInstallationSigning(
    private val environment: AndroidSelfOwnedEnvironment,
    private val journal: AndroidSelfOwnedInstallationJournal,
    private val work: AndroidOwnedInstallationWork,
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }

    /** Missing native intent cannot adopt any orphan target/storage/pepper alias as a new key. */
    fun assertAliasNamespace() {
        work.requireActive(); environment.requireCurrent()
        val original = journal.intentOrNull()
        val allowed = if (original == null) emptySet() else original.keys
            .filter { it.phase != SelfOwnedKeyIntentPhaseV1.NOT_STARTED }.map { aliasFor(original, it.role) }.toSet()
        val seen = mutableSetOf<String>(); val aliases = store.aliases(); var count = 0
        while (aliases.hasMoreElements()) {
            if (++count > 65_536) keyFailure()
            val value = aliases.nextElement()
            if (value.startsWith(environment.profile.p2SigningPrefix) || value.startsWith(environment.profile.p2DataPrefix)) {
                if (value !in allowed || !seen.add(value) || seen.size > 3) keyFailure()
            }
        }
        if (original != null) for (key in original.keys.filter { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }) {
            if (aliasFor(original, key.role) !in seen) keyFailure()
        }
        environment.requireCurrent(); work.requireActive()
    }

    fun prepareOriginalSigningKey() {
        assertAliasNamespace()
        var original = journal.intent(); var intent = original.key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING)
        requireNativeSelfOwnedProtection(original.source.preparationBody.protection)
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) { checkedKey(); return }
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.STARTED && environment.bootCount <= requireNotNull(intent.startedBoot)) keyFailure()
        val present = store.containsAlias(aliasFor(original, SelfOwnedTargetKeyRoleV1.TARGET_SIGNING))
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED && present) keyFailure()
        if (!present) {
            val started = intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED, startedBoot = environment.bootCount,
                security = null, proof = SelfOwnedOctetsV1(ByteArray(0)))
            journal.retainIntent(original.replaceKey(started))
            original = journal.intent(); intent = original.key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING)
            guard()
            if (store.containsAlias(aliasFor(original, SelfOwnedTargetKeyRoleV1.TARGET_SIGNING))) keyFailure()
            try { generate(original) } catch (_: Exception) { keyFailure() }
        }
        val key = load(original); val security = inspect(original, key)
        val point = nativePoint(original)
        try {
            prove(original, key, point)
            guard()
            val after = load(original)
            if (inspect(original, after) != security) keyFailure()
            prove(original, after, point)
            journal.retainIntent(original.replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
                security = security, proof = SelfOwnedOctetsV1(point))))
        } finally { point.fill(0) }
        checkedKey()
    }

    fun assertOriginalSigningKey() { checkedKey(); guard() }

    fun originalPoint(): ByteArray {
        checkedKey()
        val point = journal.intent().key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING).proof.copyBytes()
        try { checkedKey(); return point } catch (failure: Throwable) { point.fill(0); throw failure }
    }

    /** Crypto consistency alone is NOT imported-root origin; actual P1/journal origin is separate. */
    fun verifyOriginalSource() {
        val source = journal.intent().source
        if (!source.preparationBody.target.same(environment.target)) keyFailure()
        val rootPoint = source.rootBody.rootPublicKey.copyBytes()
        val issuerPoint = source.delegationBody.issuerPublicKey.copyBytes()
        try {
            if (!AndroidP256Primitives.verifyDocument(source.root, rootPoint) ||
                !AndroidP256Primitives.verifyDocument(source.delegation, rootPoint) ||
                !AndroidP256Primitives.verifyDocument(source.preparation, issuerPoint) ||
                source.delegationBody.rootDocumentDigest != AndroidP256Primitives.documentDigest(source.root) ||
                source.preparationBody.rootDocumentDigest != AndroidP256Primitives.documentDigest(source.root) ||
                source.preparationBody.delegationDigest != AndroidP256Primitives.documentDigest(source.delegation)) keyFailure()
        } finally { rootPoint.fill(0); issuerPoint.fill(0) }
        guard()
    }

    fun createOriginalBinding(): SelfOwnedNativeBindingV1 {
        val body = SelfOwnedNativeInstallationWireV1.encodeBindingBody(expectedBindingBody())
        val message = try { SelfOwnedNativeInstallationWireV1.bindingSigningMessage(body) }
        catch (failure: Throwable) { body.fill(0); throw failure }
        try {
            val signature = AndroidP256Primitives.sign(checkedKey(), message)
            try {
                checkedKey()
                return SelfOwnedNativeBindingV1(body, signature).also { validateOriginalBinding(it) }
            } finally { signature.fill(0) }
        } finally { body.fill(0); message.fill(0) }
    }

    fun validateOriginalBinding(binding: SelfOwnedNativeBindingV1) {
        val expected = SelfOwnedNativeInstallationWireV1.encodeBindingBody(expectedBindingBody())
        val actual = binding.body(); val signature = binding.signature()
        val point = originalPoint()
        try {
            if (!expected.contentEquals(actual)) keyFailure()
            val message = SelfOwnedNativeInstallationWireV1.bindingSigningMessage(actual)
            try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
            finally { message.fill(0) }
            checkedKey(); guard()
        } finally { expected.fill(0); actual.fill(0); signature.fill(0); point.fill(0) }
    }

    fun signJournalEnvelope(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        requireJournalFrame(aad, nonce, ciphertext)
        val message = SelfOwnedNativeInstallationWireV1.stateSignature(aad, nonce, ciphertext)
        val signature = try { AndroidP256Primitives.sign(checkedKey(), message) } finally { message.fill(0) }
        try { checkedKey(); return signature } catch (failure: Throwable) { signature.fill(0); throw failure }
    }

    fun verifyJournalEnvelope(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV1) {
        requireJournalFrame(aad, envelope.nonce, envelope.ciphertext)
        val point = originalPoint()
        val message = SelfOwnedNativeInstallationWireV1.stateSignature(aad, envelope.nonce, envelope.ciphertext)
        try { if (!AndroidP256Primitives.verify(point, message, envelope.signature)) keyFailure() }
        finally { point.fill(0); message.fill(0) }
        checkedKey(); guard()
    }

    /** Actual original allocation, not hashes supplied by a caller or imported signature chain. */
    fun originalHistoryAllocation(binding: SelfOwnedNativeBindingV1,
        initial: AndroidOriginalCompanionInitialization? = null): OriginalAllocationCommitmentV2 {
        requireHistoryAcquisition(initial)
        validateOriginalBinding(binding)
        val source = journal.intent().source
        val point = originalPoint(); var encoded = ByteArray(0)
        return try {
            encoded = binding.copyWireBytes()
            OriginalAllocationCommitmentV2(source.preparationBody.coordinates, sha256(encoded),
                PayloadDigest(AndroidP256Primitives.documentDigest(source.root)),
                PayloadDigest(AndroidP256Primitives.documentDigest(source.delegation)),
                PayloadDigest(AndroidP256Primitives.documentDigest(source.preparation)), point)
        } finally { point.fill(0); encoded.fill(0) }
    }

    /** V2 history is a distinct closed original-target domain. N-only methods are untouched. */
    fun signHistoryEnvelope(allocation: OriginalAllocationCommitmentV2, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray,
        initial: AndroidOriginalCompanionInitialization? = null): ByteArray {
        requireHistoryFrame(allocation,aad,nonce,ciphertext,initial)
        val message = SelfOwnedNativeHistoryWireV2.envelopeSignatureMessage(aad,nonce,ciphertext)
        val signature = try { AndroidP256Primitives.sign(checkedKey(),message) } finally { message.fill(0) }
        return try { checkedKey(); signature } catch (failure: Throwable) { signature.fill(0); throw failure }
    }
    fun verifyHistoryEnvelope(allocation: OriginalAllocationCommitmentV2, aad: ByteArray, envelope: NativeHistoryEnvelopeV2,
        initial: AndroidOriginalCompanionInitialization? = null) {
        requireHistoryFrame(allocation,aad,envelope.nonce,envelope.ciphertext,initial)
        val point = originalPoint(); var message = ByteArray(0)
        try {
            message = SelfOwnedNativeHistoryWireV2.envelopeSignatureMessage(aad,envelope.nonce,envelope.ciphertext)
            if (!AndroidP256Primitives.verify(point,message,envelope.signature)) keyFailure()
        } finally { point.fill(0); message.fill(0) }
        checkedKey(); guard()
    }
    private fun requireHistoryAcquisition(initial: AndroidOriginalCompanionInitialization?) {
        if(initial==null) environment.requireExistingOriginalObservation()
        else { initial.requireSigning(this); environment.requireCurrent() }
    }
    private fun requireHistoryFrame(allocation: OriginalAllocationCommitmentV2, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray,
        initial: AndroidOriginalCompanionInitialization?) {
        requireHistoryAcquisition(initial)
        val point = originalPoint(); var expectedPoint = ByteArray(0)
        var actualCoordinates = ByteArray(0); var expectedCoordinates = ByteArray(0)
        try {
            expectedPoint = allocation.copyTargetPublicKey()
            // Coordinates are an ordinary class: compare all twelve canonical fields, not object identity.
            actualCoordinates = historyCoordinateBytes(allocation.coordinates)
            expectedCoordinates = historyCoordinateBytes(journal.intent().source.preparationBody.coordinates)
            if (!point.contentEquals(expectedPoint) || !actualCoordinates.contentEquals(expectedCoordinates)) keyFailure()
        } finally { point.fill(0); expectedPoint.fill(0); actualCoordinates.fill(0); expectedCoordinates.fill(0) }
        val expected = SelfOwnedNativeHistoryWireV2.envelopeAad(allocation)
        try { if (!expected.contentEquals(aad)) keyFailure() } finally { expected.fill(0) }
        if (nonce.size != 12 || ciphertext.size !in 17..SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES+16) keyFailure()
        guard()
    }

    private fun historyCoordinateBytes(value: SelfOwnedAllocationCoordinatesV1): ByteArray {
        val writer = SelfOwnedWireWriterV1(LocalBranchInstallationWireV2.MAXIMUM_ALLOCATION_BYTES)
        return try { writer.coordinates(value); writer.finish() } finally { writer.clear() }
    }

    fun verifyOriginalHumanApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        proof: LocalInstallationSignedApprovalV2) {
        environment.requireExistingOriginalObservation()
        val source = journal.intent().source
        val root = source.rootBody; val human = snapshot.definition.human
        if (human.rootId != root.rootId || human.rootKeyId != root.rootKeyId ||
            human.rootDocumentDigest.value != AndroidP256Primitives.documentDigest(source.root) ||
            snapshot.definition.allocation.p1DelegationDigest.value != AndroidP256Primitives.documentDigest(source.delegation) ||
            snapshot.definition.allocation.p1PreparationDigest.value != AndroidP256Primitives.documentDigest(source.preparation)) keyFailure()
        val nativeCoordinates = historyCoordinateBytes(source.preparationBody.coordinates)
        val definedCoordinates = historyCoordinateBytes(snapshot.definition.allocation.coordinates)
        try { if (!nativeCoordinates.contentEquals(definedCoordinates)) keyFailure() }
        finally { nativeCoordinates.fill(0); definedCoordinates.fill(0) }
        // This checks the precise head-reference signature, not a self-authenticating founding
        // reference. Actual issuance checks P1's root-signed founding record; receipt delivery
        // additionally requires the ORIGINAL company root's retained ACK over this full D7.
        val expected = LocalBranchInstallationWireV2.humanApprovalFrame(snapshot,attempt)
        val message = expected.copyWireBytes(); var actual = ByteArray(0)
        var signature = ByteArray(0); var point = ByteArray(0)
        try {
            actual = proof.frame.copyWireBytes(); signature = proof.copySignature()
            point = snapshot.definition.human.copyHeadInstallationPublicKey()
            if (!message.contentEquals(actual) || !AndroidP256Primitives.verify(point,message,signature)) keyFailure()
        } finally { message.fill(0); actual.fill(0); signature.fill(0); point.fill(0) }
        guard()
    }
    fun verifyOriginalTargetApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        human: LocalInstallationSignedApprovalV2, proof: LocalInstallationSignedApprovalV2) {
        verifyOriginalHumanApproval(snapshot,attempt,human)
        val expected = LocalBranchInstallationWireV2.targetApprovalFrame(snapshot,attempt,human)
        val message = expected.copyWireBytes(); var actual = ByteArray(0)
        var signature = ByteArray(0); var point = ByteArray(0); var expectedPoint = ByteArray(0)
        try {
            actual = proof.frame.copyWireBytes(); signature = proof.copySignature()
            point = originalPoint(); expectedPoint = snapshot.definition.allocation.copyTargetPublicKey()
            if (!point.contentEquals(expectedPoint) || !message.contentEquals(actual) ||
                !AndroidP256Primitives.verify(point,message,signature)) keyFailure()
        } finally { message.fill(0); actual.fill(0); signature.fill(0); point.fill(0); expectedPoint.fill(0) }
        checkedKey(); guard()
    }
    fun signOriginalTargetApproval(anchor: AndroidSelfOwnedHistoryAnchor, attempt: OriginalInstallationAttempt,
        human: LocalInstallationSignedApprovalV2): LocalInstallationSignedApprovalV2 {
        anchor.requireIssuerParticipant(work,environment)
        anchor.requireOriginalAttemptForIssuance(attempt)
        verifyOriginalHumanApproval(attempt.snapshot,attempt.facts,human)
        val frame = LocalBranchInstallationWireV2.targetApprovalFrame(attempt.snapshot,attempt.facts,human)
        val message = frame.copyWireBytes()
        val signature = try { AndroidP256Primitives.sign(checkedKey(),message) } finally { message.fill(0) }
        return try {
            // Late native signing completion cannot revive a fenced/retiring attempt.
            anchor.requireOriginalAttemptForIssuance(attempt)
            LocalBranchInstallationWireV2.signedApproval(frame,signature).also {
                verifyOriginalTargetApproval(attempt.snapshot,attempt.facts,human,it)
            }
        } finally { signature.fill(0) }
    }
    fun verifyOriginalAcknowledgement(value: LocalInstallationSignedAcknowledgementV2) {
        environment.requireExistingOriginalObservation()
        val root = journal.intent().source.rootBody
        if (value.frame.rootKeyId != root.rootKeyId) keyFailure()
        val message = value.frame.copyWireBytes(); var signature = ByteArray(0); var point = ByteArray(0)
        try {
            signature = value.copySignature(); point = root.rootPublicKey.copyBytes()
            if (!AndroidP256Primitives.verify(point,message,signature)) keyFailure()
        } finally { message.fill(0); signature.fill(0); point.fill(0) }
        guard()
    }

    private fun requireJournalFrame(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray) {
        val expected = SelfOwnedNativeInstallationWireV1.stateAad(journal.intent())
        try { if (!expected.contentEquals(aad)) keyFailure() } finally { expected.fill(0) }
        if (nonce.size != 12 || ciphertext.size !in 17..SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES + 16) keyFailure()
        guard()
    }

    private fun expectedBindingBody(): SelfOwnedNativeBindingBodyV1 {
        val original = journal.intent()
        if (!original.complete() || !original.bindingStarted) keyFailure()
        verifyOriginalSource()
        val encoded = SelfOwnedNativeInstallationWireV1.encodeIntent(original)
        val intentDigest = try { AndroidP256Primitives.hashHex(encoded) } finally { encoded.fill(0) }
        val point = originalPoint()
        return try {
            SelfOwnedNativeBindingBodyV1(original.source.preparationBody.coordinates, original.source.preparationBody.target,
                AndroidP256Primitives.documentDigest(original.source.root), AndroidP256Primitives.documentDigest(original.source.delegation),
                AndroidP256Primitives.documentDigest(original.source.preparation), intentDigest, SelfOwnedOctetsV1(point),
                original.source.preparationBody.protection, requireNotNull(original.key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING).security),
                requireNotNull(original.key(SelfOwnedTargetKeyRoleV1.STORAGE).security), requireNotNull(original.key(SelfOwnedTargetKeyRoleV1.PEPPER).security))
        } finally { point.fill(0) }
    }

    private fun checkedKey(): PrivateKey {
        guard()
        val original = journal.intent(); val intent = original.key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE) keyFailure()
        val key = load(original)
        if (inspect(original, key) != intent.security) keyFailure()
        val point = nativePoint(original)
        try {
            if (!intent.proof.same(point)) keyFailure()
            prove(original, key, point)
        } finally { point.fill(0) }
        guard()
        if (inspect(original, load(original)) != intent.security) keyFailure()
        return key
    }

    private fun load(original: SelfOwnedNativeIntentV1): PrivateKey {
        val alias = aliasFor(original, SelfOwnedTargetKeyRoleV1.TARGET_SIGNING)
        if (!store.containsAlias(alias)) keyFailure()
        val key = store.getKey(alias, null) as? PrivateKey ?: keyFailure()
        if (key.algorithm != KeyProperties.KEY_ALGORITHM_EC) keyFailure()
        return key
    }

    private fun nativePoint(original: SelfOwnedNativeIntentV1): ByteArray {
        val public = store.getCertificate(aliasFor(original, SelfOwnedTargetKeyRoleV1.TARGET_SIGNING))?.publicKey as? ECPublicKey ?: keyFailure()
        return AndroidP256Primitives.point(public)
    }

    private fun inspect(original: SelfOwnedNativeIntentV1, key: PrivateKey): SelfOwnedNativeSecurityV1 {
        requireNativeSelfOwnedProtection(original.source.preparationBody.protection)
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        if (info.keystoreAlias != aliasFor(original, SelfOwnedTargetKeyRoleV1.TARGET_SIGNING) || info.keySize != 256 ||
            info.purposes != (KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY) || info.origin != KeyProperties.ORIGIN_GENERATED ||
            info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) || info.blockModes.isNotEmpty() || info.encryptionPaddings.isNotEmpty() ||
            info.signaturePaddings.isNotEmpty() || AndroidNativeKeyInfo.hasServiceRestrictions(info)) keyFailure()
        val observed = try { AndroidNativeKeyInfo.observe(info) } catch (_: Exception) { keyFailure() }
        if (!AndroidNativeKeyPolicy.permits(original.source.preparationBody.protection, observed)) keyFailure()
        return observed.selfOwnedSecurity().originalWitness(original.key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING).security)
    }

    private fun generate(original: SelfOwnedNativeIntentV1) {
        val intent = original.key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.STARTED || intent.startedBoot != environment.bootCount) keyFailure()
        val builder = KeyGenParameterSpec.Builder(aliasFor(original, intent.role), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
        AndroidNativeKeyInfo.configureService(builder, original.source.preparationBody.protection)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(builder.build()) }.generateKeyPair()
    }

    private fun prove(original: SelfOwnedNativeIntentV1, key: PrivateKey, point: ByteArray) {
        val binding = SelfOwnedNativeInstallationWireV1.keyBinding(original, SelfOwnedTargetKeyRoleV1.TARGET_SIGNING)
        val nonce = AndroidP256Primitives.randomBytes(32)
        val message = try {
            selfOwnedEncode("native-target-fresh-possession/v1", SelfOwnedNativeInstallationFormatV1.MAXIMUM_CONTROL_FRAME_BYTES) {
                field(binding); field(nonce)
            }
        } finally { binding.fill(0); nonce.fill(0) }
        val signature = try { AndroidP256Primitives.sign(key, message) }
        catch (failure: Throwable) { message.fill(0); throw failure }
        try { if (!AndroidP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { signature.fill(0); message.fill(0) }
    }

    private fun aliasFor(original: SelfOwnedNativeIntentV1, role: SelfOwnedTargetKeyRoleV1): String {
        val coordinates = original.source.preparationBody.coordinates
        return when (role) {
            SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> "${environment.profile.p2SigningPrefix}${coordinates.installationId.value}.target.${coordinates.targetSigningKeyId.value}"
            SelfOwnedTargetKeyRoleV1.STORAGE -> "${environment.profile.p2DataPrefix}${coordinates.installationId.value}/storage/${coordinates.storageKeyId.value}"
            SelfOwnedTargetKeyRoleV1.PEPPER -> "${environment.profile.p2DataPrefix}${coordinates.installationId.value}/pepper/${coordinates.pepperKeyId.value}"
        }
    }
    private fun guard() { work.requireActive(); environment.requireCurrent(); journal.intent(); work.requireActive() }
    private fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)

}
