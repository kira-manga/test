package com.ukkera.pos.sales.store.physicalcataloguesuccessorv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.PhysicalNativeInstructionLease
import com.ukkera.pos.database.PhysicalRecordingServiceLatch
import com.ukkera.pos.database.PhysicalRecordingServiceLifetime
import com.ukkera.pos.sales.store.LocalPhysicalActionContinuation
import com.ukkera.pos.sales.store.LocalPhysicalAllocationCatalogue
import com.ukkera.pos.sales.store.LocalPhysicalCatalogueBinding
import com.ukkera.pos.sales.store.LocalPhysicalCatalogueOpen
import com.ukkera.pos.sales.store.LocalPhysicalJournal
import com.ukkera.pos.sales.store.LocalPhysicalJournalDomain
import com.ukkera.pos.sales.store.LocalPhysicalJournalExecutionGate
import com.ukkera.pos.sales.store.LocalPhysicalJournalFormatV1
import com.ukkera.pos.sales.store.LocalPhysicalJournalOpen
import com.ukkera.pos.sales.store.LocalPhysicalJournalReceipt
import com.ukkera.pos.sales.store.LocalPhysicalJournalRequirementV1
import com.ukkera.pos.sales.store.LocalPhysicalJournalStartKind
import com.ukkera.pos.sales.store.LocalPhysicalOriginalCaptureEntry
import com.ukkera.pos.sales.store.LocalPhysicalOriginalKind
import com.ukkera.pos.sales.store.LocalPhysicalOriginalRead
import com.ukkera.pos.sales.store.LocalPhysicalSpoolClose
import com.ukkera.pos.sales.store.PhysicalSpoolFile
import com.ukkera.pos.sales.store.PhysicalSpoolLease
import com.ukkera.pos.sales.store.LocalPhysicalSpoolNativeIdentity
import com.ukkera.pos.sales.store.LocalPhysicalSpoolRole
import com.ukkera.pos.sales.store.LocalPhysicalSpoolStorageProfileV1
import com.ukkera.pos.sales.store.LocalPhysicalStartWrite
import com.ukkera.pos.sales.store.PhysicalJournalDenial
import com.ukkera.pos.sales.store.PhysicalJournalDenialOwnership
import com.ukkera.pos.sales.store.PhysicalJournalLeaseOwnership
import com.ukkera.pos.sales.store.PhysicalJournalNativeFilesV1
import com.ukkera.pos.sales.store.PhysicalNativeFileSpecV1
import com.ukkera.pos.sales.store.PhysicalNativeLayoutV1
import com.ukkera.pos.sales.store.PhysicalNativeOpenV1
import com.ukkera.pos.sales.store.PhysicalNativeOwnedFileV1
import com.ukkera.pos.sales.store.PhysicalSpoolAccess
import com.ukkera.pos.sales.store.PhysicalSpoolCipher
import com.ukkera.pos.sales.store.catalogueAnchorBinding
import com.ukkera.pos.sales.store.createPhysicalCatalogue
import com.ukkera.pos.sales.store.journalOpen
import com.ukkera.pos.sales.store.journalReceiptBytes
import com.ukkera.pos.sales.store.journalSeal
import com.ukkera.pos.sales.store.openPhysicalCatalogue
import com.ukkera.pos.sales.store.physicalJournalNativeSpec
import com.ukkera.pos.sales.store.physicalSpoolEnvelope
import com.ukkera.pos.sales.store.physicalSpoolNonce
import com.ukkera.pos.sales.store.readCatalogueRoot
import com.ukkera.pos.sales.store.withSpoolBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Security
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Targeted host regression for the bounded physical catalogue successor.
 *
 * The controlled native port below is deliberately NOT a native qualification. It stores the
 * exact bytes produced by the production journal/catalogue codec and supplies deterministic
 * native identities plus explicit claim/close observations, so multi-generation fault paths can
 * be exercised without pretending an emulator or device ran. [installedJvm...] separately runs
 * the genuine JVM POSIX owner for generation-zero naming and existing-only reopen.
 */
internal class PhysicalCatalogueSuccessorV1Test {
    @Test
    fun generationZeroFilenamesAndBytesRemainStableWhenTheFirstSuccessorAppears() {
        val family = base
        assertContentEquals(family.anchorAfterFirst, family.anchorAtEnd)
        assertContentEquals(family.catalogueAfterGenerationZero, family.catalogueAtEnd)
        assertContentEquals(family.firstJournalAfterFirst, family.firstJournalAtEnd)

        assertEquals(setOf("physical.catalogue-anchor-v1.enc", "physical.catalogue-anchor-v1.g0001.enc"),
            family.store.names(family.anchorSpec(0)))
        assertEquals(setOf("physical.catalogue-v1.enc", "physical.catalogue-v1.g0001.enc"),
            family.store.names(family.catalogueSpec(0)))
        assertEquals(setOf("physical.journal-first-v1.enc", "physical.journal-first-v1.g0001.enc"),
            family.store.names(family.journalSpec(0, 0)))
        assertNotEquals(family.firstReceipt.nativeIdentity.fileIdentity,
            family.lastReceipt.nativeIdentity.fileIdentity)
    }

