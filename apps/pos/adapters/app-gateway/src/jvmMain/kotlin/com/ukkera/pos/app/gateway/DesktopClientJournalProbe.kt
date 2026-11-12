package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.runBlocking

/**
 * Bounded runner check of desktop journal custody on the real OS: a synthetic journal in a separate
 * probe namespace (never a live/training journal) is enrolled, rewritten, reopened existing-only
 * and read back through the SHARED engine. Returns a DATA line; nothing here grants authority.
 */
public object DesktopClientJournalProbe {
    public fun run(storage: DesktopClientJournalStorage): String = try {
        val journalId = DomainId.random(); val keyId = DomainId.random()
        val owner = LocalClientDeviceIdentity(BranchScope(DomainId.random(), DomainId.random(), DomainId.random()), DomainId.random())
        val written = byteArrayOf(4, 2)
        open(storage, owner, journalId, keyId, enroll = true) { file -> file.replaceManifest(written, MAX_CLIENT_CREDENTIAL_BYTES) }
        var readBack: ByteArray? = null
        open(storage, owner, journalId, keyId, enroll = false) { file -> readBack = file.readManifest(MAX_CLIENT_CREDENTIAL_BYTES) }
        if (readBack?.contentEquals(written) == true) "DATA enrolled=true reopened=true" else "MISMATCH"
    } catch (failure: Throwable) { "FAILED ${failure::class.simpleName}: ${failure.message?.take(160)}" }

    private fun open(storage: DesktopClientJournalStorage, owner: LocalClientDeviceIdentity, journalId: DomainId, keyId: DomainId,
        enroll: Boolean, action: (ClientJournalFileCore) -> Unit) = runBlocking {
        val lease = checkNotNull(ClientJournalProcessLease.acquire(journalId))
        val work = ClientJournalWork(lease, JvmClientJournalProcess())
        val file = ProbeFile(owner, journalId, keyId, work, ProbeBinding(enroll), DesktopClientJournalNative(storage.parent, work, storage.keys, storage.facts))
        lease.retain(file, work)
        try {
            val result = work.run { object : ClientJournalTask<Unit> {
                override fun execute() { file.openNative(enroll, false); file.admitNativeHead(enroll); action(file) }
                override fun discard(value: Unit) = Unit
                override fun clearInputs() = Unit
            } }
            check(result is ClientJournalOutcome.Value) { "journal operation unavailable" }
        } finally { file.closeOwner() }
    }

    private class ProbeFile(owner: LocalClientDeviceIdentity, journalId: DomainId, keyId: DomainId, work: ClientJournalWork,
        binding: ClientJournalBindingFacts, native: ClientJournalNative) :
        ClientJournalFileCore(owner, null, journalId, keyId, SelfOwnedKeyProtectionV1.OS_ISOLATED, work, binding, native) {
        override fun initialManifest(): ByteArray = byteArrayOf(1)
    }

    private class ProbeBinding(private val enrollAllowed: Boolean) : ClientJournalBindingFacts {
        override fun isCurrent() = true
        override fun permitsEnrollment() = enrollAllowed
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
            protection: SelfOwnedKeyProtectionV1) = Unit
        override val rootName = "ukkera-probe-journals"
        override val keyAliasNamespace = "ukkera.probe.journal-key/"
        override val headAliasNamespace = "ukkera.probe.journal-head/"
        override val aeadDomain = "ukkera.desktop.probe-journal-aead/v1"
        override val headDigestDomain = "ukkera.desktop.probe-journal-head/v1"
        override val fixedKind: ClientJournalKind? = null
        override val employeeEnrollmentCommitment: String? = null
        override val pairedDeviceEnrollmentCommitment: String? = null
        override val pairedActorEnrollmentCommitment: String? = null
    }
}
