package com.ukkera.pos.catalog.store.setup

import com.ukkera.pos.catalog.store.BranchActivityBodyV1
import com.ukkera.pos.catalog.store.BranchActivitySourceUnavailable
import com.ukkera.pos.catalog.store.ConfigurationBody
import com.ukkera.pos.catalog.store.LocalBranchActivityProfileV1
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationResultV1
import com.ukkera.pos.catalog.store.authenticBranchActivity
import com.ukkera.pos.catalog.store.branchActivityApplied
import com.ukkera.pos.catalog.store.branchActivityCommandResources
import com.ukkera.pos.catalog.store.branchActivityReference
import com.ukkera.pos.catalog.store.decodeBranchActivityRecord
import com.ukkera.pos.catalog.store.decodeRecord
import com.ukkera.pos.catalog.store.requireBranchActivityProducerShape
import com.ukkera.pos.catalog.store.requireBranchActivityStoredWrite
import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.MasterReadInvalidSelection
import com.ukkera.pos.database.MasterReadAuthorizationDenied
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.setup.LocalSetupGoLivePrerequisiteSource
import com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1
import com.ukkera.pos.database.setup.LocalSetupPrerequisiteObservationV1
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.inventory.store.LocalStockOpeningEvidenceLineV1
import com.ukkera.pos.inventory.store.LocalStockOpeningEvidenceV1
import com.ukkera.pos.inventory.store.LocalStockOpeningEvidenceV1Source
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalSalesAction
import com.ukkera.pos.sales.store.LocalSalesResultCodec
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.cancellation.CancellationException

/** Installed production checkpoint commands and protected read. Registration grants nothing. */
public class LocalSetupCheckpointServices(
    private val signatures: CompanyPublicationSignatureVerifier,
) {
    private val commandValues: List<MasterCommandHandler> = listOf(
        LocalSetupConfigurationHandler(signatures),
        LocalSetupCatalogImportRowHandler(),
        LocalSetupCompleteCatalogImportHandler(),
        LocalSetupOpeningStockRowHandler(),
        LocalSetupOpeningFloatRowHandler(),
        LocalSetupCompleteOpeningHandler(),
    )
    private val readValues: List<MasterReadHandler> = listOf(LocalSetupCheckpointReadHandler(), LocalSetupCheckpointRowsReadHandler())

    public val commands: List<MasterCommandHandler> get() = commandValues.toList()
    public val reads: List<MasterReadHandler> get() = readValues.toList()
    public val prerequisites: LocalSetupGoLivePrerequisiteSource =
        LocalProductionSetupGoLivePrerequisites(signatures)
}

internal data class CatalogEffectV1(
    val publicationId: DomainId,
    val digest: PayloadDigest,
    val revision: Revision,
    val actorId: DomainId,
)

internal data class CashOpeningEffectV1(
    val amount: Money,
    val digest: PayloadDigest,
    val revision: Revision,
    val actorId: DomainId,
)

internal suspend fun catalogEffect(view: CommandReadView, scope: BranchScope, skuId: DomainId,
    operationId: OperationId): CatalogEffectV1? {
    val evidence = view.retainedCommand(operationId) ?: return null
    if (evidence.scope != scope) return null
    val effect = catalogEffectFromCommand(evidence, scope, skuId) ?: return null
    val key = AggregateKey(LocalConfigurationProfile.SKU, skuId)
    val current = view.aggregate(key) ?: return null
    val record = try { decodeRecord(scope, current) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    if (record.scope != scope || record.id != skuId) return null
    return effect
}

internal fun catalogEffectFromCommand(evidence: RetainedCommandEvidence, scope: BranchScope,
    skuId: DomainId): CatalogEffectV1? {
    if (!evidence.committed || evidence.nativeOriginalProvenance != null || evidence.scope != scope ||
        evidence.command != LocalConfigurationProfile.COMMAND) return null
    val result = try { LocalConfigurationCodec.decodeResult(evidence.result) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    if (result.code != "configuration.applied" || result.id != skuId) return null
    val key = AggregateKey(LocalConfigurationProfile.SKU, skuId)
    val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: return null
    val record = try {
        decodeRecord(scope, key, write.storageGeneration, write.schema, write.payload)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    val sku = (record.body as? ConfigurationBody.Sku)?.value ?: return null
    if (record.scope != scope || record.id != skuId || sku.definition.skuId != skuId ||
        sku.definition.company != scope.company() || !sku.definition.active) return null
    return CatalogEffectV1(sku.definition.publicationId, write.payloadDigest, write.storageGeneration,
        evidence.originalActorId)
}

internal suspend fun cashOpeningEffect(view: CommandReadView, scope: BranchScope, sessionId: DomainId,
    sourceId: DomainId, operationId: OperationId): CashOpeningEffectV1? {
    val evidence = view.retainedCommand(operationId) ?: return null
    if (evidence.scope != scope) return null
    val effect = cashOpeningEffectFromCommand(evidence, scope, sessionId, sourceId) ?: return null
    val key = CashNonSaleStoreProfile.sessionKey(sessionId)
    val current = view.aggregate(key) ?: return null
    // The opening operation created this session; later ordinary cash movements may update it.
    // The durable opening-source claim and the retained opening command/result stay authoritative.
    if (current.schema != CashNonSaleStoreProfile.SESSION_SCHEMA) return null
    if (view.claim(LocalSalesStoreProfile.unique("opening_source", sourceId)) != sessionId) return null
    return effect
}

internal fun cashOpeningEffectFromCommand(evidence: RetainedCommandEvidence, scope: BranchScope,
    sessionId: DomainId, sourceId: DomainId): CashOpeningEffectV1? {
    if (!evidence.committed || evidence.nativeOriginalProvenance != null || evidence.scope != scope ||
        evidence.command != LocalSalesStoreProfile.CASH_COMMAND) return null
    val request = try { LocalCashCommandCodec.decode(evidence.normalized) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    } as? LocalCashRequestV1.Open ?: return null
    if (request.sessionId != sessionId || request.openingSourceId != sourceId) return null
    val result = try { LocalSalesResultCodec.decode(evidence.result) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    } as? LocalSalesResultV1.Applied ?: return null
    if (result.action != LocalSalesAction.CASH_OPENED || result.documentId != sessionId ||
        result.amount != request.opening) return null
    val key = CashNonSaleStoreProfile.sessionKey(sessionId)
    val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: return null
    if (write.schema != CashNonSaleStoreProfile.SESSION_SCHEMA) return null
    return CashOpeningEffectV1(request.opening, write.payloadDigest, write.storageGeneration,
        evidence.originalActorId)
}

internal data class ActivitySnapshot(
    val activities: List<com.ukkera.pos.sales.store.LocalSalesActivity>,
    val operationId: OperationId,
    val reference: com.ukkera.pos.catalog.store.LocalBranchActivityReferenceV1,
    val resources: Set<RecoveryResourceKey>,
)

internal suspend fun loadActivity(view: CommandReadView, scope: BranchScope,
    signatures: CompanyPublicationSignatureVerifier): ActivitySnapshot? = try {
    val activeKey = AggregateKey(LocalBranchActivityProfileV1.ACTIVE, scope.branchId)
    val activeRow = view.aggregate(activeKey) ?: return null
    val active = decodeBranchActivityRecord(scope, activeRow)
    val activeOriginal = view.retainedCommand(active.producer.operationId) ?: return null
    requireBranchActivityStoredWrite(activeRow, active, activeOriginal)
    val reference = (active.body as? BranchActivityBodyV1.Active)?.reference ?: return null
    val publicationKey = AggregateKey(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
    val publicationRow = view.aggregate(publicationKey) ?: return null
    val publication = decodeBranchActivityRecord(scope, publicationRow)
    val publicationOriginal = view.retainedCommand(publication.producer.operationId) ?: return null
    requireBranchActivityStoredWrite(publicationRow, publication, publicationOriginal)
    val signed = requireBranchActivityProducerShape(publicationOriginal)
    if (!authenticBranchActivity(signatures, scope, signed)) return null
    if (branchActivityReference(signed) != reference || active.permissionPolicyRevision != publication.permissionPolicyRevision ||
        active.recordedAt != publication.recordedAt || active.producer != publication.producer) return null
    val request = (publication.body as? BranchActivityBodyV1.Publication)?.request ?: return null
    val applied = branchActivityApplied(scope, reference, request, active.producer.operationId,
        active.producer.actorId, active.permissionPolicyRevision, active.recordedAt)
    ActivitySnapshot(applied.activities.sortedBy { it.ordinal }, applied.operationId, applied.reference,
        branchActivityCommandResources(scope, active.producer.actorId, active.producer.operationId, reference))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: BranchActivitySourceUnavailable) {
    null
} catch (_: Exception) {
    null
}

private fun setupResources(actorId: DomainId, installationId: DomainId,
    extra: Set<RecoveryResourceKey> = emptySet()): Set<RecoveryResourceKey> = setOf(
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actorId),
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId),
) + extra

private fun configurationRequirement(context: CommandContext, installationId: DomainId,
    revision: Revision): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.SETUP_CONFIGURATION_SETTLE, PermissionVerb.ADMINISTER), context.scope,
    ActionBinding(installationId, revision, context.semanticDigest, context.operationId,
        LocalSetupCheckpointProfileV1.CONFIGURE_REASON))

