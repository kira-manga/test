package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.OriginalPhysicalEmployeeEnrollment
import com.ukkera.pos.database.belongsTo
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalEmployeeEnrollmentProfileV1
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Original physical owner action of the actual same-device original cohost (Android and desktop Master). */
public enum class AndroidOriginalRetailPhysicalAction {
    /** Explicit once-only admission on the actual acknowledged, untouched original C1. */
    ENROLL_ORIGINAL,
    /** Existing protected catalogues/journals only; no missing path or file is created. */
    REOPEN_EXISTING,
}

/**
 * Platform native namespace of ONE original physical acquisition: bounded private provisioning
 * (enrollment) or a complete existing-only hold (reopen) of the fixed catalogue layout, the
 * platform's own currentness checks and the exact native file census of each role directory.
 * It owns any temporary directory handles and their (possibly uncertain) close. Nothing is
 * unlinked, repaired or adopted.
 */
internal interface OriginalPhysicalNativeNamespace : PhysicalJournalNativePreparation {
    fun prepare()
    fun requireCurrent()
    fun requireCensus(owner: LocalPhysicalCatalogueBinding, census: LocalPhysicalCatalogueNativeCensus)
}

/** Fixed per-platform profile: catalogue ID derivation domain and the native storage profile. */
internal class OriginalPhysicalRecordingProfile(
    val catalogueIdDomain: String,
    val storageProfile: LocalPhysicalSpoolStorageProfileV1,
)

/**
 * Shared original cohost acquisition used by the Android application and the desktop Master
 * service. Platforms supply only their genuine native namespace and catalogue factory, the
 * catalogue key ID they actually hold, and the exact host-ownership predicate. The original
 * identity, consumed employee history, receipt-bound catalogue IDs, complete existing census,
 * per-journal reopen check and the master/client receipt pairing are identical on every platform.
 */
internal object LocalOriginalRetailPhysicalAcquisition {
    suspend fun acquire(
        original: AndroidOriginalLocalClient,
        backend: LocalOriginalPhysicalJournalBackend,
        enroll: Boolean,
        profile: OriginalPhysicalRecordingProfile,
        keyId: DomainId,
        stillOwned: () -> Boolean,
        namespace: (owners: List<LocalPhysicalCatalogueBinding>, history: List<OriginalPhysicalEmployeeEnrollment>) ->
            OriginalPhysicalNativeNamespace,
        openCatalogue: (owner: LocalPhysicalCatalogueBinding) -> LocalPhysicalCatalogueOpen,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val coordinates = original.material.coordinates
        val master = LocalPhysicalCatalogueBinding(coordinates.scope, coordinates.installationId,
            coordinates.deviceId, originalPhysicalCatalogueId(original, "master", profile.catalogueIdDomain),
            LocalPhysicalSpoolRole.MASTER, keyId)
        val client = LocalPhysicalCatalogueBinding(coordinates.scope, coordinates.installationId,
            coordinates.deviceId, originalPhysicalCatalogueId(original, "client", profile.catalogueIdDomain),
            LocalPhysicalSpoolRole.CLIENT, keyId)
        val identity = OriginalAndroidPhysicalOwnerIdentity(master, client,
            AuthorityIdentity(coordinates.assignmentId, coordinates.deviceId, original.receipt.core.authorityEpoch),
            coordinates.firstActorId, original.receipt.digest, original)
        val history = when (val consumed = original.originalConsumedPhysicalEmployees()) {
            is LocalCompanyAuthorityResult.Value -> consumed.value
            // Preserve the exact founding path. Missing actual history cannot census an
            // employee allocation or qualify an employee; no account/SDK/row fallback exists.
            is LocalCompanyAuthorityResult.Unavailable -> emptyList()
        }
        if (history.size > LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS ||
            history.map { it.actorId }.distinct().size != history.size ||
            history.map { it.originalActionId }.distinct().size != history.size ||
            !history.all { it.belongsTo(original) }) return false
        val enrolled = backend.installOriginalAndroidOwners(identity, history) { attempt ->
            if (!stillOwned() || (enroll && !original.permitsFirstEnrollment())) return@installOriginalAndroidOwners false
            val owners = listOf(master, client)
            val prepared = namespace(owners, history)
            // Capture the preparation owner before its first mkdir/open/fsync. The same gate
            // mutex serializes master close, including cleanup of partial native acquisition.
            attempt.nativePreparation = prepared
            prepared.prepare()
            attempt.unreturnedNativeOwner = true
            val first = openCatalogue(master)
            if (first !is LocalPhysicalCatalogueOpen.Opened) {
                if (first === LocalPhysicalCatalogueOpen.Busy) attempt.unreturnedNativeOwner = false
                return@installOriginalAndroidOwners false
            }
            attempt.master = first.catalogue
            // The master is now captured; reserve this same uncertainty slot for the client.
            attempt.unreturnedNativeOwner = true
            val second = openCatalogue(client)
            if (second !is LocalPhysicalCatalogueOpen.Opened) {
                if (second === LocalPhysicalCatalogueOpen.Busy) attempt.unreturnedNativeOwner = false
                return@installOriginalAndroidOwners false
            }
            attempt.client = second.catalogue
            attempt.unreturnedNativeOwner = false
            val verifier = OriginalPhysicalCatalogueVerifier(original, owners, history, enroll, profile, prepared)
            val masterReceipts = verifier.verify(first.catalogue, attempt)
            val clientReceipts = verifier.verify(second.catalogue, attempt)
            requireMatchingOriginalPhysicalReceipts(masterReceipts, clientReceipts)
            if (!stillOwned() || (enroll && !original.permitsFirstEnrollment())) return@installOriginalAndroidOwners false
            if (prepared.close() != LocalPhysicalSpoolClose.CLOSED) return@installOriginalAndroidOwners false
            attempt.nativePreparation = null
            true
        }
        currentCoroutineContext().ensureActive()
        return enrolled && stillOwned() && history.all { it.belongsTo(original) }
    }

