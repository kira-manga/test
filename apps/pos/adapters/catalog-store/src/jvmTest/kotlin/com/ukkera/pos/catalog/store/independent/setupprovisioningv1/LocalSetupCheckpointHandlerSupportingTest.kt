package com.ukkera.pos.catalog.store.independent.setupprovisioningv1

import com.ukkera.pos.catalog.store.ConfigurationBody
import com.ukkera.pos.catalog.store.ConfigurationRecord
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.LocalConfigurationResultV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.encodeValue
import com.ukkera.pos.catalog.store.setup.LocalProductionSetupGoLivePrerequisites
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportCheckpointV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointHistoryProducer
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1 as Profile
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProjectionV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointResultV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointServices
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointTargetV1
import com.ukkera.pos.catalog.store.setup.LocalSetupConfigurationRecordV1
import com.ukkera.pos.catalog.store.setup.LocalSetupConfigurationRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupFiscalIdentityV1
import com.ukkera.pos.catalog.store.setup.LocalSetupImportModeV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningCheckpointV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningChoiceV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadAuthorizationDenied
import com.ukkera.pos.database.MasterReadInvalidSelection
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.database.setup.LocalSetupAcknowledgementServices
import com.ukkera.pos.database.setup.LocalSetupGoLivePrerequisiteSource
import com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1
import com.ukkera.pos.database.setup.LocalSetupPrerequisiteObservationV1
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.database.setup.LocalSetupResultStatusV1
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalSalesAction
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.AuthenticationPurpose
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
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.sales.store.LocalSalesActivity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supplemental Linux/JVM handler-boundary coverage only. It drives the real installed production
 * checkpoint handlers/read/restart-producer against an explicitly constructed inert view holding
 * synthetic retained effects and a synthetic saved-original tuple. It proves the exact requirements,
 * bindings, duplicate-source hold and mismatch holds, but it is not a native original, durable
 * commit, current master transaction, protected read or SAS/AOSF case execution.
 */
class LocalSetupCheckpointHandlerSupportingTest {
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
        attemptSequence = 1,
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
    private val services = LocalSetupCheckpointServices(UnavailableCompanyPublicationSignatureVerifier)

