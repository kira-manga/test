package com.ukkera.pos.catalog.store.independent.foodfloordirectoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * NFFD-003 semantic supporting slice from frozen native-food-floor-directory-v1,
 * fixtureVersion native-food-floor-directory.1.0-draft, fixtures.json base/cas.
 * The existing unit BASE runs real handlers with controlled synthetic infrastructure.
 * Native allocation, current authority, retained-history proof, durability and admitted RPC
 * remain NOT_RUN. This supplement does not amend or qualify whole FFD-010.
 */
class NativeFoodFloorCasFrozenCatalogSlicesTest {
    private val baseFloorA = LocalFoodFloorDefinitionV1(
        DomainId("f1000000-0000-4000-8000-000000000010"), LocalNamesV1("Dining 10", "صالة ١٠"), 700, true)
    private val baseFloorB = LocalFoodFloorDefinitionV1(
        DomainId("f1000000-0000-4000-8000-000000000020"), LocalNamesV1("Terrace 02", "شرفة ٠٢"), 100, false)
    private val baseTable010 = LocalFoodTableDefinitionV1(
        DomainId("a2000000-0000-4000-8000-000000000010"), baseFloorA.id,
        LocalNamesV1("Table 002", "طاولة ٠٠٢"), 9000, true)
    private val baseTable020 = LocalFoodTableDefinitionV1(
        DomainId("a2000000-0000-4000-8000-000000000020"), baseFloorB.id,
        LocalNamesV1("", "طاولة الشرفة"), 3, false)
    private val baseTable030 = LocalFoodTableDefinitionV1(
        DomainId("a2000000-0000-4000-8000-000000000030"), baseFloorA.id,
        LocalNamesV1("007", ""), 0, false)

    private val floorsQuery = LocalFoodFloorQueryV1.Floors(null, 20)
    private val floorATablesQuery = LocalFoodFloorQueryV1.Tables(baseFloorA.id, null, 20)
    private val floorBTablesQuery = LocalFoodFloorQueryV1.Tables(baseFloorB.id, null, 20)
    private val tableQuery = LocalFoodFloorQueryV1.Table(baseTable010.id)

    @Test
    fun nffd003ExactFrozenDistinctFloorAndTableCasRequestsPreserveUnitDirectory(): Unit = runTest {
        val unit = FoodFloorUnitDirectoryFixture.base()
        val initialReadbacks = readbacks(unit)
        assertDirectory(initialReadbacks, baseFloorA, baseTable010, listOf(7L, 3L, 11L, 2L, 5L))
        assertEquals(28, unit.snapshot().operations.size)
        val capturedFloorRevision = initialReadbacks.floors.floors.single { it.definition.id == baseFloorA.id }.storageRevision
        val capturedTableRevision = initialReadbacks.table.tables.single().storageRevision
        assertEquals(Revision(7), capturedFloorRevision)
        assertEquals(Revision(11), capturedTableRevision)

        val savedNames = LocalNamesV1("Configured Update", "تحديث الإعداد")
        val staleNames = LocalNamesV1("Floor Version A", "صالة النسخة أ")
        val savedFloor = baseFloorA.copy(names = savedNames)
        val savedTable = baseTable010.copy(names = savedNames)
        val steps = listOf(
            FrozenCasStep(OperationId("d5000000-0000-4000-8000-000000000bb9"),
                LocalFoodFloorConfigurationRequestV1.Floor(capturedFloorRevision, savedFloor),
                LocalConfigurationResultV1("food.floor.saved", baseFloorA.id, Revision(8)),
                savedFloor, baseTable010, listOf(8L, 3L, 11L, 2L, 5L), 29),
            FrozenCasStep(OperationId("d5000000-0000-4000-8000-000000000bba"),
                LocalFoodFloorConfigurationRequestV1.Floor(capturedFloorRevision,
                    baseFloorA.copy(names = staleNames, layoutOrder = 8)),
                LocalConfigurationResultV1("food.floor.stale", null, null),
                savedFloor, baseTable010, listOf(8L, 3L, 11L, 2L, 5L), 29),
            FrozenCasStep(OperationId("d5000000-0000-4000-8000-000000000bbb"),
                LocalFoodFloorConfigurationRequestV1.Table(capturedTableRevision, savedTable),
                LocalConfigurationResultV1("food.floor.saved", baseTable010.id, Revision(12)),
                savedFloor, savedTable, listOf(8L, 3L, 12L, 2L, 5L), 30),
            FrozenCasStep(OperationId("d5000000-0000-4000-8000-000000000bbc"),
                LocalFoodFloorConfigurationRequestV1.Table(capturedTableRevision,
                    baseTable010.copy(names = staleNames, layoutOrder = 8)),
                LocalConfigurationResultV1("food.floor.stale", null, null),
                savedFloor, savedTable, listOf(8L, 3L, 12L, 2L, 5L), 30),
        )

        for (step in steps) {
            val before = unit.snapshot()
            val beforeReadbacks = readbacks(unit)
            val afterReadbacks: UnitReadbacks
            if (step.expectedResult.code == "food.floor.stale") {
                val originalsBefore = before.operations.map { unit.retainedCommand(it) }
                assertTrue(originalsBefore.all { it != null })
                val refusal = assertIs<ProposedOutcome.Reject>(unit.propose(step.request, step.operation))
                assertEquals("food.floor.stale", refusal.code)
                assertEquals(step.expectedResult, LocalFoodFloorCodecV1.decodeResult(refusal.result))
                assertEquals(before, unit.snapshot())
                // Equality preserves unit bookkeeping; it is not native history qualification.
                assertEquals(originalsBefore, before.operations.map { unit.retainedCommand(it) })
                afterReadbacks = readbacks(unit)
                assertEquals(beforeReadbacks, afterReadbacks)
            } else {
                assertEquals(step.expectedResult, unit.commit(step.request, step.operation))
                afterReadbacks = readbacks(unit)
            }
            assertDirectory(afterReadbacks, step.expectedFloorA, step.expectedTable010, step.expectedRevisions)
            // Accepted handler outputs only; no native retained-result/audit/outbox census is supplied.
            assertEquals(step.expectedSuccessfulCount, unit.snapshot().operations.size)
        }
    }