private fun catalogRequirement(context: CommandContext, installationId: DomainId,
    revision: Revision): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.SETUP_CATALOG_IMPORT_SETTLE, PermissionVerb.ADMINISTER), context.scope,
    ActionBinding(installationId, revision, context.semanticDigest, context.operationId,
        LocalSetupCheckpointProfileV1.CATALOG_IMPORT_REASON))

private fun openingRequirement(context: CommandContext, installationId: DomainId,
    revision: Revision): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.SETUP_OPENING_SETTLE, PermissionVerb.ADMINISTER), context.scope,
    ActionBinding(installationId, revision, context.semanticDigest, context.operationId,
        LocalSetupCheckpointProfileV1.OPENING_REASON))

private class ConfigPrepared(
    private val context: CommandContext,
    private val request: LocalSetupConfigurationRequestV1,
    private val original: SavedOriginalSetupV1?,
    private val existing: LocalSetupConfigurationRecordV1?,
    private val activity: ActivitySnapshot?,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val unavailable: Boolean,
) : PreparedMutation() {
    override val admissionRestriction: PreparedMutationAdmissionRestriction?
        get() = if (unavailable || original == null || activity == null) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean get() = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        val actual = checkNotNull(original) { "Setup configuration original unavailable" }
        val applied = checkNotNull(activity) { "Applied branch activity unavailable" }
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        val revision = existing?.revision?.next() ?: Revision(1)
        val record = LocalSetupConfigurationRecordV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            revision = revision,
            activities = applied.activities,
            currency = request.currency,
            timeZone = request.timeZone,
            businessDayCutoffMinute = request.businessDayCutoffMinute,
            fiscalIdentity = request.fiscalIdentity,
            branchActivityOperationId = applied.operationId,
            branchActivityReference = applied.reference,
            actorId = authorization.actorId,
            recordedAt = recordedAt,
            operationId = context.operationId,
            policyRevision = authorization.policyRevision,
            masterAuthorityEpoch = actual.currentAuthorityEpoch,
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CONFIGURED_CODE, actual.installationId)
        return ProposedOutcome.Apply(
            result = LocalSetupCheckpointCodecV1.encodeResult(result),
            changes = listOf(AggregateChange(
                LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND, actual.installationId),
                existing?.revision,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND),
                LocalSetupCheckpointCodecV1.encodeConfigurationRecord(record),
            )),
        )
    }
}

private class LocalSetupConfigurationHandler(
    private val signatures: CompanyPublicationSignatureVerifier,
) : MasterCommandHandler {
    override val key: CommandKey get() = LocalSetupCheckpointProfileV1.CONFIGURE
    override val writableKinds: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND)
    override val claimNamespaces: Set<String> get() = emptySet()

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeConfigurationRequest(
            LocalSetupCheckpointCodecV1.decodeConfigurationRequest(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupCheckpointCodecV1.decodeConfigurationRequest(normalized)
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND,
            request.target.original.installationId)
        val original = view.savedOriginalSetup()
        val existingRow = view.aggregate(key)
        val existing = existingRow?.let {
            check(it.schema == LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND)) {
                "Invalid setup configuration storage profile"
            }
            LocalSetupCheckpointCodecV1.decodeConfigurationRecord(it.payload)
        }
        val requirement = configurationRequirement(context, request.target.original.installationId,
            existing?.revision ?: Revision(0))
        val activity = if (original == null || !request.target.original.matches(original) ||
            existing?.binding?.let { it != request.target.original } == true ||
            request.expectedRevision != (existing?.revision ?: Revision(0))) null
            else loadActivity(view, context.scope, signatures)
        val resources = setupResources(context.originalActorId, request.target.original.installationId,
            activity?.resources.orEmpty())
        return ConfigPrepared(context, request, original, existing, activity, requirement, resources,
            unavailable = activity == null || original == null || !request.target.original.matches(original) ||
                existing?.binding?.let { it != request.target.original } == true)
    }
}

private class CatalogRowPrepared(
    private val context: CommandContext,
    private val request: LocalSetupCatalogImportRowRequestV1,
    private val original: SavedOriginalSetupV1?,
    private val effect: CatalogEffectV1?,
    private val rowId: DomainId,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val unavailable: Boolean,
) : PreparedMutation() {
    override val admissionRestriction: PreparedMutationAdmissionRestriction?
        get() = if (unavailable || original == null || effect == null) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean get() = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        val actual = checkNotNull(original) { "Setup catalog-import original unavailable" }
        val verified = checkNotNull(effect) { "Catalog import effect unavailable" }
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        val record = LocalSetupCatalogImportRowRecordV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            sourceId = request.sourceId,
            skuId = request.skuId,
            catalogOperationId = request.catalogOperationId,
            catalogPublicationId = verified.publicationId,
            catalogEffectDigest = verified.digest,
            catalogEffectRevision = verified.revision,
            actorId = authorization.actorId,
            recordedAt = recordedAt,
            operationId = context.operationId,
            policyRevision = authorization.policyRevision,
            masterAuthorityEpoch = actual.currentAuthorityEpoch,
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CATALOG_ROW_CODE,
            actual.installationId, rowId = rowId, rowCount = 1)
        return ProposedOutcome.Apply(
            result = LocalSetupCheckpointCodecV1.encodeResult(result),
            changes = listOf(AggregateChange(
                LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, rowId),
                null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND),
                LocalSetupCheckpointCodecV1.encodeCatalogImportRow(record),
            )),
            claims = listOf(ClaimChange.Acquire(LocalSetupCheckpointProfileV1.sourceClaim(
                LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, actual.installationId, request.sourceId), rowId),
                ClaimChange.Acquire(LocalSetupCheckpointProfileV1.catalogOperationClaim(
                    actual.installationId, request.catalogOperationId), rowId)),
        )
    }
}

