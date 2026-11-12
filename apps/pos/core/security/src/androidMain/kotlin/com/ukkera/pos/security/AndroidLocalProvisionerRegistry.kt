package com.ukkera.pos.security

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.types.DomainId
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Concrete OS-private provisioner registry. This is NOT the branch database/evidence namespace.
 * Retains a kernel lock and exact inode/UID/path custody; canonical authenticated snapshots are
 * forced, atomically replaced, directory-forced and read back before an output can be delivered.
 * Unknown files/pending writes are preserved and hold acquisition; no repair/delete/adoption.
 */
internal class AndroidLocalProvisionerRegistry(
    private val environment: AndroidSelfOwnedEnvironment,
    private val work: AndroidOwnedInstallationWork,
): LocalProvisionerRegistryStore {
    private enum class Phase { NEW, OPENING, OPEN, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private val directory = File(environment.noBackupDirectory, environment.profile.provisionerDirectoryName)
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private val ancestors = linkedMapOf<File, Stamp>()
    private var dataDescriptor: OwnedDescriptor? = null
    private var parentDescriptor: OwnedDescriptor? = null
    private var directoryDescriptor: OwnedDescriptor? = null
    private var directoryStamp: Stamp? = null
    private var lockDescriptor: OwnedDescriptor? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockStamp: Stamp? = null
    private var volumeUuid: String? = null
    private val snapshots = linkedMapOf<String, Snapshot?>(BOOTSTRAP to null, REGISTRY to null, BUSINESS_PUBLICATIONS to null, EMPLOYEE_ENROLLMENTS to null, EMPLOYEE_POLICY_ACTIVATIONS to null, DEVICE_PAIRINGS to null, DEVICE_ADMISSIONS to null)
    private var bootstrap: SelfOwnedBootstrapV1? = null
    private var companyBootstrap: LocalCompanyAuthorityBootstrapV1? = null
    private var verifiedBusinessPublications: VerifiedBusinessPublications? = null
    private var serialCustodyWrite: SerialCustodyWrite? = null

    private data class SerialCustodyWrite(
        val before: NativeCompanySerialAuthorityCustodyV1?, val after: NativeCompanySerialAuthorityCustodyV1,
    )

    override fun openNative() { openNative(existingOriginal = false) }

    /** Original read/acquisition only: no directory/lock/bootstrap creation or missing repair. */
    override fun openExistingNative() { openNative(existingOriginal = true) }

    private fun openNative(existingOriginal: Boolean) {
        check(phase == Phase.NEW)
        phase = Phase.OPENING
        if (existingOriginal) environment.requireExistingOriginalObservation() else environment.requireCurrent()
        val chain = generateSequence(environment.noBackupDirectory) { it.parentFile }.toList().asReversed()
        for (path in chain) {
            val observed = stamp(Os.lstat(path.path))
            requireDirectory(observed, owned = false)
            if (path == environment.dataDirectory || path == environment.noBackupDirectory) {
                known(observed.uid == environment.uid)
            }
            ancestors[path] = observed
        }
        dataDescriptor = openDirectory(environment.dataDirectory, requireNotNull(ancestors[environment.dataDirectory]))
        parentDescriptor = openDirectory(environment.noBackupDirectory, requireNotNull(ancestors[environment.noBackupDirectory]))
        val absent = lstatOrNull(directory) == null
        if (existingOriginal) known(!absent)
        if (absent) Os.mkdir(directory.path, DIRECTORY_MODE)
        val observed = stamp(Os.lstat(directory.path))
        requireDirectory(observed, owned = true)
        directoryStamp = observed
        directoryDescriptor = openDirectory(directory, observed)
        // Only our NEW descendant is chmodded. Framework-owned ancestors are never chmodded.
        if (absent) Os.fchmod(requireNotNull(directoryDescriptor).fd, DIRECTORY_MODE)
        known(observed.sameDirectory(stamp(Os.fstat(requireNotNull(directoryDescriptor).fd))))
        if (!existingOriginal) {
            Os.fsync(requireNotNull(parentDescriptor).fd)
            Os.fsync(requireNotNull(dataDescriptor).fd)
        }

        val lockFile = File(directory, LOCK)
        val priorNames = androidOwnedDirectoryNames(directory, REGISTRY_NAMES.size, work)
        known(priorNames.all { it in REGISTRY_NAMES })
        // Missing lock in a pre-existing namespace is not permission to create a new lock inode
        // while an old process could still hold the unlinked original one.
        known(if (absent) priorNames.isEmpty() else LOCK in priorNames)
        val newLock = absent
        val flags = OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or
            (if (newLock) OsConstants.O_CREAT or OsConstants.O_EXCL else 0)
        val descriptor = openDescriptor(lockFile, flags, FILE_MODE)
        lockDescriptor = descriptor
        val before = stamp(Os.fstat(descriptor.fd))
        requireFile(before, 0, allowEmpty = true)
        known(before.sameFile(stamp(Os.lstat(lockFile.path))))
        val stream = FileOutputStream(descriptor.fd)
        descriptor.streamOwner = stream
        val channel = stream.channel
        lockChannel = channel
        lock = channel.tryLock() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        if (newLock) { Os.fsync(descriptor.fd); Os.fsync(requireNotNull(directoryDescriptor).fd) }
        lockStamp = stamp(Os.fstat(descriptor.fd))
        known(requireNotNull(lockStamp).sameFile(stamp(Os.lstat(lockFile.path))))
        known(requireNotNull(lockStamp).device == observed.device)
        volumeUuid = environment.currentProvisionerVolumeUuid()
        checkNames()
        snapshots[BOOTSTRAP] = readSnapshot(BOOTSTRAP)
        snapshots[REGISTRY] = readSnapshot(REGISTRY)
        snapshots[BUSINESS_PUBLICATIONS] = readSnapshot(BUSINESS_PUBLICATIONS)
        snapshots[EMPLOYEE_ENROLLMENTS] = readSnapshot(EMPLOYEE_ENROLLMENTS)
        snapshots[EMPLOYEE_POLICY_ACTIVATIONS] = readSnapshot(EMPLOYEE_POLICY_ACTIVATIONS)
        snapshots[DEVICE_PAIRINGS] = readSnapshot(DEVICE_PAIRINGS)
        snapshots[DEVICE_ADMISSIONS] = readSnapshot(DEVICE_ADMISSIONS)
        if (existingOriginal) known(snapshots[BOOTSTRAP] != null)
        bootstrap = snapshots[BOOTSTRAP]?.let { snapshot ->
            val company = if (LocalCompanyAuthorityWireV1.isCompanyProfile(snapshot.bytes))
                LocalCompanyAuthorityWireV1.decodeBootstrap(snapshot.bytes) else null
            val parsed = company?.original ?: SelfOwnedInstallationWireV1.decodeBootstrap(snapshot.bytes)
            val canonical = if (company == null) SelfOwnedInstallationWireV1.encodeBootstrap(parsed)
                else LocalCompanyAuthorityWireV1.encodeBootstrap(company)
            try { known(canonical.contentEquals(snapshot.bytes)) } finally { canonical.fill(0) }
            known(parsed.target.same(environment.target) && parsed.namespace == capturedNamespace())
            companyBootstrap = company
            parsed
        }
        known(snapshots[REGISTRY] == null || bootstrap?.let { it.complete() && it.enrollmentStarted } == true)
        known(snapshots[REGISTRY] == null || companyBootstrap?.complete() != false)
        known(snapshots[BUSINESS_PUBLICATIONS] == null || (snapshots[REGISTRY] != null && companyBootstrap?.complete() == true))
        known(snapshots[EMPLOYEE_ENROLLMENTS] == null || (snapshots[REGISTRY] != null && companyBootstrap?.complete() == true))
        known(snapshots[EMPLOYEE_POLICY_ACTIVATIONS] == null || (snapshots[REGISTRY] != null && companyBootstrap?.complete() == true))
        known(snapshots[DEVICE_PAIRINGS] == null || (snapshots[REGISTRY] != null && companyBootstrap?.complete() == true))
        known(snapshots[DEVICE_ADMISSIONS] == null || (snapshots[DEVICE_PAIRINGS] != null && snapshots[REGISTRY] != null && companyBootstrap?.complete() == true))
        phase = Phase.OPEN
        assertContinuity()
    }

    override fun bootstrapRecord(): SelfOwnedBootstrapV1? { assertContinuity(); return bootstrap }

    override fun companyBootstrapRecord(): LocalCompanyAuthorityBootstrapV1? { assertContinuity(); return companyBootstrap }

    override fun namespaceIdentity(): SelfOwnedRegistryNamespaceV1 { assertContinuity(); return capturedNamespace() }

    private fun capturedNamespace(): SelfOwnedRegistryNamespaceV1 {
        val directoryIdentity = requireNotNull(directoryStamp); val lockIdentity = requireNotNull(lockStamp)
        return SelfOwnedRegistryNamespaceV1(directory.path, environment.uid, requireNotNull(volumeUuid),
            directoryIdentity.inode, lockIdentity.inode)
    }

    /** Pure registry/filesystem continuity, no recursive crypto or supplied verifier callback. */
    override fun assertContinuity() {
        known(phase == Phase.OPEN)
        assertBase()
        checkNames()
        checkSnapshotFiles()
        assertBase()
    }

    private fun assertBase() {
        environment.requireCurrent()
        for ((path, expected) in ancestors) {
            known(expected.sameDirectory(stamp(Os.lstat(path.path))))
        }
        known(requireNotNull(ancestors[environment.dataDirectory]).sameDirectory(
            stamp(Os.fstat(requireNotNull(dataDescriptor).fd))))
        known(requireNotNull(ancestors[environment.noBackupDirectory]).sameDirectory(
            stamp(Os.fstat(requireNotNull(parentDescriptor).fd))))
        val expected = requireNotNull(directoryStamp)
        known(expected.sameDirectory(stamp(Os.lstat(directory.path))) &&
            expected.sameDirectory(stamp(Os.fstat(requireNotNull(directoryDescriptor).fd))))
        val held = requireNotNull(lock)
        known(held.isValid && lockChannel?.isOpen == true)
        val lockExpected = requireNotNull(lockStamp)
        known(lockExpected.sameFile(stamp(Os.lstat(File(directory, LOCK).path))) &&
            lockExpected.sameFile(stamp(Os.fstat(requireNotNull(lockDescriptor).fd))))
        // st_dev is checked throughout this lifetime, not persisted across a real reboot.
        known(lockExpected.device == expected.device && environment.currentProvisionerVolumeUuid() == volumeUuid)
    }

    private fun checkNames(pending: String? = null) {
        val allowed = REGISTRY_NAMES + listOfNotNull(pending)
        val names = androidOwnedDirectoryNames(directory, allowed.size, work)
        known(names.size <= allowed.size && names.toSet().size == names.size && names.all { it in allowed } && LOCK in names)
        if (pending != null) known(pending in names)
    }

    private fun checkSnapshotFiles() {
        for ((name, expected) in snapshots) {
            val actual = readSnapshot(name)
            try {
                known(if (expected == null) actual == null else actual != null &&
                    expected.stamp.sameFile(actual.stamp) && expected.bytes.contentEquals(actual.bytes))
            } finally { actual?.clear() }
        }
    }

    override fun retainBootstrap(next: SelfOwnedBootstrapV1) {
        assertContinuity()
        known(snapshots[REGISTRY] == null && next.target.same(environment.target) && next.namespace == capturedNamespace())
        val old = bootstrap
        if (old == null) {
            known(next.revision == 0L && !next.enrollmentStarted && next.keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED })
        } else {
            known(next.revision == old.revision + 1 && next.actionId == old.actionId && next.rootId == old.rootId &&
                next.organizationId == old.organizationId && next.companyId == old.companyId && next.issuerId == old.issuerId &&
                next.installerId == old.installerId && sameFacts(next.facts, old.facts) && next.target.same(old.target) && next.namespace == old.namespace &&
                next.nonce == old.nonce && next.keys.map { it.keyId } == old.keys.map { it.keyId })
            val changed = old.keys.zip(next.keys).filter { (left, right) -> left != right }
            if (changed.isEmpty()) {
                known(old.complete() && !old.enrollmentStarted && next.enrollmentStarted)
            } else {
                known(changed.size == 1 && !old.enrollmentStarted && !next.enrollmentStarted)
                val (before, after) = changed.single()
                known(before.role == after.role && before.keyId == after.keyId)
                when (before.phase) {
                    SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> known(after.phase == SelfOwnedKeyIntentPhaseV1.STARTED &&
                        after.startedBoot == environment.bootCount)
                    SelfOwnedKeyIntentPhaseV1.STARTED -> known(
                        (after.phase == SelfOwnedKeyIntentPhaseV1.STARTED && after.startedBoot == environment.bootCount &&
                            requireNotNull(after.startedBoot) > requireNotNull(before.startedBoot)) ||
                            (after.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE && after.startedBoot == before.startedBoot))
                    SelfOwnedKeyIntentPhaseV1.COMPLETE -> known(false)
                }
            }
        }
        val company = companyBootstrap?.nextOriginal(next)
        val bytes = if (company == null) SelfOwnedInstallationWireV1.encodeBootstrap(next)
            else LocalCompanyAuthorityWireV1.encodeBootstrap(company)
        retainBootstrapBytes(bytes, company != null)
    }

    /** Explicit fresh wrapper only; no upgrade/adoption of a legacy original. */
    override fun retainCompanyBootstrap(next: LocalCompanyAuthorityBootstrapV1) {
        assertContinuity()
        known(snapshots[REGISTRY] == null && next.original.target.same(environment.target) &&
            next.original.namespace == capturedNamespace())
        val old = companyBootstrap
        if (old == null) {
            known(bootstrap == null && snapshots[BOOTSTRAP] == null && next.revision == 0L &&
                next.original.revision == 0L && !next.original.enrollmentStarted &&
                next.original.keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED } &&
                next.headKeys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED })
        } else {
            known(next.revision == old.revision + 1L && !old.original.enrollmentStarted &&
                next.headAuthorityId == old.headAuthorityId && next.headEnrollmentId == old.headEnrollmentId &&
                next.headName == old.headName && next.headLogin == old.headLogin && next.lifetime == old.lifetime)
            val before = SelfOwnedInstallationWireV1.encodeBootstrap(old.original)
            val after = SelfOwnedInstallationWireV1.encodeBootstrap(next.original)
            try { known(before.contentEquals(after)) } finally { before.fill(0); after.fill(0) }
            val changed = old.headKeys.zip(next.headKeys).filter { (a, b) -> a != b }
            known(changed.size == 1 && old.original.complete())
            val (prior, updated) = changed.single()
            known(prior.role == updated.role && prior.keyId == updated.keyId && prior.keyRevision == updated.keyRevision)
            when (prior.phase) {
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> known(updated.phase == SelfOwnedKeyIntentPhaseV1.STARTED &&
                    updated.startedBoot == environment.bootCount)
                SelfOwnedKeyIntentPhaseV1.STARTED -> known(
                    (updated.phase == SelfOwnedKeyIntentPhaseV1.STARTED && updated.startedBoot == environment.bootCount &&
                        requireNotNull(updated.startedBoot) > requireNotNull(prior.startedBoot)) ||
                        (updated.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE && updated.startedBoot == prior.startedBoot))
                SelfOwnedKeyIntentPhaseV1.COMPLETE -> known(false)
            }
        }
        retainBootstrapBytes(LocalCompanyAuthorityWireV1.encodeBootstrap(next), companyProfile = true)
    }

    private fun retainBootstrapBytes(bytes: ByteArray, companyProfile: Boolean) {
        try {
            replaceFile(BOOTSTRAP, bytes)
            val retained = requireNotNull(snapshots[BOOTSTRAP])
            if (companyProfile) {
                companyBootstrap = LocalCompanyAuthorityWireV1.decodeBootstrap(retained.bytes)
                bootstrap = requireNotNull(companyBootstrap).original
            } else {
                known(companyBootstrap == null)
                bootstrap = SelfOwnedInstallationWireV1.decodeBootstrap(retained.bytes)
            }
            known(retained.bytes.contentEquals(bytes))
        } finally { bytes.fill(0) }
        assertContinuity()
    }

    override fun retainEnrollmentStart() {
        val current = bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        known(current.complete() && companyBootstrap?.complete() != false &&
            !current.enrollmentStarted && snapshots[REGISTRY] == null)
        if (current.revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        retainBootstrap(current.copy(revision = current.revision + 1L, enrollmentStarted = true))
    }

    override fun readState(keys: LocalProvisionerNativeKeys): LocalProvisionerRegistryStateV1? {
        if (companyBootstrap != null) return readCompanyState(keys)?.original
        assertContinuity()
        val stored = snapshots[REGISTRY] ?: return null
        val original = bootstrap ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        known(original.complete() && original.enrollmentStarted)
        keys.assertAllOriginalKeys()
        val envelope = SelfOwnedInstallationWireV1.decodeEnvelope(stored.bytes)
        val aad = SelfOwnedInstallationWireV1.registryAad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        try {
            val state = SelfOwnedInstallationWireV1.decodeRegistry(plaintext)
            val canonical = SelfOwnedInstallationWireV1.encodeRegistry(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateState(state, keys, original)
            assertContinuity()
            return state
        } finally { plaintext.fill(0) }
    }

    override fun retainState(next: LocalProvisionerRegistryStateV1, keys: LocalProvisionerNativeKeys): LocalProvisionerRegistryStateV1 {
        val companyOriginal = companyBootstrapRecord()
        if (companyOriginal != null) {
            val observedSnapshot = snapshots[REGISTRY]
            val previous = readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            // Keep the fresh custody/key checks that the former second read performed.
            // Only repeated decryption/canonicalization/previous-state validation is removed.
            assertContinuity()
            known(companyBootstrap === companyOriginal && snapshots[REGISTRY] === observedSnapshot)
            keys.assertAllCompanyKeys()
            assertContinuity()
            known(companyBootstrap === companyOriginal && snapshots[REGISTRY] === observedSnapshot)
            // Legacy P1 mutation advances the ONE whole-registry revision and preserves ALL extensions.
            return retainObservedCompanyState(previous.copy(original = next), keys, companyOriginal,
                previous).original
        }
        val original = bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        known(original.complete() && original.enrollmentStarted)
        val previous = readState(keys)
        validateState(next, keys, original)
        validateOriginalTransition(previous, next)
        val plaintext = SelfOwnedInstallationWireV1.encodeRegistry(next)
        val aad = SelfOwnedInstallationWireV1.registryAad(original)
        val envelope = try { keys.sealRegistry(aad, plaintext) } finally { plaintext.fill(0); aad.fill(0) }
        val bytes = try { SelfOwnedInstallationWireV1.encodeEnvelope(envelope.nonce, envelope.ciphertext, envelope.signature) }
        finally { envelope.clear() }
        try { replaceFile(REGISTRY, bytes) } finally { bytes.fill(0) }
        // Actual authenticated force/readback, not an in-memory acknowledgement.
        return requireNotNull(readState(keys)).also { known(it.revision == next.revision) }
    }

    override fun readCompanyState(keys: LocalProvisionerNativeKeys,
        diagnoseRetentionReadback: Boolean): LocalCompanyAuthorityStateV1? {
        assertContinuity()
        if (diagnoseRetentionReadback) {
            try { Log.i("UkkeraNativeOwner", "Company retention phase: readback-continuity-returned") }
            catch (_: Throwable) { /* static diagnostics never change authority or the original result */ }
        }
        val original = companyBootstrap ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val stored = snapshots[REGISTRY] ?: return null
        known(original.complete() && original.original.enrollmentStarted)
        keys.assertAllCompanyKeys()
        if (diagnoseRetentionReadback) {
            try { Log.i("UkkeraNativeOwner", "Company retention phase: readback-keys-returned") }
            catch (_: Throwable) { /* the original watchdog and mandatory key checks remain unchanged */ }
        }
        val envelope = LocalCompanyAuthorityWireV1.decodeEnvelope(stored.bytes)
        val aad = LocalCompanyAuthorityWireV1.registryAad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        try {
            val state = LocalCompanyAuthorityWireV1.decodeState(plaintext)
            val canonical = LocalCompanyAuthorityWireV1.encodeState(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateCompanyState(state, original, keys)
            assertContinuity()
            return state
        } finally { plaintext.fill(0) }
    }

    override fun retainCompanyState(next: LocalCompanyAuthorityStateV1, keys: LocalProvisionerNativeKeys): LocalCompanyAuthorityStateV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        known(original.complete() && original.original.enrollmentStarted)
        val previous = readCompanyState(keys)
        return retainObservedCompanyState(next, keys, original, previous)
    }

    /** Only the two synchronous entry points above supply their freshly authenticated
     * previous state. No caller-supplied observation, cache or cross-task reuse exists.
     * The P1 projection need not decrypt/validate that same snapshot twice before writing;
     * current physical custody, native keys and complete post-write readback remain mandatory. */
    private fun retainObservedCompanyState(next: LocalCompanyAuthorityStateV1, keys: LocalProvisionerNativeKeys,
        original: LocalCompanyAuthorityBootstrapV1, previous: LocalCompanyAuthorityStateV1?): LocalCompanyAuthorityStateV1 {
        validateState(next.original, keys, original.original)
        validateOriginalTransition(previous?.original, next.original)
        validateCompanyExtensions(next, original, keys)
        validateCompanyTransition(previous, next)
        // Static return diagnostics are outside the lifecycle gate; the original watchdog stays armed.
        try { Log.i("UkkeraNativeOwner", "Company retention phase: validation-returned") }
        catch (_: Throwable) { /* diagnostics never replace validation or alter the original result */ }
        // Every writer, including legacy P1 projections, preserves completion reservations.
        val plaintext = encodeCompanyStateWithCompletionCapacity(next)
        val aad = LocalCompanyAuthorityWireV1.registryAad(original)
        val envelope = try { keys.sealRegistry(aad, plaintext) } finally { plaintext.fill(0); aad.fill(0) }
        val bytes = try { LocalCompanyAuthorityWireV1.encodeEnvelope(envelope) } finally { envelope.clear() }
        try { replaceFile(REGISTRY, bytes) } finally { bytes.fill(0) }
        try { Log.i("UkkeraNativeOwner", "Company retention phase: replacement-returned") }
        catch (_: Throwable) { /* diagnostics never replace authenticated readback or alter the original result */ }
        return requireNotNull(readCompanyState(keys, diagnoseRetentionReadback = true))
            .also { known(it.original.revision == next.original.revision) }
    }

    /** Separate authenticated native-local ledger; absent means no publication trust, never a new root. */
    override fun readBusinessPublications(keys: LocalProvisionerNativeKeys): NativeCompanyBusinessPublicationStateV1? {
        assertContinuity()
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val company = readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val source = readBusinessPublicationsSource(keys, original)
        val custody = company.serialCustody
        if (custody == null) {
            // Optional absence alone is NOT an origin or permission to adopt serial records.
            known(source?.records.orEmpty().none { it.kind.isOriginalSerialKindV1() })
        } else {
            try { custody.requireSettledSource(source) }
            catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        }
        return source
    }

    /** Internal actual-source read for the exact next-cut force/recovery protocol only. */
    private fun readBusinessPublicationsSource(keys: LocalProvisionerNativeKeys,
        original: LocalCompanyAuthorityBootstrapV1): NativeCompanyBusinessPublicationStateV1? {
        assertContinuity()
        val stored = snapshots[BUSINESS_PUBLICATIONS] ?: return null
        // Reuse deterministic verification only on this exact same-owner protected snapshot.
        // Physical continuity and live original key/company checks above still run on every
        // read. A forced own replacement creates a different Snapshot and must verify anew.
        verifiedBusinessPublications?.let { verified ->
            if (verified.snapshot === stored && verified.original === original && verified.keys === keys) {
                assertContinuity()
                return verified.state
            }
        }
        val envelope = LocalCompanyAuthorityWireV1.decodeEnvelope(stored.bytes)
        val aad = NativeCompanyBusinessPublicationWireV1.aad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        return try {
            val state = NativeCompanyBusinessPublicationWireV1.decode(plaintext)
            val canonical = NativeCompanyBusinessPublicationWireV1.encode(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateBusinessPublications(state, original, keys)
            assertContinuity()
            verifiedBusinessPublications = VerifiedBusinessPublications(stored, original, keys, state)
            state
        } finally { plaintext.fill(0) }
    }

    override fun retainBusinessPublications(next: NativeCompanyBusinessPublicationStateV1,
        keys: LocalProvisionerNativeKeys): NativeCompanyBusinessPublicationStateV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val company = readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val previous = readBusinessPublications(keys)
        NativeCompanyBusinessPublicationWireV1.validateTransition(previous, next)
        validateBusinessPublications(next, original, keys)
        // BEGIN new-writer pairing identity reservation V1 (old history/references unchanged).
        val existingActions = previous?.records.orEmpty().map { it.originalActionId }.toSet()
        requireNoNewPairingIdentityCollisions(next.records.filter { it.originalActionId !in existingActions }.flatMap {
            it.reservedIdentityIdsV1()
        }, keys)
        val previousIds = previous?.records.orEmpty().flatMap {
            it.reservedIdentityIdsV1()
        }.toSet()
        requireNoNewPolicyIdentityCollisions(next.records.flatMap {
            it.reservedIdentityIdsV1()
        }.filterNot { it in previousIds }, keys)
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap {
            it.reservedIdentityIdsV1()
        }.filterNot { it in previousIds }, keys)
        // END new-writer pairing identity reservation V1.
        val plaintext = NativeCompanyBusinessPublicationWireV1.encodeWithCompletionCapacity(next)
        try {
            val changedSerial = next.records.filter { record -> record.kind.isOriginalSerialKindV1() &&
                previous?.records.orEmpty().none { it == record } }
            known(changedSerial.size <= 1)
            val intent = changedSerial.singleOrNull()?.let { changed ->
                val pending = try {
                    company.serialCustody?.beginNext(previous, next, changed)
                        ?: NativeCompanySerialAuthorityCustodyV1.firstIntent(previous, next, changed)
                } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
                retainSerialCustody(pending, keys).serialCustody
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val aad = NativeCompanyBusinessPublicationWireV1.aad(original)
            val envelope = try { keys.sealRegistry(aad, plaintext) } finally { aad.fill(0) }
            val bytes = try { LocalCompanyAuthorityWireV1.encodeEnvelope(envelope) } finally { envelope.clear() }
            try { replaceFile(BUSINESS_PUBLICATIONS, bytes) } finally { bytes.fill(0) }
            if (intent != null) {
                val actual = readBusinessPublicationsSource(keys, original)
                    ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                known(actual == next && readCompanyState(keys)?.serialCustody == intent)
                val settled = try { intent.settleActualNext(actual) }
                catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
                retainSerialCustody(settled, keys)
            }
        } finally { plaintext.fill(0) }
        return requireNotNull(readBusinessPublications(keys)).also { known(it == next) }
    }

    override fun readOriginalSerialCustody(keys: LocalProvisionerNativeKeys): OriginalLocalSerialCustodyReferenceV1 {
        val state = readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        return state.serialCustody?.reference() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
    }

    /** Lookup-only recovery of a forced exact next cut. No companion replay or signature. */
    override fun recoverOriginalSerialCustody(originalActionId: DomainId,
        keys: LocalProvisionerNativeKeys): OriginalLocalSerialCustodyReferenceV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val state = readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val custody = state.serialCustody ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val pending = custody.intent
        if (pending == null) {
            val source = readBusinessPublications(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            known(source.records.any { it.kind.isOriginalSerialKindV1() && it.originalActionId == originalActionId })
            return custody.reference()
        }
        if (pending.originalActionId != originalActionId) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val source = readBusinessPublicationsSource(keys, original) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val settled = try { custody.settleActualNext(source) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        val retained = retainSerialCustody(settled, keys).serialCustody ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        readBusinessPublications(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return retained.reference()
    }

    private fun retainSerialCustody(next: NativeCompanySerialAuthorityCustodyV1,
        keys: LocalProvisionerNativeKeys): LocalCompanyAuthorityStateV1 {
        check(serialCustodyWrite == null)
        val state = readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        serialCustodyWrite = SerialCustodyWrite(state.serialCustody, next)
        return try { retainCompanyState(state.next(serialCustody = next), keys) }
        finally { serialCustodyWrite = null }
    }

    private fun validateBusinessPublications(value: NativeCompanyBusinessPublicationStateV1,
        original: LocalCompanyAuthorityBootstrapV1, keys: LocalProvisionerNativeKeys) {
        val company = CompanyScope(original.original.organizationId, original.original.companyId)
        val reserved = original.allIds().toSet()
        known(value.records.all { it.company == company && it.originalActionId !in reserved && it.keyId !in reserved &&
            it.originalActionId != it.keyId && it.originalActionId != it.publicationId && it.keyId != it.publicationId })
        val nativeIds = value.records.flatMap { listOf(it.originalActionId, it.keyId) }
        known(nativeIds.distinct().size == nativeIds.size)
        for (record in value.records) if (record.phase == CompanyBusinessPublicationPhase.COMPLETED) {
            keys.verifyBusinessPublication(record)
        }
    }

    /** Additive employee ledger on this exact original registry/descriptor/key lifetime. */
    override fun readEmployeeEnrollments(keys: LocalProvisionerNativeKeys): NativeEmployeeEnrollmentStateV1? {
        assertContinuity()
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val stored = snapshots[EMPLOYEE_ENROLLMENTS] ?: return null
        val envelope = LocalCompanyAuthorityWireV1.decodeEnvelope(stored.bytes)
        val aad = NativeEmployeeEnrollmentWireV1.aad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        return try {
            val state = NativeEmployeeEnrollmentWireV1.decode(plaintext)
            val canonical = NativeEmployeeEnrollmentWireV1.encode(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateEmployeeEnrollments(state, original, keys); assertContinuity(); state
        } finally { plaintext.fill(0) }
    }

    override fun retainEmployeeEnrollments(next: NativeEmployeeEnrollmentStateV1,
        keys: LocalProvisionerNativeKeys): NativeEmployeeEnrollmentStateV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val previous = readEmployeeEnrollments(keys)
        NativeEmployeeEnrollmentWireV1.validateTransition(previous, next)
        validateEmployeeEnrollments(next, original, keys)
        // BEGIN new-writer pairing identity reservation V1 (old history/references unchanged).
        val existingActions = previous?.records.orEmpty().map { it.originalActionId }.toSet()
        requireNoNewPairingIdentityCollisions(next.records.filter { it.originalActionId !in existingActions }.flatMap { it.nativeIds() }, keys)
        val previousIds = previous?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        requireNoNewPolicyIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds }, keys)
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds }, keys)
        // END new-writer pairing identity reservation V1.
        val plaintext = NativeEmployeeEnrollmentWireV1.encodeWithCompletionCapacity(next)
        val aad = NativeEmployeeEnrollmentWireV1.aad(original)
        val envelope = try { keys.sealRegistry(aad, plaintext) } finally { plaintext.fill(0); aad.fill(0) }
        val bytes = try { LocalCompanyAuthorityWireV1.encodeEnvelope(envelope) } finally { envelope.clear() }
        try { replaceFile(EMPLOYEE_ENROLLMENTS, bytes) } finally { bytes.fill(0) }
        return requireNotNull(readEmployeeEnrollments(keys)).also { known(it == next) }
    }

    /** Additive employee-policy ledger on this exact original registry/descriptor/key lifetime. */
    override fun readEmployeePolicyActivations(keys: LocalProvisionerNativeKeys): NativeEmployeePolicyActivationStateV1? {
        assertContinuity()
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val stored = snapshots[EMPLOYEE_POLICY_ACTIVATIONS] ?: return null
        val envelope = LocalCompanyAuthorityWireV1.decodeEnvelope(stored.bytes)
        val aad = NativeEmployeePolicyActivationWireV1.aad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        return try {
            val state = NativeEmployeePolicyActivationWireV1.decode(plaintext)
            val canonical = NativeEmployeePolicyActivationWireV1.encode(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateEmployeePolicyActivations(state, original, keys); assertContinuity(); state
        } finally { plaintext.fill(0) }
    }

    override fun retainEmployeePolicyActivations(next: NativeEmployeePolicyActivationStateV1,
        keys: LocalProvisionerNativeKeys): NativeEmployeePolicyActivationStateV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val previous = readEmployeePolicyActivations(keys)
        NativeEmployeePolicyActivationWireV1.validateTransition(previous, next)
        validateEmployeePolicyActivations(next, original, keys)
        // BEGIN new-writer pairing identity reservation V1 (old history/references unchanged).
        val existingActions = previous?.records.orEmpty().map { it.originalActionId }.toSet()
        requireNoNewPairingIdentityCollisions(next.records.filter { it.originalActionId !in existingActions }.flatMap { it.nativeIds() }, keys)
        val previousIds = previous?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds }, keys)
        // END new-writer pairing identity reservation V1.
        val plaintext = NativeEmployeePolicyActivationWireV1.encodeWithCompletionCapacity(next)
        val aad = NativeEmployeePolicyActivationWireV1.aad(original)
        val envelope = try { keys.sealRegistry(aad, plaintext) } finally { plaintext.fill(0); aad.fill(0) }
        val bytes = try { LocalCompanyAuthorityWireV1.encodeEnvelope(envelope) } finally { envelope.clear() }
        try { replaceFile(EMPLOYEE_POLICY_ACTIVATIONS, bytes) } finally { bytes.fill(0) }
        return requireNotNull(readEmployeePolicyActivations(keys)).also { known(it == next) }
    }

    /** NEW pairing preparation ledger on the same retained registry lock/namespace/AEAD.
     * Missing, unknown or partially replaced files are never an authority/key/create bypass. */
    override fun readDevicePairings(keys: LocalProvisionerNativeKeys): NativeDevicePairingStateV1? {
        assertContinuity()
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val stored = snapshots[DEVICE_PAIRINGS] ?: return null
        val envelope = LocalCompanyAuthorityWireV1.decodeEnvelope(stored.bytes)
        val aad = NativeDevicePairingWireV1.aad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        return try {
            val state = NativeDevicePairingWireV1.decode(plaintext)
            val canonical = NativeDevicePairingWireV1.encode(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateDevicePairings(state, original, keys); assertContinuity(); state
        } finally { plaintext.fill(0) }
    }

    override fun retainDevicePairings(next: NativeDevicePairingStateV1, keys: LocalProvisionerNativeKeys): NativeDevicePairingStateV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val previous = readDevicePairings(keys)
        NativeDevicePairingWireV1.validateTransition(previous, next)
        validateDevicePairings(next, original, keys)
        val previousIds = previous?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        requireNoNewPolicyIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds }, keys)
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds }, keys)
        val plaintext = NativeDevicePairingWireV1.encodeWithCompletionCapacity(next)
        val aad = NativeDevicePairingWireV1.aad(original)
        val envelope = try { keys.sealRegistry(aad, plaintext) } finally { plaintext.fill(0); aad.fill(0) }
        val bytes = try { LocalCompanyAuthorityWireV1.encodeEnvelope(envelope) } finally { envelope.clear() }
        try { replaceFile(DEVICE_PAIRINGS, bytes) } finally { bytes.fill(0) }
        return requireNotNull(readDevicePairings(keys)).also { known(it == next) }
    }

    /** Only pairing-created identities and deliberate target/key references, not the
     * original root/company/branch/admitted-device references embedded in the body. */
    override fun devicePairingReservedIdentityIds(keys: LocalProvisionerNativeKeys): Set<DomainId> =
        readDevicePairings(keys)?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
    private fun requireNoNewPairingIdentityCollisions(ids: List<DomainId>, keys: LocalProvisionerNativeKeys) {
        val reserved = devicePairingReservedIdentityIds(keys)
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    /** Newly allocated policy action/request/dispatch identities, never employee target references. */
    private fun requireNoNewPolicyIdentityCollisions(ids: List<DomainId>, keys: LocalProvisionerNativeKeys) {
        if (snapshots[EMPLOYEE_POLICY_ACTIVATIONS] == null) return
        val reserved = readEmployeePolicyActivations(keys)?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    /** Separate additive consumption ledger; pairing originals and their predecessor
     * grammar remain in the complete original pairing file and are never rewritten. */
    override fun readDeviceAdmissions(keys: LocalProvisionerNativeKeys): NativeDeviceAdmissionStateV1? {
        assertContinuity()
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val stored = snapshots[DEVICE_ADMISSIONS] ?: return null
        val envelope = LocalCompanyAuthorityWireV1.decodeEnvelope(stored.bytes)
        val aad = NativeDeviceAdmissionWireV1.aad(original)
        val plaintext = try { keys.openRegistry(aad, envelope) } finally { envelope.clear(); aad.fill(0) }
        return try {
            val state = NativeDeviceAdmissionWireV1.decode(plaintext)
            val canonical = NativeDeviceAdmissionWireV1.encode(state)
            try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
            validateDeviceAdmissions(state, original, keys); assertContinuity(); state
        } finally { plaintext.fill(0) }
    }

    override fun retainDeviceAdmissions(next: NativeDeviceAdmissionStateV1, keys: LocalProvisionerNativeKeys): NativeDeviceAdmissionStateV1 {
        val original = companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val previous = readDeviceAdmissions(keys)
        NativeDeviceAdmissionWireV1.validateTransition(previous, next)
        validateDeviceAdmissions(next, original, keys)
        NativeDeviceAdmissionWireV1.requireCompletionCapacity(next, 4096,
            next.records.maxOf { it.pairing.body.requested().retainedAdmissions.size })
        val plaintext = NativeDeviceAdmissionWireV1.encode(next)
        val aad = NativeDeviceAdmissionWireV1.aad(original)
        val envelope = try { keys.sealRegistry(aad, plaintext) } finally { plaintext.fill(0); aad.fill(0) }
        val bytes = try { LocalCompanyAuthorityWireV1.encodeEnvelope(envelope) } finally { envelope.clear() }
        try { replaceFile(DEVICE_ADMISSIONS, bytes) } finally { bytes.fill(0) }
        return requireNotNull(readDeviceAdmissions(keys)).also { known(it == next) }
    }

    /** Dispatch-created identities only, not deliberate references to pairing originals. */
    override fun deviceAdmissionReservedIdentityIds(keys: LocalProvisionerNativeKeys): Set<DomainId> =
        readDeviceAdmissions(keys)?.records.orEmpty().map { it.dispatchId }.toSet()
    private fun requireNoNewDeviceAdmissionIdentityCollisions(ids: List<DomainId>, keys: LocalProvisionerNativeKeys) {
        if (snapshots[DEVICE_ADMISSIONS] == null) return
        val reserved = deviceAdmissionReservedIdentityIds(keys)
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun validateDeviceAdmissions(value: NativeDeviceAdmissionStateV1,
        original: LocalCompanyAuthorityBootstrapV1, keys: LocalProvisionerNativeKeys) {
        // Embedded approval DATA is never its own source. This read verifies the genuine
        // FULL pairing ledger, including each unchanged unsigned-predecessor link.
        val pairings = readDevicePairings(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val reserved = original.allIds().toSet() + pairings.records.flatMap { it.nativeIds() + it.body.origin.reservedOriginalIds() } +
            readEmployeeEnrollments(keys)?.records.orEmpty().flatMap { it.nativeIds() } +
            readEmployeePolicyActivations(keys)?.records.orEmpty().flatMap { it.nativeIds() } +
            readBusinessPublications(keys)?.records.orEmpty().flatMap { it.reservedIdentityIdsV1() }
        for (record in value.records) {
            known(pairings.records.singleOrNull { it.body.originalActionId == record.pairing.body.originalActionId } == record.pairing &&
                record.dispatchId !in reserved)
        }
    }

    private fun validateDevicePairings(value: NativeDevicePairingStateV1,
        original: LocalCompanyAuthorityBootstrapV1, keys: LocalProvisionerNativeKeys) {
        val company = checkNotNull(readCompanyState(keys))
        val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val fingerprint = AndroidP256Primitives.rootFingerprint(keys.rootBody())
        val reserved = original.allIds().toSet()
        val otherIds = readEmployeeEnrollments(keys)?.records.orEmpty().flatMap { it.nativeIds() }.toSet() +
            readBusinessPublications(keys)?.records.orEmpty().flatMap { it.reservedIdentityIdsV1() }
        for (record in value.records) {
            val origin = record.body.origin; val requested = record.body.requested(); val target = requested.target
            known(origin.coordinates.scope.organizationId == original.original.organizationId &&
                origin.coordinates.scope.companyId == original.original.companyId && origin.rootId == original.original.rootId &&
                origin.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId && origin.rootDocumentDigest == rootDigest &&
                origin.foundingActionId == original.original.actionId && origin.headAuthorityId == original.headAuthorityId &&
                origin.headEnrollmentId == original.headEnrollmentId && origin.rootFingerprint == fingerprint &&
                record.body.preparedHead.companyName == original.original.facts.companyName &&
                record.body.preparedHead.headName == original.headName && record.body.preparedHead.headLogin == original.headLogin &&
                record.nativeIds().none { it in reserved || it in origin.reservedOriginalIds() || it in otherIds } &&
                requested.admission.deviceId != origin.coordinates.deviceId)
            val point = target.copyPublicPoint()
            try {
                AndroidP256Primitives.publicKey(point) // Validate actual curve/point, not attestation from DATA.
                record.possession?.let { retained ->
                    val bytes = retained.copyBytes()
                    val proof = try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
                    val challenge = LocalDevicePairingChallenge.fromNative(record.body)
                    val message = NativeDevicePairingWireV1.possessionMessage(challenge); val signature = proof.copySignature()
                    try { known(AndroidP256Primitives.verify(point, message, signature)) }
                    finally { message.fill(0); signature.fill(0) }
                }
            } finally { point.fill(0) }
            if (record.rootEndorsement.size != 0) keys.verifyDevicePairing(record)
            if (record.retirementEndorsement.size != 0) keys.verifyUnsignedDevicePairingDisposition(record)
        }
    }

    private fun validateEmployeeEnrollments(value: NativeEmployeeEnrollmentStateV1,
        original: LocalCompanyAuthorityBootstrapV1, keys: LocalProvisionerNativeKeys) {
        val rootBytes = checkNotNull(readCompanyState(keys)).original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val fingerprint = AndroidP256Primitives.rootFingerprint(keys.rootBody())
        val reserved = original.allIds().toSet()
        for (record in value.records) {
            val target = record.target; val requested = record.requested()
            known(target.coordinates.scope.organizationId == original.original.organizationId &&
                target.coordinates.scope.companyId == original.original.companyId && target.rootId == original.original.rootId &&
                target.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId && target.rootDocumentDigest == rootDigest &&
                target.foundingActionId == original.original.actionId && target.headAuthorityId == original.headAuthorityId &&
                target.headEnrollmentId == original.headEnrollmentId && target.rootFingerprint == fingerprint &&
                record.preparedHead.companyName == original.original.facts.companyName && record.preparedHead.headName == original.headName &&
                record.preparedHead.headLogin == original.headLogin && record.nativeIds().none { it in reserved || it in target.reservedOriginalIds() } &&
                requested.definition.actorId != target.coordinates.firstActorId)
            if (record.rootEndorsement.size != 0) keys.verifyEmployeeEnrollment(record)
        }
        val businessIds = readBusinessPublications(keys)?.records.orEmpty().flatMap {
            it.reservedIdentityIdsV1()
        }.toSet()
        known(value.records.flatMap { it.nativeIds() }.none { it in businessIds })
    }

    private fun validateEmployeePolicyActivations(value: NativeEmployeePolicyActivationStateV1,
        original: LocalCompanyAuthorityBootstrapV1, keys: LocalProvisionerNativeKeys) {
        val rootBytes = checkNotNull(readCompanyState(keys)).original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val fingerprint = AndroidP256Primitives.rootFingerprint(keys.rootBody())
        val reserved = original.allIds().toSet()
        for (record in value.records) {
            val target = record.target; val requested = record.requested()
            known(target.coordinates.scope.organizationId == original.original.organizationId &&
                target.coordinates.scope.companyId == original.original.companyId && target.rootId == original.original.rootId &&
                target.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId && target.rootDocumentDigest == rootDigest &&
                target.foundingActionId == original.original.actionId && target.headAuthorityId == original.headAuthorityId &&
                target.headEnrollmentId == original.headEnrollmentId && target.rootFingerprint == fingerprint &&
                record.preparedHead.companyName == original.original.facts.companyName && record.preparedHead.headName == original.headName &&
                record.preparedHead.headLogin == original.headLogin && record.nativeIds().none { it in reserved || it in target.reservedOriginalIds() } &&
                requested.request.action == LocalProvisioningAction.ACTIVATE_POLICY)
            if (record.rootEndorsement.size != 0) keys.verifyEmployeePolicyActivation(record)
        }
        val businessIds = readBusinessPublications(keys)?.records.orEmpty().flatMap {
            it.reservedIdentityIdsV1()
        }.toSet()
        val employeeIds = readEmployeeEnrollments(keys)?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        val pairingIds = devicePairingReservedIdentityIds(keys)
        known(value.records.flatMap { it.nativeIds() }.none { it in businessIds || it in employeeIds || it in pairingIds })
    }

    /** Preflight BEFORE enrollment-start or a new claim; this grants no signing permission. */
    override fun requireCompanyCapacity(next: LocalCompanyAuthorityStateV1) {
        encodeCompanyStateWithCompletionCapacity(next).fill(0)
    }

    private fun installationTerminalCapacity(): Long = maxOf(
        4L + LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES,
        8L + LocalBranchInstallationWireV2.MAXIMUM_ACKNOWLEDGEMENT_BYTES +
            LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES)

    private fun encodeCompanyStateWithCompletionCapacity(value: LocalCompanyAuthorityStateV1): ByteArray {
        val encoded = try { LocalCompanyAuthorityWireV1.encodeState(value) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED) }
        try {
            // Fixed-width counters/flags never grow. An optional reservation UUID needs 40 bytes.
            var reserved = if (value.head.reservationId == null) 40L else 0L
            var reservedRevisions = 0L
            value.serialCustody?.let { custody ->
                val marker = NativeCompanySerialAuthorityCustodyWireV1.encode(custody)
                try { reserved += NativeCompanySerialAuthorityCustodyWireV1.MAXIMUM_BYTES - marker.size }
                finally { marker.fill(0) }
                reservedRevisions += custody.reservedCompletionRevisions
                if (custody.reservedCompletionRevisions > (Long.MAX_VALUE - 1L) - custody.revision)
                    selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            }
            // The legacy installer bucket remains independently usable on the same registry.
            if (value.original.installers.single().reservationId == null) reserved += 40L
            for (allocation in value.original.allocations) if (allocation.grant == null) {
                // Fresh head publications share this file with the ORIGINAL P1 preparation.
                // They cannot spend its already-admitted exact-result completion capacity.
                reserved += 4L + SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES
                reservedRevisions += if (allocation.phase == LocalProvisionerAllocationPhaseV1.ALLOCATED) 2L else 1L
            }
            if (value.installationAdmission != null) {
                // Two length-prefixed UUIDs, sequence, two digests, complete P3 frame and
                // four existing presence flags. Count itself is already fixed-width.
                reserved += 2L * (4 + 36) + 8L + 2L * (4 + 64) +
                    4L + LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES + 4L
                reserved += 4L + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES + installationTerminalCapacity()
                // Claim, human output, ACK intent and ACK output. Genuine denial-only
                // claim+tombstone needs fewer. Authentication consumes only extra headroom.
                reservedRevisions += 4L
                // Do not credit the bytes freed when this exact reservation becomes a claim.
                // This conservatively survives the P4-force -> issuer-claim crash cut.
            }
            for (record in value.policies) if (record.signedPolicy == null) {
                reserved += 4L + LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES
                // PREPARED -> SIGNING_INTENT uses six more ASCII bytes than PREPARED.
                if (record.phase == LocalCompanyPolicyPhaseV1.PREPARED) reserved += 6L
                reservedRevisions += if (record.phase == LocalCompanyPolicyPhaseV1.PREPARED) 2L else 1L
            }
            for (claim in value.installationClaims) {
                if (claim.retirementEvidence != null || claim.signedAcknowledgement != null) continue
                if (claim.signedHumanApproval == null) reserved += 4L + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES
                if (claim.acknowledgementFrame != null) {
                    reserved += 4L + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES
                    reservedRevisions += 1L
                } else {
                    // Genuine retirement and positive completion are exclusive outcomes. Reserve
                    // the larger full outcome before any claim/signature can be retained.
                    reserved += installationTerminalCapacity()
                    reservedRevisions += if (claim.signedHumanApproval == null) 3L else 2L
                }
            }
            // Terminal writes spend/reduce their own reservation. Unrelated writes, including
            // throttles and legacy projections, may not exhaust the sequence first. There is
            // no reset/wrap; a capacity refusal before new authentication is not an approval.
            val remainingRevisions = (Long.MAX_VALUE - 1L) - value.original.revision
            if (encoded.size.toLong() + reserved > SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES ||
                reservedRevisions > remainingRevisions) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            }
            return encoded
        } catch (failure: Throwable) { encoded.fill(0); throw failure }
    }

    private fun validateCompanyTransition(previous: LocalCompanyAuthorityStateV1?, next: LocalCompanyAuthorityStateV1) {
        try { requireNativeCompanySerialCustodyTransitionV1(previous?.serialCustody, next.serialCustody) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        if (previous?.serialCustody != next.serialCustody) {
            val permission = serialCustodyWrite ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            known(permission.before == previous?.serialCustody && permission.after == next.serialCustody)
        }
        if (previous == null) {
            val head = next.head
            known(head.accountRevision == 0L && head.credentialRevision == 0L && head.enabled &&
                head.policyRevision == 0L && head.policyEnabled && head.installationRevision == 0L && head.installationEnabled &&
                head.failedAttempts == 0 && head.reservationId == null && next.policies.isEmpty() && next.installationClaims.isEmpty() &&
                next.installationAdmission == null && next.serialCustody == null)
            return
        }
        val beforeEnrollment = LocalCompanyAuthorityWireV1.encodeEnrollment(previous.enrollment)
        val afterEnrollment = LocalCompanyAuthorityWireV1.encodeEnrollment(next.enrollment)
        try { known(beforeEnrollment.contentEquals(afterEnrollment)) } finally { beforeEnrollment.fill(0); afterEnrollment.fill(0) }
        val a = previous.head; val b = next.head
        known(a.headAuthorityId == b.headAuthorityId && a.headEnrollmentId == b.headEnrollmentId && a.name == b.name &&
            a.login == b.login && a.credentialRevision == b.credentialRevision && a.salt == b.salt && a.verifier == b.verifier)
        known((a.enabled || !b.enabled) && (a.policyEnabled || !b.policyEnabled) && (a.installationEnabled || !b.installationEnabled))
        known(b.accountRevision == a.accountRevision + (if (a.enabled != b.enabled) 1L else 0L))
        known(b.policyRevision == a.policyRevision + (if (a.policyEnabled != b.policyEnabled) 1L else 0L))
        known(b.installationRevision == a.installationRevision + (if (a.installationEnabled != b.installationEnabled) 1L else 0L))
        known(b.throttleBoot >= a.throttleBoot)
        known(next.policies.size in previous.policies.size..previous.policies.size + 1)
        for ((index, old) in previous.policies.withIndex()) {
            val updated = next.policies[index]
            known(old.originalActionId == updated.originalActionId && old.headAccountRevision == updated.headAccountRevision &&
                old.headCredentialRevision == updated.headCredentialRevision && old.purposeRevision == updated.purposeRevision &&
                old.keyRevision == updated.keyRevision && old.policyBody == updated.policyBody && old.delegationBody == updated.delegationBody &&
                updated.phase.ordinal in old.phase.ordinal..old.phase.ordinal + 1 &&
                (old.signedPolicy == null || old.signedPolicy == updated.signedPolicy))
        }
        if (next.policies.size > previous.policies.size) known(next.policies.last().phase == LocalCompanyPolicyPhaseV1.PREPARED &&
            previous.policies.all { it.phase == LocalCompanyPolicyPhaseV1.ISSUED } && previous.installationAdmission == null)
        known(next.installationClaims.size in previous.installationClaims.size..previous.installationClaims.size + 1)
        for ((index, old) in previous.installationClaims.withIndex()) {
            val updated = next.installationClaims[index]
            known(old.issuedAttemptId == updated.issuedAttemptId && old.attemptSequence == updated.attemptSequence &&
                old.originalRequestId == updated.originalRequestId && old.allocationDigest == updated.allocationDigest &&
                old.definitionDigest == updated.definitionDigest && old.approvalFrame == updated.approvalFrame &&
                (old.signedHumanApproval == null || old.signedHumanApproval == updated.signedHumanApproval) &&
                (old.acknowledgementFrame == null || old.acknowledgementFrame == updated.acknowledgementFrame) &&
                (old.signedAcknowledgement == null || old.signedAcknowledgement == updated.signedAcknowledgement) &&
                (old.retirementEvidence == null || old == updated))
        }
        if (next.installationClaims.size > previous.installationClaims.size) {
            val added = next.installationClaims.last()
            val admission = previous.installationAdmission ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            known(previous.installationClaims.all { it.retirementEvidence != null } && added.signedHumanApproval == null &&
                added.acknowledgementFrame == null && added.signedAcknowledgement == null && added.retirementEvidence == null &&
                added.originalRequestId == admission.originalRequestId && added.allocationDigest == admission.allocationDigest &&
                added.definitionDigest == admission.definitionDigest && added.attemptSequence == admission.nextAttemptSequence &&
                next.installationAdmission == null)
        } else {
            // A reservation cannot be dropped/replaced to reclaim space or manufacture a retry.
            previous.installationAdmission?.let { known(it == next.installationAdmission) }
            if (previous.installationAdmission == null && next.installationAdmission != null) {
                known(previous.installationClaims.all { it.retirementEvidence != null })
            }
        }
    }

    private fun validateCompanyState(state: LocalCompanyAuthorityStateV1, original: LocalCompanyAuthorityBootstrapV1,
        keys: LocalProvisionerNativeKeys) {
        validateState(state.original, keys, original.original)
        validateCompanyExtensions(state, original, keys)
    }

    private fun validateCompanyExtensions(state: LocalCompanyAuthorityStateV1, original: LocalCompanyAuthorityBootstrapV1,
        keys: LocalProvisionerNativeKeys) {
        val head = state.head
        known(head.headAuthorityId == original.headAuthorityId && head.headEnrollmentId == original.headEnrollmentId &&
            head.name == original.headName && head.login == original.headLogin && head.throttleBoot <= environment.bootCount)
        state.serialCustody?.let { requireOriginalSerialCustodyOrigin(it, state, original, keys) }
        val expectedEnrollment = LocalCompanyAuthorityWireV1.encodeEnrollmentBody(keys.headEnrollmentBody())
        val retainedEnrollment = LocalCompanyAuthorityWireV1.encodeEnrollmentBody(state.enrollment.body)
        val rootPoint = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        // The enrollment source already checks the original head points. This additional
        // policy point is consumed only by retained policy records, which founding lacks.
        val policyPoint = if (state.policies.isEmpty()) null else keys.headPoint(LocalCompanyHeadKeyRoleV1.POLICY)
        val signature = state.enrollment.signature.copyBytes()
        try {
            known(expectedEnrollment.contentEquals(retainedEnrollment) &&
                AndroidP256Primitives.verify(rootPoint, retainedEnrollment, signature))
            var previousPolicy: LocalCompanyPolicyRecordV1? = null
            for ((index, record) in state.policies.withIndex()) {
                known(record.headAccountRevision <= head.accountRevision && record.purposeRevision <= head.policyRevision)
                val policy = record.policy(); val delegation = record.delegation()
                requireOriginalCompanyPolicyV1(policy, delegation, state.original, original,
                    AndroidP256Primitives.rootFingerprint(keys.rootBody()))
                val policyBytes = LocalCompanyPolicyWireV1.encodePolicyBody(policy)
                val delegationBytes = LocalCompanyPolicyWireV1.encodeDelegationBody(delegation)
                try {
                    known(record.policyBody.same(policyBytes) && record.delegationBody.same(delegationBytes) &&
                        policy.revision.value == index.toLong() + 1L && delegation.revision == policy.revision &&
                        delegation.subject.accountRevision.value == record.headAccountRevision)
                    previousPolicy?.let { previous ->
                        val precedingPolicy = previous.policy(); val precedingDelegation = previous.delegation()
                        known(previous.phase == LocalCompanyPolicyPhaseV1.ISSUED && policy.policyId == precedingPolicy.policyId &&
                            delegation.delegationId == precedingDelegation.delegationId &&
                            policy.predecessor?.publicationId == precedingPolicy.publicationId &&
                            policy.predecessor?.bodyDigest == companyDigest(previous.policyBody) &&
                            delegation.predecessor?.publicationId == precedingDelegation.publicationId &&
                            delegation.predecessor?.bodyDigest == companyDigest(previous.delegationBody))
                    }
                    record.signedPolicy?.let {
                        val result = record.result(); val signedDelegation = result.delegations.documents.single()
                        val rb = LocalCompanyPolicyWireV1.encodePolicyBody(result.body)
                        val db = LocalCompanyPolicyWireV1.encodeDelegationBody(signedDelegation.body)
                        val wire = LocalCompanyPolicyWireV1.encodeSignedPolicy(result)
                        val ps = result.signature.copyBytes(); val ds = signedDelegation.signature.copyBytes()
                        try {
                            known(policyBytes.contentEquals(rb) && delegationBytes.contentEquals(db) && it.same(wire) &&
                                AndroidP256Primitives.verify(rootPoint, db, ds) &&
                                AndroidP256Primitives.verify(requireNotNull(policyPoint), rb, ps))
                        } finally { rb.fill(0); db.fill(0); wire.fill(0); ps.fill(0); ds.fill(0) }
                    }
                } finally { policyBytes.fill(0); delegationBytes.fill(0) }
                previousPolicy = record
            }
            val first = state.installationClaims.firstOrNull()
            for (claim in state.installationClaims) {
                known(first != null && claim.originalRequestId == first.originalRequestId &&
                    claim.allocationDigest == first.allocationDigest && claim.definitionDigest == first.definitionDigest)
                // P4 owns the complete D7. The native issuer helper re-reads that original and
                // uses P3's full-snapshot decoders before consuming/returning a proof or ACK.
                // This authenticated issuer record alone is deliberately NOT installation admission.
            }
        } finally { expectedEnrollment.fill(0); retainedEnrollment.fill(0); rootPoint.fill(0); policyPoint?.fill(0); signature.fill(0) }
    }

    /** Actual founding/root/head/issuer sources, never company-ID/P2 self-attestation. */
    private fun requireOriginalSerialCustodyOrigin(custody: NativeCompanySerialAuthorityCustodyV1,
        state: LocalCompanyAuthorityStateV1, original: LocalCompanyAuthorityBootstrapV1, keys: LocalProvisionerNativeKeys) {
        val origin = custody.authority.origin
        val allocation = state.original.allocations.singleOrNull()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val coordinates = allocation.body.coordinates
        known(origin.company == CompanyScope(original.original.organizationId, original.original.companyId) &&
            origin.foundingActionId == original.original.actionId && origin.rootId == original.original.rootId &&
            origin.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId &&
            origin.rootDocumentDigest == PayloadDigest(AndroidP256Primitives.documentDigest(state.original.root)) &&
            origin.rootFingerprint == AndroidP256Primitives.rootFingerprint(keys.rootBody()) &&
            origin.headAuthorityId == original.headAuthorityId && origin.headEnrollmentId == original.headEnrollmentId &&
            origin.originalIssuerId == original.original.issuerId &&
            origin.originalIssuerKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId &&
            origin.originalIssuerDelegationDigest == PayloadDigest(AndroidP256Primitives.documentDigest(state.original.delegation)) &&
            origin.originalScope == coordinates.scope && origin.originalAllocationId == coordinates.allocationId)
        // The target's complete P2 digest is separately re-read by its same native
        // history owner before preparation/review/signing/trust/authority readback.
        val reserved = original.allIds() + listOf(coordinates.allocationId, coordinates.scope.branchId, coordinates.installationId,
            coordinates.assignmentId, coordinates.deviceId, coordinates.storageInstanceId, coordinates.storageKeyId,
            coordinates.pepperKeyId, coordinates.targetSigningKeyId, coordinates.firstActorId)
        val own = listOf(custody.authority.authorityId, custody.authority.publicationId,
            custody.originalAuthorityActionId, custody.authoritySigningKeyId)
        known(own.distinct().size == own.size && own.none { it in reserved })
    }

    private fun companyDigest(bytes: SelfOwnedOctetsV1): PayloadDigest {
        val copy = bytes.copyBytes()
        return try { PayloadDigest(AndroidP256Primitives.hashHex(copy)) } finally { copy.fill(0) }
    }

    private fun validateOriginalTransition(previous: LocalProvisionerRegistryStateV1?, next: LocalProvisionerRegistryStateV1) {
        if (previous == null) {
            known(next.revision == 0L && next.issuanceEnabled && next.nextSerial == 1L && next.allocations.isEmpty())
        } else {
            known(next.revision == previous.revision + 1 && sameSigned(next.root, previous.root) &&
                sameSigned(next.delegation, previous.delegation) && (previous.issuanceEnabled || !next.issuanceEnabled))
            val beforeAccount = previous.installers.single(); val afterAccount = next.installers.single()
            known(beforeAccount.installerId == afterAccount.installerId && beforeAccount.name == afterAccount.name &&
                beforeAccount.login == afterAccount.login && beforeAccount.credentialRevision == afterAccount.credentialRevision &&
                beforeAccount.delegationRevision == afterAccount.delegationRevision && beforeAccount.salt == afterAccount.salt &&
                beforeAccount.verifier == afterAccount.verifier && (beforeAccount.enabled || !afterAccount.enabled))
            known(next.allocations.size >= previous.allocations.size && next.nextSerial >= previous.nextSerial)
            for (old in previous.allocations) {
                val updated = next.allocations.single { it.body.coordinates.allocationId == old.body.coordinates.allocationId }
                known(samePreparation(old.body, updated.body) && updated.phase.ordinal >= old.phase.ordinal &&
                    updated.phase.ordinal <= old.phase.ordinal + 1 &&
                    (old.grant == null || updated.grant != null && sameSigned(old.grant, updated.grant)))
            }
        }
    }

    private fun validateState(state: LocalProvisionerRegistryStateV1, keys: LocalProvisionerNativeKeys,
        original: SelfOwnedBootstrapV1) {
        // V1 exposes founding enrollment, not a 64-account administration/import API.
        known(state.installers.size == 1 && state.allocations.size <= 1)
        val rootPoint = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        val issuerPoint = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)
        try {
            val expectedRoot = SelfOwnedInstallationWireV1.encodeRoot(keys.rootBody())
            val actualRoot = state.root.body()
            try { known(expectedRoot.contentEquals(actualRoot)) } finally { expectedRoot.fill(0); actualRoot.fill(0) }
            known(AndroidP256Primitives.verifyDocument(state.root, rootPoint) &&
                AndroidP256Primitives.verifyDocument(state.delegation, rootPoint))
            val delegationBytes = state.delegation.body()
            val delegated = try { SelfOwnedInstallationWireV1.decodeDelegation(delegationBytes) } finally { delegationBytes.fill(0) }
            val rootDigest = AndroidP256Primitives.documentDigest(state.root)
            val account = state.installers.single()
            known(account.installerId == original.installerId && account.name == original.facts.responsiblePersonName &&
                account.login == original.facts.installerLogin && account.credentialRevision == 0L && account.delegationRevision == 0L &&
                account.throttleBoot <= environment.bootCount &&
                (account.failedAttempts == 0 && account.reservationId == null && account.cooldownMillis == 0L ||
                    account.failedAttempts > 0 && account.cooldownMillis > 0L))
            known(delegated.rootId == original.rootId && delegated.rootDocumentDigest == rootDigest &&
                delegated.organizationId == original.organizationId && delegated.companyId == original.companyId &&
                delegated.issuerId == original.issuerId && delegated.issuerKeyId == original.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId &&
                delegated.issuerPublicKey.same(issuerPoint) && delegated.installerId == account.installerId &&
                delegated.installerRevision == account.credentialRevision && delegated.delegationRevision == account.delegationRevision &&
                delegated.protection == original.facts.protection)
            known(state.nextSerial == state.allocations.size.toLong() + 1L)
            val provisionerIds = listOf(original.actionId, original.rootId, original.issuerId, original.installerId) + original.keys.map { it.keyId } +
                (companyBootstrap?.allIds() ?: emptyList())
            for (allocation in state.allocations) {
                // The profile permits at most one allocation. Empty founding scans do not
                // consume this digest; every retained allocation still compares it exactly.
                val delegationDigest = AndroidP256Primitives.documentDigest(state.delegation)
                val body = allocation.body; val coordinates = body.coordinates
                known(body.rootId == original.rootId && body.rootDocumentDigest == rootDigest && body.issuerId == original.issuerId &&
                    body.delegationDigest == delegationDigest && body.installerId == account.installerId &&
                    body.installerRevision == account.credentialRevision && body.delegationRevision == account.delegationRevision &&
                    body.serial == 1L && body.target.same(original.target) && body.protection == original.facts.protection &&
                    coordinates.scope.organizationId == original.organizationId && coordinates.scope.companyId == original.companyId)
                known(listOf(coordinates.allocationId, coordinates.scope.branchId, coordinates.installationId, coordinates.assignmentId,
                    coordinates.deviceId, coordinates.storageInstanceId, coordinates.storageKeyId, coordinates.pepperKeyId,
                    coordinates.targetSigningKeyId, coordinates.firstActorId).none { it in provisionerIds })
                allocation.grant?.let { grant ->
                    val expected = SelfOwnedInstallationWireV1.encodePreparation(body); val actual = grant.body()
                    try { known(expected.contentEquals(actual)) } finally { expected.fill(0); actual.fill(0) }
                    known(AndroidP256Primitives.verifyDocument(grant, issuerPoint))
                }
            }
        } finally { rootPoint.fill(0); issuerPoint.fill(0) }
    }

    /** Unknown failures preserve both files. In particular, no finally block unlinks a pending file. */
    private fun replaceFile(name: String, bytes: ByteArray) {
        assertContinuity()
        known(name in snapshots && bytes.size in 1..maximum(name))
        val temporaryName = "$name.pending"
        val temporary = File(directory, temporaryName)
        val descriptor = openDescriptor(temporary,
            OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_CREAT or OsConstants.O_EXCL, FILE_MODE)
        val created = stamp(Os.fstat(descriptor.fd))
        requireFile(created, maximum(name), allowEmpty = true)
        known(created.sameFile(stamp(Os.lstat(temporary.path))) && created.size == 0L)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(descriptor.fd, bytes, offset, bytes.size - offset)
                known(count > 0 && count <= bytes.size - offset)
                offset += count
            }
            Os.fsync(descriptor.fd)
            val written = stamp(Os.fstat(descriptor.fd))
            requireFile(written, maximum(name), allowEmpty = false)
            known(written.sameIdentity(created) && written.size == bytes.size.toLong())
            Os.lseek(descriptor.fd, 0, OsConstants.SEEK_SET)
            val readback = readExact(descriptor.fd, bytes.size)
            try { known(readback.contentEquals(bytes)) } finally { readback.fill(0) }
            known(written.sameFile(stamp(Os.fstat(descriptor.fd))) && written.sameFile(stamp(Os.lstat(temporary.path))))
            assertBase(); checkNames(temporaryName); checkSnapshotFiles()
            closeTransient(descriptor)
            Os.rename(temporary.path, File(directory, name).path)
            Os.fsync(requireNotNull(directoryDescriptor).fd)
            val actual = readSnapshot(name) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            try {
                known(written.sameIdentity(actual.stamp) && written.size == actual.stamp.size &&
                    written.modified == actual.stamp.modified && actual.bytes.contentEquals(bytes))
                // Cache adoption is only this exact known own force/rename/readback, never an
                // externally learned rewrite. ctime can change on rename; compare below by
                // inode/UID/mode/size and exact bytes instead of manufacturing a new identity.
                snapshots[name]?.clear()
                snapshots[name] = actual
            } catch (failure: Throwable) { actual.clear(); throw failure }
            checkNames(); assertBase()
        } finally {
            if (!descriptor.closed) closeTransient(descriptor)
        }
    }

    private fun readSnapshot(name: String): Snapshot? {
        val path = File(directory, name)
        val seen = lstatOrNull(path) ?: return null
        val expected = stamp(seen)
        requireFile(expected, maximum(name), allowEmpty = false)
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var bytes: ByteArray? = null
        try {
            known(expected.sameFile(stamp(Os.fstat(descriptor.fd))))
            bytes = readExact(descriptor.fd, expected.size.toInt())
            known(expected.sameFile(stamp(Os.fstat(descriptor.fd))) && expected.sameFile(stamp(Os.lstat(path.path))))
            return Snapshot(expected, requireNotNull(bytes)).also { bytes = null }
        } finally { bytes?.fill(0); closeTransient(descriptor) }
    }

    private fun readExact(fd: FileDescriptor, size: Int): ByteArray {
        val bytes = ByteArray(size)
        val tail = ByteArray(1)
        try {
            var offset = 0
            while (offset < size) {
                val count = Os.read(fd, bytes, offset, size - offset)
                known(count > 0 && count <= size - offset)
                offset += count
            }
            known(Os.read(fd, tail, 0, 1) == 0)
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
        finally { tail.fill(0) }
    }

    private fun openDirectory(path: File, expected: Stamp): OwnedDescriptor {
        // O_DIRECTORY is not a public Android OsConstants field. Do not guess an ABI value.
        // Keep the open nonblocking/no-follow and validate the actual directory before use.
        known(OsConstants.S_ISDIR(expected.mode))
        val opened = openDescriptor(path,
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        known(expected.sameDirectory(stamp(Os.fstat(opened.fd))) && expected.sameDirectory(stamp(Os.lstat(path.path))))
        return opened
    }

    private fun openDescriptor(path: File, flags: Int, mode: Int): OwnedDescriptor {
        // Register the holder before acquiring any native resource. Adoption afterwards is
        // only a field assignment, so allocation failure cannot hide an acquired descriptor.
        val owned = OwnedDescriptor()
        descriptors.add(owned)
        owned.native = androidOwnedOpenDescriptor(path, flags, mode, work)
        known(AndroidNativeFiles.closeOnExec(owned.fd))
        return owned
    }

    private fun closeTransient(value: OwnedDescriptor) { value.closeOnce(); descriptors.remove(value) }

    /** Called only inside watched discard/close; CLOSED is idempotent, unknown CLOSE_STARTED is not. */
    override fun closeNative() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED) {
            work.nativeClosureUnknown()
            selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        phase = Phase.CLOSE_STARTED
        try {
            lock?.let { held ->
                known(held.isValid)
                held.release()
                known(!held.isValid)
                lock = null
            }
            for (descriptor in descriptors.toList().asReversed()) closeTransient(descriptor)
            known(lockChannel?.isOpen != true)
            snapshots.values.forEach { it?.clear() }
            snapshots.clear(); bootstrap = null; companyBootstrap = null; verifiedBusinessPublications = null
            phase = Phase.CLOSED
        } catch (failure: Throwable) {
            // No retry against a possibly reused fd, no release of process custody.
            work.nativeClosureUnknown()
            throw failure
        }
    }

    private inner class OwnedDescriptor {
        var native: FileDescriptor? = null
        val fd: FileDescriptor get() = requireNotNull(native)
        var streamOwner: FileOutputStream? = null
        private var closeStarted = false
        var closed = false
            private set
        fun closeOnce() {
            if (closed) return
            if (closeStarted) {
                work.nativeClosureUnknown()
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            }
            closeStarted = true
            val actual = native
            if (actual == null) { closed = true; return }
            try {
                // Android FileOutputStream(fd) borrows fd; close its borrower/channel first.
                // The closeStarted guard forbids retry after either close stage is uncertain.
                streamOwner?.close()
                AndroidNativeFiles.close(actual)
                closed = true
            } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }

    private class Snapshot(val stamp: Stamp, val bytes: ByteArray) { fun clear() { bytes.fill(0) } }

    private class VerifiedBusinessPublications(
        val snapshot: Snapshot,
        val original: LocalCompanyAuthorityBootstrapV1,
        val keys: LocalProvisionerNativeKeys,
        val state: NativeCompanyBusinessPublicationStateV1,
    )
    private data class Stamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int,
        val links: Long, val size: Long, val modified: Long, val changed: Long) {
        fun sameIdentity(other: Stamp): Boolean = device == other.device && inode == other.inode && uid == other.uid &&
            gid == other.gid && mode == other.mode && links == other.links
        fun sameDirectory(other: Stamp): Boolean = device == other.device && inode == other.inode && uid == other.uid &&
            gid == other.gid && mode == other.mode && OsConstants.S_ISDIR(other.mode)
        fun sameFile(other: Stamp): Boolean = sameIdentity(other) && size == other.size && modified == other.modified && changed == other.changed
    }

    private fun stamp(value: StructStat): Stamp = Stamp(value.st_dev, value.st_ino, value.st_uid, value.st_gid,
        value.st_mode, value.st_nlink, value.st_size, value.st_mtime, value.st_ctime)
    private fun requireDirectory(value: Stamp, owned: Boolean) {
        known(OsConstants.S_ISDIR(value.mode) && value.mode and 2 == 0)
        if (owned) known(value.uid == environment.uid && value.mode and 4095 == DIRECTORY_MODE)
    }
    private fun requireFile(value: Stamp, maximum: Int, allowEmpty: Boolean) {
        known(OsConstants.S_ISREG(value.mode) && value.uid == environment.uid && value.mode and 4095 == FILE_MODE &&
            value.links == 1L && value.size in (if (allowEmpty) 0L else 1L)..maximum.toLong())
    }
    private fun lstatOrNull(path: File): StructStat? = try { Os.lstat(path.path) } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw failure
    }
    private fun maximum(name: String): Int = when (name) {
        BOOTSTRAP -> SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES
        REGISTRY -> SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
        BUSINESS_PUBLICATIONS -> CompanyBusinessPublicationProfileV1.MAXIMUM_STATE_BYTES
        EMPLOYEE_ENROLLMENTS -> LocalEmployeeEnrollmentProfileV1.MAXIMUM_STATE_BYTES
        EMPLOYEE_POLICY_ACTIVATIONS -> LocalEmployeePolicyActivationProfileV1.MAXIMUM_STATE_BYTES
        DEVICE_PAIRINGS -> LocalDevicePairingProfileV1.MAXIMUM_STATE_BYTES
        DEVICE_ADMISSIONS -> SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
        else -> error("Unknown provisioner file role")
    }
    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

    companion object {
        private const val BOOTSTRAP = "bootstrap.v1"
        private const val REGISTRY = "registry.v1"
        private const val BUSINESS_PUBLICATIONS = "business-publications.v1"
        private const val EMPLOYEE_ENROLLMENTS = "employee-enrollments.v1"
        private const val EMPLOYEE_POLICY_ACTIVATIONS = "employee-policy-activations.v1"
        private const val DEVICE_PAIRINGS = "device-pairings.v1"
        private const val DEVICE_ADMISSIONS = "device-admissions.v1"
        private const val LOCK = "lock.v1"
        private val REGISTRY_NAMES = setOf(LOCK, BOOTSTRAP, REGISTRY, BUSINESS_PUBLICATIONS,
            EMPLOYEE_ENROLLMENTS, EMPLOYEE_POLICY_ACTIVATIONS, DEVICE_PAIRINGS, DEVICE_ADMISSIONS)
        private const val DIRECTORY_MODE = 448 // 0700
        private const val FILE_MODE = 384 // 0600

        fun sameFacts(left: SelfOwnedCompanyFactsV1, right: SelfOwnedCompanyFactsV1): Boolean =
            sameProvisionerCompanyFacts(left, right)

        private fun sameSigned(left: SelfOwnedSignedDocumentV1, right: SelfOwnedSignedDocumentV1): Boolean {
            val l = left.copyWireBytes(); val r = right.copyWireBytes()
            return try { l.contentEquals(r) } finally { l.fill(0); r.fill(0) }
        }
        private fun samePreparation(left: SelfOwnedPreparationBodyV1, right: SelfOwnedPreparationBodyV1): Boolean {
            val l = SelfOwnedInstallationWireV1.encodePreparation(left); val r = SelfOwnedInstallationWireV1.encodePreparation(right)
            return try { l.contentEquals(r) } finally { l.fill(0); r.fill(0) }
        }
    }
}
