package com.ukkera.pos.catalog.store.independent.supplierreferencev2

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Supporting actual handler/storage-decoder semantics, not durable/native original reconstruction. */
class SupplierReferenceV2HandlerSlicesTest {
    private val f = SupplierReferenceV2Inputs

    @Test
    fun sref2001_actualCreateAndDetailRetainNullAndEveryFrozenLiteral() = runTest {
        for (text in listOf<String?>(null) + f.valid.map { it.text }) {
            val h = SupplierReferenceV2HandlerFixture()
            val supplier = f.supplier(reference = text)
            val result = h.commit(LocalSupplierRequestV2(null, supplier), f.operation(202))
            assertEquals(supplier.reference, result.reference)
            assertEquals(supplier.id, result.id)
            val detail = h.read(LocalSupplierQueryV2(id = supplier.id))
            assertEquals(supplier, detail.detail)
            val row = detail.items.single()
            assertEquals(result.revision, row.revision)
            assertEquals(h.storageRevision(supplier.id), row.storageRevision)
            assertEquals(row.revision.next(), row.storageRevision)
            assertEquals(supplier.reference, row.reference)
            assertEquals(1, h.snapshot().size)
            assertEquals(1, h.originals.size)
        }
    }

    @Test
    fun sref2002_fullStateSetPreserveReplaceAndClearKeepExactOwnCutResultAndSourceFacts() = runTest {
        val h = SupplierReferenceV2HandlerFixture()
        h.commitV1(LocalConfigurationRequestV1.ConfigureSupplier(null,
            LocalSupplierV1(f.legacy, f.initialNames, true)), f.operation(201))
        assertNull(h.current(f.legacy).reference)
        val steps = listOf(
            Triple(301, f.literal, f.initialNames to true), Triple(304, f.literal, f.editedNames to false),
            Triple(302, f.mixed, f.editedNames to false), Triple(303, null, f.editedNames to false),
        )
        val frozenOriginals = mutableListOf<Pair<OperationId, SupplierReferenceV2HandlerFixture.UnitOriginal>>()
        for ((serial, text, facts) in steps) {
            val before = h.storageRevision(f.legacy)
            val full = f.supplier(f.legacy, text, facts.first, facts.second)
            val result = h.commit(LocalSupplierRequestV2(before, full), f.operation(serial))
            assertEquals(full, h.current(f.legacy))
            assertEquals(full, h.read(LocalSupplierQueryV2(id = f.legacy)).detail)
            assertEquals(full.reference, result.reference)
            assertEquals(h.storageRevision(f.legacy), checkNotNull(result.revision).next())
            val original = h.originals.getValue(f.operation(serial))
            val body = decodeRecord(h.scope, original.after.single()).body as ConfigurationBody.Supplier
            assertEquals(full, body.supplierV2())
            assertEquals(setOf("code", "id", "revision", "reference"),
                localConfigurationJson.parseToJsonElement(original.result.copyBytes().decodeToString()).jsonObject.keys)
            frozenOriginals += f.operation(serial) to original
        }
        for ((id, original) in frozenOriginals) {
            assertEquals(original, h.originals.getValue(id))
            val request = LocalSupplierCodecV2.decode(original.normalized)
            assertEquals(request.supplier.reference, LocalSupplierCodecV2.decodeResult(original.result).reference)
            assertEquals(request.supplier.names, (decodeRecord(h.scope, original.after.single()).body as ConfigurationBody.Supplier).value.names)
            assertEquals(request.supplier.active, (decodeRecord(h.scope, original.after.single()).body as ConfigurationBody.Supplier).value.active)
        }
        assertNull(h.current(f.legacy).reference)
        assertEquals(5, h.originals.size)
    }

