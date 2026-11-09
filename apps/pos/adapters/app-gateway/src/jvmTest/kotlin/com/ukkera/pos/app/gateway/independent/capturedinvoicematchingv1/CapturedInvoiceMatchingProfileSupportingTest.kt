package com.ukkera.pos.app.gateway.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.store.LocalSupplierCashCodec
import com.ukkera.pos.sales.store.LocalSupplierCashProfile
import com.ukkera.pos.sales.store.LocalSupplierCashRequest
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Supporting CCM-08/10/11 and frozen combined-action binding v1.0.0 only.
 * Checks the production matching-profile selection and saved-command validators over the
 * unchanged F-QUALITY fixture. Registry membership is not admitted RPC or authorization.
 * Current sessions, source replies and journal retention remain controlled memory seams;
 * no business transaction, physical evidence, native storage/restart or CMP run is established.
 * ORDER and exact-ID metadata are validator-only because the fixture does not serve them.
 * Source handoff status: NOT RUN. No whole frozen case is established here.
 */
class CapturedInvoiceMatchingProfileSupportingTest {
    @Test
    fun ccm08MatchingProfileFixesOneCombinedMutationAndFourReadKeys() {
        val installation = matchingProfile()
        val commands = setOf(CommandKey("local.purchasing.invoice.match-recognize", 2))
        val reads = setOf(
            ReadKey("local.purchasing.invoice.read", 2), ReadKey("local.purchasing.read", 1),
            ReadKey("local.purchasing.invoice.match-recognize-source", 2), ReadKey("purchasing.document.read", 1),
        )
        assertEquals(commands, invoiceMatchingCommands)
        assertEquals(reads, invoiceMatchingReads)
        commands.forEach { assertTrue(installation.has(it)) }
        reads.forEach { assertTrue(installation.has(it)) }
        assertEquals(LocalMasterRpcWorkflowProfile.INVOICE_MATCHING, installation.directoryProfile)
        assertTrue(installation.isRestricted)
        assertTrue(installation.isInvoiceMatchingOnly)
        assertFalse(installation.isInvoiceCaptureOnly)
        assertNull(installation.directoryKind)
        assertEquals(setOf(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE),
            LocalAction.entries.filter(installation::allows).toSet())
        for (key in listOf(
            LocalPurchasingProfile.CAPTURE_INVOICE, LocalPurchasingProfile.RECORD_INVOICE,
            LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE,
            LocalPurchasingProfile.APPROVE_ORDER, LocalPurchasingProfile.RECORD_CREDIT,
            LocalConfigurationProfile.COMMAND, LocalPurchasingDocumentProfileV1.BEGIN,
            LocalPurchasingDocumentProfileV1.CHUNK, LocalPurchasingDocumentProfileV1.COMPLETE,
            LocalStockStoreProfile.RECEIVE, LocalStockStoreProfile.RECEIVE_SERIAL,
            LocalSupplierCashProfile.COMMAND, CommandKey(LocalPurchasingProfile.MATCH_CAPTURED_INVOICE.name, 1),
        )) assertFalse(installation.has(key))
        for (key in listOf(
            LocalConfigurationProfile.QUERY, LocalPurchasingProfile.READ_MATCH_SOURCE,
            LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE, LocalPurchasingProfile.READ_CREDIT_SOURCE,
            LocalPurchasingDocumentProfileV1.BYTES, LocalSupplierCashProfile.READ,
            ReadKey(LocalPurchasingProfile.READ_INVOICES.name, 1),
            ReadKey(LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE.name, 1),
        )) assertFalse(installation.has(key))
        // Neither key selection nor allows() supplies an employee grant or a source-read grant.
    }