    @Test
    fun lifetimeAllocationContinuesPast128AndExistingOnlyReopenServesOldAndNew() {
        val family = base
        assertEquals(LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES + 1, family.allocationIds.size)
        val port = ControlledJournalFiles(family.store)
        val catalogue = (openPhysicalCatalogue(family.owner, port) as? LocalPhysicalCatalogueOpen.Opened)
            ?.catalogue ?: error("existing catalogue did not open: $port")

        val old = (catalogue.openExisting(family.firstReceipt.binding.allocationId, family.domain)
            as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("old allocation did not reopen")
        assertEquals(family.firstReceipt.allocationDigest, old.receipt.allocationDigest)
        assertTrue(old.receipt.nativeIdentity.fileIdentity.endsWith("physical.journal-first-v1.enc"))
        assertEquals(LocalPhysicalSpoolClose.CLOSED, old.close())

        val new = (catalogue.openExisting(family.lastReceipt.binding.allocationId, family.domain)
            as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("new allocation did not reopen")
        assertEquals(family.lastReceipt.allocationDigest, new.receipt.allocationDigest)
        assertTrue(new.receipt.nativeIdentity.fileIdentity.endsWith("physical.journal-first-v1.g0001.enc"))
        assertEquals(LocalPhysicalSpoolClose.CLOSED, new.close())

        assertEquals(LocalPhysicalJournalOpen.IdentityConflict,
            catalogue.allocate(requirement(0, family.owner), family.domain))
        assertEquals(LocalPhysicalJournalOpen.Held,
            catalogue.openExisting(DomainId(uuid(777_777)), family.domain))

        assertEquals(LocalPhysicalSpoolClose.CLOSED, catalogue.close())
        assertTrue(port.claimedKeys().isEmpty(), "reopen left native claims: ${port.claimedKeys()}")
    }

    @Test
    fun crossGenerationDuplicateAllocationIsHeldAndEveryCertainlyOpenedOwnerCloses() {
        val family = base
        val store = family.store.metadataCopy()
        installDuplicateGenerationZeroReceipt(family, store)
        val port = ControlledJournalFiles(store)
        assertEquals(LocalPhysicalCatalogueOpen.Held, openPhysicalCatalogue(family.owner, port),
            "cross-generation duplicate must not open")
        assertTrue(port.claimedKeys().isEmpty(),
            "cross-generation duplicate left claims: ${port.claimedKeys()}")
    }

    @Test
    fun partialMissingGappedAndForeignSuccessorsAreHeldWithoutOwnerLeaks() {
        val family = base

        val partial = family.store.metadataCopy()
        partial.truncate(family.catalogueSpec(1), LocalPhysicalJournalFormatV1.CELL_BYTES * 2)
        assertHeldWithoutClaims(family, partial, "partial successor")

        val missing = family.store.metadataCopy()
        missing.remove(family.anchorSpec(1))
        assertHeldWithoutClaims(family, missing, "missing successor anchor")

        val gapped = family.store.metadataCopy()
        gapped.rename(family.anchorSpec(1), family.anchorSpec(2))
        gapped.rename(family.catalogueSpec(1), family.catalogueSpec(2))
        assertHeldWithoutClaims(family, gapped, "gapped successor family")

        val foreignOwner = family.owner.copy(catalogueId = DomainId(uuid(900_001)))
        val foreign = singleAllocationStore(foreignOwner)
        val foreignStore = family.store.metadataCopy()
        foreignStore.put(family.anchorSpec(1),
            foreign.bytes(physicalJournalNativeSpec(foreignOwner, PhysicalNativeLayoutV1.ANCHOR))!!)
        foreignStore.put(family.catalogueSpec(1),
            foreign.bytes(physicalJournalNativeSpec(foreignOwner, PhysicalNativeLayoutV1.CATALOGUE))!!)
        assertHeldWithoutClaims(family, foreignStore, "foreign successor")
    }

    @Test
    fun uncertainCloseDuringFailedMultiGenerationOpenRetainsOnlyTheUncertainClaim() {
        val family = base
        val store = family.store.metadataCopy()
        installDuplicateGenerationZeroReceipt(family, store)
        val port = ControlledJournalFiles(store)
        port.failNextClose(family.anchorSpec(1))

        assertEquals(LocalPhysicalCatalogueOpen.Held, openPhysicalCatalogue(family.owner, port),
            "duplicate plus uncertain close must remain HELD")
        assertTrue(port.isClaimed(family.anchorSpec(1)), "uncertain owner claim was freed")
        assertFalse(port.isClaimed(family.catalogueSpec(1)), "certain successor catalogue stayed claimed")
        assertFalse(port.isClaimed(family.anchorSpec(0)), "certain generation-zero anchor stayed claimed")
        assertFalse(port.isClaimed(family.catalogueSpec(0)), "certain generation-zero catalogue stayed claimed")
        assertEquals(1, port.claimedKeys().size, "unexpected retained claims: ${port.claimedKeys()}")
    }

    @Test
    fun activeContinuationAndCaptureReferencesPinTheirJournalsDuringIdleRetirement() {
        val family = base
        val session = openSession(family, LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
        val backend = backend(family, session.catalogue)
        val first = session.journals.getValue(family.allocationIds[0])
        val second = session.journals.getValue(family.allocationIds[1])
        val continuation = fakeContinuation(family, first, second, terminal = false)
        val capture = fakeCapture(continuation, terminal = false)
        setStateList(backend, "continuations", listOf(continuation))
        setStateList(backend, "originalCaptures", listOf(capture))

        invokeRetireTerminalInstructionOwners(backend)
        assertTrue(invokeRetireIdleJournals(backend, session.journals))

        assertEquals(setOf(family.allocationIds[0], family.allocationIds[1]), session.journals.keys)
        assertIs<LocalPhysicalOriginalRead.Read>(first.readOriginal())
        assertIs<LocalPhysicalOriginalRead.Read>(second.readOriginal())
        assertIs<LocalPhysicalOriginalRead.Held>(session.all[2].readOriginal())

        session.all.take(2).forEach { assertEquals(LocalPhysicalSpoolClose.CLOSED, it.close()) }
        assertEquals(LocalPhysicalSpoolClose.CLOSED, session.catalogue.close())
        assertTrue(session.port.claimedKeys().isEmpty(),
            "active-reference check left claims: ${session.port.claimedKeys()}")
    }

    @Test
    fun terminalContinuationAndCaptureReferencesRetireBeforeCapacityReuse() {
        val family = base
        val session = openSession(family, LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
        val first = session.journals.getValue(family.allocationIds[0])
        val second = session.journals.getValue(family.allocationIds[1])
        val continuation = fakeContinuation(family, first, second, terminal = true)
        val capture = fakeCapture(continuation, terminal = true)

        val backend = backend(family, session.catalogue)
        setStateList(backend, "continuations", listOf(continuation))
        setStateList(backend, "originalCaptures", listOf(capture))
        invokeRetireTerminalInstructionOwners(backend)
        assertTrue(stateList(backend, "continuations").isEmpty(), "terminal continuation was not retired")
        assertTrue(stateList(backend, "originalCaptures").isEmpty(), "terminal capture was not retired")

        assertTrue(invokeRetireIdleJournals(backend, session.journals))
        assertTrue(session.journals.isEmpty(), "terminal journals were not retired")
        assertTrue(session.all.all { it.readOriginal() is LocalPhysicalOriginalRead.Held },
            "a terminal journal remained readable")

        assertEquals(LocalPhysicalSpoolClose.CLOSED, session.catalogue.close())
        assertTrue(session.port.claimedKeys().isEmpty(),
            "terminal-reference check left claims: ${session.port.claimedKeys()}")
    }

    @Test
    fun installedJvmGenerationZeroUsesLegacyNamesAndExistingOnlyReopen() {
        val root = Path.of("").toAbsolutePath().resolve("build/native-catalogue-regression/${UUID.randomUUID()}")
        try {
            val owner = LocalPhysicalCatalogueBinding(sampleScope, DomainId(uuid(10)), DomainId(uuid(11)),
                DomainId(uuid(12)), LocalPhysicalSpoolRole.MASTER, DomainId(uuid(13)))
            val directories = listOf("anchor", "catalogue") + (0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
                .map { "slot-" + it.toString().padStart(3, '0') }
            val journalRoot = root.resolve("physical-journal-v1").resolve(owner.installationId.value)
                .resolve(owner.catalogueId.value).resolve("master")
            directories.forEach { leaf ->
                val directory = journalRoot.resolve(leaf)
                Files.createDirectories(directory)
            }
            secureDirectories(root)
            val provider = Cipher.getInstance("AES/GCM/NoPadding").provider
            assertEquals(provider, Security.getProvider(provider.name))
            val key = com.ukkera.pos.sales.store.JvmPhysicalSpoolKey(owner.keyId, OpaqueTestKey(), provider)

            val created = com.ukkera.pos.sales.store.JvmPreallocatedPhysicalJournal.createCatalogue(root, owner, key)
            val catalogue = (created as? LocalPhysicalCatalogueOpen.Opened)?.catalogue
                ?: error("genuine JVM catalogue did not open: $created")
            val journal = (catalogue.allocate(requirement(0, owner), TestDomain)
                as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("genuine JVM allocation failed")
            val allocationId = journal.receipt.binding.allocationId
            assertEquals(LocalPhysicalSpoolClose.CLOSED, journal.close())
            assertEquals(LocalPhysicalSpoolClose.CLOSED, catalogue.close())

            assertEquals(setOf("physical.catalogue-anchor-v1.enc"),
                Files.list(journalRoot.resolve("anchor")).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() })
            assertEquals(setOf("physical.catalogue-v1.enc"),
                Files.list(journalRoot.resolve("catalogue")).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() })
            assertEquals(setOf("physical.journal-first-v1.enc"),
                Files.list(journalRoot.resolve("slot-000")).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() })

            val reopened = (com.ukkera.pos.sales.store.JvmPreallocatedPhysicalJournal.openCatalogue(root, owner, key)
                as? LocalPhysicalCatalogueOpen.Opened)?.catalogue ?: error("genuine JVM catalogue did not reopen")
            val existing = (reopened.openExisting(allocationId, TestDomain)
                as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("genuine JVM existing allocation did not reopen")
            assertEquals(allocationId, existing.receipt.binding.allocationId)
            assertEquals(LocalPhysicalSpoolClose.CLOSED, existing.close())
            assertEquals(LocalPhysicalSpoolClose.CLOSED, reopened.close())
        } finally {
            deleteTree(root)
        }
    }

    private fun assertHeldWithoutClaims(family: BaseFamily, store: ControlledStore, label: String) {
        val port = ControlledJournalFiles(store)
        assertEquals(LocalPhysicalCatalogueOpen.Held, openPhysicalCatalogue(family.owner, port), "$label must be HELD")
        assertTrue(port.claimedKeys().isEmpty(), "$label left claims: ${port.claimedKeys()}")
    }

    private fun installDuplicateGenerationZeroReceipt(family: BaseFamily, store: ControlledStore) {
        val port = ControlledJournalFiles(store)
        val anchorSpec = family.anchorSpec(1)
        val anchorNative = port.nativeFor(anchorSpec)
        val anchorBytes = store.bytes(anchorSpec) ?: error("generation-one anchor missing")
        val root = anchorBytes.withSpoolBytes { encrypted ->
            val body = journalOpen(TestCipher, "catalogue-anchor",
                catalogueAnchorBinding(family.owner, anchorNative), 0,
                LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, null, encrypted)
                ?: error("generation-one anchor did not open")
            body.withSpoolBytes { readCatalogueRoot(family.owner, anchorNative, it) }
        }
        val receiptBytes = journalReceiptBytes(family.firstReceipt)
        try {
            val encrypted = journalSeal(TestCipher, "catalogue", root.planDigest,
                family.firstReceipt.binding.slotIndex + 1,
                LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, root.headerDigest, receiptBytes)
            try {
                store.write(family.catalogueSpec(1),
                    (family.firstReceipt.binding.slotIndex + 1) * LocalPhysicalJournalFormatV1.CELL_BYTES, encrypted)
            } finally { encrypted.fill(0) }
        } finally { receiptBytes.fill(0) }
    }

    private fun singleAllocationStore(owner: LocalPhysicalCatalogueBinding): ControlledStore {
        val store = ControlledStore()
        val port = ControlledJournalFiles(store)
        val catalogue = (createPhysicalCatalogue(owner, port) as? LocalPhysicalCatalogueOpen.Opened)
            ?.catalogue ?: error("foreign catalogue did not open")
        val journal = (catalogue.allocate(requirement(0, owner), TestDomain)
            as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("foreign allocation failed")
        assertEquals(LocalPhysicalSpoolClose.CLOSED, journal.close())
        assertEquals(LocalPhysicalSpoolClose.CLOSED, catalogue.close())
        return store
    }

    private class BaseFamily(
        val owner: LocalPhysicalCatalogueBinding,
        val domain: LocalPhysicalJournalDomain,
        val store: ControlledStore,
        val firstReceipt: LocalPhysicalJournalReceipt,
        val lastReceipt: LocalPhysicalJournalReceipt,
        val allocationIds: List<DomainId>,
        val anchorAfterFirst: ByteArray,
        val catalogueAfterGenerationZero: ByteArray,
        val firstJournalAfterFirst: ByteArray,
        val anchorAtEnd: ByteArray,
        val catalogueAtEnd: ByteArray,
        val firstJournalAtEnd: ByteArray,
    ) {
        fun anchorSpec(generation: Int): PhysicalNativeFileSpecV1 =
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.ANCHOR, generation = generation)
        fun catalogueSpec(generation: Int): PhysicalNativeFileSpecV1 =
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.CATALOGUE, generation = generation)
        fun journalSpec(slot: Int, generation: Int): PhysicalNativeFileSpecV1 =
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.JOURNAL, slot, generation)
    }

    private fun createBaseFamily(): BaseFamily {
        val owner = LocalPhysicalCatalogueBinding(sampleScope, DomainId(uuid(10)), DomainId(uuid(11)),
            DomainId(uuid(12)), LocalPhysicalSpoolRole.MASTER, DomainId(uuid(13)))
        val store = ControlledStore()
        val port = ControlledJournalFiles(store)
        val catalogue = (createPhysicalCatalogue(owner, port) as? LocalPhysicalCatalogueOpen.Opened)
            ?.catalogue ?: error("generation-zero catalogue did not open")
        val first = (catalogue.allocate(requirement(0, owner), TestDomain)
            as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("first allocation failed")
        val firstReceipt = first.receipt
        val anchorAfterFirst = store.bytes(
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.ANCHOR))!!
        val firstJournalAfterFirst = store.bytes(
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.JOURNAL, 0, 0))!!
        assertEquals(LocalPhysicalSpoolClose.CLOSED, first.close())

        val allocationIds = mutableListOf(firstReceipt.binding.allocationId)
        var lastReceipt = firstReceipt
        var catalogueAfterGenerationZero: ByteArray? = null
        for (index in 1 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES + 1) {
            if (index == LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) {
                catalogueAfterGenerationZero = store.bytes(
                    physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.CATALOGUE))!!
            }
            val opened = (catalogue.allocate(requirement(index, owner), TestDomain)
                as? LocalPhysicalJournalOpen.Opened)?.journal
                ?: error("allocation $index did not open")
            lastReceipt = opened.receipt
            allocationIds += lastReceipt.binding.allocationId
            assertEquals(LocalPhysicalSpoolClose.CLOSED, opened.close())
        }
        assertEquals(LocalPhysicalSpoolClose.CLOSED, catalogue.close())
        val anchorAtEnd = store.bytes(
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.ANCHOR))!!
        val catalogueAtEnd = store.bytes(
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.CATALOGUE))!!
        val firstJournalAtEnd = store.bytes(
            physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.JOURNAL, 0, 0))!!
        return BaseFamily(owner, TestDomain, store, firstReceipt, lastReceipt, allocationIds.toList(),
            anchorAfterFirst, requireNotNull(catalogueAfterGenerationZero), firstJournalAfterFirst,
            anchorAtEnd, catalogueAtEnd, firstJournalAtEnd)
    }

    private class OpenSession(
        val catalogue: LocalPhysicalAllocationCatalogue,
        val port: ControlledJournalFiles,
        val journals: MutableMap<DomainId, LocalPhysicalJournal>,
        val all: List<LocalPhysicalJournal>,
    )

    private fun openSession(family: BaseFamily, count: Int): OpenSession {
        val port = ControlledJournalFiles(family.store)
        val catalogue = (openPhysicalCatalogue(family.owner, port) as? LocalPhysicalCatalogueOpen.Opened)
            ?.catalogue ?: error("catalogue did not reopen for retirement check")
        val journals = LinkedHashMap<DomainId, LocalPhysicalJournal>()
        val all = ArrayList<LocalPhysicalJournal>(count)
        for (index in 0 until count) {
            val id = family.allocationIds[index]
            val journal = (catalogue.openExisting(id, family.domain)
                as? LocalPhysicalJournalOpen.Opened)?.journal ?: error("allocation $index did not reopen")
            journals[id] = journal
            all += journal
        }
        return OpenSession(catalogue, port, journals, all)
    }

    private fun backend(family: BaseFamily, master: LocalPhysicalAllocationCatalogue): Any {
        val clientOwner = family.owner.copy(role = LocalPhysicalSpoolRole.CLIENT)
        val clientStore = ControlledStore()
        val client = (createPhysicalCatalogue(clientOwner, ControlledJournalFiles(clientStore))
            as? LocalPhysicalCatalogueOpen.Opened)?.catalogue ?: error("client catalogue did not open")
        val gate = LocalPhysicalJournalExecutionGate.forRetail(master, client, Dispatchers.Unconfined,
            Dispatchers.Unconfined)
        return requireNotNull(privateField(gate, "engine")) { "backend engine missing" }
    }

    private fun fakeContinuation(family: BaseFamily, master: LocalPhysicalJournal,
        client: LocalPhysicalJournal, terminal: Boolean): LocalPhysicalActionContinuation {
        val value = unsafe.allocateInstance(LocalPhysicalActionContinuation::class.java)
            as LocalPhysicalActionContinuation
        val denial = PhysicalJournalDenial()
        val ownership = PhysicalJournalLeaseOwnership(emptyList())
        val employeeDenial = PhysicalJournalDenialOwnership().apply { attach(null) }
        if (terminal) {
            denial.deny()
            ownership.release()
            employeeDenial.release()
        }
        val service = PhysicalRecordingServiceLifetime(PhysicalRecordingServiceLatch(), sampleScope,
            DomainId(uuid(30)), family.owner.deviceId, DomainId(uuid(31)), SessionGeneration(0),
            OperationId(uuid(32)), null)
        put(value, "masterStart", LocalPhysicalStartWrite.Fresh(master, bytesOf(1)))
        put(value, "clientStart", LocalPhysicalStartWrite.Fresh(client, bytesOf(2)))
        put(value, "service", service)
        put(value, "denial", denial)
        put(value, "originatingJob", Job())
        put(value, "ownership", ownership)
        put(value, "cleanupUncertain", MutableStateFlow(false))
        put(value, "employeeDenial", employeeDenial)
        put(value, "taken", MutableStateFlow(false))
        return value
    }

    private fun fakeCapture(continuation: LocalPhysicalActionContinuation,
        terminal: Boolean): LocalPhysicalOriginalCaptureEntry {
        val value = unsafe.allocateInstance(LocalPhysicalOriginalCaptureEntry::class.java)
            as LocalPhysicalOriginalCaptureEntry
        val temporary = PhysicalJournalLeaseOwnership(emptyList())
        val evidence = PhysicalJournalLeaseOwnership(emptyList())
        val employeeDenial = PhysicalJournalDenialOwnership().apply { attach(null) }
        if (terminal) {
            temporary.release()
            evidence.release()
            employeeDenial.release()
        }
        put(value, "continuation", continuation)
        put(value, "temporaryOwnership", temporary)
        put(value, "evidenceOwnership", evidence)
        put(value, "cleanupUncertain", MutableStateFlow(false))
        put(value, "employeeDenial", employeeDenial)
        put(value, "temporaryExternal", null)
        put(value, "worker", MutableStateFlow<Job?>(null))
        put(value, "uncertainCandidate", MutableStateFlow<Any?>(null))
        put(value, "state", MutableStateFlow<Any?>(if (terminal) withdrawnState() else null))
        put(value, "prepared", if (terminal)
            unsafe.allocateInstance(PhysicalNativeInstructionLease::class.java) else null)
        return value
    }

    private fun invokeRetireTerminalInstructionOwners(backend: Any) {
        val method = backend.javaClass.getDeclaredMethod("retireTerminalInstructionOwners")
        method.isAccessible = true
        method.invoke(backend)
    }

    private fun invokeRetireIdleJournals(backend: Any,
        owned: MutableMap<DomainId, LocalPhysicalJournal>): Boolean {
        val method = backend.javaClass.getDeclaredMethod("retireIdleJournals", java.util.Map::class.java)
        method.isAccessible = true
        return method.invoke(backend, owned) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun setStateList(backend: Any, name: String, values: List<Any>) {
        (privateField(backend, name) as MutableStateFlow<List<Any>>).value = values
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateList(backend: Any, name: String): List<Any> =
        (privateField(backend, name) as MutableStateFlow<List<Any>>).value

    private companion object {
        val base: BaseFamily by lazy { PhysicalCatalogueSuccessorV1Test().createBaseFamily() }

        val sampleScope: BranchScope = BranchScope(DomainId(uuid(1)), DomainId(uuid(2)), DomainId(uuid(3)))

        object TestDomain : LocalPhysicalJournalDomain {
            override val domainProfile: String = "physical.catalogue.test/v1"
            override val command: CommandKey = CommandKey("physical.catalogue.test", 1)
            override val startKind: LocalPhysicalJournalStartKind = LocalPhysicalJournalStartKind.PAYMENT
            override fun validateRequirement(requirement: LocalPhysicalJournalRequirementV1) {
                require(requirement.domainProfile == domainProfile && requirement.command == command)
            }
            override fun classifyOriginal(requirement: LocalPhysicalJournalRequirementV1,
                body: com.ukkera.pos.database.StoredBytes): LocalPhysicalOriginalKind =
                LocalPhysicalOriginalKind.KNOWN
            override fun validateResolution(requirement: LocalPhysicalJournalRequirementV1,
                original: com.ukkera.pos.database.StoredBytes, resolverActorId: DomainId,
                resolverAuthorization: com.ukkera.pos.database.StoredBytes,
                body: com.ukkera.pos.database.StoredBytes) = Unit
        }

        fun requirement(index: Int, owner: LocalPhysicalCatalogueBinding): LocalPhysicalJournalRequirementV1 {
            val digest = sha256(byteArrayOf(index.toByte()))
            return LocalPhysicalJournalRequirementV1(
                scope = owner.scope,
                originalActorId = DomainId(uuid(20)),
                intentOperationId = OperationId(uuid(1_000 + index)),
                intentSemanticDigest = digest,
                recordingOperationId = OperationId(uuid(2_000 + index)),
                resolutionOperationId = OperationId(uuid(3_000 + index)),
                attemptId = DomainId(uuid(4_000 + index)),
                domainProfile = TestDomain.domainProfile,
                command = TestDomain.command,
                targetBinding = digest,
                masterAllocationId = DomainId(uuid(10_000 + index)),
                clientAllocationId = DomainId(uuid(20_000 + index)),
                masterDeviceId = owner.deviceId,
                clientDeviceId = DomainId(uuid(30_000 + index)),
                masterStorageProfile = LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
                clientStorageProfile = LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
                canonicalDomainRequirement = com.ukkera.pos.database.StoredBytes(byteArrayOf(1, 2, 3)),
            )
        }

        fun uuid(value: Int): String = "00000000-0000-4000-8000-" + value.toString().padStart(12, '0')

        fun bytesOf(value: Int): com.ukkera.pos.database.StoredBytes =
            com.ukkera.pos.database.StoredBytes(byteArrayOf(value.toByte()))

        val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe

        fun put(target: Any, name: String, value: Any?) {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
                if (field != null) {
                    unsafe.putObject(target, unsafe.objectFieldOffset(field), value)
                    return
                }
                type = type.superclass
            }
            error("missing field $name")
        }

        fun privateField(target: Any, name: String): Any? {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
                if (field != null) {
                    field.isAccessible = true
                    return field.get(target)
                }
                type = type.superclass
            }
            error("missing field $name")
        }

        fun withdrawnState(): Any = Class.forName(
            "com.ukkera.pos.sales.store.PhysicalOriginalMemoryState\$Withdrawn")
            .getField("INSTANCE").get(null)
    }
}

