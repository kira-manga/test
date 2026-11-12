package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Desktop platform custody for client journals: fixed application-private parent, OS keys, installed facts. */
public class DesktopClientJournalStorage internal constructor(
    internal val parent: Path,
    internal val keys: DesktopJournalKeys,
    internal val facts: DesktopJournalPlatformFacts,
)

/** Same acquisition-only gate and coordinates as the Android paired-device binding; desktop AEAD/head domains. */
internal class DesktopPairedDeviceBinding(private val custody: PairedDeviceCredentialJournalCustody) : ClientJournalBindingFacts {
    private val profile = custody.approvedOriginal.masterSdkOriginal.compositionProfile
    init { check(custody.originalOpenClaimed()) }
    override fun isCurrent(): Boolean = custody.isCurrent() && custody.originalOpenClaimed()
    override fun permitsEnrollment(): Boolean = custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL && isCurrent()
    override val rootName: String get() = pairedRoot("ukkera-local-paired-device-credential-journals-v1", profile)
    override val keyAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-credential-journal.v1/", profile)
    override val headAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-credential-journal-head.v1/", profile)
    override val aeadDomain: String get() = "ukkera.desktop.paired-device-credential-journal-aead/v1"
    override val headDigestDomain: String get() = "ukkera.desktop.paired-device-credential-journal-head/v1"
    override val fixedKind: ClientJournalKind? get() = null
    override val employeeEnrollmentCommitment: String? get() = null
    override val pairedDeviceEnrollmentCommitment: String get() = custody.bindingCommitment
    override val pairedActorEnrollmentCommitment: String? get() = null
    override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
        protection: SelfOwnedKeyProtectionV1) {
        check(owner == LocalClientDeviceIdentity(custody.scope, custody.deviceId) && actor == null &&
            journalId == custody.journalId && keyId == custody.keyId && journalId != keyId &&
            protection == custody.protection && custody.originalOpenClaimed())
    }
}

internal class DesktopPairedDeviceCredentialFile(
    owner: LocalClientDeviceIdentity, journalId: DomainId, keyId: DomainId, protection: SelfOwnedKeyProtectionV1,
    work: ClientJournalWork, binding: DesktopPairedDeviceBinding, native: DesktopClientJournalNative,
) : ClientJournalFileCore(owner, null, journalId, keyId, protection, work, binding, native) {
    override fun initialManifest(): ByteArray = AndroidEncryptedClientCredentialJournal.pairedDeviceEmptyManifest(this)
    fun attach(facet: ClientJournalPhysicalOwner) { check(headAdmitted); work.requireActive(); work.attachSuccessfulOwner(facet) }
    fun isCurrentOwner(facet: ClientJournalPhysicalOwner): Boolean = work.isCurrentOwner(facet)
}

public sealed interface DesktopPairedDeviceClientCredentialsResult {
    public class Opened internal constructor(public val credentials: DesktopPairedDeviceClientCredentials) :
        DesktopPairedDeviceClientCredentialsResult
    public data object Unavailable : DesktopPairedDeviceClientCredentialsResult
}

/**
 * Root-owned desktop device credential journal for the exact approved original additional device
 * (credentials-only route, like the Android acquire route). Its key/head/file owner is acquired
 * BEFORE the target is transferred to RPC and survives that source's denial/close.
 */
