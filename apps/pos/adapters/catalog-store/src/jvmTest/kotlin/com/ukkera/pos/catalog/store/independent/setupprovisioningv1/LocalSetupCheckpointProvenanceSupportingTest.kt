package com.ukkera.pos.catalog.store.independent.setupprovisioningv1

import com.ukkera.pos.catalog.store.BranchActivityBodyV1
import com.ukkera.pos.catalog.store.BranchActivityProducerV1
import com.ukkera.pos.catalog.store.BranchActivityRecordV1
import com.ukkera.pos.catalog.store.LocalBranchActivityProfileV1
import com.ukkera.pos.catalog.store.LocalBranchActivityReferenceV1
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.branchActivityDigest
import com.ukkera.pos.catalog.store.branchActivityEncode
import com.ukkera.pos.catalog.store.setup.LocalProductionSetupGoLivePrerequisites
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1 as Profile
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointResultV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointTargetV1
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningStockRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.catalog.store.setup.loadActivity
import com.ukkera.pos.catalog.store.setup.scanCatalogRows
import com.ukkera.pos.catalog.store.setup.scanFloatRows
import com.ukkera.pos.catalog.store.setup.scanStockRows
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ClaimKeyEntry
import com.ukkera.pos.database.ClaimKeyPage
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalSalesAction
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supplemental Linux/JVM setup-checkpoint provenance coverage only.
 *
 * The scans and the installed go-live prerequisite source are the real production functions.
 * They run against an explicitly constructed inert [CommandReadView] holding synthetic retained
 * writes/operations and synthetic saved-original coordinates. This proves fail-closed scan
 * behavior, source-key footprint bounds and the mandatory original binding; it is not a native
 * BEFORE cut, durable commit, protected read, historical producer join, SAS/AOSF case or device
 * test. `loadActivity` is exercised only on its unavailable branch: the JVM fixture has no
 * authentic company publication signature and no genuine native history owner, so no positive
 * signature/producer PASS is claimed. `RuntimeHistoricalCommand.matchesOriginalSetup` and the
 * historical ACK/live/checkpoint producer joins cannot run on JVM without fabricating a native
 * history owner; that callback/owner binding is audited separately, not simulated here.
 */