/** Test-only normalized AES-GCM cipher. It is real cryptography, not a native storage claim. */
private object TestCipher : PhysicalSpoolCipher {
    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    override val identity: String = "controlled-native-port-test-aes-gcm/v1"
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return physicalSpoolEnvelope(cipher.iv, cipher.doFinal(plaintext))
    }
    override fun open(envelope: ByteArray, aad: ByteArray): ByteArray {
        val nonce = physicalSpoolNonce(envelope)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, 16, envelope.size - 16)
    }
}

/**
 * Persistent test bytes only. No native API is called here; the port below is the labelled
 * controlled custody adapter for fault injection. Storage format bytes are production's.
 */
private class ControlledStore {
    private val leaves = LinkedHashMap<String, LinkedHashMap<String, ByteArray>>()

    fun bytes(spec: PhysicalNativeFileSpecV1): ByteArray? = leaves[leafKey(spec)]?.get(spec.filename)?.copyOf()

    fun names(spec: PhysicalNativeFileSpecV1): Set<String> =
        leaves[leafKey(spec)]?.keys?.toSet() ?: emptySet()

    fun put(spec: PhysicalNativeFileSpecV1, bytes: ByteArray) {
        leaves.getOrPut(leafKey(spec)) { LinkedHashMap() }[spec.filename] = bytes
    }

