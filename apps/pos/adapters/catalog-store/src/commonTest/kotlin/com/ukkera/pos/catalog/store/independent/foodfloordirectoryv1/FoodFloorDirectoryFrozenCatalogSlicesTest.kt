package com.ukkera.pos.catalog.store.independent.foodfloordirectoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Supporting semantic slices of frozen food-floor-directory-v1. DTO serialization supplies
 * codec inputs, never independently specified canonical bytes. The labeled unit fixture runs
 * actual handlers but does not qualify whole cases, native history/transactions, authority,
 * admitted RPC, business sentinels, restart, fault/restore or installed platforms.
 */
class FoodFloorDirectoryFrozenCatalogSlicesTest {
    private val f = FfdCatalogInputs
    private val dtoJson = Json { encodeDefaults = true }

    @Test
    fun ffd004ValidLiteralNamesAndUtf16BoundariesSurviveFloorAndTableCodecs() {
        for (vector in f.names.filter { it.accepts }) for (swap in listOf(false, true)) {
            val names = LocalNamesV1(if (swap) vector.arabic else vector.english, if (swap) vector.english else vector.arabic)
            val requests = listOf(LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(names = names)),
                LocalFoodFloorConfigurationRequestV1.Table(Revision(11), f.table010.copy(names = names)))
            for (request in requests) assertEquals(request, LocalFoodFloorCodecV1.decode(LocalFoodFloorCodecV1.encode(request)), vector.label)
        }
        assertEquals(160, "😀".repeat(80).length)
        assertEquals(161, ("😀".repeat(80) + "x").length)
    }

    @Test
    fun ffd004BlankOverlengthAndEveryAsciiControlRefuseInEitherLanguage() {
        for (vector in f.names.filterNot { it.accepts } + f.controls()) for (swap in listOf(false, true)) {
            assertFails(vector.label) {
                LocalFoodFloorDefinitionV1(f.floorA.id, LocalNamesV1(if (swap) vector.arabic else vector.english,
                    if (swap) vector.english else vector.arabic), 700, true)
            }
            assertFails(vector.label) {
                LocalFoodTableDefinitionV1(f.table010.id, f.floorA.id, LocalNamesV1(if (swap) vector.arabic else vector.english,
                    if (swap) vector.english else vector.arabic), 9000, true)
            }
        }
    }

    @Test
    fun ffd005OrderAndMalformedIdentifierVectorsRefuseWithoutCoercion() {
        for (order in listOf(0, 9999)) {
            assertEquals(order, f.floorA.copy(layoutOrder = order).layoutOrder)
            assertEquals(order, f.table010.copy(layoutOrder = order).layoutOrder)
        }
        for (order in listOf(-1, 10000)) {
            assertFails { f.floorA.copy(layoutOrder = order) }
            assertFails { f.table010.copy(layoutOrder = order) }
        }
        val valid = LocalFoodFloorCodecV1.encode(LocalFoodFloorConfigurationRequestV1.Table(Revision(11), f.table010))
            .copyBytes().decodeToString()
        for (id in listOf("", "not-a-uuid", "a2000000-0000-4000-8000-00000000003")) {
            assertFails { DomainId(id) }
            assertFails { LocalFoodFloorCodecV1.decode(StoredBytes(valid.replace(f.table010.id.value, id).encodeToByteArray())) }
        }
    }

    @Test
    fun ffd026CommandsResultsAndLargeRevisionsRetainExactSemanticValues() {
        val requests = listOf(LocalFoodFloorConfigurationRequestV1.Floor(null, f.newFloor),
            LocalFoodFloorConfigurationRequestV1.Table(null, f.newTable),
            LocalFoodFloorConfigurationRequestV1.Table(Revision(1), f.replacement),
            LocalFoodFloorConfigurationRequestV1.Floor(Revision(FfdCatalogInputs.LARGE_REVISION), f.floorA))
        for ((index, request) in requests.withIndex()) {
            val operation = f.operation(26, index + 1)
            val command = LocalFoodFloorCodecV1.command(operation, request)
            assertEquals(operation, command.operationId)
            assertEquals(LocalFoodFloorProfileV1.COMMAND, command.command)
            assertNull(command.actionApprovalId)
            assertEquals(request, LocalFoodFloorCodecV1.decode(command.payload))
        }
        val saved = LocalConfigurationResultV1("food.floor.saved", f.floorA.id, Revision(FfdCatalogInputs.LARGE_REVISION))
        val decoded = LocalFoodFloorCodecV1.decodeResult(resultBytes(saved))
        assertEquals(saved, decoded)
        assertEquals(9_007_199_254_740_993L, decoded.revision!!.value)
        assertNotEquals(9_007_199_254_740_992L, decoded.revision!!.value)
        for (code in f.rejectionCodes) {
            val rejection = LocalFoodFloorCodecV1.decodeResult(resultBytes(LocalConfigurationResultV1(code, null, null)))
            assertEquals(code, rejection.code)
            assertNull(rejection.id)
            assertNull(rejection.revision)
        }
    }

    @Test
    fun ffd002026ReadDtoRoundTripsPreserveBaseQueryEchoRowsAndLiteralFields() {
        val floorA = LocalFoodFloorRowV1(f.floorA, Revision(7))
        val floorB = LocalFoodFloorRowV1(f.floorB, Revision(3))
        val table010 = LocalFoodTableRowV1(f.table010, Revision(11))
        val pages = listOf(
            LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Floors(), true, floors = listOf(floorA, floorB)),
            LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Tables(f.floorA.id), true, tables = listOf(table010,
                LocalFoodTableRowV1(f.table030, Revision(5))), floor = floorA),
            LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Tables(f.floorB.id), true,
                tables = listOf(LocalFoodTableRowV1(f.table020, Revision(2))), floor = floorB),
            LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Table(f.table010.id), true, tables = listOf(table010), floor = floorA),
            LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Table(f.unknownTable), true),
            LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Tables(f.capacityFloor(1).id, f.capacityTable(20).id, 20), true,
                tables = (21..40).map { LocalFoodTableRowV1(f.capacityTable(it), Revision(1)) },
                floor = LocalFoodFloorRowV1(f.capacityFloor(1), Revision(FfdCatalogInputs.LARGE_REVISION)),
                nextAfter = f.capacityTable(40).id),
        )
        for (page in pages) {
            val query = LocalFoodFloorReadCodecV1.read(page.query)
            assertEquals(LocalFoodFloorReadCodecV1.QUERY, query.query)
            assertEquals(LocalFoodFloorReadCodecV1.encode(page.query), LocalFoodFloorReadHandler().normalize(query.payload))
            assertEquals(page, LocalFoodFloorReadCodecV1.decodeResult(pageBytes(page)))
        }
    }

    @Test
    fun ffd025BoundQueryLimitsAcceptOneAndTwentyAndRefuseZeroTwentyOneAndMinusOne() {
        for (limit in listOf(1, 20)) {
            assertEquals(limit, LocalFoodFloorQueryV1.Floors(limit = limit).limit)
            assertEquals(limit, LocalFoodFloorQueryV1.Tables(f.capacityFloor(1).id, limit = limit).limit)
        }
        for (limit in listOf(0, 21, -1)) {
            assertFails { LocalFoodFloorQueryV1.Floors(limit = limit) }
            assertFails { LocalFoodFloorQueryV1.Tables(f.capacityFloor(1).id, limit = limit) }
        }
    }

    @Test
    fun ffd001EmptyUnitReadHandlerInventsNoDefinitionsOrSelection(): Unit = runTest {
        val unit = FoodFloorUnitDirectoryFixture()
        val before = unit.snapshot()
        for (query in listOf(LocalFoodFloorQueryV1.Floors(), LocalFoodFloorQueryV1.Tables(f.unknownFloor),
            LocalFoodFloorQueryV1.Table(f.unknownTable))) {
            val page = unit.read(query)
            assertEquals(query, page.query)
            assertFalse(page.configured)
            assertTrue(page.floors.isEmpty())
            assertTrue(page.tables.isEmpty())
            assertNull(page.floor)
            assertNull(page.nextAfter)
        }
        assertEquals(before, unit.snapshot())
    }

    @Test
    fun ffd003UnitHandlerCreatesAndReplacesExactTargetsAndPerDefinitionRevisions(): Unit = runTest {
        val unit = FoodFloorUnitDirectoryFixture.base()
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Floor(null, f.newFloor), f.operation(3, 1)), f.newFloor.id, 1)
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Table(null, f.newTable), f.operation(3, 2)), f.newTable.id, 1)
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Table(Revision(1), f.replacement), f.operation(3, 3)), f.newTable.id, 2)
        val floors = unit.read(LocalFoodFloorQueryV1.Floors())
        assertEquals(listOf(f.floorA.id, f.floorB.id, f.newFloor.id), floors.floors.map { it.definition.id })
        assertEquals(listOf(Revision(7), Revision(3), Revision(1)), floors.floors.map { it.storageRevision })
        val detail = unit.read(LocalFoodFloorQueryV1.Table(f.newTable.id))
        assertEquals(f.replacement, detail.tables.single().definition)
        assertEquals(Revision(2), detail.tables.single().storageRevision)
        assertEquals(f.newFloor, detail.floor!!.definition)
        assertEquals(Revision(1), detail.floor!!.storageRevision)
        assertEquals(31, unit.snapshot().operations.size) // Unit handler outputs, not native retained-history qualification.
    }

    @Test
    fun ffd007008016ListedBusinessRefusalsAndFfd010SameInputStaleFragmentPreserveUnitDirectory(): Unit = runTest {
        val cases = listOf(
            "food.table.floor_required" to LocalFoodFloorConfigurationRequestV1.Table(null, f.newTable.copy(floorId = f.unknownFloor)),
            "food.floor.identity_conflict" to LocalFoodFloorConfigurationRequestV1.Table(null, f.newTable.copy(id = f.floorA.id, floorId = f.floorA.id)),
            "food.floor.identity_conflict" to LocalFoodFloorConfigurationRequestV1.Floor(null, f.newFloor.copy(id = f.table010.id)),
            "food.floor.active_tables" to LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(active = false)),
        )
        for ((index, case) in cases.withIndex()) {
            val unit = FoodFloorUnitDirectoryFixture.base()
            assertUnitRefusal(unit, case.second, f.operation(if (index == 3) 8 else 7, index + 1), case.first)
        }
        val stale = FoodFloorUnitDirectoryFixture.base()
        val replacement = LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(names = f.defaultNames))
        assertSaved(stale.commit(replacement, f.operation(10, 1)), f.floorA.id, 8)
        // Supporting same-input stale fragment only. FFD-010's required different second
        // replacement is not represented; no distinct input is invented from its default vector.
        assertUnitRefusal(stale, replacement, f.operation(10, 2), "food.floor.stale")
        val unit = FoodFloorUnitDirectoryFixture.base()
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Floor(Revision(3), f.floorB.copy(active = true)), f.operation(16, 1)), f.floorB.id, 4)
        assertUnitRefusal(unit, LocalFoodFloorConfigurationRequestV1.Table(Revision(11), f.table010.copy(floorId = f.floorB.id)),
            f.operation(16, 2), "food.table.floor_changed")
    }

    @Test
    fun ffd008UnitHandlerDisableSequenceKeepsDisabledParentAndChildSelectable(): Unit = runTest {
        val unit = FoodFloorUnitDirectoryFixture.base()
        assertUnitRefusal(unit, LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(active = false)),
            f.operation(8, 1), "food.floor.active_tables")
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Table(Revision(11), f.table010.copy(active = false)), f.operation(8, 2)), f.table010.id, 12)
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Floor(Revision(7), f.floorA.copy(active = false)), f.operation(8, 3)), f.floorA.id, 8)
        val page = unit.read(LocalFoodFloorQueryV1.Table(f.table010.id))
        assertFalse(page.tables.single().definition.active)
        assertFalse(page.floor!!.definition.active)
        assertEquals(Revision(12), page.tables.single().storageRevision)
        assertEquals(Revision(8), page.floor!!.storageRevision)
        assertEquals(30, unit.snapshot().operations.size)
        assertUnitRefusal(unit, LocalFoodFloorConfigurationRequestV1.Table(Revision(12), f.table010), f.operation(8, 4), "food.table.floor_required")
    }

    @Test
    fun ffd006UnitHandlerCountsDisabledCapacityAndStillReplacesExistingDefinitions(): Unit = runTest {
        val unit = FoodFloorUnitDirectoryFixture.capacity()
        assertUnitRefusal(unit, LocalFoodFloorConfigurationRequestV1.Floor(null, f.capacityFloor(17)), f.operation(6, 1), "food.floor.capacity")
        assertUnitRefusal(unit, LocalFoodFloorConfigurationRequestV1.Table(null,
            f.capacityTable(1).copy(id = f.serialId("a3000000", 101))), f.operation(6, 2), "food.floor.capacity")
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Floor(Revision(1), f.capacityFloor(1).copy(names = f.defaultNames)), f.operation(6, 3)), f.capacityFloor(1).id, 2)
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Table(Revision(1), f.capacityTable(1).copy(names = f.defaultNames)), f.operation(6, 4)), f.capacityTable(1).id, 2)
        assertEquals(16, unit.read(LocalFoodFloorQueryV1.Floors()).floors.size)
        assertEquals(118, unit.snapshot().operations.size)
    }

    @Test
    fun ffd025029UnitReadHandlerKeepsIdKeysetPagesCursorsAndDetachedCuts(): Unit = runTest {
        val unit = FoodFloorUnitDirectoryFixture.capacity()
        val beforeReads = unit.snapshot()
        val floors = unit.read(LocalFoodFloorQueryV1.Floors())
        assertEquals((1..16).map { f.capacityFloor(it).id }, floors.floors.map { it.definition.id })
        assertNull(floors.nextAfter)
        for ((floor, ranges) in listOf(1 to listOf(1..20, 21..40, 41..45), 2 to listOf(46..65, 66..85, 86..100))) {
            var after: DomainId? = null
            for ((index, range) in ranges.withIndex()) {
                val query = LocalFoodFloorQueryV1.Tables(f.capacityFloor(floor).id, after, 20)
                val page = unit.read(query)
                assertEquals(query, page.query)
                assertEquals(range.map { f.capacityTable(it).id }, page.tables.map { it.definition.id })
                assertEquals(range.map { f.capacityTable(it) }, page.tables.map { it.definition })
                assertEquals(f.capacityFloor(floor), page.floor!!.definition)
                assertEquals(Revision(1), page.floor!!.storageRevision)
                val expectedCursor = if (index == 2) null else f.capacityTable(range.last).id
                assertEquals(expectedCursor, page.nextAfter)
                after = page.nextAfter
            }
        }
        val one = unit.read(LocalFoodFloorQueryV1.Tables(f.capacityFloor(1).id, limit = 1))
        assertEquals(listOf(f.capacityTable(1).id), one.tables.map { it.definition.id })
        assertEquals(f.capacityTable(1).id, one.nextAfter)
        assertEquals(beforeReads, unit.snapshot())

        val query = LocalFoodFloorQueryV1.Tables(f.capacityFloor(1).id, f.capacityTable(20).id)
        val old = unit.read(query)
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Floor(Revision(1), f.capacityFloor(1).copy(
            names = LocalNamesV1("Updated Floor 01", "صالة محدثة"))), f.operation(29, 1)), f.capacityFloor(1).id, 2)
        assertSaved(unit.commit(LocalFoodFloorConfigurationRequestV1.Table(Revision(1), f.capacityTable(21).copy(
            names = LocalNamesV1("Updated Table 021", "طاولة محدثة"))), f.operation(29, 2)), f.capacityTable(21).id, 2)
        val fresh = unit.read(query)
        assertEquals(f.capacityFloor(1), old.floor!!.definition)
        assertEquals(Revision(1), old.floor!!.storageRevision)
        assertEquals(f.capacityTable(21), old.tables.first().definition)
        assertEquals(Revision(1), old.tables.first().storageRevision)
        assertEquals(LocalNamesV1("Updated Floor 01", "صالة محدثة"), fresh.floor!!.definition.names)
        assertEquals(Revision(2), fresh.floor!!.storageRevision)
        assertEquals(LocalNamesV1("Updated Table 021", "طاولة محدثة"), fresh.tables.first().definition.names)
        assertEquals(Revision(2), fresh.tables.first().storageRevision)
    }

    private suspend fun assertUnitRefusal(unit: FoodFloorUnitDirectoryFixture, request: LocalFoodFloorConfigurationRequestV1,
        operation: OperationId, code: String) {
        val before = unit.snapshot()
        val outcome = assertIs<ProposedOutcome.Reject>(unit.propose(request, operation))
        assertEquals(code, outcome.code)
        assertEquals(LocalConfigurationResultV1(code, null, null), LocalFoodFloorCodecV1.decodeResult(outcome.result))
        assertEquals(before, unit.snapshot())
    }
    private fun assertSaved(result: LocalConfigurationResultV1, id: DomainId, revision: Long) {
        assertEquals(LocalConfigurationResultV1("food.floor.saved", id, Revision(revision)), result)
    }
    private fun resultBytes(result: LocalConfigurationResultV1) = StoredBytes(dtoJson.encodeToString(LocalConfigurationResultV1.serializer(), result).encodeToByteArray())
    private fun pageBytes(page: LocalFoodFloorPageV1) = StoredBytes(dtoJson.encodeToString(LocalFoodFloorPageV1.serializer(), page).encodeToByteArray())
}
