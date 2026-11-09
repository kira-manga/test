@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodfloordirectoryv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.foodfloordirectoryv1.FloorUiFixture.Companion.withController
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Supporting common controller/editor slices of frozen food-floor-directory-v1. Scripted
 * pages, form descriptors and pending/result replies are presentation inputs, never actual
 * Master reads, authenticated grants, durable commands, history or installed UI acceptance.
 */
class FoodFloorDirectoryFrozenControllerSlicesTest {
    private val f = FfdUiInputs

    @Test
    fun ffd001028ExplicitNewEditorOpeningsAllocateOneTargetAndRapidReentryRetainsIt(): Unit = runTest {
        for ((query, action, field) in listOf(
            Triple(LocalFoodUiQuery.FloorDirectory(), LocalAction.FLOOR_CONFIGURE, LocalField.FLOOR_ID),
            Triple(LocalFoodUiQuery.FloorTables(f.createdFloor.id), LocalAction.TABLE_CONFIGURE, LocalField.TABLE_ID))) {
            withController(query) { fixture ->
                assertNull(fixture.controller.state.value.form)
                assertNull(fixture.controller.state.value.operationId)
                val source = fixture.controller.state.value.page!!.forms.single { it.action == action }
                assertNull(source.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == field }.initialValue)
                fixture.port.assertNoMutationCalls()
                fixture.controller.openForm(action, fixture.token())
                val first = fixture.controller.state.value.form!!.identifier(field)
                repeat(8) { fixture.controller.openForm(action, fixture.token()) }
                assertEquals(first, fixture.controller.state.value.form!!.identifier(field))
                assertSame(fixture.controller, fixture.currentController())
                assertEquals(first, fixture.controller.state.value.form!!.identifier(field))
                fixture.controller.discardForm(fixture.token())
                fixture.controller.openForm(action, fixture.token())
                val second = fixture.controller.state.value.form!!.identifier(field)
                assertNotEquals(first, second)
                assertNull(fixture.controller.state.value.operationId)
                fixture.port.assertNoMutationCalls()
            }
        }
    }

    @Test
    fun ffd008016028ExistingTableReentryCapturesExactTargetRevisionFixedFloorAndUncheckedFalse(): Unit = runTest {
        withController(LocalFoodUiQuery.TableDefinition(f.table010.id)) { fixture ->
            repeat(3) {
                fixture.controller.openForm(LocalAction.TABLE_CONFIGURE, fixture.token())
                val draft = fixture.controller.state.value.form!!
                assertEquals(f.table010.id, draft.identifier(LocalField.TABLE_ID))
                assertEquals(f.floorA.id, draft.identifier(LocalField.FLOOR_ID))
                assertEquals("11", draft.values.getValue(LocalField.EXPECTED_STORAGE_REVISION).text)
                assertEquals("Table 002", draft.values.getValue(LocalField.NAME_ENGLISH).text)
                assertEquals("طاولة ٠٠٢", draft.values.getValue(LocalField.NAME_ARABIC).text)
                fixture.controller.discardForm(fixture.token())
            }
            fixture.controller.openForm(LocalAction.TABLE_CONFIGURE, fixture.token())
            fixture.controller.edit(LocalField.TABLE_ID, LocalDraftValue(f.table020.id.value), fixture.token())
            fixture.controller.edit(LocalField.FLOOR_ID, LocalDraftValue(f.floorB.id.value), fixture.token())
            fixture.controller.edit(LocalField.EXPECTED_STORAGE_REVISION, LocalDraftValue("2"), fixture.token())
            fixture.controller.edit(LocalField.ACTIVE, LocalDraftValue(selected = LocalScalarValue.Flag(false)), fixture.token())
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            val original = fixture.port.stages.single()
            assertEquals(LocalAction.TABLE_CONFIGURE, original.action)
            assertEquals(1, original.formVersion)
            assertNull(original.reference)
            assertNull(original.lineField)
            assertTrue(original.lines.isEmpty())
            assertEquals(LocalScalarValue.Identifier(f.table010.id), original.scalar(LocalField.TABLE_ID))
            assertEquals(LocalScalarValue.Identifier(f.floorA.id), original.scalar(LocalField.FLOOR_ID))
            assertEquals(LocalScalarValue.Integer(11), original.scalar(LocalField.EXPECTED_STORAGE_REVISION))
            assertEquals(LocalScalarValue.Text("Table 002"), original.scalar(LocalField.NAME_ENGLISH))
            assertEquals(LocalScalarValue.Text("طاولة ٠٠٢"), original.scalar(LocalField.NAME_ARABIC))
            assertEquals(LocalScalarValue.Flag(false), original.scalar(LocalField.ACTIVE))
            assertEquals(original.operationId, fixture.controller.state.value.operationId)
            fixture.port.assertNoDispatchOrRecoveryCalls()
        }
    }

    @Test
    fun ffd025029RefreshReturnsToFirstPageKeepsFloorAndDetachesEarlierCut(): Unit = runTest {
        val second = LocalFoodUiQuery.FloorTables(f.capacityFloor.id, f.capacityTable(20).id)
        withController(second) { fixture ->
            val oldPage = fixture.controller.state.value.page!!.food!!
            val old = assertIs<LocalFoodUiBody.Tables>(oldPage.body)
            assertEquals((21..40).map { f.capacityTable(it).id }, old.tables.map { it.id })
            fixture.port.updatedCapacity = true
            fixture.controller.selectFood(second, fixture.token())
            runCurrent()
            val fresh = assertIs<LocalFoodUiBody.Tables>(fixture.controller.state.value.page!!.food!!.body)
            assertEquals(Revision(2), fresh.floor.storageRevision)
            assertEquals(LocalText("Updated Floor 01", "صالة محدثة"), fresh.floor.names)
            assertEquals(Revision(2), fresh.tables.first().storageRevision)
            assertEquals(LocalText("Updated Table 021", "طاولة محدثة"), fresh.tables.first().names)
            assertEquals(Revision(1), old.floor.storageRevision)
            assertEquals(f.capacityFloor.names, old.floor.names)
            assertEquals(Revision(1), old.tables.first().storageRevision)
            assertEquals(f.capacityTable(21).names, old.tables.first().names)

            fixture.controller.refresh(fixture.token())
            runCurrent()
            val first = fixture.controller.state.value.page!!.food!!
            assertEquals(LocalFoodUiQuery.FloorTables(f.capacityFloor.id), first.query)
            assertEquals(LocalFoodUiQuery.FloorTables(f.capacityFloor.id), fixture.port.reads.last().food)
            val firstBody = assertIs<LocalFoodUiBody.Tables>(first.body)
            assertEquals(f.capacityFloor.id, firstBody.floor.id)
            assertEquals(Revision(2), firstBody.floor.storageRevision)
            assertEquals((1..20).map { f.capacityTable(it).id }, firstBody.tables.map { it.id })
            assertEquals(LocalFoodUiQuery.FloorTables(f.capacityFloor.id, f.capacityTable(20).id), first.next)
            fixture.port.assertNoMutationCalls()
        }
    }

    @Test
    fun ffd019FailedReadAndLateRetiredSessionReplyCannotRestorePrivatePage(): Unit = runTest {
        withController(LocalFoodUiQuery.TableDefinition(f.table010.id)) { fixture ->
            val old = assertNotNull(fixture.controller.state.value.page)
            fixture.port.readProblem = LocalServiceProblem.UNAVAILABLE
            fixture.controller.refresh(fixture.token())
            runCurrent()
            assertNull(fixture.controller.state.value.page)
            assertNotNull(fixture.controller.state.value.readProblem)
            fixture.port.readProblem = null
            val held = fixture.port.holdNextRead()
            fixture.controller.refresh(fixture.token())
            runCurrent()
            assertTrue(held.requested.isCompleted)
            fixture.retireSession()
            held.reply.complete(LocalReadResult.Available(old))
            runCurrent()
            assertTrue(held.returned.isCompleted)
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            assertNull(fixture.controller.state.value.reference)
            fixture.port.assertNoMutationCalls()
        }
    }

    @Test
    fun ffd028029SavedLookingResultForBKeepsAsOriginalIdInputAndRecovery(): Unit = runTest {
        withController(LocalFoodUiQuery.FloorDirectory()) { fixture ->
            fixture.controller.openForm(LocalAction.FLOOR_CONFIGURE, fixture.token())
            val selectedId = fixture.controller.state.value.form!!.identifier(LocalField.FLOOR_ID)
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            val original = fixture.port.stages.single()
            val originalValues = original.values
            val a = original.operationId
            assertEquals(LocalScalarValue.Identifier(selectedId), original.scalar(LocalField.FLOOR_ID))
            assertEquals(LocalScalarValue.Text("New floor 003"), original.scalar(LocalField.NAME_ENGLISH))
            assertEquals(LocalScalarValue.Text("صالة جديدة ٠٠٣"), original.scalar(LocalField.NAME_ARABIC))
            assertTrue(original.values.none { it.field == LocalField.EXPECTED_STORAGE_REVISION })
            val b = OperationId("d4000000-0000-4000-8000-00000000714c") // FFD-029 operation rule: 29*1000+4.
            assertNotEquals(a, b)
            fixture.port.lookupResult = LocalOperationResult.Recorded(b, LocalRecordedDecision.APPLIED,
                LocalText("food.floor.saved $selectedId · 1", "food.floor.saved $selectedId · ١"))
            fixture.controller.lookup(a, fixture.token())
            runCurrent()
            assertEquals(a, fixture.controller.state.value.operationId)
            assertEquals(a, assertIs<LocalOperationResult.Unresolved>(fixture.controller.state.value.operation).operationId)
            assertEquals(selectedId, fixture.controller.state.value.form!!.identifier(LocalField.FLOOR_ID))
            fixture.controller.edit(LocalField.NAME_ENGLISH, LocalDraftValue("Configured Update"), fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            assertEquals(originalValues, original.values)
            assertEquals(1, fixture.port.stages.size)
            assertEquals(listOf(a), fixture.port.lookups)
            assertTrue(fixture.port.submissions.isEmpty())
            assertTrue(fixture.port.acknowledgements.isEmpty())
            fixture.port.lookupResult = LocalOperationResult.Pending(assertNotNull(fixture.port.stagedOperation))
            fixture.controller.lookup(a, fixture.token())
            runCurrent()
            assertEquals(a, assertIs<LocalOperationResult.Pending>(fixture.controller.state.value.operation).operation.operationId)
            assertEquals(originalValues, original.values)
            assertEquals(listOf(a, a), fixture.port.lookups)
        }
    }
}

private fun LocalFormDraft.identifier(field: LocalField): DomainId =
    DomainId(values.getValue(field).text)
private fun LocalCommandIntent.scalar(field: LocalField) = values.single { it.field == field }.value
