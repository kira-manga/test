package com.ukkera.pos.security

import com.sun.jna.Pointer
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Concrete Windows original-target namespace/storage primitive for LIVE and the two training
 * compositions. It owns one service-only private NTFS target directory, its fixed lock file,
 * its fixed database directory, the cross-process exclusive lease and the bounded V2 intent and
 * journal control bytes.
 *
 * Windows directory flushing does not durably qualify a renamed directory entry. The control
 * bytes therefore live in the service/profile-private nonvolatile registry key; every update is
 * followed by `RegFlushKey` and an exact readback before it is reported successful. The actual
 * target/lock/database handles and their 128-bit FileIdInfo identities remain retained.
 *
 * This is native custody of a namespace only. It is not an installation approval, P1 registry,
 * target key, boot-session observation, history owner or current Master. [openExisting] never
 * creates a missing target, lock, database directory, registry key or control value; [createNew]
 * is the only entry that may create the original namespace, and only with an explicit new
 * source/nonce. A remnant that is missing, unreadable, unknown or conflicting is preserved and
 * reported as unresolved.
 */
internal class WindowsSelfOwnedOriginalNamespace private constructor(
    private val expectedTarget: SelfOwnedInstalledIdentityTargetV2,
    private val serviceSid: String,
    private val accountSid: String,
    private val profileName: String,
    private val targetDirectoryName: String,
    private val supportCanonical: String,
    private val targetCanonical: String,
    private val databaseCanonical: String,
    private val lockCanonical: String,
    private val volumeUuid: String,
    private val supportObservation: WindowsOriginalObservation,
    private val targetObservation: WindowsOriginalObservation,
    private val lockObservation: WindowsOriginalObservation,
    private val databaseObservation: WindowsOriginalObservation,
    private var intentSnapshot: ByteArray?,
    private var journalSnapshot: ByteArray?,
    private var intentValue: SelfOwnedNativeIntentV2?,
    private val supportHandle: WindowsOriginalHandle,
    private val targetHandle: WindowsOriginalHandle,
    private val lockHandle: WindowsOriginalHandle,
    private val databaseHandle: WindowsOriginalHandle,
    val createdThisLifetime: Boolean,
) : AutoCloseable {
    private enum class Phase { OPEN, CLOSE_STARTED, CLOSED, CLOSE_UNCERTAIN }

    private var phase: Phase = Phase.OPEN
    private val registryProfilePath: String = WindowsOriginalNative.registryProfilePath(profileName)
    private val registryLeafPath: String = WindowsOriginalNative.registryLeafPath(profileName,
        targetDigest(expectedTarget))

    /** Actual local namespace facts; no device number, credential or key material is exposed. */
    @Synchronized
    fun identity(): SelfOwnedNativeNamespaceV2.Windows {
        assertContinuity()
        return currentIdentity()
    }

    /** The one fixed database directory. It remains owned by this retained namespace lease. */
    @Synchronized
    fun databaseDirectoryPath(): String {
        assertContinuity()
        return databaseCanonical
    }

    /** Exact canonical V2 intent bytes or null before the one initial control write. */
    @Synchronized
    fun readIntent(): ByteArray? {
        assertContinuity()
        return intentSnapshot?.copyOf()
    }

    /** Exact canonical V2 journal envelope bytes, without parsing or authenticating them here. */
    @Synchronized
    fun readJournal(): ByteArray? {
        assertContinuity()
        return journalSnapshot?.copyOf()
    }

    /**
     * Replaces the single intent control value atomically from this owner's perspective. The
     * source, nonce, key ids, namespace and target are immutable; revisions advance only and no
     * intent write is possible after the binding-started marker.
     */
    @Synchronized
    fun replaceIntent(bytes: ByteArray) {
        assertContinuity()
        val previous = intentValue ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (journalSnapshot != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val next = validateIntent(bytes, previous)
        if (next.revision <= previous.revision) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        val readback = writeControl(INTENT_VALUE, bytes,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES, createOnly = false)
        try {
            if (!readback.contentEquals(bytes)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            intentSnapshot?.fill(0)
            intentSnapshot = readback
            intentValue = next
            assertContinuity()
        } catch (failure: Throwable) {
            readback.fill(0)
            throw failure
        }
    }

    /**
     * Replaces or first installs the exact journal envelope control value. Envelope
     * authentication/decryption belongs to the separate journal owner; this storage helper only
     * enforces canonical framing, the completed-intent ordering and registry custody.
     */
    @Synchronized
    fun replaceJournal(bytes: ByteArray) {
        assertContinuity()
        requireJournalConsistent(bytes)
        val readback = writeControl(JOURNAL_VALUE, bytes,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES, createOnly = false)
        try {
            if (!readback.contentEquals(bytes)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            journalSnapshot?.fill(0)
            journalSnapshot = readback
            assertContinuity()
        } catch (failure: Throwable) {
            readback.fill(0)
            throw failure
        }
    }

    /** Namespace/registry continuity only; it does not prove a valid Room schema or Master. */
    @Synchronized
    fun assertContinuity() {
        WindowsOriginalCloseState.requireConfirmed()
        if (phase != Phase.OPEN) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        requireCurrentTarget(expectedTarget)
        assertBase()
        assertRegistry()
        assertBase()
    }

    /**
     * Releases the cross-process lease only after every non-lease namespace handle has returned a
     * confirmed close. If any close is identity-unknown, the lease is deliberately left held until
     * process exit; it is never retried or reported released. A failed release is terminal.
     */
    @Synchronized
    override fun close() {
        when (phase) {
            Phase.CLOSED -> return
            Phase.OPEN -> Unit
            Phase.CLOSE_STARTED, Phase.CLOSE_UNCERTAIN ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        phase = Phase.CLOSE_STARTED
        var failure: Throwable? = null
        // Do not release the cross-process lease until every non-lease namespace handle has a
        // confirmed close. An unknown close leaves the lease held intentionally: another process
        // must not be able to acquire this namespace while an identity-unknown handle may remain.
        closeAttempt({ databaseHandle.closeConfirmed() }) { problem -> failure = merge(failure, problem) }
        closeAttempt({ targetHandle.closeConfirmed() }) { problem -> failure = merge(failure, problem) }
        closeAttempt({ supportHandle.closeConfirmed() }) { problem -> failure = merge(failure, problem) }
        if (failure == null) {
            closeAttempt({ WindowsOriginalCloseState.releaseLease(lockHandle) }) { problem ->
                failure = merge(failure, problem)
            }
        }
        intentSnapshot?.fill(0)
        journalSnapshot?.fill(0)
        intentSnapshot = null
        journalSnapshot = null
        intentValue = null
        if (failure != null) {
            phase = Phase.CLOSE_UNCERTAIN
            val unresolved = SelfOwnedFailureV1(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            unresolved.addSuppressed(checkNotNull(failure))
            throw unresolved
        }
        phase = Phase.CLOSED
    }

    private fun initializeNewIntent(source: SelfOwnedNativeSourceV2, nonce: ByteArray) {
        assertContinuity()
        if (intentSnapshot != null || journalSnapshot != null) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        val coordinates = source.coordinates
        val keys = listOf(
            SelfOwnedTargetKeyIntentV2(
                SelfOwnedTargetKeyRoleV1.TARGET_SIGNING, coordinates.targetSigningKeyId,
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null, null, SelfOwnedOctetsV1(ByteArray(0)),
            ),
            SelfOwnedTargetKeyIntentV2(
                SelfOwnedTargetKeyRoleV1.STORAGE, coordinates.storageKeyId,
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null, null, SelfOwnedOctetsV1(ByteArray(0)),
            ),
            SelfOwnedTargetKeyIntentV2(
                SelfOwnedTargetKeyRoleV1.PEPPER, coordinates.pepperKeyId,
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED, null, null, SelfOwnedOctetsV1(ByteArray(0)),
            ),
        )
        val intent = SelfOwnedNativeIntentV2(
            0L, source, currentIdentity(), SelfOwnedOctetsV1(nonce), keys, false,
        )
        val bytes = SelfOwnedNativeInstallationWireV2.encodeIntent(intent)
        try {
            val validated = validateIntent(bytes, null)
            if (validated.revision != 0L || validated.bindingStarted ||
                validated.keys.any { it.phase != SelfOwnedKeyIntentPhaseV1.NOT_STARTED }) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val readback = writeControl(INTENT_VALUE, bytes,
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES, createOnly = true)
            try {
                if (!readback.contentEquals(bytes)) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                intentSnapshot = readback
                intentValue = validated
            } catch (failure: Throwable) {
                readback.fill(0)
                throw failure
            }
        } finally {
            bytes.fill(0)
        }
        assertContinuity()
    }

    private fun loadExisting() {
        assertBase()
        val controls = readControls()
        val intentBytes = controls.intent
        if (intentBytes == null) {
            controls.journal?.fill(0)
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val decoded = try {
            validateIntent(intentBytes, null)
        } catch (failure: Throwable) {
            intentBytes.fill(0)
            controls.journal?.fill(0)
            throw failure
        }
        intentSnapshot = intentBytes
        intentValue = decoded
        val journalBytes = controls.journal
        if (journalBytes != null) {
            try {
                requireJournalConsistent(journalBytes)
                journalSnapshot = journalBytes
            } catch (failure: Throwable) {
                journalBytes.fill(0)
                intentSnapshot?.fill(0)
                intentSnapshot = null
                intentValue = null
                throw failure
            }
        } else if (decoded.complete() && decoded.bindingStarted) {
            intentSnapshot?.fill(0)
            intentSnapshot = null
            intentValue = null
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        assertContinuity()
    }

    private fun assertBase() {
        if (phase != Phase.OPEN) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val supportNow = WindowsOriginalNative.observeSupportDirectory(supportHandle.pointer,
            supportCanonical)
        if (supportNow != supportObservation) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (WindowsOriginalNative.requireVolume(supportHandle.pointer, supportCanonical) != volumeUuid) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val targetNow = WindowsOriginalNative.observeServiceDirectory(targetHandle.pointer,
            targetCanonical, accountSid, serviceSid)
        if (targetNow != targetObservation) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val lockNow = WindowsOriginalNative.observeLockFile(lockHandle.pointer, lockCanonical,
            accountSid, serviceSid)
        if (lockNow != lockObservation) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val databaseNow = WindowsOriginalNative.observeServiceDirectory(databaseHandle.pointer,
            databaseCanonical, accountSid, serviceSid)
        if (databaseNow != databaseObservation) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (targetNow.identity.volumeSerialNumber != supportObservation.identity.volumeSerialNumber ||
            lockNow.identity.volumeSerialNumber != supportObservation.identity.volumeSerialNumber ||
            databaseNow.identity.volumeSerialNumber != supportObservation.identity.volumeSerialNumber) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val names = WindowsOriginalNative.directoryNames(targetCanonical)
        if (names.size != 2 || names.toSet().size != names.size ||
            names.none { it.equals(LOCK_NAME, ignoreCase = true) } ||
            names.none { it.equals(DATABASE_NAME, ignoreCase = true) }) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun assertRegistry() {
        val controls = readControls()
        try {
            requireSnapshot(INTENT_VALUE, intentSnapshot, controls.intent)
            requireSnapshot(JOURNAL_VALUE, journalSnapshot, controls.journal)
            if (intentSnapshot == null && controls.journal != null) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val current = intentValue
            if (current != null && current.complete() && current.bindingStarted &&
                controls.journal == null) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        } finally {
            controls.intent?.fill(0)
            controls.journal?.fill(0)
        }
    }

    private fun readControls(): Controls = withRegistryLeaf { key ->
        val intentStored = WindowsOriginalNative.readRegistryValue(key, INTENT_VALUE,
            CONTROL_HEADER_BYTES + SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES)
        val journalStored = WindowsOriginalNative.readRegistryValue(key, JOURNAL_VALUE,
            CONTROL_HEADER_BYTES + SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES)
        var intent: ByteArray? = null
        var journal: ByteArray? = null
        try {
            if (intentStored != null) {
                intent = decodeControl(INTENT_VALUE, intentStored,
                    SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES)
            }
            if (journalStored != null) {
                journal = decodeControl(JOURNAL_VALUE, journalStored,
                    SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES)
            }
            return@withRegistryLeaf Controls(intent, journal)
        } catch (failure: Throwable) {
            intent?.fill(0)
            journal?.fill(0)
            throw failure
        } finally {
            intentStored?.fill(0)
            journalStored?.fill(0)
        }
    }

    private fun writeControl(
        valueName: String,
        payload: ByteArray,
        maximum: Int,
        createOnly: Boolean,
    ): ByteArray = withRegistryLeaf { key ->
        if (createOnly) {
            val current = WindowsOriginalNative.readRegistryValue(key, valueName,
                CONTROL_HEADER_BYTES + maximum)
            if (current != null) {
                current.fill(0)
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        }
        val stored = encodeControl(valueName, payload, maximum)
        try {
            val readback = WindowsOriginalNative.writeRegistryValue(key, valueName, stored,
                CONTROL_HEADER_BYTES + maximum)
            try {
                val decoded = decodeControl(valueName, readback, maximum)
                try {
                    if (!decoded.contentEquals(payload)) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                    }
                    return@withRegistryLeaf decoded
                } catch (failure: Throwable) {
                    decoded.fill(0)
                    throw failure
                }
            } finally {
                readback.fill(0)
            }
        } finally {
            stored.fill(0)
        }
    }

    private fun <T> withRegistryLeaf(block: (Pointer) -> T): T {
        WindowsOriginalNative.requireNoRegistryLinks(registryLeafPath, allowMissing = false)
        val base = WindowsOriginalNative.openExistingRegistryComponent(
            registryProfilePath.substringBeforeLast('\\'), accountSid, serviceSid,
        ) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        WindowsOriginalNative.useRegistryKey(base) { key ->
            WindowsOriginalNative.requireRegistryBase(key)
        }
        val profile = WindowsOriginalNative.openExistingRegistryComponent(registryProfilePath,
            accountSid, serviceSid)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        WindowsOriginalNative.useRegistryKey(profile) { key ->
            WindowsOriginalNative.requireRegistryProfile(key, targetDigest(expectedTarget),
                allowEmpty = false)
        }
        val leaf = WindowsOriginalNative.openExistingRegistryComponent(registryLeafPath,
            accountSid, serviceSid)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return WindowsOriginalNative.useRegistryKey(leaf) { key ->
            WindowsOriginalNative.requireRegistryLeaf(key, CONTROL_VALUES)
            block(key)
        }
    }

    private fun requireSnapshot(
        valueName: String,
        expected: ByteArray?,
        actual: ByteArray?,
    ) {
        if (expected == null) {
            if (actual != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return
        }
        if (actual == null || !MessageDigest.isEqual(expected, actual)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun validateIntent(
        bytes: ByteArray,
        previous: SelfOwnedNativeIntentV2?,
    ): SelfOwnedNativeIntentV2 {
        val next = when (val result = SelfOwnedNativeInstallationWireV2.decodeIntent(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> result.value
            is SelfOwnedInstalledIdentityResultV2.Refused ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (next.namespace != currentIdentity()) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (next.source.platform != SelfOwnedNativePlatformV2.WINDOWS ||
            !SelfOwnedInstalledIdentityV2.matches(next.source.installedTarget.target,
                expectedTarget)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        if (journalSnapshot != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        if (previous != null &&
            (!next.source.same(previous.source) || next.nonce != previous.nonce ||
                next.namespace != previous.namespace ||
                next.keys.map { it.role to it.keyId } != previous.keys.map { it.role to it.keyId } ||
                next.revision <= previous.revision || previous.bindingStarted)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        return next
    }

    private fun requireJournalConsistent(bytes: ByteArray) {
        val envelope = when (val result = SelfOwnedNativeInstallationWireV2.decodeEnvelope(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> result.value
            is SelfOwnedInstalledIdentityResultV2.Refused ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        try {
            val original = intentValue
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (!original.complete() || !original.bindingStarted) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally {
            envelope.clear()
        }
    }

    private fun currentIdentity(): SelfOwnedNativeNamespaceV2.Windows {
        val directoryFileId = targetObservation.identity.fileId
        val lockFileId = lockObservation.identity.fileId
        val databaseFileId = databaseObservation.identity.fileId
        if (setOf(directoryFileId, lockFileId, databaseFileId).size != 3) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return SelfOwnedNativeNamespaceV2.Windows(
            targetCanonical, accountSid, volumeUuid, directoryFileId, lockFileId, databaseFileId,
        )
    }

    companion object {
        /**
         * Bounded creation after an explicit original source/nonce intent. An existing target is
         * never adopted; a failed create leaves every partially created remnant in place.
         */
        fun createNew(
            profile: LocalCompositionSdkProfileV1,
            supportDirectory: Path,
            expectedTarget: SelfOwnedInstalledIdentityTargetV2,
            source: SelfOwnedNativeSourceV2,
            nonce: ByteArray,
        ): WindowsSelfOwnedOriginalNamespace {
            WindowsOriginalNative.requireSupported()
            WindowsOriginalCloseState.requireConfirmed()
            val (serviceSid, accountSid) = serviceCoordinates(expectedTarget)
            requireCurrentTarget(expectedTarget)
            if (nonce.size != SelfOwnedNativeIdentityProfileV2.NONCE_BYTES) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            if (source.platform != SelfOwnedNativePlatformV2.WINDOWS ||
                !SelfOwnedInstalledIdentityV2.matches(source.installedTarget.target, expectedTarget)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            }
            val profileName = profileName(profile)
            val targetName = targetDirectoryName(profile)
            preflightRegistryCreation(profileName, expectedTarget, accountSid, serviceSid)
            val support = WindowsOriginalNative.openSupportDirectory(supportDirectory)
            val handles = mutableListOf(support.handle)
            var leaseHandle: WindowsOriginalHandle? = null
            var namespace: WindowsSelfOwnedOriginalNamespace? = null
            try {
                val targetPath = support.observation.canonicalPath + "\\" + targetName
                val targetHandle = WindowsOriginalNative.createServiceDirectory(targetPath,
                    accountSid, serviceSid).also { handles += it }
                val targetObservation = WindowsOriginalNative.observeServiceDirectory(
                    targetHandle.pointer, targetPath, accountSid, serviceSid)
                val targetCanonical = targetObservation.canonicalPath
                if (targetCanonical != targetPath) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }

                val lockPath = targetCanonical + "\\" + LOCK_NAME
                val lockHandle = WindowsOriginalNative.createLockFile(lockPath, accountSid, serviceSid)
                    .also { handles += it; leaseHandle = it }
                val lockObservation = WindowsOriginalNative.observeLockFile(lockHandle.pointer,
                    lockPath, accountSid, serviceSid)
                WindowsOriginalNative.acquireExclusiveLock(lockHandle.pointer)

                val databasePath = targetCanonical + "\\" + DATABASE_NAME
                val databaseHandle = WindowsOriginalNative.createServiceDirectory(databasePath,
                    accountSid, serviceSid).also { handles += it }
                val databaseObservation = WindowsOriginalNative.observeServiceDirectory(
                    databaseHandle.pointer, databasePath, accountSid, serviceSid)
                val databaseCanonical = databaseObservation.canonicalPath
                if (databaseCanonical != databasePath ||
                    WindowsOriginalNative.directoryNames(databaseCanonical).isNotEmpty() ||
                    lockObservation.identity.volumeSerialNumber !=
                        support.observation.identity.volumeSerialNumber ||
                    targetObservation.identity.volumeSerialNumber !=
                        support.observation.identity.volumeSerialNumber ||
                    databaseObservation.identity.volumeSerialNumber !=
                        support.observation.identity.volumeSerialNumber) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val names = WindowsOriginalNative.directoryNames(targetCanonical)
                if (names.size != 2 ||
                    names.none { it.equals(LOCK_NAME, ignoreCase = true) } ||
                    names.none { it.equals(DATABASE_NAME, ignoreCase = true) }) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }

                val created = WindowsSelfOwnedOriginalNamespace(
                    expectedTarget, serviceSid, accountSid, profileName, targetName,
                    support.observation.canonicalPath, targetCanonical, databaseCanonical, lockPath,
                    support.volumeUuid, support.observation, targetObservation, lockObservation,
                    databaseObservation, null, null, null, support.handle, targetHandle, lockHandle,
                    databaseHandle, true,
                )
                namespace = created
                handles.clear()
                try {
                    createRegistryNamespace(profileName, expectedTarget, accountSid, serviceSid)
                    created.initializeNewIntent(source, nonce)
                    requireCurrentTarget(expectedTarget)
                    created.assertContinuity()
                    return created
                } catch (failure: Throwable) {
                    try { created.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                    throw failure
                }
            } catch (failure: Throwable) {
                if (namespace == null) closeAcquiredHandles(handles, leaseHandle, failure)
                throw failure
            }
        }

        /** Existing-only open. Missing lock, database directory or control value is never created. */
        fun openExisting(
            profile: LocalCompositionSdkProfileV1,
            supportDirectory: Path,
            expectedTarget: SelfOwnedInstalledIdentityTargetV2,
        ): WindowsSelfOwnedOriginalNamespace {
            WindowsOriginalNative.requireSupported()
            WindowsOriginalCloseState.requireConfirmed()
            val (serviceSid, accountSid) = serviceCoordinates(expectedTarget)
            requireCurrentTarget(expectedTarget)
            val profileName = profileName(profile)
            val targetName = targetDirectoryName(profile)
            val support = WindowsOriginalNative.openSupportDirectory(supportDirectory)
            val handles = mutableListOf(support.handle)
            var leaseHandle: WindowsOriginalHandle? = null
            var namespace: WindowsSelfOwnedOriginalNamespace? = null
            try {
                val targetPath = support.observation.canonicalPath + "\\" + targetName
                val targetHandle = WindowsOriginalNative.openServiceDirectory(targetPath)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
                handles += targetHandle
                val targetObservation = WindowsOriginalNative.observeServiceDirectory(
                    targetHandle.pointer, targetPath, accountSid, serviceSid)
                val targetCanonical = targetObservation.canonicalPath
                if (targetCanonical != targetPath) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }

                val lockPath = targetCanonical + "\\" + LOCK_NAME
                val lockHandle = WindowsOriginalNative.openLockFile(lockPath)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                handles += lockHandle
                leaseHandle = lockHandle
                val lockObservation = WindowsOriginalNative.observeLockFile(lockHandle.pointer,
                    lockPath, accountSid, serviceSid)
                WindowsOriginalNative.acquireExclusiveLock(lockHandle.pointer)

                val databasePath = targetCanonical + "\\" + DATABASE_NAME
                val databaseHandle = WindowsOriginalNative.openServiceDirectory(databasePath)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                handles += databaseHandle
                val databaseObservation = WindowsOriginalNative.observeServiceDirectory(
                    databaseHandle.pointer, databasePath, accountSid, serviceSid)
                val databaseCanonical = databaseObservation.canonicalPath
                if (databaseCanonical != databasePath ||
                    lockObservation.identity.volumeSerialNumber !=
                        support.observation.identity.volumeSerialNumber ||
                    targetObservation.identity.volumeSerialNumber !=
                        support.observation.identity.volumeSerialNumber ||
                    databaseObservation.identity.volumeSerialNumber !=
                        support.observation.identity.volumeSerialNumber) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val names = WindowsOriginalNative.directoryNames(targetCanonical)
                if (names.size != 2 ||
                    names.none { it.equals(LOCK_NAME, ignoreCase = true) } ||
                    names.none { it.equals(DATABASE_NAME, ignoreCase = true) }) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }

                val existing = WindowsSelfOwnedOriginalNamespace(
                    expectedTarget, serviceSid, accountSid, profileName, targetName,
                    support.observation.canonicalPath, targetCanonical, databaseCanonical, lockPath,
                    support.volumeUuid, support.observation, targetObservation, lockObservation,
                    databaseObservation, null, null, null, support.handle, targetHandle, lockHandle,
                    databaseHandle, false,
                )
                namespace = existing
                handles.clear()
                try {
                    existing.loadExisting()
                    requireCurrentTarget(expectedTarget)
                    existing.assertContinuity()
                    return existing
                } catch (failure: Throwable) {
                    try { existing.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                    throw failure
                }
            } catch (failure: Throwable) {
                if (namespace == null) closeAcquiredHandles(handles, leaseHandle, failure)
                throw failure
            }
        }

        /**
         * Acquisition-failure cleanup. Non-lease handles close first. Any unknown close keeps the
         * lease held for process lifetime; it is never retried or treated as released.
         */
        private fun closeAcquiredHandles(
            handles: List<WindowsOriginalHandle>,
            lease: WindowsOriginalHandle?,
            failure: Throwable,
        ) {
            var nonLeaseFailed = false
            for (handle in handles.asReversed()) {
                if (handle === lease) continue
                try {
                    handle.closeConfirmed()
                } catch (close: Throwable) {
                    nonLeaseFailed = true
                    failure.addSuppressed(close)
                }
            }
            if (lease == null) return
            if (nonLeaseFailed) {
                // The lock handle remains open and unreachable; process exit is the only release.
                failure.addSuppressed(SelfOwnedFailureV1(SelfOwnedInstallationFailureV1.NATIVE_RETIRED))
                return
            }
            try {
                WindowsOriginalCloseState.releaseLease(lease)
            } catch (close: Throwable) {
                failure.addSuppressed(close)
            }
        }

        private fun preflightRegistryCreation(
            profileName: String,
            expectedTarget: SelfOwnedInstalledIdentityTargetV2,
            accountSid: String,
            serviceSid: String,
        ) {
            val profilePath = WindowsOriginalNative.registryProfilePath(profileName)
            val basePath = profilePath.substringBeforeLast('\\')
            val leafPath = WindowsOriginalNative.registryLeafPath(profileName,
                targetDigest(expectedTarget))
            WindowsOriginalNative.requireNoRegistryLinks(leafPath, allowMissing = true)
            val base = WindowsOriginalNative.openExistingRegistryComponent(basePath, accountSid,
                serviceSid)
            if (base != null) {
                WindowsOriginalNative.useRegistryKey(base) { key ->
                    WindowsOriginalNative.requireRegistryBase(key)
                }
                val profile = WindowsOriginalNative.openExistingRegistryComponent(profilePath,
                    accountSid, serviceSid)
                if (profile != null) {
                    WindowsOriginalNative.useRegistryKey(profile) { key ->
                        WindowsOriginalNative.requireRegistryProfile(key,
                            targetDigest(expectedTarget), allowEmpty = true)
                    }
                }
            }
            val leaf = WindowsOriginalNative.openExistingRegistryComponent(leafPath, accountSid,
                serviceSid)
            if (leaf != null) {
                WindowsOriginalNative.useRegistryKey(leaf) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                }
            }
        }

        private fun createRegistryNamespace(
            profileName: String,
            expectedTarget: SelfOwnedInstalledIdentityTargetV2,
            accountSid: String,
            serviceSid: String,
        ) {
            val basePath = WindowsOriginalNative.registryProfilePath(profileName)
                .substringBeforeLast('\\')
            val profilePath = WindowsOriginalNative.registryProfilePath(profileName)
            val leafPath = WindowsOriginalNative.registryLeafPath(profileName,
                targetDigest(expectedTarget))
            WindowsOriginalNative.requireNoRegistryLinks(leafPath, allowMissing = true)
            val base = WindowsOriginalNative.ensureRegistryComponent(basePath, accountSid, serviceSid)
            WindowsOriginalNative.useRegistryKey(base) { key ->
                WindowsOriginalNative.requireRegistryBase(key)
            }
            val profile = WindowsOriginalNative.ensureRegistryComponent(profilePath, accountSid,
                serviceSid)
            WindowsOriginalNative.useRegistryKey(profile) { key ->
                WindowsOriginalNative.requireRegistryProfile(key, targetDigest(expectedTarget),
                    allowEmpty = true)
            }
            val leaf = WindowsOriginalNative.createRegistryComponent(leafPath, accountSid,
                serviceSid, createOnly = true)
            WindowsOriginalNative.useRegistryKey(leaf) { key ->
                WindowsOriginalNative.requireRegistryLeaf(key, emptySet())
            }
            WindowsOriginalNative.requireNoRegistryLinks(leafPath, allowMissing = false)
        }

        private fun profileName(profile: LocalCompositionSdkProfileV1): String = when {
            profile === LocalMasterSdkProfileV1 -> "live"
            profile is LocalTrainingSdkProfileV1 ->
                SelfOwnedCompositionNames.trainingSuffix(profile.slot)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }

        private fun targetDirectoryName(profile: LocalCompositionSdkProfileV1): String = when {
            profile === LocalMasterSdkProfileV1 -> SelfOwnedCompositionNames.LIVE_TARGET_DIRECTORY
            profile is LocalTrainingSdkProfileV1 ->
                SelfOwnedCompositionNames.trainingTargetDirectory(profile.slot)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }

        private fun serviceCoordinates(
            target: SelfOwnedInstalledIdentityTargetV2,
        ): Pair<String, String> = when (target) {
            is SelfOwnedWindowsServiceIdentityV2 -> target.serviceSid to target.serviceAccountSid
            is SelfOwnedWindowsPackagedServiceIdentityV2 ->
                target.serviceSid to target.serviceAccountSid
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
        }

        private fun requireCurrentTarget(expected: SelfOwnedInstalledIdentityTargetV2) {
            val result = when (expected) {
                is SelfOwnedWindowsServiceIdentityV2 ->
                    WindowsInstalledApplicationIdentity.captureService(expected)
                is SelfOwnedWindowsPackagedServiceIdentityV2 ->
                    WindowsInstalledApplicationIdentity.capturePackagedService(expected)
                else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            }
            val observation = when (result) {
                is SelfOwnedInstalledIdentityResultV2.Data -> result.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> when (result.code) {
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH ->
                        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE ->
                        selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
                    else -> selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
                }
            }
            if (observation.isAppContainer ||
                !SelfOwnedInstalledIdentityV2.matches(expected, observation.target)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            }
            WindowsOriginalNative.requireNoImpersonation()
        }

        private fun targetDigest(target: SelfOwnedInstalledIdentityTargetV2): String {
            val bytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)
            return try { JvmP256Primitives.hashHex(bytes) } finally { bytes.fill(0) }
        }
    }

    private class Controls(val intent: ByteArray?, val journal: ByteArray?)
}

private fun encodeControl(valueName: String, payload: ByteArray, maximum: Int): ByteArray {
    if (payload.size !in 1..maximum) {
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    val role = controlRole(valueName)
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    val buffer = ByteBuffer.allocate(CONTROL_HEADER_BYTES + payload.size)
    buffer.put(CONTROL_MAGIC)
    buffer.putInt(CONTROL_VERSION)
    buffer.putInt(role)
    buffer.putInt(payload.size)
    buffer.put(digest)
    buffer.put(payload)
    digest.fill(0)
    return buffer.array()
}

private fun decodeControl(valueName: String, stored: ByteArray, maximum: Int): ByteArray {
    if (stored.size < CONTROL_HEADER_BYTES) {
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    val buffer = ByteBuffer.wrap(stored)
    val magic = ByteArray(CONTROL_MAGIC.size)
    buffer.get(magic)
    val matchesMagic = MessageDigest.isEqual(magic, CONTROL_MAGIC)
    magic.fill(0)
    val version = buffer.int
    val role = buffer.int
    val length = buffer.int
    val digest = ByteArray(32)
    buffer.get(digest)
    try {
        if (!matchesMagic || version != CONTROL_VERSION || role != controlRole(valueName) ||
            length !in 1..maximum || length != buffer.remaining()) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val payload = ByteArray(length)
        buffer.get(payload)
        val actual = MessageDigest.getInstance("SHA-256").digest(payload)
        try {
            if (!MessageDigest.isEqual(digest, actual)) {
                payload.fill(0)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return payload
        } finally {
            actual.fill(0)
        }
    } finally {
        digest.fill(0)
    }
}

private fun controlRole(valueName: String): Int = when (valueName) {
    INTENT_VALUE -> 1
    JOURNAL_VALUE -> 2
    else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}

private fun closeAttempt(block: () -> Unit, record: (Throwable) -> Unit) {
    try { block() } catch (failure: Throwable) { record(failure) }
}

private fun merge(existing: Throwable?, next: Throwable): Throwable {
    if (existing == null) return next
    existing.addSuppressed(next)
    return existing
}

private val CONTROL_MAGIC = "UKPONV02".encodeToByteArray()
private const val CONTROL_VERSION = 1
private const val CONTROL_HEADER_BYTES = 8 + 4 + 4 + 4 + 32
private const val DATABASE_NAME = "database"
private const val LOCK_NAME = "lock.v1"
private const val INTENT_VALUE = "intent"
private const val JOURNAL_VALUE = "journal"
private val CONTROL_VALUES: Set<String> = setOf(INTENT_VALUE, JOURNAL_VALUE)
