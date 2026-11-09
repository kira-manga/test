package com.ukkera.pos.catalog.store.independent.setupprovisioningv1

import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1 as Profile
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointResultV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowsPageV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowsQueryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowsV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowKindV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointServices
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningStockRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ClaimKeyEntry
import com.ukkera.pos.database.ClaimKeyPage
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadAuthorizationDenied
import com.ukkera.pos.database.MasterReadInvalidSelection
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.CurrentAuthorizationProvider
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent Linux/JVM qualification of the additive retained-row read.
 *
 * The real installed read handler, checkpoint scans, provenance validation and codecs run against
 * synthetic retained aggregates/operations and a synthetic saved-original tuple. This is not a
 * native receipt, protected master read, durable commit, SAS/AOSF case or device result. No native
 * PASS is claimed.
 */
class LocalSetupCheckpointRowsReadSupportingTest {
    private val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
    private val actorId = DomainId.random()
    private val deviceId = DomainId.random()
    private val assignmentId = DomainId.random()
    private val authenticationId = DomainId.random()
    private val installationId = DomainId.random()
    private val recordedAt = UtcInstant.parseCanonical("2026-11-09T12:00:00Z")
    private val original = SavedOriginalSetupV1(
        scope = scope, installationId = installationId, assignmentId = assignmentId, deviceId = deviceId,
        firstActorId = actorId, originalRequestId = DomainId.random(), issuedAttemptId = DomainId.random(),
        attemptSequence = 2, definitionDigest = PayloadDigest("a".repeat(64)),
        genesisDigest = PayloadDigest("b".repeat(64)), receiptDigest = PayloadDigest("c".repeat(64)),
        installationAuthorityEpoch = Revision(1), currentAuthorityEpoch = Revision(1),
        installedAt = recordedAt, nativeSettlement = true,
    )
    private val binding = LocalSetupOriginalBindingV1.of(original)
    private val services = LocalSetupCheckpointServices(UnavailableCompanyPublicationSignatureVerifier)
    private val reader = services.reads.single { it.key == LocalSetupCheckpointRowsV1.READ }

    @Test
    fun rowsReadRequiresItsOwnCheckpointGrantAndSharesNoSavedResultReadAuthority() = runTest {
        val row = catalogRow()
        val prepared = prepare(LocalSetupCheckpointRowKindV1.CATALOG, rows = listOf(row))
        val requirement = prepared.requirements.single()
        assertEquals(Permission(SecurityAction.SETUP_CHECKPOINTS_READ, PermissionVerb.PERFORM),
            requirement.permission)
        assertTrue(LocalSetupCheckpointRowsV1.READ != Profile.READ,
            "Retained-row read reused the checkpoint-detail read key")
        assertTrue(LocalSetupCheckpointRowsV1.READ !=
            com.ukkera.pos.database.setup.LocalSetupResultProfileV1.READ,
            "Retained-row read reused the saved-result read key")

        val savedResultOnly = policy(SecurityAction.SETUP_RESULT_READ)
            .authorize(principal(), requirement)
        assertIs<AuthorizationDecision.Denied>(savedResultOnly,
            "SETUP_RESULT_READ alone disclosed retained setup row references")

        val exact = policy(SecurityAction.SETUP_CHECKPOINTS_READ).authorize(principal(), requirement)
        val authorization = assertIs<AuthorizationDecision.Allowed>(exact).authorization
        val page = LocalSetupCheckpointRowsV1.decodePage(prepared.project(listOf(authorization), recordedAt))
        assertEquals(binding, page.binding)
        assertEquals(listOf(row.reference()), page.rows)
    }

