package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** New native purpose derived only from an already admitted actual actor journal owner. */
internal class ClientReadViewPurpose(
    private val parent: ClientJournalFileCore,
    private val parentBinding: ClientJournalBindingFacts,
    val protection: SelfOwnedKeyProtectionV1,
    val newWork: (ClientJournalProcessLease) -> ClientJournalWork,
    val newNative: (ClientJournalWork) -> ClientJournalNative,
) : ClientJournalBindingFacts {
    val owner = LocalClientIdentity(parent.deviceOwner.scope, checkNotNull(parent.actorId), parent.deviceOwner.deviceId)
    val journalId: DomainId = id("journal")
    val keyId: DomainId = id("key")
    override val fixedKind: ClientJournalKind get() = ClientJournalKind.READ_VIEWS_V1
    override val rootName: String get() = parentBinding.rootName + "-read-views-v1"
    override val keyAliasNamespace: String get() = parentBinding.keyAliasNamespace + "read-views.v1/"
    override val headAliasNamespace: String get() = parentBinding.headAliasNamespace + "read-views.v1/"
    override val aeadDomain: String get() = parentBinding.aeadDomain + "/read-views/v1"
    override val headDigestDomain: String get() = parentBinding.headDigestDomain + "/read-views/v1"
    override val employeeEnrollmentCommitment: String? get() = parentBinding.employeeEnrollmentCommitment
    override val pairedDeviceEnrollmentCommitment: String? get() = parentBinding.pairedDeviceEnrollmentCommitment
    override val pairedActorEnrollmentCommitment: String? get() = parentBinding.pairedActorEnrollmentCommitment
    override fun isCurrent(): Boolean = parent.readViewParentCurrent()
    fun belongsTo(owner: ClientJournalFileCore): Boolean = parent === owner
    val dataKeyAlias: String get() = keyAliasNamespace + journalId.value + "/" + keyId.value
    val headKeyPrefix: String get() = headAliasNamespace + journalId.value + "/" + keyId.value + "/"
    // Enrollment is explicit for this disposable NEW purpose, after the real parent opened.
    // It cannot enroll, repair or reopen the intent/credential purpose.
    override fun permitsEnrollment(): Boolean = isCurrent()
    override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
        keyId: DomainId, protection: SelfOwnedKeyProtectionV1) {
        check(owner == parent.deviceOwner && actor == parent.actorId && journalId == this.journalId &&
            keyId == this.keyId && protection == this.protection && isCurrent())
    }

    private fun id(purpose: String): DomainId = listOf("ukkera.client.read-view-purpose/v1", parent.journalId.value,
        parent.keyId.value, parent.deviceOwner.scope.organizationId.value, parent.deviceOwner.scope.companyId.value,
        parent.deviceOwner.scope.branchId.value, parent.deviceOwner.deviceId.value,
        checkNotNull(parent.actorId).value, purpose).joinToString("\n").encodeToByteArray().useWiped { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).useWiped { hash ->
            val hex = encodeHex(hash).take(32)
            DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
        }
    }

    companion object {
        const val MAX_QUARANTINES = 4
        const val MAX_RESET_HEAD_KEYS = 256
    }
}

@Serializable
private data class ClientReadViewManifestV1(
    val format: String,
    val owner: LocalClientIdentity,
    val batch: StoredClientReadViewBatchV1?,
)

private class ClientReadViewFile(
    val purpose: ClientReadViewPurpose,
    work: ClientJournalWork,
    private val platform: ClientJournalNative,
) : ClientJournalFileCore(LocalClientDeviceIdentity(purpose.owner.scope, purpose.owner.deviceId), purpose.owner.actorId,
    purpose.journalId, purpose.keyId, purpose.protection, work, purpose, platform) {
    var settledNamespaceProven: Boolean = false
        private set
    private val ownerFacet = object : ClientJournalPhysicalOwner {
        @Volatile private var denied = false
        override fun denyPhysical() { denied = true }
        override val physicalDenied: Boolean get() = denied
        override val physicalLoansDrained: Boolean get() = true
        override suspend fun awaitPhysicalDrain() = Unit
    }

    fun open() {
        check(purpose.isCurrent())
        val enroll = !platform.readViewPurposeExists(purpose.rootName, purpose.journalId)
        openNative(enroll, recoverWrites = !enroll, onSettledNamespace = { settledNamespaceProven = true })
        readBatch() // Full typed validation before successful-owner/head admission.
        admitNativeHead(enroll)
        assertContinuity()
        check(purpose.isCurrent())
        work.attachSuccessfulOwner(ownerFacet)
        settledNamespaceProven = true // Actual exact-write recovery and complete open also settle it.
    }

    override fun initialManifest(): ByteArray = encode(null)

    fun readBatch(): StoredClientReadViewBatchV1? {
        check(purpose.isCurrent())
        return readManifest(MAX_CLIENT_READ_VIEW_BYTES_V1).useWiped { bytes ->
            val manifest = journalJson.decodeFromString(ClientReadViewManifestV1.serializer(),
                bytes.decodeToString(throwOnInvalidSequence = true))
            check(manifest.format == FORMAT && manifest.owner == purpose.owner)
            manifest.batch?.validate(purpose.owner)
            encode(manifest.batch).useWiped { check(it.contentEquals(bytes)) }
            check(purpose.isCurrent())
            manifest.batch
        }
    }

    fun writeBatch(batch: StoredClientReadViewBatchV1?) {
        check(purpose.isCurrent())
        batch?.validate(purpose.owner)
        if (readBatch() == batch) return // Already durably retained; avoid repeated empty-cache writes while disconnected.
        encode(batch).useWiped { replaceManifest(it, MAX_CLIENT_READ_VIEW_BYTES_V1) }
        check(purpose.isCurrent())
    }

    private fun encode(batch: StoredClientReadViewBatchV1?): ByteArray =
        journalJson.encodeToString(ClientReadViewManifestV1.serializer(),
            ClientReadViewManifestV1(FORMAT, purpose.owner, batch)).encodeToByteArray().also {
            if (it.size !in 1..MAX_CLIENT_READ_VIEW_BYTES_V1) { it.fill(0); error("client.read-views.capacity") }
        }

    companion object { private const val FORMAT = "ukkera.protected-client-read-views/v1" }
}

