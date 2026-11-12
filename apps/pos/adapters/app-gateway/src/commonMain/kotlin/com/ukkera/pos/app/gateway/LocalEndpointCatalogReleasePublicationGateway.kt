package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact native release approval, followed only by a separate immutable branch journal intent. */
public class LocalEndpointCatalogReleasePublicationGateway(
    private val publications: LocalCompanyBusinessPublications,
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
) : LocalCatalogReleasePublicationGateway {
    private val actions = Mutex()
    override val configured: Boolean = true
    init { require(publications.trust.company == journal.owner.scope.company()) }

    override suspend fun status(catalogId: DomainId?): LocalCatalogReleasePublicationStatusResult = actions.withLock {
        try { withAccess { access ->
            if (catalogId != null) access.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, catalogId)
            val admitted = admission(access)
            val references: List<CompanyBusinessPublicationReference>
            val source: LocalCatalogReleaseAuthoringSource?
            if (catalogId == null) {
                // Original rediscovery does not claim a complete fresh authoring cut.
                // An unrelated uncertain signing action must not hide completed originals.
                references = retainedReferences()
                source = null
            } else {
                val cut = nativeCut()
                references = cut.references
                val history = releaseHistory(cut, catalogId)
                val previous = history.maxByOrNull { it.revision }?.let(::localCatalogReleaseReference)
                val active = releaseRead(access, catalogId, cut)
                val members = memberSources(access, cut)
                // Do not label an older observed native sequence as the refreshed authoring cut.
                if (nativeCut().generation != cut.generation) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                source = LocalCatalogReleaseAuthoringSource(catalogId,
                    if (previous?.revision?.value == Long.MAX_VALUE) null else previous?.revision?.next() ?: Revision(0),
                    previous?.ui(), active.activeStorageRevision, active.active?.definition?.let(::localCatalogReleaseReference)?.ui(), members)
            }
            val vacant = source != null && journal.requests.withLock { required(journal.pending()).isEmpty() }
            val refreshed = currentAccess(access.lease)
            if (catalogId != null) refreshed.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, catalogId)
            refreshed.checkCurrent()
            LocalCatalogReleasePublicationStatusResult.Available(LocalCatalogReleasePublicationStatus(
                references.filter { it.kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 &&
                    refreshed.allow(LocalAction.CATALOG_RELEASE_ACTIVATE, it.entityId) }, source,
                vacant && installed.has(LocalCatalogReleaseProfileV1.ACTIVATE) && source != null &&
                    source.nextRevision != null && source.members.isNotEmpty(), admitted.reply.observedAt))
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CatalogReleasePublicationFailure) { LocalCatalogReleasePublicationStatusResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalCatalogReleasePublicationStatusResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalCatalogReleasePublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun prepare(draft: LocalCatalogReleasePublicationDraft): LocalCatalogReleasePublicationResult = actions.withLock {
        publicationResult { access ->
            val exactDraft = draft.snapshot()
            access.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, exactDraft.catalogId)
            admission(access)
            val proposed = definition(exactDraft)
            val reference = retainedReferences().singleOrNull { it.publicationId == proposed.publicationId }
            if (reference != null) {
                if (reference.kind != CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 || reference.entityId != proposed.catalogId) invalid()
                val original = retainedPublication(reference)
                if (original.first != proposed) invalid()
                // The actual COMPLETED original is recovered unchanged after later releases or
                // branch member changes. Only a still-PREPARED action has fresh authoring gates.
                if (reference.phase == CompanyBusinessPublicationPhase.PREPARED) validateFresh(access, proposed, nativeCut())
                return@publicationResult original.second
            }
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() })
                throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            validateFresh(access, proposed, nativeCut())
            val refreshed = currentAccess(access.lease)
            refreshed.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, proposed.catalogId)
            refreshed.checkCurrent()
            val action = native(publications.prepare(makeNativeCatalogReleaseProposal(proposed)))
            val exact = reviewDefinition(action)
            if (exact != proposed) unavailable()
            LocalCatalogReleasePublicationResult.Review(LocalCatalogReleasePublicationReview(action, preview(exact)))
        }
    }

    override suspend fun review(originalActionId: DomainId): LocalCatalogReleasePublicationResult = actions.withLock {
        publicationResult { access ->
            admission(access)
            val reference = retainedReferences().singleOrNull {
                it.originalActionId == originalActionId && it.kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1
            } ?: unavailable()
            access.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, reference.entityId)
            retainedPublication(reference).second
        }
    }

    override suspend fun approve(review: LocalCatalogReleasePublicationReview, headLogin: String,
        headSecret: CharArray): LocalCatalogReleasePublicationResult {
        try { return actions.withLock {
            publicationResult { access ->
                admission(access)
                val exact = reviewDefinition(review.action)
                access.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, exact.catalogId)
                if (review.preview.companyId != exact.company.companyId || definition(review.preview.definition) != exact) invalid()
                val reference = retainedReferences().singleOrNull { it.originalActionId == review.action.originalActionId &&
                    it.publicationId == exact.publicationId && it.entityId == exact.catalogId &&
                    it.kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 } ?: unavailable()
                when (reference.phase) {
                    CompanyBusinessPublicationPhase.COMPLETED -> {
                        val original = retainedPublication(reference)
                        if (original.first != exact) unavailable()
                        return@publicationResult original.second
                    }
                    CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw CatalogReleasePublicationFailure(
                        LocalServiceProblem.STORAGE_UNCERTAIN, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                    CompanyBusinessPublicationPhase.PREPARED -> validateFresh(access, exact, nativeCut())
                }
                val refreshed = currentAccess(access.lease)
                refreshed.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, exact.catalogId)
                refreshed.checkCurrent()
                val output = native(publications.approve(review.action, headLogin, headSecret))
                val signed = completedOutput(output, review.action.originalActionId)
                if (signed.definition != exact || signed.signingKeyId != review.action.signingKeyId) unavailable()
                LocalCatalogReleasePublicationResult.Completed(completedView(output, signed))
            }
        } } finally { headSecret.fill('\u0000') }
    }

    override suspend fun completed(originalActionId: DomainId): LocalCatalogReleasePublicationResult = actions.withLock {
        publicationResult { access ->
            admission(access)
            val output = native(publications.recover(originalActionId))
            val signed = completedOutput(output, originalActionId)
            access.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, signed.definition.catalogId)
            LocalCatalogReleasePublicationResult.Completed(completedView(output, signed))
        }
    }

    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = actions.withLock {
        val operationId = OperationId(originalActionId.value)
        try { withAccess { access ->
            // Recover saved original identity before reading any fresh CAS or branch source.
            val snapshot = journal.requests.withLock { originalEntry(operationId) }
            access.checkCurrent()
            val output = native(publications.recover(originalActionId))
            val signed = completedOutput(output, originalActionId)
            journal.requests.withLock stage@{
                val existing = originalEntry(operationId)
                if (existing != null) {
                    if (snapshot != null && !snapshot.sameIntent(existing)) return@stage LocalStageResult.Uncertain(operationId)
                    if (LocalCatalogReleaseCodecV1.decode(existing.request().payload).publication != signed)
                        return@stage LocalStageResult.Uncertain(operationId)
                    val refreshed = currentAccess(access.lease)
                    refreshed.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, signed.definition.catalogId)
                    refreshed.checkCurrent()
                    return@stage LocalStageResult.Staged(pending(existing, refreshed.canContinue(existing)))
                }
                if (snapshot != null) return@stage LocalStageResult.Uncertain(operationId)
                stageFresh(access, operationId, signed)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LocalStageResult.Uncertain(operationId) }
    }

    /** Under the shared journal mutex, after definite original absence. Never dispatches. */
    private suspend fun stageFresh(access: GatewayAccess, operationId: OperationId,
        signed: LocalSignedCatalogReleaseV1): LocalStageResult {
        var writeStarted = false
        try {
            when (val unresolved = journal.pending()) {
                JournalRead.Unavailable -> return LocalStageResult.Uncertain(operationId)
                is JournalRead.Value -> if (unresolved.value.isNotEmpty())
                    return LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
            }
            if (!installed.has(LocalCatalogReleaseProfileV1.ACTIVATE)) return LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
            val value = signed.definition
            access.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, value.catalogId)
            val cut = nativeCut()
            releasePublicationIdentity(cut, value)
            val history = releaseHistory(cut, value.catalogId)
            // Complete snapshots may advance beyond branch-active without being the newest
            // root publication. Authoring still requires the latest completed predecessor.
            if (history.singleOrNull { it.publicationId == value.publicationId } != value ||
                cut.releases.singleOrNull { it.definition.publicationId == value.publicationId } != signed) invalid()
            validateMembers(access, value, cut)
            val renewed = nativeCut()
            releasePublicationIdentity(renewed, value)
            if (releaseHistory(renewed, value.catalogId).singleOrNull { it.publicationId == value.publicationId } != value ||
                renewed.releases.singleOrNull { it.definition.publicationId == value.publicationId } != signed) invalid()
            if (localCatalogReleaseMemberRejection(value, renewed.skus) != null) invalid()
            val current = releaseRead(access, value.catalogId, renewed)
            current.active?.let { if (it.definition.revision >= value.revision) invalid() }
            val activation = LocalActivateCatalogReleaseV1(signed, current.activeStorageRevision)
            val request = LocalCatalogReleaseCodecV1.activate(operationId, activation)
            val intent = LocalCommandIntent(operationId, LocalAction.CATALOG_RELEASE_ACTIVATE, 1,
                LocalRecordReference(LocalRecordKind.CATALOG_RELEASE_PUBLICATION, value.publicationId), listOfNotNull(
                    LocalScalarInput(LocalField.DOCUMENT_ID, LocalScalarValue.Identifier(value.catalogId)),
                    LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(value.publicationId)),
                    LocalScalarInput(LocalField.EXPECTED_REVISION, LocalScalarValue.Integer(value.revision.value)),
                    LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Text(localCatalogReleaseReference(value).contentDigest.value)),
                    current.activeStorageRevision?.let { LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(it.value)) },
                    LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))
            val entry = StoredClientIntentV1.staged(journal.owner, intent, request,
                text("Activate exact company catalog release", "تفعيل إصدار كتالوج الشركة الدقيق"),
                summary(value, current.activeStorageRevision))
            val refreshed = currentAccess(access.lease)
            refreshed.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, value.catalogId)
            refreshed.checkCurrent()
            writeStarted = true
            return when (journal.stage(entry)) {
                JournalWrite.DURABLE -> {
                    val released = currentAccess(access.lease)
                    released.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, value.catalogId)
                    released.checkCurrent()
                    LocalStageResult.Staged(pending(entry, released.canContinue(entry)))
                }
                JournalWrite.NO_CAPACITY -> LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(operationId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CatalogReleasePublicationFailure) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(failure.problem)
        } catch (failure: GatewayFailure) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(failure.problem)
        } catch (_: IllegalArgumentException) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
        } catch (_: Exception) { return LocalStageResult.Uncertain(operationId) }
    }

    private suspend fun originalEntry(operationId: OperationId): StoredClientIntentV1? = required(journal.find(operationId))?.also {
        it.validate(journal.owner)
        require(it.operationId == operationId && it.localAction == LocalAction.CATALOG_RELEASE_ACTIVATE &&
            it.commandName == LocalCatalogReleaseProfileV1.ACTIVATE.name && it.commandVersion == LocalCatalogReleaseProfileV1.ACTIVATE.version &&
            it.actionApprovalId == null)
        LocalCatalogReleaseCodecV1.decode(it.request().payload)
    }

    private suspend fun retainedPublication(reference: CompanyBusinessPublicationReference):
        Pair<LocalPublishedCatalogReleaseV1, LocalCatalogReleasePublicationResult> {
        val original = when (reference.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> {
                val action = native(publications.review(reference.originalActionId))
                val exact = reviewDefinition(action)
                if (action.originalActionId != reference.originalActionId) unavailable()
                exact to LocalCatalogReleasePublicationResult.Review(LocalCatalogReleasePublicationReview(action, preview(exact)))
            }
            CompanyBusinessPublicationPhase.COMPLETED -> {
                val output = native(publications.recover(reference.originalActionId))
                val signed = completedOutput(output, reference.originalActionId)
                signed.definition to LocalCatalogReleasePublicationResult.Completed(completedView(output, signed))
            }
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw CatalogReleasePublicationFailure(
                LocalServiceProblem.STORAGE_UNCERTAIN, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (reference.kind != CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 ||
            original.first.publicationId != reference.publicationId || original.first.catalogId != reference.entityId) unavailable()
        return original
    }

    /** Actual retained original lookup does not require a new complete authoring observation. */
    private suspend fun retainedReferences(): List<CompanyBusinessPublicationReference> {
        val references = native(publications.retained())
        if (references.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS ||
            references.map { it.originalActionId }.distinct().size != references.size ||
            references.map { it.publicationId }.distinct().size != references.size) unavailable()
        return references.toList()
    }

    /** Refresh the actual native owner; neither a supplied list nor absent observation can enter. */
    private suspend fun nativeCut(): CatalogReleaseNativeCut {
        val references = retainedReferences()
        if (references.any { it.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT })
            throw CatalogReleasePublicationFailure(LocalServiceProblem.STORAGE_UNCERTAIN, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val snapshot = publications.currentCompletedSnapshot() ?: unavailable()
        if (snapshot.company != journal.owner.scope.company() || snapshot.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        // Private serial originals stay in the authenticated complete snapshot, but are
        // deliberately absent from this generic facade. Compare only its visible members.
        val outputs = snapshot.completedPublications.filter { it.proposal.kind != CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 &&
            it.proposal.kind != CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 }
        if (references.count { it.phase == CompanyBusinessPublicationPhase.COMPLETED } != outputs.size) unavailable()
        val skus = mutableListOf<LocalSignedSkuV1>()
        val releases = mutableListOf<LocalSignedCatalogReleaseV1>()
        for (output in outputs) {
            val reference = references.singleOrNull { it.originalActionId == output.originalActionId } ?: unavailable()
            if (reference.phase != CompanyBusinessPublicationPhase.COMPLETED || reference.publicationId != output.proposal.publicationId ||
                reference.entityId != output.proposal.entityId || reference.kind != output.proposal.kind) unavailable()
            when (output.proposal.kind) {
                CompanyBusinessPublicationKind.CATALOG_SKU_V1 -> skus += completedSku(output)
                CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 -> releases += completedOutput(output, reference.originalActionId)
                else -> Unit
            }
        }
        return CatalogReleaseNativeCut(snapshot.generation, references.toList(), skus.toList(), releases.toList())
    }

    private fun releaseHistory(cut: CatalogReleaseNativeCut, catalogId: DomainId): List<LocalPublishedCatalogReleaseV1> =
        cut.releases.map { it.definition }.filter { it.catalogId == catalogId }.also {
            if (cut.releases.any { release -> release.definition.publicationId == catalogId }) invalid()
            if (localCatalogReleaseHistoryRejection(it) != null) invalid()
            // Every predecessor's manifest, not only the newest one, must qualify
            // against the actual authenticated complete native SKU/product cut.
            if (it.any { release -> localCatalogReleaseMemberRejection(release, cut.skus) != null }) invalid()
        }

    private fun releasePublicationIdentity(cut: CatalogReleaseNativeCut, value: LocalPublishedCatalogReleaseV1) {
        if (cut.releases.any { it.definition.catalogId == value.publicationId }) invalid()
    }

    private suspend fun validateFresh(access: GatewayAccess, value: LocalPublishedCatalogReleaseV1,
        cut: CatalogReleaseNativeCut) {
        fun sequence(observed: CatalogReleaseNativeCut) {
            releasePublicationIdentity(observed, value)
            val previous = releaseHistory(observed, value.catalogId).maxByOrNull { it.revision }?.let(::localCatalogReleaseReference)
            if (previous?.revision?.value == Long.MAX_VALUE) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            if (value.previous != previous || value.revision != (previous?.revision?.next() ?: Revision(0))) invalid()
        }
        sequence(cut)
        validateMembers(access, value, cut)
        // Refreshed UX preflight only. It does not serialize independent root owners;
        // the native approval and authoritative Master each retain their own refusal boundary.
        val renewed = nativeCut()
        sequence(renewed)
        if (localCatalogReleaseMemberRejection(value, renewed.skus) != null) invalid()
    }

    private suspend fun validateMembers(access: GatewayAccess, value: LocalPublishedCatalogReleaseV1,
        cut: CatalogReleaseNativeCut) {
        if (localCatalogReleaseMemberRejection(value, cut.skus) != null) invalid()
        for (member in value.members) {
            val original = cut.skus.singleOrNull { it.definition.publicationId == member.publicationId } ?: unavailable()
            val currentPage = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = member.skuId)).page
            val row = currentPage.items.singleOrNull() ?: invalid()
            val detail = currentPage.detail as? LocalConfigurationDetailV1.Sku ?: unavailable()
            val matching = cut.skus.filter { skuProjectionMatches(it.definition, row, detail) }
            if (matching.size != 1 || matching.single() != original) invalid()
        }
    }

    private suspend fun memberSources(access: GatewayAccess, cut: CatalogReleaseNativeCut): List<LocalCatalogReleaseMemberSource> {
        val definitions = cut.skus.map { it.definition }
        val result = mutableListOf<LocalCatalogReleaseMemberSource>()
        for ((skuId, members) in cut.skus.groupBy { it.definition.skuId }.entries.sortedBy { it.key.value }) {
            val page = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = skuId)).page
            if (page.items.isEmpty()) {
                if (page.detail != null) unavailable()
                continue // A definite protected absence is not an activated member.
            }
            val row = page.items.singleOrNull() ?: unavailable()
            val detail = page.detail as? LocalConfigurationDetailV1.Sku ?: unavailable()
            val matching = members.filter { skuProjectionMatches(it.definition, row, detail) }
            if (matching.size > 1) unavailable()
            val original = matching.singleOrNull() ?: continue
            val value = original.definition
            val product = value.productDefinition ?: continue
            if (value.variantValues == null || localCatalogProductShapeRejection(value) != null ||
                selectLocalCatalogProductVersion(value.productId, definitions) !is LocalCatalogProductSelectionV1.Selected) continue
            val canonical = LocalConfigurationCodec.canonicalPublication(value)
            val digest = try { sha256(canonical) } finally { canonical.fill(0) }
            result += LocalCatalogReleaseMemberSource(LocalCatalogReleaseMemberInput(value.publicationId, digest,
                value.skuId, value.skuRevision, value.productId, product.revision),
                LocalText(value.names.english, value.names.arabic), LocalText(product.names.english, product.names.arabic), value.code, value.active)
        }
        return result.toList()
    }

    private suspend fun releaseRead(access: GatewayAccess, catalogId: DomainId,
        cut: CatalogReleaseNativeCut): LocalCatalogReleaseReadReplyV1 {
        val reply = access.read(LocalCatalogReleaseCodecV1.query(LocalCatalogReleaseQueryV1(catalogId)))
        val value = LocalCatalogReleaseCodecV1.decodeRead(reply.payload)
        if (value.catalogId != catalogId || value.active != null && value.activeStorageRevision == null) unavailable()
        for (signed in listOfNotNull(value.active, value.publication)) {
            if (signed.definition.catalogId != catalogId || signed.definition.company != journal.owner.scope.company() ||
                cut.releases.singleOrNull { it.definition.publicationId == signed.definition.publicationId } != signed) unavailable()
        }
        return value
    }

    private fun reviewDefinition(action: CompanyBusinessPublicationAction): LocalPublishedCatalogReleaseV1 =
        action.readNativeCatalogReleaseDefinition().also {
            if (it.company != journal.owner.scope.company() || action.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        }

    private fun completedOutput(output: CompanyBusinessPublicationOutput, originalActionId: DomainId): LocalSignedCatalogReleaseV1 {
        val signed = output.readNativeCatalogRelease()
        val bytes = LocalCatalogReleaseCodecV1.canonicalPublication(signed.definition)
        try {
            if (output.originalActionId != originalActionId || signed.definition.company != journal.owner.scope.company() ||
                output.rootFingerprint != publications.trust.rootFingerprint || output.proposal.contentDigest != sha256(bytes) ||
                !publications.trust.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) unavailable()
            return signed
        } finally { bytes.fill(0) }
    }

    private fun completedSku(output: CompanyBusinessPublicationOutput): LocalSignedSkuV1 {
        val signed = output.readNativeCompanySku()
        val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
        try {
            if (signed.definition.company != journal.owner.scope.company() || output.rootFingerprint != publications.trust.rootFingerprint ||
                output.proposal.contentDigest != sha256(bytes) ||
                !publications.trust.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) unavailable()
            return signed
        } finally { bytes.fill(0) }
    }

    private fun definition(draft: LocalCatalogReleasePublicationDraft): LocalPublishedCatalogReleaseV1 =
        LocalPublishedCatalogReleaseV1(journal.owner.scope.company(), draft.catalogId, draft.publicationId, draft.revision,
            draft.previous?.let { LocalCatalogReleaseReferenceV1(it.catalogId, it.publicationId, it.revision, it.contentDigest) },
            draft.members.map { LocalCatalogReleaseMemberV1(it.publicationId, it.contentDigest, it.skuId, it.skuRevision, it.productId, it.productRevision) })
    private fun preview(value: LocalPublishedCatalogReleaseV1): LocalCatalogReleasePublicationPreview = LocalCatalogReleasePublicationPreview(
        value.company.companyId, LocalCatalogReleasePublicationDraft(value.publicationId, value.catalogId, value.revision, value.previous?.ui(),
            value.members.map { LocalCatalogReleaseMemberInput(it.publicationId, it.contentDigest, it.skuId, it.skuRevision, it.productId, it.productRevision) }))
    private fun completedView(output: CompanyBusinessPublicationOutput, signed: LocalSignedCatalogReleaseV1): LocalCatalogReleasePublicationCompleted =
        LocalCatalogReleasePublicationCompleted(output.originalActionId, preview(signed.definition), output.rootFingerprint)

    private suspend fun admission(access: GatewayAccess): ConfigurationCut =
        configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, limit = 1))
    private suspend fun publicationResult(body: suspend (GatewayAccess) -> LocalCatalogReleasePublicationResult): LocalCatalogReleasePublicationResult {
        try { return withAccess { access ->
            val result = body(access)
            val catalogId = when (result) {
                is LocalCatalogReleasePublicationResult.Review -> result.review.preview.definition.catalogId
                is LocalCatalogReleasePublicationResult.Completed -> result.publication.preview.definition.catalogId
                is LocalCatalogReleasePublicationResult.Unavailable -> null
            }
            // A head ceremony or recovery cannot release private data under stale cached grants.
            if (catalogId != null) {
                val refreshed = currentAccess(access.lease)
                refreshed.requireAction(LocalAction.CATALOG_RELEASE_ACTIVATE, catalogId)
                refreshed.checkCurrent()
            }
            result
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CatalogReleasePublicationFailure) { return LocalCatalogReleasePublicationResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { return LocalCatalogReleasePublicationResult.Unavailable(failure.problem) }
        catch (_: IllegalArgumentException) { return LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: Exception) { return LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }
    private suspend fun <T> withAccess(body: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        try {
            val access = currentAccess(lease)
            val result = body(access)
            access.checkCurrent()
            return result
        } finally { lease.destroy() }
    }
    private suspend fun currentAccess(lease: LocalWorkflowCredentialLease): GatewayAccess {
        if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val view = when (val current = lease.observeSession(endpoint)) {
            is LocalSessionViewResult.Current -> current.view
            LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        if (!lease.isCurrent() || view.actorId != journal.owner.actorId || view.scope != journal.owner.scope || view.deviceId != journal.owner.deviceId)
            throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return GatewayAccess(endpoint, lease, view, installed)
    }
    private fun <T> native(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> throw CatalogReleasePublicationFailure(when (result.nativeReason) {
            SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED -> LocalServiceProblem.DENIED
            SelfOwnedInstallationFailureV1.THROTTLED, SelfOwnedInstallationFailureV1.BUSY,
            SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED -> LocalServiceProblem.RETRY_LATER
            SelfOwnedInstallationFailureV1.INVALID_INPUT -> LocalServiceProblem.INVALID_INPUT
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
            else -> LocalServiceProblem.UNAVAILABLE
        }, result.nativeReason)
    }
    private fun pending(entry: StoredClientIntentV1, allowed: Boolean): LocalPendingOperation = LocalPendingOperation(
        entry.operationId, entry.localAction, entry.label.ui(), entry.summary.map { it.ui() },
        allowed && entry.recorded == null && !entry.acknowledged, entry.localPhase,
        entry.recorded?.let { if (it.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED })
}

private data class CatalogReleaseNativeCut(val generation: Long, val references: List<CompanyBusinessPublicationReference>,
    val skus: List<LocalSignedSkuV1>, val releases: List<LocalSignedCatalogReleaseV1>)
private class CatalogReleasePublicationFailure(val problem: LocalServiceProblem,
    val nativeReason: SelfOwnedInstallationFailureV1?) : Exception()
private fun LocalCatalogReleaseReferenceV1.ui(): LocalCatalogReleasePublicationReference =
    LocalCatalogReleasePublicationReference(catalogId, publicationId, revision, contentDigest)

/** All current projection fields must match one authenticated native original, not just counters. */
internal fun skuProjectionMatches(value: LocalPublishedSkuV1, row: LocalConfigurationListItemV1,
    detail: LocalConfigurationDetailV1.Sku): Boolean = row.id == value.skuId &&
    row.names == value.names && row.active == value.active && row.code == value.code &&
    detail.skuId == value.skuId && detail.productId == value.productId && detail.skuRevision == value.skuRevision &&
    detail.publicationRevision == value.catalogRevision && detail.names == value.names && detail.code == value.code &&
    detail.barcodes == value.barcodes && detail.baseUnitId == value.baseUnitId && detail.quantityScale == value.quantityScale &&
    detail.tracking == value.tracking && detail.fulfillment == value.fulfillment && detail.active == value.active &&
    detail.canPurchase == value.canPurchase && detail.canSell == value.canSell && detail.requiresExpiry == value.requiresExpiry &&
    detail.conversions == value.conversions && detail.prices == value.prices && detail.unitDefinitions == value.unitDefinitions &&
    detail.productDefinition == value.productDefinition && detail.variantValues == value.variantValues

/** Every ordered member is displayed; grouping only conserves the existing 32-row/2048-text bounds. */
private fun summary(value: LocalPublishedCatalogReleaseV1, storageRevision: Revision?): List<LocalRecordValue> = listOf(
    recordValue(text("Company / stable catalog", "الشركة / الكتالوج الثابت"), literal("${value.company.companyId.value} / ${value.catalogId.value}")),
    recordValue(text("Release publication / independent revision", "منشور الإصدار / مراجعته المستقلة"), literal("${value.publicationId.value} / ${value.revision.value}")),
    recordValue(text("Release canonical-content SHA-256", "بصمة محتوى الإصدار المعياري SHA-256"), literal(localCatalogReleaseReference(value).contentDigest.value)),
    recordValue(text("Exact predecessor", "السلف الدقيق"), value.previous?.let {
        literal("${it.catalogId.value} / ${it.publicationId.value} / ${it.revision.value} / ${it.contentDigest.value}")
    } ?: text("Native release genesis; no predecessor", "بداية إصدار أصلية؛ لا سلف")),
    recordValue(text("Expected active storage revision", "مراجعة تخزين الإصدار النشط المتوقعة"), storageRevision?.let { literal(it.value.toString()) }
        ?: text("Protected read proved no active pointer", "أثبتت القراءة المحمية عدم وجود مؤشر نشط")),
    recordValue(text("Authority separation", "فصل الصلاحيات"), text(
        "Signing is complete. This separate employee request activates only the exact release manifest. It does not change SKU/cursor rows, assortment, stock, purchasing allowances, invoices or money.",
        "اكتمل التوقيع. يفعّل طلب الموظف المنفصل قائمة الإصدار الدقيقة فقط. لا يغيّر صفوف الأصناف أو مؤشرها أو تشكيلة الفرع أو المخزون أو سماحات الشراء أو الفواتير أو المال."))) +
    value.members.chunked(4).mapIndexed { index, group -> recordValue(
        text("Ordered members ${index * 4 + 1}–${index * 4 + group.size}", "الأعضاء المرتبون ${index * 4 + 1}–${index * 4 + group.size}"),
        literal(group.joinToString("\n") {
            "${it.publicationId.value} / ${it.contentDigest.value} / SKU ${it.skuId.value}@${it.skuRevision.value} / PRODUCT ${it.productId.value}@${it.productRevision.value}"
        })) }
