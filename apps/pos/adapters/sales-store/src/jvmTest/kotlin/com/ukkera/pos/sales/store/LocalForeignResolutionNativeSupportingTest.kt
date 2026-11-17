package com.ukkera.pos.sales.store

import com.ukkera.pos.database.NativeOriginalDomain
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** SELF-AUTHORED, NOT independent. FRN1.0.0. Real POSIX preallocation/locks/AEAD/force,
 * with synthetic binding/software keys/reference DATA; NOT C1/native-authority or TCP proof. */
class LocalForeignResolutionNativeSupportingTest {
    @Test fun durableResolutionIsIdempotentAndDoesNotManufactureAStartOrOriginal() = fixture { f ->
        val j = f.allocate()
        assertEquals(LocalPhysicalJournalWrite.RETAINED, j.retainForeignResolution(f.request.payload))
        assertEquals(LocalPhysicalJournalWrite.ALREADY_RETAINED, j.retainForeignResolution(f.request.payload))
        assertEquals(f.request.payload, assertIs<LocalForeignResolutionRead.Read>(j.readForeignResolution()).body)
        val original = assertIs<LocalPhysicalOriginalRead.Read>(j.readOriginal()).snapshot
        assertNull(original.certificate); assertNull(original.original); assertFalse(original.actionMayHaveStarted)
        assertFalse(j.retainOriginal(f.source.recordingOperationId, f.sourceData) in retained)
        assertFalse(j.retainResolution(f.source.resolutionOperationId, f.resolverActor, StoredBytes(byteArrayOf(1)), f.request.payload) in retained)
        assertEquals(f.request.payload, assertIs<LocalForeignResolutionRead.Read>(j.readForeignResolution()).body)
        val captured = assertNotNull(j.captureForeignResolution { error("unexpected retirement") })
        captured.release()
    }

    @Test fun confirmedCloseAndExistingOnlyReopenPreserveLiteralAndRejectChangedRequest() = fixture { f ->
        val first = f.allocate()
        assertEquals(LocalPhysicalJournalWrite.RETAINED, first.retainForeignResolution(f.request.payload))
        val receipt = first.receipt
        f.closeCertain()
        val reopened = f.reopen(receipt.binding.allocationId)
        assertEquals(f.request.payload, assertIs<LocalForeignResolutionRead.Read>(reopened.readForeignResolution()).body)
        val changed = LocalSupplierCashCodec.command(f.source.resolutionOperationId,
            LocalSupplierCashRequest.RecordResolution(f.target, sha256(f.sourceData.copyBytes()), f.resolverActor,
                LocalSupplierCashOutcome.NotPaid(f.evidence, "changed.reason")))
        assertFalse(reopened.retainForeignResolution(changed.payload) in retained)
        val wrongActor = LocalSupplierCashCodec.command(f.source.resolutionOperationId,
            LocalSupplierCashRequest.RecordResolution(f.target, sha256(f.sourceData.copyBytes()), DomainId.random(),
                LocalSupplierCashOutcome.NotPaid(f.evidence, "synthetic.not_paid")))
        assertFalse(reopened.retainForeignResolution(wrongActor.payload) in retained)
        assertEquals(f.request.payload, assertIs<LocalForeignResolutionRead.Read>(reopened.readForeignResolution()).body)
    }

    @Test fun wrongNativeOwnerCannotAllocateTheCounterparty() = fixture { f ->
        val requirement = f.requirement
        val refs = LocalForeignResolutionReferencesV1.read(requirement.canonicalDomainRequirement)
        val wrong = refs.copy(resolver = refs.resolver.copy(deviceId = DomainId.random()))
        assertFails { wrong.validate(requirement, f.domain) }
        assertIs<LocalPhysicalJournalOpen.IdentityConflict>(f.catalogue.allocate(f.requirement,
            LocalRetailPaymentJournalDomain))
        assertTrue(assertNotNull(f.catalogue.retainedAllocationSelectors()).isEmpty())
        f.allocate()
    }

    @Test fun criticalCiphertextDamageCannotBecomeAnEmptyOrSuccessfulRecord() = fixture { f ->
        val j = f.allocate()
        assertEquals(LocalPhysicalJournalWrite.RETAINED, j.retainForeignResolution(f.request.payload))
        val receipt = j.receipt
        val spec = physicalJournalNativeSpec(f.resolver, PhysicalNativeLayoutV1.JOURNAL, receipt.binding.slotIndex)
        val path = spec.segments.fold(f.root) { current, segment -> current.resolve(segment) }.resolve(spec.filename)
        f.closeCertain()
        RandomAccessFile(path.toFile(), "rw").use { file ->
            val offset = PhysicalJournalCellV1.LINKED_RESOLUTION.offset.toLong() + 80L
            file.seek(offset); val old = file.read(); check(old >= 0)
            file.seek(offset); file.write(old xor 1); file.fd.sync()
        }
        val damaged = sha256(Files.readAllBytes(path))
        f.openCatalogue()
        when (val open = f.catalogue.openExisting(receipt.binding.allocationId, f.domain)) {
            is LocalPhysicalJournalOpen.Opened -> {
                f.journal = open.journal
                assertIs<LocalForeignResolutionRead.Held>(open.journal.readForeignResolution())
            }
            LocalPhysicalJournalOpen.Held -> Unit
            else -> fail("Unexpected damaged critical record result: $open")
        }
        assertEquals(damaged, sha256(Files.readAllBytes(path)))
    }

