@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Frozen v1 controller/predicate observations with controlled gateway inputs; not rendered/device or durable evidence. */
class FoodFinancialRpcUiSupportingTest {
    private val input get() = FrozenFoodFinancialUi

    @Test
    fun ffrc001ExplicitModeAndSetupRequestNeverImplicitlySelectFinancialAuthority() {
        input.case("FFRC-001")
        val selected = input.display()
        assertEquals(LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY, selected.workspaceMode)
        for (area in AppArea.entries.filterNot { it == AppArea.ORDERS }) {
            assertFailsWith<IllegalArgumentException> { input.display(areas = setOf(area)) }
        }
        assertFailsWith<IllegalArgumentException> { selected.copy(branchId = null) }
        val ordinary = LocalSetupLanInput(listOf("192.168.50.0/24"), "synthetic-interface", "192.168.50.10", 0)
        assertEquals(LocalSetupLanWorkflow.AUTHENTICATION_ONLY, ordinary.workflow)
        assertNull(ordinary.credentialAction)
        assertNull(ordinary.actorIntentAction)
        val incomplete = LocalSetupLanInput(ordinary.allowedSubnets, ordinary.interfaceName, ordinary.localAddress, 0,
            LocalPairedDeviceCredentialJournalAction.REOPEN, LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL)
        assertEquals(LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL, incomplete.workflow)
        assertNull(incomplete.actorIntentAction, "The new mode must not infer an actor-journal action")
        val explicit = LocalSetupLanInput(ordinary.allowedSubnets, ordinary.interfaceName, ordinary.localAddress, 0,
            LocalPairedDeviceCredentialJournalAction.REOPEN, LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL,
            LocalPairedDeviceCredentialJournalAction.REOPEN)
        assertEquals(LocalPairedDeviceCredentialJournalAction.REOPEN, explicit.actorIntentAction)
        assertEquals(input.actions.toSet(), foodCashFinancialUiActions)
        for (action in input.actions) {
            val query = input.query(action)
            assertTrue(input.form(action).isFoodCashFinancialUiForm(query, input.page(query).food!!.body))
            assertFalse(input.form(action).copy(version = 2).isFoodCashFinancialUiForm(query))
            assertTrue(input.intent(action).isFoodCashFinancialUiIntent())
        }
        // Input equality is not setup execution, journal acquisition, endpoint registration or current admission.
    }

    @Test
    fun ffrc001And016RoutesQueriesAndActionsRemainAClosedPresentationSubset() = runTest {
        input.case("FFRC-001")
        input.case("FFRC-016")
        val admitted = listOf(AppRoute.Orders, AppRoute.Food, AppRoute.FoodOrder(input.order), AppRoute.SessionAccess)
        val excluded = listOf(AppRoute.Cashier, AppRoute.Order(input.order), AppRoute.FoodStation(input.id("station")),
            AppRoute.FoodHandoff(input.order), AppRoute.Drawer, AppRoute.Stock, AppRoute.Reports, AppRoute.Purchasing,
            AppRoute.Catalog, AppRoute.People, AppRoute.Devices, AppRoute.CompanySettings)
        admitted.forEach { assertTrue(foodCashFinancialUiRoute(it)) }
        excluded.forEach { assertFalse(foodCashFinancialUiRoute(it)) }
        val navigator = AppNavigator()
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, input.display())
        excluded.forEach { assertEquals(NavigationResult.IGNORED,
            navigator.navigate(NavigationAction.Push(it), navigator.token()), it.toString()) }
        for (action in input.actions) assertTrue(input.query(action).isFoodCashFinancialUiQuery())
        input.excludedQueries().forEach { assertFalse(it.isFoodCashFinancialUiQuery(), it.toString()) }

