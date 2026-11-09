package com.ukkera.pos.catalog.store.setup

import com.ukkera.pos.catalog.store.BranchActivityBodyV1
import com.ukkera.pos.catalog.store.LocalBranchActivityProfileV1
import com.ukkera.pos.catalog.store.authenticBranchActivity
import com.ukkera.pos.catalog.store.branchActivityApplied
import com.ukkera.pos.catalog.store.branchActivityCommandResources
import com.ukkera.pos.catalog.store.branchActivityReference
import com.ukkera.pos.catalog.store.decodeBranchActivityRecord
import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

/**
 * Restart/reopen producer for the additive setup checkpoints. It reconstructs the exact
 * command effect from the genuine native BEFORE cut and the same retained operation/result
 * references used by the live handler. Unsupported/mismatched history returns false rather
 * than inventing a checkpoint, actor or effect.
 */
public class LocalSetupCheckpointHistoryProducer(
    private val signatures: CompanyPublicationSignatureVerifier,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(
        LocalSetupCheckpointProfileV1.CONFIGURE,
        LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW,
        LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT,
        LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW,
        LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW,
        LocalSetupCheckpointProfileV1.COMPLETE_OPENING,
    )

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val evidence = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (!evidence.committed || evidence.nativeOriginalProvenance != null || evidence.command != command.command) return false
        return try {
            when (command.command) {
                LocalSetupCheckpointProfileV1.CONFIGURE -> joinConfiguration(command, evidence)
                LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW -> joinCatalogRow(command, evidence)
                LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT -> joinCatalogCheckpoint(command, evidence)
                LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW -> joinOpeningStockRow(command, evidence)
                LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW -> joinOpeningFloatRow(command, evidence)
                LocalSetupCheckpointProfileV1.COMPLETE_OPENING -> joinOpeningCheckpoint(command, evidence)
                else -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun joinConfiguration(command: RuntimeHistoricalCommand, evidence: RetainedCommandEvidence): Boolean {
        val request = LocalSetupCheckpointCodecV1.decodeConfigurationRequest(command.normalized)
        if (!request.target.original.matchesHistorical(command)) return false
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND,
            request.target.original.installationId)
        val before = command.aggregate(key)
        if (request.expectedRevision != (before?.revision ?: Revision(0))) return false
        if (before != null) {
            val old = try { LocalSetupCheckpointCodecV1.decodeConfigurationRecord(before.payload) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
            if (old.binding != request.target.original) return false
        }
        val activity = historicalActivity(command) ?: return false
        val revision = before?.revision?.next() ?: Revision(1)
        val record = LocalSetupConfigurationRecordV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            revision = revision,
            activities = activity.activities,
            currency = request.currency,
            timeZone = request.timeZone,
            businessDayCutoffMinute = request.businessDayCutoffMinute,
            fiscalIdentity = request.fiscalIdentity,
            branchActivityOperationId = activity.operationId,
            branchActivityReference = activity.reference,
            actorId = command.actorId,
            recordedAt = command.recordedAt,
            operationId = command.operationId,
            policyRevision = command.policyRevision,
            masterAuthorityEpoch = evidenceEpoch(command),
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CONFIGURED_CODE,
            request.target.original.installationId)
        command.requireRequirement(configurationRequirement(command, request.target.original.installationId,
            before?.revision ?: Revision(0)))
        val resources = setupResources(command.actorId, request.target.original.installationId, activity.resources)
        command.requireLegacyEffects(ProposedOutcome.Apply(
            LocalSetupCheckpointCodecV1.encodeResult(result),
            listOf(AggregateChange(key, before?.revision,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND),
                LocalSetupCheckpointCodecV1.encodeConfigurationRecord(record))),
        ), resources)
        return true
    }

    private suspend fun joinCatalogRow(command: RuntimeHistoricalCommand, evidence: RetainedCommandEvidence): Boolean {
        val request = LocalSetupCheckpointCodecV1.decodeCatalogImportRowRequest(command.normalized)
        if (!request.target.original.matchesHistorical(command)) return false
        val installationId = request.target.original.installationId
        val rowId = LocalSetupCheckpointProfileV1.rowId(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND,
            installationId, request.sourceId)
        val rowKey = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, rowId)
        val claim = LocalSetupCheckpointProfileV1.sourceClaim(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND,
            installationId, request.sourceId)
        val operationClaim = LocalSetupCheckpointProfileV1.catalogOperationClaim(installationId, request.catalogOperationId)
        if (command.aggregate(rowKey) != null || command.claim(claim) != null || command.claim(operationClaim) != null) return false
        val write = evidence.aggregateWrites.singleOrNull { it.key == rowKey } ?: return false
        if (write.schema != LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND)) return false
        val record = try { LocalSetupCheckpointCodecV1.decodeCatalogImportRow(write.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (record.binding != request.target.original || record.sourceId != request.sourceId ||
            record.skuId != request.skuId || record.catalogOperationId != request.catalogOperationId ||
            record.actorId != command.actorId || record.recordedAt != command.recordedAt ||
            record.operationId != command.operationId || record.policyRevision != command.policyRevision ||
            record.masterAuthorityEpoch != evidenceEpoch(command)) return false
        val effect = catalogEffect(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope,
            request.skuId, request.catalogOperationId) ?: return false
        if (effect.publicationId != record.catalogPublicationId || effect.digest != record.catalogEffectDigest ||
            effect.revision != record.catalogEffectRevision) return false
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CATALOG_ROW_CODE,
            installationId, rowId = rowId, rowCount = 1)
        command.requireRequirement(catalogRequirement(command, installationId, Revision(0)))
        val resources = setupResources(command.actorId, installationId, setOf(
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.skuId),
        ))
        command.requireLegacyEffects(ProposedOutcome.Apply(
            LocalSetupCheckpointCodecV1.encodeResult(result),
            listOf(AggregateChange(rowKey, null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND),
                LocalSetupCheckpointCodecV1.encodeCatalogImportRow(record))),
            listOf(ClaimChange.Acquire(claim, rowId), ClaimChange.Acquire(operationClaim, rowId)),
        ), resources)
        return true
    }

    private suspend fun joinCatalogCheckpoint(command: RuntimeHistoricalCommand,
        evidence: RetainedCommandEvidence): Boolean {
        val request = LocalSetupCheckpointCodecV1.decodeCompleteCatalogImportRequest(command.normalized)
        if (!request.target.original.matchesHistorical(command)) return false
        val installationId = request.target.original.installationId
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND,
            installationId)
        if (command.aggregate(key) != null) return false
        val actual = historicalCatalogRows(command, installationId) ?: return false
        val rows = verifyHistoricalCatalogManifest(command, installationId, request, actual) ?: return false
        val digest = LocalSetupCheckpointCodecV1.catalogManifestDigest(rows)
        val record = LocalSetupCatalogImportCheckpointV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            mode = request.mode,
            rowCount = rows.size,
            manifestDigest = digest,
            inputManifestDigest = request.expectedManifestDigest,
            actorId = command.actorId,
            recordedAt = command.recordedAt,
            operationId = command.operationId,
            policyRevision = command.policyRevision,
            masterAuthorityEpoch = evidenceEpoch(command),
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CATALOG_SETTLED_CODE,
            installationId, rowCount = rows.size, manifestDigest = digest)
        command.requireRequirement(catalogRequirement(command, installationId, Revision(0)))
        val resources = setupResources(command.actorId, installationId,
            actual.flatMap { (_, row) -> setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.skuId)) }.toSet())
        command.requireLegacyEffects(ProposedOutcome.Apply(
            LocalSetupCheckpointCodecV1.encodeResult(result),
            listOf(AggregateChange(key, null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND),
                LocalSetupCheckpointCodecV1.encodeCatalogImportCheckpoint(record))),
        ), resources)
        return true
    }

    private suspend fun joinOpeningStockRow(command: RuntimeHistoricalCommand,
        evidence: RetainedCommandEvidence): Boolean {
        val request = LocalSetupCheckpointCodecV1.decodeOpeningStockRowRequest(command.normalized)
        if (!request.target.original.matchesHistorical(command)) return false
        val installationId = request.target.original.installationId
        val rowId = LocalSetupCheckpointProfileV1.rowId(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND,
            installationId, request.sourceId)
        val rowKey = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, rowId)
        val claim = LocalSetupCheckpointProfileV1.sourceClaim(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND,
            installationId, request.sourceId)
        if (command.aggregate(rowKey) != null || command.claim(claim) != null) return false
        val write = evidence.aggregateWrites.singleOrNull { it.key == rowKey } ?: return false
        if (write.schema != LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND)) return false
        val record = try { LocalSetupCheckpointCodecV1.decodeOpeningStockRow(write.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (record.binding != request.target.original || record.sourceId != request.sourceId ||
            record.receiptId != request.receiptId || record.openingOperationId != request.openingOperationId ||
            record.actorId != command.actorId || record.recordedAt != command.recordedAt ||
            record.operationId != command.operationId || record.policyRevision != command.policyRevision ||
            record.masterAuthorityEpoch != evidenceEpoch(command)) return false
        val effect = com.ukkera.pos.inventory.store.LocalStockOpeningEvidenceV1Source.observe(
            com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), request.openingOperationId,
            request.receiptId) ?: return false
        val line = effect.lines.singleOrNull { it.sourceId == request.sourceId } ?: return false
        if (effect.effectDigest != record.stockEffectDigest || effect.effectRevision != record.stockEffectRevision ||
            line.skuId != record.skuId || line.warehouseId != record.warehouseId || line.quantity != record.quantity ||
            line.originalValue != record.value || line.lotId != record.lotId || line.serialId != record.serialId) return false
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_CODE,
            installationId, rowId = rowId, rowCount = 1)
        command.requireRequirement(openingRequirement(command, installationId, Revision(0)))
        val resources = setupResources(command.actorId, installationId, setOf(
            RecoveryResourceKey(RecoveryResourceKind.STOCK, request.receiptId),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, record.skuId),
        ))
        command.requireLegacyEffects(ProposedOutcome.Apply(
            LocalSetupCheckpointCodecV1.encodeResult(result),
            listOf(AggregateChange(rowKey, null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningStockRow(record))),
            listOf(ClaimChange.Acquire(claim, rowId)),
        ), resources)
        return true
    }

    private suspend fun joinOpeningFloatRow(command: RuntimeHistoricalCommand,
        evidence: RetainedCommandEvidence): Boolean {
        val request = LocalSetupCheckpointCodecV1.decodeOpeningFloatRowRequest(command.normalized)
        if (!request.target.original.matchesHistorical(command)) return false
        val installationId = request.target.original.installationId
        val rowId = LocalSetupCheckpointProfileV1.rowId(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND,
            installationId, request.sourceId)
        val rowKey = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, rowId)
        val claim = LocalSetupCheckpointProfileV1.sourceClaim(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND,
            installationId, request.sourceId)
        if (command.aggregate(rowKey) != null || command.claim(claim) != null) return false
        val write = evidence.aggregateWrites.singleOrNull { it.key == rowKey } ?: return false
        if (write.schema != LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND)) return false
        val record = try { LocalSetupCheckpointCodecV1.decodeOpeningFloatRow(write.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (record.binding != request.target.original || record.sourceId != request.sourceId ||
            record.sessionId != request.sessionId || record.openingOperationId != request.openingOperationId ||
            record.actorId != command.actorId || record.recordedAt != command.recordedAt ||
            record.operationId != command.operationId || record.policyRevision != command.policyRevision ||
            record.masterAuthorityEpoch != evidenceEpoch(command)) return false
        val effect = cashOpeningEffect(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope,
            request.sessionId, request.sourceId, request.openingOperationId) ?: return false
        if (effect.amount != record.amount || effect.digest != record.cashEffectDigest ||
            effect.revision != record.cashEffectRevision) return false
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_CODE,
            installationId, rowId = rowId, rowCount = 1)
        command.requireRequirement(openingRequirement(command, installationId, Revision(0)))
        val resources = setupResources(command.actorId, installationId, setOf(
            RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, request.sessionId),
        ))
        command.requireLegacyEffects(ProposedOutcome.Apply(
            LocalSetupCheckpointCodecV1.encodeResult(result),
            listOf(AggregateChange(rowKey, null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningFloatRow(record))),
            listOf(ClaimChange.Acquire(claim, rowId)),
        ), resources)
        return true
    }

    private suspend fun joinOpeningCheckpoint(command: RuntimeHistoricalCommand,
        evidence: RetainedCommandEvidence): Boolean {
        val request = LocalSetupCheckpointCodecV1.decodeCompleteOpeningRequest(command.normalized)
        if (!request.target.original.matchesHistorical(command)) return false
        val installationId = request.target.original.installationId
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND, installationId)
        if (command.aggregate(key) != null) return false
        val actualStock = historicalStockRows(command, installationId) ?: return false
        val actualFloat = historicalFloatRows(command, installationId) ?: return false
        val verified = verifyHistoricalOpeningManifest(command, installationId, request, actualStock, actualFloat)
            ?: return false
        val stock = verified.first
        val float = verified.second
        val stockDigest = LocalSetupCheckpointCodecV1.stockManifestDigest(stock)
        val floatDigest = LocalSetupCheckpointCodecV1.floatManifestDigest(float)
        val record = LocalSetupOpeningCheckpointV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            stockChoice = request.stockChoice,
            stockRowCount = stock.size,
            stockValueTotal = stock.fold(Money(Currency.EGP, 0)) { total, row -> total + row.value },
            floatChoice = request.floatChoice,
            floatRowCount = float.size,
            floatTotal = float.fold(Money(Currency.EGP, 0)) { total, row -> total + row.amount },
            stockManifestDigest = stockDigest,
            stockInputManifestDigest = request.expectedStockManifestDigest,
            floatManifestDigest = floatDigest,
            floatInputManifestDigest = request.expectedFloatManifestDigest,
            actorId = command.actorId,
            recordedAt = command.recordedAt,
            operationId = command.operationId,
            policyRevision = command.policyRevision,
            masterAuthorityEpoch = evidenceEpoch(command),
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_SETTLED_CODE,
            installationId, rowCount = stock.size + float.size, manifestDigest = stockDigest ?: floatDigest)
        command.requireRequirement(openingRequirement(command, installationId, Revision(0)))
        val resources = setupResources(command.actorId, installationId,
            (actualStock.map { RecoveryResourceKey(RecoveryResourceKind.STOCK, it.second.receiptId) } +
                actualFloat.map { RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it.second.sessionId) }).toSet())
        command.requireLegacyEffects(ProposedOutcome.Apply(
            LocalSetupCheckpointCodecV1.encodeResult(result),
            listOf(AggregateChange(key, null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningCheckpoint(record))),
        ), resources)
        return true
    }

    private suspend fun historicalActivity(command: RuntimeHistoricalCommand): ActivitySnapshot? =
        loadActivity(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope, signatures)

    private suspend fun historicalCatalogRows(command: RuntimeHistoricalCommand,
        installationId: DomainId): List<Pair<AggregateKey, LocalSetupCatalogImportRowRecordV1>>? =
        scanCatalogRows(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope, installationId)

    private suspend fun historicalStockRows(command: RuntimeHistoricalCommand,
        installationId: DomainId): List<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>? =
        scanStockRows(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope, installationId)

    private suspend fun historicalFloatRows(command: RuntimeHistoricalCommand,
        installationId: DomainId): List<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>? =
        scanFloatRows(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope, installationId)

    private suspend fun verifyHistoricalCatalogManifest(command: RuntimeHistoricalCommand, installationId: DomainId,
        request: LocalSetupCatalogImportCompleteRequestV1,
        actual: List<Pair<AggregateKey, LocalSetupCatalogImportRowRecordV1>>): List<LocalSetupCatalogImportRowRefV1>? =
        verifyCatalogManifest(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope,
            installationId, request, actual)

    private suspend fun verifyHistoricalOpeningManifest(command: RuntimeHistoricalCommand, installationId: DomainId,
        request: LocalSetupOpeningCompleteRequestV1,
        actualStock: List<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>,
        actualFloat: List<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>):
        Pair<List<LocalSetupOpeningStockRowRefV1>, List<LocalSetupOpeningFloatRowRefV1>>? =
        verifyOpeningManifest(com.ukkera.pos.database.RuntimeHistoricalSetupReadView(command), command.scope,
            installationId, request, actualStock, actualFloat)

    private suspend fun LocalSetupOriginalBindingV1.matchesHistorical(command: RuntimeHistoricalCommand): Boolean =
        command.matchesOriginalSetup(installationId, originalRequestId, issuedAttemptId, attemptSequence,
            definitionDigest, genesisDigest, receiptDigest)

    private fun configurationRequirement(command: RuntimeHistoricalCommand, installationId: DomainId,
        revision: Revision): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.SETUP_CONFIGURATION_SETTLE, PermissionVerb.ADMINISTER), command.scope,
        ActionBinding(installationId, revision, command.semanticDigest, command.operationId,
            LocalSetupCheckpointProfileV1.CONFIGURE_REASON))

    private fun catalogRequirement(command: RuntimeHistoricalCommand, installationId: DomainId,
        revision: Revision): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.SETUP_CATALOG_IMPORT_SETTLE, PermissionVerb.ADMINISTER), command.scope,
        ActionBinding(installationId, revision, command.semanticDigest, command.operationId,
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_REASON))

    private fun openingRequirement(command: RuntimeHistoricalCommand, installationId: DomainId,
        revision: Revision): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.SETUP_OPENING_SETTLE, PermissionVerb.ADMINISTER), command.scope,
        ActionBinding(installationId, revision, command.semanticDigest, command.operationId,
            LocalSetupCheckpointProfileV1.OPENING_REASON))

    private fun setupResources(actorId: DomainId, installationId: DomainId,
        extra: Set<RecoveryResourceKey> = emptySet()): Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId),
    ) + extra

    private fun evidenceEpoch(command: RuntimeHistoricalCommand): Revision =
        ((command.original as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("Setup checkpoint original unavailable")).authorityEpoch
}