/**
 * Android and desktop use their actual native key/file/head owners through the shared engine.
 * A corrupt cache is quarantined only after confirmed child close under current parent
 * exclusion. Unknown close keeps the original exclusion. Financial originals are untouched.
 */
internal class ProtectedClientReadViews(private val parent: ClientJournalFileCore) : LocalClientReadViewStore(
    LocalClientIdentity(parent.deviceOwner.scope, checkNotNull(parent.actorId), parent.deviceOwner.deviceId)) {
    private val gate = Mutex()
    private var file: ClientReadViewFile? = null
    private var pendingWork: ClientJournalWork? = null
    @Volatile var nativeResourcesClosed: Boolean = true
        private set
    private var repairAttempted = false
    private var closed = false

    override suspend fun load(): JournalRead<StoredClientReadViewBatchV1?> = gate.withLock {
        val retained = opened() ?: return@withLock JournalRead.Unavailable
        when (val result = retained.owned { task { retained.readBatch() } }) {
            is ClientJournalOutcome.Value -> JournalRead.Value(result.value)
            ClientJournalOutcome.Unavailable -> {
                val recovered = repair(retained.purpose) ?: return@withLock JournalRead.Unavailable
                when (val next = recovered.owned { task { recovered.readBatch() } }) {
                    is ClientJournalOutcome.Value -> JournalRead.Value(next.value)
                    ClientJournalOutcome.Unavailable -> JournalRead.Unavailable
                }
            }
        }
    }

    override suspend fun replace(batch: StoredClientReadViewBatchV1?): JournalWrite = gate.withLock {
        val captured = batch?.copy(pages = batch.pages.toList()).also { it?.validate(owner) }
        val retained = opened() ?: return@withLock JournalWrite.UNAVAILABLE
        val result = try { retained.owned { task { retained.writeBatch(captured) } } }
        catch (failure: Throwable) {
            closed = true // Cancellation abandons delivery, not the actual accepted native write.
            throw failure
        }
        when (result) {
            is ClientJournalOutcome.Value -> JournalWrite.DURABLE
            ClientJournalOutcome.Unavailable -> {
                // A failed native call does not distinguish before-write from unknown commit.
                // Preserve this exact purpose/head/write-ahead; never quarantine and rewrite
                // it into apparent success. A new parent may attempt exact cold recovery.
                closed = true
                JournalWrite.UNAVAILABLE
            }
        }
    }

    private suspend fun opened(): ClientReadViewFile? {
        if (closed || !parent.readViewParentCurrent()) return null
        file?.let { return it }
        val purpose = try { parent.readViewPurpose() } catch (_: Exception) { return null }
        val lease = ClientJournalProcessLease.acquire(purpose.journalId) ?: return null
        val work = try { purpose.newWork(lease) } catch (_: Throwable) { lease.releaseUnused(); return null }
        pendingWork = work
        nativeResourcesClosed = false // Before any possible partial native acquisition.
        val retained = try { ClientReadViewFile(purpose, work, purpose.newNative(work)) }
        catch (_: Throwable) {
            if (!closeChild()) closed = true
            return null
        }
        lease.retain(retained, work)
        // Retain partial ownership before IO. Unknown close must not lose exclusion.
        file = retained
        val result = withContext(NonCancellable) { work.run { task { retained.open(); retained } } }
        if (result is ClientJournalOutcome.Value) return result.value
        return repair(purpose)
    }

    override suspend fun close(): Unit = withContext(NonCancellable) { gate.withLock {
        closed = true
        check(closeChild()) { "client.read-views.close-unconfirmed" }
    } }

    private suspend fun closeChild(): Boolean {
        if (nativeResourcesClosed) return true
        val confirmed = try { withContext(NonCancellable) {
            withTimeoutOrNull(120_000L) {
                val retained = file
                if (retained != null) retained.closeOwner() else checkNotNull(pendingWork).requestClose {}.await()
                true
            } == true
        } } catch (_: Throwable) { false }
        if (confirmed) {
            nativeResourcesClosed = true
            file = null
            pendingWork = null
        }
        return confirmed
    }

    private suspend fun repair(purpose: ClientReadViewPurpose): ClientReadViewFile? {
        val settledNamespaceProven = file?.settledNamespaceProven == true
        if (!closeChild()) { closed = true; return null }
        // Corruption recovery cannot choose an empty cache over unresolved atomic intent.
        // Keep its original bytes/keys when actual write-ahead recovery did not complete.
        if (!settledNamespaceProven) { closed = true; return null }
        if (closed || repairAttempted || !parent.readViewParentCurrent()) { closed = true; return null }
        repairAttempted = true
        if (!parent.quarantineReadViews(purpose)) { closed = true; return null }
        return opened() // At most one reset; a second failure remains unavailable.
    }

    private fun <T> task(run: () -> T): ClientJournalTask<T> = object : ClientJournalTask<T> {
        override fun execute(): T = run()
        override fun discard(value: T) = Unit
        override fun clearInputs() = Unit
    }
}