        withFoodFinancialUi {
            val before = port.counts()
            LocalWorkflow.entries.filterNot { it == LocalWorkflow.FOOD }.forEach { controller.selectWorkflow(it, token()) }
            input.excludedQueries().forEach { controller.selectFood(it, token()) }
            LocalAction.entries.filterNot { it in input.actions }.forEach { controller.openForm(it, token()) }
            controller.changeSearch("cashier-controlled-price", token())
            controller.openFoodMenu(token())
            controller.openRetailCollection(token())
            controller.stage(token())
            runCurrent()
            assertEquals(before, port.counts())
            assertEquals(LocalWorkflow.FOOD, controller.state.value.workflow)
            assertEquals("", controller.state.value.search)
            assertNull(controller.state.value.form)
            assertNull(controller.retailPhysical.state.value.payment)
            assertFalse(controller.retailPhysical.state.value.pendingLoaded)
        }
    }

    @Test
    fun ffrc001StoredFinancialContinuationCannotUpgradeFoodChecksMode() = runTest {
        input.case("FFRC-001")
        val original = input.continuation(input.intent(LocalAction.FOOD_PAYMENT_APPLY))
        withFoodFinancialUi(continuation = original, mode = LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY) {
            assertEquals(listOf(0, 0, 0, 0, 0, 0), port.counts())
            assertEquals(original.workspace.operationId, controller.state.value.operationId)
            controller.selectFood(input.query(LocalAction.FOOD_PAYMENT_APPLY), token())
            controller.openForm(LocalAction.FOOD_PAYMENT_APPLY, token())
            controller.confirmForm(true, token())
            controller.stage(token())
            controller.retryStage(token())
            controller.submit(input.intent(LocalAction.FOOD_PAYMENT_APPLY).operationId, token())
            controller.lookup(input.intent(LocalAction.FOOD_PAYMENT_APPLY).operationId, token())
            controller.acknowledgeRecorded(input.intent(LocalAction.FOOD_PAYMENT_APPLY).operationId, token())
            controller.reviewAttachment(token())
            runCurrent()
            assertEquals(listOf(0, 0, 0, 0, 0, 0), port.counts())
            val retained = assertNotNull(controller.retireForAttachment())
            assertEquals(original.workspace.operationId, retained.workspace.operationId)
            assertSame(original.frozen, retained.frozen)
            assertEquals(original.originalAction, retained.originalAction)
        }
    }

    @Test
    fun ffrc012And023ApplyReviewFieldsAreReadOnlyAndEachSourceTupleMismatchIsRefused() = runTest {
        input.case("FFRC-012")
        input.case("FFRC-023")
        val action = LocalAction.FOOD_PAYMENT_APPLY
        val query = input.query(action)
        assertEquals(57_013L, input.payable.minor)
        val originalForm = input.form(action)
        assertTrue(originalForm.fields.filterIsInstance<LocalFormField.Scalar>().all { it.readOnly })
        val mismatches = listOf(
            LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID to LocalScalarValue.Identifier(input.id("other-recording")),
            LocalField.ORDER_ID to LocalScalarValue.Identifier(input.id("other-order")),
            LocalField.COLLECTION_ID to LocalScalarValue.Identifier(input.id("other-collection")),
            LocalField.AMOUNT to LocalScalarValue.Decimal("570.14"),
            LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST to LocalScalarValue.Text("f".repeat(64)))
        for ((field, value) in mismatches) {
            val mismatched = originalForm.financialUiChange(field, value)
            assertFalse(mismatched.isFoodCashFinancialUiForm(query, input.page(query).food!!.body), field.name)
            withFoodFinancialUi(initialQuery = query, configure = { port ->
                port.readReply = { LocalReadResult.Available(input.page(query, forms = listOf(mismatched))) }
            }) {
                assertNull(controller.state.value.page, field.name)
                assertNotNull(controller.state.value.readProblem, field.name)
                controller.openForm(action, token())
                controller.confirmForm(true, token())
                controller.stage(token())
                runCurrent()
                assertTrue(port.stages.isEmpty(), field.name)
                assertTrue(port.submissions.isEmpty(), field.name)
                assertTrue(port.acknowledgements.isEmpty(), field.name)
            }
        }
        withFoodFinancialUi(initialQuery = query) {
            controller.openForm(action, token())
            val before = assertNotNull(controller.state.value.form)
            before.values.keys.forEach { controller.edit(it, LocalDraftValue("tampered"), token()) }
            assertEquals(before.values, controller.state.value.form!!.values)
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val staged = port.stages.single()
            assertEquals(input.intent(action, staged.operationId).values, staged.values)
            assertEquals(1, staged.formVersion)
            assertTrue(port.submissions.isEmpty())
        }
    }

    @Test
    fun ffrc023ConfirmationStageAndSubmitAreSeparateInBothLanguagesWithDuplicateAndBackSafety() = runTest {
        input.case("FFRC-023")
        for (language in AppLanguage.entries) for (action in input.actions) {
            val barrier = FoodFinancialUiBarrier()
            withFoodFinancialUi(initialQuery = input.query(action), language = language, configure = { port ->
                port.stageReply = { barrier.awaitRelease(); LocalStageResult.Staged(input.pending(it)) }
            }) {
                controller.openForm(action, token())
                assertNotNull(controller.state.value.form)
                controller.stage(token())
                runCurrent()
                assertEquals(LocalField.CONFIRMED, controller.state.value.issue?.field)
                assertTrue(port.stages.isEmpty())
                assertNull(controller.state.value.operationId)
                controller.confirmForm(true, token())
                controller.stage(token())
                runCurrent()
                try {
                    assertTrue(barrier.entered.isCompleted)
                    val staged = port.stages.single()
                    assertEquals(input.intent(action, staged.operationId).values, staged.values)
                    assertEquals(1, staged.formVersion)
                    assertEquals(staged.operationId, controller.state.value.operationId)
                    assertEquals(LocalWorkBusy.STAGING, controller.state.value.busy)
                    controller.stage(token())
                    controller.submit(staged.operationId, token())
                    assertEquals(NavigationResult.BLOCKED,
                        runtime.navigator.navigate(NavigationAction.Back, token()))
                    assertEquals(staged.operationId, controller.state.value.operationId)
                    assertEquals(1, port.stages.size)
                    assertTrue(port.submissions.isEmpty())
                    assertTrue(port.acknowledgements.isEmpty())
                } finally { barrier.release() }
                runCurrent()
                val staged = port.stages.single()
                assertEquals(staged.operationId, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation.operationId)
                controller.stage(token())
                controller.discardForm(token())
                runCurrent()
                assertEquals(staged.operationId, controller.state.value.operationId)
                assertEquals(1, port.stages.size)
                assertTrue(port.submissions.isEmpty())
                assertEquals(language, runtime.presentation.value.language)
            }
        }
        // Both languages invoke the same controller. No rendered Arabic/English, IME or device claim is made.
    }

    @Test
    fun ffrc023UncertainStageKeepsItsOriginalIdentityWithoutSubmissionOrReplacement() = runTest {
        input.case("FFRC-023")
        for (action in input.actions) withFoodFinancialUi(initialQuery = input.query(action), configure = { port ->
            port.stageReply = { LocalStageResult.Uncertain(input.operation("mismatched-stage-response")) }
        }) {
            controller.openForm(action, token())
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val original = port.stages.single()
            assertEquals(original.operationId, controller.state.value.operationId)
            assertTrue(controller.state.value.stageUncertain)
            controller.stage(token())
            controller.submit(original.operationId, token())
            controller.acknowledgeRecorded(original.operationId, token())
            runCurrent()
            assertEquals(1, port.stages.size)
            assertTrue(port.submissions.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
            val retained = recreate()
            runCurrent()
            assertSame(original, retained.frozen)
            assertEquals(original.operationId, controller.state.value.operationId)
            assertEquals(1, port.stages.size)
            assertTrue(port.submissions.isEmpty())
        }
    }

    @Test
    fun ffrc025LostSubmitRecreationAndBoundedLookupUseOnlyTheExactOriginal() = runTest {
        input.case("FFRC-025")
        for (action in input.actions) withFoodFinancialUi(initialQuery = input.query(action)) {
            controller.openForm(action, token())
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val original = port.stages.single()
            controller.submit(original.operationId, token())
            controller.submit(original.operationId, token())
            runCurrent()
            assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
            controller.submit(original.operationId, token())
            controller.stage(token())
            runCurrent()
            assertEquals(listOf(original.operationId), port.submissions)
            val beforeRecreation = port.counts()
            val retained = recreate()
            runCurrent()
            assertSame(original, retained.frozen)
            assertEquals(original.operationId, retained.workspace.operationId)
            assertNull(retained.workspace.page)
            assertTrue(controller.state.value.attachmentReviewRequired)
            assertEquals(beforeRecreation, port.counts(), "Recreation performs no automatic Review, Stage, Submit or ACK")
            controller.reviewAttachment(token()) // Explicit current presentation admission; never a fresh Stage.
            runCurrent()
            assertFalse(controller.state.value.attachmentReviewRequired)
            val readCount = port.reads.size
            repeat(2) {
                controller.lookup(original.operationId, token())
                runCurrent()
                assertEquals(original.operationId, assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation).operationId)
            }
            controller.submit(original.operationId, token())
            controller.stage(token())
            controller.acknowledgeRecorded(original.operationId, token())
            runCurrent()
            assertEquals(listOf(original.operationId, original.operationId), port.lookups)
            assertEquals(readCount, port.reads.size)
            assertEquals(1, port.stages.size)
            assertEquals(listOf(original.operationId), port.submissions)
            assertTrue(port.acknowledgements.isEmpty())
            assertSame(original, port.savedIntent)
        }
        // Recreation is an in-memory presentation retirement, not an OS process restart or Client disk test.
    }

    @Test
    fun ffrc024WrongIdentityActorOriginalAndMalformedTypedResultsCannotCompleteOrAcknowledge() = runTest {
        input.case("FFRC-024")
        for (action in input.actions) {
            val template = input.intent(action)
            val valid = input.recorded(template)
            val details = assertNotNull(valid.foodCashFinancial)
            val differentOriginal = when (val original = details.original) {
                is LocalFoodCashFinancialOriginal.BeginCollection -> original.copy(expectedOrderStorageRevision = original.expectedOrderStorageRevision.next())
                is LocalFoodCashFinancialOriginal.FinalizePaid -> original.copy(expectedCollectionDomainRevision = original.expectedCollectionDomainRevision.next())
                is LocalFoodCashFinancialOriginal.ApplyOriginal -> original.copy(recordingOperationId = input.operation("other-recording"))
            }
            for (variant in listOf("operation", "actor", "original", "missing-typed", "code", "reference")) {
                withFoodFinancialUi(initialQuery = input.query(action), configure = { port ->
                    port.submitReply = {
                        val exact = input.recorded(assertNotNull(port.savedIntent))
                        val exactDetails = assertNotNull(exact.foodCashFinancial)
                        when (variant) {
                            "operation" -> {
                                val other = input.operation("wrong-ui-result")
                                exact.copy(operationId = other, foodCashFinancial = exactDetails.copy(operationId = other))
                            }
                            "actor" -> exact.copy(foodCashFinancial = exactDetails.copy(originalActorId = input.otherActor))
                            "original" -> exact.copy(foodCashFinancial = exactDetails.copy(original = differentOriginal))
                            "missing-typed" -> exact.copy(foodCashFinancial = null)
                            "code" -> exact.copy(foodCashFinancial = exactDetails.copy(code = "unsupported.outcome"))
                            else -> {
                                val record = assertNotNull(exact.result)
                                exact.copy(result = record.copy(reference = LocalRecordReference(
                                    record.reference.kind, input.id("wrong-result-target"))))
                            }
                        }
                    }
                }) {
                    controller.openForm(action, token())
                    controller.confirmForm(true, token())
                    controller.stage(token())
                    runCurrent()
                    val original = port.stages.single()
                    controller.submit(original.operationId, token())
                    runCurrent()
                    assertEquals(original.operationId,
                        assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation, variant).operationId)
                    assertFalse(controller.state.value.recordedAcknowledged, variant)
                    controller.acknowledgeRecorded(original.operationId, token())
                    controller.stage(token())
                    runCurrent()
                    assertTrue(port.acknowledgements.isEmpty(), variant)
                    assertEquals(1, port.stages.size, variant)
                    assertEquals(listOf(original.operationId), port.submissions, variant)
                    assertSame(original, port.savedIntent)
                }
            }
        }
    }

    @Test
    fun ffrc024AcceptedTypedResultStillNeedsExplicitAcknowledgementAndItsSuccessfulReply() = runTest {
        input.case("FFRC-024")
        for (action in input.actions) {
            val barrier = FoodFinancialUiBarrier()
            withFoodFinancialUi(initialQuery = input.query(action), configure = { port ->
                port.submitReply = { input.recorded(assertNotNull(port.savedIntent)) }
                port.acknowledgementReply = { barrier.awaitRelease(); LocalAcknowledgementResult.Acknowledged(it) }
            }) {
                controller.openForm(action, token())
                controller.confirmForm(true, token())
                controller.stage(token())
                runCurrent()
                val original = port.stages.single()
                controller.submit(original.operationId, token())
                runCurrent()
                val recorded = assertIs<LocalOperationResult.Recorded>(controller.state.value.operation)
                assertEquals(original.operationId, recorded.operationId)
                assertTrue(recorded.isFoodCashFinancialUiResult(action, original))
                assertFalse(controller.state.value.recordedAcknowledged)
                assertTrue(port.acknowledgements.isEmpty(), "Receiving a terminal result never automatically ACKs it")
                controller.acknowledgeRecorded(original.operationId, token())
                runCurrent()
                try {
                    assertTrue(barrier.entered.isCompleted)
                    assertFalse(controller.state.value.recordedAcknowledged)
                    controller.acknowledgeRecorded(original.operationId, token())
                    assertEquals(listOf(original.operationId), port.acknowledgements)
                    assertEquals(LocalWorkBusy.ACKNOWLEDGING, controller.state.value.busy)
                } finally { barrier.release() }
                runCurrent()
                assertTrue(controller.state.value.recordedAcknowledged)
                assertEquals(recorded.foodCashFinancial!!.followUp, controller.state.value.foodQuery)
                assertEquals(listOf(original.operationId), port.submissions)
                assertEquals(1, port.stages.size)
                assertEquals(listOf(original.operationId), port.acknowledgements)
            }
        }
        // Durable-before-ACK is exercised with the real encrypted journal in the separate gateway test layer.
    }

    @Test
    fun ffrc013And026RetiredTerminalKnowledgeCannotRediscloseCachedDetailsOrAcknowledgeUnavailableSource() = runTest {
        input.case("FFRC-013")
        input.case("FFRC-026")
        withFoodFinancialUi(initialQuery = input.query(LocalAction.FOOD_PAYMENT_APPLY), configure = { port ->
            port.submitReply = { input.recorded(assertNotNull(port.savedIntent)) }
        }) {
            controller.openForm(LocalAction.FOOD_PAYMENT_APPLY, token())
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val original = port.stages.single()
            controller.submit(original.operationId, token())
            runCurrent()
            assertIs<LocalOperationResult.Recorded>(controller.state.value.operation)
            port.pendingOriginal = input.pending(original, LocalPendingPhase.RECORDED_UNACKNOWLEDGED, LocalRecordedDecision.APPLIED)
            val retained = recreate()
            runCurrent()
            assertNull(retained.workspace.page)
            assertIs<LocalOperationResult.Unresolved>(retained.workspace.operation)
            assertTrue(retained.workspace.operationRead!!.recorded)
            assertNull(retained.frozen)
            assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
            controller.reviewAttachment(token())
            runCurrent()
            assertFalse(controller.state.value.attachmentReviewRequired)
            port.lookupReply = { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) }
            controller.lookup(original.operationId, token())
            runCurrent()
            assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
            assertTrue(controller.state.value.operationRead!!.recorded, "Original terminal knowledge survives without its private details")
            controller.acknowledgeRecorded(original.operationId, token())
            controller.submit(original.operationId, token())
            runCurrent()
            assertTrue(port.acknowledgements.isEmpty())
            assertEquals(listOf(original.operationId), port.submissions)
            assertEquals(1, port.stages.size)
            assertSame(original, port.savedIntent)
        }
    }

    @Test
    fun ffrc013UnavailableOrIncompatiblePendingIsNeverAUsableEmptyRecoveryList() = runTest {
        input.case("FFRC-013")
        val excluded = LocalPendingOperation(input.operation("unsupported-original"), LocalAction.FOOD_SEND,
            input.text("Excluded retained original"))
        val variants = listOf(LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE),
            LocalPendingResult.Available(listOf(input.pending(input.intent(LocalAction.FOOD_BEGIN_COLLECTION)), excluded)))
        for (reply in variants) withFoodFinancialUi(initialQuery = input.query(LocalAction.FOOD_BEGIN_COLLECTION),
            configure = { it.pendingReply = { reply } }) {
            assertFalse(controller.state.value.pendingLoaded)
            assertNotNull(controller.state.value.pendingProblem)
            assertTrue(controller.state.value.pending.isEmpty())
            controller.openForm(LocalAction.FOOD_BEGIN_COLLECTION, token())
            controller.stage(token())
            runCurrent()
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
        }
    }
}