    @Test
    fun sref2003_genuineHandlerV1OmissionPreservesPriorV2ReferenceAndNeverDecoratesV1Result() = runTest {
        val h = SupplierReferenceV2HandlerFixture()
        h.commit(LocalSupplierRequestV2(null, f.supplier()), f.operation(202))
        val creation = h.originals.getValue(f.operation(202))
        val result = h.commitV1(LocalConfigurationRequestV1.ConfigureSupplier(h.storageRevision(f.current),
            LocalSupplierV1(f.current, f.editedNames, false)), f.operation(305))
        assertEquals(f.supplier(names = f.editedNames, active = false), h.current(f.current))
        val legacyOriginal = h.originals.getValue(f.operation(305))
        assertEquals(LocalSupplierProfileV2.SCHEMA, legacyOriginal.after.single().schema)
        assertEquals(setOf("code", "id", "revision"),
            localConfigurationJson.parseToJsonElement(legacyOriginal.result.copyBytes().decodeToString()).jsonObject.keys)
        assertEquals(result, LocalConfigurationCodec.decodeResult(legacyOriginal.result))
        assertEquals(Code.exact(f.literal), (decodeRecord(h.scope, legacyOriginal.before.single()!!).body as ConfigurationBody.Supplier).reference)
        h.commit(LocalSupplierRequestV2(h.storageRevision(f.current),
            f.supplier(reference = null, names = f.editedNames, active = false)), f.operation(303))
        assertNull(h.current(f.current).reference)
        assertEquals(creation, h.originals.getValue(f.operation(202)))
        assertEquals(legacyOriginal, h.originals.getValue(f.operation(305)))
        assertEquals(Code.exact(f.literal), (decodeRecord(h.scope, legacyOriginal.after.single()).body as ConfigurationBody.Supplier).reference)
        // Within-test original retention is not the missing pre-transition V1 byte golden.
    }

    @Test
    fun sref2004_bothSerialCasOrdersPermitExactlyOneSetOrClearAndRejectFreshStaleWork() = runTest {
        for (setFirst in listOf(true, false)) {
            val h = SupplierReferenceV2HandlerFixture()
            h.commit(LocalSupplierRequestV2(null, f.supplier()), f.operation(202))
            val r0 = h.storageRevision(f.current)
            val winnerText = if (setFirst) f.mixed else null
            val loserText = if (setFirst) null else f.mixed
            h.commit(LocalSupplierRequestV2(r0, f.supplier(reference = winnerText)), f.operation(if (setFirst) 401 else 402))
            val beforeLoser = h.snapshot()
            val originalCount = h.originals.size
            val loser = assertIs<ProposedOutcome.Reject>(h.propose(LocalSupplierRequestV2(r0,
                f.supplier(reference = loserText)), f.operation(if (setFirst) 402 else 401)))
            assertEquals("configuration.stale_revision", loser.code)
            assertEquals(LocalSupplierResultV2(loser.code, null, null, null), LocalSupplierCodecV2.decodeResult(loser.result))
            assertEquals(beforeLoser, h.snapshot())
            assertEquals(originalCount, h.originals.size)
            assertEquals(winnerText?.let(Code::exact), h.read(LocalSupplierQueryV2(id = f.current)).detail!!.reference)
            val stale = assertIs<ProposedOutcome.Reject>(h.propose(LocalSupplierRequestV2(r0,
                f.supplier(reference = f.literal)), f.operation(501)))
            assertEquals("configuration.stale_revision", stale.code)
            assertEquals(beforeLoser, h.snapshot())
        }
        // This covers the frozen two serial orders, not the required real-writer barrier race.
    }

    @Test
    fun sref2005_normalizedBusinessDigestsDistinguishEveryLiteralConflictAndNfcNfd() {
        val handler = LocalSupplierHandlerV2()
        fun digest(text: String?, actor: DomainId = f.actor) = handler.normalize(
            LocalSupplierCodecV2.encode(LocalSupplierRequestV2(null, f.supplier(reference = text)))).let {
                semanticDigest(f.scope, actor, handler.key, it)
            }
        val original = digest(f.literal)
        assertEquals(original, digest(f.literal))
        for (text in f.conflicts) assertNotEquals(original, digest(text))
        assertNotEquals(digest(f.nfc), digest(f.nfd))
        assertNotEquals(original, digest(f.literal, f.otherActor))
    }

