package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.AndroidEmployeeClientCustody
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.SelfOwnedMasterHostOwner
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.NativeEmployeeJournalAllocation
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DESKTOP_ORIGINAL_ID_DOMAIN = "ukkera.desktop.original-client-enrollment/v1"

/** Desktop Master-service original cohost binding: first actor only, same coordinates as Android, desktop domains. */
internal class DesktopOriginalCohostBinding(val original: AndroidOriginalLocalClient) : ClientJournalBindingFacts {
    private val training: Boolean get() = original.compositionMode == LocalSetupCompositionMode.TRAINING
    override fun isCurrent(): Boolean = original.isCurrent()
    override fun permitsEnrollment(): Boolean = original.permitsFirstEnrollment()
    override val rootName: String get() = if (training) "ukkera-desktop-master-client-journals-training-v1"
        else "ukkera-desktop-master-client-journals-v1"
    override val keyAliasNamespace: String get() = if (training) "com.ukkera.pos.desktop-master.client-journal.training.v1/"
        else "com.ukkera.pos.desktop-master.client-journal.v1/"
    override val headAliasNamespace: String get() = if (training) "com.ukkera.pos.desktop-master.client-journal-head.training.v1/"
        else "com.ukkera.pos.desktop-master.client-journal-head.v1/"
    override val aeadDomain: String get() = "ukkera.desktop.master-client-journal-aead/v1"
    override val headDigestDomain: String get() = "ukkera.desktop.master-client-journal-head/v1"
    override val fixedKind: ClientJournalKind? get() = null
    override val employeeEnrollmentCommitment: String? get() = null
    override val pairedDeviceEnrollmentCommitment: String? get() = null
    override val pairedActorEnrollmentCommitment: String? get() = null
    override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
        protection: SelfOwnedKeyProtectionV1) {
        val c = original.material.coordinates
        val kind = if (actor == null) ClientJournalKind.CREDENTIALS else ClientJournalKind.INTENTS
        check(owner == LocalClientDeviceIdentity(c.scope, c.deviceId) && (actor == null || actor == c.firstActorId) &&
            journalId == originalClientJournalId(original, kind, "journal", DESKTOP_ORIGINAL_ID_DOMAIN) &&
            keyId == originalClientJournalId(original, kind, "key", DESKTOP_ORIGINAL_ID_DOMAIN) &&
            protection == original.material.protection)
    }
}

/** Desktop Master-service employee binding: a genuine consumed employee and its exact STARTED/open allocation. */
internal class DesktopEmployeeCohostBinding(val custody: AndroidEmployeeClientCustody, val allocation: NativeEmployeeJournalAllocation) :
    ClientJournalBindingFacts {
    init { check(allocation.belongsTo(custody.native) && allocation.originalOpenClaimed()) }
    private val training: Boolean get() = custody.compositionMode == LocalSetupCompositionMode.TRAINING
    override fun isCurrent(): Boolean = custody.isCurrent() && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed()
    override fun permitsEnrollment(): Boolean = allocation.action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE && isCurrent()
    override val rootName: String get() = if (training) "ukkera-desktop-master-employee-intent-journals-training-v1"
        else "ukkera-desktop-master-employee-intent-journals-v1"
    override val keyAliasNamespace: String get() = if (training) "com.ukkera.pos.desktop-master.employee-intent-journal.training.v1/"
        else "com.ukkera.pos.desktop-master.employee-intent-journal.v1/"
    override val headAliasNamespace: String get() = if (training) "com.ukkera.pos.desktop-master.employee-intent-journal-head.training.v1/"
        else "com.ukkera.pos.desktop-master.employee-intent-journal-head.v1/"
    override val aeadDomain: String get() = "ukkera.desktop.master-employee-intent-journal-aead/v1"
    override val headDigestDomain: String get() = "ukkera.desktop.master-employee-intent-journal-head/v1"
    override val fixedKind: ClientJournalKind? get() = null
    override val employeeEnrollmentCommitment: String = custody.native.copyOriginalJournalBinding().useWiped { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).useWiped(::encodeHex)
    }
    override val pairedDeviceEnrollmentCommitment: String? get() = null
    override val pairedActorEnrollmentCommitment: String? get() = null
    override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
        protection: SelfOwnedKeyProtectionV1) {
        val c = custody.material.coordinates
        check(owner == LocalClientDeviceIdentity(custody.scope, custody.deviceId) && actor == custody.actorId &&
            actor != c.firstActorId && c.scope == custody.scope && c.deviceId == custody.deviceId &&
            journalId == allocation.journalId && keyId == allocation.keyId && journalId != keyId &&
            protection == custody.material.protection && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed())
    }
}