private class LocalSetupCatalogImportRowHandler : MasterCommandHandler {
    override val key: CommandKey get() = LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW
    override val writableKinds: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND)
    override val claimNamespaces: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.SOURCE_CLAIM_NAMESPACE)

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeCatalogImportRowRequest(
            LocalSetupCheckpointCodecV1.decodeCatalogImportRowRequest(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupCheckpointCodecV1.decodeCatalogImportRowRequest(normalized)
        val original = view.savedOriginalSetup()
        val rowId = LocalSetupCheckpointProfileV1.rowId(
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, request.target.original.installationId, request.sourceId)
        val rowKey = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, rowId)
        val claimKey = LocalSetupCheckpointProfileV1.sourceClaim(
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, request.target.original.installationId, request.sourceId)
        val occupied = view.aggregate(rowKey) != null || view.claim(claimKey) != null ||
            view.claim(LocalSetupCheckpointProfileV1.catalogOperationClaim(
                request.target.original.installationId, request.catalogOperationId)) != null
        val effect = if (original == null || !request.target.original.matches(original) || occupied) null
            else catalogEffect(view, context.scope, request.skuId, request.catalogOperationId)
        val requirement = catalogRequirement(context, request.target.original.installationId, Revision(0))
        val resources = setupResources(context.originalActorId, request.target.original.installationId, setOf(
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.skuId),
        ))
        return CatalogRowPrepared(context, request, original, effect, rowId, requirement, resources,
            unavailable = occupied || original == null || !request.target.original.matches(original))
    }
}

private class CompleteCatalogImportPrepared(
    private val context: CommandContext,
    private val request: LocalSetupCatalogImportCompleteRequestV1,
    private val original: SavedOriginalSetupV1?,
    private val rows: List<LocalSetupCatalogImportRowRefV1>?,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val unavailable: Boolean,
) : PreparedMutation() {
    override val admissionRestriction: PreparedMutationAdmissionRestriction?
        get() = if (unavailable || original == null || rows == null) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean get() = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        val actual = checkNotNull(original) { "Setup catalog-import original unavailable" }
        val verifiedRows = checkNotNull(rows) { "Catalog import rows unavailable" }
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        val digest = LocalSetupCheckpointCodecV1.catalogManifestDigest(verifiedRows)
        val record = LocalSetupCatalogImportCheckpointV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            mode = request.mode,
            rowCount = verifiedRows.size,
            manifestDigest = digest,
            inputManifestDigest = request.expectedManifestDigest,
            actorId = authorization.actorId,
            recordedAt = recordedAt,
            operationId = context.operationId,
            policyRevision = authorization.policyRevision,
            masterAuthorityEpoch = actual.currentAuthorityEpoch,
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CATALOG_SETTLED_CODE,
            actual.installationId, rowCount = verifiedRows.size, manifestDigest = digest)
        return ProposedOutcome.Apply(
            result = LocalSetupCheckpointCodecV1.encodeResult(result),
            changes = listOf(AggregateChange(
                LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND, actual.installationId),
                null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND),
                LocalSetupCheckpointCodecV1.encodeCatalogImportCheckpoint(record),
            )),
        )
    }
}

private class LocalSetupCompleteCatalogImportHandler : MasterCommandHandler {
    override val key: CommandKey get() = LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT
    override val writableKinds: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND)
    override val claimNamespaces: Set<String> get() = emptySet()

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeCompleteCatalogImportRequest(
            LocalSetupCheckpointCodecV1.decodeCompleteCatalogImportRequest(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupCheckpointCodecV1.decodeCompleteCatalogImportRequest(normalized)
        val installationId = request.target.original.installationId
        val checkpointKey = LocalSetupCheckpointProfileV1.key(
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND, installationId)
        val original = view.savedOriginalSetup()
        val existing = view.aggregate(checkpointKey)
        val actualRows = if (original == null || !request.target.original.matches(original) || existing != null) null
            else scanCatalogRows(view, context.scope, installationId)
        val verified = if (actualRows == null) null else verifyCatalogManifest(view, context.scope,
            installationId, request, actualRows)
        val requirement = catalogRequirement(context, installationId, Revision(0))
        val resources = setupResources(context.originalActorId, installationId,
            actualRows.orEmpty().flatMap { (_, row) ->
                setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.skuId))
            }.toSet())
        return CompleteCatalogImportPrepared(context, request, original, verified, requirement, resources,
            unavailable = existing != null || original == null || !request.target.original.matches(original) || verified == null)
    }
}

private class OpeningStockRowPrepared(
    private val context: CommandContext,
    private val request: LocalSetupOpeningStockRowRequestV1,
    private val original: SavedOriginalSetupV1?,
    private val effect: LocalStockOpeningEvidenceV1?,
    private val line: LocalStockOpeningEvidenceLineV1?,
    private val rowId: DomainId,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val unavailable: Boolean,
) : PreparedMutation() {
    override val admissionRestriction: PreparedMutationAdmissionRestriction?
        get() = if (unavailable || original == null || effect == null || line == null) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean get() = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        val actual = checkNotNull(original) { "Setup opening original unavailable" }
        val verified = checkNotNull(effect) { "Opening stock effect unavailable" }
        val verifiedLine = checkNotNull(line) { "Opening stock line unavailable" }
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        val record = LocalSetupOpeningStockRowRecordV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            sourceId = verifiedLine.sourceId,
            receiptId = request.receiptId,
            openingOperationId = request.openingOperationId,
            skuId = verifiedLine.skuId,
            warehouseId = verifiedLine.warehouseId,
            quantity = verifiedLine.quantity,
            value = verifiedLine.originalValue,
            lotId = verifiedLine.lotId,
            serialId = verifiedLine.serialId,
            stockEffectDigest = verified.effectDigest,
            stockEffectRevision = verified.effectRevision,
            actorId = authorization.actorId,
            recordedAt = recordedAt,
            operationId = context.operationId,
            policyRevision = authorization.policyRevision,
            masterAuthorityEpoch = actual.currentAuthorityEpoch,
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_CODE,
            actual.installationId, rowId = rowId, rowCount = 1)
        return ProposedOutcome.Apply(
            result = LocalSetupCheckpointCodecV1.encodeResult(result),
            changes = listOf(AggregateChange(
                LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, rowId),
                null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningStockRow(record),
            )),
            claims = listOf(ClaimChange.Acquire(LocalSetupCheckpointProfileV1.sourceClaim(
                LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, actual.installationId, verifiedLine.sourceId), rowId)),
        )
    }
}

private class LocalSetupOpeningStockRowHandler : MasterCommandHandler {
    override val key: CommandKey get() = LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW
    override val writableKinds: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND)
    override val claimNamespaces: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.SOURCE_CLAIM_NAMESPACE)

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeOpeningStockRowRequest(
            LocalSetupCheckpointCodecV1.decodeOpeningStockRowRequest(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupCheckpointCodecV1.decodeOpeningStockRowRequest(normalized)
        val installationId = request.target.original.installationId
        val original = view.savedOriginalSetup()
        val rowId = LocalSetupCheckpointProfileV1.rowId(
            LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, installationId, request.sourceId)
        val rowKey = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, rowId)
        val claimKey = LocalSetupCheckpointProfileV1.sourceClaim(
            LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, installationId, request.sourceId)
        val occupied = view.aggregate(rowKey) != null || view.claim(claimKey) != null
        val effect = if (original == null || !request.target.original.matches(original) || occupied) null
            else LocalStockOpeningEvidenceV1Source.observe(view, request.openingOperationId, request.receiptId)
        val line = effect?.lines?.singleOrNull { it.sourceId == request.sourceId }
        val requirement = openingRequirement(context, installationId, Revision(0))
        val resources = setupResources(context.originalActorId, installationId, setOfNotNull(
            RecoveryResourceKey(RecoveryResourceKind.STOCK, request.receiptId),
            line?.let { RecoveryResourceKey(RecoveryResourceKind.STOCK, it.skuId) },
        ))
        return OpeningStockRowPrepared(context, request, original, effect, line, rowId, requirement, resources,
            unavailable = occupied || original == null || !request.target.original.matches(original) || effect == null || line == null)
    }
}