    /** Receipt-bound fixed identities, never fresh random IDs chosen after a failed open. */
    fun originalPhysicalCatalogueId(original: AndroidOriginalLocalClient, role: String, domain: String): DomainId {
        val coordinates = original.material.coordinates
        val body = listOf(domain,
            coordinates.scope.organizationId.value, coordinates.scope.companyId.value, coordinates.scope.branchId.value,
            coordinates.deviceId.value, coordinates.installationId.value, coordinates.storageKeyId.value,
            coordinates.firstActorId.value, original.receipt.core.originalRequestId.value, original.receipt.digest.value, role)
            .joinToString("\n").encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            val hash = MessageDigest.getInstance("SHA-256").digest(body)
            try {
                val hex = buildString(32) {
                    for (index in 0 until 16) {
                        val value = hash[index].toInt() and 255
                        append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15])
                    }
                }
                DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
            } finally { hash.fill(0) }
        } finally { body.fill(0) }
    }

    private fun requireMatchingOriginalPhysicalReceipts(master: List<LocalPhysicalJournalReceipt>,
        client: List<LocalPhysicalJournalReceipt>) {
        val left = master.associateBy { it.binding.requirement.masterAllocationId }
        val right = client.associateBy { it.binding.requirement.masterAllocationId }
        check(left.size == master.size && right.size == client.size && left.keys == right.keys)
        for ((id, receipt) in left) {
            val paired = checkNotNull(right[id])
            check(receipt.binding.requirement.canonicalBytes == paired.binding.requirement.canonicalBytes &&
                receipt.binding.allocationId != paired.binding.allocationId &&
                receipt.nativeIdentity.fileIdentity != paired.nativeIdentity.fileIdentity &&
                receipt.nativeIdentity.directoryIdentity != paired.nativeIdentity.directoryIdentity)
        }
    }
}

