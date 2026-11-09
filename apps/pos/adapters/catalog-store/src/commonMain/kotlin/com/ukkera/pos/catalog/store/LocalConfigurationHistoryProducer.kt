package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerializationException

/** Real canonical original configuration comparison, using the core-native BEFORE cut.
 * First and valid successor SKU publications retain the exact COMPLETE price-history
 * genesis/head, version claims and high-water sources. Backfill, company price policies,
 * branch overrides retain their own original-source prerequisites. SKU refusals compare
 * the whole decision against native-owned original completion membership and exact BEFORE
 * sources. Product bodies/selected variants use independently continuous versions and the same
 * original native cut for Apply and closed refusal. Warehouse, tender and supplier refusals
 * retain their original requirement/sources.
 * Opening and branch-local purchase approvals retain exact supplier/SKU/warehouse sources;
 * a company-shared PO allocation is still not minted by branch-local approval. */
internal class LocalConfigurationHistoryProducer(private val signatures: CompanyPublicationSignatureVerifier) : RuntimeHistoryBusinessProducer {
    private val priceHistory = LocalCompanyPriceHistory()
    override val commands: Set<CommandKey> = setOf(LocalConfigurationProfile.COMMAND)
    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val original = command.original as? RetainedOriginalV2.Legacy ?: return false
        if (command.command !in commands || original.evidence.nativeOriginalProvenance != null) return false
        val request = LocalConfigurationCodec.decode(command.normalized)
        check(LocalConfigurationCodec.encode(request) == command.normalized)
        if (!original.evidence.committed) return joinRejection(command, original.evidence, request)
        val resources = mutableSetOf(priceResource(command.actorId))
        fun track(record: ConfigurationRecord): ConfigurationRecord {
            resources += priceResource(record.id); resources += priceResource(record.authorizedBy)
            if (record.body is ConfigurationBody.Sku) {
                verify(record.signedSku(), command.scope)
                resources += companySourceResources(record, emptyList())
            }
            return record
        }
        suspend fun source(kind: String, id: DomainId): ConfigurationRecord? {
            resources += priceResource(id)
            return command.aggregate(LocalConfigurationProfile.key(kind, id))?.let { track(decodeRecord(command.scope, it)) }
        }
        val kind: String; val id: DomainId; val action: SecurityAction
        when (request) {
            is LocalConfigurationRequestV1.InstallCompanySku -> { kind = LocalConfigurationProfile.SKU; id = request.publication.definition.skuId; action = SecurityAction.CATALOG_ACTIVATE }
            is LocalConfigurationRequestV1.ConfigureWarehouse -> { kind = LocalConfigurationProfile.WAREHOUSE; id = request.warehouse.id; action = SecurityAction.WAREHOUSE_CONFIGURE }
            is LocalConfigurationRequestV1.ConfigureTender -> { kind = LocalConfigurationProfile.TENDER; id = request.tender.id; action = SecurityAction.TENDER_CONFIGURE }
            is LocalConfigurationRequestV1.ConfigureSupplier -> { kind = LocalConfigurationProfile.SUPPLIER; id = request.supplier.id; action = SecurityAction.SUPPLIER_ADMINISTRATION }
            is LocalConfigurationRequestV1.ConfigureSalesRoute -> { kind = LocalConfigurationProfile.SALES_ROUTE; id = request.route.id; action = SecurityAction.WAREHOUSE_CONFIGURE }
            is LocalConfigurationRequestV1.ApproveReceiving -> {
                if (request.approval.kind == LocalBasisKindV1.PURCHASE_ORDER_ALLOCATION) return false
                kind = LocalConfigurationProfile.BASIS; id = request.approval.id
                action = if (request.approval.kind == LocalBasisKindV1.OPENING)
                    SecurityAction.OPENING_INVENTORY_APPROVE else SecurityAction.PURCHASING_APPROVE
            }
        }
        val key = LocalConfigurationProfile.key(kind, id)
        val before = if (request is LocalConfigurationRequestV1.InstallCompanySku || request is LocalConfigurationRequestV1.ConfigureSupplier)
            command.aggregateSource(key)?.row else command.aggregate(key)
        val previous = before?.let { track(decodeRecord(command.scope, it)) }
        val revision = previous?.revision ?: Revision(0)
        val amount = if (request is LocalConfigurationRequestV1.ApproveReceiving)
            request.approval.lines.fold(Money(Currency.EGP, 0)) { sum, line -> sum + line.maximumOwnedValue } else null
        command.requireRequirement(AuthorizationRequirement(Permission(action, PermissionVerb.ADMINISTER), command.scope,
            ActionBinding(id, revision, command.semanticDigest, command.operationId, "local.configuration"),
            if (amount != null) LimitUse.Monetary(amount) else LimitUse.NotApplicable))
        val claims = mutableListOf<ClaimChange>()
        val sidecars = mutableListOf<AggregateChange>()
        val cursorKey = LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, command.scope.branchId)
        val cursorRow = if (request is LocalConfigurationRequestV1.InstallCompanySku) command.aggregateSource(cursorKey)?.row else null
        val oldCursor = cursorRow?.let { track(decodeRecord(command.scope, it)) }
        check(oldCursor == null || oldCursor.body is ConfigurationBody.CatalogCursor)
        val body: ConfigurationBody = when (request) {
            is LocalConfigurationRequestV1.InstallCompanySku -> {
                val signed = request.publication; val sku = signed.definition
                verify(signed, command.scope)
                val priorSigned = (previous?.body as? ConfigurationBody.Sku)?.value
                val prior = priorSigned?.definition
                check(previous == null || prior != null)
                // Legacy originals without the additive extension keep their old proof shape.
                // A product-bearing original never substitutes today's completion membership.
                val completed = if (sku.unitDefinitions != null || sku.productDefinition != null || sku.variantValues != null ||
                    prior?.productDefinition != null || prior?.variantValues != null)
                    command.completedNativeBusinessPublicationsBefore() else null
                check(before?.revision?.value != Long.MAX_VALUE && cursorRow?.revision?.value != Long.MAX_VALUE)
                check(!incompatibleLocalPriceRules(sku.prices) && command.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, sku.publicationId)) == null)
                if (prior != null) {
                    check(localCatalogUnitShapeRejection(sku) == null)
                    val publication = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, prior.publicationId))
                        ?: error("Original prior SKU publication unavailable")
                    check(decodeRecord(command.scope, publication.row) == checkNotNull(previous).copy(
                        kind = LocalConfigurationProfile.PUBLICATION, id = prior.publicationId, revision = Revision(0)))
                    val oldPrices = prior.prices.associateBy { it.ruleId }
                    for (price in sku.prices) {
                        val oldPrice = oldPrices[price.ruleId] ?: continue
                        check(price.revision >= oldPrice.revision && (price.revision != oldPrice.revision || price == oldPrice))
                    }
                    check(sku.skuRevision > prior.skuRevision && sku.catalogRevision >= prior.catalogRevision)
                    check(sku.productId == prior.productId && sku.baseUnitId == prior.baseUnitId && sku.quantityScale == prior.quantityScale &&
                        sku.tracking == prior.tracking && sku.fulfillment == prior.fulfillment)
                    check(prior.unitDefinitions == null || sku.unitDefinitions != null)
                    check(prior.productDefinition == null || sku.productDefinition != null)
                }
                if (sku.unitDefinitions != null) {
                    // Apply and refusal require the same complete original native membership,
                    // including approvals never activated here. Missing old proof stays held.
                    val units = originalUnits(command, signed, priorSigned,
                        checkNotNull(completed))
                    if (units.sourceUnavailable) return false
                    check(units.rejection == null && units.definitions == sku.unitDefinitions)
                    resources += localCatalogUnitResources(sku)
                }
                if (completed != null) {
                    val product = originalProduct(command, signed, priorSigned, completed)
                    if (product.sourceUnavailable) return false
                    check(product.rejection == null && product.definition == sku.productDefinition && product.variantValues == sku.variantValues)
                    resources += product.resources
                }
                if (!priceHistory.joinCommitted(command, signed, prior, resources, claims, sidecars)) return false
                for (identity in listOf("sku-code/${sku.code}") + sku.barcodes.map { "barcode/$it" }) {
                    val claim = ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, identity)
                    val owner = command.claim(claim)
                    check(owner == null || owner == sku.skuId)
                    if (owner == null) claims += ClaimChange.Acquire(claim, sku.skuId)
                }
                resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, sku.skuId)
                resources += priceResource(sku.productId); resources += priceResource(sku.publicationId); resources += priceResource(cursorKey.id)
                resources += localCatalogProductResources(sku)
                prior?.let { resources += localCatalogUnitResources(it); resources += localCatalogProductResources(it); resources += priceResource(it.publicationId) }
                for (promise in listOfNotNull(sku.returnPromise, prior?.returnPromise)) resources += priceResource(promise.policyId)
                for (price in sku.prices + prior?.prices.orEmpty()) { resources += priceResource(price.ruleId); resources += priceResource(price.tax.policyId) }
                ConfigurationBody.Sku(signed)
            }
            is LocalConfigurationRequestV1.ConfigureWarehouse -> {
                check(before?.revision == request.expectedStorageRevision && request.warehouse.active)
                resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, id)
                ConfigurationBody.Warehouse(request.warehouse)
            }
            is LocalConfigurationRequestV1.ConfigureTender -> {
                check(before?.revision == request.expectedStorageRevision); ConfigurationBody.ExternalTender(request.tender)
            }
            is LocalConfigurationRequestV1.ConfigureSupplier -> {
                check(before?.revision == request.expectedStorageRevision)
                ConfigurationBody.Supplier(request.supplier, (previous?.body as? ConfigurationBody.Supplier)?.reference)
            }
            is LocalConfigurationRequestV1.ConfigureSalesRoute -> {
                check(before?.revision == request.expectedStorageRevision)
                val warehouse = (source(LocalConfigurationProfile.WAREHOUSE, request.route.warehouseId)?.body as? ConfigurationBody.Warehouse)?.value
                val oldRoute = (previous?.body as? ConfigurationBody.SalesRoute)?.value
                check(warehouse?.active == true && (oldRoute == null || oldRoute.activity == request.route.activity && oldRoute.channel == request.route.channel))
                val claim = salesRouteClaim(request.route.activity, request.route.channel); val owner = command.claim(claim)
                check(owner == null || owner == request.route.id)
                if (owner == null) claims += ClaimChange.Acquire(claim, request.route.id)
                resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, request.route.warehouseId)
                ConfigurationBody.SalesRoute(request.route)
            }
            is LocalConfigurationRequestV1.ApproveReceiving -> {
                check(before == null && request.approval.validUntil?.let { command.recordedAt >= it } != true)
                request.approval.supplierId?.let { supplierId ->
                    val supplier = (source(LocalConfigurationProfile.SUPPLIER, supplierId)?.body as? ConfigurationBody.Supplier)?.value
                    check(supplier?.active == true)
                }
                for (line in request.approval.lines) {
                    val item = source(LocalConfigurationProfile.SKU, line.skuId)
                    val signed = (item?.body as? ConfigurationBody.Sku)?.value ?: error("Original receiving SKU unavailable")
                    verify(signed, command.scope)
                    val sku = signed.definition
                    val warehouse = (source(LocalConfigurationProfile.WAREHOUSE, line.warehouseId)?.body as? ConfigurationBody.Warehouse)?.value
                    check(sku.active && sku.tracking != LocalTrackingV1.NONE &&
                        (request.approval.kind == LocalBasisKindV1.OPENING || sku.canPurchase) && line.maximumBaseQuantity.unitId == sku.baseUnitId &&
                        line.maximumBaseQuantity.scale == sku.quantityScale && warehouse?.active == true)
                    val claim = ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}")
                    check(command.claim(claim) == null); claims += ClaimChange.Acquire(claim, id)
                    resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, line.skuId)
                    resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, line.warehouseId)
                    resources += RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, line.allocationId)
                }
                resources += RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, id)
                ConfigurationBody.Basis(request.approval)
            }
        }
        resources += priceResource(id)
        val next = previous?.revision?.next() ?: Revision(0)
        val record = ConfigurationRecord(command.scope, kind, id, next, body, command.actorId, command.policyRevision, command.recordedAt)
        val changes = mutableListOf(AggregateChange(key, before?.revision, configurationV1WriteSchema(kind, before), encodeValue(record, ConfigurationRecord.serializer())))
        if (request is LocalConfigurationRequestV1.InstallCompanySku) {
            val sku = request.publication.definition
            val publicationKey = LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, sku.publicationId)
            changes += AggregateChange(publicationKey, null, LocalConfigurationProfile.schema(LocalConfigurationProfile.PUBLICATION),
                encodeValue(record.copy(kind = publicationKey.kind, id = publicationKey.id, revision = Revision(0)), ConfigurationRecord.serializer()))
            val oldRevision = (oldCursor?.body as? ConfigurationBody.CatalogCursor)?.latestObservedCompanyRevision
            val cursor = ConfigurationRecord(command.scope, cursorKey.kind, cursorKey.id, oldCursor?.revision?.next() ?: Revision(0),
                ConfigurationBody.CatalogCursor(if (oldRevision != null && oldRevision > sku.catalogRevision) oldRevision else sku.catalogRevision),
                command.actorId, command.policyRevision, command.recordedAt)
            changes += AggregateChange(cursorKey, cursorRow?.revision, LocalConfigurationProfile.schema(cursorKey.kind), encodeValue(cursor, ConfigurationRecord.serializer()))
        }
        changes += sidecars
        command.requireLegacyEffects(ProposedOutcome.Apply(encodeValue(LocalConfigurationResultV1("configuration.applied", id, next),
            LocalConfigurationResultV1.serializer()), changes, claims), resources)
        return true
    }

    private suspend fun joinRejection(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalConfigurationRequestV1): Boolean {
        if (request is LocalConfigurationRequestV1.InstallCompanySku)
            return joinSkuRejection(command, original, request)
        if (original.code !in setOf("configuration.stale_revision", "warehouse.controlled_retirement_required",
                "configuration.capacity_required")) return false
        val kind: String; val id: DomainId; val action: SecurityAction; val expectedStorageRevision: Revision?
        when (request) {
            is LocalConfigurationRequestV1.ConfigureWarehouse -> {
                kind = LocalConfigurationProfile.WAREHOUSE; id = request.warehouse.id
                action = SecurityAction.WAREHOUSE_CONFIGURE; expectedStorageRevision = request.expectedStorageRevision
            }
            is LocalConfigurationRequestV1.ConfigureTender -> {
                kind = LocalConfigurationProfile.TENDER; id = request.tender.id
                action = SecurityAction.TENDER_CONFIGURE; expectedStorageRevision = request.expectedStorageRevision
            }
            is LocalConfigurationRequestV1.ConfigureSupplier -> {
                kind = LocalConfigurationProfile.SUPPLIER; id = request.supplier.id
                action = SecurityAction.SUPPLIER_ADMINISTRATION; expectedStorageRevision = request.expectedStorageRevision
            }
            else -> return false
        }
        check(semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val before = command.aggregateSource(LocalConfigurationProfile.key(kind, id))?.row
        val previous = before?.let { decodeRecord(command.scope, it) }
        // Derive the original handler's first refusal, never trust the retained code.
        // Storage expectation and domain authorization revision remain distinct.
        val code = when {
            request is LocalConfigurationRequestV1.ConfigureWarehouse && !request.warehouse.active ->
                "warehouse.controlled_retirement_required"
            before?.revision != expectedStorageRevision -> "configuration.stale_revision"
            before?.revision?.value == Long.MAX_VALUE -> "configuration.capacity_required"
            else -> return false
        }
        check(original.code == code)
        command.requireRequirement(AuthorizationRequirement(Permission(action, PermissionVerb.ADMINISTER), command.scope,
            ActionBinding(id, previous?.revision ?: Revision(0), command.semanticDigest, command.operationId, "local.configuration"),
            LimitUse.NotApplicable))
        val resources = mutableSetOf(priceResource(command.actorId), priceResource(id))
        previous?.let { resources += priceResource(it.id); resources += priceResource(it.authorizedBy) }
        if (request is LocalConfigurationRequestV1.ConfigureWarehouse)
            resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, id)
        val expected = ProposedOutcome.Reject(code,
            encodeValue(LocalConfigurationResultV1(code, null, null), LocalConfigurationResultV1.serializer()))
        check(original.result == expected.result && original.aggregateWrites.isEmpty() &&
            original.claimChanges.isEmpty() && original.jobs.isEmpty())
        val result = expected.result.copyBytes()
        try { check(sha256(result) == original.resultHash) } finally { result.fill(0) }
        command.requireConfigurationRejection(expected, resources)
        return true
    }

    private suspend fun joinSkuRejection(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalConfigurationRequestV1.InstallCompanySku): Boolean {
        if (original.code !in SKU_REJECTION_CODES) return false
        check(semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val signed = request.publication
        val sku = signed.definition
        val key = LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, sku.skuId)
        val resources = mutableSetOf(priceResource(command.actorId))
        fun track(record: ConfigurationRecord): ConfigurationRecord = record.also {
            resources += priceResource(it.id); resources += priceResource(it.authorizedBy)
            if (it.body is ConfigurationBody.Sku) {
                verify(it.signedSku(), command.scope)
                resources += companySourceResources(it, emptyList())
            }
        }
        val before = command.aggregateSource(key)?.row
        val previous = before?.let { track(decodeRecord(command.scope, it)) }
        val prior = (previous?.body as? ConfigurationBody.Sku)?.value
        check(previous == null || prior != null) { "Invalid original catalog storage" }
        command.requireRequirement(AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER),
            command.scope, ActionBinding(sku.skuId, previous?.revision ?: Revision(0), command.semanticDigest,
                command.operationId, "local.configuration"), LimitUse.NotApplicable))
        val cursorKey = LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, command.scope.branchId)
        val cursorRow = command.aggregateSource(cursorKey)?.row
        val cursor = cursorRow?.let { track(decodeRecord(command.scope, it)) }
        check(cursor == null || cursor.body is ConfigurationBody.CatalogCursor)

        // Only the actual original native observation can prove completion or known absence.
        // Current trust and later approvals cannot supply an earlier negative signature fact.
        val completed = command.completedNativeBusinessPublicationsBefore()
        check(completed.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
            completed.all { it.proposal.company == command.scope.company() } &&
            completed.map { it.originalActionId }.distinct().size == completed.size &&
            completed.map { it.proposal.publicationId }.distinct().size == completed.size &&
            completed.map { it.signingKeyId }.distinct().size == completed.size)
        val publicationAuthenticated = originalPublicationAuthenticated(command.scope, signed, completed)
        val units = originalUnits(command, signed, prior, completed)
        val product = originalProduct(command, signed, prior, completed)
        resources += product.resources
        val prices = priceHistory.prepareOriginal(command, signed, prior?.definition)
        resources += prices.resources
        val publicationKey = LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, sku.publicationId)
        val alreadyInstalled = command.aggregateSource(publicationKey) != null
        val owners = (listOf("sku-code/${sku.code}") + sku.barcodes.map { "barcode/$it" }).map {
            command.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, it))
        }

        resources += priceResource(sku.skuId)
        resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, sku.skuId)
        resources += priceResource(sku.productId); resources += priceResource(sku.publicationId); resources += priceResource(cursorKey.id)
        resources += localCatalogUnitResources(sku)
        resources += localCatalogProductResources(sku)
        prior?.definition?.let { resources += localCatalogUnitResources(it); resources += localCatalogProductResources(it); resources += priceResource(it.publicationId) }
        for (promise in listOfNotNull(sku.returnPromise, prior?.definition?.returnPromise)) resources += priceResource(promise.policyId)
        for (price in sku.prices + prior?.definition?.prices.orEmpty()) {
            resources += priceResource(price.ruleId); resources += priceResource(price.tax.policyId)
        }

        // An unavailable unit/product source was deferred before evaluation/durable outcomes, even if a
        // later price/publication/identifier refusal would otherwise have replaced its code.
        if (units.sourceUnavailable || product.sourceUnavailable) return false
        var code = localCompanySkuPreparationRejection(sku, prior?.definition, publicationAuthenticated,
            units.rejection, prices.rejection, alreadyInstalled, owners, product.rejection)
        if (code == null) {
            if (configurationStorageCapacityRequired(before, cursorRow)) code = "configuration.capacity_required"
            else {
                val record = ConfigurationRecord(command.scope, key.kind, key.id, previous?.revision?.next() ?: Revision(0),
                    ConfigurationBody.Sku(signed), command.actorId, command.policyRevision, command.recordedAt)
                if (boundedConfigurationRecord(record) == null || boundedConfigurationRecord(record.copy(
                        kind = LocalConfigurationProfile.PUBLICATION, id = sku.publicationId, revision = Revision(0))) == null)
                    code = "configuration.capacity_required"
            }
        }
        code ?: return false
        check(original.code == code)
        val expected = ProposedOutcome.Reject(code,
            encodeValue(LocalConfigurationResultV1(code, null, null), LocalConfigurationResultV1.serializer()))
        check(original.result == expected.result && original.aggregateWrites.isEmpty() &&
            original.claimChanges.isEmpty() && original.jobs.isEmpty())
        val result = expected.result.copyBytes()
        try { check(sha256(result) == original.resultHash) } finally { result.fill(0) }
        command.requireConfigurationRejection(expected, resources)
        return true
    }

    private fun originalPublicationAuthenticated(scope: BranchScope, signed: LocalSignedSkuV1,
        completed: List<CompanyBusinessPublicationOutput>): Boolean {
        val sku = signed.definition
        if (sku.company != scope.company()) return false
        val output = completed.singleOrNull { it.signingKeyId == signed.signingKeyId } ?: return false
        if (output.proposal.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1 || output.proposal.company != sku.company ||
            output.proposal.publicationId != sku.publicationId || output.proposal.entityId != sku.skuId ||
            output.signatureHex != signed.signatureHex) return false
        val bytes = LocalConfigurationCodec.canonicalPublication(sku)
        val originalBytes = output.proposal.copyCanonicalContent()
        return try { bytes.contentEquals(originalBytes) } finally { bytes.fill(0); originalBytes.fill(0) }
    }

    private suspend fun originalUnits(command: RuntimeHistoricalCommand, signed: LocalSignedSkuV1,
        previous: LocalSignedSkuV1?, completed: List<CompanyBusinessPublicationOutput>): LocalCatalogUnitResolution {
        val sku = signed.definition
        localCatalogUnitShapeRejection(sku)?.let { return LocalCatalogUnitResolution(null, it) }
        if (sku.unitDefinitions == null) return LocalCatalogUnitResolution(null,
            if (previous?.definition?.unitDefinitions != null) "catalog.unit_definitions_required" else null)
        fun unavailable(): LocalCatalogUnitResolution =
            LocalCatalogUnitResolution(null, "catalog.unit_history_required", sourceUnavailable = true)
        val native = mutableMapOf<DomainId, LocalSignedSkuV1>()
        for (output in completed) {
            if (output.proposal.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1) continue
            val value = try { output.readNativeCompanySku() }
            catch (_: SerializationException) { return unavailable() }
            catch (_: IllegalArgumentException) { return unavailable() }
            if (native.put(value.definition.publicationId, value) != null) return unavailable()
        }
        if (native[sku.publicationId] != signed) return unavailable()
        val activated = mutableMapOf<DomainId, LocalSignedSkuV1>()
        val publications = command.aggregates(LocalConfigurationProfile.PUBLICATION)
        if (publications.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return unavailable()
        for (row in publications) {
            val record = originalMember(command, row)
            if (record.revision != Revision(0)) return unavailable()
            val value = record.signedSku()
            if (native[value.definition.publicationId] != value || activated.put(value.definition.publicationId, value) != null)
                return unavailable()
        }
        val current = command.aggregates(LocalConfigurationProfile.SKU)
        if (current.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return unavailable()
        for (row in current) {
            val value = originalMember(command, row).signedSku()
            if (activated[value.definition.publicationId] != value) return unavailable()
        }
        if (previous != null && activated[previous.definition.publicationId] != previous) return unavailable()
        localCatalogUnitHistoryRejection(native.values.toList())?.let { return LocalCatalogUnitResolution(null, it) }
        localCatalogConversionRevisionRejection(sku, activated.values.map { it.definition })?.let {
            return LocalCatalogUnitResolution(null, it)
        }
        return LocalCatalogUnitResolution(sku.unitDefinitions.toList(), null)
    }

    private suspend fun originalMember(command: RuntimeHistoricalCommand, row: StoredAggregate): ConfigurationRecord {
        val source = command.aggregateSource(row.key) ?: error("Original catalog membership source unavailable")
        check(source.row.key == row.key && source.row.revision == row.revision && source.row.digest == row.digest &&
            source.row.schema == row.schema && source.row.payload == row.payload)
        return decodeRecord(command.scope, source.row)
    }

    /** Complete original membership and exact earlier AFTER sources; no current owner registry. */
    private suspend fun originalProduct(command: RuntimeHistoricalCommand, signed: LocalSignedSkuV1,
        previous: LocalSignedSkuV1?, completed: List<CompanyBusinessPublicationOutput>): LocalCatalogProductResolution {
        val sku = signed.definition
        fun unavailable(): LocalCatalogProductResolution =
            LocalCatalogProductResolution(null, null, "catalog.product_history_required", sourceUnavailable = true)
        if (completed.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS ||
            completed.any { it.proposal.company != command.scope.company() } ||
            completed.map { it.originalActionId }.distinct().size != completed.size ||
            completed.map { it.proposal.publicationId }.distinct().size != completed.size ||
            completed.map { it.signingKeyId }.distinct().size != completed.size) return unavailable()
        val native = mutableMapOf<DomainId, LocalSignedSkuV1>()
        for (output in completed) {
            if (output.proposal.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1) continue
            val value = try { output.readNativeCompanySku() }
            catch (_: SerializationException) { return unavailable() }
            catch (_: IllegalArgumentException) { return unavailable() }
            if (native.put(value.definition.publicationId, value) != null) return unavailable()
        }
        val relevant = localCatalogProductRelevantDefinitions(sku.productId, sku.skuId, native.values.map { it.definition })
        val explicit = sku.productDefinition != null || sku.variantValues != null ||
            previous?.definition?.productDefinition != null || previous?.definition?.variantValues != null
        if (!explicit && relevant.none { it.productDefinition != null || it.variantValues != null })
            return LocalCatalogProductResolution(null, null, null)
        if (native[sku.publicationId] != signed) return unavailable()
        val relevantPublications = relevant.map { it.publicationId }.toSet()
        val resources = localCatalogProductComparisonResources(relevant).toMutableSet()
        fun track(record: ConfigurationRecord) {
            if (record.signedSku().definition.publicationId in relevantPublications) {
                resources += priceResource(record.id); resources += priceResource(record.authorizedBy)
            }
        }
        val activated = mutableMapOf<DomainId, LocalSignedSkuV1>()
        val publications = command.aggregates(LocalConfigurationProfile.PUBLICATION)
        if (publications.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return unavailable()
        for (row in publications) {
            val record = originalMember(command, row)
            if (record.revision != Revision(0)) return unavailable()
            val value = record.signedSku()
            if (native[value.definition.publicationId] != value || activated.put(value.definition.publicationId, value) != null)
                return unavailable()
            track(record)
        }
        val current = command.aggregates(LocalConfigurationProfile.SKU)
        if (current.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS) return unavailable()
        for (row in current) {
            val record = originalMember(command, row)
            val value = record.signedSku()
            if (activated[value.definition.publicationId] != value) return unavailable()
            track(record)
        }
        if (previous != null && activated[previous.definition.publicationId] != previous) return unavailable()
        val rejection = localCatalogProductActivationRejection(sku, previous?.definition, relevant)
        return LocalCatalogProductResolution(if (rejection == null) sku.productDefinition else null,
            if (rejection == null) sku.variantValues?.toList() else null, rejection, resources = resources.toSet())
    }

    private companion object {
        val SKU_REJECTION_CODES = setOf("catalog.publication_required", "catalog.price_conflict", "catalog.price_version_changed",
            "catalog.stale_publication", "catalog.controlled_migration_required", "catalog.unit_definitions_required",
            "catalog.unit_precision_required", "catalog.serial_unit_required", "catalog.cross_dimension_unavailable",
            "catalog.conversion_evidence_invalid", "catalog.unit_definition_conflict", "catalog.conversion_identity_changed",
            "catalog.conversion_version_changed", "catalog.legacy_unit_identity", "catalog.price_history_index_required",
            "catalog.product_definition_required", "catalog.product_definition_invalid", "catalog.product_identity_changed",
            "catalog.product_version_changed", "catalog.legacy_product_identity", "catalog.variant_values_required",
            "catalog.variant_selection_invalid", "catalog.variant_identity_changed", "catalog.duplicate_variant_combination", "catalog.archived_product",
            "catalog.price_history_index_in_progress", "catalog.price_history_capacity_required", "catalog.price_history_conflict",
            "catalog.publication_already_installed", "catalog.duplicate_identifier", "configuration.capacity_required")
    }

    private fun verify(signed: LocalSignedSkuV1, scope: BranchScope) {
        val sku = signed.definition; val bytes = LocalConfigurationCodec.canonicalPublication(sku)
        try { check(sku.company == scope.company() && signatures.verify(sku.company, signed.signingKeyId, bytes, signed.signatureHex)) }
        finally { bytes.fill(0) }
    }
}