public class DesktopPairedDeviceClientCredentials private constructor(
    private val file: DesktopPairedDeviceCredentialFile,
    private val custody: PairedDeviceCredentialJournalCustody,
) {
    public val credentials: LocalClientCredentialJournal = AndroidEncryptedClientCredentialJournal.fromExisting(file)
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)
    @Volatile private var denied = false
    /** Credentials-only owner: it never lends physical custody, so drain is immediate. */
    private val facet = object : ClientJournalPhysicalOwner {
        override fun denyPhysical() { denied = true }
        override val physicalDenied: Boolean get() = denied
        override val physicalLoansDrained: Boolean get() = true
        override suspend fun awaitPhysicalDrain() = Unit
    }
    init { file.attach(facet) }

    public fun isCurrent(): Boolean = closure.get() == null && !denied && file.isCurrentOwner(facet)

    public suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        var failure: Throwable? = null
        try { file.closeOwner() } catch (caught: Throwable) { failure = caught }
        try { custody.close() } catch (caught: Throwable) { failure?.addSuppressed(caught) ?: run { failure = caught } }
        failure?.let { result.completeExceptionally(it) } ?: result.complete(Unit)
        result.await()
    }

    override fun toString(): String = "DesktopPairedDeviceClientCredentials([REDACTED])"

    public companion object {
        /** The one original claim precedes key/file IO; existing-only actions never enroll missing state. */
        public suspend fun acquire(owner: Any, custody: PairedDeviceCredentialJournalCustody,
            storage: DesktopClientJournalStorage): DesktopPairedDeviceClientCredentialsResult {
            currentCoroutineContext().ensureActive()
            var delivered = false
            try {
                if (!custody.belongsTo(owner) || !custody.isCurrent()) return DesktopPairedDeviceClientCredentialsResult.Unavailable
                if (!custody.claimOriginalJournalOpen(owner) || !custody.originalOpenClaimed())
                    return DesktopPairedDeviceClientCredentialsResult.Unavailable
                val deviceOwner = LocalClientDeviceIdentity(custody.scope, custody.deviceId)
                val ids = listOf(deviceOwner.scope.organizationId, deviceOwner.scope.companyId, deviceOwner.scope.branchId,
                    deviceOwner.deviceId, custody.journalId, custody.keyId)
                if (ids.any { !CLIENT_JOURNAL_UUID_V1.matches(it.value) } || custody.journalId == custody.keyId)
                    return DesktopPairedDeviceClientCredentialsResult.Unavailable
                val enroll = custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL
                val recoverWrites = custody.action == LocalPairedDeviceCredentialJournalAction.RECOVER
                val lease = ClientJournalProcessLease.acquire(custody.journalId) ?: return DesktopPairedDeviceClientCredentialsResult.Unavailable
                val work = try { ClientJournalWork(lease, JvmClientJournalProcess()) } catch (_: Throwable) {
                    lease.releaseUnused(); return DesktopPairedDeviceClientCredentialsResult.Unavailable
                }
                val file = try {
                    DesktopPairedDeviceCredentialFile(deviceOwner, custody.journalId, custody.keyId, custody.protection, work,
                        DesktopPairedDeviceBinding(custody), DesktopClientJournalNative(storage.parent, work, storage.keys, storage.facts))
                } catch (_: Throwable) {
                    withContext(NonCancellable) { work.requestClose { }.await() }
                    return DesktopPairedDeviceClientCredentialsResult.Unavailable
                }
                lease.retain(file, work) // Retain the partial real owner before any native IO.
                var opened = false
                try {
                    val result = work.run {
                        object : ClientJournalTask<Unit> {
                            override fun execute() {
                                file.openNative(enroll, recoverWrites)
                                check(file.kind == ClientJournalKind.CREDENTIALS && file.actorId == null)
                                AndroidEncryptedClientCredentialJournal.validateExisting(file)
                                file.admitNativeHead(enroll)
                                file.assertContinuity()
                                check(custody.isCurrent() && custody.originalOpenClaimed())
                            }
                            override fun discard(value: Unit) = file.closeNative()
                            override fun clearInputs() = Unit
                        }
                    }
                    if (result !is ClientJournalOutcome.Value || !custody.finishNativeHandoff(owner))
                        return DesktopPairedDeviceClientCredentialsResult.Unavailable
                    val credentials = DesktopPairedDeviceClientCredentials(file, custody)
                    opened = true
                    if (!credentials.isCurrent()) { credentials.close(); return DesktopPairedDeviceClientCredentialsResult.Unavailable }
                    delivered = true
                    return DesktopPairedDeviceClientCredentialsResult.Opened(credentials)
                } finally {
                    if (!opened) withContext(NonCancellable) { work.requestClose { file.closeNative() }.await() }
                }
            } finally {
                if (!delivered) withContext(NonCancellable) { try { custody.close() } catch (_: Throwable) { } }
            }
        }
    }
}