    @Test
    fun catalogRowBindsOnlyTheExactRetainedSkuEffectAndRequiresTheCurrentActionGrant() = runTest {
        val sku = skuFixture()
        val sourceId = DomainId.random()
        val request = LocalSetupCatalogImportRowRequestV1(target, sourceId, sku.signed.definition.skuId,
            sku.operationId)
        val handler = services.commands.single { it.key == Profile.CATALOG_IMPORT_ROW }
        val normalized = handler.normalize(LocalSetupCheckpointCodecV1.catalogImportRow(OperationId.random(), request).payload)
        val view = view(aggregates = mapOf(sku.key to sku.current), retained = mapOf(sku.operationId to sku.evidence))
        val prepared = handler.prepare(normalized, view, context(handler.key, normalized))
        assertNull(prepared.admissionRestriction)
        val authorization = authorize(prepared.requirement, SecurityAction.SETUP_CATALOG_IMPORT_SETTLE)
        val outcome = assertIs<ProposedOutcome.Apply>(prepared.evaluate(authorization, recordedAt))
        val result = LocalSetupCheckpointCodecV1.decodeResult(outcome.result)
        val rowId = Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId)
        assertEquals(LocalSetupCheckpointResultV1(Profile.CATALOG_ROW_CODE, installationId, rowId, 1), result)
        assertEquals(Profile.key(Profile.CATALOG_IMPORT_ROW_KIND, rowId), outcome.changes.single().key)
        val record = LocalSetupCheckpointCodecV1.decodeCatalogImportRow(outcome.changes.single().payload)
        assertEquals(request.target.original, record.binding)
        assertEquals(sourceId, record.sourceId)
        assertEquals(sku.signed.definition.skuId, record.skuId)
        assertEquals(sku.operationId, record.catalogOperationId)
        assertEquals(sku.signed.definition.publicationId, record.catalogPublicationId)
        assertEquals(sku.effectDigest, record.catalogEffectDigest)
        assertEquals(Revision(1), record.catalogEffectRevision)
        assertEquals(actorId, record.actorId)
        assertEquals(recordedAt, record.recordedAt)
        assertEquals(Revision(1), record.masterAuthorityEpoch)
        assertEquals(listOf(ClaimChange.Acquire(Profile.sourceClaim(Profile.CATALOG_IMPORT_ROW_KIND,
            installationId, sourceId), rowId), ClaimChange.Acquire(Profile.catalogOperationClaim(
            installationId, sku.operationId), rowId)), outcome.claims)
    }

    @Test
    fun catalogRowHoldsOnAMismatchedOriginalAnOccupiedSourceOrAnInactiveEffect() = runTest {
        val sku = skuFixture()
        val sourceId = DomainId.random()
        val handler = services.commands.single { it.key == Profile.CATALOG_IMPORT_ROW }
        suspend fun prepare(targetValue: LocalSetupCheckpointTargetV1, view: CommandReadView,
            skuId: DomainId = sku.signed.definition.skuId): com.ukkera.pos.database.PreparedMutation {
            val request = LocalSetupCatalogImportRowRequestV1(targetValue, sourceId, skuId, sku.operationId)
            val normalized = handler.normalize(LocalSetupCheckpointCodecV1.catalogImportRow(
                OperationId.random(), request).payload)
            return handler.prepare(normalized, view, context(handler.key, normalized))
        }

        val mismatched = prepare(target.copy(original = binding.copy(installationId = DomainId.random())),
            view(aggregates = mapOf(sku.key to sku.current), retained = mapOf(sku.operationId to sku.evidence)))
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, mismatched.admissionRestriction)

        val occupied = prepare(target, view(aggregates = mapOf(sku.key to sku.current),
            retained = mapOf(sku.operationId to sku.evidence),
            claims = mapOf(Profile.sourceClaim(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId)
                to DomainId.random())))
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, occupied.admissionRestriction)

        val inactive = skuFixture(active = false)
        val inactivePrepared = prepare(target, view(aggregates = mapOf(inactive.key to inactive.current),
            retained = mapOf(inactive.operationId to inactive.evidence)), inactive.signed.definition.skuId)
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, inactivePrepared.admissionRestriction)
    }

    @Test
    fun catalogRowRequiresTheExactCurrentActionAndRejectsAnUnrelatedGrant() = runTest {
        val sku = skuFixture()
        val request = LocalSetupCatalogImportRowRequestV1(target, DomainId.random(),
            sku.signed.definition.skuId, sku.operationId)
        val handler = services.commands.single { it.key == Profile.CATALOG_IMPORT_ROW }
        val normalized = handler.normalize(LocalSetupCheckpointCodecV1.catalogImportRow(OperationId.random(), request).payload)
        val prepared = handler.prepare(normalized, view(aggregates = mapOf(sku.key to sku.current),
            retained = mapOf(sku.operationId to sku.evidence)), context(handler.key, normalized))
        val decision = policy(SecurityAction.SETUP_OPENING_SETTLE).authorize(principal(), prepared.requirement)
        assertIs<AuthorizationDecision.Denied>(decision)
    }

    @Test
    fun openingFloatRowBindsOnlyTheExactRetainedCashOpeningAndRequiresTheCurrentActionGrant() = runTest {
        val cash = cashFixture()
        val request = LocalSetupOpeningFloatRowRequestV1(target, cash.sourceId, cash.sessionId, cash.operationId)
        val handler = services.commands.single { it.key == Profile.OPENING_FLOAT_ROW }
        val normalized = handler.normalize(LocalSetupCheckpointCodecV1.openingFloatRow(OperationId.random(), request).payload)
        val view = view(aggregates = mapOf(cash.key to cash.current), claims = mapOf(cash.claim to cash.sessionId),
            retained = mapOf(cash.operationId to cash.evidence))
        val prepared = handler.prepare(normalized, view, context(handler.key, normalized))
        assertNull(prepared.admissionRestriction)
        val authorization = authorize(prepared.requirement, SecurityAction.SETUP_OPENING_SETTLE)
        val outcome = assertIs<ProposedOutcome.Apply>(prepared.evaluate(authorization, recordedAt))
        val result = LocalSetupCheckpointCodecV1.decodeResult(outcome.result)
        val rowId = Profile.rowId(Profile.OPENING_FLOAT_ROW_KIND, installationId, cash.sourceId)
        assertEquals(LocalSetupCheckpointResultV1(Profile.OPENING_FLOAT_ROW_CODE, installationId, rowId, 1), result)
        val record = LocalSetupCheckpointCodecV1.decodeOpeningFloatRow(outcome.changes.single().payload)
        assertEquals(cash.amount, record.amount)
        assertEquals(cash.sourceId, record.sourceId)
        assertEquals(cash.sessionId, record.sessionId)
        assertEquals(cash.operationId, record.openingOperationId)
        assertEquals(cash.effectDigest, record.cashEffectDigest)
        assertEquals(Revision(1), record.cashEffectRevision)
        assertEquals(listOf(ClaimChange.Acquire(Profile.sourceClaim(Profile.OPENING_FLOAT_ROW_KIND,
            installationId, cash.sourceId), rowId)), outcome.claims)
    }

    @Test
    fun openingFloatRowHoldsOnAMismatchedOriginalOrAMismatchedCashEffect() = runTest {
        val cash = cashFixture()
        val handler = services.commands.single { it.key == Profile.OPENING_FLOAT_ROW }
        suspend fun prepare(request: LocalSetupOpeningFloatRowRequestV1, view: CommandReadView) =
            handler.prepare(handler.normalize(LocalSetupCheckpointCodecV1.openingFloatRow(
                OperationId.random(), request).payload), view, context(handler.key,
                handler.normalize(LocalSetupCheckpointCodecV1.openingFloatRow(OperationId.random(), request).payload)))
        val goodView = view(aggregates = mapOf(cash.key to cash.current),
            claims = mapOf(cash.claim to cash.sessionId), retained = mapOf(cash.operationId to cash.evidence))
        val mismatched = prepare(LocalSetupOpeningFloatRowRequestV1(target.copy(
            original = binding.copy(installationId = DomainId.random())), cash.sourceId, cash.sessionId,
            cash.operationId), goodView)
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, mismatched.admissionRestriction)

        val wrongSource = prepare(LocalSetupOpeningFloatRowRequestV1(target, DomainId.random(), cash.sessionId,
            cash.operationId), goodView)
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, wrongSource.admissionRestriction)

        val missingClaim = prepare(LocalSetupOpeningFloatRowRequestV1(target, cash.sourceId, cash.sessionId,
            cash.operationId), view(aggregates = mapOf(cash.key to cash.current),
            retained = mapOf(cash.operationId to cash.evidence)))
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, missingClaim.admissionRestriction)
    }

    @Test
    fun protectedCheckpointReadProjectsTheFrozenSentinelsOnlyForTheExactOriginal() = runTest {
        val configuration = configurationRecord()
        val catalog = catalogCheckpoint()
        val opening = openingCheckpoint()
        val aggregates = mapOf(
            Profile.key(Profile.CONFIGURATION_KIND, installationId) to aggregate(
                Profile.key(Profile.CONFIGURATION_KIND, installationId),
                Profile.schema(Profile.CONFIGURATION_KIND),
                LocalSetupCheckpointCodecV1.encodeConfigurationRecord(configuration)),
            Profile.key(Profile.CATALOG_IMPORT_CHECKPOINT_KIND, installationId) to aggregate(
                Profile.key(Profile.CATALOG_IMPORT_CHECKPOINT_KIND, installationId),
                Profile.schema(Profile.CATALOG_IMPORT_CHECKPOINT_KIND),
                LocalSetupCheckpointCodecV1.encodeCatalogImportCheckpoint(catalog)),
            Profile.key(Profile.OPENING_CHECKPOINT_KIND, installationId) to aggregate(
                Profile.key(Profile.OPENING_CHECKPOINT_KIND, installationId),
                Profile.schema(Profile.OPENING_CHECKPOINT_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningCheckpoint(opening)),
        )
        val reader = services.reads.single { it.key == Profile.READ }
        val request = LocalSetupCheckpointCodecV1.read(installationId)
        val normalized = reader.normalize(request.payload)
        val prepared = reader.prepare(normalized, view(aggregates = aggregates),
            ReadContext(scope, actorId, Profile.READ, queryDigest(scope, actorId, Profile.READ, normalized)))
        val authorization = authorize(prepared.requirements.single(), SecurityAction.SETUP_CHECKPOINTS_READ)
        val projection = LocalSetupCheckpointCodecV1.decodeProjection(
            prepared.project(listOf(authorization), recordedAt))
        assertEquals(LocalSetupCheckpointProjectionV1(configuration, catalog, opening), projection)
        assertEquals(Money(Currency.EGP, 7500), projection.opening?.stockValueTotal)
        assertEquals(Money(Currency.EGP, 50000), projection.opening?.floatTotal)

        val other = LocalSetupCheckpointCodecV1.read(DomainId.random())
        val otherNormalized = reader.normalize(other.payload)
        assertFailsWith<MasterReadAuthorizationDenied> {
            reader.prepare(otherNormalized, view(aggregates = aggregates),
                ReadContext(scope, actorId, Profile.READ,
                    queryDigest(scope, actorId, Profile.READ, otherNormalized)))
        }
        assertFailsWith<MasterReadAuthorizationDenied> {
            reader.prepare(normalized, view(aggregates = aggregates, savedOriginal = null),
                ReadContext(scope, actorId, Profile.READ, queryDigest(scope, actorId, Profile.READ, normalized)))
        }
    }

    @Test
    fun restartProducerOwnsExactlyTheSixCheckpointCommands() {
        val producer = LocalSetupCheckpointHistoryProducer(UnavailableCompanyPublicationSignatureVerifier)
        assertEquals(setOf(Profile.CONFIGURE, Profile.CATALOG_IMPORT_ROW, Profile.COMPLETE_CATALOG_IMPORT,
            Profile.OPENING_STOCK_ROW, Profile.OPENING_FLOAT_ROW, Profile.COMPLETE_OPENING), producer.commands)
        assertIs<LocalProductionSetupGoLivePrerequisites>(services.prerequisites)
        assertTrue(producer.actionApprovalBusinessAttribution(Profile.CONFIGURE) ==
            com.ukkera.pos.database.ActionApprovalBusinessAttributionSupport.UNSUPPORTED)
    }

    private class SkuFixture(
        val signed: LocalSignedSkuV1,
        val operationId: OperationId,
        val key: AggregateKey,
        val current: StoredAggregate,
        val evidence: RetainedCommandEvidence,
        val effectDigest: PayloadDigest,
    )

    private fun skuFixture(active: Boolean = true): SkuFixture {
        val skuId = DomainId.random()
        val publicationId = DomainId.random()
        val baseUnitId = DomainId.random()
        val signed = LocalSignedSkuV1(LocalPublishedSkuV1(
            company = scope.company(), publicationId = publicationId, skuId = skuId,
            productId = DomainId.random(), catalogRevision = Revision(0), skuRevision = Revision(0),
            names = LocalNamesV1("Setup synthetic sentinel", ""), code = "SETUP-SYNTH-001",
            barcodes = emptyList(), baseUnitId = baseUnitId, quantityScale = 0,
            tracking = LocalTrackingV1.QUANTITY, fulfillment = LocalFulfillmentV1.RETAIL,
            requiresExpiry = false, canPurchase = true, canSell = true, active = active,
            conversions = emptyList(), prices = listOf(LocalPriceV1(
                ruleId = DomainId.random(), revision = Revision(0), channel = "COUNTER", priority = 0,
                minimumQuantity = Quantity(baseUnitId, 0, 0), maximumQuantityExclusive = null,
                effectiveFrom = recordedAt, effectiveUntil = null,
                unitPrice = UnitPrice(Currency.EGP, ExactDecimal.parse("25.00")),
                inclusion = TaxInclusion.INCLUSIVE,
                tax = TaxSnapshot(DomainId.random(), Revision(0), "SYNTHETIC_ZERO", PercentageRate.parse("0")),
            )), returnPromise = null,
        ), DomainId.random(), "0".repeat(128))
        val key = LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId)
        val record = ConfigurationRecord(scope, LocalConfigurationProfile.SKU, skuId, Revision(0),
            ConfigurationBody.Sku(signed), actorId, Revision(1), recordedAt)
        val payload = encodeValue(record, ConfigurationRecord.serializer())
        val digest = sha256(payload.copyBytes())
        val current = StoredAggregate(key, Revision(1),
            LocalConfigurationProfile.schema(LocalConfigurationProfile.SKU), payload, digest)
        val operationId = OperationId.random()
        val normalized = LocalConfigurationCodec.encode(LocalConfigurationRequestV1.InstallCompanySku(signed))
        val result = encodeValue(LocalConfigurationResultV1("configuration.applied", skuId, Revision(0)),
            LocalConfigurationResultV1.serializer())
        val evidence = RetainedCommandEvidence(scope, operationId, actorId, LocalConfigurationProfile.COMMAND,
            assignmentId, Revision(1), JournalSequence(1), recordedAt, authenticationId, Revision(1),
            semanticDigest(scope, actorId, LocalConfigurationProfile.COMMAND, normalized), "committed",
            normalized, result, sha256(result.copyBytes()), PayloadDigest("0".repeat(64)),
            PayloadDigest("d".repeat(64)), listOf(RetainedAggregateWrite(key, Revision(1),
                LocalConfigurationProfile.schema(LocalConfigurationProfile.SKU), payload, digest)),
            emptyList(), emptyList())
        return SkuFixture(signed, operationId, key, current, evidence, digest)
    }

    private class CashFixture(
        val sourceId: DomainId,
        val sessionId: DomainId,
        val operationId: OperationId,
        val amount: Money,
        val key: AggregateKey,
        val current: StoredAggregate,
        val evidence: RetainedCommandEvidence,
        val effectDigest: PayloadDigest,
        val claim: com.ukkera.pos.database.ClaimKey,
    )

    private fun cashFixture(): CashFixture {
        val sourceId = DomainId.random()
        val sessionId = DomainId.random()
        val drawerId = DomainId.random()
        val amount = Money(Currency.EGP, 50000)
        val request = LocalCashRequestV1.Open(sessionId, drawerId, Revision(0), Revision(0),
            BusinessDate.parseCanonical("2026-11-09"), sourceId, amount,
            LocalCashEvidenceV1(DomainId.random(), recordedAt))
        val normalized = LocalCashCommandCodec.encode(request)
        val result = salesJson.encodeToString(LocalSalesResultV1.serializer(),
            LocalSalesResultV1.Applied(LocalSalesAction.CASH_OPENED, sessionId, emptyList(), amount))
            .encodeToByteArray()
        val key = CashNonSaleStoreProfile.sessionKey(sessionId)
        val payload = StoredBytes("{}".encodeToByteArray())
        val digest = sha256(payload.copyBytes())
        val current = StoredAggregate(key, Revision(1), CashNonSaleStoreProfile.SESSION_SCHEMA, payload, digest)
        val operationId = OperationId.random()
        val evidence = RetainedCommandEvidence(scope, operationId, actorId, LocalSalesStoreProfile.CASH_COMMAND,
            assignmentId, Revision(1), JournalSequence(2), recordedAt, authenticationId, Revision(1),
            semanticDigest(scope, actorId, LocalSalesStoreProfile.CASH_COMMAND, normalized), "committed",
            normalized, StoredBytes(result), sha256(result), PayloadDigest("0".repeat(64)),
            PayloadDigest("e".repeat(64)), listOf(RetainedAggregateWrite(key, Revision(1),
                CashNonSaleStoreProfile.SESSION_SCHEMA, payload, digest)), emptyList(), emptyList())
        return CashFixture(sourceId, sessionId, operationId, amount, key, current, evidence, digest,
            LocalSalesStoreProfile.unique("opening_source", sourceId))
    }

    private fun configurationRecord(): LocalSetupConfigurationRecordV1 = LocalSetupConfigurationRecordV1(
        binding = binding, environment = LocalSetupEnvironmentV1.LIVE, revision = Revision(1),
        activities = listOf(LocalSalesActivity.SUPERMARKET),
        currency = Currency.EGP, timeZone = "Africa/Cairo", businessDayCutoffMinute = 0,
        fiscalIdentity = LocalSetupFiscalIdentityV1("Synthetic setup", taxRegistrationAbsent = true),
        branchActivityOperationId = OperationId.random(),
        branchActivityReference = com.ukkera.pos.catalog.store.LocalBranchActivityReferenceV1(
            scope.company(), DomainId.random(), DomainId.random(), Revision(0), PayloadDigest("f".repeat(64)),
            DomainId.random()),
        actorId = actorId, recordedAt = recordedAt, operationId = OperationId.random(),
        policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
    )

    private fun catalogCheckpoint(): LocalSetupCatalogImportCheckpointV1 = LocalSetupCatalogImportCheckpointV1(
        binding = binding, environment = LocalSetupEnvironmentV1.LIVE, mode = LocalSetupImportModeV1.DECLARED,
        rowCount = 1, manifestDigest = PayloadDigest("1".repeat(64)),
        inputManifestDigest = PayloadDigest("2".repeat(64)), actorId = actorId, recordedAt = recordedAt,
        operationId = OperationId.random(), policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
    )

    private fun openingCheckpoint(): LocalSetupOpeningCheckpointV1 = LocalSetupOpeningCheckpointV1(
        binding = binding, environment = LocalSetupEnvironmentV1.LIVE,
        stockChoice = LocalSetupOpeningChoiceV1.DECLARED, stockRowCount = 1,
        stockValueTotal = Money(Currency.EGP, 7500),
        floatChoice = LocalSetupOpeningChoiceV1.DECLARED, floatRowCount = 1,
        floatTotal = Money(Currency.EGP, 50000),
        stockManifestDigest = PayloadDigest("3".repeat(64)),
        stockInputManifestDigest = PayloadDigest("4".repeat(64)),
        floatManifestDigest = PayloadDigest("5".repeat(64)),
        floatInputManifestDigest = PayloadDigest("6".repeat(64)),
        actorId = actorId, recordedAt = recordedAt, operationId = OperationId.random(),
        policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
    )

    @Test
    fun checkpointDetailReadRequiresItsOwnExplicitGrantNotSavedResultReadAlone() = runTest {
        val configuration = configurationRecord()
        val aggregates = mapOf(
            Profile.key(Profile.CONFIGURATION_KIND, installationId) to aggregate(
                Profile.key(Profile.CONFIGURATION_KIND, installationId),
                Profile.schema(Profile.CONFIGURATION_KIND),
                LocalSetupCheckpointCodecV1.encodeConfigurationRecord(configuration)),
        )
        val reader = services.reads.single { it.key == Profile.READ }
        val normalized = reader.normalize(LocalSetupCheckpointCodecV1.read(installationId).payload)
        val prepared = reader.prepare(normalized, view(aggregates = aggregates),
            ReadContext(scope, actorId, Profile.READ, queryDigest(scope, actorId, Profile.READ, normalized)))

        val savedResultOnly = policy(SecurityAction.SETUP_RESULT_READ).authorize(principal(), prepared.requirements.single())
        assertIs<AuthorizationDecision.Denied>(savedResultOnly,
            "SETUP_RESULT_READ alone disclosed the checkpoint detail read")

        val checkpointAction = SecurityAction.SETUP_CHECKPOINTS_READ
        val explicit = policy(checkpointAction).authorize(principal(), prepared.requirements.single())
        assertIs<AuthorizationDecision.Allowed>(explicit,
            "SETUP_CHECKPOINTS_READ did not authorize the checkpoint detail read")
    }

    @Test
    fun changedNativeOriginalUnderTheSameInstallationDoesNotDiscloseOldAckOrLiveAsCurrent() = runTest {
        val setupResults = LocalSetupAcknowledgementServices(settledPrerequisites())
        val ackPayload = setupResultPayload(setupResults, LocalSetupResultProfileV1.ACKNOWLEDGE,
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE, LocalSetupResultCodecV1.acknowledge(
                OperationId.random(), installationId), original)
        val livePayload = setupResultPayload(setupResults, LocalSetupResultProfileV1.GO_LIVE,
            SecurityAction.SETUP_BUSINESS_GO_LIVE, LocalSetupResultCodecV1.goLive(
                OperationId.random(), installationId), original)
        val ackKey = AggregateKey(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, installationId)
        val liveKey = AggregateKey(LocalSetupResultProfileV1.GO_LIVE_KIND, installationId)
        val aggregates = mapOf(
            ackKey to aggregate(ackKey, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_SCHEMA, ackPayload),
            liveKey to aggregate(liveKey, LocalSetupResultProfileV1.GO_LIVE_SCHEMA, livePayload),
        )
        val changed = original.copy(receiptDigest = PayloadDigest("9".repeat(64)), attemptSequence = 2)
        val reader = setupResults.reads.single { it.key == LocalSetupResultProfileV1.READ }
        val normalized = reader.normalize(LocalSetupResultCodecV1.read(installationId).payload)
        val projection = runCatching {
            val prepared = reader.prepare(normalized, view(aggregates = aggregates, savedOriginal = changed),
                ReadContext(scope, actorId, LocalSetupResultProfileV1.READ,
                    queryDigest(scope, actorId, LocalSetupResultProfileV1.READ, normalized)))
            val authorization = authorize(prepared.requirements.single(),
                prepared.requirements.single().permission.action)
            LocalSetupResultCodecV1.decodeProjection(prepared.project(listOf(authorization), recordedAt))
        }.getOrNull()
        assertTrue(projection == null || (projection.acknowledgement == null && projection.goLive == null),
            "A different saved original under the same installation disclosed the old ACK/live facts as current")
    }

    @Test
    fun committedAckAndLiveRecordsRetainReceiptDigestAndAttemptSequence() = runTest {
        val setupResults = LocalSetupAcknowledgementServices(settledPrerequisites())
        val ackPayload = setupResultPayload(setupResults, LocalSetupResultProfileV1.ACKNOWLEDGE,
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE, LocalSetupResultCodecV1.acknowledge(
                OperationId.random(), installationId), original)
        val livePayload = setupResultPayload(setupResults, LocalSetupResultProfileV1.GO_LIVE,
            SecurityAction.SETUP_BUSINESS_GO_LIVE, LocalSetupResultCodecV1.goLive(
                OperationId.random(), installationId), original)
        for ((label, payload) in listOf("ACK" to ackPayload, "live" to livePayload)) {
            val json = payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
            assertTrue(json.contains("\"receiptDigest\""), "$label record omitted receiptDigest")
            assertTrue(json.contains("\"attemptSequence\""), "$label record omitted attemptSequence")
        }
    }

    @Test
    fun goLiveHoldsOnAnyUnsettledPrerequisiteAndWritesNothing() = runTest {
        val unsettled = LocalSetupGoLivePrerequisiteSource { _, _ ->
            listOf(
                LocalSetupPrerequisiteObservationV1.settled(
                    LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION, "setup.configuration:op"),
                LocalSetupPrerequisiteObservationV1.unavailable(
                    LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                    "unavailable:setup.catalog-import.checkpoint.missing"),
                LocalSetupPrerequisiteObservationV1.settled(
                    LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT, "setup.opening:op"),
            )
        }
        val setupResults = LocalSetupAcknowledgementServices(unsettled)
        val handler = setupResults.commands.single { it.key == LocalSetupResultProfileV1.GO_LIVE }
        val operationId = OperationId.random()
        val request = LocalSetupResultCodecV1.goLive(operationId, installationId)
        val normalized = handler.normalize(request.payload)
        val prepared = handler.prepare(normalized, view(),
            CommandContext(scope, actorId, operationId, handler.key,
                semanticDigest(scope, actorId, handler.key, normalized)))
        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, prepared.admissionRestriction)
    }

    @Test
    fun sameCatalogOperationIdUnderTwoSourceIdsCannotSettleTwice() = runTest {
        val sku = skuFixture()
        val firstSource = DomainId.random()
        val secondSource = DomainId.random()
        val firstRowId = Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, installationId, firstSource)
        val firstRecord = LocalSetupCatalogImportRowRecordV1(
            binding = binding, environment = LocalSetupEnvironmentV1.LIVE, sourceId = firstSource,
            skuId = sku.signed.definition.skuId, catalogOperationId = sku.operationId,
            catalogPublicationId = sku.signed.definition.publicationId,
            catalogEffectDigest = sku.effectDigest, catalogEffectRevision = Revision(1),
            actorId = actorId, recordedAt = recordedAt, operationId = OperationId.random(),
            policyRevision = Revision(1), masterAuthorityEpoch = Revision(1),
        )
        val firstPayload = LocalSetupCheckpointCodecV1.encodeCatalogImportRow(firstRecord)
        val aggregates = mapOf(
            sku.key to sku.current,
            Profile.key(Profile.CATALOG_IMPORT_ROW_KIND, firstRowId) to aggregate(
                Profile.key(Profile.CATALOG_IMPORT_ROW_KIND, firstRowId),
                Profile.schema(Profile.CATALOG_IMPORT_ROW_KIND), firstPayload),
        )
        val claims = mapOf(Profile.sourceClaim(Profile.CATALOG_IMPORT_ROW_KIND, installationId, firstSource) to firstRowId,
            Profile.catalogOperationClaim(installationId, sku.operationId) to firstRowId)
        val handler = services.commands.single { it.key == Profile.CATALOG_IMPORT_ROW }
        val request = LocalSetupCatalogImportRowRequestV1(target, secondSource, sku.signed.definition.skuId,
            sku.operationId)
        val normalized = handler.normalize(LocalSetupCheckpointCodecV1.catalogImportRow(OperationId.random(), request).payload)
        val prepared = handler.prepare(normalized, view(aggregates = aggregates, claims = claims,
            retained = mapOf(sku.operationId to sku.evidence)), context(handler.key, normalized))
        val blocked = prepared.admissionRestriction == PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE ||
            (prepared.admissionRestriction == null && run {
                val authorization = authorize(prepared.requirement, SecurityAction.SETUP_CATALOG_IMPORT_SETTLE)
                val outcome = prepared.evaluate(authorization, recordedAt)
                outcome is ProposedOutcome.Reject
            })
        assertTrue(blocked, "One catalog operation ID settled two distinct import source rows")
    }


    @Test
    fun checkpointAndGoLiveWritesDeclareTheStockCashAuthorizationRecoveryBarriers() = runTest {
        val expected = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY,
            RecoveryResourceKind.AUTHORIZATION)
        val configHandler = services.commands.single { it.key == Profile.CONFIGURE }
        val configRequest = LocalSetupConfigurationRequestV1(target, Revision(0), Currency.EGP,
            "Africa/Cairo", 0, LocalSetupFiscalIdentityV1("Synthetic setup", taxRegistrationAbsent = true))
        val configNormalized = configHandler.normalize(LocalSetupCheckpointCodecV1.configure(
            OperationId.random(), configRequest).payload)
        val configPrepared = configHandler.prepare(configNormalized, view(),
            context(configHandler.key, configNormalized))
        assertEquals(expected, configPrepared.recoveryHoldBarrierKinds)

        val sku = skuFixture()
        val rowHandler = services.commands.single { it.key == Profile.CATALOG_IMPORT_ROW }
        val rowRequest = LocalSetupCatalogImportRowRequestV1(target, DomainId.random(),
            sku.signed.definition.skuId, sku.operationId)
        val rowNormalized = rowHandler.normalize(LocalSetupCheckpointCodecV1.catalogImportRow(
            OperationId.random(), rowRequest).payload)
        val rowPrepared = rowHandler.prepare(rowNormalized, view(aggregates = mapOf(sku.key to sku.current),
            retained = mapOf(sku.operationId to sku.evidence)), context(rowHandler.key, rowNormalized))
        assertEquals(expected, rowPrepared.recoveryHoldBarrierKinds)

        val cash = cashFixture()
        val floatHandler = services.commands.single { it.key == Profile.OPENING_FLOAT_ROW }
        val floatRequest = LocalSetupOpeningFloatRowRequestV1(target, cash.sourceId, cash.sessionId, cash.operationId)
        val floatNormalized = floatHandler.normalize(LocalSetupCheckpointCodecV1.openingFloatRow(
            OperationId.random(), floatRequest).payload)
        val floatPrepared = floatHandler.prepare(floatNormalized, view(aggregates = mapOf(cash.key to cash.current),
            claims = mapOf(cash.claim to cash.sessionId),
            retained = mapOf(cash.operationId to cash.evidence)), context(floatHandler.key, floatNormalized))
        assertEquals(expected, floatPrepared.recoveryHoldBarrierKinds)

        val setupResults = LocalSetupAcknowledgementServices(settledPrerequisites())
        val ackHandler = setupResults.commands.single { it.key == LocalSetupResultProfileV1.ACKNOWLEDGE }
        val ackRequest = LocalSetupResultCodecV1.acknowledge(OperationId.random(), installationId)
        val ackNormalized = ackHandler.normalize(ackRequest.payload)
        val ackPrepared = ackHandler.prepare(ackNormalized, view(),
            CommandContext(scope, actorId, ackRequest.operationId, ackHandler.key,
                semanticDigest(scope, actorId, ackHandler.key, ackNormalized)))
        assertEquals(setOf(RecoveryResourceKind.AUTHORIZATION), ackPrepared.recoveryHoldBarrierKinds)

        val liveHandler = setupResults.commands.single { it.key == LocalSetupResultProfileV1.GO_LIVE }
        val liveRequest = LocalSetupResultCodecV1.goLive(OperationId.random(), installationId)
        val liveNormalized = liveHandler.normalize(liveRequest.payload)
        val livePrepared = liveHandler.prepare(liveNormalized, view(),
            CommandContext(scope, actorId, liveRequest.operationId, liveHandler.key,
                semanticDigest(scope, actorId, liveHandler.key, liveNormalized)))
        assertEquals(expected, livePrepared.recoveryHoldBarrierKinds)
    }

    private fun settledPrerequisites(): LocalSetupGoLivePrerequisiteSource = object : LocalSetupGoLivePrerequisiteSource {
        override suspend fun observe(view: CommandReadView, original: SavedOriginalSetupV1) = listOf(
            LocalSetupPrerequisiteObservationV1.settled(
                LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION, "setup.configuration:op"),
            LocalSetupPrerequisiteObservationV1.settled(
                LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT, "setup.catalog-import:op"),
            LocalSetupPrerequisiteObservationV1.settled(
                LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT, "setup.opening:op"),
        )
        override suspend fun recoveryResources(view: CommandReadView, installationId: DomainId) = emptySet<com.ukkera.pos.recovery.RecoveryResourceKey>()
    }

    private suspend fun setupResultPayload(services: LocalSetupAcknowledgementServices,
        command: com.ukkera.pos.database.CommandKey, action: SecurityAction,
        request: com.ukkera.pos.database.MutationRequest,
        savedOriginal: SavedOriginalSetupV1): StoredBytes {
        val handler = services.commands.single { it.key == command }
        val normalized = handler.normalize(request.payload)
        val prepared = handler.prepare(normalized, view(savedOriginal = savedOriginal),
            CommandContext(scope, actorId, request.operationId, handler.key,
                semanticDigest(scope, actorId, handler.key, normalized)))
        assertNull(prepared.admissionRestriction, "$command was held with a settled prerequisite source")
        val authorization = authorize(prepared.requirement, action)
        val outcome = assertIs<ProposedOutcome.Apply>(prepared.evaluate(authorization, recordedAt))
        return outcome.changes.single().payload
    }

    private fun aggregate(key: AggregateKey, schema: String, payload: StoredBytes): StoredAggregate =
        StoredAggregate(key, Revision(1), schema, payload, sha256(payload.copyBytes()))

    private fun context(key: CommandKey, normalized: StoredBytes): CommandContext =
        CommandContext(scope, actorId, OperationId.random(), key, semanticDigest(scope, actorId, key, normalized))

    private suspend fun principal(): com.ukkera.pos.security.AuthenticatedPrincipal =
        TrustedPrincipalFactory(object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity =
                VerifiedLocalIdentity(actorId, scope, deviceId, authenticationId, SessionGeneration(1),
                    Revision(1), AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }).authenticateLocal(LocalGatewayCredentials(DomainId.random(), OpaqueCredential(ByteArray(32) { 7 })))!!

    private suspend fun authorize(requirement: AuthorizationRequirement,
        action: SecurityAction): AuthorizedAction {
        val decision = policy(action).authorize(principal(), requirement)
        return assertIs<AuthorizationDecision.Allowed>(decision).authorization
    }

    private fun policy(action: SecurityAction): AuthorizationPolicy = AuthorizationPolicy(
        CurrentAuthorizationProvider { principal ->
            val verb = when (action) {
                SecurityAction.SETUP_RESULT_READ,
                SecurityAction.SETUP_CHECKPOINTS_READ,
                SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE,
                SecurityAction.SETUP_BUSINESS_GO_LIVE -> PermissionVerb.PERFORM
                else -> PermissionVerb.ADMINISTER
            }
            AuthorizationSnapshot(principal.subjectId, principal.kind, principal.scope, principal.authenticationId,
                principal.generation, deviceId, Revision(1), Revision(1), true, true, true,
                AuthenticationPurpose.INTERACTIVE, listOf(PermissionGrant(Permission(action, verb), scope,
                    GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY, actorId, null, null)))
        })

    private fun view(aggregates: Map<AggregateKey, StoredAggregate> = emptyMap(),
        claims: Map<com.ukkera.pos.database.ClaimKey, DomainId> = emptyMap(),
        retained: Map<OperationId, RetainedCommandEvidence> = emptyMap(),
        savedOriginal: SavedOriginalSetupV1? = original): CommandReadView = object : CommandReadView {
        override val scope: BranchScope get() = this@LocalSetupCheckpointHandlerSupportingTest.scope
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = aggregates[key]
        override suspend fun claim(key: com.ukkera.pos.database.ClaimKey): DomainId? = claims[key]
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = retained[operationId]
        override suspend fun savedOriginalSetup(): SavedOriginalSetupV1? = savedOriginal
        override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
            val selected = aggregates.keys.filter { it.kind == kind &&
                (after == null || it.id.value > after.value) }
                .distinct().sortedBy { it.id.value }.take(limit + 1)
            return AggregateKeyPage(selected.take(limit),
                if (selected.size > limit) selected[limit - 1].id else null)
        }
    }

    private companion object {
        val salesJson = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
            isLenient = false
            coerceInputValues = false
        }
    }
}