    fun remove(spec: PhysicalNativeFileSpecV1) {
        leaves[leafKey(spec)]?.remove(spec.filename)
    }

    fun rename(from: PhysicalNativeFileSpecV1, to: PhysicalNativeFileSpecV1) {
        val bytes = leaves[leafKey(from)]?.remove(from.filename)
            ?: error("missing ${from.filename}")
        put(to, bytes)
    }

    fun truncate(spec: PhysicalNativeFileSpecV1, size: Int) {
        val bytes = leaves[leafKey(spec)]?.get(spec.filename) ?: error("missing ${spec.filename}")
        check(size in 0 until bytes.size)
        leaves[leafKey(spec)]!![spec.filename] = bytes.copyOf(size)
    }

    fun write(spec: PhysicalNativeFileSpecV1, offset: Int, bytes: ByteArray) {
        val target = leaves[leafKey(spec)]?.get(spec.filename) ?: error("missing ${spec.filename}")
        check(offset >= 0 && offset + bytes.size <= target.size)
        bytes.copyInto(target, offset)
    }

    fun metadataCopy(): ControlledStore {
        val copy = ControlledStore()
        for ((leaf, files) in leaves) {
            val leafName = leaf.substringAfterLast('/')
            if (leafName != "anchor" && leafName != "catalogue") continue
            for ((name, bytes) in files) {
                copy.leaves.getOrPut(leaf) { LinkedHashMap() }[name] = bytes.copyOf()
            }
        }
        return copy
    }