    @Test
    fun catalogPagesAreOrderedExclusiveAndProjectExactReferenceIdentity() = runTest {
        val rows = (1..130).map { catalogRow() }
        val expected = rows.map { it.reference() }.sortedBy { it.sourceId.value }
        val collected = mutableListOf<com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowReferenceV1>()
        val pageSizes = mutableListOf<Int>()
        var cursor: DomainId? = null
        var pages = 0
        do {
            val page = projectPage(LocalSetupCheckpointRowKindV1.CATALOG, rows, cursor)
            assertEquals(LocalSetupCheckpointRowKindV1.CATALOG, page.kind)
            assertEquals(binding, page.binding)
            assertTrue(page.rows.size <= LocalSetupCheckpointRowsV1.PAGE_SIZE)
            if (cursor != null && page.rows.isNotEmpty()) {
                assertTrue(page.rows.all { it.sourceId.value > cursor!!.value },
                    "A later page repeated or moved behind its exclusive cursor")
            }
            collected += page.rows
            pageSizes += page.rows.size
            pages++
            cursor = page.nextSourceId
        } while (cursor != null)

        assertEquals(3, pages, "130 rows did not page as 64/64/2")
        assertEquals(listOf(64, 64, 2), pageSizes)
        assertEquals(expected, collected)
        assertEquals(expected.size, collected.map { it.sourceId }.distinct().size,
            "Retained-row pagination repeated a source identity")
        assertEquals(expected, collected.sortedBy { it.sourceId.value })
    }

    @Test
    fun eachKindUsesItsOwnResourceAndOriginalOperationWithoutMixingRows() = runTest {
        val catalog = catalogRow()
        val stock = stockRow()
        val float = floatRow()
        val rows = listOf(catalog, stock, float)

        val catalogPage = projectPage(LocalSetupCheckpointRowKindV1.CATALOG, rows)
        val stockPage = projectPage(LocalSetupCheckpointRowKindV1.STOCK, rows)
        val floatPage = projectPage(LocalSetupCheckpointRowKindV1.FLOAT, rows)

        assertEquals(listOf(catalog.reference()), catalogPage.rows)
        assertEquals(listOf(stock.reference()), stockPage.rows)
        assertEquals(listOf(float.reference()), floatPage.rows)
        assertEquals(catalog.resourceId, catalogPage.rows.single().resourceId)
        assertEquals(catalog.originalOperationId, catalogPage.rows.single().originalOperationId)
        assertEquals(stock.resourceId, stockPage.rows.single().resourceId)
        assertEquals(stock.originalOperationId, stockPage.rows.single().originalOperationId)
        assertEquals(float.resourceId, floatPage.rows.single().resourceId)
        assertEquals(float.originalOperationId, floatPage.rows.single().originalOperationId)
    }

    @Test
    fun wrongInstallationIsDeniedAndChangedOriginalOrRowProvenanceFailsClosed() = runTest {
        val valid = catalogRow()
        val request = LocalSetupCheckpointRowsV1.read(DomainId.random(), LocalSetupCheckpointRowKindV1.CATALOG)
        val normalized = reader.normalize(request.payload)
        assertFailsWith<MasterReadAuthorizationDenied> {
            reader.prepare(normalized, viewOf(listOf(valid), original = original),
                ReadContext(scope, actorId, LocalSetupCheckpointRowsV1.READ,
                    queryDigest(scope, actorId, LocalSetupCheckpointRowsV1.READ, normalized)))
        }

        assertFails("Changed saved original disclosed retained rows") {
            prepareAsync(LocalSetupCheckpointRowKindV1.CATALOG, listOf(valid),
                original.copy(receiptDigest = PayloadDigest("9".repeat(64))))
        }
        assertFails("A row bound to another original was disclosed") {
            prepareAsync(LocalSetupCheckpointRowKindV1.CATALOG,
                listOf(catalogRow(recordBinding = binding.copy(originalRequestId = DomainId.random()))))
        }
        assertFails("A row without its exact retained producer was disclosed") {
            prepareAsync(LocalSetupCheckpointRowKindV1.CATALOG,
                listOf(valid.copy(retained = emptyMap())))
        }
        assertFails("A wrong-schema row was disclosed") {
            prepareAsync(LocalSetupCheckpointRowKindV1.CATALOG,
                listOf(valid.copy(aggregate = valid.aggregate?.let { row ->
                    StoredAggregate(row.key, row.revision, row.schema + "/v2", row.payload, row.digest)
                })))
        }
        assertFails("An undecodable row payload was disclosed") {
            prepareAsync(LocalSetupCheckpointRowKindV1.CATALOG,
                listOf(valid.copy(aggregate = valid.aggregate?.let { row ->
                    val payload = StoredBytes("not-json".encodeToByteArray())
                    StoredAggregate(row.key, row.revision, row.schema, payload, sha256(payload.copyBytes()))
                })))
        }
    }

