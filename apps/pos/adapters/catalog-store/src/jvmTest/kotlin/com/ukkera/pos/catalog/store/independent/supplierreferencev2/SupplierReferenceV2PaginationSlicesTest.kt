package com.ukkera.pos.catalog.store.independent.supplierreferencev2

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * P01–P04 supporting handler slices of the selected pagination 1.0.1 freeze.
 * Bound descriptor: public V2 page/detail DTOs; B: 65,536 raw canonical UTF-8 bytes.
 * The controlled keyset source is not native ordering/storage/current authority/RPC.
 * No expected fill count or largest-prefix algorithm is inferred from the implementation.
 */
class SupplierReferenceV2PaginationSlicesTest {
    private val f = SupplierReferenceV2PaginationInputs
    private val bound = 65_536

    @Test
    fun sref2p01_everyCompleteOneRowAndDetailFitsAndFiftyCompleteFixtureRowsQualifyForcedShort() = runTest {
        val (h, expected) = fixture()
        qualifyCompleteValues(expected)
        val before = h.snapshot()
        val firstBytes = h.readBytes(LocalSupplierQueryV2(limit = 50))
        val first = LocalSupplierCodecV2.decodePage(firstBytes)
        assertTrue(firstBytes.size <= bound)
        assertTrue(first.items.size in 1..49, "Qualified overlarge 50-row page must make nonempty complete progress")
        assertEquals(first.items.last().id, first.nextAfter)
        first.items.forEach { assertEquals(expected.getValue(it.id), it) }
        for (maximum in listOf(1, 2)) {
            val page = h.read(LocalSupplierQueryV2(limit = maximum))
            assertTrue(page.items.size in 1..maximum)
            assertNotNull(page.nextAfter)
            page.items.forEach { assertEquals(expected.getValue(it.id), it) }
        }
        assertEquals(before, h.snapshot())
        assertEquals(53, h.originals.size)
        val empty = SupplierReferenceV2HandlerFixture(f.scope, f.actor, f.device).read(LocalSupplierQueryV2(limit = 50))
        assertEquals(LocalSupplierPageV2(emptyList(), null, null), empty)
    }

    @Test
    fun sref2p02_independentStableOneTwoAndFiftyTraversalsEmitAllFiftyThreeExactRowsAndDetails() = runTest {
        for (maximum in listOf(1, 2, 50)) {
            val (h, expected) = fixture()
            qualifyCompleteValues(expected)
            val before = h.snapshot()
            val pages = traverse(h, expected, maximum)
            assertTrue(pages in when (maximum) { 1 -> 53..53; 2 -> 27..53; else -> 2..53 })
            assertEquals(before, h.snapshot())
            assertEquals(53, h.originals.size)
        }
    }

    @Test
    fun sref2p03_sameMaximumResumesAfterLastEmittedRowAndIdenticalPageRetryDoesNotAdvanceProgress() = runTest {
        val (h, expected) = fixture()
        qualifyCompleteValues(expected)
        val first = h.read(LocalSupplierQueryV2(limit = 50))
        assertTrue(first.items.size in 1..49)
        val cursor = assertNotNull(first.nextAfter)
        assertEquals(first.items.last().id, cursor)
        val query = LocalSupplierQueryV2(after = cursor, limit = 50)
        val confirmed = h.read(query)
        assertEquals(confirmed, h.read(query))
        assertTrue(confirmed.items.isNotEmpty())
        assertTrue(confirmed.items.none { row -> first.items.any { it.id == row.id } })
        val seen = first.items.map { it.id }.toMutableList()
        traverse(h, expected, 50, cursor, seen)
        assertEquals(53, seen.size)
        assertEquals(expected.keys, seen.toSet())
        assertEquals(53, h.originals.size)
        // Retry observations are excluded from forward progress. This pure read boundary
        // does not qualify lost RPC delivery, native reopen, or durable cursor storage.
    }