    fun leafKey(spec: PhysicalNativeFileSpecV1): String = spec.segments.joinToString("/")
}

/**
 * CLEARLY LABELLED controlled native port, not JVM/Android native qualification.
 * It executes production catalogue/journal codec logic over persistent bytes and supplies
 * deterministic identities, explicit claim retention and injected close failure.
 */
private class ControlledJournalFiles(val store: ControlledStore) : PhysicalJournalNativeFilesV1 {
    private val states = LinkedHashMap<String, ControlledFileState>()
    private val claims = LinkedHashSet<String>()
    private val closeFailures = mutableMapOf<String, Int>()

    fun nativeFor(spec: PhysicalNativeFileSpecV1): LocalPhysicalSpoolNativeIdentity =
        LocalPhysicalSpoolNativeIdentity(
            LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
            directoryIdentity(spec), "controlled-file/" + key(spec),
            "controlled-owner", TestCipher.identity)

    fun isClaimed(spec: PhysicalNativeFileSpecV1): Boolean = key(spec) in claims

    fun claimedKeys(): Set<String> = claims.toSet()

    fun failNextClose(spec: PhysicalNativeFileSpecV1) {
        val key = key(spec)
        closeFailures[key] = (closeFailures[key] ?: 0) + 1
    }

    override fun open(spec: PhysicalNativeFileSpecV1,
        expected: LocalPhysicalSpoolNativeIdentity?, create: Boolean): PhysicalNativeOpenV1 {
        val key = key(spec)
        if (key in claims) return PhysicalNativeOpenV1.Busy
        val existing = store.bytes(spec)
        if (create) {
            if (existing != null) return PhysicalNativeOpenV1.Held
            for (name in store.names(spec)) {
                val generation = spec.layout.generationOf(name) ?: return PhysicalNativeOpenV1.Held
                if (generation >= spec.generation) return PhysicalNativeOpenV1.Held
            }
            val state = ControlledFileState(key, spec)
            store.put(spec, state.bytes)
            states[key] = state
            claims += key
            return opened(spec, state)
        }
        if (existing == null) return PhysicalNativeOpenV1.Held
        val native = nativeFor(spec)
        if (expected != null && expected != native) return PhysicalNativeOpenV1.Held
        val state = states.getOrPut(key) {
            ControlledFileState(key, spec).also { it.bytes = existing.copyOf(); it.initialized = true }
        }
        claims += key
        return opened(spec, state)
    }