/** Complete existing-only verification of one opened original catalogue. */
private class OriginalPhysicalCatalogueVerifier(
    private val original: AndroidOriginalLocalClient,
    private val owners: List<LocalPhysicalCatalogueBinding>,
    private val employees: List<OriginalPhysicalEmployeeEnrollment>,
    private val enroll: Boolean,
    private val profile: OriginalPhysicalRecordingProfile,
    private val namespace: OriginalPhysicalNativeNamespace,
) {
    /** Selectors come from the authenticated catalogue; filesystem bytes never supply a receipt. */
    fun verify(catalogue: LocalPhysicalAllocationCatalogue,
        attempt: PhysicalJournalGateOwnerAttempt): List<LocalPhysicalJournalReceipt> {
        namespace.requireCurrent()
        check(catalogue.binding in owners && catalogue.storageProfile == profile.storageProfile)
        val selectors = catalogue.retainedAllocationSelectors() ?: error("physical.original.catalogue")
        check(selectors.size <= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES *
            LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS && selectors.distinct().size == selectors.size)
        val authority = AuthorityIdentity(original.material.coordinates.assignmentId,
            original.peer.deviceId, original.receipt.core.authorityEpoch)
        val receipts = ArrayList<LocalPhysicalJournalReceipt>(selectors.size)
        for (id in selectors) {
            namespace.requireCurrent()
            val receipt = catalogue.readExpectedReceipt(id) ?: error("physical.original.catalogue_receipt")
            val requirement = receipt.binding.requirement
            val foundingActor = requirement.originalActorId == original.material.coordinates.firstActorId
            val employee = if (foundingActor) null else employees.singleOrNull { witness ->
                witness.actorId == requirement.originalActorId && witness.belongsTo(original) &&
                    witness.authority == authority &&
                    witness.receiptDigest == original.receipt.digest && witness.firstActorId == original.material.coordinates.firstActorId &&
                    witness.installationId == catalogue.binding.installationId && witness.scope == requirement.scope &&
                    witness.deviceId == requirement.clientDeviceId
            }
            check(receipt.binding.owner == catalogue.binding && (foundingActor || employee != null) &&
                requirement.scope == original.peer.scope && requirement.masterDeviceId == original.peer.deviceId &&
                requirement.clientDeviceId == original.peer.deviceId &&
                requirement.masterStorageProfile == catalogue.storageProfile && requirement.clientStorageProfile == catalogue.storageProfile)
            val domain = localPhysicalDomainOwners(requirement)
            check((domain.domain === LocalRetailPaymentJournalDomain || domain.domain === LocalRetailRefundJournalDomain ||
                    domain.domain === LocalFoodCashJournalDomain || domain.domain === LocalFoodRefundCashJournalDomain ||
                    domain.domain === LocalSupplierCashJournalDomain) &&
                domain.authority == authority &&
                domain.master == owners.single { it.role == LocalPhysicalSpoolRole.MASTER } &&
                domain.client == owners.single { it.role == LocalPhysicalSpoolRole.CLIENT })
            attempt.unreturnedNativeOwner = true
            val opened = catalogue.openExisting(id, domain.domain)
            if (opened !is LocalPhysicalJournalOpen.Opened) {
                if (opened !== LocalPhysicalJournalOpen.Held) attempt.unreturnedNativeOwner = false
                error("physical.original.journal")
            }
            attempt.journals.add(opened.journal)
            attempt.unreturnedNativeOwner = false
            val snapshot = (opened.journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                ?: error("physical.original.journal_origin")
            check(opened.journal.receipt.sourceCommitment == receipt.sourceCommitment && snapshot.receipt.sourceCommitment == receipt.sourceCommitment)
            // A readable ALLOCATED/Started cell without an observation is still exactly that
            // retained state. It never becomes NOT_PAID, unused return rights or another instruction.
            check(opened.journal.close() == LocalPhysicalSpoolClose.CLOSED) { "physical.original.journal_close" }
            check(attempt.journals.remove(opened.journal))
            receipts.add(receipt)
        }
        check(receipts.size == selectors.size)
        namespace.requireCensus(catalogue.binding, catalogue.nativeFileCensus())
        if (enroll) check(receipts.isEmpty())
        namespace.requireCurrent()
        return receipts
    }
}