    @Test
    fun ccm08And11TypedReadsStayWithinInvoiceOrderSourceAndExactDocumentMetadata() {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val installation = matchingProfile()
        for (costs in listOf(false, true)) {
            for ((key, section, id) in listOf(
                Triple(LocalPurchasingProfile.READ_INVOICES, LocalPurchasingSectionV1.INVOICE, f.invoice),
                Triple(LocalPurchasingProfile.READ, LocalPurchasingSectionV1.ORDER, f.order),
            )) {
                for (selected in listOf<DomainId?>(null, id)) {
                    val query = LocalPurchasingQueryV1(section, id = selected, includeCosts = costs)
                    val request = ReadRequest(key, LocalPurchasingCodec.query(query))
                    installation.requireRead(request)
                    assertEquals(query, LocalPurchasingCodec.decodeQuery(request.payload))
                }
            }
        }
        for ((section, kind, id) in listOf(
            Triple(LocalPurchasingSection.INVOICES, LocalRecordKind.PURCHASE_INVOICE, f.invoice),
            Triple(LocalPurchasingSection.ORDERS, LocalRecordKind.PURCHASE, f.order),
        )) {
            installation.requireWorkflow(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
                purchasing = LocalPurchasingUiQuery(section)))
            installation.requireWorkflow(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
                reference = LocalRecordReference(kind, id), purchasing = LocalPurchasingUiQuery(section)))
        }
        val source = qualitySourceQuery(f)
        val sourceRequest = ReadRequest(LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE,
            LocalPurchasingCodec.capturedMatchSource(source))
        installation.requireRead(sourceRequest)
        assertEquals(source, LocalPurchasingCodec.decodeCapturedMatchSourceQuery(sourceRequest.payload))
        assertEquals(f.original, source.originalDocumentId)
        assertEquals(f.selectedQuantity, source.quantity)
        val metadata = LocalPurchasingDocumentQueryV1(documentId = f.original)
        val metadataRequest = LocalPurchasingDocumentCodecV1.query(metadata)
        installation.requireRead(metadataRequest)
        assertEquals(metadata, LocalPurchasingDocumentCodecV1.decodeQuery(metadataRequest.payload))
        assertTrue(f.endpoint.reads.isEmpty())
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // includeCosts is a query selection, not proof of the three current protected-read grants.
    }

    @Test
    fun ccm08And11ReadModesDoNotAdmitLegacyPayableScanBytesExportOrReferenceDirectories() {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val installation = matchingProfile()
        for ((key, section) in listOf(
            LocalPurchasingProfile.READ to LocalPurchasingSectionV1.INVOICE,
            LocalPurchasingProfile.READ to LocalPurchasingSectionV1.PAYABLE,
            LocalPurchasingProfile.READ_INVOICES to LocalPurchasingSectionV1.ORDER,
            LocalPurchasingProfile.READ_INVOICES to LocalPurchasingSectionV1.PAYABLE,
        )) expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
            installation.requireRead(ReadRequest(key, LocalPurchasingCodec.query(
                LocalPurchasingQueryV1(section, includeCosts = true))))
        }
        for ((key, section) in listOf(
            LocalPurchasingProfile.READ to LocalPurchasingSectionV1.ORDER,
            LocalPurchasingProfile.READ_INVOICES to LocalPurchasingSectionV1.INVOICE,
        )) expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
            installation.requireRead(ReadRequest(key, LocalPurchasingCodec.query(
                LocalPurchasingQueryV1(section, includeCosts = true, export = true))))
        }
        for (query in listOf(LocalPurchasingDocumentQueryV1(),
            LocalPurchasingDocumentQueryV1(after = f.original),
            LocalPurchasingDocumentQueryV1(pendingDocumentId = f.original))) {
            expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
                installation.requireRead(LocalPurchasingDocumentCodecV1.query(query))
            }
        }
        for (export in listOf(false, true)) expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
            installation.requireRead(LocalPurchasingDocumentCodecV1.chunkQuery(
                LocalPurchasingDocumentChunkQueryV1(f.original, 0, forExport = export)))
        }
        for (kind in listOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.SKU)) {
            expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
                installation.requireRead(ReadRequest(LocalConfigurationProfile.QUERY,
                    LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind))))
            }
        }
        for (kind in listOf(LocalRecordKind.SUPPLIER, LocalRecordKind.SKU, LocalRecordKind.WAREHOUSE)) {
            expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
                installation.requireReferences(LocalReferenceQuery(LocalReferenceSource(kind), exactId = f.id(100)))
            }
        }
        for (section in LocalPurchasingSection.entries.filter {
            it !in setOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS)
        }) expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
            installation.requireWorkflow(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
                purchasing = LocalPurchasingUiQuery(section, includeCosts = true)))
        }
        val exact = LocalPurchasingDocumentCodecV1.query(LocalPurchasingDocumentQueryV1(documentId = f.original))
        expectProfileProblem(LocalServiceProblem.INVALID_INPUT) {
            installation.requireRead(ReadRequest(exact.query, withTrailingWhitespace(exact.payload)))
        }
        assertTrue(f.endpoint.reads.isEmpty())
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // Separate original-byte read/export authority cannot widen this metadata-only profile.
    }

    @Test
    fun ccm08And10OnlyScalarVersionOneAndCanonicalCombinedBodiesAreCompatible() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val installation = matchingProfile()
        val intent = f.intent()
        installation.requireIntent(intent)
        for (changed in listOf(
            profileIntentVariant(intent, formVersion = 2),
            profileIntentVariant(intent, action = LocalAction.SUPPLIER_INVOICE_CAPTURE),
            profileIntentVariant(intent, action = LocalAction.SUPPLIER_INVOICE_MATCH),
            profileIntentVariant(intent, lineField = LocalField.LINES),
            profileIntentVariant(intent, lineField = LocalField.LINES,
                lines = listOf(LocalInputLine(f.invoiceLine, emptyList()))),
            profileIntentVariant(intent, reference = LocalRecordReference(LocalRecordKind.PURCHASE, f.order)),
            profileIntentVariant(intent, reference = intent.reference?.copy(revision = Revision(1))),
            profileIntentVariant(intent, reference = intent.reference?.copy(parentId = f.order)),
        )) expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) { installation.requireIntent(changed) }
        assertNull(intent.arrivalEvidence)
        // LocalCommandIntent itself restricts non-null arrival evidence to arrival action/version 2.
        val gateway = f.gateway(installation = installation)
        assertIs<LocalStageResult.Staged>(gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        val request = original.request()
        assertExactQualityMatchRequest(f, request, intent)
        installation.requireMutation(intent.action, request)
        installation.requireOriginal(original)
        expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
            installation.requireMutation(LocalAction.SUPPLIER_INVOICE_MATCH, request)
        }
        expectProfileProblem(LocalServiceProblem.NOT_IMPLEMENTED) {
            installation.requireMutation(intent.action, MutationRequest(request.operationId, request.command,
                request.payload, actionApprovalId = f.id(101)))
        }
        expectProfileProblem(LocalServiceProblem.STORAGE_UNCERTAIN) {
            installation.requireMutation(intent.action, MutationRequest(request.operationId, request.command,
                withTrailingWhitespace(request.payload)))
        }
        val fields = Json.parseToJsonElement(request.payload.copyBytes().decodeToString()).jsonObject.toMutableMap()
        assertNotNull(fields.remove("originalDocumentId"))
        expectProfileProblem(LocalServiceProblem.STORAGE_UNCERTAIN) {
            installation.requireMutation(intent.action, MutationRequest(request.operationId, request.command,
                StoredBytes(JsonObject(fields).toString().encodeToByteArray())))
        }
        expectProfileProblem(LocalServiceProblem.STORAGE_UNCERTAIN) {
            installation.requireOriginal(original.copy(formVersion = 2))
        }
        assertEquals(original, f.journal.entry)
        assertEquals(1, f.journal.stageCalls)
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        // A missing original field is malformed retained input, not a qualified-source business refusal.
    }

    @Test
    fun ccm08And10CurrentCombinedHintAndOriginalActorStillBoundMatchingProfileWork() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val gateway = f.gateway(installation = matchingProfile())
        val intent = f.intent()
        f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(combined = false))
        assertEquals(LocalServiceProblem.DENIED,
            assertIs<LocalStageResult.NotStaged>(gateway.stage(intent)).problem)
        assertNull(f.journal.entry)
        assertEquals(0, f.journal.stageCalls)
        assertTrue(f.endpoint.reads.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        f.endpoint.currentReply = null
        assertIs<LocalStageResult.Staged>(gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        val readsBefore = f.endpoint.reads.size
        f.endpoint.rejectReads = true
        f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(combined = false))
        assertEquals(LocalServiceProblem.DENIED,
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId)).problem)
        assertEquals(original.identity(), f.endpoint.lookups.single())
        val lookupsBefore = f.endpoint.lookups.size
        f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(actorId = f.otherActor))
        assertIs<LocalStageResult.Uncertain>(gateway.stage(intent))
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId)).problem)
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalOperationResult.Unresolved>(gateway.lookup(intent.operationId)).problem)
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalPendingResult.Unavailable>(gateway.pending()).problem)
        assertEquals(original, f.journal.entry)
        assertEquals(f.actor, original.owner.actorId)
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertEquals(lookupsBefore, f.endpoint.lookups.size)
        assertEquals(1, f.journal.stageCalls)
        assertEquals(0, f.journal.rememberCalls)
        assertEquals(0, f.journal.acknowledgeCalls)
        assertTrue(f.endpoint.executions.isEmpty())
        // Controlled current hints/identity checks do not prove current Master authorization or revocation ordering.
    }

    @Test
    fun ccm10And11MatchingProfileRecoversTheExactQualityOriginalWithoutNewPreparation() = runTest(timeout = 5.seconds) {
        val f = CapturedInvoiceMatchingGatewayFixture()
        val installation = matchingProfile()
        val gateway = f.gateway(installation = installation)
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId)).problem)
        val original = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        assertNull(original.recorded)
        assertFalse(original.acknowledged)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, original.localPhase)
        val restored = f.journal.codecRoundTrip()
        assertEquals(original, restored.entry)
        val recreated = f.gateway(restored, installation)
        val readsBefore = f.endpoint.reads.size
        val lookupsBefore = f.endpoint.lookups.size
        f.endpoint.rejectReads = true
        assertIs<LocalStageResult.Uncertain>(recreated.stage(f.intent(quantity = "21")))
        assertEquals(original, restored.entry)
        assertEquals(lookupsBefore, f.endpoint.lookups.size)
        assertEquals(0, restored.stageCalls)
        val pending = assertIs<LocalPendingResult.Available>(recreated.pending()).operations.single()
        assertEquals(intent.operationId, pending.operationId)
        assertEquals(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, pending.action)
        assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, pending.phase)
        assertEquals(LocalRecordedDecision.APPLIED, pending.recordedDecision)
        assertFalse(pending.submitAllowed)
        val recovered = assertIs<LocalOperationResult.Recorded>(recreated.lookup(intent.operationId))
        assertEquals(intent.operationId, recovered.operationId)
        assertEquals(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, recovered.originalAction)
        assertEquals(LocalRecordedDecision.APPLIED, recovered.decision)
        assertTrue(recovered.replayed)
        val retained = assertNotNull(restored.entry)
        assertEquals(original.owner, retained.owner)
        assertEquals(original.identity(), retained.identity())
        assertEquals(original.request().payload, retained.request().payload)
        assertEquals(StoredKernelResultV1.from(originalReply), retained.recorded)
        installation.requireOriginal(retained)
        assertExactQualityMatchRequest(f, retained.request(), intent)
        val result = LocalPurchasingCodec.capturedMatchResult(originalReply.result)
        assertEquals(LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS, result.code)
        assertEquals(f.rawClaim, result.originalInvoiceClaim)
        assertEquals(f.selectedQuantity, result.quantity)
        assertEquals(f.recognized, result.net)
        assertEquals(f.zero, result.tax)
        assertEquals(f.unresolved, result.originalInvoiceClaim - assertNotNull(result.net) - assertNotNull(result.tax))
        val displayed = assertNotNull(recovered.result)
        assertEquals("220.00 EGP", matchingValue(displayed, "This operation's recognized amount — not paid"))
        assertEquals("240.00 EGP", matchingValue(displayed, "Original supplier claim"))
        assertTrue(recovered.message.english.contains("No stock or money moved"))
        assertIs<LocalAcknowledgementResult.Acknowledged>(recreated.acknowledgeRecorded(intent.operationId))
        assertTrue(assertNotNull(restored.entry).acknowledged)
        assertEquals(original.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(1, restored.acknowledgeCalls)
        assertEquals(readsBefore, f.endpoint.reads.size)
        assertEquals(1, f.endpoint.executions.size)
        assertTrue(f.endpoint.lookups.all { it == original.identity() })
        assertEquals(f.rawClaim, f.capture.supplierClaim)
        assertEquals("UNMATCHED_UNAPPROVED", f.capture.status)
        // Exact controlled result/projection and memory retention do not establish held-stock/ledger survival or native restart.
    }

    @Test
    fun ccm08And10UnsupportedRetainedProfilesNeverBecomeEmptyPendingOrReplacementWork() = runTest(timeout = 5.seconds) {
        for (action in listOf(LocalAction.SUPPLIER_INVOICE_CAPTURE, LocalAction.SUPPLIER_INVOICE_MATCH,
            LocalAction.SUPPLIER_CASH_APPLY)) {
            val f = CapturedInvoiceMatchingGatewayFixture()
            val original = unsupportedProfileOriginal(f, action)
            original.validate(f.journal.owner)
            assertEquals(JournalWrite.DURABLE, f.journal.stage(original))
            val restored = f.journal.codecRoundTrip()
            assertEquals(original, restored.entry)
            val gateway = f.gateway(restored, matchingProfile())
            f.endpoint.rejectReads = true
            assertIs<LocalStageResult.Uncertain>(gateway.stage(f.intent()))
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(original.operationId)).problem)
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalOperationResult.Unresolved>(gateway.lookup(original.operationId)).problem)
            for (cursor in listOf(null, "p1:${original.operationId.value}")) {
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertIs<LocalPendingResult.Unavailable>(gateway.pending(cursor)).problem)
            }
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(original.operationId)).problem)
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalStageResult.NotStaged>(gateway.stage(f.intent(operationId = OperationId(f.id(102).value)))).problem)
            assertEquals(original, restored.entry)
            assertNull(original.recorded)
            assertFalse(original.acknowledged)
            assertEquals(0, restored.stageCalls)
            assertEquals(0, restored.rememberCalls)
            assertEquals(0, restored.acknowledgeCalls)
            assertTrue(f.endpoint.reads.isEmpty())
            assertTrue(f.endpoint.executions.isEmpty())
            assertTrue(f.endpoint.lookups.isEmpty())
        }
        // Canonical synthetic staged bodies test profile compatibility, not their business/source qualification.
        // The physical Apply reference carries no invented cash observation and is never executed or looked up.
    }
}

