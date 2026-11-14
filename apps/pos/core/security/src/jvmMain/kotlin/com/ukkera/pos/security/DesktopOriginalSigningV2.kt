package com.ukkera.pos.security

/**
 * Key-intent lifecycle shared by the Windows/macOS original target keys (V2 journal). A key is
 * created only after its STARTED intent naming the CURRENT boot session is durably retained. A
 * STARTED intent whose key already exists is adopted only in a LATER boot of the retained chain
 * (never a same-boot replay of a possibly still-completing creation); NOT_STARTED with an existing
 * key, or COMPLETE without one, is KEY_STATE_UNCERTAIN.
 */
internal abstract class DesktopOriginalKeyLifecycleV2(
    protected val environment: SelfOwnedOriginalEnvironment,
    protected val journal: AndroidSelfOwnedInstallationJournal,
    protected val work: AndroidOwnedInstallationWork,
    protected val custody: DesktopKeyCustody,
    protected val security: SelfOwnedNativeSecurityV1,
) {
    protected fun alias(original: SelfOwnedNativeIntentV2, role: SelfOwnedTargetKeyRoleV1): String =
        DesktopKeyAliases.target(environment.profile, original.key(role).keyId)

    protected fun present(original: SelfOwnedNativeIntentV2, role: SelfOwnedTargetKeyRoleV1): Boolean = when (role) {
        SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> custody.p256Point(alias(original, role))?.also { it.fill(0) } != null
        else -> custody.symmetricCheck(alias(original, role))?.also { it.fill(0) } != null
    }

    /** Returns the intent ready for proof (STARTED with the key present). */
    protected fun startOrAdopt(role: SelfOwnedTargetKeyRoleV1): SelfOwnedNativeIntentV2 {
        var original = guard(); val intent = original.key(role)
        environment.requireSupportedProtection(original.source.protection)
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.STARTED && !laterBoot(requireNotNull(intent.startedBoot))) keyFailure()
        val exists = present(original, role)
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED && exists) keyFailure()
        if (!exists) {
            journal.retainIntent(original.replaceKey(intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED,
                startedBoot = currentSession(), security = null, proof = SelfOwnedOctetsV1(ByteArray(0)))))
            original = guard()
            if (present(original, role)) keyFailure()
            try {
                when (role) {
                    SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> custody.createP256(alias(original, role)).fill(0)
                    SelfOwnedTargetKeyRoleV1.STORAGE -> custody.createAes(alias(original, role))
                    SelfOwnedTargetKeyRoleV1.PEPPER -> custody.createHmac(alias(original, role))
                }
            } catch (failure: SelfOwnedFailureV1) { throw failure } catch (_: Exception) { keyFailure() }
        }
        return guard()
    }

    protected fun complete(original: SelfOwnedNativeIntentV2, role: SelfOwnedTargetKeyRoleV1, proof: ByteArray) {
        if (guard() !== original) keyFailure()
        journal.retainIntent(original.replaceKey(original.key(role).copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE,
            security = security, proof = SelfOwnedOctetsV1(proof))))
    }

    protected fun currentSession(): SelfOwnedNativeBootSessionV2 =
        (environment.bootMark as? SelfOwnedBootMark.Session)?.session ?: keyFailure()

    /** [started] is a strictly earlier boot of the verified retained chain. */
    private fun laterBoot(started: SelfOwnedNativeBootSessionV2): Boolean {
        val mark = environment.bootMark as? SelfOwnedBootMark.Session ?: return false
        val chain = SelfOwnedBootChainV2.verify(environment.bootLedgerChain(), mark.ledger) ?: return false
        val earlier = chain.ordinalOf(started) ?: return false
        return earlier < mark.ledger.ordinal
    }

    protected fun guard(): SelfOwnedNativeIntentV2 {
        work.requireActive()
        val original = journal.intentV2()
        work.requireActive()
        return original
    }

    protected fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
}