    @Test
    fun invalidCursorIsDisclosedOnlyAfterTheExactReadAuthorizationBinds() = runTest {
        val rows = listOf(catalogRow(), catalogRow())
        val valid = prepare(LocalSetupCheckpointRowKindV1.CATALOG, rows, afterSourceId = rows.first().sourceId)
        val validAuthorization = authorize(valid.requirements.single())
        val invalidCursor = DomainId.random()
        val invalid = prepare(LocalSetupCheckpointRowKindV1.CATALOG, rows, afterSourceId = invalidCursor)

        val unbound = assertFails {
            invalid.project(listOf(validAuthorization), recordedAt)
        }
        assertTrue(unbound !is MasterReadInvalidSelection,
            "An unauthorized caller learned that the cursor was invalid")

        val noAuthorization = assertFails { invalid.project(emptyList(), recordedAt) }
        assertTrue(noAuthorization !is MasterReadInvalidSelection,
            "Missing authorization disclosed invalid-cursor status")

        val exactAuthorization = authorize(invalid.requirements.single())
        assertFailsWith<MasterReadInvalidSelection> {
            invalid.project(listOf(exactAuthorization), recordedAt)
        }
    }

    @Test
    fun oneKindBeyondTheSupportedMaximumFailsClosedInsteadOfTruncating() = runTest {
        val rows = (0..Profile.MAX_MANIFEST_ROWS).map { catalogRow() }
        assertFails("1025 retained catalog rows were silently truncated or paged") {
            prepareAsync(LocalSetupCheckpointRowKindV1.CATALOG, rows)
        }
    }

