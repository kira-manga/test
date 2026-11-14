package com.ukkera.pos.security

/**
 * Actual original target-journal transitions. File custody is the SAME retained namespace. The
 * format is fixed once from the captured environment (Android V1, Windows/macOS V2) and every
 * read, transition and write uses only that version; nothing is re-tried as another version.
 */
internal class AndroidSelfOwnedInstallationJournal(
    private val namespace: SelfOwnedOriginalDatabaseNamespace,
    private val environment: SelfOwnedOriginalEnvironment,
    private val work: AndroidOwnedInstallationWork,
    private val ownerToken: Any,
) {
    val format: SelfOwnedOriginalFormat = environment.originalFormat()
    private var originalIntent: SelfOwnedOriginalIntent? = null
    private var initialBindingCommitArmed = false

    fun openNative() {
        environment.requireCurrent(); namespace.assertContinuity()
        val bytes = namespace.readIntent()
        originalIntent = if (bytes == null) null else try {
            val decoded = SelfOwnedOriginalIntent.decode(format, bytes)
            val canonical = decoded.encode()
            try { known(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            known(decoded.namespace == namespace.identity() && decoded.source.namesTarget(environment))
            known(startedNoLaterThanCurrentBoot(decoded))
            decoded
        } finally { bytes.fill(0) }
        val state = namespace.readState()
        try { known(state == null || originalIntent?.let { it.complete() && it.bindingStarted } == true) }
        finally { state?.fill(0) }
        work.requireActive()
    }

    fun originalIntentOrNull(): SelfOwnedOriginalIntent? { namespace.assertContinuity(); return originalIntent }
    fun originalIntent(): SelfOwnedOriginalIntent =
        originalIntentOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)

    /** Android V1 view of the exact retained intent instance; a V2 journal is UNSUPPORTED_PLATFORM. */
    fun intentOrNull(): SelfOwnedNativeIntentV1? = when (val retained = originalIntentOrNull()) {
        null -> null
        is SelfOwnedOriginalIntent.V1 -> retained.value
        is SelfOwnedOriginalIntent.V2 -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }
    fun intent(): SelfOwnedNativeIntentV1 = intentOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)

    /** Windows/macOS V2 view of the exact retained intent instance; a V1 journal is UNSUPPORTED_PLATFORM. */
    fun intentV2OrNull(): SelfOwnedNativeIntentV2? = when (val retained = originalIntentOrNull()) {
        null -> null
        is SelfOwnedOriginalIntent.V2 -> retained.value
        is SelfOwnedOriginalIntent.V1 -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }
    fun intentV2(): SelfOwnedNativeIntentV2 = intentV2OrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)

    /** One initial prior intent; an existing namespace missing it can NEVER start again. */
    fun initializeOriginal(approval: SelfOwnedOriginalApproval) {
        environment.requireCurrent(); namespace.assertContinuity()
        known(namespace.createdThisLifetime && originalIntent == null)
        if (!approval.current(ownerToken, environment) { work.clock() }) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (approval.source.format != format) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val coordinates = approval.source.coordinates
        val roles = listOf(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING to coordinates.targetSigningKeyId,
            SelfOwnedTargetKeyRoleV1.STORAGE to coordinates.storageKeyId, SelfOwnedTargetKeyRoleV1.PEPPER to coordinates.pepperKeyId)
        val nonce = AndroidP256Primitives.randomBytes(32)
        val next = try {
            when (val identity = namespace.identity()) {
                is SelfOwnedOriginalNamespaceIdentity.V1 -> SelfOwnedOriginalIntent.V1(SelfOwnedNativeIntentV1(0L,
                    approval.v1().source, identity.value, SelfOwnedOctetsV1(nonce),
                    roles.map { (role, keyId) -> SelfOwnedTargetKeyIntentV1(role, keyId, SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                        null, null, SelfOwnedOctetsV1(ByteArray(0))) }, false))
                is SelfOwnedOriginalNamespaceIdentity.V2 -> SelfOwnedOriginalIntent.V2(SelfOwnedNativeIntentV2(0L,
                    approval.v2().source, identity.value, SelfOwnedOctetsV1(nonce),
                    roles.map { (role, keyId) -> SelfOwnedTargetKeyIntentV2(role, keyId, SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                        null, null, SelfOwnedOctetsV1(ByteArray(0))) }, false))
            }
        } finally { nonce.fill(0) }
        retainOriginalIntent(next)
    }

    /** The original packet/namespace/nonce/IDs never change as individual key intents advance. */
    fun retainIntent(next: SelfOwnedNativeIntentV1) = retainOriginalIntent(SelfOwnedOriginalIntent.V1(next))
    fun retainIntent(next: SelfOwnedNativeIntentV2) = retainOriginalIntent(SelfOwnedOriginalIntent.V2(next))

    private fun retainOriginalIntent(next: SelfOwnedOriginalIntent) {
        namespace.assertContinuity()
        known(next.format == format)
        known(next.namespace == namespace.identity() && next.source.namesTarget(environment))
        val stateBytes = namespace.readState()
        try { known(stateBytes == null) } finally { stateBytes?.fill(0) }
        val previous = originalIntent
        val after = keyFacts(next)
        if (previous == null) {
            known(namespace.createdThisLifetime && next.revision == 0L && !next.bindingStarted &&
                after.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED })
        } else {
            val before = keyFacts(previous)
            known(next.revision == previous.revision + 1L && next.source.same(previous.source) && next.namespace == previous.namespace &&
                nonceOf(next) == nonceOf(previous) && after.map { it.keyId } == before.map { it.keyId })
            val changed = before.zip(after).filter { (left, right) -> left.key != right.key }
            if (changed.isEmpty()) known(previous.complete() && !previous.bindingStarted && next.bindingStarted)
            else {
                known(changed.size == 1 && !previous.bindingStarted && !next.bindingStarted)
                val (old, new) = changed.single()
                known(old.role == new.role && old.keyId == new.keyId)
                val currentBoot = currentBootFact()
                when (old.phase) {
                    SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> known(new.phase == SelfOwnedKeyIntentPhaseV1.STARTED &&
                        new.startedBoot == currentBoot)
                    SelfOwnedKeyIntentPhaseV1.STARTED -> known(
                        new.phase == SelfOwnedKeyIntentPhaseV1.STARTED && new.startedBoot == currentBoot &&
                            laterBoot(requireNotNull(old.startedBoot), requireNotNull(new.startedBoot)) ||
                            new.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE && new.startedBoot == old.startedBoot)
                    SelfOwnedKeyIntentPhaseV1.COMPLETE -> known(false)
                }
            }
        }
        val bytes = next.encode()
        try {
            namespace.retainIntent(bytes)
            val actual = namespace.readIntent() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            try {
                known(actual.contentEquals(bytes))
                originalIntent = SelfOwnedOriginalIntent.decode(format, actual)
            } finally { actual.fill(0) }
        } finally { bytes.fill(0) }
        namespace.assertContinuity()
    }

    fun retainBindingStarted() {
        val original = originalIntent()
        known(original.complete() && !original.bindingStarted)
        if (original.revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        retainOriginalIntent(original.withBindingStarted())
        initialBindingCommitArmed = true // Only this returned prior write can authorize a FIRST state commit.
    }

    /** Authenticated positive native state only; absence after a prior binding marker is unresolved. */
    fun readState(signing: SelfOwnedOriginalSigning,
        material: SelfOwnedOriginalDataKeys): SelfOwnedOriginalJournalState? {
        val original = originalIntent()
        val encoded = namespace.readState() ?: return null
        try {
            known(original.complete() && original.bindingStarted)
            val envelope = SelfOwnedOriginalJournalEnvelope.decode(format, encoded)
            val aad = original.stateAad()
            val state = try { signing.openOriginalJournalState(aad, envelope, material) }
            finally { aad.fill(0); envelope.clear() }
            known(state.format == format)
            namespace.assertContinuity()
            return state
        } finally { encoded.fill(0) }
    }

    fun retainState(next: SelfOwnedOriginalJournalState, signing: SelfOwnedOriginalSigning,
        material: SelfOwnedOriginalDataKeys): SelfOwnedOriginalJournalState {
        val original = originalIntent()
        known(original.complete() && original.bindingStarted && next.format == format)
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
        val plaintext = next.encode()
        val aad = original.stateAad()
        try {
            val encrypted = material.sealJournal(aad, plaintext)
            try {
                val signature = signing.signJournalEnvelope(aad, encrypted.nonce, encrypted.ciphertext)
                val encoded = try {
                    SelfOwnedOriginalJournalEnvelope.of(format, encrypted.nonce, encrypted.ciphertext, signature).encode()
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

    /** One key's comparable facts; [startedBoot] is the Android boot count or the desktop session. */
    private class KeyFact(val key: Any, val role: SelfOwnedTargetKeyRoleV1, val keyId: com.ukkera.pos.types.DomainId,
        val phase: SelfOwnedKeyIntentPhaseV1, val startedBoot: Any?)

    private fun keyFacts(intent: SelfOwnedOriginalIntent): List<KeyFact> = when (intent) {
        is SelfOwnedOriginalIntent.V1 -> intent.value.keys.map { KeyFact(it, it.role, it.keyId, it.phase, it.startedBoot) }
        is SelfOwnedOriginalIntent.V2 -> intent.value.keys.map { KeyFact(it, it.role, it.keyId, it.phase, it.startedBoot) }
    }

    private fun nonceOf(intent: SelfOwnedOriginalIntent): SelfOwnedOctetsV1 = when (intent) {
        is SelfOwnedOriginalIntent.V1 -> intent.value.nonce
        is SelfOwnedOriginalIntent.V2 -> intent.value.nonce
    }

    /** Android: the boot count. Desktop: the actual current boot session (never an ordinal). */
    private fun currentBootFact(): Any = when (val mark = environment.bootMark) {
        is SelfOwnedBootMark.AndroidCount -> mark.count
        // A desktop key may only be stamped with a boot the verified ledger chain names as current.
        is SelfOwnedBootMark.Session -> { known(verifiedBootChain() != null); mark.session }
    }

    /** A STARTED key may restart only on a later Android boot count / a strictly later verified desktop ordinal. */
    private fun laterBoot(before: Any, after: Any): Boolean = when (format) {
        SelfOwnedOriginalFormat.V1 -> (after as Int) > (before as Int)
        SelfOwnedOriginalFormat.V2 -> after is SelfOwnedNativeBootSessionV2 && before is SelfOwnedNativeBootSessionV2 &&
            verifiedBootChain()?.later(before, after) == true
    }

    /** No retained key was started on a later boot than the current one (or on a boot outside the verified chain). */
    private fun startedNoLaterThanCurrentBoot(intent: SelfOwnedOriginalIntent): Boolean = when (intent) {
        is SelfOwnedOriginalIntent.V1 -> intent.value.keys.all { it.startedBoot == null || it.startedBoot <= environment.bootCount }
        is SelfOwnedOriginalIntent.V2 -> verifiedBootChain()?.let { chain ->
            intent.value.keys.all { it.startedBoot == null || chain.startedNoLaterThanCurrent(it.startedBoot) }
        } == true
    }

    /** Desktop sessions are unordered UUIDs; ordering comes only from the platform's verified ledger chain. */
    private fun verifiedBootChain(): SelfOwnedBootChainV2? = (environment.bootMark as? SelfOwnedBootMark.Session)?.let { mark ->
        SelfOwnedBootChainV2.verify(environment.bootLedgerChain(), mark.ledger)
    }

    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
}

/** Owned transient output, no key bytes and no claim that a ciphertext is committed state. */
internal class AndroidSelfOwnedCiphertext(val nonce: ByteArray, val ciphertext: ByteArray) {
    fun clear() { nonce.fill(0); ciphertext.fill(0) }
}