    private fun opened(spec: PhysicalNativeFileSpecV1,
        state: ControlledFileState): PhysicalNativeOpenV1.Opened =
        PhysicalNativeOpenV1.Opened(PhysicalNativeOwnedFileV1(nativeFor(spec),
            ControlledOwnedFile(this, spec, state), TestCipher))

    override fun list(spec: PhysicalNativeFileSpecV1, expectedDirectoryIdentity: String): List<String>? {
        if (directoryIdentity(spec) != expectedDirectoryIdentity) return null
        return store.names(spec).toList()
    }

    fun close(state: ControlledFileState): LocalPhysicalSpoolClose {
        state.closeCalls++
        state.lastClose?.let { return it }
        state.closed = true
        val failures = closeFailures[state.key] ?: 0
        if (state.leases.isNotEmpty() || failures > 0) {
            if (failures > 0) closeFailures[state.key] = failures - 1
            state.lastClose = LocalPhysicalSpoolClose.HELD
            return LocalPhysicalSpoolClose.HELD
        }
        claims.remove(state.key)
        state.lastClose = LocalPhysicalSpoolClose.CLOSED
        return LocalPhysicalSpoolClose.CLOSED
    }

    fun releaseLease(state: ControlledFileState, lease: Any) {
        state.leases.remove(lease)
    }