/** Original V2 target signing identity. Only fixed native-binding/journal/history purposes. */
internal class DesktopOriginalSigningV2(
    environment: SelfOwnedOriginalEnvironment,
    journal: AndroidSelfOwnedInstallationJournal,
    work: AndroidOwnedInstallationWork,
    custody: DesktopKeyCustody,
    security: SelfOwnedNativeSecurityV1,
) : DesktopOriginalKeyLifecycleV2(environment, journal, work, custody, security), SelfOwnedOriginalSigning {
    private val role = SelfOwnedTargetKeyRoleV1.TARGET_SIGNING
    @Volatile private var verifiedOriginalSource: SelfOwnedNativeSourceV2? = null

    override fun assertAliasNamespace() {
        work.requireActive(); environment.requireCurrent()
        val original = journal.intentV2OrNull() ?: return
        for (key in original.keys) {
            val exists = present(original, key.role)
            when (key.phase) {
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> if (exists) keyFailure()
                SelfOwnedKeyIntentPhaseV1.COMPLETE -> if (!exists) keyFailure()
                SelfOwnedKeyIntentPhaseV1.STARTED -> Unit
            }
        }
        environment.requireCurrent(); work.requireActive()
    }

    override fun prepareOriginalSigningKey() {
        assertAliasNamespace()
        if (guard().key(role).phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) { checkedPoint().fill(0); return }
        val original = startOrAdopt(role)
        val point = custody.p256Point(alias(original, role)) ?: keyFailure()
        try {
            prove(original, point)
            complete(original, role, point)
        } finally { point.fill(0) }
        checkedPoint().fill(0)
    }

    override fun assertOriginalSigningKey() { checkedPoint().fill(0); guard() }

    override fun originalPoint(): ByteArray = checkedPoint()

    override fun verifyOriginalSource() {
        val source = guard().source
        if (!SelfOwnedInstalledIdentityV2.matches(source.installedTarget.target, (environment.installedTarget as?
                SelfOwnedNativeInstalledTargetBindingV1 ?: keyFailure()).target)) keyFailure()
        if (verifiedOriginalSource !== source) {
            val rootPoint = source.rootBody.rootPublicKey.copyBytes()
            val issuerPoint = source.delegationBody.issuerPublicKey.copyBytes()
            val prepared = source.preparation.signedBody.copyWireBytes()
            val signature = source.preparation.copySignature()
            try {
                if (!JvmP256Primitives.verifyDocument(source.root, rootPoint) ||
                    !JvmP256Primitives.verifyDocument(source.delegation, rootPoint) ||
                    !JvmP256Primitives.verify(issuerPoint, prepared, signature)) keyFailure()
            } finally { rootPoint.fill(0); issuerPoint.fill(0); prepared.fill(0); signature.fill(0) }
        }
        guard()
        verifiedOriginalSource = source
    }

    override fun createOriginalBinding(): SelfOwnedNativeBindingV2 {
        val body = SelfOwnedNativeInstallationWireV2.encodeBindingBody(expectedBindingBody(currentSession()))
        val message = try { SelfOwnedNativeInstallationWireV2.bindingSigningMessage(body) }
        catch (failure: Throwable) { body.fill(0); throw failure }
        try {
            val signature = sign(message)
            try { return SelfOwnedNativeBindingV2(body, signature).also { validateOriginalBinding(it) } }
            finally { signature.fill(0) }
        } finally { body.fill(0); message.fill(0) }
    }

    override fun validateOriginalBinding(binding: SelfOwnedNativeOriginalBinding) {
        val actual = (binding as? SelfOwnedNativeBindingV2 ?: keyFailure()).body()
        val signature = binding.signature()
        val point = checkedPoint()
        try {
            val session = bindingSession(actual)
            val expected = SelfOwnedNativeInstallationWireV2.encodeBindingBody(expectedBindingBody(session))
            try { if (!expected.contentEquals(actual)) keyFailure() } finally { expected.fill(0) }
            val message = SelfOwnedNativeInstallationWireV2.bindingSigningMessage(actual)
            try { if (!JvmP256Primitives.verify(point, message, signature)) keyFailure() } finally { message.fill(0) }
            checkedPoint().fill(0); guard()
        } finally { actual.fill(0); signature.fill(0); point.fill(0) }
    }

    override fun signJournalEnvelope(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        requireJournalFrame(aad, nonce, ciphertext)
        val message = SelfOwnedNativeInstallationWireV2.stateSignature(aad, nonce, ciphertext)
        return try { sign(message) } finally { message.fill(0) }
    }

    override fun openOriginalJournalState(aad: ByteArray, envelope: SelfOwnedOriginalJournalEnvelope,
        material: SelfOwnedOriginalDataKeys): SelfOwnedOriginalJournalState {
        val v2 = (envelope as? SelfOwnedOriginalJournalEnvelope.V2)?.value ?: keyFailure()
        requireJournalFrame(aad, v2.nonce, v2.ciphertext)
        val original = guard()
        val point = checkedPoint()
        material.assertOriginalPepperKey()
        try {
            val message = SelfOwnedNativeInstallationWireV2.stateSignature(aad, v2.nonce, v2.ciphertext)
            try { if (!JvmP256Primitives.verify(point, message, v2.signature)) keyFailure() } finally { message.fill(0) }
            val plaintext = material.openJournal(aad, v2.nonce, v2.ciphertext)
            try {
                val state = desktopKnownData(SelfOwnedNativeInstallationWireV2.decodeState(plaintext))
                val canonical = SelfOwnedNativeInstallationWireV2.encodeState(state)
                try { if (!canonical.contentEquals(plaintext)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
                finally { canonical.fill(0) }
                validateOriginalBinding(state.originalBinding)
                if (guard() !== original) keyFailure()
                material.assertOriginalPepperKey()
                return SelfOwnedOriginalJournalState.V2(state)
            } finally { plaintext.fill(0) }
        } finally { point.fill(0) }
    }

    override fun originalHistoryAllocation(binding: SelfOwnedNativeOriginalBinding,
        initial: AndroidOriginalCompanionInitialization?): OriginalAllocationCommitmentV2 {
        requireHistoryAcquisition(initial)
        validateOriginalBinding(binding)
        val source = guard().source
        val point = checkedPoint(); var encoded = ByteArray(0)
        return try {
            encoded = binding.copyWireBytes()
            OriginalAllocationCommitmentV2(source.coordinates, sha256(encoded), PayloadDigest(source.rootDocumentDigest),
                PayloadDigest(source.delegationDigest), PayloadDigest(source.preparationDigest), point)
        } finally { point.fill(0); encoded.fill(0) }
    }

    override fun signHistoryEnvelope(allocation: OriginalAllocationCommitmentV2, aad: ByteArray, nonce: ByteArray,
        ciphertext: ByteArray, initial: AndroidOriginalCompanionInitialization?): ByteArray {
        requireHistoryFrame(allocation, aad, nonce, ciphertext, initial)
        val message = SelfOwnedNativeHistoryWireV2.envelopeSignatureMessage(aad, nonce, ciphertext)
        return try { sign(message) } finally { message.fill(0) }
    }

    override fun verifyHistoryEnvelope(allocation: OriginalAllocationCommitmentV2, aad: ByteArray,
        envelope: NativeHistoryEnvelopeV2, initial: AndroidOriginalCompanionInitialization?) {
        requireHistoryFrame(allocation, aad, envelope.nonce, envelope.ciphertext, initial)
        val point = checkedPoint(); var message = ByteArray(0)
        try {
            message = SelfOwnedNativeHistoryWireV2.envelopeSignatureMessage(aad, envelope.nonce, envelope.ciphertext)
            if (!JvmP256Primitives.verify(point, message, envelope.signature)) keyFailure()
        } finally { point.fill(0); message.fill(0) }
        guard()
    }

    override fun verifyOriginalHumanApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        proof: LocalInstallationSignedApprovalV2) {
        environment.requireExistingOriginalObservation()
        val source = guard().source
        val root = source.rootBody; val human = snapshot.definition.human
        if (human.rootId != root.rootId || human.rootKeyId != root.rootKeyId ||
            human.rootDocumentDigest.value != source.rootDocumentDigest ||
            snapshot.definition.allocation.p1DelegationDigest.value != source.delegationDigest ||
            snapshot.definition.allocation.p1PreparationDigest.value != source.preparationDigest) keyFailure()
        val nativeCoordinates = coordinateBytes(source.coordinates)
        val definedCoordinates = coordinateBytes(snapshot.definition.allocation.coordinates)
        try { if (!nativeCoordinates.contentEquals(definedCoordinates)) keyFailure() }
        finally { nativeCoordinates.fill(0); definedCoordinates.fill(0) }
        val message = LocalBranchInstallationWireV2.humanApprovalFrame(snapshot, attempt).copyWireBytes()
        var actual = ByteArray(0); var signature = ByteArray(0); var point = ByteArray(0)
        try {
            actual = proof.frame.copyWireBytes(); signature = proof.copySignature()
            point = snapshot.definition.human.copyHeadInstallationPublicKey()
            if (!message.contentEquals(actual) || !JvmP256Primitives.verify(point, message, signature)) keyFailure()
        } finally { message.fill(0); actual.fill(0); signature.fill(0); point.fill(0) }
        guard()
    }

    override fun verifyOriginalTargetApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        human: LocalInstallationSignedApprovalV2, proof: LocalInstallationSignedApprovalV2) {
        verifyOriginalHumanApproval(snapshot, attempt, human)
        val message = LocalBranchInstallationWireV2.targetApprovalFrame(snapshot, attempt, human).copyWireBytes()
        var actual = ByteArray(0); var signature = ByteArray(0); var point = ByteArray(0); var expectedPoint = ByteArray(0)
        try {
            actual = proof.frame.copyWireBytes(); signature = proof.copySignature()
            point = checkedPoint(); expectedPoint = snapshot.definition.allocation.copyTargetPublicKey()
            if (!point.contentEquals(expectedPoint) || !message.contentEquals(actual) ||
                !JvmP256Primitives.verify(point, message, signature)) keyFailure()
        } finally { message.fill(0); actual.fill(0); signature.fill(0); point.fill(0); expectedPoint.fill(0) }
        guard()
    }

    override fun signOriginalTargetApproval(anchor: AndroidSelfOwnedHistoryAnchor, attempt: OriginalInstallationAttempt,
        human: LocalInstallationSignedApprovalV2): LocalInstallationSignedApprovalV2 {
        anchor.requireIssuerParticipant(work, environment)
        anchor.requireOriginalAttemptForIssuance(attempt)
        verifyOriginalHumanApproval(attempt.snapshot, attempt.facts, human)
        val frame = LocalBranchInstallationWireV2.targetApprovalFrame(attempt.snapshot, attempt.facts, human)
        val message = frame.copyWireBytes()
        val signature = try { sign(message) } finally { message.fill(0) }
        return try {
            anchor.requireOriginalAttemptForIssuance(attempt)
            LocalBranchInstallationWireV2.signedApproval(frame, signature).also {
                verifyOriginalTargetApproval(attempt.snapshot, attempt.facts, human, it)
            }
        } finally { signature.fill(0) }
    }

    override fun verifyOriginalAcknowledgement(value: LocalInstallationSignedAcknowledgementV2) {
        environment.requireExistingOriginalObservation()
        val root = guard().source.rootBody
        if (value.frame.rootKeyId != root.rootKeyId) keyFailure()
        val message = value.frame.copyWireBytes(); var signature = ByteArray(0); var point = ByteArray(0)
        try {
            signature = value.copySignature(); point = root.rootPublicKey.copyBytes()
            if (!JvmP256Primitives.verify(point, message, signature)) keyFailure()
        } finally { message.fill(0); signature.fill(0); point.fill(0) }
        guard()
    }

    // ---- internals ----

    private fun sign(message: ByteArray): ByteArray {
        val point = checkedPoint()
        try {
            val raw = custody.signP256(alias(guard(), role), message)
            val signature = try { JvmP256Primitives.canonicalize(raw) } finally { raw.fill(0) }
            if (!JvmP256Primitives.verify(point, message, signature)) { signature.fill(0); keyFailure() }
            return signature
        } finally { point.fill(0) }
    }

    /** Exact COMPLETE target key: present, same point as its retained proof, fresh possession. */
    private fun checkedPoint(): ByteArray {
        val original = guard(); val intent = original.key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE || intent.security != security) keyFailure()
        val point = custody.p256Point(alias(original, role)) ?: keyFailure()
        try {
            if (!intent.proof.same(point)) keyFailure()
            prove(original, point)
            if (guard() !== original) keyFailure()
            return point
        } catch (failure: Throwable) { point.fill(0); throw failure }
    }

    private fun prove(original: SelfOwnedNativeIntentV2, point: ByteArray) {
        val binding = SelfOwnedNativeInstallationWireV2.keyBinding(original, role)
        val nonce = JvmP256Primitives.randomBytes(32)
        val message = try {
            selfOwnedEncode(FRESH_POSSESSION, SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES) {
                field(binding); field(nonce)
            }
        } finally { binding.fill(0); nonce.fill(0) }
        try {
            val raw = try { custody.signP256(alias(original, role), message) }
            catch (failure: SelfOwnedFailureV1) { throw failure } catch (_: Exception) { keyFailure() }
            try { if (!JvmP256Primitives.verify(point, message, JvmP256Primitives.canonicalize(raw))) keyFailure() }
            finally { raw.fill(0) }
        } finally { message.fill(0) }
    }

    private fun bindingSession(body: ByteArray): SelfOwnedNativeBootSessionV2 {
        val decoded = desktopKnownData(SelfOwnedNativeInstallationWireV2.decodeBindingBody(body))
        val mark = environment.bootMark as? SelfOwnedBootMark.Session ?: keyFailure()
        val chain = SelfOwnedBootChainV2.verify(environment.bootLedgerChain(), mark.ledger) ?: keyFailure()
        if (chain.ordinalOf(decoded.bootSession) == null) keyFailure()
        return decoded.bootSession
    }

    private fun expectedBindingBody(session: SelfOwnedNativeBootSessionV2): SelfOwnedNativeBindingBodyV2 {
        val original = guard()
        if (!original.complete() || !original.bindingStarted) keyFailure()
        verifyOriginalSource()
        val point = checkedPoint()
        return try {
            val source = original.source
            SelfOwnedNativeBindingBodyV2(source.coordinates, source.installedTarget, original.namespace, session,
                source.rootDocumentDigest, source.delegationDigest, source.preparationDigest,
                SelfOwnedNativeInstallationWireV2.completedIntentDigest(original), SelfOwnedOctetsV1(point),
                source.protection, original.keys)
        } finally { point.fill(0) }
    }

    private fun requireJournalFrame(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray) {
        val expected = SelfOwnedNativeInstallationWireV2.stateAad(guard())
        try { if (!expected.contentEquals(aad)) keyFailure() } finally { expected.fill(0) }
        if (nonce.size != 12 || ciphertext.size !in 17..SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES + 16) keyFailure()
        guard()
    }

    private fun requireHistoryAcquisition(initial: AndroidOriginalCompanionInitialization?) {
        if (initial == null) environment.requireExistingOriginalObservation()
        else { initial.requireSigning(this); environment.requireCurrent() }
    }

    private fun requireHistoryFrame(allocation: OriginalAllocationCommitmentV2, aad: ByteArray, nonce: ByteArray,
        ciphertext: ByteArray, initial: AndroidOriginalCompanionInitialization?) {
        requireHistoryAcquisition(initial)
        val point = checkedPoint(); var expectedPoint = ByteArray(0)
        var actualCoordinates = ByteArray(0); var expectedCoordinates = ByteArray(0)
        try {
            expectedPoint = allocation.copyTargetPublicKey()
            actualCoordinates = coordinateBytes(allocation.coordinates)
            expectedCoordinates = coordinateBytes(guard().source.coordinates)
            if (!point.contentEquals(expectedPoint) || !actualCoordinates.contentEquals(expectedCoordinates)) keyFailure()
        } finally { point.fill(0); expectedPoint.fill(0); actualCoordinates.fill(0); expectedCoordinates.fill(0) }
        val expected = SelfOwnedNativeHistoryWireV2.envelopeAad(allocation)
        try { if (!expected.contentEquals(aad)) keyFailure() } finally { expected.fill(0) }
        if (nonce.size != 12 || ciphertext.size !in 17..SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES + 16) keyFailure()
        guard()
    }

    private fun coordinateBytes(value: SelfOwnedAllocationCoordinatesV1): ByteArray {
        val writer = SelfOwnedWireWriterV1(LocalBranchInstallationWireV2.MAXIMUM_ALLOCATION_BYTES)
        return try { writer.coordinates(value); writer.finish() } finally { writer.clear() }
    }

    override fun toString(): String = "DesktopOriginalSigningV2([REDACTED])"

    private companion object { const val FRESH_POSSESSION = "native-target-fresh-possession/v2" }
}