    @Test
    fun sref2p04_currentV1ProjectionNeverAddsReferenceOrErasesTheActualV2DirectoryValue() = runTest {
        val (h, expected) = fixture()
        val before = h.snapshot()
        var after: DomainId? = null
        val seen = mutableListOf<DomainId>()
        do {
            val page = h.readV1(LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, after = after, limit = 50))
            val json = localConfigurationJson.parseToJsonElement(
                encodeValue(page, LocalConfigurationPageV1.serializer()).copyBytes().decodeToString()).jsonObject
            for (item in json.getValue("items").jsonArray) assertFalse("reference" in item.jsonObject)
            for (row in page.items) {
                val exact = expected.getValue(row.id)
                assertEquals(exact.names, row.names)
                assertEquals(exact.active, row.active)
                assertEquals(exact.revision, row.revision)
                assertEquals(exact.storageRevision, row.storageRevision)
                val detail = h.readV1(LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, id = row.id))
                assertEquals(LocalConfigurationDetailV1.Supplier(LocalSupplierV1(row.id, exact.names, exact.active)), detail.detail)
                assertEquals(LocalSupplierV2(row.id, exact.names, exact.active, exact.reference),
                    h.read(LocalSupplierQueryV2(id = row.id)).detail)
                seen += row.id
            }
            after = page.nextAfter
        } while (after != null)
        assertEquals(53, seen.size)
        assertEquals(expected.keys, seen.toSet())
        assertEquals(before, h.snapshot())
        // Current V1 shape/projection only. Genuine pre-transition V1 read grammar/bytes
        // and old-binary behavior remain unverified, not minted from this serialization.
    }

    private suspend fun fixture(): Pair<SupplierReferenceV2HandlerFixture, Map<DomainId, LocalSupplierListItemV2>> {
        val h = SupplierReferenceV2HandlerFixture(f.scope, f.actor, f.device)
        val expected = linkedMapOf<DomainId, LocalSupplierListItemV2>()
        for (row in f.rows) {
            val result = h.commit(LocalSupplierRequestV2(null, row.supplier), row.operation)
            val domain = assertNotNull(result.revision)
            expected[row.supplier.id] = LocalSupplierListItemV2(row.supplier.id, domain, h.storageRevision(row.supplier.id),
                row.supplier.names, row.supplier.active, row.supplier.reference)
        }
        return h to expected
    }

    /** Qualify complete frozen fields before comparing any returned page. */
    private fun qualifyCompleteValues(expected: Map<DomainId, LocalSupplierListItemV2>) {
        assertEquals(bound, LocalSupplierProfileV2.MAXIMUM_PAGE_BYTES)
        for (row in expected.values) {
            assertEquals(160, row.names.english.length)
            assertEquals(160, row.names.arabic.length)
            assertEquals(256, row.reference!!.original.length)
            assertEquals(row.reference.original, row.reference.normalizedKey)
            assertTrue(rawSize(LocalSupplierPageV2(listOf(row), row.id)) <= bound)
            assertTrue(rawSize(LocalSupplierPageV2(listOf(row), null,
                LocalSupplierV2(row.id, row.names, row.active, row.reference))) <= bound)
        }
        val fifty = expected.values.take(50)
        assertTrue(rawSize(LocalSupplierPageV2(fifty, fifty.last().id)) > bound,
            "Missing complete-value forced-short qualification must fail, not bless truncated output")
    }

    private fun rawSize(page: LocalSupplierPageV2): Int =
        localConfigurationJson.encodeToString(LocalSupplierPageV2.serializer(), page).encodeToByteArray().size

    private suspend fun traverse(h: SupplierReferenceV2HandlerFixture, expected: Map<DomainId, LocalSupplierListItemV2>,
        maximum: Int, initialAfter: DomainId? = null, seen: MutableList<DomainId> = mutableListOf()): Int {
        var after = initialAfter
        var pages = 0
        do {
            assertTrue(pages < 53, "Continuation must make bounded progress")
            val bytes = h.readBytes(LocalSupplierQueryV2(after = after, limit = maximum))
            assertTrue(bytes.size <= bound)
            val page = LocalSupplierCodecV2.decodePage(bytes)
            assertTrue(page.items.size in 1..maximum, "An eligible remainder cannot become terminal empty success")
            page.items.forEach { row ->
                assertFalse(row.id in seen, "No duplicate forward-progress supplier")
                assertEquals(expected.getValue(row.id), row)
                val detailBytes = h.readBytes(LocalSupplierQueryV2(id = row.id, limit = 1))
                assertTrue(detailBytes.size <= bound)
                assertEquals(LocalSupplierV2(row.id, row.names, row.active, row.reference), LocalSupplierCodecV2.decodePage(detailBytes).detail)
                seen += row.id
            }
            if (page.nextAfter != null) assertEquals(page.items.last().id, page.nextAfter)
            after = page.nextAfter
            pages++
        } while (after != null)
        assertEquals(53, seen.size)
        assertEquals(53, seen.distinct().size)
        assertEquals(expected.keys, seen.toSet())
        return pages
    }
}