    @Test fun closedHandleCannotReplayRetainedData() = fixture { f ->
        val j = f.allocate()
        assertEquals(LocalPhysicalJournalWrite.RETAINED, j.retainForeignResolution(f.request.payload))
        f.closeCertain()
        assertIs<LocalForeignResolutionRead.Held>(j.readForeignResolution())
    }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(Files.getFileStore(Path.of(System.getProperty("user.home"))).supportsFileAttributeView("posix"))
        val f = Fixture()
        System.err.println("Self-authored foreign retention native boundary files retained at ${f.root}; synthetic key/reference DATA only")
        try { block(f) } finally { f.close() }
    }

    private class Fixture {
        val root: Path = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "foreign-resolution-native-boundary-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        private val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
        val resolverActor = DomainId.random()
        val resolver = LocalPhysicalCatalogueBinding(scope, DomainId.random(), DomainId.random(), DomainId.random(),
            LocalPhysicalSpoolRole.CLIENT, DomainId.random())
        private val master = LocalPhysicalCatalogueBinding(scope, DomainId.random(), DomainId.random(), DomainId.random(),
            LocalPhysicalSpoolRole.MASTER, DomainId.random())
        private val sourceClient = LocalPhysicalCatalogueBinding(scope, DomainId.random(), DomainId.random(), DomainId.random(),
            LocalPhysicalSpoolRole.CLIENT, DomainId.random())
        private val authority = AuthorityIdentity(DomainId.random(), master.deviceId, Revision(1))
        val sourceData = StoredBytes("unqualified original reference DATA".encodeToByteArray())
        val source = LocalPhysicalJournalRequirementV1(scope, DomainId.random(), OperationId.random(), sha256(byteArrayOf(1)),
            OperationId.random(), OperationId.random(), DomainId.random(), NativeOriginalDomain.SUPPLIER_CASH_V1.domainProfile,
            LocalSupplierCashProfile.COMMAND, sha256(byteArrayOf(2)), DomainId.random(), DomainId.random(), master.deviceId,
            sourceClient.deviceId, LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
            LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1, StoredBytes(byteArrayOf(3)))
        val target = LocalSupplierCashTarget(DomainId.random(), DomainId.random(), null, null, DomainId.random(), source.attemptId,
            source.intentOperationId, source.recordingOperationId, source.resolutionOperationId)
        val evidence = LocalCashEvidenceV1(DomainId.random(), UtcInstant.parseCanonical("2026-11-17T00:00:00Z"))
        val request = LocalSupplierCashCodec.command(source.resolutionOperationId, LocalSupplierCashRequest.RecordResolution(
            target, sha256(sourceData.copyBytes()), resolverActor, LocalSupplierCashOutcome.NotPaid(evidence, "synthetic.not_paid")))
        val requirement = foreignResolutionRequirement(source, sourceData,
            LocalPhysicalDomainOwners(LocalSupplierCashJournalDomain, authority, master, sourceClient),
            resolver, DomainId.random(), resolverActor, LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1, request)
        val domain = checkNotNull(LocalPhysicalForeignResolutionDomain.fromRequirement(requirement))
        private val key = JvmPhysicalSpoolKey(resolver.keyId, SyntheticKey(), Cipher.getInstance("AES/GCM/NoPadding").provider)
        lateinit var catalogue: LocalPhysicalAllocationCatalogue
        var journal: LocalPhysicalJournal? = null
        init {
            val parent = root.resolve("physical-journal-v1").resolve(resolver.installationId.value)
                .resolve(resolver.catalogueId.value).resolve("client")
            for (leaf in listOf("anchor", "catalogue") + (0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES).map { "slot-${it.toString().padStart(3, '0')}" })
                Files.createDirectories(parent.resolve(leaf))
            Files.walk(root).use { stream -> stream.filter { Files.isDirectory(it) }.forEach {
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) } }
            catalogue = assertIs<LocalPhysicalCatalogueOpen.Opened>(JvmPreallocatedPhysicalJournal.createCatalogue(root, resolver, key)).catalogue
        }
        fun allocate(): LocalPhysicalJournal = assertIs<LocalPhysicalJournalOpen.Opened>(catalogue.allocate(requirement, domain)).journal.also { journal = it }
        fun openCatalogue() { catalogue = assertIs<LocalPhysicalCatalogueOpen.Opened>(JvmPreallocatedPhysicalJournal.openCatalogue(root, resolver, key)).catalogue }
        fun reopen(id: DomainId): LocalPhysicalJournal {
            openCatalogue()
            return assertIs<LocalPhysicalJournalOpen.Opened>(catalogue.openExisting(id, domain)).journal.also { journal = it }
        }
        fun closeCertain() {
            journal?.let { assertEquals(LocalPhysicalSpoolClose.CLOSED, it.close()) }; journal = null
            assertEquals(LocalPhysicalSpoolClose.CLOSED, catalogue.close())
        }
        fun close() { journal?.close(); if (::catalogue.isInitialized) catalogue.close() }
    }
    private class SyntheticKey : SecretKey {
        override fun getAlgorithm(): String = "AES"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray = ByteArray(32) { (it + 19).toByte() }
    }
    companion object {
        private val retained = setOf(LocalPhysicalJournalWrite.RETAINED, LocalPhysicalJournalWrite.ALREADY_RETAINED)
    }
}