private class OpeningFloatRowPrepared(
    private val context: CommandContext,
    private val request: LocalSetupOpeningFloatRowRequestV1,
    private val original: SavedOriginalSetupV1?,
    private val effect: CashOpeningEffectV1?,
    private val rowId: DomainId,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val unavailable: Boolean,
) : PreparedMutation() {
    override val admissionRestriction: PreparedMutationAdmissionRestriction?
        get() = if (unavailable || original == null || effect == null) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean get() = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        val actual = checkNotNull(original) { "Setup opening original unavailable" }
        val verified = checkNotNull(effect) { "Opening float effect unavailable" }
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        val record = LocalSetupOpeningFloatRowRecordV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            sourceId = request.sourceId,
            sessionId = request.sessionId,
            openingOperationId = request.openingOperationId,
            amount = verified.amount,
            cashEffectDigest = verified.digest,
            cashEffectRevision = verified.revision,
            actorId = authorization.actorId,
            recordedAt = recordedAt,
            operationId = context.operationId,
            policyRevision = authorization.policyRevision,
            masterAuthorityEpoch = actual.currentAuthorityEpoch,
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_CODE,
            actual.installationId, rowId = rowId, rowCount = 1)
        return ProposedOutcome.Apply(
            result = LocalSetupCheckpointCodecV1.encodeResult(result),
            changes = listOf(AggregateChange(
                LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, rowId),
                null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningFloatRow(record),
            )),
            claims = listOf(ClaimChange.Acquire(LocalSetupCheckpointProfileV1.sourceClaim(
                LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, actual.installationId, request.sourceId), rowId)),
        )
    }
}

private class LocalSetupOpeningFloatRowHandler : MasterCommandHandler {
    override val key: CommandKey get() = LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW
    override val writableKinds: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND)
    override val claimNamespaces: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.SOURCE_CLAIM_NAMESPACE)

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeOpeningFloatRowRequest(
            LocalSetupCheckpointCodecV1.decodeOpeningFloatRowRequest(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupCheckpointCodecV1.decodeOpeningFloatRowRequest(normalized)
        val installationId = request.target.original.installationId
        val original = view.savedOriginalSetup()
        val rowId = LocalSetupCheckpointProfileV1.rowId(
            LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, installationId, request.sourceId)
        val rowKey = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, rowId)
        val claimKey = LocalSetupCheckpointProfileV1.sourceClaim(
            LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, installationId, request.sourceId)
        val occupied = view.aggregate(rowKey) != null || view.claim(claimKey) != null
        val effect = if (original == null || !request.target.original.matches(original) || occupied) null
            else cashOpeningEffect(view, context.scope, request.sessionId, request.sourceId, request.openingOperationId)
        val requirement = openingRequirement(context, installationId, Revision(0))
        val resources = setupResources(context.originalActorId, installationId, setOf(
            RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, request.sessionId),
        ))
        return OpeningFloatRowPrepared(context, request, original, effect, rowId, requirement, resources,
            unavailable = occupied || original == null || !request.target.original.matches(original) || effect == null)
    }
}

private class CompleteOpeningPrepared(
    private val context: CommandContext,
    private val request: LocalSetupOpeningCompleteRequestV1,
    private val original: SavedOriginalSetupV1?,
    private val stockRows: List<LocalSetupOpeningStockRowRefV1>?,
    private val floatRows: List<LocalSetupOpeningFloatRowRefV1>?,
    override val requirement: AuthorizationRequirement,
    override val affectedResources: Set<RecoveryResourceKey>,
    private val unavailable: Boolean,
) : PreparedMutation() {
    override val admissionRestriction: PreparedMutationAdmissionRestriction?
        get() = if (unavailable || original == null || stockRows == null || floatRows == null)
            PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
        setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
    override val allowsActionApproval: Boolean get() = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        val actual = checkNotNull(original) { "Setup opening original unavailable" }
        val stock = checkNotNull(stockRows) { "Opening stock rows unavailable" }
        val float = checkNotNull(floatRows) { "Opening float rows unavailable" }
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        val stockTotal = stock.fold(Money(Currency.EGP, 0)) { total, row -> total + row.value }
        val floatTotal = float.fold(Money(Currency.EGP, 0)) { total, row -> total + row.amount }
        val stockDigest = LocalSetupCheckpointCodecV1.stockManifestDigest(stock)
        val floatDigest = LocalSetupCheckpointCodecV1.floatManifestDigest(float)
        val record = LocalSetupOpeningCheckpointV1(
            binding = request.target.original,
            environment = LocalSetupEnvironmentV1.LIVE,
            stockChoice = request.stockChoice,
            stockRowCount = stock.size,
            stockValueTotal = stockTotal,
            floatChoice = request.floatChoice,
            floatRowCount = float.size,
            floatTotal = floatTotal,
            stockManifestDigest = stockDigest,
            stockInputManifestDigest = request.expectedStockManifestDigest,
            floatManifestDigest = floatDigest,
            floatInputManifestDigest = request.expectedFloatManifestDigest,
            actorId = authorization.actorId,
            recordedAt = recordedAt,
            operationId = context.operationId,
            policyRevision = authorization.policyRevision,
            masterAuthorityEpoch = actual.currentAuthorityEpoch,
        )
        val result = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_SETTLED_CODE,
            actual.installationId, rowCount = stock.size + float.size,
            manifestDigest = stockDigest ?: floatDigest)
        return ProposedOutcome.Apply(
            result = LocalSetupCheckpointCodecV1.encodeResult(result),
            changes = listOf(AggregateChange(
                LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND, actual.installationId),
                null,
                LocalSetupCheckpointProfileV1.schema(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND),
                LocalSetupCheckpointCodecV1.encodeOpeningCheckpoint(record),
            )),
        )
    }
}

private class LocalSetupCompleteOpeningHandler : MasterCommandHandler {
    override val key: CommandKey get() = LocalSetupCheckpointProfileV1.COMPLETE_OPENING
    override val writableKinds: Set<String> get() = setOf(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND)
    override val claimNamespaces: Set<String> get() = emptySet()

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeCompleteOpeningRequest(
            LocalSetupCheckpointCodecV1.decodeCompleteOpeningRequest(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupCheckpointCodecV1.decodeCompleteOpeningRequest(normalized)
        val installationId = request.target.original.installationId
        val checkpointKey = LocalSetupCheckpointProfileV1.key(
            LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND, installationId)
        val original = view.savedOriginalSetup()
        val existing = view.aggregate(checkpointKey)
        val scan = if (original == null || !request.target.original.matches(original) || existing != null) null
            else OpeningScan(
                stock = scanStockRows(view, context.scope, installationId),
                float = scanFloatRows(view, context.scope, installationId),
            )
        val verified = scan?.let { verifyOpeningManifest(view, context.scope, installationId, request, it.stock, it.float) }
        val requirement = openingRequirement(context, installationId, Revision(0))
        val resources = setupResources(context.originalActorId, installationId,
            (scan?.stock.orEmpty().map {
                RecoveryResourceKey(RecoveryResourceKind.STOCK, it.second.receiptId)
            } + scan?.float.orEmpty().map {
                RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it.second.sessionId)
            }).toSet())
        return CompleteOpeningPrepared(context, request, original, verified?.first, verified?.second,
            requirement, resources,
            unavailable = existing != null || original == null || !request.target.original.matches(original) || verified == null)
    }
}