    @Test
    fun sref2006_handlerRequirementsUseSeparateReadAdministrationAndPureCurrentIdentityRefusals() = runTest {
        val h = SupplierReferenceV2HandlerFixture()
        h.commit(LocalSupplierRequestV2(null, f.supplier()), f.operation(202))
        val before = h.snapshot()
        val write = h.prepared(LocalSupplierRequestV2(h.storageRevision(f.current), f.supplier(reference = null)), f.operation(303))
        assertEquals(h.editPermission, write.requirement.permission)
        assertEquals(f.current, write.requirement.binding!!.documentId)
        h.permissions = setOf(h.readPermission)
        assertIs<AuthorizationDecision.Denied>(h.decision(write.requirement))
        assertEquals(Code.exact(f.literal), h.read(LocalSupplierQueryV2(id = f.current)).detail!!.reference)
        h.permissions = setOf(h.editPermission)
        assertFails { h.read(LocalSupplierQueryV2(id = f.current)) }
        assertIs<AuthorizationDecision.Allowed>(h.decision(write.requirement))
        h.permissions = setOf(h.readPermission, h.editPermission)
        for (scope in listOf(BranchScope(f.id(1), f.id(10), f.id(12)), BranchScope(f.id(1), f.id(20), f.id(21))))
            assertIs<AuthorizationDecision.Denied>(h.decision(write.requirement, scope))
        h.accountEnabled = false
        assertIs<AuthorizationDecision.Denied>(h.decision(write.requirement))
        h.accountEnabled = true; h.sessionActive = false
        assertIs<AuthorizationDecision.Denied>(h.decision(write.requirement))
        h.sessionActive = true; h.deviceAdmitted = false
        assertIs<AuthorizationDecision.Denied>(h.decision(write.requirement))
        assertEquals(before, h.snapshot())
    }

    @Test
    fun sref2009_realStorageDecoderRefusesUnknownSchemaBodyIdentityScopeAndExactKeyCorruption() = runTest {
        val h = SupplierReferenceV2HandlerFixture()
        h.commit(LocalSupplierRequestV2(null, f.supplier()), f.operation(202))
        val row = h.row(f.current)
        val record = decodeRecord(h.scope, row)
        assertFails { decodeRecord(h.scope, row.key, row.revision, "local.purchasing.supplier/v99", row.payload) }
        assertFails { decodeRecord(h.scope, row.key, row.revision, LocalConfigurationProfile.schema(row.key.kind), row.payload) }
        assertFails { decodeRecord(h.scope, AggregateKey(LocalConfigurationProfile.WAREHOUSE, row.key.id), row.revision, row.schema, row.payload) }
        assertFails { decodeRecord(h.scope, row.key, row.revision.next(), row.schema, row.payload) }
        assertFails { decodeRecord(BranchScope(f.id(1), f.id(10), f.id(12)), row.key, row.revision, row.schema, row.payload) }
        val wrongBody = record.copy(body = ConfigurationBody.Supplier(LocalSupplierV1(f.legacy, f.initialNames, true), Code.exact(f.literal)))
        assertFails { decodeRecord(h.scope, row.key, row.revision, row.schema, encodeValue(wrongBody, ConfigurationRecord.serializer())) }
        val corrupt = row.payload.copyBytes().decodeToString().replace("\"normalizedKey\":\" 0007-Ab  \"", "\"normalizedKey\":\"0007-Ab\"")
        assertNotEquals(row.payload.copyBytes().decodeToString(), corrupt)
        assertFails { decodeRecord(h.scope, row.key, row.revision, row.schema, StoredBytes(corrupt.encodeToByteArray())) }
        h.missingDirectoryRow = row.key
        assertFails { h.read(LocalSupplierQueryV2()) }
        h.missingDirectoryRow = null
        assertEquals(f.supplier(), h.read(LocalSupplierQueryV2(id = f.current)).detail)
        assertEquals(1, h.originals.size)
    }
}
