package com.ukkera.pos.app.gateway

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.security.AndroidNativeApplicationMetadata
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import com.ukkera.pos.security.AndroidNativeKeySecurity
import com.ukkera.pos.security.AndroidNativeStorageVolume
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidEmployeeClientCustody
import com.ukkera.pos.sales.store.LocalPhysicalExternalCustody
import com.ukkera.pos.sales.store.LocalPhysicalExternalDenialRegistration
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.NativeEmployeeJournalAllocation
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Retained Android CLIENT journal custody, separate from the branch-master owner. Closing a
 * journal view cannot close Room, change the desired master role or stop the branch service.
 *
 * Acquisition is confined to actual original/employee/paired-device service/native bindings. Original remains
 * first-actor-only; an employee gets only a separately authorized intent namespace. First
 * enrollment and existing-only reopening are explicit different actions.
 * No public scalar/path/key constructor, missing-journal recreation or restored-token login.
 * Additional-device enrollment consumes its exact protected approved target, not a cohost binding.
 * Its distinct allocation-metadata journal admits actors only through actual fresh Master Current.
 */
public class AndroidLocalClientJournalOwner private constructor(
    private val file: AndroidClientJournalFile,
) {
    public val credentialJournal: LocalClientCredentialJournal? =
        if (file.kind == ClientJournalKind.CREDENTIALS)
            AndroidEncryptedClientCredentialJournal.fromExisting(file) else null
    public val intentJournal: LocalClientIntentJournal? =
        if (file.kind == ClientJournalKind.INTENTS)
            AndroidEncryptedClientIntentJournal.fromExisting(file) else null
    internal val actorAllocationJournal: PairedDeviceActorAllocationJournal? =
        if (file.kind == ClientJournalKind.ACTOR_ALLOCATIONS)
            PairedDeviceActorAllocationJournal.fromExisting(file, checkNotNull(file.pairedDeviceAllocationCustody)) else null

    /** Shared paired-ledger/actor facet of THIS successful owner (no constructor of its own). */
    internal val paired: PairedClientJournalOwner = object : PairedClientJournalOwner {
        override val intentJournal: LocalClientIntentJournal? get() = this@AndroidLocalClientJournalOwner.intentJournal
        override val actorAllocationJournal: PairedDeviceActorAllocationJournal?
            get() = this@AndroidLocalClientJournalOwner.actorAllocationJournal
        override fun isCurrent(): Boolean = this@AndroidLocalClientJournalOwner.isCurrent()
        override suspend fun close() = this@AndroidLocalClientJournalOwner.close()
    }

    // This private constructor is reached only AFTER the unchanged full typed validator,
    // native head admission and exact binding/continuity checks in the factories below.
    // A real custody or a lower file/head API cannot construct this successful owner.
    private class PhysicalState(val denied: Boolean, val loans: List<PhysicalLoan>,
        val observers: List<PhysicalObserver>)
    private val physical = AtomicReference(PhysicalState(false, emptyList(), emptyList()))
    private val physicalDrain = CompletableDeferred<Unit>()
    private val physicalDenialDelivered = CompletableDeferred<Unit>()
    init {
        file.attachSuccessfulOwner(this)
        actorAllocationJournal?.attachSuccessfulOwner(paired)
    }

    /** Final memory-only denial; safe for the registered transaction participant read. */
    /** Shared work fences this owner's physical loans through this internal facet. */
    internal val physicalFacet: ClientJournalPhysicalOwner = object : ClientJournalPhysicalOwner {
        override fun denyPhysical() = this@AndroidLocalClientJournalOwner.denyPhysical()
        override val physicalDenied: Boolean get() = this@AndroidLocalClientJournalOwner.physicalDenied
        override val physicalLoansDrained: Boolean get() = this@AndroidLocalClientJournalOwner.physicalLoansDrained
        override suspend fun awaitPhysicalDrain() = this@AndroidLocalClientJournalOwner.awaitPhysicalDrain()
    }
    internal val physicalDenied: Boolean get() = physical.get().denied
    internal val physicalLoansDrained: Boolean get() = physicalDenialDelivered.isCompleted &&
        physical.get().let { it.denied && it.loans.isEmpty() }

    /** Immutable reference/scalar join to THIS successful owner's private employee binding. */
    internal fun ownsEmployeePhysical(custody: AndroidEmployeeClientCustody): Boolean =
        file.employeePhysicalCustody === custody && credentialJournal == null &&
            intentJournal?.owner == LocalClientIdentity(custody.scope, custody.actorId, custody.deviceId) &&
            file.journalId == custody.physicalEnrollmentHistory.journalId && file.keyId == custody.physicalEnrollmentHistory.journalKeyId

    /** No native IO or grant callback. Actual currentness and FULL typed validation follow
     * outside Room under this same retained native worker before any backend IO is attempted. */
    internal fun captureEmployeePhysical(custody: AndroidEmployeeClientCustody): LocalPhysicalExternalCustody? {
        if (!ownsEmployeePhysical(custody)) return null
        val loan = PhysicalLoan()
        while (true) {
            val selected = physical.get()
            if (selected.denied) return null
            if (physical.compareAndSet(selected, PhysicalState(false, selected.loans + loan, selected.observers))) return loan
        }
    }

    internal suspend fun recheckEmployeePhysical(custody: AndroidEmployeeClientCustody): Boolean {
        if (physicalDenied || !ownsEmployeePhysical(custody)) return false
        val checked = file.owned {
            object : ClientJournalTask<Unit> {
                override fun execute() {
                    check(!physicalDenied)
                    file.requireEmployeePhysicalCustody(custody)
                    // Do not substitute structural decoding, a native digest/head result,
                    // claimed-open allocation, or a callback for this complete typed codec.
                    AndroidEncryptedClientIntentJournal.validateExisting(file)
                    file.requireEmployeePhysicalCustody(custody)
                    check(!physicalDenied && ownsEmployeePhysical(custody) && custody.isCurrent())
                }
                override fun discard(value: Unit) = Unit
                override fun clearInputs() = Unit
            }
        }
        // Busy native work is a refusal, not owner retirement. Real native/validation failure
        // already synchronously denies this owner through the retained work object below.
        return checked is ClientJournalOutcome.Value && !physicalDenied && ownsEmployeePhysical(custody)
    }

    /** One CAS atom prevents retirement/capture races. Observer failure never prevents other
     * end-only denials or loan drainage; no reset/replacement or positive verifier is exposed. */
    internal fun denyPhysical() {
        val selected = denyPhysicalState() ?: return
        try {
            selected.loans.forEach { it.deny() }
            selected.observers.forEach { it.deny() }
        } finally {
            // A racing second retire/close cannot report drainage before the first denial
            // has delivered every end-only core-atom notification.
            physicalDenialDelivered.complete(Unit)
            if (physical.get().loans.isEmpty()) physicalDrain.complete(Unit)
        }
    }

    private fun denyPhysicalState(): PhysicalState? {
        while (true) {
            val selected = physical.get()
            if (selected.denied) return null
            if (physical.compareAndSet(selected, PhysicalState(true, selected.loans, selected.observers))) return selected
        }
    }

    internal suspend fun awaitPhysicalDrain() {
        denyPhysical()
        if (physical.get().loans.isEmpty()) physicalDrain.complete(Unit)
        physicalDenialDelivered.await()
        physicalDrain.await()
    }

    private fun releasePhysicalLoan(loan: PhysicalLoan) {
        while (true) {
            val selected = physical.get()
            if (selected.loans.none { it === loan }) return
            val next = PhysicalState(selected.denied, selected.loans.filterNot { it === loan }, selected.observers)
            if (physical.compareAndSet(selected, next)) {
                return
            }
        }
    }

    private fun observePhysicalDenial(onDenial: () -> Unit): PhysicalObserver {
        val observer = PhysicalObserver(onDenial)
        while (true) {
            val selected = physical.get()
            if (selected.denied) { observer.deny(); return observer }
            if (physical.compareAndSet(selected, PhysicalState(false, selected.loans, selected.observers + observer))) return observer
        }
    }

    private fun releasePhysicalObserver(observer: PhysicalObserver) {
        while (true) {
            val selected = physical.get()
            if (selected.observers.none { it === observer } || physical.compareAndSet(selected,
                    PhysicalState(selected.denied, selected.loans, selected.observers.filterNot { it === observer }))) return
        }
    }

    private inner class PhysicalObserver(private val onDenial: () -> Unit) : LocalPhysicalExternalDenialRegistration {
        private val finished = AtomicReference(false)
        fun deny() {
            if (finished.compareAndSet(false, true)) try { onDenial() } catch (_: Throwable) { /* Denial cannot grant or stop other observers. */ }
        }
        override fun release() { finished.set(true); releasePhysicalObserver(this) }
    }

    private enum class PhysicalLoanState { OWNED, RELEASING, RELEASED, UNCERTAIN }
    private inner class PhysicalLoan : LocalPhysicalExternalCustody {
        private val state = AtomicReference(PhysicalLoanState.OWNED)
        private val invocationDenied = AtomicReference(false)
        override val denied: Boolean get() = lifetimeDenied || invocationDenied.get() || state.get() != PhysicalLoanState.OWNED
        override val lifetimeDenied: Boolean get() = physicalDenied
        override fun deny() { invocationDenied.set(true) }
        override fun observeLifetimeDenial(onDenial: () -> Unit): LocalPhysicalExternalDenialRegistration = observePhysicalDenial(onDenial)
        override fun release(): Boolean {
            if (!state.compareAndSet(PhysicalLoanState.OWNED, PhysicalLoanState.RELEASING)) return state.get() == PhysicalLoanState.RELEASED
            val complete = try { releasePhysicalLoan(this); true } catch (_: Throwable) { false }
            state.set(if (complete) PhysicalLoanState.RELEASED else PhysicalLoanState.UNCERTAIN)
            if (!complete) denyPhysical()
            else if (physical.get().let { it.denied && it.loans.isEmpty() }) {
                // Cleanup publication already succeeded; a memory-only wake cannot turn it
                // into a failed release after the actual loan has left the owner census.
                try { physicalDrain.complete(Unit) } catch (_: Throwable) { /* Close also resamples the empty census. */ }
            }
            return complete
        }
    }

    /** Success means actual native close AFTER all physical loans drain, not cancellation. */
    public suspend fun close() { file.closeOwner() }

    /** Exact successful worker ownership and synchronous terminal denial. Every actual
     * read/write still performs full protected file/key/head continuity inside that worker. */
    public fun isCurrent(): Boolean = !physicalDenied && file.isCurrentOwner(this)

    /** Read-only proof for the explicit between-journals continuation, never an empty fallback. */
    internal suspend fun verifyUnusedOriginalCredentials(): Boolean = file.owned {
        object : ClientJournalTask<Unit> {
            override fun execute() { file.requireUnusedOriginalCredentials() }
            override fun discard(value: Unit) = Unit
            override fun clearInputs() = Unit
        }
    } is ClientJournalOutcome.Value

    override fun toString(): String = "AndroidLocalClientJournalOwnerV1([REDACTED])"

    internal companion object {
        /**
         * Coordinates come only from the actual original installation. Existing-only opens
         * never create keys/files; explicit enrollment requires its fully admitted pristine C1.
         * Unknown close retains the process lease. No journal is delivered before native head,
         * full manifest and complete archive validation.
         */
        suspend fun acquireOriginal(
            application: Application,
            binding: AndroidOriginalLocalClient,
            kind: ClientJournalKind,
            enroll: Boolean,
            recoverWrites: Boolean = false,
        ): ClientJournalOutcome<AndroidLocalClientJournalOwner> {
            check(!enroll || !recoverWrites)
            if (kind == ClientJournalKind.ACTOR_ALLOCATIONS) return ClientJournalOutcome.Unavailable
            if (!binding.belongsTo(application) || (enroll && !binding.permitsFirstEnrollment()))
                return ClientJournalOutcome.Unavailable
            val c = binding.material.coordinates
            val deviceOwner = LocalClientDeviceIdentity(c.scope, c.deviceId)
            val actorId = if (kind == ClientJournalKind.INTENTS) c.firstActorId else null
            val journalId = originalClientJournalId(binding, kind, "journal")
            val keyId = originalClientJournalId(binding, kind, "key")
            val protection = binding.material.protection
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId,
                deviceOwner.scope.branchId, deviceOwner.deviceId, journalId, keyId) + listOfNotNull(actorId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId)
                return ClientJournalOutcome.Unavailable
            val lease = ClientJournalProcessLease.acquire(journalId)
                ?: return ClientJournalOutcome.Unavailable
            val work = try { ClientJournalWork(lease, AndroidClientJournalProcess()) } catch (_: Throwable) {
                lease.releaseUnused(); return ClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, actorId, journalId,
                keyId, protection, work, AndroidClientJournalBinding.Original(binding)) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return ClientJournalOutcome.Unavailable
            }
            // Retain partial acquisition BEFORE the first native open. An unknown close keeps it.
            lease.retain(file, work)
            var delivered = false
            try {
                val result = work.run {
                    object : ClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            when (file.kind) {
                                ClientJournalKind.CREDENTIALS ->
                                    AndroidEncryptedClientCredentialJournal.validateExisting(file)
                                ClientJournalKind.INTENTS ->
                                    AndroidEncryptedClientIntentJournal.validateExisting(file)
                                ClientJournalKind.ACTOR_ALLOCATIONS -> error("client.original.kind")
                            }
                            file.admitNativeHead(enroll)
                            file.assertContinuity()
                            check(binding.isCurrent() && (!enroll || binding.permitsFirstEnrollment()))
                            return AndroidLocalClientJournalOwner(file)
                        }
                        override fun discard(value: AndroidLocalClientJournalOwner) { file.closeNative() }
                        override fun clearInputs() = Unit
                    }
                }
                delivered = result is ClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }

        /** A real consumed employee and exact STARTED/open allocation, never another original
         * firstActor, arbitrary actor constructor or second device credential owner. */
        suspend fun acquireEmployee(application: Application, custody: AndroidEmployeeClientCustody,
            allocation: NativeEmployeeJournalAllocation): ClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !allocation.belongsTo(custody.native)) return ClientJournalOutcome.Unavailable
            val c = custody.material.coordinates
            val deviceOwner = LocalClientDeviceIdentity(custody.scope, custody.deviceId)
            val actorId = custody.actorId; val journalId = allocation.journalId; val keyId = allocation.keyId
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId, deviceOwner.scope.branchId,
                deviceOwner.deviceId, actorId, journalId, keyId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId || actorId == c.firstActorId ||
                c.scope != deviceOwner.scope || c.deviceId != deviceOwner.deviceId) return ClientJournalOutcome.Unavailable
            // The actual native owner claims this ONE open before any client key/file/native
            // operation. Reusing a lost ENROLL attempt never creates a missing namespace.
            allocation.claimOriginalJournalOpen()
            if (!allocation.belongsTo(custody.native) || !allocation.originalOpenClaimed()) return ClientJournalOutcome.Unavailable
            val enroll = allocation.action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE
            val recoverWrites = allocation.action == NativeEmployeeClientJournalAction.RECOVER_PENDING_WRITES
            val lease = ClientJournalProcessLease.acquire(journalId) ?: return ClientJournalOutcome.Unavailable
            val work = try { ClientJournalWork(lease, AndroidClientJournalProcess()) } catch (_: Throwable) {
                lease.releaseUnused(); return ClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, actorId, journalId,
                keyId, custody.material.protection, work, AndroidClientJournalBinding.Employee(custody, allocation)) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return ClientJournalOutcome.Unavailable
            }
            lease.retain(file, work)
            var delivered = false
            try {
                val result = work.run {
                    object : ClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            check(file.kind == ClientJournalKind.INTENTS)
                            AndroidEncryptedClientIntentJournal.validateExisting(file)
                            file.admitNativeHead(enroll)
                            file.assertContinuity()
                            check(custody.isCurrent() && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed())
                            return AndroidLocalClientJournalOwner(file)
                        }
                        override fun discard(value: AndroidLocalClientJournalOwner) { file.closeNative() }
                        override fun clearInputs() = Unit
                    }
                }
                delivered = result is ClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }

        /** Credential-only acquisition from the actual approved additional-device source.
         * The one original claim precedes client key/file IO; existing-only actions never
         * enroll missing aliases/files/heads. Final target handoff belongs to the aggregate. */
        suspend fun acquirePairedDevice(application: Application, custody: PairedDeviceCredentialJournalCustody):
            ClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !custody.isCurrent()) return ClientJournalOutcome.Unavailable
            if (!custody.claimOriginalJournalOpen(application) || !custody.originalOpenClaimed())
                return ClientJournalOutcome.Unavailable
            val deviceOwner = LocalClientDeviceIdentity(custody.scope, custody.deviceId)
            val journalId = custody.journalId; val keyId = custody.keyId
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId,
                deviceOwner.scope.branchId, deviceOwner.deviceId, journalId, keyId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId)
                return ClientJournalOutcome.Unavailable
            val enroll = custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL
            val recoverWrites = custody.action == LocalPairedDeviceCredentialJournalAction.RECOVER
            val lease = ClientJournalProcessLease.acquire(journalId) ?: return ClientJournalOutcome.Unavailable
            val work = try { ClientJournalWork(lease, AndroidClientJournalProcess()) } catch (_: Throwable) {
                lease.releaseUnused(); return ClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, null, journalId, keyId,
                custody.protection, work, AndroidClientJournalBinding.PairedDevice(custody)) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return ClientJournalOutcome.Unavailable
            }
            lease.retain(file, work) // Retain the partial real owner before any native IO.
            var delivered = false
            try {
                val result = work.run {
                    object : ClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            check(file.kind == ClientJournalKind.CREDENTIALS && file.actorId == null)
                            AndroidEncryptedClientCredentialJournal.validateExisting(file)
                            file.admitNativeHead(enroll)
                            file.assertContinuity()
                            check(custody.isCurrent() && custody.originalOpenClaimed())
                            return AndroidLocalClientJournalOwner(file)
                        }
                        override fun discard(value: AndroidLocalClientJournalOwner) { file.closeNative() }
                        override fun clearInputs() = Unit
                    }
                }
                delivered = result is ClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }

        /** The second once-only native pre-transfer claim owns allocation metadata only.
         * ENROLL requires the same actual original fresh device enrollment; other dispositions
         * cannot create a missing ledger, alias, directory, lock, manifest or native head. */
        suspend fun acquirePairedActorAllocations(application: Application,
            custody: PairedDeviceCredentialJournalCustody): ClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !custody.isCurrent() ||
                !custody.claimOriginalActorAllocationJournalOpen(application) ||
                !custody.originalActorAllocationOpenClaimed()) return ClientJournalOutcome.Unavailable
            return acquirePairedBound(application, LocalClientDeviceIdentity(custody.scope, custody.deviceId), null,
                pairedActorAllocationJournalId(custody, "journal"), pairedActorAllocationJournalId(custody, "key"),
                custody.protection, AndroidClientJournalBinding.PairedActorAllocations(custody), custody.action)
        }

        /** Only a fully forced original allocation AND private actual fresh Current capture
         * can consume this exact actor open. No first-cohost/employee borrowing is possible. */
        suspend fun acquirePairedActor(application: Application,
            custody: PairedDeviceActorJournalCustody): ClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !custody.isCurrent() ||
                !custody.claimOriginalJournalOpen(application) || !custody.originalOpenClaimed())
                return ClientJournalOutcome.Unavailable
            val identity = custody.identity
            return acquirePairedBound(application, LocalClientDeviceIdentity(identity.scope, identity.deviceId),
                identity.actorId, custody.journalId, custody.keyId, custody.protection,
                AndroidClientJournalBinding.PairedActor(custody), custody.action)
        }

        private suspend fun acquirePairedBound(application: Application, deviceOwner: LocalClientDeviceIdentity,
            actorId: DomainId?, journalId: DomainId, keyId: DomainId, protection: SelfOwnedKeyProtectionV1,
            binding: AndroidClientJournalBinding, action: LocalPairedDeviceCredentialJournalAction):
            ClientJournalOutcome<AndroidLocalClientJournalOwner> {
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId,
                deviceOwner.scope.branchId, deviceOwner.deviceId, journalId, keyId) + listOfNotNull(actorId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId || !binding.isCurrent())
                return ClientJournalOutcome.Unavailable
            val lease = ClientJournalProcessLease.acquire(journalId) ?: return ClientJournalOutcome.Unavailable
            val work = try { ClientJournalWork(lease, AndroidClientJournalProcess()) } catch (_: Throwable) {
                lease.releaseUnused(); return ClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, actorId, journalId, keyId,
                protection, work, binding) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return ClientJournalOutcome.Unavailable
            }
            lease.retain(file, work)
            val enroll = action == LocalPairedDeviceCredentialJournalAction.ENROLL
            val recoverWrites = action == LocalPairedDeviceCredentialJournalAction.RECOVER
            var delivered = false
            try {
                val result = work.run {
                    object : ClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            when (file.kind) {
                                ClientJournalKind.ACTOR_ALLOCATIONS -> PairedDeviceActorAllocationJournal.validateExisting(file,
                                    checkNotNull(file.pairedDeviceAllocationCustody))
                                ClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.validateExisting(file)
                                ClientJournalKind.CREDENTIALS -> error("client.paired.actor.kind")
                            }
                            file.admitNativeHead(enroll)
                            file.assertContinuity()
                            check(binding.isCurrent())
                            return AndroidLocalClientJournalOwner(file)
                        }
                        override fun discard(value: AndroidLocalClientJournalOwner) { file.closeNative() }
                        override fun clearInputs() = Unit
                    }
                }
                delivered = result is ClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }
    }
}

