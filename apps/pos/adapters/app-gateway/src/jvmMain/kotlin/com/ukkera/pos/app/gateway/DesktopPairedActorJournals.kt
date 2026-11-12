package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Desktop allocation-ledger binding: same acquisition gate and coordinates as Android, desktop AEAD/head domains. */
internal class DesktopPairedActorAllocationsBinding(val custody: PairedDeviceCredentialJournalCustody) : ClientJournalBindingFacts {
    private val profile = custody.approvedOriginal.masterSdkOriginal.compositionProfile
    init { check(custody.originalActorAllocationOpenClaimed()) }
    override fun isCurrent(): Boolean = custody.isCurrent() && custody.originalActorAllocationOpenClaimed()
    override fun permitsEnrollment(): Boolean = custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL && isCurrent()
    override val fixedKind: ClientJournalKind get() = ClientJournalKind.ACTOR_ALLOCATIONS
    override val rootName: String get() = pairedRoot("ukkera-local-paired-device-actor-allocations-v1", profile)
    override val keyAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-allocation.v1/", profile)
    override val headAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-allocation-head.v1/", profile)
    override val aeadDomain: String get() = "ukkera.desktop.paired-device-actor-allocation-aead/v1"
    override val headDigestDomain: String get() = "ukkera.desktop.paired-device-actor-allocation-head/v1"
    override val employeeEnrollmentCommitment: String? get() = null
    override val pairedDeviceEnrollmentCommitment: String get() = custody.bindingCommitment
    override val pairedActorEnrollmentCommitment: String? get() = null
    override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
        protection: SelfOwnedKeyProtectionV1) {
        check(owner == LocalClientDeviceIdentity(custody.scope, custody.deviceId) && actor == null &&
            journalId == pairedActorAllocationJournalId(custody, "journal") &&
            keyId == pairedActorAllocationJournalId(custody, "key") && journalId != keyId &&
            protection == custody.protection && custody.originalActorAllocationOpenClaimed())
    }
}

/** Desktop actor intent binding: a genuine allocation plus fresh Master Current, never a supplied actor scalar. */
internal class DesktopPairedActorBinding(val custody: PairedDeviceActorJournalCustody) : ClientJournalBindingFacts {
    private val profile = custody.compositionProfile
    init { check(custody.originalOpenClaimed()) }
    override fun isCurrent(): Boolean = custody.isCurrent() && custody.originalOpenClaimed()
    override fun permitsEnrollment(): Boolean = custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL && isCurrent()
    override val fixedKind: ClientJournalKind? get() = null
    override val rootName: String get() = pairedRoot("ukkera-local-paired-device-actor-intent-journals-v1", profile)
    override val keyAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-intent-journal.v1/", profile)
    override val headAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-intent-journal-head.v1/", profile)
    override val aeadDomain: String get() = "ukkera.desktop.paired-device-actor-intent-journal-aead/v1"
    override val headDigestDomain: String get() = "ukkera.desktop.paired-device-actor-intent-journal-head/v1"
    override val employeeEnrollmentCommitment: String? get() = null
    override val pairedDeviceEnrollmentCommitment: String get() = custody.deviceBindingCommitment
    override val pairedActorEnrollmentCommitment: String get() = custody.bindingCommitment
    override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
        protection: SelfOwnedKeyProtectionV1) {
        val identity = custody.identity
        check(owner == LocalClientDeviceIdentity(identity.scope, identity.deviceId) && actor == identity.actorId &&
            journalId == custody.journalId && keyId == custody.keyId && journalId != keyId &&
            protection == custody.protection && custody.originalOpenClaimed())
    }
}

/** Desktop allocation-ledger or actor-intent file on the shared engine. */
internal class DesktopPairedJournalFile(
    owner: LocalClientDeviceIdentity, actorId: DomainId?, journalId: DomainId, keyId: DomainId,
    protection: SelfOwnedKeyProtectionV1, work: ClientJournalWork, binding: ClientJournalBindingFacts,
    native: DesktopClientJournalNative, private val allocationCustody: PairedDeviceCredentialJournalCustody?,
) : ClientJournalFileCore(owner, actorId, journalId, keyId, protection, work, binding, native) {
    init { check((kind == ClientJournalKind.ACTOR_ALLOCATIONS) == (allocationCustody != null) && kind != ClientJournalKind.CREDENTIALS) }

    override fun initialManifest(): ByteArray = when (kind) {
        ClientJournalKind.ACTOR_ALLOCATIONS -> PairedDeviceActorAllocationJournal.originalEmptyManifest(this, checkNotNull(allocationCustody))
        ClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.originalEmptyManifest(this)
        ClientJournalKind.CREDENTIALS -> error("client.paired.desktop.kind")
    }

    fun validateExisting() = when (kind) {
        ClientJournalKind.ACTOR_ALLOCATIONS -> PairedDeviceActorAllocationJournal.validateExisting(this, checkNotNull(allocationCustody))
        ClientJournalKind.INTENTS -> AndroidEncryptedClientIntentJournal.validateExisting(this)
        ClientJournalKind.CREDENTIALS -> error("client.paired.desktop.kind")
    }

    fun ledger(): PairedDeviceActorAllocationJournal? =
        allocationCustody?.let { PairedDeviceActorAllocationJournal.fromExisting(this, it) }
    fun attach(facet: ClientJournalPhysicalOwner) { check(headAdmitted); work.requireActive(); work.attachSuccessfulOwner(facet) }
    fun isCurrentOwner(facet: ClientJournalPhysicalOwner): Boolean = work.isCurrentOwner(facet)
}