class LocalSetupCheckpointProvenanceSupportingTest {
    private val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
    private val actorId = DomainId.random()
    private val deviceId = DomainId.random()
    private val assignmentId = DomainId.random()
    private val authenticationId = DomainId.random()
    private val installationId = DomainId.random()
    private val recordedAt = UtcInstant.parseCanonical("2026-11-09T12:00:00Z")
    private val original = SavedOriginalSetupV1(
        scope = scope,
        installationId = installationId,
        assignmentId = assignmentId,
        deviceId = deviceId,
        firstActorId = actorId,
        originalRequestId = DomainId.random(),
        issuedAttemptId = DomainId.random(),
        attemptSequence = 3,
        definitionDigest = PayloadDigest("a".repeat(64)),
        genesisDigest = PayloadDigest("b".repeat(64)),
        receiptDigest = PayloadDigest("c".repeat(64)),
        installationAuthorityEpoch = Revision(1),
        currentAuthorityEpoch = Revision(1),
        installedAt = recordedAt,
        nativeSettlement = true,
    )
    private val binding = LocalSetupOriginalBindingV1.of(original)
    private val target = LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE)
    private val prerequisites = LocalProductionSetupGoLivePrerequisites(
        UnavailableCompanyPublicationSignatureVerifier)

    @Test
    fun validCatalogStockAndCashRowsProveTheirExactRetainedOperationAndContributeOnlyTheirStableSourceKey() = runTest {
        val catalog = catalogRow()
        val stock = stockRow()
        val cash = floatRow()
        val view = viewOf(listOf(catalog, stock, cash))

        val catalogRows = assertNotNull(scanCatalogRows(view, scope, installationId))
        val stockRows = assertNotNull(scanStockRows(view, scope, installationId))
        val cashRows = assertNotNull(scanFloatRows(view, scope, installationId))
        assertEquals(listOf(catalog.key), catalogRows.map { it.first })
        assertEquals(listOf(stock.key), stockRows.map { it.first })
        assertEquals(listOf(cash.key), cashRows.map { it.first })

        val resources = assertNotNull(prerequisites.recoveryResources(view, installationId))
        assertEquals(setOf(
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, scope.branchId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, catalog.skuId!!),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, stock.receiptId!!),
            RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, cash.sessionId!!),
        ), resources)
        assertFalse(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actorId) in resources,
            "Row actor was repeated as a second recovery key")
        assertFalse(RecoveryResourceKey(RecoveryResourceKind.STOCK, stock.forbiddenSecondary) in resources,
            "A secondary stock identifier was repeated as an extra recovery key")
        assertFalse(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, cash.forbiddenSecondary) in resources,
            "A cash drawer identifier was repeated as an extra recovery key")
    }

    @Test
    fun malformedCatalogCheckpointRowsMakeTheScanUnavailableInsteadOfSilentlyDroppingTheRow() = runTest {
        val valid = catalogRow()
        val otherStock = stockRow()
        val otherCash = floatRow()
        suspend fun scan(row: SyntheticRow) = scanCatalogRows(
            viewOf(listOf(row, otherStock, otherCash)), scope, installationId)
        suspend fun footprint(row: SyntheticRow) = prerequisites.recoveryResources(
            viewOf(listOf(row, otherStock, otherCash)), installationId)

        assertNotNull(scan(valid))
        assertNotNull(footprint(valid))

        val undecodable = valid.copy(aggregate = aggregate(valid.key, valid.schema, StoredBytes("not-json".encodeToByteArray())))
        assertNull(scan(undecodable), "An undecodable catalog row was silently omitted")
        assertNull(footprint(undecodable), "An undecodable catalog row disappeared from go-live recovery footprint")

        val wrongSchema = valid.copy(aggregate = aggregate(valid.key, "local.setup.catalog-import.row/v2", valid.payload))
        assertNull(scan(wrongSchema), "A wrong-schema catalog row was silently omitted")
        assertNull(footprint(wrongSchema))

        val wrongActor = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceActor = DomainId.random())
        assertNull(scan(wrongActor), "A catalog row with a foreign original actor was accepted")
        assertNull(footprint(wrongActor))

        val wrongCommand = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceCommand = Profile.CONFIGURE)
        assertNull(scan(wrongCommand), "A catalog row whose retained operation was a different command was accepted")

        val wrongResult = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            rowResult = LocalSetupCheckpointResultV1(Profile.CATALOG_ROW_CODE, installationId,
                rowId = DomainId.random(), rowCount = 1))
        assertNull(scan(wrongResult), "A catalog row whose retained result named another row was accepted")

        val wrongWrite = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            writePayloadOverride = StoredBytes("{}".encodeToByteArray()))
        assertNull(scan(wrongWrite), "A catalog row whose retained write did not equal the current row was accepted")

        val wrongPolicy = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidencePolicyRevision = Revision(2))
        assertNull(scan(wrongPolicy), "A catalog row with a mismatched historical policy revision was accepted")

        val wrongAuthority = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceAuthorityEpoch = Revision(2))
        assertNull(scan(wrongAuthority), "A catalog row with a mismatched authority epoch was accepted")

        val wrongBinding = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            recordBinding = binding.copy(installationId = DomainId.random()))
        assertNull(scan(wrongBinding), "A catalog row for another installation was silently filtered out")

        val missingAggregate = valid.copy(aggregate = null)
        assertNull(scan(missingAggregate), "A claimed catalog row with no aggregate was treated as absent")

        val missingEvidence = valid.copy(retained = emptyMap())
        assertNull(scan(missingEvidence), "A catalog row with no retained operation was accepted")

        val wrongClaimOwner = catalogRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            claimOwner = DomainId.random())
        assertNull(scan(wrongClaimOwner), "A catalog source claim owned by another row was accepted")
    }

    @Test
    fun malformedStockCheckpointRowsMakeTheScanUnavailableInsteadOfSilentlyDroppingTheRow() = runTest {
        val valid = stockRow()
        val otherCatalog = catalogRow()
        val otherCash = floatRow()
        suspend fun scan(row: SyntheticRow) = scanStockRows(
            viewOf(listOf(otherCatalog, row, otherCash)), scope, installationId)
        suspend fun footprint(row: SyntheticRow) = prerequisites.recoveryResources(
            viewOf(listOf(otherCatalog, row, otherCash)), installationId)

        assertNotNull(scan(valid))
        assertNotNull(footprint(valid))

        assertNull(scan(valid.copy(aggregate = aggregate(valid.key, valid.schema,
            StoredBytes("not-json".encodeToByteArray())))), "An undecodable stock row was silently omitted")
        assertNull(scan(valid.copy(aggregate = aggregate(valid.key,
            "local.setup.opening.stock-row/v2", valid.payload))), "A wrong-schema stock row was silently omitted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceActor = DomainId.random())), "A stock row with a foreign original actor was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceCommand = Profile.OPENING_FLOAT_ROW)), "A stock row with the wrong retained command was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            rowResult = LocalSetupCheckpointResultV1(Profile.OPENING_STOCK_ROW_CODE, installationId,
                rowId = DomainId.random(), rowCount = 1))), "A stock row with another result row id was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            writePayloadOverride = StoredBytes("{}".encodeToByteArray()))),
            "A stock row with a mismatched retained write was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidencePolicyRevision = Revision(2))), "A stock row with a mismatched policy revision was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceAuthorityEpoch = Revision(2))), "A stock row with a mismatched authority epoch was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            recordBinding = binding.copy(installationId = DomainId.random()))),
            "A stock row bound to another installation was accepted")
        assertNull(scan(valid.copy(aggregate = null)), "A claimed stock row with no aggregate was treated as absent")
        assertNull(scan(valid.copy(retained = emptyMap())), "A stock row with no retained operation was accepted")
        assertNull(scan(stockRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            claimOwner = DomainId.random())), "A stock source claim owned by another row was accepted")
    }

    @Test
    fun malformedCashCheckpointRowsMakeBothTheScanAndTheGoLiveCashEffectUnavailable() = runTest {
        val valid = floatRow()
        val otherCatalog = catalogRow()
        val otherStock = stockRow()
        suspend fun scan(row: SyntheticRow) = scanFloatRows(
            viewOf(listOf(otherCatalog, otherStock, row)), scope, installationId)
        suspend fun footprint(row: SyntheticRow) = prerequisites.recoveryResources(
            viewOf(listOf(otherCatalog, otherStock, row)), installationId)

        assertNotNull(scan(valid))
        assertNotNull(footprint(valid))

        assertNull(scan(valid.copy(aggregate = aggregate(valid.key, valid.schema,
            StoredBytes("not-json".encodeToByteArray())))), "An undecodable cash row was silently omitted")
        assertNull(scan(valid.copy(aggregate = aggregate(valid.key,
            "local.setup.opening.float-row/v2", valid.payload))), "A wrong-schema cash row was silently omitted")
        assertNull(scan(floatRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceActor = DomainId.random())), "A cash row with a foreign original actor was accepted")
        assertNull(scan(floatRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            evidenceCommand = Profile.OPENING_STOCK_ROW)), "A cash row with the wrong retained command was accepted")
        assertNull(scan(floatRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            rowResult = LocalSetupCheckpointResultV1(Profile.OPENING_FLOAT_ROW_CODE, installationId,
                rowId = DomainId.random(), rowCount = 1))), "A cash row with another result row id was accepted")
        assertNull(scan(floatRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            writePayloadOverride = StoredBytes("{}".encodeToByteArray()))),
            "A cash row with a mismatched retained write was accepted")
        assertNull(scan(valid.copy(aggregate = null)), "A claimed cash row with no aggregate was treated as absent")
        assertNull(scan(valid.copy(retained = emptyMap())), "A cash row with no retained operation was accepted")
        assertNull(scan(floatRow(sourceId = valid.sourceId!!, operation = valid.rowOperationId!!,
            claimOwner = DomainId.random())), "A cash source claim owned by another row was accepted")

        val cashEvidenceMissing = floatRow(sourceId = valid.sourceId!!, sessionId = valid.sessionId!!,
            operation = valid.rowOperationId!!, includeCashEvidence = false)
        assertNotNull(scan(cashEvidenceMissing), "The scan itself should still verify the unchanged checkpoint row")
        assertNull(footprint(cashEvidenceMissing),
            "A cash row without its exact opening command was accepted into the go-live footprint")

        val wrongCashAmount = floatRow(sourceId = valid.sourceId!!, sessionId = valid.sessionId!!,
            operation = valid.rowOperationId!!, cashResultOverride = Money(Currency.EGP, 1))
        assertNull(footprint(wrongCashAmount),
            "A cash row whose retained opening result named another amount was accepted into the go-live footprint")
    }

    @Test
    fun maximumSupportedManifestRowsKeepTheGoLiveRecoveryFootprintAtOneStableKeyPerSourceRow() = runTest {
        val rows = (0 until Profile.MAX_MANIFEST_ROWS).map { catalogRow() } +
            (0 until Profile.MAX_MANIFEST_ROWS).map { stockRow() } +
            (0 until Profile.MAX_MANIFEST_ROWS).map { floatRow() }
        val view = viewOf(rows)

        val catalog = assertNotNull(scanCatalogRows(view, scope, installationId))
        val stock = assertNotNull(scanStockRows(view, scope, installationId))
        val cash = assertNotNull(scanFloatRows(view, scope, installationId))
        assertEquals(Profile.MAX_MANIFEST_ROWS, catalog.size)
        assertEquals(Profile.MAX_MANIFEST_ROWS, stock.size)
        assertEquals(Profile.MAX_MANIFEST_ROWS, cash.size)

        val resources = assertNotNull(prerequisites.recoveryResources(view, installationId))
        val expected = buildSet {
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, scope.branchId))
            catalog.forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.second.skuId)) }
            stock.forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.second.receiptId)) }
            cash.forEach { add(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it.second.sessionId)) }
        }
        assertEquals(expected, resources)
        assertEquals(2 + Profile.MAX_MANIFEST_ROWS * 3, resources.size,
            "The supported 1024+1024+1024 source manifest exceeded one stable key per row plus scope")
        assertTrue(resources.size <= 4096,
            "The supported source manifest exceeded the kernel recovery footprint limit")
        assertFalse(resources.any { it.resourceId == actorId },
            "The shared row actor became a second resource for supported rows")
        assertFalse(resources.any { it.resourceId == stockSecondaryId },
            "A stock warehouse/lot/serial secondary identifier became an extra resource")
        assertFalse(resources.any { it.resourceId == cashSecondaryId },
            "A cash drawer secondary identifier became an extra resource")
    }

    @Test
    fun checkpointBindingRequiresTheActualAttemptSequenceAndExactReceiptDigest() {
        assertEquals(3L, binding.attemptSequence)
        assertTrue(binding.matches(original))
        assertFalse(binding.copy(attemptSequence = 4).matches(original),
            "The same receipt under another attempt sequence matched the saved original")
        assertFalse(binding.copy(receiptDigest = PayloadDigest("9".repeat(64))).matches(original),
            "The same attempt sequence under another receipt matched the saved original")
        assertFailsWith<IllegalArgumentException> { binding.copy(attemptSequence = 0) }
        assertFailsWith<IllegalArgumentException> { binding.copy(attemptSequence = Long.MAX_VALUE) }

        val record = LocalSetupCatalogImportRowRecordV1(
            binding = binding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = DomainId.random(),
            skuId = DomainId.random(), catalogOperationId = OperationId.random(),
            catalogPublicationId = DomainId.random(), catalogEffectDigest = PayloadDigest("1".repeat(64)),
            catalogEffectRevision = Revision(1), actorId = actorId, recordedAt = recordedAt,
            operationId = OperationId.random(), policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val decoded = LocalSetupCheckpointCodecV1.decodeCatalogImportRow(
            LocalSetupCheckpointCodecV1.encodeCatalogImportRow(record))
        assertEquals(binding, decoded.binding)
        assertEquals(3L, decoded.binding.attemptSequence)
        assertEquals(original.receiptDigest, decoded.binding.receiptDigest)
        assertTrue(decoded.binding.matches(original))
    }

    @Test
    fun loadActivityRefusesAnActiveRowWithoutTheExactProducerWrite() = runTest {
        val reference = LocalBranchActivityReferenceV1(scope.company(), DomainId.random(),
            DomainId.random(), Revision(0), PayloadDigest("a".repeat(64)), DomainId.random())
        val producer = BranchActivityProducerV1(OperationId.random(), actorId,
            LocalBranchActivityProfileV1.ACTIVATE_INITIAL.name,
            LocalBranchActivityProfileV1.ACTIVATE_INITIAL.version, PayloadDigest("e".repeat(64)))
        val record = BranchActivityRecordV1(scope, LocalBranchActivityProfileV1.ACTIVE, scope.branchId,
            Revision(1), producer, recordedAt, Revision(1), BranchActivityBodyV1.Active(reference))
        val payload = branchActivityEncode(record, BranchActivityRecordV1.serializer())
        val key = AggregateKey(LocalBranchActivityProfileV1.ACTIVE, scope.branchId)
        val row = StoredAggregate(key, Revision(1), LocalBranchActivityProfileV1.schema(
            LocalBranchActivityProfileV1.ACTIVE), payload, branchActivityDigest(payload))

        assertNull(loadActivity(viewOf(emptyList(), mapOf(key to row)),
            scope, UnavailableCompanyPublicationSignatureVerifier),
            "An active row without any retained producer operation was accepted")
        val wrongSchemaRow = StoredAggregate(key, Revision(1), "local.branch.activity-active/v2", payload,
            branchActivityDigest(payload))
        assertNull(loadActivity(viewOf(emptyList(), mapOf(key to wrongSchemaRow)),
            scope, UnavailableCompanyPublicationSignatureVerifier),
            "An active row with a wrong schema was accepted")

        val producerWithoutWrite = retained(producer.operationId,
            LocalBranchActivityProfileV1.ACTIVATE_INITIAL, actorId, StoredBytes("{}".encodeToByteArray()),
            emptyList())
        assertNull(loadActivity(viewOf(emptyList(), mapOf(key to row),
            mapOf(producer.operationId to producerWithoutWrite)), scope,
            UnavailableCompanyPublicationSignatureVerifier),
            "An active row without its exact producer write was accepted")
    }

    private data class SyntheticRow(
        val kind: String,
        val key: AggregateKey,
        val aggregate: StoredAggregate?,
        val retained: Map<OperationId, RetainedCommandEvidence>,
        val claims: Map<ClaimKey, DomainId>,
        val sourceId: DomainId? = null,
        val rowOperationId: OperationId? = null,
        val skuId: DomainId? = null,
        val receiptId: DomainId? = null,
        val sessionId: DomainId? = null,
        val forbiddenSecondary: DomainId = DomainId.random(),
    ) {
        val schema: String get() = aggregate?.schema ?: Profile.schema(kind)
        val payload: StoredBytes get() = checkNotNull(aggregate).payload
    }

    private fun catalogRow(
        sourceId: DomainId = DomainId.random(),
        skuId: DomainId = DomainId.random(),
        operation: OperationId = OperationId.random(),
        recordActor: DomainId = actorId,
        evidenceActor: DomainId = recordActor,
        recordBinding: LocalSetupOriginalBindingV1 = binding,
        rowResult: LocalSetupCheckpointResultV1? = null,
        evidenceCommand: CommandKey = Profile.CATALOG_IMPORT_ROW,
        schemaOverride: String? = null,
        payloadOverride: StoredBytes? = null,
        writePayloadOverride: StoredBytes? = null,
        evidencePolicyRevision: Revision = Revision(1),
        evidenceAuthorityEpoch: Revision = Revision(1),
        claimOwner: DomainId? = null,
        includeAggregate: Boolean = true,
        includeEvidence: Boolean = true,
    ): SyntheticRow {
        val rowId = Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId)
        val key = Profile.key(Profile.CATALOG_IMPORT_ROW_KIND, rowId)
        val record = LocalSetupCatalogImportRowRecordV1(
            binding = recordBinding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
            skuId = skuId, catalogOperationId = operation, catalogPublicationId = DomainId.random(),
            catalogEffectDigest = PayloadDigest("1".repeat(64)), catalogEffectRevision = Revision(1),
            actorId = recordActor, recordedAt = recordedAt, operationId = OperationId.random(),
            policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val payload = payloadOverride ?: LocalSetupCheckpointCodecV1.encodeCatalogImportRow(record)
        val schema = schemaOverride ?: Profile.schema(Profile.CATALOG_IMPORT_ROW_KIND)
        val aggregate = if (includeAggregate) aggregate(key, schema, payload) else null
        val rowResultBytes = LocalSetupCheckpointCodecV1.encodeResult(rowResult ?: LocalSetupCheckpointResultV1(
            Profile.CATALOG_ROW_CODE, installationId, rowId = rowId, rowCount = 1))
        val evidence = if (includeEvidence) retained(record.operationId, evidenceCommand, evidenceActor,
            rowResultBytes, listOf(write(key, schema, writePayloadOverride ?: payload)),
            policyRevision = evidencePolicyRevision, authorityEpoch = evidenceAuthorityEpoch) else null
        return SyntheticRow(
            kind = Profile.CATALOG_IMPORT_ROW_KIND, key = key, aggregate = aggregate,
            retained = evidence?.let { mapOf(record.operationId to it) } ?: emptyMap(),
            claims = mapOf(Profile.sourceClaim(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId)
                to (claimOwner ?: rowId)),
            sourceId = sourceId, rowOperationId = record.operationId, skuId = skuId,
        )
    }

    private fun stockRow(
        sourceId: DomainId = DomainId.random(),
        receiptId: DomainId = DomainId.random(),
        operation: OperationId = OperationId.random(),
        recordActor: DomainId = actorId,
        evidenceActor: DomainId = recordActor,
        recordBinding: LocalSetupOriginalBindingV1 = binding,
        rowResult: LocalSetupCheckpointResultV1? = null,
        evidenceCommand: CommandKey = Profile.OPENING_STOCK_ROW,
        schemaOverride: String? = null,
        writePayloadOverride: StoredBytes? = null,
        evidencePolicyRevision: Revision = Revision(1),
        evidenceAuthorityEpoch: Revision = Revision(1),
        claimOwner: DomainId? = null,
        includeAggregate: Boolean = true,
        includeEvidence: Boolean = true,
    ): SyntheticRow {
        val rowId = Profile.rowId(Profile.OPENING_STOCK_ROW_KIND, installationId, sourceId)
        val key = Profile.key(Profile.OPENING_STOCK_ROW_KIND, rowId)
        val record = LocalSetupOpeningStockRowRecordV1(
            binding = recordBinding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
            receiptId = receiptId, openingOperationId = operation, skuId = stockSecondaryId,
            warehouseId = stockSecondaryId, quantity = Quantity(DomainId.random(), 1, 0),
            value = Money(Currency.EGP, 100), lotId = stockSecondaryId, serialId = stockSecondaryId,
            stockEffectDigest = PayloadDigest("2".repeat(64)), stockEffectRevision = Revision(1),
            actorId = recordActor, recordedAt = recordedAt, operationId = OperationId.random(),
            policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val payload = LocalSetupCheckpointCodecV1.encodeOpeningStockRow(record)
        val schema = schemaOverride ?: Profile.schema(Profile.OPENING_STOCK_ROW_KIND)
        val aggregate = if (includeAggregate) aggregate(key, schema, payload) else null
        val rowResultBytes = LocalSetupCheckpointCodecV1.encodeResult(rowResult ?: LocalSetupCheckpointResultV1(
            Profile.OPENING_STOCK_ROW_CODE, installationId, rowId = rowId, rowCount = 1))
        val evidence = if (includeEvidence) retained(record.operationId, evidenceCommand, evidenceActor,
            rowResultBytes, listOf(write(key, schema, writePayloadOverride ?: payload)),
            policyRevision = evidencePolicyRevision, authorityEpoch = evidenceAuthorityEpoch) else null
        return SyntheticRow(
            kind = Profile.OPENING_STOCK_ROW_KIND, key = key, aggregate = aggregate,
            retained = evidence?.let { mapOf(record.operationId to it) } ?: emptyMap(),
            claims = mapOf(Profile.sourceClaim(Profile.OPENING_STOCK_ROW_KIND, installationId, sourceId)
                to (claimOwner ?: rowId)),
            sourceId = sourceId, rowOperationId = record.operationId, receiptId = receiptId,
            forbiddenSecondary = stockSecondaryId,
        )
    }

    private fun floatRow(
        sourceId: DomainId = DomainId.random(),
        sessionId: DomainId = DomainId.random(),
        operation: OperationId = OperationId.random(),
        recordActor: DomainId = actorId,
        evidenceActor: DomainId = recordActor,
        recordBinding: LocalSetupOriginalBindingV1 = binding,
        rowResult: LocalSetupCheckpointResultV1? = null,
        evidenceCommand: CommandKey = Profile.OPENING_FLOAT_ROW,
        schemaOverride: String? = null,
        writePayloadOverride: StoredBytes? = null,
        claimOwner: DomainId? = null,
        includeAggregate: Boolean = true,
        includeEvidence: Boolean = true,
        includeCashEvidence: Boolean = true,
        cashResultOverride: Money? = null,
    ): SyntheticRow {
        val rowId = Profile.rowId(Profile.OPENING_FLOAT_ROW_KIND, installationId, sourceId)
        val key = Profile.key(Profile.OPENING_FLOAT_ROW_KIND, rowId)
        val amount = Money(Currency.EGP, 50_000)
        val drawerId = cashSecondaryId
        val sessionPayload = StoredBytes("{\"syntheticSession\":true}".encodeToByteArray())
        val cashEffectDigest = sha256(sessionPayload.copyBytes())
        val record = LocalSetupOpeningFloatRowRecordV1(
            binding = recordBinding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
            sessionId = sessionId, openingOperationId = operation, amount = amount,
            cashEffectDigest = cashEffectDigest, cashEffectRevision = Revision(1),
            actorId = recordActor, recordedAt = recordedAt, operationId = OperationId.random(),
            policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val payload = LocalSetupCheckpointCodecV1.encodeOpeningFloatRow(record)
        val schema = schemaOverride ?: Profile.schema(Profile.OPENING_FLOAT_ROW_KIND)
        val aggregate = if (includeAggregate) aggregate(key, schema, payload) else null
        val rowResultBytes = LocalSetupCheckpointCodecV1.encodeResult(rowResult ?: LocalSetupCheckpointResultV1(
            Profile.OPENING_FLOAT_ROW_CODE, installationId, rowId = rowId, rowCount = 1))
        val retained = mutableMapOf<OperationId, RetainedCommandEvidence>()
        if (includeEvidence) {
            retained[record.operationId] = retained(record.operationId, evidenceCommand, evidenceActor,
                rowResultBytes, listOf(write(key, schema, writePayloadOverride ?: payload)))
        }
        if (includeCashEvidence) {
            val cashRequest = LocalCashRequestV1.Open(sessionId, drawerId, Revision(0), Revision(0),
                BusinessDate.parseCanonical("2026-11-09"), sourceId, amount,
                LocalCashEvidenceV1(DomainId.random(), recordedAt))
            val cashResult = salesJson.encodeToString(LocalSalesResultV1.serializer(),
                LocalSalesResultV1.Applied(LocalSalesAction.CASH_OPENED, sessionId, emptyList(),
                    cashResultOverride ?: amount)).encodeToByteArray()
            retained[operation] = retained(operation, LocalSalesStoreProfile.CASH_COMMAND, recordActor,
                StoredBytes(cashResult), listOf(RetainedAggregateWrite(
                    CashNonSaleStoreProfile.sessionKey(sessionId), Revision(1),
                    CashNonSaleStoreProfile.SESSION_SCHEMA, sessionPayload, cashEffectDigest)),
                normalized = LocalCashCommandCodec.encode(cashRequest))
        }
        return SyntheticRow(
            kind = Profile.OPENING_FLOAT_ROW_KIND, key = key, aggregate = aggregate,
            retained = retained,
            claims = mapOf(Profile.sourceClaim(Profile.OPENING_FLOAT_ROW_KIND, installationId, sourceId)
                to (claimOwner ?: rowId)),
            sourceId = sourceId, rowOperationId = record.operationId, sessionId = sessionId,
            forbiddenSecondary = cashSecondaryId,
        )
    }

    private fun viewOf(rows: List<SyntheticRow>,
        extraAggregates: Map<AggregateKey, StoredAggregate> = emptyMap(),
        extraRetained: Map<OperationId, RetainedCommandEvidence> = emptyMap()): CommandReadView {
        val aggregates = rows.mapNotNull { row -> row.aggregate?.let { row.key to it } }.toMap() + extraAggregates
        val retained = rows.flatMap { it.retained.entries }.associate { it.key to it.value } + extraRetained
        val claims = rows.flatMap { row -> row.claims.entries.map { it.key to it.value } }
        return object : CommandReadView {
            override val scope: BranchScope get() = this@LocalSetupCheckpointProvenanceSupportingTest.scope
            override suspend fun aggregate(key: AggregateKey): StoredAggregate? = aggregates[key]
            override suspend fun claim(key: ClaimKey): DomainId? =
                claims.firstOrNull { it.first == key }?.second
            override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
                retained[operationId]
            override suspend fun claimKeys(namespace: String, prefix: String, after: String?,
                limit: Int): ClaimKeyPage {
                val selected = claims.map { it.first to it.second }
                    .filter { it.first.namespace == namespace && it.first.key.startsWith(prefix) &&
                        (after == null || it.first.key > after) }
                    .distinctBy { it.first.key }
                    .sortedBy { it.first.key }
                    .take(limit + 1)
                val entries = selected.take(limit).map { ClaimKeyEntry(it.first, it.second) }
                return ClaimKeyPage(entries, if (selected.size > limit) entries.last().key.key else null)
            }
            override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
                val selected = aggregates.keys.filter { it.kind == kind &&
                    (after == null || it.id.value > after.value) }
                    .distinct().sortedBy { it.id.value }.take(limit + 1)
                return AggregateKeyPage(selected.take(limit),
                    if (selected.size > limit) selected[limit - 1].id else null)
            }
        }
    }

    private fun aggregate(key: AggregateKey, schema: String, payload: StoredBytes): StoredAggregate =
        StoredAggregate(key, Revision(1), schema, payload, sha256(payload.copyBytes()))

    private fun write(key: AggregateKey, schema: String, payload: StoredBytes): RetainedAggregateWrite =
        RetainedAggregateWrite(key, Revision(1), schema, payload, sha256(payload.copyBytes()))

    private fun retained(operationId: OperationId, command: CommandKey, actor: DomainId,
        result: StoredBytes, writes: List<RetainedAggregateWrite>,
        normalized: StoredBytes = StoredBytes("{}".encodeToByteArray()),
        policyRevision: Revision = Revision(1), authorityEpoch: Revision = Revision(1)):
        RetainedCommandEvidence = RetainedCommandEvidence(
        scope, operationId, actor, command, assignmentId, authorityEpoch, JournalSequence(1),
        recordedAt, authenticationId, policyRevision, PayloadDigest("e".repeat(64)), "committed",
        normalized, result, sha256(result.copyBytes()), PayloadDigest("0".repeat(64)),
        PayloadDigest("d".repeat(64)), writes, emptyList(), emptyList())

    private companion object {
        val stockSecondaryId: DomainId = DomainId.random()
        val cashSecondaryId: DomainId = DomainId.random()
        val salesJson = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
            isLenient = false
            coerceInputValues = false
        }
    }
}