/**
 * Android configuration of the shared [ClientJournalFileCore]: same-lifetime native continuity with
 * AndroidKeyStore keys/heads and POSIX custody. Callers supply no SecretKey, path, clock or verifier.
 */
internal class AndroidClientJournalFile private constructor(
    deviceOwner: LocalClientDeviceIdentity,
    actorId: DomainId?,
    journalId: DomainId,
    keyId: DomainId,
    protection: SelfOwnedKeyProtectionV1,
    work: ClientJournalWork,
    private val journalBinding: AndroidClientJournalBinding,
    native: AndroidClientJournalNative,
) : ClientJournalFileCore(deviceOwner, actorId, journalId, keyId, protection, work, journalBinding, native) {
    constructor(application: Application, deviceOwner: LocalClientDeviceIdentity, actorId: DomainId?, journalId: DomainId,
        keyId: DomainId, protection: SelfOwnedKeyProtectionV1, work: ClientJournalWork, journalBinding: AndroidClientJournalBinding) :
        this(deviceOwner, actorId, journalId, keyId, protection, work, journalBinding,
            AndroidClientJournalNative(application, work, protection))

    val employeePhysicalCustody: AndroidEmployeeClientCustody? = (journalBinding as? AndroidClientJournalBinding.Employee)?.custody
    val pairedDeviceAllocationCustody: PairedDeviceCredentialJournalCustody? =
        (journalBinding as? AndroidClientJournalBinding.PairedActorAllocations)?.custody

    override fun initialManifest(): ByteArray = when (kind) {
        ClientJournalKind.CREDENTIALS -> if (journalBinding is AndroidClientJournalBinding.PairedDevice)
            AndroidEncryptedClientCredentialJournal.pairedDeviceEmptyManifest(this)
        else AndroidEncryptedClientCredentialJournal.originalEmptyManifest(this)
        ClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.originalEmptyManifest(this)
        ClientJournalKind.ACTOR_ALLOCATIONS ->
            PairedDeviceActorAllocationJournal.originalEmptyManifest(this, checkNotNull(pairedDeviceAllocationCustody))
    }

    fun requireEmployeePhysicalCustody(custody: AndroidEmployeeClientCustody) {
        work.requireActive()
        known(kind == ClientJournalKind.INTENTS && employeePhysicalCustody === custody && journalBinding.isCurrent())
    }

    fun attachSuccessfulOwner(owner: AndroidLocalClientJournalOwner) {
        check(headAdmitted)
        work.requireActive()
        work.attachSuccessfulOwner(owner.physicalFacet)
    }

    fun isCurrentOwner(owner: AndroidLocalClientJournalOwner): Boolean = work.isCurrentOwner(owner.physicalFacet)

    /** Positive proof that the FIRST journal completed enrollment and has never held work. */
    fun requireUnusedOriginalCredentials() {
        val original = (journalBinding as? AndroidClientJournalBinding.Original)?.original ?: error("client.journal.original-required")
        known(kind == ClientJournalKind.CREDENTIALS && original.permitsFirstEnrollment())
        assertContinuity()
        requireInitialHead()
        readManifest(MAX_CLIENT_CREDENTIAL_BYTES).useWiped { actual ->
            AndroidEncryptedClientCredentialJournal.originalEmptyManifest(this).useWiped { initial ->
                known(actual.contentEquals(initial))
            }
        }
        assertContinuity()
        known(original.permitsFirstEnrollment())
    }
}