private class LocalSetupCheckpointReadHandler : MasterReadHandler {
    override val key: ReadKey get() = LocalSetupCheckpointProfileV1.READ

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointCodecV1.encodeQuery(LocalSetupCheckpointCodecV1.decodeQuery(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: ReadContext): PreparedRead {
        val query = LocalSetupCheckpointCodecV1.decodeQuery(normalized)
        val original = view.savedOriginalSetup() ?: throw MasterReadAuthorizationDenied()
        if (query.installationId != original.installationId) throw MasterReadAuthorizationDenied()
        fun <T> decode(kind: String, binding: (T) -> LocalSetupOriginalBindingV1, row: StoredAggregate?,
            decoder: (StoredBytes) -> T): T? {
            if (row == null) return null
            if (row.schema != LocalSetupCheckpointProfileV1.schema(kind)) throw MasterReadAuthorizationDenied()
            val value = decoder(row.payload)
            if (binding(value) != LocalSetupOriginalBindingV1.of(original)) throw MasterReadAuthorizationDenied()
            return value
        }
        val configuration = decode(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND, { it.binding },
            view.aggregate(LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND, original.installationId)),
            LocalSetupCheckpointCodecV1::decodeConfigurationRecord)
        val catalogImport = decode(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND, { it.binding },
            view.aggregate(LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND, original.installationId)),
            LocalSetupCheckpointCodecV1::decodeCatalogImportCheckpoint)
        val opening = decode(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND, { it.binding },
            view.aggregate(LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND, original.installationId)),
            LocalSetupCheckpointCodecV1::decodeOpeningCheckpoint)
        val requirement = AuthorizationRequirement(
            Permission(SecurityAction.SETUP_CHECKPOINTS_READ, PermissionVerb.PERFORM), context.scope,
            ActionBinding(original.installationId, Revision(0), context.semanticDigest, null,
                LocalSetupCheckpointProfileV1.READ_REASON),
        )
        val projection = LocalSetupCheckpointProjectionV1(configuration, catalogImport, opening)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> get() = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                val authorization = authorizations.single()
                check(authorization.isBoundTo(requirement)) { "Setup checkpoint read authorization mismatch" }
                return LocalSetupCheckpointCodecV1.encodeProjection(projection)
            }
        }
    }
}

private data class OpeningScan(
    val stock: List<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>?,
    val float: List<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>?,
)