/**
 * Successful desktop ledger/actor owner, reached only after full typed validation, native head
 * admission and continuity. It lends no physical custody, so drain is immediate.
 */
internal class DesktopPairedJournalOwner private constructor(private val file: DesktopPairedJournalFile) : PairedClientJournalOwner {
    override val intentJournal: LocalClientIntentJournal? =
        if (file.kind == ClientJournalKind.INTENTS) AndroidEncryptedClientIntentJournal.fromExisting(file) else null
    override val actorAllocationJournal: PairedDeviceActorAllocationJournal? = file.ledger()
    @Volatile private var denied = false
    private val facet = object : ClientJournalPhysicalOwner {
        override fun denyPhysical() { denied = true }
        override val physicalDenied: Boolean get() = denied
        override val physicalLoansDrained: Boolean get() = true
        override suspend fun awaitPhysicalDrain() = Unit
    }
    init {
        file.attach(facet)
        actorAllocationJournal?.attachSuccessfulOwner(this)
    }

    override fun isCurrent(): Boolean = !denied && file.isCurrentOwner(facet)
    override suspend fun close() { file.closeOwner() }
    override fun toString(): String = "DesktopPairedJournalOwner([REDACTED])"

    companion object {
        /** Second once-only pre-transfer claim: allocation metadata only; only ENROLL may create it. */
        suspend fun acquireAllocations(owner: Any, custody: PairedDeviceCredentialJournalCustody,
            storage: DesktopClientJournalStorage): ClientJournalOutcome<DesktopPairedJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(owner) || !custody.isCurrent() ||
                !custody.claimOriginalActorAllocationJournalOpen(owner) || !custody.originalActorAllocationOpenClaimed())
                return ClientJournalOutcome.Unavailable
            return acquireBound(storage, LocalClientDeviceIdentity(custody.scope, custody.deviceId), null,
                pairedActorAllocationJournalId(custody, "journal"), pairedActorAllocationJournalId(custody, "key"),
                custody.protection, DesktopPairedActorAllocationsBinding(custody), custody.action, custody)
        }

        /** Only a forced original allocation with actual fresh Current consumes this exact actor open. */
        suspend fun acquireActor(owner: Any, custody: PairedDeviceActorJournalCustody,
            storage: DesktopClientJournalStorage): ClientJournalOutcome<DesktopPairedJournalOwner> {
            currentCoroutineContext().ensureActive()
            if (!custody.belongsTo(owner) || !custody.isCurrent() ||
                !custody.claimOriginalJournalOpen(owner) || !custody.originalOpenClaimed()) return ClientJournalOutcome.Unavailable
            val identity = custody.identity
            return acquireBound(storage, LocalClientDeviceIdentity(identity.scope, identity.deviceId), identity.actorId,
                custody.journalId, custody.keyId, custody.protection, DesktopPairedActorBinding(custody), custody.action, null)
        }

        private suspend fun acquireBound(storage: DesktopClientJournalStorage, deviceOwner: LocalClientDeviceIdentity,
            actorId: DomainId?, journalId: DomainId, keyId: DomainId, protection: SelfOwnedKeyProtectionV1,
            binding: ClientJournalBindingFacts, action: LocalPairedDeviceCredentialJournalAction,
            allocationCustody: PairedDeviceCredentialJournalCustody?): ClientJournalOutcome<DesktopPairedJournalOwner> {
            val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId, deviceOwner.scope.branchId,
                deviceOwner.deviceId, journalId, keyId) + listOfNotNull(actorId)
            if (ids.any { !CLIENT_JOURNAL_UUID_V1.matches(it.value) } || journalId == keyId || !binding.isCurrent())
                return ClientJournalOutcome.Unavailable
            val lease = ClientJournalProcessLease.acquire(journalId) ?: return ClientJournalOutcome.Unavailable
            val work = try { ClientJournalWork(lease, JvmClientJournalProcess()) } catch (_: Throwable) {
                lease.releaseUnused(); return ClientJournalOutcome.Unavailable
            }
            val file = try {
                DesktopPairedJournalFile(deviceOwner, actorId, journalId, keyId, protection, work, binding,
                    DesktopClientJournalNative(storage.parent, work, storage.keys, storage.facts), allocationCustody)
            } catch (_: Throwable) {
                withContext(NonCancellable) { work.requestClose { /* no native open was attempted */ }.await() }
                return ClientJournalOutcome.Unavailable
            }
            lease.retain(file, work) // Retain the partial real owner before any native IO.
            val enroll = action == LocalPairedDeviceCredentialJournalAction.ENROLL
            val recoverWrites = action == LocalPairedDeviceCredentialJournalAction.RECOVER
            var delivered = false
            try {
                val result = work.run {
                    object : ClientJournalTask<DesktopPairedJournalOwner> {
                        override fun execute(): DesktopPairedJournalOwner {
                            file.openNative(enroll, recoverWrites)
                            file.validateExisting()
                            file.admitNativeHead(enroll)
                            file.assertContinuity()
                            check(binding.isCurrent())
                            return DesktopPairedJournalOwner(file)
                        }
                        override fun discard(value: DesktopPairedJournalOwner) { file.closeNative() }
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
