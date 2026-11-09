package com.ukkera.pos.security

/** Actual original target-journal transitions. File custody is the SAME retained namespace. */
internal class AndroidSelfOwnedInstallationJournal(
    private val namespace: AndroidSelfOwnedDatabaseNamespace,
    private val environment: AndroidSelfOwnedEnvironment,
    private val work: AndroidOwnedInstallationWork,
    private val ownerToken: Any,
) {
    private var originalIntent: SelfOwnedNativeIntentV1? = null
    private var initialBindingCommitArmed = false

    fun openNative() {
        environment.requireCurrent(); namespace.assertContinuity()
        val bytes = namespace.readIntent()
        originalIntent = if (bytes == null) null else try {
            val decoded = SelfOwnedNativeInstallationWireV1.decodeIntent(bytes)
            val canonical = SelfOwnedNativeInstallationWireV1.encodeIntent(decoded)
            try { known(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            known(decoded.namespace == namespace.identity() && decoded.source.preparationBody.target.same(environment.target))
            known(decoded.keys.all { it.startedBoot == null || it.startedBoot <= environment.bootCount })
            decoded
        } finally { bytes.fill(0) }
        val state = namespace.readState()
        try { known(state == null || originalIntent?.let { it.complete() && it.bindingStarted } == true) }
        finally { state?.fill(0) }
        work.requireActive()
    }

    fun intentOrNull(): SelfOwnedNativeIntentV1? { namespace.assertContinuity(); return originalIntent }
    fun intent(): SelfOwnedNativeIntentV1 = intentOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)

    /** One initial prior intent; an existing namespace missing it can NEVER start again. */
    fun initializeOriginal(approval: SelfOwnedNativeApproval) {
        environment.requireCurrent(); namespace.assertContinuity()
        known(namespace.createdThisLifetime && originalIntent == null)
        if (approval.owner !== ownerToken || approval.bootCount != environment.bootCount || work.clock() >= approval.expiresElapsedMillis ||
            !approval.source.preparationBody.target.same(environment.target)) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val coordinates = approval.source.preparationBody.coordinates
        val nonce = AndroidP256Primitives.randomBytes(32)
        val next = try {
            SelfOwnedNativeIntentV1(0L, approval.source, namespace.identity(), SelfOwnedOctetsV1(nonce),
                listOf(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING to coordinates.targetSigningKeyId,
                    SelfOwnedTargetKeyRoleV1.STORAGE to coordinates.storageKeyId, SelfOwnedTargetKeyRoleV1.PEPPER to coordinates.pepperKeyId)
                    .map { (role, keyId) -> SelfOwnedTargetKeyIntentV1(role, keyId, SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                        null, null, SelfOwnedOctetsV1(ByteArray(0))) }, false)
        } finally { nonce.fill(0) }
        retainIntent(next)
    }

    /** The original packet/namespace/nonce/IDs never change as individual key intents advance. */
    fun retainIntent(next: SelfOwnedNativeIntentV1) {
        namespace.assertContinuity()
        known(next.namespace == namespace.identity() && next.source.preparationBody.target.same(environment.target))
        val stateBytes = namespace.readState()
        try { known(stateBytes == null) } finally { stateBytes?.fill(0) }
        val previous = originalIntent
        if (previous == null) {
            known(namespace.createdThisLifetime && next.revision == 0L && !next.bindingStarted &&
                next.keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED })
        } else {
            known(next.revision == previous.revision + 1L && next.source.same(previous.source) && next.namespace == previous.namespace &&
                next.nonce == previous.nonce && next.keys.map { it.keyId } == previous.keys.map { it.keyId })
            val changed = previous.keys.zip(next.keys).filter { (left, right) -> left != right }
            if (changed.isEmpty()) known(previous.complete() && !previous.bindingStarted && next.bindingStarted)
            else {
                known(changed.size == 1 && !previous.bindingStarted && !next.bindingStarted)
                val (before, after) = changed.single()
                known(before.role == after.role && before.keyId == after.keyId)
                when (before.phase) {
                    SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> known(after.phase == SelfOwnedKeyIntentPhaseV1.STARTED &&
                        after.startedBoot == environment.bootCount)
                    SelfOwnedKeyIntentPhaseV1.STARTED -> known(
                        after.phase == SelfOwnedKeyIntentPhaseV1.STARTED && after.startedBoot == environment.bootCount &&
                            requireNotNull(after.startedBoot) > requireNotNull(before.startedBoot) ||
                            after.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE && after.startedBoot == before.startedBoot)
                    SelfOwnedKeyIntentPhaseV1.COMPLETE -> known(false)
                }
            }
        }
        val bytes = SelfOwnedNativeInstallationWireV1.encodeIntent(next)
        try {
            namespace.retainIntent(bytes)
            val actual = namespace.readIntent() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            try {
                known(actual.contentEquals(bytes))
                originalIntent = SelfOwnedNativeInstallationWireV1.decodeIntent(actual)
            } finally { actual.fill(0) }
        } finally { bytes.fill(0) }
        namespace.assertContinuity()
    }

    fun retainBindingStarted() {
        val original = intent()
        known(original.complete() && !original.bindingStarted)
        if (original.revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        retainIntent(original.copy(revision = original.revision + 1L, bindingStarted = true))
        initialBindingCommitArmed = true // Only this returned prior write can authorize a FIRST state commit.
    }

    /** Authenticated positive native state only; absence after a prior binding marker is unresolved. */
    fun readState(signing: AndroidSelfOwnedInstallationSigning,
        material: AndroidSelfOwnedDataKeyMaterial): SelfOwnedNativeJournalStateV1? {
        val original = intent()
        val encoded = namespace.readState() ?: return null
        try {
            known(original.complete() && original.bindingStarted)
            signing.assertOriginalSigningKey(); material.assertOriginalDataKeys()
            val envelope = SelfOwnedNativeInstallationWireV1.decodeEnvelope(encoded)
            val aad = SelfOwnedNativeInstallationWireV1.stateAad(original)
            val plaintext = try {
                signing.verifyJournalEnvelope(aad, envelope)
                material.openJournal(aad, envelope.nonce, envelope.ciphertext)
            } finally { aad.fill(0); envelope.clear() }
            try {
                val state = SelfOwnedNativeInstallationWireV1.decodeState(plaintext)
                val canonical = SelfOwnedNativeInstallationWireV1.encodeState(state)
                try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
                signing.validateOriginalBinding(state.originalBinding)
                namespace.assertContinuity()
                return state
            } finally { plaintext.fill(0) }
        } finally { encoded.fill(0) }
    }

    fun retainState(next: SelfOwnedNativeJournalStateV1, signing: AndroidSelfOwnedInstallationSigning,
        material: AndroidSelfOwnedDataKeyMaterial): SelfOwnedNativeJournalStateV1 {
        val original = intent()
        known(original.complete() && original.bindingStarted)
        val previous = readState(signing, material)
        signing.validateOriginalBinding(next.originalBinding)
        if (previous == null) {
            // A journal opened with an existing STARTED marker never arms this one-shot path.
            // Even a same-lifetime failed initial write cannot be retried as a missing-state repair.
            known(initialBindingCommitArmed && next.revision == 0L && next.newCeremoniesAllowed)
            initialBindingCommitArmed = false
        } else {
            val before = previous.originalBinding.copyWireBytes(); val after = next.originalBinding.copyWireBytes()
            try {
                known(before.contentEquals(after) && next.revision == previous.revision + 1L &&
                    (previous.newCeremoniesAllowed || !next.newCeremoniesAllowed))
            } finally { before.fill(0); after.fill(0) }
        }
        val plaintext = SelfOwnedNativeInstallationWireV1.encodeState(next)
        val aad = SelfOwnedNativeInstallationWireV1.stateAad(original)
        try {
            val encrypted = material.sealJournal(aad, plaintext)
            try {
                val signature = signing.signJournalEnvelope(aad, encrypted.nonce, encrypted.ciphertext)
                val encoded = try {
                    SelfOwnedNativeInstallationWireV1.encodeEnvelope(SelfOwnedRegistryEnvelopeV1(
                        encrypted.nonce, encrypted.ciphertext, signature))
                } finally { signature.fill(0) }
                try { namespace.retainState(encoded) } finally { encoded.fill(0) }
            } finally { encrypted.clear() }
        } finally { plaintext.fill(0); aad.fill(0) }
        return requireNotNull(readState(signing, material)).also {
            known(it.revision == next.revision && it.newCeremoniesAllowed == next.newCeremoniesAllowed)
            val expected = next.originalBinding.copyWireBytes(); val actual = it.originalBinding.copyWireBytes()
            try { known(expected.contentEquals(actual)) } finally { expected.fill(0); actual.fill(0) }
        }
    }

    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
}

/** Owned transient output, no key bytes and no claim that a ciphertext is committed state. */
internal class AndroidSelfOwnedCiphertext(val nonce: ByteArray, val ciphertext: ByteArray) {
    fun clear() { nonce.fill(0); ciphertext.fill(0) }
}
