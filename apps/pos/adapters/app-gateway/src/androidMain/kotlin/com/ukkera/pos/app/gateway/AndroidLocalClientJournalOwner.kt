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
import com.ukkera.pos.security.AndroidPairedDeviceCredentialJournalCustody
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
        if (file.kind == AndroidClientJournalKind.CREDENTIALS)
            AndroidEncryptedClientCredentialJournal.fromExisting(file) else null
    public val intentJournal: LocalClientIntentJournal? =
        if (file.kind == AndroidClientJournalKind.INTENTS)
            AndroidEncryptedClientIntentJournal.fromExisting(file) else null
    internal val actorAllocationJournal: AndroidPairedDeviceActorAllocationJournal? =
        if (file.kind == AndroidClientJournalKind.ACTOR_ALLOCATIONS)
            AndroidPairedDeviceActorAllocationJournal.fromExisting(file) else null

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
        actorAllocationJournal?.attachSuccessfulOwner(this)
    }

    /** Final memory-only denial; safe for the registered transaction participant read. */
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
            object : AndroidClientJournalTask<Unit> {
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
        return checked is AndroidClientJournalOutcome.Value && !physicalDenied && ownsEmployeePhysical(custody)
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
        object : AndroidClientJournalTask<Unit> {
            override fun execute() { file.requireUnusedOriginalCredentials() }
            override fun discard(value: Unit) = Unit
            override fun clearInputs() = Unit
        }
    } is AndroidClientJournalOutcome.Value

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
            kind: AndroidClientJournalKind,
            enroll: Boolean,
            recoverWrites: Boolean = false,
        ): AndroidClientJournalOutcome<AndroidLocalClientJournalOwner> {
            check(!enroll || !recoverWrites)
            if (kind == AndroidClientJournalKind.ACTOR_ALLOCATIONS) return AndroidClientJournalOutcome.Unavailable
            if (!binding.belongsTo(application) || (enroll && !binding.permitsFirstEnrollment()))
                return AndroidClientJournalOutcome.Unavailable
            val c = binding.material.coordinates
            val deviceOwner = LocalClientDeviceIdentity(c.scope, c.deviceId)
            val actorId = if (kind == AndroidClientJournalKind.INTENTS) c.firstActorId else null
            val journalId = originalClientJournalId(binding, kind, "journal")
            val keyId = originalClientJournalId(binding, kind, "key")
            val protection = binding.material.protection
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId,
                deviceOwner.scope.branchId, deviceOwner.deviceId, journalId, keyId) + listOfNotNull(actorId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId)
                return AndroidClientJournalOutcome.Unavailable
            val lease = AndroidClientJournalProcessLease.acquire(journalId)
                ?: return AndroidClientJournalOutcome.Unavailable
            val work = try { AndroidClientJournalWork(lease) } catch (_: Throwable) {
                lease.releaseUnused(); return AndroidClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, actorId, journalId,
                keyId, protection, work, AndroidClientJournalBinding.Original(binding)) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return AndroidClientJournalOutcome.Unavailable
            }
            // Retain partial acquisition BEFORE the first native open. An unknown close keeps it.
            lease.retain(file, work)
            var delivered = false
            try {
                val result = work.run {
                    object : AndroidClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            when (file.kind) {
                                AndroidClientJournalKind.CREDENTIALS ->
                                    AndroidEncryptedClientCredentialJournal.validateExisting(file)
                                AndroidClientJournalKind.INTENTS ->
                                    AndroidEncryptedClientIntentJournal.validateExisting(file)
                                AndroidClientJournalKind.ACTOR_ALLOCATIONS -> error("client.original.kind")
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
                delivered = result is AndroidClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }

        /** A real consumed employee and exact STARTED/open allocation, never another original
         * firstActor, arbitrary actor constructor or second device credential owner. */
        suspend fun acquireEmployee(application: Application, custody: AndroidEmployeeClientCustody,
            allocation: NativeEmployeeJournalAllocation): AndroidClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !allocation.belongsTo(custody.native)) return AndroidClientJournalOutcome.Unavailable
            val c = custody.material.coordinates
            val deviceOwner = LocalClientDeviceIdentity(custody.scope, custody.deviceId)
            val actorId = custody.actorId; val journalId = allocation.journalId; val keyId = allocation.keyId
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId, deviceOwner.scope.branchId,
                deviceOwner.deviceId, actorId, journalId, keyId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId || actorId == c.firstActorId ||
                c.scope != deviceOwner.scope || c.deviceId != deviceOwner.deviceId) return AndroidClientJournalOutcome.Unavailable
            // The actual native owner claims this ONE open before any client key/file/native
            // operation. Reusing a lost ENROLL attempt never creates a missing namespace.
            allocation.claimOriginalJournalOpen()
            if (!allocation.belongsTo(custody.native) || !allocation.originalOpenClaimed()) return AndroidClientJournalOutcome.Unavailable
            val enroll = allocation.action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE
            val recoverWrites = allocation.action == NativeEmployeeClientJournalAction.RECOVER_PENDING_WRITES
            val lease = AndroidClientJournalProcessLease.acquire(journalId) ?: return AndroidClientJournalOutcome.Unavailable
            val work = try { AndroidClientJournalWork(lease) } catch (_: Throwable) {
                lease.releaseUnused(); return AndroidClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, actorId, journalId,
                keyId, custody.material.protection, work, AndroidClientJournalBinding.Employee(custody, allocation)) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return AndroidClientJournalOutcome.Unavailable
            }
            lease.retain(file, work)
            var delivered = false
            try {
                val result = work.run {
                    object : AndroidClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            check(file.kind == AndroidClientJournalKind.INTENTS)
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
                delivered = result is AndroidClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }

        /** Credential-only acquisition from the actual approved additional-device source.
         * The one original claim precedes client key/file IO; existing-only actions never
         * enroll missing aliases/files/heads. Final target handoff belongs to the aggregate. */
        suspend fun acquirePairedDevice(application: Application, custody: AndroidPairedDeviceCredentialJournalCustody):
            AndroidClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !custody.isCurrent()) return AndroidClientJournalOutcome.Unavailable
            if (!custody.claimOriginalJournalOpen(application) || !custody.originalOpenClaimed())
                return AndroidClientJournalOutcome.Unavailable
            val deviceOwner = LocalClientDeviceIdentity(custody.scope, custody.deviceId)
            val journalId = custody.journalId; val keyId = custody.keyId
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId,
                deviceOwner.scope.branchId, deviceOwner.deviceId, journalId, keyId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId)
                return AndroidClientJournalOutcome.Unavailable
            val enroll = custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL
            val recoverWrites = custody.action == LocalPairedDeviceCredentialJournalAction.RECOVER
            val lease = AndroidClientJournalProcessLease.acquire(journalId) ?: return AndroidClientJournalOutcome.Unavailable
            val work = try { AndroidClientJournalWork(lease) } catch (_: Throwable) {
                lease.releaseUnused(); return AndroidClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, null, journalId, keyId,
                custody.protection, work, AndroidClientJournalBinding.PairedDevice(custody)) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return AndroidClientJournalOutcome.Unavailable
            }
            lease.retain(file, work) // Retain the partial real owner before any native IO.
            var delivered = false
            try {
                val result = work.run {
                    object : AndroidClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            check(file.kind == AndroidClientJournalKind.CREDENTIALS && file.actorId == null)
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
                delivered = result is AndroidClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }

        /** The second once-only native pre-transfer claim owns allocation metadata only.
         * ENROLL requires the same actual original fresh device enrollment; other dispositions
         * cannot create a missing ledger, alias, directory, lock, manifest or native head. */
        suspend fun acquirePairedActorAllocations(application: Application,
            custody: AndroidPairedDeviceCredentialJournalCustody): AndroidClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !custody.isCurrent() ||
                !custody.claimOriginalActorAllocationJournalOpen(application) ||
                !custody.originalActorAllocationOpenClaimed()) return AndroidClientJournalOutcome.Unavailable
            return acquirePairedBound(application, LocalClientDeviceIdentity(custody.scope, custody.deviceId), null,
                pairedActorAllocationJournalId(custody, "journal"), pairedActorAllocationJournalId(custody, "key"),
                custody.protection, AndroidClientJournalBinding.PairedActorAllocations(custody), custody.action)
        }

        /** Only a fully forced original allocation AND private actual fresh Current capture
         * can consume this exact actor open. No first-cohost/employee borrowing is possible. */
        suspend fun acquirePairedActor(application: Application,
            custody: AndroidPairedDeviceActorJournalCustody): AndroidClientJournalOutcome<AndroidLocalClientJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(application) || !custody.isCurrent() ||
                !custody.claimOriginalJournalOpen(application) || !custody.originalOpenClaimed())
                return AndroidClientJournalOutcome.Unavailable
            val identity = custody.identity
            return acquirePairedBound(application, LocalClientDeviceIdentity(identity.scope, identity.deviceId),
                identity.actorId, custody.journalId, custody.keyId, custody.protection,
                AndroidClientJournalBinding.PairedActor(custody), custody.action)
        }

        private suspend fun acquirePairedBound(application: Application, deviceOwner: LocalClientDeviceIdentity,
            actorId: DomainId?, journalId: DomainId, keyId: DomainId, protection: SelfOwnedKeyProtectionV1,
            binding: AndroidClientJournalBinding, action: LocalPairedDeviceCredentialJournalAction):
            AndroidClientJournalOutcome<AndroidLocalClientJournalOwner> {
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId,
                deviceOwner.scope.branchId, deviceOwner.deviceId, journalId, keyId) + listOfNotNull(actorId)
            if (ids.any { !ANDROID_CLIENT_UUID_V1.matches(it.value) } || journalId == keyId || !binding.isCurrent())
                return AndroidClientJournalOutcome.Unavailable
            val lease = AndroidClientJournalProcessLease.acquire(journalId) ?: return AndroidClientJournalOutcome.Unavailable
            val work = try { AndroidClientJournalWork(lease) } catch (_: Throwable) {
                lease.releaseUnused(); return AndroidClientJournalOutcome.Unavailable
            }
            val file = try { AndroidClientJournalFile(application, deviceOwner, actorId, journalId, keyId,
                protection, work, binding) } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return AndroidClientJournalOutcome.Unavailable
            }
            lease.retain(file, work)
            val enroll = action == LocalPairedDeviceCredentialJournalAction.ENROLL
            val recoverWrites = action == LocalPairedDeviceCredentialJournalAction.RECOVER
            var delivered = false
            try {
                val result = work.run {
                    object : AndroidClientJournalTask<AndroidLocalClientJournalOwner> {
                        override fun execute(): AndroidLocalClientJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            when (file.kind) {
                                AndroidClientJournalKind.ACTOR_ALLOCATIONS ->
                                    AndroidPairedDeviceActorAllocationJournal.validateExisting(file)
                                AndroidClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.validateExisting(file)
                                AndroidClientJournalKind.CREDENTIALS -> error("client.paired.actor.kind")
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
                delivered = result is AndroidClientJournalOutcome.Value
                return result
            } finally {
                if (!delivered) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
            }
        }
    }
}