/** Desktop Master-service cohost credential/intent file on the shared engine. */
internal class DesktopCohostJournalFile(
    owner: LocalClientDeviceIdentity, actorId: DomainId?, journalId: DomainId, keyId: DomainId,
    protection: SelfOwnedKeyProtectionV1, work: ClientJournalWork, private val journalBinding: ClientJournalBindingFacts,
    native: DesktopClientJournalNative,
) : ClientJournalFileCore(owner, actorId, journalId, keyId, protection, work, journalBinding, native) {
    init { check(kind != ClientJournalKind.ACTOR_ALLOCATIONS) }

    override fun initialManifest(): ByteArray = when (kind) {
        ClientJournalKind.CREDENTIALS -> AndroidEncryptedClientCredentialJournal.originalEmptyManifest(this)
        ClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.originalEmptyManifest(this)
        ClientJournalKind.ACTOR_ALLOCATIONS -> error("client.cohost.desktop.kind")
    }

    fun validateExisting() = when (kind) {
        ClientJournalKind.CREDENTIALS -> AndroidEncryptedClientCredentialJournal.validateExisting(this)
        ClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.validateExisting(this)
        ClientJournalKind.ACTOR_ALLOCATIONS -> error("client.cohost.desktop.kind")
    }

    fun attach(facet: ClientJournalPhysicalOwner) { check(headAdmitted); work.requireActive(); work.attachSuccessfulOwner(facet) }
    fun isCurrentOwner(facet: ClientJournalPhysicalOwner): Boolean = work.isCurrentOwner(facet)

    /** Positive proof that the FIRST journal completed enrollment and has never held work. */
    fun requireUnusedOriginalCredentials() {
        val original = (journalBinding as? DesktopOriginalCohostBinding)?.original ?: error("client.journal.original-required")
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
 * Successful desktop cohost owner, reached only after full typed validation, native head admission
 * and continuity. Desktop physical recording is unavailable, so it lends no physical custody.
 */
internal class DesktopCohostJournalOwner private constructor(private val file: DesktopCohostJournalFile) {
    val credentialJournal: LocalClientCredentialJournal? =
        if (file.kind == ClientJournalKind.CREDENTIALS) AndroidEncryptedClientCredentialJournal.fromExisting(file) else null
    val intentJournal: LocalClientIntentJournal? =
        if (file.kind == ClientJournalKind.INTENTS) AndroidEncryptedClientIntentJournal.fromExisting(file) else null
    @Volatile private var denied = false
    private val facet = object : ClientJournalPhysicalOwner {
        override fun denyPhysical() { denied = true }
        override val physicalDenied: Boolean get() = denied
        override val physicalLoansDrained: Boolean get() = true
        override suspend fun awaitPhysicalDrain() = Unit
    }
    init { file.attach(facet) }

    fun isCurrent(): Boolean = !denied && file.isCurrentOwner(facet)
    fun deny() { denied = true }
    suspend fun close() { file.closeOwner() }

    suspend fun verifyUnusedOriginalCredentials(): Boolean = file.owned {
        object : ClientJournalTask<Unit> {
            override fun execute() { file.requireUnusedOriginalCredentials() }
            override fun discard(value: Unit) = Unit
            override fun clearInputs() = Unit
        }
    } is ClientJournalOutcome.Value

    override fun toString(): String = "DesktopCohostJournalOwner([REDACTED])"

    companion object {
        /** Coordinates come only from the actual original installation; existing-only opens never create. */
        suspend fun acquireOriginal(host: SelfOwnedMasterHostOwner, original: AndroidOriginalLocalClient, kind: ClientJournalKind,
            enroll: Boolean, recoverWrites: Boolean, storage: DesktopClientJournalStorage): ClientJournalOutcome<DesktopCohostJournalOwner> {
            currentCoroutineContext().ensureActive()
            check(!enroll || !recoverWrites)
            if (kind == ClientJournalKind.ACTOR_ALLOCATIONS || !original.belongsTo(host) ||
                (enroll && !original.permitsFirstEnrollment())) return ClientJournalOutcome.Unavailable
            val c = original.material.coordinates
            val actorId = if (kind == ClientJournalKind.INTENTS) c.firstActorId else null
            return acquireBound(storage, LocalClientDeviceIdentity(c.scope, c.deviceId), actorId,
                originalClientJournalId(original, kind, "journal", DESKTOP_ORIGINAL_ID_DOMAIN),
                originalClientJournalId(original, kind, "key", DESKTOP_ORIGINAL_ID_DOMAIN), original.material.protection,
                DesktopOriginalCohostBinding(original), enroll, recoverWrites) {
                original.isCurrent() && (!enroll || original.permitsFirstEnrollment())
            }
        }

        /** A real consumed employee and exact STARTED/open allocation, never the original first actor. */
        suspend fun acquireEmployee(host: SelfOwnedMasterHostOwner, custody: AndroidEmployeeClientCustody,
            allocation: NativeEmployeeJournalAllocation, storage: DesktopClientJournalStorage): ClientJournalOutcome<DesktopCohostJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(host) || !allocation.belongsTo(custody.native)) return ClientJournalOutcome.Unavailable
            val c = custody.material.coordinates
            if (custody.actorId == c.firstActorId || c.scope != custody.scope || c.deviceId != custody.deviceId)
                return ClientJournalOutcome.Unavailable
            // The actual native owner claims this ONE open before any client key/file/native operation.
            allocation.claimOriginalJournalOpen()
            if (!allocation.belongsTo(custody.native) || !allocation.originalOpenClaimed()) return ClientJournalOutcome.Unavailable
            return acquireBound(storage, LocalClientDeviceIdentity(custody.scope, custody.deviceId), custody.actorId,
                allocation.journalId, allocation.keyId, custody.material.protection, DesktopEmployeeCohostBinding(custody, allocation),
                allocation.action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE,
                allocation.action == NativeEmployeeClientJournalAction.RECOVER_PENDING_WRITES) {
                custody.isCurrent() && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed()
            }
        }

        private suspend fun acquireBound(storage: DesktopClientJournalStorage, deviceOwner: LocalClientDeviceIdentity,
            actorId: DomainId?, journalId: DomainId, keyId: DomainId, protection: SelfOwnedKeyProtectionV1,
            binding: ClientJournalBindingFacts, enroll: Boolean, recoverWrites: Boolean,
            stillCurrent: () -> Boolean): ClientJournalOutcome<DesktopCohostJournalOwner> {
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId, deviceOwner.scope.branchId,
                deviceOwner.deviceId, journalId, keyId) + listOfNotNull(actorId)
            if (ids.any { !CLIENT_JOURNAL_UUID_V1.matches(it.value) } || journalId == keyId || !binding.isCurrent())
                return ClientJournalOutcome.Unavailable
            val lease = ClientJournalProcessLease.acquire(journalId) ?: return ClientJournalOutcome.Unavailable
            val work = try { ClientJournalWork(lease, JvmClientJournalProcess()) } catch (_: Throwable) {
                lease.releaseUnused(); return ClientJournalOutcome.Unavailable
            }
            val file = try {
                DesktopCohostJournalFile(deviceOwner, actorId, journalId, keyId, protection, work, binding,
                    DesktopClientJournalNative(storage.parent, work, storage.keys, storage.facts))
            } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return ClientJournalOutcome.Unavailable
            }
            lease.retain(file, work) // Retain the partial real owner before any native IO.
            var delivered = false
            try {
                val result = work.run {
                    object : ClientJournalTask<DesktopCohostJournalOwner> {
                        override fun execute(): DesktopCohostJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            file.validateExisting()
                            file.admitNativeHead(enroll)
                            file.assertContinuity()
                            check(stillCurrent())
                            return DesktopCohostJournalOwner(file)
                        }
                        override fun discard(value: DesktopCohostJournalOwner) { file.closeNative() }
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
 * First cohost of the desktop Master service: its one device credential journal and the first
 * employee's intent journal, both admitted before any gateway is published. Same actions and
 * no-create-on-lookup rules as Android ([AndroidOriginalClientJournals]).
 */
internal class DesktopOriginalClientJournals private constructor(
    private val original: AndroidOriginalLocalClient,
    private val credentialsOwner: DesktopCohostJournalOwner,
    private val intentsOwner: DesktopCohostJournalOwner,
) : LocalClientIntentJournalSource {
    val credentials: LocalClientCredentialJournal = checkNotNull(credentialsOwner.credentialJournal)
    private val intents = checkNotNull(intentsOwner.intentJournal)
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)
    fun belongsTo(original: AndroidOriginalLocalClient): Boolean = this.original === original && closure.get() == null

    override suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal? =
        intents.takeIf { closure.get() == null && it.owner == owner }

    suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        var failure: Throwable? = null
        try { intentsOwner.close() } catch (caught: Throwable) { failure = caught }
        try { credentialsOwner.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        val problem = failure
        if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
        result.await()
    }

    override fun toString(): String = "DesktopOriginalClientJournals([REDACTED])"

    companion object {
        suspend fun acquire(host: SelfOwnedMasterHostOwner, original: AndroidOriginalLocalClient,
            action: LocalOriginalClientJournalAction, storage: DesktopClientJournalStorage): DesktopOriginalClientJournals? {
            currentCoroutineContext().ensureActive()
            val firstEnrollment = action == LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT
            val continuation = action == LocalOriginalClientJournalAction.CONTINUE_FIRST_CLIENT
            val recoverWrites = action == LocalOriginalClientJournalAction.RECOVER_PENDING_WRITES
            val requiresPristineMaster = firstEnrollment || continuation
            if (!original.belongsTo(host) || (requiresPristineMaster && !original.permitsFirstEnrollment())) return null
            var credentials: DesktopCohostJournalOwner? = null
            var intents: DesktopCohostJournalOwner? = null
            var delivered = false
            try {
                val first = DesktopCohostJournalOwner.acquireOriginal(host, original, ClientJournalKind.CREDENTIALS,
                    firstEnrollment, recoverWrites, storage)
                if (first !is ClientJournalOutcome.Value) return null
                credentials = first.value
                if (continuation && !first.value.verifyUnusedOriginalCredentials()) return null
                val second = DesktopCohostJournalOwner.acquireOriginal(host, original, ClientJournalKind.INTENTS,
                    requiresPristineMaster, recoverWrites, storage)
                if (second !is ClientJournalOutcome.Value) return null
                intents = second.value
                currentCoroutineContext().ensureActive()
                if (!original.isCurrent() || (requiresPristineMaster && !original.permitsFirstEnrollment()) ||
                    (continuation && !first.value.verifyUnusedOriginalCredentials())) return null
                return DesktopOriginalClientJournals(original, first.value, second.value).also { delivered = true }
            } finally {
                if (!delivered) withContext(NonCancellable) {
                    try { intents?.close() } finally { credentials?.close() }
                }
            }
        }
    }
}

/**
 * Aggregate of the desktop Master service's privately delivered genuine cohost owners. All
 * employees reuse the original device credential owner; only their intent lease is new. Desktop
 * physical recording is unavailable: no employee physical participation is ever admitted.
 */
internal class DesktopSameCohostClientJournals(
    private val host: SelfOwnedMasterHostOwner,
    private val originalClient: AndroidOriginalLocalClient,
    private val original: DesktopOriginalClientJournals,
    private val storage: DesktopClientJournalStorage,
) : SameCohostClientJournals {
    init { check(original.belongsTo(originalClient) && originalClient.belongsTo(host)) }
    override val credentials: LocalClientCredentialJournal get() = original.credentials
    private class EmployeeOwner(val custody: AndroidEmployeeClientCustody, val allocation: NativeEmployeeJournalAllocation,
        val native: DesktopCohostJournalOwner) {
        val intents: LocalClientIntentJournal = checkNotNull(native.intentJournal)
        fun current(): Boolean = native.isCurrent() && custody.isCurrent() && allocation.belongsTo(custody.native) &&
            allocation.originalOpenClaimed()
    }
    private val employees = AtomicReference<List<EmployeeOwner>>(emptyList())
    private val acquiring = Mutex()
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)

    override suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal? {
        if (closure.get() != null) return null
        original.current(owner)?.let { return it.takeIf { closure.get() == null } }
        return employees.get().singleOrNull { it.intents.owner == owner && it.current() }?.intents
            ?.takeIf { closure.get() == null }
    }

    override suspend fun acquireEmployee(custody: AndroidEmployeeClientCustody, action: NativeEmployeeClientJournalAction): Boolean =
        acquiring.withLock {
            currentCoroutineContext().ensureActive()
            if (closure.get() != null || !original.belongsTo(originalClient) || !custody.belongsTo(originalClient) ||
                !custody.belongsTo(host)) return@withLock false
            val owner = LocalClientIdentity(custody.scope, custody.actorId, custody.deviceId)
            employees.get().singleOrNull { it.intents.owner == owner }?.let {
                return@withLock it.custody === custody && it.current()
            }
            if (employees.get().size >= MAX_CLIENT_ENROLLMENTS || original.current(owner) != null) return@withLock false
            val allocation = when (val selected = custody.acquireJournalAction(action)) {
                is LocalCompanyAuthorityResult.Value -> selected.value
                is LocalCompanyAuthorityResult.Unavailable -> return@withLock false
            }
            var native: DesktopCohostJournalOwner? = null
            var delivered = false
            try {
                val acquired = DesktopCohostJournalOwner.acquireEmployee(host, custody, allocation, storage)
                if (acquired !is ClientJournalOutcome.Value) return@withLock false
                native = acquired.value
                currentCoroutineContext().ensureActive()
                if (closure.get() != null || !original.belongsTo(originalClient) || !custody.belongsTo(originalClient) ||
                    !allocation.belongsTo(custody.native) || !allocation.originalOpenClaimed()) return@withLock false
                val entry = EmployeeOwner(custody, allocation, acquired.value)
                check(entry.intents.owner == owner && acquired.value.credentialJournal == null)
                employees.set(employees.get() + entry)
                delivered = true
                true
            } finally {
                if (!delivered) withContext(NonCancellable) { native?.close() }
            }
        }

    /** Desktop has no native physical journal gate; physical participation stays unavailable. */
    override suspend fun admitEmployeePhysical(custody: AndroidEmployeeClientCustody): Boolean = false

    override suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        employees.get().forEach { it.native.deny() }
        acquiring.withLock {
            var failure: Throwable? = null
            for (employee in employees.get().asReversed()) {
                try { employee.native.close() } catch (caught: Throwable) {
                    if (failure == null) failure = caught else checkNotNull(failure).addSuppressed(caught)
                }
            }
            try { original.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else checkNotNull(failure).addSuppressed(caught)
            }
            val problem = failure
            if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
        }
        result.await()
    }

    override fun toString(): String = "DesktopSameCohostClientJournals([REDACTED])"
}