    @Test
    fun queryAndPageCodecsRemainCanonicalRedactedAndBounded() = runTest {
        val query = LocalSetupCheckpointRowsQueryV1(DomainId.random(), LocalSetupCheckpointRowKindV1.STOCK,
            DomainId.random())
        assertEquals(query, LocalSetupCheckpointRowsV1.decodeQuery(LocalSetupCheckpointRowsV1.encodeQuery(query)))
        assertTrue(!query.toString().contains(query.installationId.value),
            "Retained-row query string exposed its installation identity")

        val page = LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.STOCK,
            listOf(catalogRow().reference()), null)
        assertEquals(page, LocalSetupCheckpointRowsV1.decodePage(LocalSetupCheckpointRowsV1.encodePage(page)))
        assertTrue(!page.toString().contains(page.rows.single().sourceId.value),
            "Retained-row page string exposed a source identity")
        assertFails("A page accepted more than 64 rows") {
            LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.STOCK,
                (1..65).map { val row = catalogRow(); row.reference() }, null)
        }
    }

    @Test
    fun claimDrivenDiscoveryRequiresCompleteAggregateMembership() = runTest {
        val valid = catalogRow()
        val orphan = catalogRow()
        suspend fun refused(view: CommandReadView, message: String) {
            assertFails(message) {
                prepare(LocalSetupCheckpointRowKindV1.CATALOG, listOf(valid), view = view)
            }
        }

        refused(viewOf(listOf(valid), claimsOverride = emptyList()),
            "A row with no source claim was still discovered through aggregate enumeration")
        refused(viewOf(listOf(valid), extraAggregates = mapOf(
            orphan.aggregate!!.key to orphan.aggregate)),
            "An aggregate with no source claim was accepted as complete membership")
        refused(viewOf(listOf(valid), claimsOverride = listOf(valid.claim, orphan.claim)),
            "A source claim whose aggregate was absent was treated as a complete manifest")

        val aggregate = valid.aggregate!!
        val mismatched = StoredAggregate(aggregate.key, aggregate.revision, aggregate.schema,
            orphan.aggregate!!.payload, orphan.aggregate!!.digest)
        refused(viewOf(listOf(valid), extraAggregates = mapOf(aggregate.key to mismatched)),
            "A member whose decoded source identity differed from its row key was accepted")
    }

    @Test
    fun unavailableOrCorruptAggregateEnumerationNeverYieldsAnEmptyManifest() = runTest {
        val valid = catalogRow()
        val kind = Profile.CATALOG_IMPORT_ROW_KIND
        val key = valid.aggregate!!.key
        suspend fun refused(page: AggregateKeyPage?, message: String, unavailable: Boolean = false) {
            val view = viewOf(listOf(valid), aggregateKeysOverride = { _, _, _ ->
                if (unavailable) throw UnsupportedOperationException("synthetic enumeration unavailable")
                checkNotNull(page)
            })
            assertFails(message) {
                prepare(LocalSetupCheckpointRowKindV1.CATALOG, listOf(valid), view = view)
            }
        }

        refused(null, "Unavailable aggregate enumeration produced an apparently empty manifest", unavailable = true)
        refused(AggregateKeyPage((1..101).map { AggregateKey(kind, DomainId.random()) }, null),
            "An aggregate page larger than its bound was accepted")
        refused(AggregateKeyPage(listOf(
            AggregateKey(kind, DomainId("00000000-0000-0000-0000-000000000002")),
            AggregateKey(kind, DomainId("00000000-0000-0000-0000-000000000001"))), null),
            "A non-monotonic aggregate page was accepted")
        refused(AggregateKeyPage(listOf(key), DomainId.random()),
            "A page cursor that did not identify its last key was accepted")
        refused(AggregateKeyPage(listOf(AggregateKey(Profile.OPENING_STOCK_ROW_KIND, key.id)), null),
            "An aggregate page for another kind was accepted")
    }

    @Test
    fun foreignInstallationAggregatesStreamPastLegacyCapacityWithoutAHiddenCumulativeCap() = runTest {
        val foreignInstallation = DomainId.random()
        val foreign = (1..1200).associate { index ->
            val sourceId = DomainId("00000000-0000-0000-0001-${index.toString().padStart(12, '0')}")
            val key = Profile.key(Profile.CATALOG_IMPORT_ROW_KIND,
                Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, foreignInstallation, sourceId))
            val record = LocalSetupCatalogImportRowRecordV1(
                binding = binding.copy(installationId = foreignInstallation),
                environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
                skuId = DomainId.random(), catalogOperationId = OperationId.random(),
                catalogPublicationId = DomainId.random(), catalogEffectDigest = PayloadDigest("4".repeat(64)),
                catalogEffectRevision = Revision(1), actorId = actorId, recordedAt = recordedAt,
                operationId = OperationId.random(), policyRevision = Revision(1),
                masterAuthorityEpoch = Revision(1),
            )
            key to aggregate(key, LocalSetupCheckpointCodecV1.encodeCatalogImportRow(record))
        }
        val current = listOf(catalogRow(), catalogRow())
        val expected = current.map { it.reference() }.sortedBy { it.sourceId.value }
        val prepared = prepare(LocalSetupCheckpointRowKindV1.CATALOG, current,
            view = viewOf(current, extraAggregates = foreign))
        val authorization = authorize(prepared.requirements.single())
        val page = LocalSetupCheckpointRowsV1.decodePage(prepared.project(listOf(authorization), recordedAt))

        assertEquals(expected, page.rows,
            "Valid foreign-installation aggregates hid or truncated the current installation's rows")
        assertEquals(2, page.rows.size)
        assertTrue(foreign.size > Profile.MAX_MANIFEST_ROWS,
            "The fixture did not exceed the legacy cumulative row capacity")
    }

    private data class SyntheticRow(
        val kind: LocalSetupCheckpointRowKindV1,
        val kindName: String,
        val sourceId: DomainId,
        val resourceId: DomainId,
        val originalOperationId: OperationId,
        val aggregate: StoredAggregate?,
        val retained: Map<OperationId, RetainedCommandEvidence>,
        val claim: Pair<ClaimKey, DomainId>,
    ) {
        fun reference() = com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowReferenceV1(
            sourceId, resourceId, originalOperationId)
    }

    private fun catalogRow(
        sourceId: DomainId = DomainId.random(),
        skuId: DomainId = DomainId.random(),
        originalOperationId: OperationId = OperationId.random(),
        recordBinding: LocalSetupOriginalBindingV1 = binding,
        includeAggregate: Boolean = true,
    ): SyntheticRow {
        val rowId = Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId)
        val key = Profile.key(Profile.CATALOG_IMPORT_ROW_KIND, rowId)
        val record = LocalSetupCatalogImportRowRecordV1(
            binding = recordBinding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
            skuId = skuId, catalogOperationId = originalOperationId,
            catalogPublicationId = DomainId.random(), catalogEffectDigest = PayloadDigest("1".repeat(64)),
            catalogEffectRevision = Revision(1), actorId = actorId, recordedAt = recordedAt,
            operationId = OperationId.random(), policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val payload = LocalSetupCheckpointCodecV1.encodeCatalogImportRow(record)
        return SyntheticRow(
            kind = LocalSetupCheckpointRowKindV1.CATALOG, kindName = Profile.CATALOG_IMPORT_ROW_KIND,
            sourceId = sourceId, resourceId = skuId, originalOperationId = originalOperationId,
            aggregate = if (includeAggregate) aggregate(key, payload) else null,
            retained = mapOf(record.operationId to evidence(record.operationId, Profile.CATALOG_IMPORT_ROW,
                key, payload, LocalSetupCheckpointResultV1(Profile.CATALOG_ROW_CODE, installationId,
                    rowId = rowId, rowCount = 1))),
            claim = Profile.sourceClaim(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId) to rowId,
        )
    }

    private fun stockRow(
        sourceId: DomainId = DomainId.random(),
        receiptId: DomainId = DomainId.random(),
        originalOperationId: OperationId = OperationId.random(),
    ): SyntheticRow {
        val rowId = Profile.rowId(Profile.OPENING_STOCK_ROW_KIND, installationId, sourceId)
        val key = Profile.key(Profile.OPENING_STOCK_ROW_KIND, rowId)
        val record = LocalSetupOpeningStockRowRecordV1(
            binding = binding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
            receiptId = receiptId, openingOperationId = originalOperationId, skuId = DomainId.random(),
            warehouseId = DomainId.random(), quantity = Quantity(DomainId.random(), 1, 0),
            value = Money(Currency.EGP, 100), lotId = null, serialId = null,
            stockEffectDigest = PayloadDigest("2".repeat(64)), stockEffectRevision = Revision(1),
            actorId = actorId, recordedAt = recordedAt, operationId = OperationId.random(),
            policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val payload = LocalSetupCheckpointCodecV1.encodeOpeningStockRow(record)
        return SyntheticRow(
            kind = LocalSetupCheckpointRowKindV1.STOCK, kindName = Profile.OPENING_STOCK_ROW_KIND,
            sourceId = sourceId, resourceId = receiptId, originalOperationId = originalOperationId,
            aggregate = aggregate(key, payload),
            retained = mapOf(record.operationId to evidence(record.operationId, Profile.OPENING_STOCK_ROW,
                key, payload, LocalSetupCheckpointResultV1(Profile.OPENING_STOCK_ROW_CODE, installationId,
                    rowId = rowId, rowCount = 1))),
            claim = Profile.sourceClaim(Profile.OPENING_STOCK_ROW_KIND, installationId, sourceId) to rowId,
        )
    }

    private fun floatRow(
        sourceId: DomainId = DomainId.random(),
        sessionId: DomainId = DomainId.random(),
        originalOperationId: OperationId = OperationId.random(),
    ): SyntheticRow {
        val rowId = Profile.rowId(Profile.OPENING_FLOAT_ROW_KIND, installationId, sourceId)
        val key = Profile.key(Profile.OPENING_FLOAT_ROW_KIND, rowId)
        val record = LocalSetupOpeningFloatRowRecordV1(
            binding = binding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = sourceId,
            sessionId = sessionId, openingOperationId = originalOperationId,
            amount = Money(Currency.EGP, 500), cashEffectDigest = PayloadDigest("3".repeat(64)),
            cashEffectRevision = Revision(1), actorId = actorId, recordedAt = recordedAt,
            operationId = OperationId.random(), policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val payload = LocalSetupCheckpointCodecV1.encodeOpeningFloatRow(record)
        return SyntheticRow(
            kind = LocalSetupCheckpointRowKindV1.FLOAT, kindName = Profile.OPENING_FLOAT_ROW_KIND,
            sourceId = sourceId, resourceId = sessionId, originalOperationId = originalOperationId,
            aggregate = aggregate(key, payload),
            retained = mapOf(record.operationId to evidence(record.operationId, Profile.OPENING_FLOAT_ROW,
                key, payload, LocalSetupCheckpointResultV1(Profile.OPENING_FLOAT_ROW_CODE, installationId,
                    rowId = rowId, rowCount = 1))),
            claim = Profile.sourceClaim(Profile.OPENING_FLOAT_ROW_KIND, installationId, sourceId) to rowId,
        )
    }

    private fun aggregate(key: AggregateKey, payload: StoredBytes): StoredAggregate =
        StoredAggregate(key, Revision(1), key.kind + "/v1", payload, sha256(payload.copyBytes()))

    private fun evidence(operationId: OperationId, command: com.ukkera.pos.database.CommandKey,
        key: AggregateKey, payload: StoredBytes, result: LocalSetupCheckpointResultV1): RetainedCommandEvidence =
        RetainedCommandEvidence(
            scope, operationId, actorId, command, assignmentId, Revision(1), JournalSequence(1), recordedAt,
            authenticationId, Revision(1), PayloadDigest("e".repeat(64)), "committed",
            StoredBytes("{}".encodeToByteArray()), LocalSetupCheckpointCodecV1.encodeResult(result), sha256(
                LocalSetupCheckpointCodecV1.encodeResult(result).copyBytes()),
            PayloadDigest("0".repeat(64)), PayloadDigest("d".repeat(64)),
            listOf(RetainedAggregateWrite(key, Revision(1), key.kind + "/v1", payload,
                sha256(payload.copyBytes()))), emptyList(), emptyList(),
        )

    private suspend fun prepare(kind: LocalSetupCheckpointRowKindV1, rows: List<SyntheticRow>,
        afterSourceId: DomainId? = null, original: SavedOriginalSetupV1 = this.original,
        view: CommandReadView = viewOf(rows, original)): PreparedRead {
        val request = LocalSetupCheckpointRowsV1.read(installationId, kind, afterSourceId)
        val normalized = reader.normalize(request.payload)
        return reader.prepare(normalized, view,
            ReadContext(scope, actorId, LocalSetupCheckpointRowsV1.READ,
                queryDigest(scope, actorId, LocalSetupCheckpointRowsV1.READ, normalized)))
    }

    private suspend fun prepareAsync(kind: LocalSetupCheckpointRowKindV1, rows: List<SyntheticRow>,
        original: SavedOriginalSetupV1 = this.original) = prepare(kind, rows, original = original)

    private suspend fun projectPage(kind: LocalSetupCheckpointRowKindV1, rows: List<SyntheticRow>,
        afterSourceId: DomainId? = null): LocalSetupCheckpointRowsPageV1 {
        val prepared = prepare(kind, rows, afterSourceId)
        val authorization = authorize(prepared.requirements.single())
        return LocalSetupCheckpointRowsV1.decodePage(prepared.project(listOf(authorization), recordedAt))
    }

    private fun viewOf(rows: List<SyntheticRow>, original: SavedOriginalSetupV1 = this.original,
        extraAggregates: Map<AggregateKey, StoredAggregate> = emptyMap(),
        claimsOverride: List<Pair<ClaimKey, DomainId>>? = null,
        aggregateKeysOverride: (suspend (String, DomainId?, Int) -> AggregateKeyPage)? = null): CommandReadView {
        val aggregates = rows.mapNotNull { row -> row.aggregate?.let { it.key to it } }.toMap() + extraAggregates
        val retained = rows.flatMap { it.retained.entries }.associate { it.key to it.value }
        val claims = claimsOverride ?: rows.map { it.claim }
        return object : CommandReadView {
            override val scope: BranchScope get() = this@LocalSetupCheckpointRowsReadSupportingTest.scope
            override suspend fun aggregate(key: AggregateKey): StoredAggregate? = aggregates[key]
            override suspend fun claim(key: ClaimKey): DomainId? = claims.firstOrNull { it.first == key }?.second
            override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
                retained[operationId]
            override suspend fun claimKeys(namespace: String, prefix: String, after: String?,
                limit: Int): ClaimKeyPage {
                val selected = claims.filter { it.first.namespace == namespace &&
                    it.first.key.startsWith(prefix) && (after == null || it.first.key > after) }
                    .distinctBy { it.first.key }.sortedBy { it.first.key }.take(limit + 1)
                val entries = selected.take(limit).map { ClaimKeyEntry(it.first, it.second) }
                return ClaimKeyPage(entries, if (selected.size > limit) entries.last().key.key else null)
            }
            override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
                aggregateKeysOverride?.let { return it(kind, after, limit) }
                val selected = aggregates.keys.filter { it.kind == kind &&
                    (after == null || it.id.value > after.value) }
                    .distinct().sortedBy { it.id.value }.take(limit + 1)
                return AggregateKeyPage(selected.take(limit),
                    if (selected.size > limit) selected[limit - 1].id else null)
            }
            override suspend fun savedOriginalSetup(): SavedOriginalSetupV1? = original
        }
    }

    private suspend fun principal(): com.ukkera.pos.security.AuthenticatedPrincipal =
        TrustedPrincipalFactory(object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity =
                VerifiedLocalIdentity(actorId, scope, deviceId, authenticationId, SessionGeneration(1),
                    Revision(1), AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }).authenticateLocal(LocalGatewayCredentials(DomainId.random(), OpaqueCredential(ByteArray(32) { 7 })))!!

    private suspend fun authorize(requirement: AuthorizationRequirement): AuthorizedAction {
        val decision = policy(SecurityAction.SETUP_CHECKPOINTS_READ).authorize(principal(), requirement)
        return assertIs<AuthorizationDecision.Allowed>(decision).authorization
    }

    private fun policy(action: SecurityAction): AuthorizationPolicy = AuthorizationPolicy(
        CurrentAuthorizationProvider { principal ->
            AuthorizationSnapshot(principal.subjectId, principal.kind, principal.scope, principal.authenticationId,
                principal.generation, deviceId, Revision(1), Revision(1), true, true, true,
                AuthenticationPurpose.INTERACTIVE, listOf(PermissionGrant(Permission(action, PermissionVerb.PERFORM),
                    scope, GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY, actorId, null, null)))
        })
}