/**
 * Android POSIX/KeyStore custody seam of the shared [ClientJournalFileCore]: owned no-backup
 * directories, descriptors, exclusive lock, no-replace publication and AndroidKeyStore keys/heads.
 */
internal class AndroidClientJournalNative(
    private val application: Application,
    private val work: ClientJournalWork,
    private val protection: SelfOwnedKeyProtectionV1,
) : ClientJournalNative {
    private lateinit var environment: AndroidClientJournalEnvironment
    private lateinit var root: File
    private lateinit var directory: File
    private lateinit var archiveDirectory: File
    private var archiveHeld = false
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private val streams = mutableListOf<OwnedDirectoryCursor>()
    private val ancestors = linkedMapOf<File, Stamp>()
    private val heldDirectories = linkedMapOf<File, Pair<OwnedDescriptor, Stamp>>()
    private var lockDescriptor: OwnedDescriptor? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockStamp: Stamp? = null
    private var keyStore: KeyStore? = null

    override fun openCustody(rootName: String, journalId: DomainId, archive: Boolean, enroll: Boolean,
        enrollChecks: () -> Unit, createDataKey: () -> Unit) {
        environment = AndroidClientJournalEnvironment.capture(application, work)
        root = File(environment.noBackupDirectory, rootName)
        directory = File(root, journalId.value)
        archiveDirectory = File(directory, ARCHIVE)
        for (path in generateSequence(environment.noBackupDirectory) { it.parentFile }.toList().asReversed()) {
            val found = stamp(Os.lstat(path.path))
            requireDirectory(found, owned = false)
            if (path == environment.dataDirectory || path == environment.noBackupDirectory) known(found.uid == environment.uid)
            ancestors[path] = found
        }
        // The original native installation already owns the no-backup parent. Never use the
        // framework's creating getter, repair an existing path or infer enrollment from absence.
        holdDirectory(environment.dataDirectory, requireNotNull(ancestors[environment.dataDirectory]))
        holdDirectory(environment.noBackupDirectory, requireNotNull(ancestors[environment.noBackupDirectory]))
        keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }
        if (enroll) {
            enrollChecks()
            known(absent(directory))
            if (!absent(root)) requireDirectory(stamp(Os.lstat(root.path)), owned = true)
            createDataKey()
            if (absent(root)) createDirectory(root, environment.noBackupDirectory)
        }
        holdDirectory(root, stamp(Os.lstat(root.path)).also { requireDirectory(it, owned = true) })
        if (enroll) createDirectory(directory, root)
        holdDirectory(directory, stamp(Os.lstat(directory.path)).also { requireDirectory(it, owned = true) })
        if (archive) {
            if (enroll) createDirectory(archiveDirectory, directory)
            holdDirectory(archiveDirectory, stamp(Os.lstat(archiveDirectory.path)).also { requireDirectory(it, owned = true) })
            archiveHeld = true
        }
    }

    override fun acquireLock(enroll: Boolean) {
        val descriptor = openDescriptor(File(directory, LOCK),
            OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or
                (if (enroll) OsConstants.O_CREAT or OsConstants.O_EXCL else 0), if (enroll) FILE_MODE else 0)
        lockDescriptor = descriptor
        val before = stamp(Os.fstat(descriptor.fd))
        requireFile(before, 0, allowEmpty = true)
        known(before.size == 0L && before.sameFile(stamp(Os.lstat(File(directory, LOCK).path))))
        val borrower = FileOutputStream(descriptor.fd)
        descriptor.borrower = borrower
        lockChannel = borrower.channel
        lock = requireNotNull(lockChannel).tryLock() ?: error("client.journal.busy")
        lockStamp = stamp(Os.fstat(descriptor.fd))
        known(before.sameFile(requireNotNull(lockStamp)))
        if (enroll) {
            Os.fsync(descriptor.fd)
            Os.fsync(heldDirectories.getValue(directory).first.fd)
        }
    }

    override fun exists(name: String): Boolean = !absent(File(directory, name))

    override fun journalNames(): Set<String> {
        val found = mutableSetOf<String>()
        eachName(directory) { name -> known(found.add(name)) }
        return found
    }

    override fun archiveNames(visit: (String) -> Unit) = eachName(archiveDirectory, visit)
    override fun archiveHasTwoLinks(name: String): Boolean = stamp(Os.lstat(File(archiveDirectory, name).path)).links == 2L
    override fun archiveLinkedWithTemporary(name: String): Boolean =
        stamp(Os.lstat(File(archiveDirectory, name).path)).sameFile(stamp(Os.lstat(File(directory, TEMPORARY).path)))

    override fun syncDirectories(archive: Boolean) {
        if (archive) Os.fsync(heldDirectories.getValue(archiveDirectory).first.fd)
        Os.fsync(heldDirectories.getValue(directory).first.fd)
    }

    override fun removeWriteAhead() {
        work.requireActive()
        Os.remove(File(directory, ClientJournalWriteCodecV1.FILE_NAME).path)
        Os.fsync(heldDirectories.getValue(directory).first.fd)
    }

    override fun assertBase() {
        environment.requireCurrent(work)
        for ((path, expected) in ancestors) known(expected.sameDirectory(stamp(Os.lstat(path.path))))
        for ((path, held) in heldDirectories) {
            known(held.second.sameDirectory(stamp(Os.fstat(held.first.fd))) &&
                held.second.sameDirectory(stamp(Os.lstat(path.path))))
        }
        val expected = requireNotNull(lockStamp)
        known(lock?.isValid == true && lockChannel?.isOpen == true &&
            expected.sameFile(stamp(Os.fstat(requireNotNull(lockDescriptor).fd))) &&
            expected.sameFile(stamp(Os.lstat(File(directory, LOCK).path))))
        val device = heldDirectories.getValue(directory).second.device
        known(expected.device == device && heldDirectories.getValue(root).second.device == device &&
            (!archiveHeld || heldDirectories.getValue(archiveDirectory).second.device == device))
    }

    override fun writeAadFacts(text: (String) -> Unit, int: (Int) -> Unit, long: (Long) -> Unit) {
        text(environment.targetDigest); text(environment.volumeUuid)
        text(directory.path); int(environment.uid)
        long(heldDirectories.getValue(root).second.inode)
        long(heldDirectories.getValue(directory).second.inode)
        long(requireNotNull(lockStamp).inode)
        long(if (archiveHeld) heldDirectories.getValue(archiveDirectory).second.inode else 0L)
    }

    override fun requireActive() = work.requireActive()

    override fun keyNames(prefix: String): List<String> {
        val names = requireNotNull(keyStore).aliases()
        val found = ArrayList<String>()
        var count = 0
        while (names.hasMoreElements()) {
            known(++count <= 65_536)
            val name = names.nextElement()
            if (name.startsWith(prefix)) found.add(name)
        }
        return found
    }
    override fun keyExists(name: String): Boolean = requireNotNull(keyStore).containsAlias(name)
    override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) =
        createClientJournalKey(requireNotNull(keyStore), name, protection)
    override fun deleteKey(name: String) = requireNotNull(keyStore).deleteEntry(name)
    override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any =
        inspectClientJournalKey(requireNotNull(keyStore), name, protection).second
    override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(name)) // AndroidKeyStore chooses the random IV.
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = cipher.iv ?: run { ciphertext.fill(0); error("client.journal.iv") }
        return nonce.copyOf() to ciphertext
    }
    override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(name), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
    private fun key(name: String): SecretKey = inspectClientJournalKey(requireNotNull(keyStore), name, protection).first

    override fun release() {
        for (stream in streams.toList().asReversed()) { stream.closeOnce(); streams.remove(stream) }
        lock?.let { held -> known(held.isValid); held.release(); known(!held.isValid); lock = null }
        for (descriptor in descriptors.toList().asReversed()) closeDescriptor(descriptor)
        known(lockChannel?.isOpen != true)
        keyStore = null
        heldDirectories.clear(); ancestors.clear()
    }

    private fun holdDirectory(path: File, expected: Stamp) {
        // The native adapter applies header-defined close-on-exec/no-follow atomically;
        // nonblocking open plus fstat validates the directory without Java ABI constants.
        val descriptor = openDescriptor(path,
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        known(expected.sameDirectory(stamp(Os.fstat(descriptor.fd))) &&
            expected.sameDirectory(stamp(Os.lstat(path.path))))
        heldDirectories[path] = descriptor to expected
    }
    override fun readSnapshot(name: String, archive: Boolean, maximum: Int, allowTwoLinks: Boolean, force: Boolean): ClientJournalSnapshot {
        val path = File(if (archive) archiveDirectory else directory, name)
        work.requireActive()
        val before = stamp(Os.lstat(path.path))
        requireFile(before, maximum, allowEmpty = false, allowTwoLinks = allowTwoLinks)
        val descriptor = openDescriptor(path,
            OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        var bytes: ByteArray? = null
        try {
            known(before.sameFile(stamp(Os.fstat(descriptor.fd))))
            if (force) Os.fsync(descriptor.fd)
            bytes = readExact(descriptor.fd, before.size.toInt())
            known(before.sameFile(stamp(Os.fstat(descriptor.fd))) && before.sameFile(stamp(Os.lstat(path.path))))
            val mark = ClientJournalMark(before, digest(requireNotNull(bytes)).useWiped(::encodeHex))
            closeDescriptor(descriptor)
            return ClientJournalSnapshot(mark, requireNotNull(bytes)).also { bytes = null }
        } finally {
            bytes?.fill(0)
            if (!descriptor.closeStarted) closeDescriptor(descriptor)
        }
    }
    private fun readExact(fd: FileDescriptor, size: Int): ByteArray {
        val result = ByteArray(size)
        val tail = ByteArray(1)
        try {
            var offset = 0
            while (offset < size) {
                work.requireActive()
                val count = Os.read(fd, result, offset, size - offset)
                known(count > 0 && count <= size - offset)
                offset += count
            }
            known(Os.read(fd, tail, 0, 1) == 0)
            return result
        } catch (failure: Throwable) { result.fill(0); throw failure }
        finally { tail.fill(0) }
    }
    private fun eachName(path: File, action: (String) -> Unit) {
        work.requireActive()
        // Track the owner before native acquisition; allocation/tracking failure must not
        // leave an untracked native handle that a later close could falsely omit.
        val stream = OwnedDirectoryCursor()
        streams.add(stream)
        try {
            stream.open(path)
            while (true) {
                work.requireActive()
                val name = stream.nextName() ?: break
                known(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name)
                action(name)
            }
            stream.closeOnce(); streams.remove(stream)
        } finally { if (!stream.closeStarted) { stream.closeOnce(); streams.remove(stream) } }
    }
    private fun openDescriptor(path: File, flags: Int, mode: Int): OwnedDescriptor {
        val descriptor = OwnedDescriptor()
        descriptors.add(descriptor) // Retain partial acquisition before the actual native open.
        descriptor.open(path, flags, mode)
        return descriptor
    }
    private fun closeDescriptor(value: OwnedDescriptor) { value.closeOnce(); descriptors.remove(value) }
    override fun publishPrepared(name: String, archive: Boolean, ciphertext: ByteArray, maximum: Int, beforePublish: () -> Unit) {
        val temporary = File(directory, "write.pending")
        val existing = !absent(temporary)
        val descriptor = openDescriptor(temporary, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or
            OsConstants.O_NOFOLLOW or
            (if (existing) 0 else OsConstants.O_CREAT or OsConstants.O_EXCL), if (existing) 0 else FILE_MODE)
        try {
            val created = stamp(Os.fstat(descriptor.fd))
            requireFile(created, maximum, allowEmpty = true)
            known(created.size <= ciphertext.size && created.sameFile(stamp(Os.lstat(temporary.path))))
            if (!existing) known(created.size == 0L)
            readExact(descriptor.fd, created.size.toInt()).useWiped { prefix ->
                known(prefix.indices.all { prefix[it] == ciphertext[it] })
            }
            known(created.sameFile(stamp(Os.fstat(descriptor.fd))) &&
                created.sameFile(stamp(Os.lstat(temporary.path))))
            Os.lseek(descriptor.fd, created.size, OsConstants.SEEK_SET)
            var offset = created.size.toInt()
            while (offset < ciphertext.size) {
                work.requireActive()
                val count = Os.write(descriptor.fd, ciphertext, offset, ciphertext.size - offset)
                known(count > 0 && count <= ciphertext.size - offset)
                offset += count
            }
            Os.fsync(descriptor.fd)
            Os.fsync(heldDirectories.getValue(directory).first.fd)
            val written = stamp(Os.fstat(descriptor.fd))
            requireFile(written, maximum, allowEmpty = false)
            known(written.sameIdentity(created) && written.size == ciphertext.size.toLong())
            Os.lseek(descriptor.fd, 0L, OsConstants.SEEK_SET)
            readExact(descriptor.fd, ciphertext.size).useWiped { known(it.contentEquals(ciphertext)) }
            known(written.sameFile(stamp(Os.fstat(descriptor.fd))) &&
                written.sameFile(stamp(Os.lstat(temporary.path))))
            beforePublish()
            val destination = File(if (archive) archiveDirectory else directory, name)
            if (archive) {
                // link is an atomic no-replace publication, unlike a check-then-rename race.
                // The only unlink is this known own temporary AFTER its exact archive is forced.
                Os.link(temporary.path, destination.path)
                val linked = stamp(Os.fstat(descriptor.fd))
                known(linked.sameIdentityExceptLinks(written) && linked.links == 2L &&
                    linked.sameFile(stamp(Os.lstat(temporary.path))) &&
                    linked.sameFile(stamp(Os.lstat(destination.path))))
                Os.fsync(heldDirectories.getValue(archiveDirectory).first.fd)
                assertBase()
                known(linked.sameFile(stamp(Os.lstat(temporary.path))) &&
                    linked.sameFile(stamp(Os.lstat(destination.path))))
                Os.remove(temporary.path)
                Os.fsync(heldDirectories.getValue(directory).first.fd)
                Os.fsync(heldDirectories.getValue(archiveDirectory).first.fd)
            } else {
                Os.rename(temporary.path, destination.path)
                Os.fsync(heldDirectories.getValue(directory).first.fd)
            }
            val after = readSnapshot(name, archive, maximum, allowTwoLinks = false, force = false)
            try {
                known((after.mark.stamp as Stamp).sameIdentity(written) && (after.mark.stamp as Stamp).size == written.size &&
                    after.bytes.contentEquals(ciphertext))
            } finally { after.clear() }
            closeDescriptor(descriptor)
            assertBase()
        } finally {
            // Never erase a leftover temporary on error. Close only once; an uncertain native
            // close retains the descriptor and process lease rather than retrying a reused fd.
            if (!descriptor.closeStarted) closeDescriptor(descriptor)
        }
    }
    override fun removePublishedTemporary(name: String, archive: Boolean, ciphertext: ByteArray, maximum: Int, beforeRemove: () -> Unit) {
        val temporary = File(directory, "write.pending")
        if (absent(temporary)) return
        val before = stamp(Os.lstat(temporary.path))
        val linked = before.links == 2L
        val destination = File(if (archive) archiveDirectory else directory, name)
        if (linked) known(archive && before.sameFile(stamp(Os.lstat(destination.path))))
        val pending = readSnapshot(TEMPORARY, false, maximum, allowTwoLinks = linked, force = false)
        try {
            known(pending.mark.stamp == before && pending.bytes.contentEquals(ciphertext))
            val actual = readSnapshot(name, archive, maximum, allowTwoLinks = linked, force = false)
            try { known(actual.bytes.contentEquals(ciphertext)) } finally { actual.clear() }
            beforeRemove()
            known(before.sameFile(stamp(Os.lstat(temporary.path))))
            if (linked) known(before.sameFile(stamp(Os.lstat(destination.path))))
            work.requireActive()
            Os.remove(temporary.path)
            Os.fsync(heldDirectories.getValue(directory).first.fd)
            if (archive) Os.fsync(heldDirectories.getValue(archiveDirectory).first.fd)
        } finally { pending.clear() }
    }
    override fun writeNewFile(name: String, bytes: ByteArray, maximum: Int): ClientJournalMark {
        val path = File(directory, name)
        known(bytes.size in 1..maximum)
        val descriptor = openDescriptor(path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or
            OsConstants.O_NOFOLLOW or OsConstants.O_CREAT or OsConstants.O_EXCL, FILE_MODE)
        try {
            val before = stamp(Os.fstat(descriptor.fd))
            requireFile(before, maximum, allowEmpty = true)
            known(before.size == 0L && before.sameFile(stamp(Os.lstat(path.path))))
            var offset = 0
            while (offset < bytes.size) {
                work.requireActive()
                val count = Os.write(descriptor.fd, bytes, offset, bytes.size - offset)
                known(count > 0 && count <= bytes.size - offset)
                offset += count
            }
            Os.fsync(descriptor.fd)
            Os.fsync(heldDirectories.getValue(directory).first.fd)
            val after = stamp(Os.fstat(descriptor.fd))
            requireFile(after, maximum, allowEmpty = false)
            known(before.sameIdentity(after) && after.size == bytes.size.toLong() &&
                after.sameFile(stamp(Os.lstat(path.path))))
            Os.lseek(descriptor.fd, 0, OsConstants.SEEK_SET)
            readExact(descriptor.fd, bytes.size).useWiped { known(it.contentEquals(bytes)) }
            known(after.sameFile(stamp(Os.fstat(descriptor.fd))) && after.sameFile(stamp(Os.lstat(path.path))))
            closeDescriptor(descriptor)
            return ClientJournalMark(after, digest(bytes).useWiped(::encodeHex))
        } finally { if (!descriptor.closeStarted) closeDescriptor(descriptor) }
    }
    private fun absent(path: File): Boolean = try { Os.lstat(path.path); false }
        catch (missing: ErrnoException) { if (missing.errno == OsConstants.ENOENT) true else throw missing }
    private fun createDirectory(path: File, parent: File) {
        work.requireActive()
        known(absent(path))
        Os.mkdir(path.path, DIRECTORY_MODE)
        requireDirectory(stamp(Os.lstat(path.path)), owned = true)
        Os.fsync(heldDirectories.getValue(parent).first.fd)
    }
    private inner class OwnedDescriptor {
        private var native: FileDescriptor? = null
        val fd: FileDescriptor get() = checkNotNull(native)
        var borrower: FileOutputStream? = null
        var closeStarted = false
            private set
        private var closed = false
        fun open(path: File, flags: Int, mode: Int) {
            check(native == null && !closeStarted)
            try { native = AndroidNativeFiles.open(path.path, flags, mode) }
            catch (failure: AndroidNativeFiles.ClosureUncertainException) {
                work.nativeClosureUnknown()
                throw failure
            }
            val observed = AndroidNativeFiles.statusFlags(fd)
            known(AndroidNativeFiles.closeOnExec(fd) &&
                (observed and OsConstants.O_ACCMODE) == (flags and OsConstants.O_ACCMODE) &&
                (flags and OsConstants.O_NONBLOCK == 0 || observed and OsConstants.O_NONBLOCK != 0))
        }
        fun closeOnce() {
            if (closed) return
            if (closeStarted) { work.nativeClosureUnknown(); error("client.journal.descriptor-close-unknown") }
            closeStarted = true
            try {
                // FileOutputStream(FileDescriptor) borrows this descriptor on Android.
                borrower?.close()
                native?.let { AndroidNativeFiles.close(it) }
                closed = true
            } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }
    private inner class OwnedDirectoryCursor {
        private var native: AndroidNativeFiles.DirectoryCursor? = null
        var closeStarted = false
            private set
        private var closed = false
        fun open(path: File) {
            check(native == null && !closeStarted)
            try { native = AndroidNativeFiles.openDirectory(path.path) }
            catch (failure: AndroidNativeFiles.ClosureUncertainException) {
                work.nativeClosureUnknown()
                throw failure
            }
        }
        fun nextName(): String? = checkNotNull(native).nextName()
        fun closeOnce() {
            if (closed) return
            if (closeStarted) { work.nativeClosureUnknown(); error("client.journal.directory-close-unknown") }
            closeStarted = true
            try { native?.close(); closed = true }
            catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }
    internal data class Stamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int,
        val links: Long, val size: Long, val modified: Long, val changed: Long) {
        fun sameIdentityExceptLinks(other: Stamp): Boolean = device == other.device && inode == other.inode &&
            uid == other.uid && gid == other.gid && mode == other.mode
        fun sameIdentity(other: Stamp): Boolean = sameIdentityExceptLinks(other) && links == other.links
        fun sameDirectory(other: Stamp): Boolean = sameIdentityExceptLinks(other) && OsConstants.S_ISDIR(other.mode)
        fun sameFile(other: Stamp): Boolean = sameIdentity(other) && size == other.size &&
            modified == other.modified && changed == other.changed
    }
    private fun stamp(value: StructStat): Stamp = Stamp(value.st_dev, value.st_ino, value.st_uid, value.st_gid,
        value.st_mode, value.st_nlink, value.st_size, value.st_mtime, value.st_ctime)
    private fun requireDirectory(value: Stamp, owned: Boolean) {
        known(OsConstants.S_ISDIR(value.mode) && value.mode and 2 == 0)
        if (owned) known(value.uid == environment.uid && value.mode and 4095 == DIRECTORY_MODE)
    }
    private fun requireFile(value: Stamp, maximum: Int, allowEmpty: Boolean, allowTwoLinks: Boolean = false) {
        known(OsConstants.S_ISREG(value.mode) && value.uid == environment.uid &&
            value.mode and 4095 == FILE_MODE && (value.links == 1L || (allowTwoLinks && value.links == 2L)) &&
            value.size in (if (allowEmpty) 0L else 1L)..maximum.toLong())
    }

    private fun known(value: Boolean) { check(value) { "client.journal.unavailable" } }

    private companion object {
        const val LOCK = ClientJournalFileCore.LOCK
        const val ARCHIVE = ClientJournalFileCore.ARCHIVE
        const val TEMPORARY = ClientJournalFileCore.TEMPORARY
        const val DIRECTORY_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Android process identity and elapsedRealtime for the shared journal work. */
internal class AndroidClientJournalProcess : ClientJournalProcess {
    private val pid = Process.myPid()
    private val uid = Process.myUid()
    init { check(pid > 0 && uid >= 0) }
    override fun matchesOriginal(): Boolean = Process.myPid() == pid && Process.myUid() == uid
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

/** Actual installed application/user/volume observations, never an injected clock or device ID. */
private class AndroidClientJournalEnvironment private constructor(
    private val application: Application,
    private val observation: Observation,
) {
    val uid: Int get() = observation.uid
    val dataDirectory: File get() = File(observation.dataPath)
    val noBackupDirectory: File get() = File(dataDirectory, "no_backup")
    val targetDigest: String get() = observation.targetDigest
    val volumeUuid: String get() = observation.volumeUuid

    fun requireCurrent(work: ClientJournalWork) {
        work.requireActive()
        check(observe(application) == observation) { "client.journal.application-changed" }
        work.requireActive()
    }

    private data class Observation(val uid: Int, val targetDigest: String, val volumeUuid: String,
        val boot: Int, val dataPath: String, val sourcePath: String, val version: Long, val updated: Long)

    companion object {
        fun capture(application: Application, work: ClientJournalWork): AndroidClientJournalEnvironment {
            work.requireActive()
            return AndroidClientJournalEnvironment(application, observe(application)).also { it.requireCurrent(work) }
        }

        @Suppress("DEPRECATION")
        private fun observe(application: Application): Observation {
            check(Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_ANDROID_API &&
                application.applicationContext === application &&
                !application.isDeviceProtectedStorage)
            val user = application.getSystemService(Context.USER_SERVICE) as? UserManager
                ?: error("client.journal.user")
            check(user.isUserUnlocked)
            val uid = Process.myUid()
            val name = application.packageName
            val pm = application.packageManager
            val installed = AndroidNativeApplicationMetadata.installedPackageInfo(pm, name)
            val app = requireNotNull(installed.applicationInfo)
            check(uid >= 0 && app.uid == uid && application.applicationInfo.uid == uid &&
                installed.packageName == name && pm.getPackagesForUid(uid)?.toSet() == setOf(name) &&
                app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
                application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            val signer = requireNotNull(AndroidNativeApplicationMetadata.singleInstalledSignerSha256(installed))
            val androidId = requireNotNull(Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID))
            check(androidId.length in 1..16 && androidId.all { it in '0'..'9' || it in 'a'..'f' } &&
                androidId.any { it != '0' })
            val target = listOf("ukkera.android.client-device/v1", name, uid.toString(), signer, androidId)
                .joinToString("\n").encodeToByteArray().useWiped {
                    MessageDigest.getInstance("SHA-256").digest(it).useWiped(::encodeHex)
                }
            val boot = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT)
            check(boot >= 0)
            val data = application.dataDir.canonicalFile
            check(data.isAbsolute && data == File(app.dataDir).canonicalFile &&
                data.path.none { it.code < 0x20 || it.code == 0x7f })
            val volume = AndroidNativeStorageVolume.uuidForPath(application, File(data, "no_backup")).toString()
            return Observation(uid, target, volume, boot, data.path, app.sourceDir,
                AndroidNativeApplicationMetadata.versionCode(installed), installed.lastUpdateTime)
        }
    }
}

internal val ANDROID_CLIENT_UUID_V1: Regex get() = CLIENT_JOURNAL_UUID_V1

/**
 * Bounded retained-mark engineering profile. Full census refuses excess; nothing is truncated
 * or pruned. The intent adapter reserves one archive slot per active operation BEFORE dispatch.
 * This count bound is NOT a disk-space reservation or a replacement for native enrollment and
 * cross-restart rollback admission. An archive/capacity successor needs its own qualified join.
 */
internal const val ANDROID_CLIENT_MAX_ARCHIVES_V1: Int = CLIENT_JOURNAL_MAX_ARCHIVES_V1