/**
 * Desktop Master-service same-device original client: the shared [LocalCohostBranchRuntime] over the
 * service's own journal custody. Retail physical recording is explicitly unavailable on desktop.
 */
public class DesktopLocalBranchRuntime private constructor(
    original: AndroidOriginalLocalClient,
    journals: DesktopSameCohostClientJournals,
    publications: com.ukkera.pos.security.LocalCompanyBusinessPublications?,
) : LocalCohostBranchRuntime(original, journals, null, publications) {
    override fun toString(): String = "DesktopLocalBranchRuntime([REDACTED])"

    public companion object {
        /** Called by the retained service platform owner only, never by a serialized body. */
        public suspend fun connectOriginal(host: SelfOwnedMasterHostOwner, master: AndroidSelfOwnedMasterService,
            action: LocalOriginalClientJournalAction, storage: DesktopClientJournalStorage): DesktopLocalBranchRuntime? {
            currentCoroutineContext().ensureActive()
            val original = master.originalLocalClient()
            if (!original.belongsTo(host)) return null
            val publications = original.originalBusinessPublications()
            val acquired = DesktopOriginalClientJournals.acquire(host, original, action, storage) ?: return null
            var candidate: DesktopLocalBranchRuntime? = null
            var delivered = false
            try {
                currentCoroutineContext().ensureActive()
                check(original.isCurrent())
                val journals = DesktopSameCohostClientJournals(host, original, acquired, storage)
                val runtime = DesktopLocalBranchRuntime(original, journals, publications).also { candidate = it }
                check(runtime.isCurrent())
                return runtime.also { delivered = true }
            } finally {
                if (!delivered) withContext(NonCancellable) {
                    val actual = candidate
                    if (actual == null) acquired.close() else actual.close()
                }
            }
        }
    }
}