    private fun key(spec: PhysicalNativeFileSpecV1): String = store.leafKey(spec) + "/" + spec.filename

    private fun directoryIdentity(spec: PhysicalNativeFileSpecV1): String =
        "controlled-directory/" + store.leafKey(spec)
}

private class ControlledFileState(val key: String, val spec: PhysicalNativeFileSpecV1) {
    var bytes: ByteArray = ByteArray(spec.layout.bytes)
    var initialized: Boolean = false
    var closed: Boolean = false
    var inAccess: Boolean = false
    var closeCalls: Int = 0
    var lastClose: LocalPhysicalSpoolClose? = null
    val leases: MutableSet<Any> = mutableSetOf()
}

private class ControlledOwnedFile(
    private val port: ControlledJournalFiles,
    private val spec: PhysicalNativeFileSpecV1,
    private val state: ControlledFileState,
) : PhysicalSpoolFile {
    override fun <T> access(block: () -> T): PhysicalSpoolAccess<T> {
        if (state.closed) return PhysicalSpoolAccess.Held
        if (state.inAccess) return PhysicalSpoolAccess.Busy
        state.inAccess = true
        return try {
            val value = block()
            if (state.closed) PhysicalSpoolAccess.Held else PhysicalSpoolAccess.Value(value)
        } catch (_: Exception) {
            PhysicalSpoolAccess.Held
        } finally {
            state.inAccess = false
        }
    }

    override fun initialize(bytes: ByteArray) {
        check(!state.closed && !state.initialized)
        check(bytes.size == spec.layout.bytes)
        val copy = bytes.copyOf()
        state.bytes = copy
        state.initialized = true
        port.store.put(spec, copy)
    }

    override fun read(offset: Int, size: Int): ByteArray {
        spec.layout.range(offset, size)
        check(!state.closed && state.initialized)
        check(offset >= 0 && offset + size <= state.bytes.size)
        return state.bytes.copyOfRange(offset, offset + size)
    }

    override fun overwrite(offset: Int, bytes: ByteArray) {
        spec.layout.range(offset, bytes.size, writable = true)
        check(!state.closed && state.initialized)
        check(offset >= 0 && offset + bytes.size <= state.bytes.size)
        bytes.copyInto(state.bytes, offset)
        port.store.put(spec, state.bytes)
    }

    override fun force() {
        check(!state.closed && state.initialized)
    }

    override fun capture(onRetirement: () -> Unit): PhysicalSpoolLease? {
        if (state.closed) return null
        val lease = object : PhysicalSpoolLease {
            private var released = false
            override fun release() {
                if (!released) {
                    released = true
                    port.releaseLease(state, this)
                }
            }
        }
        state.leases += lease
        return lease
    }

    override fun close(): LocalPhysicalSpoolClose = port.close(state)
}

private fun secureDirectories(root: Path) {
    Files.walk(root).use { paths ->
        paths.filter { Files.isDirectory(it) }.forEach { directory ->
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
    }
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(java.util.Comparator.reverseOrder<Path>()).forEach { path ->
            try { Files.deleteIfExists(path) } catch (_: Exception) { }
        }
    }
}

private class OpaqueTestKey : SecretKey {
    private val material = ByteArray(32) { (it + 7).toByte() }
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray = material.copyOf()
    override fun destroy() { }
    override fun isDestroyed(): Boolean = false
}


