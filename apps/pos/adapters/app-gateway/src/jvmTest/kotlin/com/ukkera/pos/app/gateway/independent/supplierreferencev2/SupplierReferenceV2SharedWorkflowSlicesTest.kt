package com.ukkera.pos.app.gateway.independent.supplierreferencev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual shared form descriptors/parser/gateway; controlled Master, not installed UI or RPC. */
class SupplierReferenceV2SharedWorkflowSlicesTest {
    private val f = SupplierReferenceV2GatewayInputs

    @Test
    fun sref2002_010_sharedPreloadNamesOnlyReplaceEmptyClearAndSpaceUseExactV2FullState() = runTest {
        withSupplierReferenceV2Gateway { h ->
            h.master.commitActual(MutationRequest(f.operation(201), LocalConfigurationProfile.COMMAND,
                LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureSupplier(null,
                    LocalSupplierV1(f.id(101), f.initialNames, true)))))
            val originalResults = linkedMapOf<OperationId, StoredClientIntentV1>()
            val steps = listOf(
                Triple(301, f.literal, false), Triple(304, f.literal, true),
                Triple(302, f.mixed, true), Triple(303, "", true), Triple(501, " ", true),
            )
            var prior: String? = null
            for ((serial, text, editNames) in steps) {
                val page = detailPage(h, f.id(101))
                val form = page.forms.single()
                val field = form.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == LocalField.SUPPLIER_REFERENCE }
                assertEquals(2, form.version)
                assertEquals(LocalInputKind.TEXT, field.kind)
                assertFalse(field.required)
                assertEquals(256, field.maximumCharacters)
                assertEquals("Supplier directory reference", field.label.english)
                assertEquals("مرجع المورد في الدليل", field.label.arabic)
                assertEquals(prior?.let(LocalScalarValue::Text), field.initialValue)
                val edits = mutableMapOf<LocalField, LocalDraftValue>()
                // The names-only step uses the actual untouched reference preload.
                if (serial != 304) edits[LocalField.SUPPLIER_REFERENCE] = LocalDraftValue(text)
                if (editNames) {
                    edits[LocalField.NAME_ENGLISH] = LocalDraftValue(f.editedNames.english)
                    edits[LocalField.NAME_ARABIC] = LocalDraftValue(f.editedNames.arabic)
                    edits[LocalField.ACTIVE] = LocalDraftValue(selected = LocalScalarValue.Flag(false))
                }
                val intent = parsedIntent(form, f.operation(serial), page.records.single().reference, edits)
                val gateway = h.gateway()
                assertIs<LocalStageResult.Staged>(gateway.stage(intent))
                val saved = h.host.find(intent.operationId)
                val request = LocalSupplierCodecV2.decode(saved.request().payload)
                assertEquals(text.takeIf { it.isNotEmpty() }?.let(Code::exact), request.supplier.reference)
                assertEquals(h.master.storage(f.id(101)), request.expectedStorageRevision)
                assertEquals(if (editNames) f.editedNames else f.initialNames, request.supplier.names)
                assertEquals(!editNames, request.supplier.active)
                val recorded = assertIs<LocalOperationResult.Recorded>(gateway.submit(intent.operationId))
                assertEquals(LocalRecordedDecision.APPLIED, recorded.decision)
                val originalValue = recorded.result!!.values.single()
                assertEquals("Original supplier directory reference", originalValue.label.english)
                assertEquals("مرجع المورد الأصلي في الدليل", originalValue.label.arabic)
                assertEquals(text.takeIf { it.isNotEmpty() } ?: "Not recorded", originalValue.value.english)
                originalResults[intent.operationId] = h.host.find(intent.operationId)
                assertIs<LocalAcknowledgementResult.Acknowledged>(gateway.acknowledgeRecorded(intent.operationId))
                prior = text.takeIf { it.isNotEmpty() }
                val current = detailPage(h, f.id(101)).records.single().values.single { it.label.english == field.label.english }
                assertEquals(prior ?: "Not recorded", current.value.english)
                if (prior == null) {
                    assertEquals("غير مسجل", current.value.arabic)
                    val old = assertIs<LocalOperationResult.Recorded>(gateway.lookup(f.operation(301)))
                    assertEquals(f.literal, old.result!!.values.single().value.english)
                }
            }
            val old = assertIs<LocalOperationResult.Recorded>(h.gateway().lookup(f.operation(301)))
            assertEquals(f.literal, old.result!!.values.single().value.english)
            for ((operation, entry) in originalResults) {
                val retained = h.host.find(operation)
                assertEquals(entry.request().payload, retained.request().payload)
                assertEquals(entry.recorded, retained.recorded)
                assertTrue(retained.acknowledged)
            }
            assertEquals(1, h.master.rowCount)
            assertTrue(h.master.executions.all { it.command == LocalSupplierProfileV2.COMMAND })
        }
    }

    @Test
    fun sref2001_002_actualEditorAndGatewayKeepMaximumPairsEscapesEmptyAndWhitespaceWithoutDispatchAtStage() = runTest {
        for (text in listOf("", " ", f.literal, f.mixed, "😀".repeat(128), "A".repeat(254) + "😀", "\\\"".repeat(128))) {
            withSupplierReferenceV2Gateway { h ->
                val field = assertIs<LocalReadResult.Available>(h.gateway().read(f.query())).page.forms.single()
                    .fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == LocalField.SUPPLIER_REFERENCE }
                val parsed = parseLocalInput(field, LocalDraftValue(text))
                if (text.isEmpty()) assertEquals(LocalParsedValue.Omitted, parsed)
                else assertEquals(LocalScalarValue.Text(text), assertIs<LocalParsedValue.Value>(parsed).value)
                val supplier = f.supplier(text = text.takeIf { it.isNotEmpty() }, names = LocalNamesV1("A".repeat(160), "م".repeat(160)))
                val intent = f.intent(f.operation(202), supplier)
                assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
                assertEquals(LocalSupplierRequestV2(null, supplier), LocalSupplierCodecV2.decode(h.host.find(intent.operationId).request().payload))
                assertTrue(h.master.executions.isEmpty())
                assertEquals(0, h.master.rowCount)
            }
        }
        withSupplierReferenceV2Gateway { h ->
            val base = f.intent(f.operation(202), f.supplier())
            val tooLong = LocalCommandIntent(base.operationId, base.action, base.formVersion, base.reference,
                base.values.map { if (it.field == LocalField.SUPPLIER_REFERENCE) it.copy(value = LocalScalarValue.Text("A".repeat(257))) else it })
            val refusal = assertIs<LocalStageResult.NotStaged>(h.gateway().stage(tooLong))
            assertEquals(LocalServiceProblem.INVALID_INPUT, refusal.problem)
            assertEquals(LocalField.SUPPLIER_REFERENCE, refusal.field)
            assertTrue(h.host.pending().isEmpty())
            assertTrue(h.master.executions.isEmpty())
        }
    }

    @Test
    fun sref2007_v1OnlyAndGenericPartialCapabilityHintsBlockNewReferenceFormWithoutDowngradingWrites() = runTest {
        val installations = listOf(LocalWorkflowInstallation.supplierOnly(false),
            LocalWorkflowInstallation(setOf(LocalConfigurationProfile.COMMAND, LocalSupplierProfileV2.COMMAND), setOf(LocalConfigurationProfile.QUERY)),
            LocalWorkflowInstallation(setOf(LocalConfigurationProfile.COMMAND), setOf(LocalConfigurationProfile.QUERY, LocalSupplierProfileV2.QUERY)))
        for (installation in installations) {
            withSupplierReferenceV2Gateway { h ->
                h.installation = installation
                val page = assertIs<LocalReadResult.Available>(h.gateway().read(f.query())).page
                assertTrue(page.forms.none { it.version == 2 || it.fields.any { field -> field.field == LocalField.SUPPLIER_REFERENCE } })
                assertTrue(page.records.none { it.values.any { value -> value.value.english == "Not recorded" } })
                val result = assertIs<LocalStageResult.NotStaged>(h.gateway().stage(f.intent(f.operation(202), f.supplier())))
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, result.problem)
                assertTrue(h.host.pending().isEmpty())
                assertTrue(h.master.executions.isEmpty())
            }
        }
        // Partial installations above are the existing public generic hint constructor,
        // not fabricated restricted-RPC factories or transport capability qualification.
    }

    @Test
    fun sref2007_supplierOnlyBlocksUnrelatedWorkflowsAndSelectedV2ReadFailuresNeverFallBack() = runTest {
        withSupplierReferenceV2Gateway { h ->
            for (workflow in listOf(LocalWorkflow.SETUP, LocalWorkflow.CATALOG, LocalWorkflow.REPORTS, LocalWorkflow.CASH, LocalWorkflow.INVENTORY)) {
                assertIs<LocalReadResult.Unavailable>(h.gateway().read(LocalWorkflowQuery(workflow)))
            }
            val unrelated = LocalCommandIntent(f.operation(501), LocalAction.WAREHOUSE_CONFIGURE, 1, null, emptyList())
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalStageResult.NotStaged>(h.gateway().stage(unrelated)).problem)
            assertTrue(h.master.reads.isEmpty())
            assertTrue(h.master.executions.isEmpty())
            assertTrue(h.host.pending().isEmpty())
        }
        for (failure in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.UNKNOWN_QUERY, ReadFailure.STORAGE_UNCERTAIN)) {
            withSupplierReferenceV2Gateway { h ->
                h.master.readOverride = { ReadReply.Unavailable(failure) }
                assertIs<LocalReadResult.Unavailable>(h.gateway().read(f.query()))
                assertEquals(listOf(LocalSupplierProfileV2.QUERY), h.master.reads.map { it.query })
                assertTrue(h.master.executions.isEmpty())
                assertTrue(h.host.pending().isEmpty())
            }
        }
    }

    @Test
    fun sref2007_009_malformedSelectedV2PagesAndResultsStayUnavailableWithoutV1FallbackOrPartialReference() = runTest {
        withSupplierReferenceV2Gateway { h ->
            h.master.createV2(f.supplier(), f.operation(202))
            val request = ReadRequest(LocalSupplierProfileV2.QUERY, LocalSupplierCodecV2.query(LocalSupplierQueryV2(id = f.id(102))))
            val coherentReply = assertIs<ReadReply.Data>(h.master.readActual(request))
            val coherent = LocalSupplierCodecV2.decodePage(coherentReply.payload)
            val row = coherent.items.single()
            val badPages = listOf(coherent.copy(items = listOf(row.copy(storageRevision = row.storageRevision.next()))),
                coherent.copy(detail = coherent.detail!!.copy(reference = Code.exact(f.mixed))),
                coherent.copy(items = listOf(row, row)))
            for (bad in badPages) {
                h.master.readOverride = { ReadReply.Data(f.body(bad, LocalSupplierPageV2.serializer()), coherentReply.branchRevision, f.at) }
                assertIs<LocalReadResult.Unavailable>(h.gateway().read(f.query(LocalRecordReference(LocalRecordKind.SUPPLIER, row.id, row.revision))))
            }
            assertTrue(h.master.reads.all { it.query == LocalSupplierProfileV2.QUERY })
            assertTrue(h.master.executions.isEmpty())
            assertEquals(1, h.master.rowCount)
        }
        for (failure in 0..3) {
            withSupplierReferenceV2Gateway { h ->
                val intent = f.intent(f.operation(202), f.supplier())
                assertIs<LocalStageResult.Staged>(h.gateway().stage(intent))
                val saved = h.host.find(intent.operationId)
                h.master.executeOverride = { request -> when (failure) {
                    0 -> MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
                    1 -> MutationReply.Deferred(request.operationId, DeferredReason.UNKNOWN_COMMAND)
                    2 -> MutationReply.Recorded(request.operationId, f.actor, true, "committed", StoredBytes("{}".encodeToByteArray()),
                        Revision(1), JournalSequence(1), f.at, false)
                    else -> MutationReply.Recorded(request.operationId, f.actor, true, "committed",
                        f.body(LocalSupplierResultV2("configuration.applied", f.id(102), Revision(0), Code.exact(f.mixed)), LocalSupplierResultV2.serializer()),
                        Revision(1), JournalSequence(1), f.at, false)
                } }
                assertIs<LocalOperationResult.Unresolved>(h.gateway().submit(intent.operationId))
                val retained = h.host.find(intent.operationId)
                assertEquals(saved.request().payload, retained.request().payload)
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
                assertNull(retained.recorded)
                assertEquals(listOf(LocalSupplierProfileV2.COMMAND), h.master.executions.map { it.command })
                assertEquals(0, h.master.rowCount)
            }
        }
    }

    @Test
    fun sref2010_currentPageSearchUsesExactReferenceAlongsideUnchangedBilingualNameSearch() = runTest {
        withSupplierReferenceV2Gateway { h ->
            h.master.createV2(f.supplier(), f.operation(202))
            h.master.createV2(f.supplier(f.id(103), f.mixed, f.filterNames), f.operation(203))
            suspend fun search(text: String) = assertIs<LocalReadResult.Available>(h.gateway().read(f.query(search = text))).page
            assertEquals(listOf(f.id(102)), search(f.literal).records.map { it.reference.id })
            for (text in listOf("0007-Ab", " 0007-ab  ", " 0007-Ab   ")) assertTrue(search(text).records.isEmpty())
            assertEquals(listOf(f.id(103)), search(f.mixed).records.map { it.reference.id })
            assertEquals(listOf(f.id(102)), search("atlas").records.map { it.reference.id })
            assertEquals(listOf(f.id(103)), search("الأرز").records.map { it.reference.id })
            val notice = search("unseen-reference").notice!!
            assertTrue(notice.english.contains("Current supplier page only"))
            assertTrue(notice.arabic.contains("صفحة الموردين الحالية فقط"))
            assertTrue(h.master.executions.isEmpty())
            assertEquals(2, h.master.rowCount)
        }
        withSupplierReferenceV2Gateway { h ->
            h.master.createV2(f.supplier(text = f.nfc), f.operation(403))
            assertEquals(listOf(f.id(102)), assertIs<LocalReadResult.Available>(h.gateway().read(f.query(search = f.nfc))).page.records.map { it.reference.id })
            assertTrue(assertIs<LocalReadResult.Available>(h.gateway().read(f.query(search = f.nfd))).page.records.isEmpty())
        }
    }

    private suspend fun detailPage(h: SupplierReferenceV2GatewayFixture, id: DomainId): LocalWorkflowPage =
        assertIs<LocalReadResult.Available>(h.gateway().read(f.query(LocalRecordReference(LocalRecordKind.SUPPLIER,
            id, Revision(h.master.storage(id).value - 1))))).page

    private fun parsedIntent(form: LocalActionForm, operation: OperationId, reference: LocalRecordReference,
        edits: Map<LocalField, LocalDraftValue>): LocalCommandIntent {
        val draft = localFormDraft(form)
        val values = draft.values + edits
        val inputs = form.fields.filterIsInstance<LocalFormField.Scalar>().mapNotNull { field ->
            when (val parsed = parseLocalInput(field, values.getValue(field.field))) {
                is LocalParsedValue.Value -> LocalScalarInput(field.field, parsed.value)
                LocalParsedValue.Omitted -> null
                LocalParsedValue.Invalid -> error("Frozen valid shared form input refused at ${field.field}")
            }
        }
        return LocalCommandIntent(operation, form.action, form.version, reference, inputs)
    }
}