internal suspend fun scanCatalogRows(view: CommandReadView, scope: BranchScope,
    installationId: DomainId): List<Pair<AggregateKey, LocalSetupCatalogImportRowRecordV1>>? {
    val rows = scanKeys(view, LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, installationId) ?: return null
    val result = mutableListOf<Pair<AggregateKey, LocalSetupCatalogImportRowRecordV1>>()
    for (key in rows) {
        val row = view.aggregate(key) ?: return null
        if (row.schema != LocalSetupCheckpointProfileV1.schema(key.kind)) return null
        val value = try { LocalSetupCheckpointCodecV1.decodeCatalogImportRow(row.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (value.binding.installationId != installationId ||
            !verifyCheckpointOperation(view, value.operationId, LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW, key,
                value.actorId, value.recordedAt, value.policyRevision, value.masterAuthorityEpoch,
                LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CATALOG_ROW_CODE, installationId,
                    rowId = key.id, rowCount = 1), LocalSetupCheckpointCodecV1.encodeCatalogImportRow(value))) return null
        result += key to value
    }
    return result.sortedBy { it.second.sourceId.value }
}

internal suspend fun scanStockRows(view: CommandReadView, scope: BranchScope,
    installationId: DomainId): List<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>? {
    val rows = scanKeys(view, LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, installationId) ?: return null
    val result = mutableListOf<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>()
    for (key in rows) {
        val row = view.aggregate(key) ?: return null
        if (row.schema != LocalSetupCheckpointProfileV1.schema(key.kind)) return null
        val value = try { LocalSetupCheckpointCodecV1.decodeOpeningStockRow(row.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (value.binding.installationId != installationId ||
            !verifyCheckpointOperation(view, value.operationId, LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW, key,
                value.actorId, value.recordedAt, value.policyRevision, value.masterAuthorityEpoch,
                LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_CODE, installationId,
                    rowId = key.id, rowCount = 1), LocalSetupCheckpointCodecV1.encodeOpeningStockRow(value))) return null
        result += key to value
    }
    return result.sortedBy { it.second.sourceId.value }
}

internal suspend fun scanFloatRows(view: CommandReadView, scope: BranchScope,
    installationId: DomainId): List<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>? {
    val rows = scanKeys(view, LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, installationId) ?: return null
    val result = mutableListOf<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>()
    for (key in rows) {
        val row = view.aggregate(key) ?: return null
        if (row.schema != LocalSetupCheckpointProfileV1.schema(key.kind)) return null
        val value = try { LocalSetupCheckpointCodecV1.decodeOpeningFloatRow(row.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (value.binding.installationId != installationId ||
            !verifyCheckpointOperation(view, value.operationId, LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW, key,
                value.actorId, value.recordedAt, value.policyRevision, value.masterAuthorityEpoch,
                LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_CODE, installationId,
                    rowId = key.id, rowCount = 1), LocalSetupCheckpointCodecV1.encodeOpeningFloatRow(value))) return null
        result += key to value
    }
    return result.sortedBy { it.second.sourceId.value }
}

private fun catalogRefs(actual: List<Pair<AggregateKey, LocalSetupCatalogImportRowRecordV1>>):
    List<LocalSetupCatalogImportRowRefV1> = actual.map { (key, row) ->
    LocalSetupCatalogImportRowRefV1(row.sourceId, row.skuId, key.id, row.catalogOperationId,
        row.catalogEffectDigest, row.catalogEffectRevision)
}.sortedBy { it.sourceId.value }

private fun stockRefs(actual: List<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>):
    List<LocalSetupOpeningStockRowRefV1> = actual.map { (key, row) ->
    LocalSetupOpeningStockRowRefV1(row.sourceId, row.receiptId, key.id, row.openingOperationId,
        row.skuId, row.warehouseId, row.quantity, row.value, row.stockEffectDigest, row.stockEffectRevision)
}.sortedBy { it.sourceId.value }

private fun floatRefs(actual: List<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>):
    List<LocalSetupOpeningFloatRowRefV1> = actual.map { (key, row) ->
    LocalSetupOpeningFloatRowRefV1(row.sourceId, row.sessionId, key.id, row.openingOperationId,
        row.amount, row.cashEffectDigest, row.cashEffectRevision)
}.sortedBy { it.sourceId.value }

private suspend fun scanKeys(view: CommandReadView, kind: String, installationId: DomainId): List<AggregateKey>? {
    val prefix = "$kind/${installationId.value}/"
    val result = mutableListOf<AggregateKey>()
    var after: String? = null
    do {
        val page = try { view.claimKeys(LocalSetupCheckpointProfileV1.SOURCE_CLAIM_NAMESPACE, prefix, after, 100) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        for (entry in page.entries) {
            if (!entry.key.key.startsWith(prefix)) return null
            val sourceId = try { DomainId(entry.key.key.removePrefix(prefix)) } catch (_: Exception) { return null }
            if (entry.ownerId != LocalSetupCheckpointProfileV1.rowId(kind, installationId, sourceId)) return null
            result += AggregateKey(kind, entry.ownerId)
        }
        if (result.size > LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS || result.distinct().size != result.size) return null
        val next = page.nextAfter
        if (next != null && (next == after || !next.startsWith(prefix))) return null
        after = next
    } while (after != null)
    // Claims locate rows efficiently, but cannot prove that a row with a lost claim is
    // absent. Cross-check complete aggregate membership at this same writer/BEFORE cut.
    // Stream foreign installations without imposing a cumulative legacy-row capacity.
    if (!confirmCheckpointRowMembership(view, kind, installationId, result.toSet())) return null
    return result
}

private suspend fun confirmCheckpointRowMembership(view: CommandReadView, kind: String,
    installationId: DomainId, indexed: Set<AggregateKey>): Boolean {
    return try {
        val observed = mutableSetOf<AggregateKey>()
        var after: DomainId? = null
        do {
            val page = view.aggregateKeys(kind, after, 100)
            if (page.keys.size > 100 || page.keys.any { it.kind != kind || after != null && it.id.value <= after!!.value } ||
                !page.keys.zipWithNext().all { (a, b) -> a.id.value < b.id.value }) return false
            for (key in page.keys) {
                val stored = view.aggregate(key) ?: return false
                if (stored.key != key || stored.schema != LocalSetupCheckpointProfileV1.schema(kind)) return false
                val (rowInstallation, sourceId) = when (kind) {
                    LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND ->
                        LocalSetupCheckpointCodecV1.decodeCatalogImportRow(stored.payload).let { it.binding.installationId to it.sourceId }
                    LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND ->
                        LocalSetupCheckpointCodecV1.decodeOpeningStockRow(stored.payload).let { it.binding.installationId to it.sourceId }
                    LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND ->
                        LocalSetupCheckpointCodecV1.decodeOpeningFloatRow(stored.payload).let { it.binding.installationId to it.sourceId }
                    else -> return false
                }
                if (rowInstallation != installationId) continue
                if (key.id != LocalSetupCheckpointProfileV1.rowId(kind, installationId, sourceId) || key !in indexed ||
                    view.claim(LocalSetupCheckpointProfileV1.sourceClaim(kind, installationId, sourceId)) != key.id ||
                    !observed.add(key)) return false
            }
            val next = page.nextAfter
            if (next != null && (page.keys.isEmpty() || next != page.keys.last().id ||
                    after != null && next.value <= after!!.value)) return false
            after = next
        } while (after != null)
        observed == indexed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

internal suspend fun verifyCatalogManifest(view: CommandReadView, scope: BranchScope, installationId: DomainId,
    request: LocalSetupCatalogImportCompleteRequestV1,
    actual: List<Pair<AggregateKey, LocalSetupCatalogImportRowRecordV1>>): List<LocalSetupCatalogImportRowRefV1>? {
    if (request.mode == LocalSetupImportModeV1.EXPLICIT_NO_IMPORT) {
        return if (actual.isEmpty()) emptyList() else null
    }
    if (actual.size != request.expectedRowCount) return null
    val bySource = actual.associateBy { it.second.sourceId }
    if (bySource.size != actual.size || actual.map { it.second.catalogOperationId }.distinct().size != actual.size) return null
    val entries = actual.map { (_, row) -> LocalSetupCatalogImportManifestEntryV1(
        row.sourceId, row.skuId, row.catalogOperationId) }
    if (LocalSetupCheckpointCodecV1.catalogInputManifestDigest(entries) != request.expectedManifestDigest) return null
    val refs = mutableListOf<LocalSetupCatalogImportRowRefV1>()
    for ((key, row) in actual) {
        if (row.binding != request.target.original) return null
        if (key != LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND,
                LocalSetupCheckpointProfileV1.rowId(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND,
                    installationId, row.sourceId))) return null
        if (view.claim(LocalSetupCheckpointProfileV1.sourceClaim(
                LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, installationId, row.sourceId)) != key.id) return null
        if (view.claim(LocalSetupCheckpointProfileV1.catalogOperationClaim(installationId, row.catalogOperationId)) != key.id) return null
        val effect = catalogEffect(view, scope, row.skuId, row.catalogOperationId) ?: return null
        if (effect.digest != row.catalogEffectDigest || effect.revision != row.catalogEffectRevision ||
            effect.publicationId != row.catalogPublicationId) return null
        refs += LocalSetupCatalogImportRowRefV1(row.sourceId, row.skuId, key.id, row.catalogOperationId,
            row.catalogEffectDigest, row.catalogEffectRevision)
    }
    if (refs.map { it.sourceId }.distinct().size != refs.size) return null
    return refs.sortedBy { it.sourceId.value }
}

internal suspend fun verifyOpeningManifest(view: CommandReadView, scope: BranchScope, installationId: DomainId,
    request: LocalSetupOpeningCompleteRequestV1,
    actualStock: List<Pair<AggregateKey, LocalSetupOpeningStockRowRecordV1>>?,
    actualFloat: List<Pair<AggregateKey, LocalSetupOpeningFloatRowRecordV1>>?):
    Pair<List<LocalSetupOpeningStockRowRefV1>, List<LocalSetupOpeningFloatRowRefV1>>? {
    if (actualStock == null || actualFloat == null) return null
    val stock = if (request.stockChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO) {
        if (actualStock.isNotEmpty()) return null
        emptyList()
    } else {
        if (actualStock.size != request.expectedStockRowCount) return null
        val bySource = actualStock.associateBy { it.second.sourceId }
        if (bySource.size != actualStock.size) return null
        val entries = actualStock.map { (_, row) -> LocalSetupOpeningStockManifestEntryV1(
            row.sourceId, row.receiptId, row.openingOperationId) }
        if (LocalSetupCheckpointCodecV1.stockInputManifestDigest(entries) != request.expectedStockManifestDigest) return null
        val refs = mutableListOf<LocalSetupOpeningStockRowRefV1>()
        for ((key, row) in actualStock) {
            if (row.binding != request.target.original) return null
            if (key != LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND,
                    LocalSetupCheckpointProfileV1.rowId(LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND,
                        installationId, row.sourceId))) return null
            if (view.claim(LocalSetupCheckpointProfileV1.sourceClaim(
                    LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, installationId, row.sourceId)) != key.id) return null
            val effect = LocalStockOpeningEvidenceV1Source.observe(view, row.openingOperationId, row.receiptId) ?: return null
            val line = effect.lines.singleOrNull { it.sourceId == row.sourceId } ?: return null
            if (line.skuId != row.skuId || line.warehouseId != row.warehouseId || line.quantity != row.quantity ||
                line.originalValue != row.value || line.lotId != row.lotId || line.serialId != row.serialId) return null
            if (effect.effectDigest != row.stockEffectDigest || effect.effectRevision != row.stockEffectRevision) return null
            refs += LocalSetupOpeningStockRowRefV1(row.sourceId, row.receiptId, key.id, row.openingOperationId,
                row.skuId, row.warehouseId, row.quantity, row.value, row.stockEffectDigest, row.stockEffectRevision)
        }
        refs.sortedBy { it.sourceId.value }
    }
    val float = if (request.floatChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO) {
        if (actualFloat.isNotEmpty()) return null
        emptyList()
    } else {
        if (actualFloat.size != request.expectedFloatRowCount) return null
        val bySource = actualFloat.associateBy { it.second.sourceId }
        if (bySource.size != actualFloat.size) return null
        val entries = actualFloat.map { (_, row) -> LocalSetupOpeningFloatManifestEntryV1(
            row.sourceId, row.sessionId, row.openingOperationId) }
        if (LocalSetupCheckpointCodecV1.floatInputManifestDigest(entries) != request.expectedFloatManifestDigest) return null
        val refs = mutableListOf<LocalSetupOpeningFloatRowRefV1>()
        for ((key, row) in actualFloat) {
            if (row.binding != request.target.original) return null
            if (key != LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND,
                    LocalSetupCheckpointProfileV1.rowId(LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND,
                        installationId, row.sourceId))) return null
            if (view.claim(LocalSetupCheckpointProfileV1.sourceClaim(
                    LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, installationId, row.sourceId)) != key.id) return null
            val effect = cashOpeningEffect(view, scope, row.sessionId, row.sourceId, row.openingOperationId) ?: return null
            if (effect.amount != row.amount || effect.digest != row.cashEffectDigest ||
                effect.revision != row.cashEffectRevision) return null
            refs += LocalSetupOpeningFloatRowRefV1(row.sourceId, row.sessionId, key.id, row.openingOperationId,
                row.amount, row.cashEffectDigest, row.cashEffectRevision)
        }
        refs.sortedBy { it.sourceId.value }
    }
    return stock to float
}

/**
 * Installed production go-live source. Every settled observation is derived from an exact
 * original-bound checkpoint record plus its actual retained command/result/effect and the
 * referenced catalog/stock/cash operation identities. Any missing, partial, unknown or
 * conflicting required step returns a precise unavailable binding and never becomes ready.
 */
public class LocalProductionSetupGoLivePrerequisites(
    private val signatures: CompanyPublicationSignatureVerifier,
) : LocalSetupGoLivePrerequisiteSource {
    override suspend fun observe(view: CommandReadView, original: SavedOriginalSetupV1):
        List<LocalSetupPrerequisiteObservationV1> = listOf(
        configuration(view, original.scope, LocalSetupOriginalBindingV1.of(original)),
        catalogImport(view, original.scope, LocalSetupOriginalBindingV1.of(original)),
        opening(view, original.scope, LocalSetupOriginalBindingV1.of(original)),
    )

    override suspend fun historicalObservations(view: CommandReadView,
        record: com.ukkera.pos.database.setup.LocalSetupGoLiveRecordV1): List<LocalSetupPrerequisiteObservationV1>? {
        if (record.organizationId != view.scope.organizationId || record.companyId != view.scope.companyId ||
            record.branchId != view.scope.branchId) return null
        val identity = LocalSetupOriginalBindingV1(record.installationId, record.originalRequestId,
            record.issuedAttemptId, record.attemptSequence, record.definitionDigest, record.genesisDigest, record.receiptDigest)
        return listOf(configuration(view, view.scope, identity), catalogImport(view, view.scope, identity),
            opening(view, view.scope, identity))
    }

    override suspend fun recoveryResources(view: CommandReadView, installationId: DomainId): Set<RecoveryResourceKey>? {
        val catalog = scanCatalogRows(view, view.scope, installationId) ?: return null
        val stock = scanStockRows(view, view.scope, installationId) ?: return null
        val cash = scanFloatRows(view, view.scope, installationId) ?: return null
        val resources = mutableSetOf(
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, view.scope.branchId),
        )
        // Every handler selects STOCK/CASH_CUSTODY/AUTHORIZATION barrier kinds, so all
        // holds on secondary warehouse/lot/serial/drawer/actor resources are also checked.
        // Keep one stable source key per row: the full supported manifest stays below the
        // kernel's 4096-key footprint limit without truncating source membership.
        catalog.forEach { (_, row) ->
            resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.skuId)
        }
        stock.forEach { (_, row) ->
            resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, row.receiptId)
        }
        for ((_, row) in cash) {
            val evidence = view.retainedCommand(row.openingOperationId) ?: return null
            if (cashOpeningEffectFromCommand(evidence, view.scope, row.sessionId, row.sourceId) == null) return null
            resources += RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, row.sessionId)
        }
        return resources
    }

    private suspend fun configuration(view: CommandReadView,
        scope: BranchScope, identity: LocalSetupOriginalBindingV1): LocalSetupPrerequisiteObservationV1 {
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CONFIGURATION_KIND,
            identity.installationId)
        val row = view.aggregate(key)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
                "unavailable:setup.configuration.checkpoint.missing")
        val record = try { LocalSetupCheckpointCodecV1.decodeConfigurationRecord(row.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
                "unavailable:setup.configuration.checkpoint.invalid")
        }
        if (record.binding != identity || record.environment != LocalSetupEnvironmentV1.LIVE ||
            record.revision != row.revision)
            return unavailable(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
                "unavailable:setup.configuration.original-mismatch")
        if (!verifyCheckpointOperation(view, record.operationId, LocalSetupCheckpointProfileV1.CONFIGURE, key,
                record.actorId, record.recordedAt, record.policyRevision, record.masterAuthorityEpoch,
                LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CONFIGURED_CODE, identity.installationId),
                LocalSetupCheckpointCodecV1.encodeConfigurationRecord(record)))
            return unavailable(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
                "unavailable:setup.configuration.operation-mismatch")
        val activity = loadActivity(view, scope, signatures)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
                "unavailable:setup.configuration.activity-unavailable")
        if (activity.operationId != record.branchActivityOperationId ||
            activity.reference != record.branchActivityReference || activity.activities != record.activities)
            return unavailable(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
                "unavailable:setup.configuration.activity-mismatch")
        return settled(LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
            "setup.configuration.checkpoint:${record.operationId.value}")
    }

    private suspend fun catalogImport(view: CommandReadView,
        scope: BranchScope, identity: LocalSetupOriginalBindingV1): LocalSetupPrerequisiteObservationV1 {
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.CATALOG_IMPORT_CHECKPOINT_KIND,
            identity.installationId)
        val row = view.aggregate(key)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.checkpoint.missing")
        val record = try { LocalSetupCheckpointCodecV1.decodeCatalogImportCheckpoint(row.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.checkpoint.invalid")
        }
        if (record.binding != identity || record.environment != LocalSetupEnvironmentV1.LIVE)
            return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.original-mismatch")
        val expectedResult = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.CATALOG_SETTLED_CODE,
            identity.installationId, rowCount = record.rowCount, manifestDigest = record.manifestDigest)
        if (!verifyCheckpointOperation(view, record.operationId, LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT, key,
                record.actorId, record.recordedAt, record.policyRevision, record.masterAuthorityEpoch,
                expectedResult, LocalSetupCheckpointCodecV1.encodeCatalogImportCheckpoint(record)))
            return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.operation-mismatch")
        val actual = scanCatalogRows(view, scope, identity.installationId)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.rows.unreadable")
        if (record.mode == LocalSetupImportModeV1.EXPLICIT_NO_IMPORT) {
            if (actual.isNotEmpty()) return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.rows.conflict")
            return settled(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "setup.catalog-import.checkpoint:${record.operationId.value}")
        }
        if (record.rowCount != actual.size)
            return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.manifest.mismatch")
        val refs = catalogRefs(actual)
        val inputEntries = actual.map { (_, row) -> LocalSetupCatalogImportManifestEntryV1(
            row.sourceId, row.skuId, row.catalogOperationId) }
        if (record.manifestDigest == null || record.manifestDigest != LocalSetupCheckpointCodecV1.catalogManifestDigest(refs) ||
            record.inputManifestDigest == null ||
            record.inputManifestDigest != LocalSetupCheckpointCodecV1.catalogInputManifestDigest(inputEntries))
            return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                "unavailable:setup.catalog-import.manifest.mismatch")
        val bySource = actual.associateBy { it.second.sourceId }
        if (bySource.size != actual.size || actual.map { it.second.catalogOperationId }.distinct().size != actual.size) return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
            "unavailable:setup.catalog-import.rows.conflict")
        for ((rowKey, rowValue) in actual) {
            if (rowValue.binding != identity)
                return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                    "unavailable:setup.catalog-import.rows.original-mismatch")
            if (view.claim(LocalSetupCheckpointProfileV1.sourceClaim(
                    LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW_KIND, identity.installationId, rowValue.sourceId)) != rowKey.id)
                return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                    "unavailable:setup.catalog-import.rows.source-claim")
            if (view.claim(LocalSetupCheckpointProfileV1.catalogOperationClaim(identity.installationId,
                    rowValue.catalogOperationId)) != rowKey.id)
                return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                    "unavailable:setup.catalog-import.rows.operation-claim")
            val effect = catalogEffect(view, scope, rowValue.skuId, rowValue.catalogOperationId)
                ?: return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                    "unavailable:setup.catalog-import.rows.operation")
            if (effect.digest != rowValue.catalogEffectDigest || effect.revision != rowValue.catalogEffectRevision ||
                effect.publicationId != rowValue.catalogPublicationId)
                return unavailable(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
                    "unavailable:setup.catalog-import.rows.effect-mismatch")
        }
        return settled(LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
            "setup.catalog-import.checkpoint:${record.operationId.value}")
    }

    private suspend fun opening(view: CommandReadView,
        scope: BranchScope, identity: LocalSetupOriginalBindingV1): LocalSetupPrerequisiteObservationV1 {
        val key = LocalSetupCheckpointProfileV1.key(LocalSetupCheckpointProfileV1.OPENING_CHECKPOINT_KIND,
            identity.installationId)
        val row = view.aggregate(key)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.checkpoint.missing")
        val record = try { LocalSetupCheckpointCodecV1.decodeOpeningCheckpoint(row.payload) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.checkpoint.invalid")
        }
        if (record.binding != identity || record.environment != LocalSetupEnvironmentV1.LIVE)
            return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.original-mismatch")
        val expectedResult = LocalSetupCheckpointResultV1(LocalSetupCheckpointProfileV1.OPENING_SETTLED_CODE,
            identity.installationId, rowCount = record.stockRowCount + record.floatRowCount,
            manifestDigest = record.stockManifestDigest ?: record.floatManifestDigest)
        if (!verifyCheckpointOperation(view, record.operationId, LocalSetupCheckpointProfileV1.COMPLETE_OPENING, key,
                record.actorId, record.recordedAt, record.policyRevision, record.masterAuthorityEpoch,
                expectedResult, LocalSetupCheckpointCodecV1.encodeOpeningCheckpoint(record)))
            return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.operation-mismatch")
        val actualStock = scanStockRows(view, scope, identity.installationId)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.stock.unreadable")
        val actualFloat = scanFloatRows(view, scope, identity.installationId)
            ?: return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.float.unreadable")
        if (record.stockChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO) {
            if (actualStock.isNotEmpty() || record.stockRowCount != 0)
                return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                    "unavailable:setup.opening.stock.conflict")
        } else {
            if (record.stockRowCount != actualStock.size)
                return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                    "unavailable:setup.opening.stock.manifest")
            val refs = stockRefs(actualStock)
            val inputEntries = actualStock.map { (_, row) -> LocalSetupOpeningStockManifestEntryV1(
                row.sourceId, row.receiptId, row.openingOperationId) }
            if (record.stockManifestDigest == null ||
                record.stockManifestDigest != LocalSetupCheckpointCodecV1.stockManifestDigest(refs) ||
                record.stockInputManifestDigest == null ||
                record.stockInputManifestDigest != LocalSetupCheckpointCodecV1.stockInputManifestDigest(inputEntries))
                return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                    "unavailable:setup.opening.stock.manifest")
            val bySource = actualStock.associateBy { it.second.sourceId }
            if (bySource.size != actualStock.size) return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.stock.conflict")
            for ((rowKey, rowValue) in actualStock) {
                if (rowValue.binding != identity)
                    return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.stock.original-mismatch")
                if (view.claim(LocalSetupCheckpointProfileV1.sourceClaim(
                        LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_KIND, identity.installationId, rowValue.sourceId)) != rowKey.id)
                    return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.stock.source-claim")
                val effect = LocalStockOpeningEvidenceV1Source.observe(view, rowValue.openingOperationId, rowValue.receiptId)
                    ?: return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.stock.operation")
                val line = effect.lines.singleOrNull { it.sourceId == rowValue.sourceId }
                    ?: return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.stock.line")
                if (line.skuId != rowValue.skuId || line.warehouseId != rowValue.warehouseId ||
                    line.quantity != rowValue.quantity || line.originalValue != rowValue.value ||
                    line.lotId != rowValue.lotId || line.serialId != rowValue.serialId ||
                    effect.effectDigest != rowValue.stockEffectDigest ||
                    effect.effectRevision != rowValue.stockEffectRevision)
                    return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.stock.effect-mismatch")
            }
        }
        if (record.floatChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO) {
            if (actualFloat.isNotEmpty() || record.floatRowCount != 0)
                return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                    "unavailable:setup.opening.float.conflict")
        } else {
            if (record.floatRowCount != actualFloat.size)
                return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                    "unavailable:setup.opening.float.manifest")
            val refs = floatRefs(actualFloat)
            val inputEntries = actualFloat.map { (_, row) -> LocalSetupOpeningFloatManifestEntryV1(
                row.sourceId, row.sessionId, row.openingOperationId) }
            if (record.floatManifestDigest == null ||
                record.floatManifestDigest != LocalSetupCheckpointCodecV1.floatManifestDigest(refs) ||
                record.floatInputManifestDigest == null ||
                record.floatInputManifestDigest != LocalSetupCheckpointCodecV1.floatInputManifestDigest(inputEntries))
                return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                    "unavailable:setup.opening.float.manifest")
            val bySource = actualFloat.associateBy { it.second.sourceId }
            if (bySource.size != actualFloat.size) return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.float.conflict")
            for ((rowKey, rowValue) in actualFloat) {
                if (rowValue.binding != identity)
                    return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.float.original-mismatch")
                if (view.claim(LocalSetupCheckpointProfileV1.sourceClaim(
                        LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_KIND, identity.installationId, rowValue.sourceId)) != rowKey.id)
                    return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.float.source-claim")
                val effect = cashOpeningEffect(view, scope, rowValue.sessionId, rowValue.sourceId,
                    rowValue.openingOperationId)
                    ?: return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.float.operation")
                if (effect.amount != rowValue.amount || effect.digest != rowValue.cashEffectDigest ||
                    effect.revision != rowValue.cashEffectRevision)
                    return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                        "unavailable:setup.opening.float.effect-mismatch")
            }
        }
        val expectedStockTotal = actualStock.fold(Money(Currency.EGP, 0)) { total, row -> total + row.second.value }
        val expectedFloatTotal = actualFloat.fold(Money(Currency.EGP, 0)) { total, row -> total + row.second.amount }
        if (record.stockValueTotal != expectedStockTotal || record.floatTotal != expectedFloatTotal)
            return unavailable(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
                "unavailable:setup.opening.totals.mismatch")
        return settled(LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
            "setup.opening.checkpoint:${record.operationId.value}")
    }

    private fun settled(code: LocalSetupPrerequisiteCodeV1, binding: String): LocalSetupPrerequisiteObservationV1 =
        LocalSetupPrerequisiteObservationV1.settled(code, binding)

    private fun unavailable(code: LocalSetupPrerequisiteCodeV1, binding: String): LocalSetupPrerequisiteObservationV1 =
        LocalSetupPrerequisiteObservationV1.unavailable(code, binding)
}

private suspend fun verifyCheckpointOperation(view: CommandReadView, operationId: OperationId, command: CommandKey,
    key: AggregateKey, actorId: DomainId, recordedAt: UtcInstant, policyRevision: Revision,
    authorityEpoch: Revision, expectedResult: LocalSetupCheckpointResultV1,
    expectedPayload: StoredBytes): Boolean {
    val evidence = view.retainedCommand(operationId) ?: return false
    if (!evidence.committed || evidence.nativeOriginalProvenance != null || evidence.scope != view.scope ||
        evidence.command != command || evidence.originalActorId != actorId ||
        evidence.recordedAt != recordedAt || evidence.policyRevision != policyRevision ||
        evidence.authorityEpoch != authorityEpoch) return false
    val current = view.aggregate(key) ?: return false
    val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: return false
    if (write.storageGeneration != current.revision || write.schema != current.schema ||
        write.payload != current.payload || write.payloadDigest != current.digest ||
        write.payload != expectedPayload) return false
    val result = try { LocalSetupCheckpointCodecV1.decodeResult(evidence.result) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    }
    return result == expectedResult
}