/** Original V2 storage (AES-256-GCM) and pepper (HMAC-SHA256) keys over [DesktopKeyCustody]. */
internal class DesktopOriginalDataKeysV2(
    environment: SelfOwnedOriginalEnvironment,
    journal: AndroidSelfOwnedInstallationJournal,
    work: AndroidOwnedInstallationWork,
    custody: DesktopKeyCustody,
    security: SelfOwnedNativeSecurityV1,
) : DesktopOriginalKeyLifecycleV2(environment, journal, work, custody, security), SelfOwnedOriginalDataKeys {

    override fun prepareOriginalDataKey(role: SelfOwnedTargetKeyRoleV1) {
        if (role == SelfOwnedTargetKeyRoleV1.TARGET_SIGNING) keyFailure()
        if (guard().key(role).phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) { checked(role); return }
        val original = startOrAdopt(role)
        val proof = createProof(original, role)
        try {
            verifyProof(original, role, proof)
            complete(original, role, proof)
        } finally { proof.fill(0) }
        checked(role)
    }

    override fun assertOriginalDataKeys() { checked(SelfOwnedTargetKeyRoleV1.STORAGE); checked(SelfOwnedTargetKeyRoleV1.PEPPER); guard() }
    override fun assertOriginalPepperKey() { checked(SelfOwnedTargetKeyRoleV1.PEPPER); guard() }
    override fun requireStorageKey() = checked(SelfOwnedTargetKeyRoleV1.STORAGE)
    override fun requirePepperKey() = checked(SelfOwnedTargetKeyRoleV1.PEPPER)

    override fun sealStorage(aad: ByteArray, plaintext: ByteArray, contractFailure: SelfOwnedInstallationFailureV1): AndroidSelfOwnedCiphertext {
        checked(SelfOwnedTargetKeyRoleV1.STORAGE)
        val (nonce, ciphertext) = custody.sealAes(alias(guard(), SelfOwnedTargetKeyRoleV1.STORAGE), aad, plaintext)
        if (nonce.size != ProtectedLocalStorageFormat.NONCE_BYTES || ciphertext.size != plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES) {
            nonce.fill(0); ciphertext.fill(0); selfOwnedFail(contractFailure)
        }
        return AndroidSelfOwnedCiphertext(nonce, ciphertext)
    }

    override fun openStorage(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        checked(SelfOwnedTargetKeyRoleV1.STORAGE)
        return custody.openAes(alias(guard(), SelfOwnedTargetKeyRoleV1.STORAGE), aad, nonce, ciphertext)
    }

    override fun pepperTag(message: ByteArray): ByteArray {
        checked(SelfOwnedTargetKeyRoleV1.PEPPER)
        return custody.hmac(alias(guard(), SelfOwnedTargetKeyRoleV1.PEPPER), message)
    }

    override fun keyConfiguration(): SelfOwnedDataKeyConfiguration {
        val coordinates = guard().source.coordinates
        return SelfOwnedDataKeyConfiguration(coordinates.installationId, coordinates.scope,
            ProtectedStorageKeyReference(coordinates.storageKeyId), ProtectedPepperReference(coordinates.pepperKeyId))
    }

    override fun sealJournal(aad: ByteArray, plaintext: ByteArray): AndroidSelfOwnedCiphertext {
        requireJournalAad(aad)
        if (plaintext.size !in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES) keyFailure()
        val sealed = sealStorage(aad, plaintext, SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        checked(SelfOwnedTargetKeyRoleV1.STORAGE)
        return sealed
    }

    override fun openJournal(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        requireJournalAad(aad)
        if (nonce.size != 12 || ciphertext.size !in 17..SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES + 16) keyFailure()
        val plaintext = try { openStorage(aad, nonce, ciphertext) }
        catch (failure: SelfOwnedFailureV1) { throw failure } catch (_: Exception) { keyFailure() }
        try {
            if (plaintext.size + 16 != ciphertext.size) keyFailure()
            checked(SelfOwnedTargetKeyRoleV1.STORAGE)
            return plaintext
        } catch (failure: Throwable) { plaintext.fill(0); throw failure }
    }

    private fun requireJournalAad(supplied: ByteArray) {
        val expected = SelfOwnedNativeInstallationWireV2.stateAad(guard())
        try { if (!expected.contentEquals(supplied)) keyFailure() } finally { expected.fill(0) }
        guard()
    }

    private fun checked(role: SelfOwnedTargetKeyRoleV1) {
        val original = guard(); val intent = original.key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE || intent.security != security) keyFailure()
        val proof = intent.proof.copyBytes()
        try { verifyProof(original, role, proof) } finally { proof.fill(0) }
        if (guard() !== original) keyFailure()
    }

    /** STORAGE: 0x01 || nonce(12) || AES-GCM(hash(binding), aad = binding); PEPPER: HMAC(binding). */
    private fun createProof(original: SelfOwnedNativeIntentV2, role: SelfOwnedTargetKeyRoleV1): ByteArray {
        val binding = SelfOwnedNativeInstallationWireV2.keyBinding(original, role)
        try {
            return when (role) {
                SelfOwnedTargetKeyRoleV1.STORAGE -> {
                    val expected = JvmP256Primitives.hash(binding)
                    val (nonce, encrypted) = try { custody.sealAes(alias(original, role), binding, expected) } finally { expected.fill(0) }
                    try {
                        if (nonce.size != 12 || encrypted.size != 48) keyFailure()
                        byteArrayOf(1) + nonce + encrypted
                    } finally { nonce.fill(0); encrypted.fill(0) }
                }
                SelfOwnedTargetKeyRoleV1.PEPPER -> custody.hmac(alias(original, role), binding).also { if (it.size != 32) keyFailure() }
                SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> keyFailure()
            }
        } finally { binding.fill(0) }
    }

    private fun verifyProof(original: SelfOwnedNativeIntentV2, role: SelfOwnedTargetKeyRoleV1, proof: ByteArray) {
        val binding = SelfOwnedNativeInstallationWireV2.keyBinding(original, role)
        try {
            when (role) {
                SelfOwnedTargetKeyRoleV1.STORAGE -> {
                    if (proof.size != 61 || proof[0].toInt() != 1) keyFailure()
                    val expected = JvmP256Primitives.hash(binding)
                    val actual = try { custody.openAes(alias(original, role), binding, proof.copyOfRange(1, 13), proof.copyOfRange(13, 61)) }
                    catch (failure: SelfOwnedFailureV1) { expected.fill(0); throw failure }
                    catch (_: Exception) { expected.fill(0); keyFailure() }
                    try { if (!JvmP256Primitives.same(expected, actual)) keyFailure() } finally { expected.fill(0); actual.fill(0) }
                }
                SelfOwnedTargetKeyRoleV1.PEPPER -> {
                    val actual = custody.hmac(alias(original, role), binding)
                    try { if (!JvmP256Primitives.same(proof, actual)) keyFailure() } finally { actual.fill(0) }
                }
                SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> keyFailure()
            }
        } finally { binding.fill(0) }
    }

    override fun toString(): String = "DesktopOriginalDataKeysV2([REDACTED])"
}

private fun <T> desktopKnownData(result: SelfOwnedInstalledIdentityResultV2<T>): T = when (result) {
    is SelfOwnedInstalledIdentityResultV2.Data -> result.value
    is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}