internal enum class AndroidClientJournalKind { CREDENTIALS, INTENTS, ACTOR_ALLOCATIONS }
internal sealed interface AndroidClientJournalOutcome<out T> {
    class Value<T>(val value: T) : AndroidClientJournalOutcome<T>
    data object Unavailable : AndroidClientJournalOutcome<Nothing>
}
internal interface AndroidClientJournalTask<T> {
    fun execute(): T
    fun discard(value: T)
    fun clearInputs()
}

/**
 * Same-lifetime native continuity only. All paths/aliases are derived here; callers supply no
 * SecretKey, path, clock, verifier or current-lifetime predicate. Android API24 native key/file
 * facilities preserve the selected protection. All native operations run inside this retained worker.
 */
internal class AndroidClientJournalFile(
    private val application: Application,
    val deviceOwner: LocalClientDeviceIdentity,
    val actorId: DomainId?,
    val journalId: DomainId,
    val keyId: DomainId,
    private val protection: SelfOwnedKeyProtectionV1,
    private val work: AndroidClientJournalWork,
    private val journalBinding: AndroidClientJournalBinding,
) {
    val employeePhysicalCustody: AndroidEmployeeClientCustody? = (journalBinding as? AndroidClientJournalBinding.Employee)?.custody
    val pairedDeviceAllocationCustody: AndroidPairedDeviceCredentialJournalCustody? =
        (journalBinding as? AndroidClientJournalBinding.PairedActorAllocations)?.custody
    init { journalBinding.requireCoordinates(deviceOwner, actorId, journalId, keyId, protection) }
    val kind: AndroidClientJournalKind = journalBinding.fixedKind ?:
        if (actorId == null) AndroidClientJournalKind.CREDENTIALS else AndroidClientJournalKind.INTENTS
    private enum class Phase { NEW, OPENING, OPEN, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private lateinit var environment: AndroidClientJournalEnvironment
    private lateinit var root: File
    private lateinit var directory: File
    private lateinit var archive: File
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private val streams = mutableListOf<OwnedDirectoryCursor>()
    private val ancestors = linkedMapOf<File, Stamp>()
    private val heldDirectories = linkedMapOf<File, Pair<OwnedDescriptor, Stamp>>()
    private var lockDescriptor: OwnedDescriptor? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockStamp: Stamp? = null
    private var manifestStamp: Mark? = null
    private var archiveStamps: Map<String, Mark>? = null
    private var keyStore: KeyStore? = null
    // Evidence is retained only for this live owner. Reopening observes the same native key
    // again; richer OS evidence does not rewrite a persisted claim about its original kind.
    private var keySecurity: AndroidNativeKeySecurity? = null
    private var keyWitness: ByteArray? = null
    private var keyWitnessDigest: ByteArray? = null
    private var head: AndroidClientJournalHead? = null
    private var headAdmitted = false
    private var recoveredWriteHead = false
    private val alias: String get() = journalBinding.keyAliasNamespace + journalId.value + "/" + keyId.value
    private val maximumManifest: Int get() = when (kind) {
        AndroidClientJournalKind.CREDENTIALS -> MAX_CLIENT_CREDENTIAL_BYTES
        AndroidClientJournalKind.INTENTS -> MAX_CLIENT_INTENT_MANIFEST_BYTES
        AndroidClientJournalKind.ACTOR_ALLOCATIONS -> MAX_PAIRED_ACTOR_ALLOCATION_BYTES
    }

    fun openNative(enroll: Boolean, recoverWrites: Boolean) {
        check(phase == Phase.NEW)
        known(!enroll || !recoverWrites)
        known(journalBinding.isCurrent() && (!enroll || journalBinding.permitsEnrollment()))
        phase = Phase.OPENING
        environment = AndroidClientJournalEnvironment.capture(application, work)
        root = File(environment.noBackupDirectory, journalBinding.rootName)
        directory = File(root, journalId.value)
        archive = File(directory, "archive")
        for (path in generateSequence(environment.noBackupDirectory) { it.parentFile }.toList().asReversed()) {
            val found = stamp(Os.lstat(path.path))
            requireDirectory(found, owned = false)
            if (path == environment.dataDirectory || path == environment.noBackupDirectory)
                known(found.uid == environment.uid)
            ancestors[path] = found
        }
        // The original native installation already owns the no-backup parent. Never use the
        // framework's creating getter, repair an existing path or infer enrollment from absence.
        holdDirectory(environment.dataDirectory, requireNotNull(ancestors[environment.dataDirectory]))
        holdDirectory(environment.noBackupDirectory, requireNotNull(ancestors[environment.noBackupDirectory]))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }
        keyStore = store
        head = AndroidClientJournalHead(store,
            "${journalBinding.headAliasNamespace}${journalId.value}/${keyId.value}/", protection, work)
        if (enroll) {
            known(journalBinding.permitsEnrollment())
            // The original fixed key alias is the once-only native enrollment intent. It is
            // created BEFORE filesystem creation. A partial/unknown intent is never replaced.
            known(!store.containsAlias(alias))
            requireNoDataKey()
            requireNotNull(head).requireAbsent()
            known(absent(directory))
            if (!absent(root)) requireDirectory(stamp(Os.lstat(root.path)), owned = true)
            createClientJournalKey(store, alias, protection)
            if (absent(root)) createDirectory(root, environment.noBackupDirectory)
        }
        holdDirectory(root, stamp(Os.lstat(root.path)).also { requireDirectory(it, owned = true) })
        if (enroll) createDirectory(directory, root)
        holdDirectory(directory, stamp(Os.lstat(directory.path)).also { requireDirectory(it, owned = true) })
        if (kind == AndroidClientJournalKind.INTENTS) {
            if (enroll) createDirectory(archive, directory)
            holdDirectory(archive, stamp(Os.lstat(archive.path)).also { requireDirectory(it, owned = true) })
        }
        val hasWriteAhead = !enroll && !absent(File(directory, AndroidClientJournalWriteCodecV1.FILE_NAME))
        val hasTemporary = !enroll && !absent(File(directory, "write.pending"))
        if (!enroll) {
            known((!hasWriteAhead && !hasTemporary) || (recoverWrites && hasWriteAhead))
            checkNames(pending = hasTemporary, writeAhead = hasWriteAhead)
        }
        val descriptor = openDescriptor(File(directory, "client.lock"),
            OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or
                (if (enroll) OsConstants.O_CREAT or OsConstants.O_EXCL else 0), if (enroll) FILE_MODE else 0)
        lockDescriptor = descriptor
        val before = stamp(Os.fstat(descriptor.fd))
        requireFile(before, 0, allowEmpty = true)
        known(before.size == 0L && before.sameFile(stamp(Os.lstat(File(directory, "client.lock").path))))
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
        phase = Phase.OPEN
        assertBase()
        if (enroll) createInitialManifest()
        checkNames(pending = hasTemporary, writeAhead = hasWriteAhead)
        val snapshot = readSnapshot(File(directory, "active.v1.enc"), maximumManifest + ENVELOPE_BYTES)
        try {
            manifestStamp = snapshot.mark
            val nativeKey = inspectKey()
            decryptWith(nativeKey, snapshot.bytes, "manifest", maximumManifest).useWiped { plain ->
                keyWitnessDigest = digest(plain)
            }
            keyWitness = snapshot.bytes.copyOf()
        } finally { snapshot.clear() }
        checkedKey()
        if (hasWriteAhead) recoverPreparedWrite()
        checkNames()
        // Complete decoding/census AND the native head join precede publication below.
    }

    fun requireEmployeePhysicalCustody(custody: AndroidEmployeeClientCustody) {
        work.requireActive()
        known(kind == AndroidClientJournalKind.INTENTS && employeePhysicalCustody === custody && journalBinding.isCurrent())
    }

    fun attachSuccessfulOwner(owner: AndroidLocalClientJournalOwner) {
        check(phase == Phase.OPEN && headAdmitted)
        work.requireActive()
        work.attachSuccessfulOwner(owner)
    }

    fun isCurrentOwner(owner: AndroidLocalClientJournalOwner): Boolean = work.isCurrentOwner(owner)

    fun admitNativeHead(enroll: Boolean) {
        known(!headAdmitted)
        val digest = completeDigest()
        when {
            enroll -> requireNotNull(head).enroll(digest)
            recoveredWriteHead -> requireNotNull(head).requireCurrent(digest)
            else -> requireNotNull(head).open(digest)
        }
        headAdmitted = true
    }

    /**
     * Positive proof that the FIRST journal completed enrollment and has never held work.
     * The complete native head must be sequence zero, not a reset/current-empty projection.
     * This never creates or rewrites any credential file/key/head and grants no login.
     */
    fun requireUnusedOriginalCredentials() {
        val original = (journalBinding as? AndroidClientJournalBinding.Original)?.original ?: error("client.journal.original-required")
        known(kind == AndroidClientJournalKind.CREDENTIALS && original.permitsFirstEnrollment())
        assertContinuity()
        requireNotNull(head).requireInitial(completeDigest())
        readManifest(MAX_CLIENT_CREDENTIAL_BYTES).useWiped { actual ->
            AndroidEncryptedClientCredentialJournal.originalEmptyManifest(this).useWiped { initial ->
                known(actual.contentEquals(initial))
            }
        }
        assertContinuity()
        known(original.permitsFirstEnrollment())
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

    private fun requireNoDataKey() {
        val names = requireNotNull(keyStore).aliases()
        var count = 0
        val prefix = "${journalBinding.keyAliasNamespace}${journalId.value}/"
        while (names.hasMoreElements()) {
            known(++count <= 65_536)
            known(!names.nextElement().startsWith(prefix))
        }
    }

    /** Only explicit native-approved first enrollment calls this; absence never selects it. */
    private fun createInitialManifest() {
        known(journalBinding.permitsEnrollment() && absent(File(directory, "active.v1.enc")))
        val initial = when (kind) {
            AndroidClientJournalKind.CREDENTIALS -> if (journalBinding is AndroidClientJournalBinding.PairedDevice)
                AndroidEncryptedClientCredentialJournal.pairedDeviceEmptyManifest(this)
            else AndroidEncryptedClientCredentialJournal.originalEmptyManifest(this)
            AndroidClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.originalEmptyManifest(this)
            AndroidClientJournalKind.ACTOR_ALLOCATIONS -> AndroidPairedDeviceActorAllocationJournal.originalEmptyManifest(this)
        }
        initial.useWiped { plain ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, inspectKey())
            aad("manifest").useWiped(cipher::updateAAD)
            val encrypted = cipher.doFinal(plain)
            val nonce = cipher.iv
            try {
                known(nonce != null && nonce.size == 12 && encrypted.size == plain.size + 16)
                val bytes = ByteBuffer.allocate(ENVELOPE_BYTES + plain.size)
                    .putInt(ENVELOPE_MAGIC).put(nonce).put(encrypted).array()
                bytes.useWiped { writeNewFile(File(directory, "active.v1.enc"), it, maximumManifest + ENVELOPE_BYTES) }
            } finally { nonce?.fill(0); encrypted.fill(0) }
        }
    }

    /** Includes every archive, not merely a caller's selected operation or a mutable count. */
    private fun completeDigest(
        manifestDigest: String = requireNotNull(manifestStamp).digest,
        archiveDigests: Map<String, String> = if (kind == AndroidClientJournalKind.INTENTS)
            requireNotNull(archiveStamps).mapValues { it.value.digest } else emptyMap(),
    ): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) { (value + "\n").encodeToByteArray().useWiped(hash::update) }
        field(journalBinding.headDigestDomain)
        journalBinding.employeeEnrollmentCommitment?.let(::field)
        journalBinding.pairedDeviceEnrollmentCommitment?.let(::field)
        journalBinding.pairedActorEnrollmentCommitment?.let(::field)
        field(journalId.value); field(keyId.value); field(manifestDigest)
        field(archiveDigests.size.toString())
        for ((name, digest) in archiveDigests.toSortedMap()) { field(name); field(digest) }
        return hash.digest().useWiped(::encodeHex)
    }

    suspend fun <T> owned(prepare: () -> AndroidClientJournalTask<T>): AndroidClientJournalOutcome<T> =
        work.run {
            val task = prepare()
            try {
                object : AndroidClientJournalTask<T> {
                    override fun execute(): T {
                        assertContinuity()
                        val value = task.execute()
                        try {
                            assertContinuity()
                            return value
                        } catch (failure: Throwable) {
                            // A successful read may already contain copied tokens. Losing custody
                            // after that read must dispose the value, not merely return Unavailable.
                            task.discard(value)
                            throw failure
                        }
                    }
                    override fun discard(value: T) { task.discard(value) }
                    override fun clearInputs() { task.clearInputs() }
                }
            } catch (failure: Throwable) { task.clearInputs(); throw failure }
        }

    suspend fun closeView() { work.retireView().await() }
    suspend fun closeOwner() { work.requestClose { closeNative() }.await() }

    fun readManifest(maximumBytes: Int): ByteArray {
        known(maximumBytes == maximumManifest)
        assertBase()
        val snapshot = readSnapshot(File(directory, "active.v1.enc"), maximumBytes + ENVELOPE_BYTES)
        try {
            known(snapshot.mark == manifestStamp)
            val plain = decryptWith(checkedKey(), snapshot.bytes, "manifest", maximumBytes)
            try {
                assertBase(); checkedKey()
                return plain
            } catch (failure: Throwable) { plain.fill(0); throw failure }
        } finally { snapshot.clear() }
    }

    fun replaceManifest(plaintext: ByteArray, maximumBytes: Int) {
        known(maximumBytes == maximumManifest && plaintext.size in 1..maximumBytes)
        assertContinuity()
        encrypt(plaintext, "manifest").useWiped { encrypted ->
            writeForced(File(directory, "active.v1.enc"), encrypted, appendArchive = false)
        }
        assertContinuity()
    }

    /**
     * Stream every complete archive body, checking all names, native identities and ciphertext
     * digests. Only small immutable marks are retained; complete records are not accumulated.
     * The adapter verifies archivedCount and every active/archive duplicate on EVERY read.
     */
    fun visitArchives(visit: (OperationId, ByteArray) -> Unit): Long {
        known(kind == AndroidClientJournalKind.INTENTS)
        assertBase()
        val previous = archiveStamps
        val observed = linkedMapOf<String, Mark>()
        var count = 0L
        eachName(archive) { name ->
            val id = archiveId(name)
            known(name !in observed && observed.size < ANDROID_CLIENT_MAX_ARCHIVES_V1)
            val snapshot = readSnapshot(File(archive, name), MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES)
            try {
                if (previous != null) known(previous[name] == snapshot.mark)
                decryptWith(checkedKey(), snapshot.bytes, "archive/" + id.value, MAX_CLIENT_ENTRY_BYTES).useWiped {
                    visit(id, it)
                }
                observed[name] = snapshot.mark
                count = Math.addExact(count, 1L)
            } finally { snapshot.clear() }
        }
        if (previous != null) known(previous.keys == observed.keys)
        assertBase(); checkedKey()
        archiveStamps = observed
        return count
    }

    /** A new immutable result archive; never overwrites an existing name or deletes an old one. */
    fun appendArchive(operationId: OperationId, plaintext: ByteArray) {
        known(kind == AndroidClientJournalKind.INTENTS && plaintext.size in 1..MAX_CLIENT_ENTRY_BYTES)
        val name = archiveName(operationId)
        assertContinuity()
        val retained = requireNotNull(archiveStamps)
        known(name !in retained && retained.size < ANDROID_CLIENT_MAX_ARCHIVES_V1)
        encrypt(plaintext, "archive/" + operationId.value).useWiped { encrypted ->
            writeForced(File(archive, name), encrypted, appendArchive = true)
        }
        assertContinuity()
    }

    fun assertContinuity() {
        assertBase(); checkNames()
        val current = readSnapshot(File(directory, "active.v1.enc"), maximumManifest + ENVELOPE_BYTES)
        try { known(current.mark == manifestStamp) } finally { current.clear() }
        if (kind == AndroidClientJournalKind.INTENTS) checkArchiveMarks()
        checkedKey()
        if (headAdmitted) requireNotNull(head).requireCurrent(completeDigest())
        assertBase()
    }

    private fun assertBase() {
        known(phase == Phase.OPEN)
        environment.requireCurrent(work)
        for ((path, expected) in ancestors) known(expected.sameDirectory(stamp(Os.lstat(path.path))))
        for ((path, held) in heldDirectories) {
            known(held.second.sameDirectory(stamp(Os.fstat(held.first.fd))) &&
                held.second.sameDirectory(stamp(Os.lstat(path.path))))
        }
        val expected = requireNotNull(lockStamp)
        known(lock?.isValid == true && lockChannel?.isOpen == true &&
            expected.sameFile(stamp(Os.fstat(requireNotNull(lockDescriptor).fd))) &&
            expected.sameFile(stamp(Os.lstat(File(directory, "client.lock").path))))
        val device = heldDirectories.getValue(directory).second.device
        known(expected.device == device && heldDirectories.getValue(root).second.device == device &&
            (kind != AndroidClientJournalKind.INTENTS || heldDirectories.getValue(archive).second.device == device))
    }

    private fun checkNames(pending: Boolean = false, writeAhead: Boolean = false) {
        val allowed = mutableSetOf("client.lock", "active.v1.enc")
        if (kind == AndroidClientJournalKind.INTENTS) allowed.add("archive")
        if (pending) allowed.add("write.pending")
        if (writeAhead) allowed.add(AndroidClientJournalWriteCodecV1.FILE_NAME)
        val found = mutableSetOf<String>()
        eachName(directory) { name -> known(name in allowed && found.add(name)) }
        known(found == allowed)
    }

    private fun checkArchiveMarks() {
        val expected = requireNotNull(archiveStamps)
        val found = mutableSetOf<String>()
        eachName(archive) { name ->
            archiveId(name)
            known(found.size < ANDROID_CLIENT_MAX_ARCHIVES_V1 && found.add(name))
            val actual = readSnapshot(File(archive, name), MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES)
            try { known(expected[name] == actual.mark) } finally { actual.clear() }
        }
        known(found == expected.keys)
    }

    private fun writeForced(destination: File, bytes: ByteArray, appendArchive: Boolean) {
        val maximum = if (appendArchive) MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES else maximumManifest + ENVELOPE_BYTES
        known(bytes.size in 1..maximum)
        assertContinuity()
        known(headAdmitted)
        val beforeDigest = completeDigest()
        val sequence = requireNotNull(head).settledSequence(beforeDigest)
        val archiveId = if (appendArchive) archiveId(destination.name) else null
        known(destination == if (archiveId == null) File(directory, "active.v1.enc") else File(archive, archiveName(archiveId)))
        val write = AndroidClientJournalWrite(sequence, beforeDigest, projectedDigest(archiveId, bytes), archiveId, bytes.copyOf())
        try {
            val ahead = AndroidClientJournalWriteCodecV1.encode(write, maximum).useWiped { encoded ->
                writeNewFile(File(directory, AndroidClientJournalWriteCodecV1.FILE_NAME), encoded, maximumWriteAhead)
            }
            // Recheck the entire old cut and exact write-ahead bytes BEFORE advancing native
            // authority. A file-only proposal, including an interrupted key generation, is
            // never replayed unless the original native successor is actually present.
            assertBase(); checkNames(writeAhead = true); requireWriteAhead(ahead)
            known(capturePreparedCut(write) == beforeDigest)
            checkedKey()
            requireNotNull(head).prepare(sequence, beforeDigest, write.nextDigest)
            finishPreparedWrite(write, ahead)
        } finally { write.clear() }
    }

    private val maximumWriteAhead: Int
        get() = maximumManifest + ENVELOPE_BYTES + AndroidClientJournalWriteCodecV1.MAX_HEADER_BYTES

    /** Only the explicit recovery action reaches this; ordinary opening never changes files. */
    private fun recoverPreparedWrite() {
        known(!headAdmitted && !recoveredWriteHead)
        val ahead = readSnapshot(File(directory, AndroidClientJournalWriteCodecV1.FILE_NAME), maximumWriteAhead)
        try {
            val write = AndroidClientJournalWriteCodecV1.decode(ahead.bytes, maximumManifest + ENVELOPE_BYTES)
            try {
                writeMaximum(write)
                requireNotNull(head).recoverPrepared(write.previousSequence, write.previousDigest, write.nextDigest)
                finishPreparedWrite(write, ahead.mark)
                recoveredWriteHead = true
            } finally { write.clear() }
        } finally { ahead.clear() }
    }

    private fun writeMaximum(write: AndroidClientJournalWrite): Int {
        val maximum = if (write.archiveId == null) maximumManifest + ENVELOPE_BYTES else {
            known(kind == AndroidClientJournalKind.INTENTS)
            archiveName(write.archiveId)
            MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES
        }
        known(write.ciphertext.size in (ENVELOPE_BYTES + 1)..maximum)
        return maximum
    }

    private fun writeDestination(write: AndroidClientJournalWrite): File =
        write.archiveId?.let { File(archive, archiveName(it)) } ?: File(directory, "active.v1.enc")

    private fun projectedDigest(archiveId: OperationId?, ciphertext: ByteArray): String {
        val next = digest(ciphertext).useWiped(::encodeHex)
        if (archiveId == null) return completeDigest(manifestDigest = next)
        known(kind == AndroidClientJournalKind.INTENTS)
        val old = requireNotNull(archiveStamps).mapValues { it.value.digest }
        val name = archiveName(archiveId)
        known(name !in old && old.size < ANDROID_CLIENT_MAX_ARCHIVES_V1)
        return completeDigest(archiveDigests = old + (name to next))
    }

    private fun requireWriteAhead(expected: Mark) {
        val actual = readSnapshot(File(directory, AndroidClientJournalWriteCodecV1.FILE_NAME), maximumWriteAhead)
        try { known(actual.mark == expected) } finally { actual.clear() }
    }

    /**
     * Census every file, not only the target. The only accepted two-link file is the exact
     * archive/temporary pair left by our atomic no-replace link. Other extra links still fail.
     * Neither this census nor a readable write-ahead record grants authority without the head.
     */
    private fun capturePreparedCut(write: AndroidClientJournalWrite): String {
        assertBase()
        val temporary = File(directory, "write.pending")
        val hasTemporary = !absent(temporary)
        checkNames(pending = hasTemporary, writeAhead = true)
        val previousManifest = requireNotNull(manifestStamp)
        val manifest = readSnapshot(File(directory, "active.v1.enc"), maximumManifest + ENVELOPE_BYTES)
        try {
            if (write.archiveId != null || manifest.mark.digest == previousManifest.digest)
                known(manifest.mark == previousManifest)
            manifestStamp = manifest.mark
        } finally { manifest.clear() }
        if (kind == AndroidClientJournalKind.INTENTS) {
            val previous = archiveStamps
            val target = write.archiveId?.let(::archiveName)
            val observed = linkedMapOf<String, Mark>()
            eachName(archive) { name ->
                archiveId(name)
                known(observed.size < ANDROID_CLIENT_MAX_ARCHIVES_V1 && name !in observed)
                val path = File(archive, name)
                val before = stamp(Os.lstat(path.path))
                val linked = before.links == 2L
                if (linked) known(hasTemporary && write.archiveId?.let(::archiveName) == name &&
                    before.sameFile(stamp(Os.lstat(temporary.path))))
                val actual = readSnapshot(path, MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES, allowTwoLinks = linked)
                try {
                    if (previous != null && name != target) known(previous[name] == actual.mark)
                    observed[name] = actual.mark
                } finally { actual.clear() }
            }
            if (previous != null) known(previous.keys.filterNot { it == target }.toSet() ==
                observed.keys.filterNot { it == target }.toSet())
            archiveStamps = observed
        }
        checkedKey(); assertBase()
        return completeDigest()
    }

    /** Complete only the already native-fenced exact bytes; never retry a business command. */
    private fun finishPreparedWrite(write: AndroidClientJournalWrite, ahead: Mark) {
        val maximum = writeMaximum(write)
        requireWriteAhead(ahead)
        requireNotNull(head).requireCurrent(write.nextDigest)
        val purpose = write.archiveId?.let { "archive/" + it.value } ?: "manifest"
        decryptWith(checkedKey(), write.ciphertext, purpose, maximum - ENVELOPE_BYTES).useWiped { }
        when (capturePreparedCut(write)) {
            write.previousDigest -> {
                known(projectedDigest(write.archiveId, write.ciphertext) == write.nextDigest)
                publishPreparedBytes(write, maximum, ahead)
            }
            write.nextDigest -> removePublishedTemporary(write, maximum)
            else -> error("client.journal.write-cut-unresolved")
        }
        // A visible rename/link is not enough after interruption. Force the actual target
        // and both affected directory entries, then read/hash the complete resulting cut.
        val destination = writeDestination(write)
        val actual = readSnapshot(destination, maximum, force = true)
        try { known(actual.bytes.contentEquals(write.ciphertext)) } finally { actual.clear() }
        if (write.archiveId != null) Os.fsync(heldDirectories.getValue(archive).first.fd)
        Os.fsync(heldDirectories.getValue(directory).first.fd)
        known(capturePreparedCut(write) == write.nextDigest)
        requireWriteAhead(ahead)
        requireNotNull(head).finishPrepared(write.nextDigest)
        // Remove only this exact own duplicate recovery record after native/file agreement.
        // Any failure leaves it (or its completed file cut) recoverable; no data/key reset.
        assertBase(); checkNames(writeAhead = true); requireWriteAhead(ahead)
        work.requireActive()
        Os.remove(File(directory, AndroidClientJournalWriteCodecV1.FILE_NAME).path)
        Os.fsync(heldDirectories.getValue(directory).first.fd)
        checkNames(); assertBase()
        requireNotNull(head).requireCurrent(completeDigest())
    }

    /**
     * A partial temporary may be completed ONLY as the exact prefix of the native-fenced
     * ciphertext. No truncation, overwrite, removal/recreation or fresh encryption occurs.
     */
    private fun publishPreparedBytes(write: AndroidClientJournalWrite, maximum: Int, ahead: Mark) {
        val temporary = File(directory, "write.pending")
        val existing = !absent(temporary)
        val descriptor = openDescriptor(temporary, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or
            OsConstants.O_NOFOLLOW or
            (if (existing) 0 else OsConstants.O_CREAT or OsConstants.O_EXCL), if (existing) 0 else FILE_MODE)
        try {
            val created = stamp(Os.fstat(descriptor.fd))
            requireFile(created, maximum, allowEmpty = true)
            known(created.size <= write.ciphertext.size && created.sameFile(stamp(Os.lstat(temporary.path))))
            if (!existing) known(created.size == 0L)
            readExact(descriptor.fd, created.size.toInt()).useWiped { prefix ->
                known(prefix.indices.all { prefix[it] == write.ciphertext[it] })
            }
            known(created.sameFile(stamp(Os.fstat(descriptor.fd))) &&
                created.sameFile(stamp(Os.lstat(temporary.path))))
            Os.lseek(descriptor.fd, created.size, OsConstants.SEEK_SET)
            var offset = created.size.toInt()
            while (offset < write.ciphertext.size) {
                work.requireActive()
                val count = Os.write(descriptor.fd, write.ciphertext, offset, write.ciphertext.size - offset)
                known(count > 0 && count <= write.ciphertext.size - offset)
                offset += count
            }
            Os.fsync(descriptor.fd)
            Os.fsync(heldDirectories.getValue(directory).first.fd)
            val written = stamp(Os.fstat(descriptor.fd))
            requireFile(written, maximum, allowEmpty = false)
            known(written.sameIdentity(created) && written.size == write.ciphertext.size.toLong())
            Os.lseek(descriptor.fd, 0L, OsConstants.SEEK_SET)
            readExact(descriptor.fd, write.ciphertext.size).useWiped { known(it.contentEquals(write.ciphertext)) }
            known(written.sameFile(stamp(Os.fstat(descriptor.fd))) &&
                written.sameFile(stamp(Os.lstat(temporary.path))))
            requireWriteAhead(ahead)
            known(capturePreparedCut(write) == write.previousDigest)
            requireNotNull(head).requireCurrent(write.nextDigest)
            val destination = writeDestination(write)
            if (write.archiveId != null) {
                // link is an atomic no-replace publication, unlike a check-then-rename race.
                // The only unlink is this known own temporary AFTER its exact archive is forced.
                Os.link(temporary.path, destination.path)
                val linked = stamp(Os.fstat(descriptor.fd))
                known(linked.sameIdentityExceptLinks(written) && linked.links == 2L &&
                    linked.sameFile(stamp(Os.lstat(temporary.path))) &&
                    linked.sameFile(stamp(Os.lstat(destination.path))))
                Os.fsync(heldDirectories.getValue(archive).first.fd)
                assertBase()
                known(linked.sameFile(stamp(Os.lstat(temporary.path))) &&
                    linked.sameFile(stamp(Os.lstat(destination.path))))
                Os.remove(temporary.path)
                Os.fsync(heldDirectories.getValue(directory).first.fd)
                Os.fsync(heldDirectories.getValue(archive).first.fd)
            } else {
                Os.rename(temporary.path, destination.path)
                Os.fsync(heldDirectories.getValue(directory).first.fd)
            }
            val after = readSnapshot(destination, maximum)
            try {
                known(after.mark.stamp.sameIdentity(written) && after.mark.stamp.size == written.size &&
                    after.bytes.contentEquals(write.ciphertext))
            } finally { after.clear() }
            closeDescriptor(descriptor)
            assertBase(); checkNames(writeAhead = true)
        } finally {
            // Never erase a leftover temporary on error. Close only once; an uncertain native
            // close retains the descriptor and process lease rather than retrying a reused fd.
            if (!descriptor.closeStarted) closeDescriptor(descriptor)
        }
    }

    /** Remove a proved duplicate after publication, including the interrupted two-link cut. */
    private fun removePublishedTemporary(write: AndroidClientJournalWrite, maximum: Int) {
        val temporary = File(directory, "write.pending")
        if (absent(temporary)) return
        val before = stamp(Os.lstat(temporary.path))
        val linked = before.links == 2L
        val destination = writeDestination(write)
        if (linked) known(write.archiveId != null && before.sameFile(stamp(Os.lstat(destination.path))))
        val pending = readSnapshot(temporary, maximum, allowTwoLinks = linked)
        try {
            known(pending.mark.stamp == before && pending.bytes.contentEquals(write.ciphertext))
            val actual = readSnapshot(destination, maximum, allowTwoLinks = linked)
            try { known(actual.bytes.contentEquals(write.ciphertext)) } finally { actual.clear() }
            requireNotNull(head).requireCurrent(write.nextDigest)
            known(before.sameFile(stamp(Os.lstat(temporary.path))))
            if (linked) known(before.sameFile(stamp(Os.lstat(destination.path))))
            work.requireActive()
            Os.remove(temporary.path)
            Os.fsync(heldDirectories.getValue(directory).first.fd)
            if (write.archiveId != null) Os.fsync(heldDirectories.getValue(archive).first.fd)
        } finally { pending.clear() }
    }

    /** Fixed new file only: explicit initial enrollment or write-ahead, never journal replacement. */
    private fun writeNewFile(path: File, bytes: ByteArray, maximum: Int): Mark {
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
            return Mark(after, digest(bytes).useWiped(::encodeHex))
        } finally { if (!descriptor.closeStarted) closeDescriptor(descriptor) }
    }

    private fun encrypt(plaintext: ByteArray, purpose: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, checkedKey()) // AndroidKeyStore chooses the random IV.
        aad(purpose).useWiped(cipher::updateAAD)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        try {
            known(nonce != null && nonce.size == 12 && ciphertext.size == plaintext.size + 16)
            val encoded = ByteBuffer.allocate(ENVELOPE_BYTES + plaintext.size)
                .putInt(ENVELOPE_MAGIC).put(nonce).put(ciphertext).array()
            try {
                checkedKey(); assertBase()
                return encoded
            } catch (failure: Throwable) { encoded.fill(0); throw failure }
        } finally { nonce?.fill(0); ciphertext.fill(0) }
    }

    private fun decryptWith(key: SecretKey, encrypted: ByteArray, purpose: String, maximum: Int): ByteArray {
        known(encrypted.size in (ENVELOPE_BYTES + 1)..(maximum + ENVELOPE_BYTES))
        val input = ByteBuffer.wrap(encrypted)
        known(input.int == ENVELOPE_MAGIC)
        val nonce = ByteArray(12)
        try {
            input.get(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            aad(purpose).useWiped(cipher::updateAAD)
            val plain = cipher.doFinal(encrypted, 16, encrypted.size - 16)
            try { known(plain.size in 1..maximum); return plain }
            catch (failure: Throwable) { plain.fill(0); throw failure }
        } finally { nonce.fill(0) }
    }

    private fun checkedKey(): SecretKey {
        val key = inspectKey()
        decryptWith(key, requireNotNull(keyWitness), "manifest", maximumManifest).useWiped { plain ->
            digest(plain).useWiped { known(MessageDigest.isEqual(it, requireNotNull(keyWitnessDigest))) }
        }
        work.requireActive()
        return key
    }

    private fun inspectKey(): SecretKey {
        work.requireActive()
        val store = requireNotNull(keyStore)
        val prefix = journalBinding.keyAliasNamespace + journalId.value + "/"
        val names = store.aliases()
        var count = 0
        var found = false
        while (names.hasMoreElements()) {
            known(++count <= 65_536)
            val name = names.nextElement()
            if (name.startsWith(prefix)) { known(name == alias && !found); found = true }
        }
        known(found && store.containsAlias(alias))
        val (key, security) = inspectClientJournalKey(store, alias, protection)
        known(keySecurity == null || keySecurity == security)
        keySecurity = security
        work.requireActive()
        return key
    }

    private fun aad(purpose: String): ByteArray {
        val out = ByteBuffer.allocate(8192)
        try {
            fun text(value: String) {
                value.encodeToByteArray(throwOnInvalidSequence = true).useWiped { out.putInt(it.size); out.put(it) }
            }
            text(journalBinding.aeadDomain)
            text(deviceOwner.scope.organizationId.value); text(deviceOwner.scope.companyId.value)
            text(deviceOwner.scope.branchId.value); text(deviceOwner.deviceId.value)
            out.putInt(when (kind) {
                AndroidClientJournalKind.CREDENTIALS -> 1
                AndroidClientJournalKind.INTENTS -> 2
                AndroidClientJournalKind.ACTOR_ALLOCATIONS -> 3
            })
            text(actorId?.value.orEmpty()); text(journalId.value); text(keyId.value)
            journalBinding.employeeEnrollmentCommitment?.let(::text)
            journalBinding.pairedDeviceEnrollmentCommitment?.let(::text)
            journalBinding.pairedActorEnrollmentCommitment?.let(::text)
            out.putInt(when (protection) {
                SelfOwnedKeyProtectionV1.OS_ISOLATED -> 1
                SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> 2
                SelfOwnedKeyProtectionV1.STRONGBOX -> 3
            })
            text(environment.targetDigest); text(environment.volumeUuid)
            text(directory.path); out.putInt(environment.uid)
            out.putLong(heldDirectories.getValue(root).second.inode)
            out.putLong(heldDirectories.getValue(directory).second.inode)
            out.putLong(requireNotNull(lockStamp).inode)
            out.putLong(if (kind == AndroidClientJournalKind.INTENTS)
                heldDirectories.getValue(archive).second.inode else 0L)
            text(purpose)
            return out.array().copyOf(out.position())
        } finally { out.array().fill(0) }
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

    private fun readSnapshot(path: File, maximum: Int, allowTwoLinks: Boolean = false, force: Boolean = false): Snapshot {
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
            val mark = Mark(before, digest(requireNotNull(bytes)).useWiped(::encodeHex))
            closeDescriptor(descriptor)
            return Snapshot(mark, requireNotNull(bytes)).also { bytes = null }
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

    /** Only invoked by watched owned disposal/close. Unknown close is NEVER retried. */
    fun closeNative() {
        // Acceptance changes first. No OS lock/key/descriptor/process lease can be released
        // while the backend still owns a real external physical loan, including uncertainty.
        work.prepareNativeClose()
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED) { work.nativeClosureUnknown(); error("client.journal.close-unknown") }
        phase = Phase.CLOSE_STARTED
        try {
            for (stream in streams.toList().asReversed()) { stream.closeOnce(); streams.remove(stream) }
            lock?.let { held -> known(held.isValid); held.release(); known(!held.isValid); lock = null }
            for (descriptor in descriptors.toList().asReversed()) closeDescriptor(descriptor)
            known(lockChannel?.isOpen != true)
            keyWitness?.fill(0); keyWitnessDigest?.fill(0)
            keyWitness = null; keyWitnessDigest = null; keyStore = null; keySecurity = null
            head = null; headAdmitted = false; recoveredWriteHead = false
            manifestStamp = null; archiveStamps = null; heldDirectories.clear(); ancestors.clear()
            phase = Phase.CLOSED
        } catch (failure: Throwable) {
            work.nativeClosureUnknown()
            throw failure
        }
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

    private class Snapshot(val mark: Mark, val bytes: ByteArray) { fun clear() { bytes.fill(0) } }
    private data class Mark(val stamp: Stamp, val digest: String)
    private data class Stamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int,
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
    private fun archiveName(id: OperationId): String {
        known(ANDROID_CLIENT_UUID_V1.matches(id.value))
        return id.value + ".v1.enc"
    }
    private fun archiveId(name: String): OperationId {
        known(name.length == 43 && name.endsWith(".v1.enc"))
        val id = name.removeSuffix(".v1.enc")
        known(ANDROID_CLIENT_UUID_V1.matches(id))
        return OperationId(id)
    }
    private fun known(value: Boolean) { check(value) { "client.journal.unavailable" } }

    private companion object {
        const val DIRECTORY_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
        const val ENVELOPE_BYTES = 32 // explicit magic + 12-byte provider IV + 16-byte GCM tag
        const val ENVELOPE_MAGIC = 0x41434a31
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** No snapshot/epoch can substitute for this actual same-process retained-resource exclusion. */
internal class AndroidClientJournalProcessLease private constructor(private val journalId: DomainId) {
    private var retainedFile: AndroidClientJournalFile? = null
    private var retainedWork: AndroidClientJournalWork? = null
    fun retain(file: AndroidClientJournalFile, work: AndroidClientJournalWork) = synchronized(gate) {
        check(current[journalId] === this && retainedFile == null && retainedWork == null)
        retainedFile = file; retainedWork = work
    }
    fun releaseUnused() = synchronized(gate) {
        check(current[journalId] === this && retainedFile == null && retainedWork == null)
        current.remove(journalId); Unit
    }
    fun releaseAfterActualClose() = synchronized(gate) {
        check(current[journalId] === this)
        current.remove(journalId); retainedFile = null; retainedWork = null
    }
    companion object {
        private val gate = Any()
        private val current = mutableMapOf<DomainId, AndroidClientJournalProcessLease>()
        fun acquire(journalId: DomainId): AndroidClientJournalProcessLease? = synchronized(gate) {
            if (journalId in current) null else AndroidClientJournalProcessLease(journalId).also { current[journalId] = it }
        }
    }
}

/**
 * One admitted owned task, no mutable-input waiter queue. Cancellation abandons delivery, not
 * disk work. The independently scheduled watchdog denies access on overrun, retaining the
 * operation, native resources and process lease. It NEVER kills the cohost/master process.
 * Native-close uncertainty leaves the close result exceptional and exclusion retained.
 */
internal class AndroidClientJournalWork(private val lease: AndroidClientJournalProcessLease) {
    private val gate = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pid = Process.myPid()
    private val uid = Process.myUid()
    private val lastElapsed = AtomicLong(SystemClock.elapsedRealtime())
    private var accepting = true
    private var closeUnknown = false
    private var closed = false
    private var active: Invocation<*>? = null
    private var segment: Segment? = null
    private var closeResult: CompletableDeferred<Unit>? = null
    private val successfulOwner = AtomicReference<AndroidLocalClientJournalOwner?>(null)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ukkera-client-journal-watchdog").apply { isDaemon = true }
    }

    init {
        try {
            check(pid > 0 && uid >= 0 && lastElapsed.get() >= 0)
            watchdog.scheduleWithFixedDelay({
                try {
                    val timedOut = synchronized(gate) { segment?.let { clock() >= it.deadline } == true }
                    if (timedOut) retireAccess()
                } catch (_: Throwable) { retireAccess() }
            }, 100L, 100L, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) { watchdog.shutdownNow(); scope.cancel(); throw failure }
    }

    private fun clock(): Long {
        check(Process.myPid() == pid && Process.myUid() == uid)
        repeat(8) {
            val previous = lastElapsed.get()
            val now = SystemClock.elapsedRealtime()
            check(now >= 0 && now >= previous)
            if (lastElapsed.compareAndSet(previous, now)) return now
        }
        error("client.journal.clock")
    }

    fun requireActive() {
        val usable = synchronized(gate) {
            accepting && !closed && !closeUnknown && segment?.let { clock() < it.deadline } == true
        }
        if (!usable) retireAccess()
        check(usable) { "client.journal.retired" }
    }

    private fun arm(token: Any) = synchronized(gate) {
        check(!closed && !closeUnknown)
        val now = clock()
        check(now <= Long.MAX_VALUE - NATIVE_SEGMENT_MILLIS)
        segment = Segment(token, now + NATIVE_SEGMENT_MILLIS)
    }

    private fun disarm(token: Any) = synchronized(gate) {
        if (!closeUnknown && segment?.token === token) segment = null
    }

    fun attachSuccessfulOwner(owner: AndroidLocalClientJournalOwner) {
        check(successfulOwner.compareAndSet(null, owner) || successfulOwner.get() === owner)
        // A racing retirement first changed acceptance, then samples this reference.
        // Attachment independently notices retirement even if it missed the earlier sample.
        if (synchronized(gate) { !accepting || closed || closeUnknown }) owner.denyPhysical()
    }

    fun isCurrentOwner(owner: AndroidLocalClientJournalOwner): Boolean {
        if (successfulOwner.get() !== owner) return false
        val usable = try { synchronized(gate) {
            accepting && !closed && !closeUnknown && Process.myPid() == pid && Process.myUid() == uid &&
                (segment?.let { clock() < it.deadline } ?: true)
        } } catch (_: Throwable) { false }
        if (!usable) retireAccess()
        return usable && !owner.physicalDenied
    }

    private fun retireAccess() {
        val invocation = synchronized(gate) { accepting = false; active }
        successfulOwner.get()?.denyPhysical()
        invocation?.abandon(postRefusal = true)
    }

    fun prepareNativeClose() {
        synchronized(gate) { accepting = false }
        val owner = successfulOwner.get()
        owner?.denyPhysical()
        check(owner?.physicalLoansDrained != false) { "client.journal.physical-custody-held" }
    }

    fun nativeClosureUnknown() {
        synchronized(gate) { closeUnknown = true; accepting = false }
        retireAccess()
    }

    fun retireView(): CompletableDeferred<Unit> {
        val invocation = synchronized(gate) { accepting = false; active }
        successfulOwner.get()?.denyPhysical()
        invocation?.abandon(postRefusal = true)
        return invocation?.finished ?: CompletableDeferred(Unit)
    }

    suspend fun <T> run(prepare: () -> AndroidClientJournalTask<T>): AndroidClientJournalOutcome<T> {
        currentCoroutineContext().ensureActive()
        val invocation: Invocation<T>
        synchronized(gate) {
            if (!accepting || closed || closeUnknown || active != null) return AndroidClientJournalOutcome.Unavailable
            // Copy bounded private input only after winning this admission slot.
            val task = try { prepare() }
            catch (_: Exception) { return AndroidClientJournalOutcome.Unavailable }
            catch (_: Throwable) { retireAccess(); return AndroidClientJournalOutcome.Unavailable }
            invocation = try { Invocation(task) }
            catch (_: Throwable) {
                try { task.clearInputs() } finally { retireAccess() }
                return AndroidClientJournalOutcome.Unavailable
            }
            active = invocation
            try { arm(invocation) } catch (_: Throwable) {
                accepting = false
                try { task.clearInputs() } finally { active = null; successfulOwner.get()?.denyPhysical() }
                return AndroidClientJournalOutcome.Unavailable
            }
        }
        var claimed = false
        try {
            val result = suspendCancellableCoroutine<AndroidClientJournalOutcome<T>> { continuation ->
                invocation.continuation = continuation
                continuation.invokeOnCancellation { invocation.abandon(postRefusal = false) }
                scope.launch { invocation.execute() }
            }
            currentCoroutineContext().ensureActive()
            claimed = invocation.claim()
            return if (claimed || result is AndroidClientJournalOutcome.Unavailable) result
                else AndroidClientJournalOutcome.Unavailable
        } finally { if (!claimed) invocation.abandon(postRefusal = false) }
    }

    fun requestClose(closeNative: () -> Unit): CompletableDeferred<Unit> {
        val result: CompletableDeferred<Unit>
        val invocation: Invocation<*>?
        synchronized(gate) {
            closeResult?.let { return it }
            accepting = false
            result = CompletableDeferred()
            closeResult = result
            invocation = active
        }
        successfulOwner.get()?.denyPhysical()
        invocation?.abandon(postRefusal = true)
        scope.launch {
            val token = Any()
            try {
                invocation?.finished?.await()
                // Waiting on memory loans is not a split native acquisition or a renewed
                // authorization segment. Real close begins only after the captured borrowers
                // have certainly released; unknown cleanup keeps every original resource held.
                successfulOwner.get()?.awaitPhysicalDrain()
                arm(token)
                closeNative()
                synchronized(gate) {
                    val watched = segment
                    check(!closeUnknown && watched?.token === token && clock() < watched.deadline)
                    lease.releaseAfterActualClose()
                    closed = true
                    segment = null
                }
                result.complete(Unit)
                watchdog.shutdown()
                scope.cancel()
            } catch (_: Throwable) {
                nativeClosureUnknown()
                result.completeExceptionally(IllegalStateException("Client journal native close remains unconfirmed"))
                // Keep the watchdog/scope/resource lease after any uncertain physical close.
            }
        }
        return result
    }

    private inner class Invocation<T>(private val task: AndroidClientJournalTask<T>) {
        val finished = CompletableDeferred<Unit>()
        @Volatile var continuation: CancellableContinuation<AndroidClientJournalOutcome<T>>? = null
        private var result: AndroidClientJournalOutcome<T>? = null
        private var ready = false
        private var abandoned = false
        private var posted = false
        private var disposing = false
        private var done = false

        fun execute() {
            val outcome: AndroidClientJournalOutcome<T> = try {
                requireActive()
                if (synchronized(gate) { abandoned }) AndroidClientJournalOutcome.Unavailable
                else AndroidClientJournalOutcome.Value(task.execute())
            } catch (_: Throwable) {
                retireAccess()
                AndroidClientJournalOutcome.Unavailable
            }
            try { task.clearInputs() } catch (_: Throwable) { nativeClosureUnknown() }
            try { requireActive() } catch (_: Throwable) { synchronized(gate) { abandoned = true } }
            val dispose = synchronized(gate) {
                result = outcome; ready = true
                if (abandoned || !accepting || closeUnknown) { abandoned = true; disposing = true; true }
                else { posted = true; false }
            }
            if (dispose) disposeAndFinish()
            else continuation?.resume(outcome) { _, _, _ -> abandon(postRefusal = false) }
        }

        fun abandon(postRefusal: Boolean) {
            var sendRefusal = false
            val launch = synchronized(gate) {
                if (done) return
                abandoned = true
                if (postRefusal && !posted && continuation != null) { posted = true; sendRefusal = true }
                (ready && !disposing).also { if (it) disposing = true }
            }
            if (sendRefusal) continuation?.resume(AndroidClientJournalOutcome.Unavailable) { _, _, _ -> }
            if (launch) scope.launch { disposeAndFinish() }
        }

        fun claim(): Boolean = synchronized(gate) {
            if (!ready || done || abandoned || disposing || !accepting || closeUnknown) return@synchronized false
            try { requireActive() } catch (_: Throwable) { return@synchronized false }
            done = true
            if (active === this) active = null
            disarm(this)
            finished.complete(Unit)
            true
        }

        private fun disposeAndFinish() {
            try {
                // Disposal is part of the original bounded segment, not an unlimited unowned job.
                val outcome = result
                if (outcome is AndroidClientJournalOutcome.Value) task.discard(outcome.value)
            } catch (_: Throwable) { nativeClosureUnknown() }
            finally {
                val send = synchronized(gate) {
                    val unposted = !posted
                    posted = true; done = true
                    if (active === this) active = null
                    disarm(this)
                    finished.complete(Unit)
                    unposted
                }
                if (send) continuation?.resume(AndroidClientJournalOutcome.Unavailable) { _, _, _ -> }
            }
        }
    }

    private class Segment(val token: Any, val deadline: Long)
    private companion object { const val NATIVE_SEGMENT_MILLIS = 120_000L }
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

    fun requireCurrent(work: AndroidClientJournalWork) {
        work.requireActive()
        check(observe(application) == observation) { "client.journal.application-changed" }
        work.requireActive()
    }

    private data class Observation(val uid: Int, val targetDigest: String, val volumeUuid: String,
        val boot: Int, val dataPath: String, val sourcePath: String, val version: Long, val updated: Long)

    companion object {
        fun capture(application: Application, work: AndroidClientJournalWork): AndroidClientJournalEnvironment {
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

internal val ANDROID_CLIENT_UUID_V1 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * Bounded retained-mark engineering profile. Full census refuses excess; nothing is truncated
 * or pruned. The intent adapter reserves one archive slot per active operation BEFORE dispatch.
 * This count bound is NOT a disk-space reservation or a replacement for native enrollment and
 * cross-restart rollback admission. An archive/capacity successor needs its own qualified join.
 */
internal const val ANDROID_CLIENT_MAX_ARCHIVES_V1: Int = 65_536