private fun matchingProfile(): LocalWorkflowInstallation =
    LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_MATCHING)

private fun expectProfileProblem(problem: LocalServiceProblem, block: () -> Unit) {
    assertEquals(problem, assertFailsWith<GatewayFailure> { block() }.problem)
}

private fun withTrailingWhitespace(bytes: StoredBytes): StoredBytes =
    StoredBytes((bytes.copyBytes().decodeToString() + "\n").encodeToByteArray())

private fun profileIntentVariant(original: LocalCommandIntent, action: LocalAction = original.action,
    formVersion: Int = original.formVersion, reference: LocalRecordReference? = original.reference,
    lineField: LocalField? = null, lines: List<LocalInputLine> = emptyList()): LocalCommandIntent =
    LocalCommandIntent(original.operationId, action, formVersion, reference, original.values, lineField, lines)

private fun qualitySourceQuery(f: CapturedInvoiceMatchingGatewayFixture): LocalCapturedSupplierInvoiceMatchSourceQueryV2 =
    LocalCapturedSupplierInvoiceMatchSourceQueryV2(f.invoice, f.invoiceLine, Revision(1), f.original, Revision(1),
        f.order, Revision(1), f.allocation, f.receipt, f.receiptLine, Revision(1), f.selectedQuantity)

private fun unsupportedProfileOriginal(f: CapturedInvoiceMatchingGatewayFixture, action: LocalAction): StoredClientIntentV1 {
    val summary = listOf(recordValue(text("Synthetic saved command identity", "هوية أمر محفوظ اصطناعي"),
        literal(f.operation.value)))
    if (action == LocalAction.SUPPLIER_CASH_APPLY) {
        val request = LocalSupplierCashCodec.command(f.operation,
            LocalSupplierCashRequest.ApplyOriginal(OperationId(f.id(103).value)))
        return supplierPhysicalIntent(f.journal.owner, request, actionLabel(action), summary).also(::requireSupplierEntry)
    }
    val request = when (action) {
        LocalAction.SUPPLIER_INVOICE_CAPTURE -> {
            val original = f.capture
            val invoice = PurchaseInvoiceCaptureV2(original.id, original.supplierId, original.reference,
                original.date, original.dueDate, original.sourceEvidenceId, original.lines.map { line ->
                    PurchaseInvoiceCaptureLineV2(line.lineId, PurchaseGoodsV1(line.skuId, line.supplierItemReference,
                        line.units, line.originalQuantity, checkNotNull(line.unitPrice), checkNotNull(line.discount),
                        checkNotNull(line.purchaseTax), checkNotNull(line.claimedNet), checkNotNull(line.claimedTax)),
                        line.provenance, line.internalAllocationId, line.supplierAllocationReference)
                }, checkNotNull(original.charges), original.internalOrderLink, original.supplierOrderReference)
            assertEquals(f.rawClaim, invoice.total())
            LocalPurchasingCommands.captureInvoice(f.operation, LocalCaptureSupplierInvoiceV2(invoice,
                Revision(1), "supplier.invoice.capture"))
        }
        LocalAction.SUPPLIER_INVOICE_MATCH -> LocalPurchasingCommands.match(f.operation, LocalMatchSupplierInvoiceV1(
            f.match, f.invoice, f.invoiceLine, Revision(1), Revision(1), Revision(1), Revision(1), null,
            f.receipt, f.receiptLine, Revision(1), null, f.selectedQuantity, "purchasing.exact_goods"))
        else -> error("Unsupported profile probe action")
    }
    // This explicitly creates only canonical journal inputs; it does not pretend to prepare a business source.
    return StoredClientIntentV1.staged(f.journal.owner,
        LocalCommandIntent(f.operation, action, 1, null, emptyList()), request, actionLabel(action), summary)
}