    private data class FrozenCasStep(
        val operation: OperationId,
        val request: LocalFoodFloorConfigurationRequestV1,
        val expectedResult: LocalConfigurationResultV1,
        val expectedFloorA: LocalFoodFloorDefinitionV1,
        val expectedTable010: LocalFoodTableDefinitionV1,
        val expectedRevisions: List<Long>,
        val expectedSuccessfulCount: Int,
    )

    private data class UnitReadbacks(
        val floors: LocalFoodFloorPageV1,
        val floorATables: LocalFoodFloorPageV1,
        val floorBTables: LocalFoodFloorPageV1,
        val table: LocalFoodFloorPageV1,
    )

    private suspend fun readbacks(unit: FoodFloorUnitDirectoryFixture) = UnitReadbacks(
        unit.read(floorsQuery), unit.read(floorATablesQuery), unit.read(floorBTablesQuery), unit.read(tableQuery))

    private fun assertDirectory(readbacks: UnitReadbacks, expectedFloorA: LocalFoodFloorDefinitionV1,
        expectedTable010: LocalFoodTableDefinitionV1, expectedRevisions: List<Long>) {
        // Vector order is exactly cas: F-A, F-B, T-010, T-020, T-030.
        val floorA = LocalFoodFloorRowV1(expectedFloorA, Revision(expectedRevisions[0]))
        val floorB = LocalFoodFloorRowV1(baseFloorB, Revision(expectedRevisions[1]))
        val table010 = LocalFoodTableRowV1(expectedTable010, Revision(expectedRevisions[2]))
        val table020 = LocalFoodTableRowV1(baseTable020, Revision(expectedRevisions[3]))
        val table030 = LocalFoodTableRowV1(baseTable030, Revision(expectedRevisions[4]))
        assertEquals(LocalFoodFloorPageV1(floorsQuery, true, floors = listOf(floorA, floorB)), readbacks.floors)
        assertEquals(LocalFoodFloorPageV1(floorATablesQuery, true,
            tables = listOf(table010, table030), floor = floorA), readbacks.floorATables)
        assertEquals(LocalFoodFloorPageV1(floorBTablesQuery, true,
            tables = listOf(table020), floor = floorB), readbacks.floorBTables)
        assertEquals(LocalFoodFloorPageV1(tableQuery, true,
            tables = listOf(table010), floor = floorA), readbacks.table)
        assertEquals(2, readbacks.floors.floors.size)
        assertEquals(3, readbacks.floorATables.tables.size + readbacks.floorBTables.tables.size)
    }
}
