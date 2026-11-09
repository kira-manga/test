package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native head approval followed by a separate immutable employee receipt intent. */
public class LocalEndpointPricePolicyPublicationGateway(
    private val publications: LocalCompanyBusinessPublications,
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
) : LocalPricePolicyPublicationGateway {
    private val actions = Mutex()
    override val configured: Boolean = true
    init { require(publications.trust.company == journal.owner.scope.company()) }

    override suspend fun status(): LocalPricePolicyPublicationStatusResult = actions.withLock {
        try { withAccess { access ->
            val cut = admission(access)
            val references = retainedReferences().filter { it.kind == CompanyBusinessPublicationKind.PRICE_POLICY_V1 }
            val vacant = journal.requests.withLock { required(journal.pending()).isEmpty() }
            val refreshed = currentAccess(access.lease)
            refreshed.requireAction(LocalAction.PRICE_POLICY_RECEIVE, refreshed.view.scope.branchId)
            refreshed.checkCurrent()
            LocalPricePolicyPublicationStatusResult.Available(LocalPricePolicyPublicationStatus(references,
                vacant && installed.has(LocalPricingProfile.COMMAND), cut.reply.observedAt))
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PricePolicyPublicationFailure) { LocalPricePolicyPublicationStatusResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalPricePolicyPublicationStatusResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalPricePolicyPublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun prepare(draft: LocalPricePolicyPublicationDraft): LocalPricePolicyPublicationResult = actions.withLock {
        publicationResult { access ->
            admission(access)
            access.requireAction(LocalAction.PRICE_POLICY_RECEIVE, draft.publicationId)
            val proposed = definition(draft)
            val reference = retainedReferences().singleOrNull { it.publicationId == draft.publicationId }
            if (reference != null) {
                if (reference.kind != CompanyBusinessPublicationKind.PRICE_POLICY_V1 || reference.entityId != draft.entryId) invalid()
                val original = retainedPublication(reference)
                if (original.first != proposed) invalid()
                return@publicationResult original.second
            }
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() })
                throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            access.checkCurrent()
            val action = native(publications.prepare(proposed.toNativeCompanyPricePolicyProposal()))
            val exact = reviewDefinition(action)
            if (exact != proposed) unavailable()
            LocalPricePolicyPublicationResult.Review(LocalPricePolicyPublicationReview(action, preview(exact)))
        }
    }

    override suspend fun review(originalActionId: DomainId): LocalPricePolicyPublicationResult = actions.withLock {
        publicationResult { access ->
            admission(access)
            val reference = retainedReferences().singleOrNull {
                it.originalActionId == originalActionId && it.kind == CompanyBusinessPublicationKind.PRICE_POLICY_V1
            } ?: unavailable()
            access.requireAction(LocalAction.PRICE_POLICY_RECEIVE, reference.publicationId)
            retainedPublication(reference).second
        }
    }

    override suspend fun approve(review: LocalPricePolicyPublicationReview, headLogin: String,
        headSecret: CharArray): LocalPricePolicyPublicationResult {
        try { return actions.withLock {
            publicationResult { access ->
                admission(access)
                val exact = reviewDefinition(review.action)
                access.requireAction(LocalAction.PRICE_POLICY_RECEIVE, exact.publicationId)
                if (review.preview.companyId != exact.company.companyId || definition(review.preview.definition) != exact) invalid()
                access.checkCurrent()
                val output = native(publications.approve(review.action, headLogin, headSecret))
                val signed = completedOutput(output, review.action.originalActionId)
                if (signed.definition != exact || signed.signingKeyId != review.action.signingKeyId) unavailable()
                LocalPricePolicyPublicationResult.Completed(completedView(output, signed))
            }
        } } finally { headSecret.fill('\u0000') }
    }

    override suspend fun completed(originalActionId: DomainId): LocalPricePolicyPublicationResult = actions.withLock {
        publicationResult { access ->
            admission(access)
            val output = native(publications.recover(originalActionId))
            val signed = completedOutput(output, originalActionId)
            access.requireAction(LocalAction.PRICE_POLICY_RECEIVE, signed.definition.publicationId)
            LocalPricePolicyPublicationResult.Completed(completedView(output, signed))
        }
    }

    override suspend fun stageReceipt(originalActionId: DomainId): LocalStageResult = actions.withLock {
        val operationId = OperationId(originalActionId.value)
        try { withAccess { access ->
            // Rejoin the saved original before any native recovery or fresh staging.
            val snapshot = journal.requests.withLock { originalEntry(operationId) }
            access.checkCurrent()
            val output = native(publications.recover(originalActionId))
            val signed = completedOutput(output, originalActionId)
            journal.requests.withLock stage@{
                val existing = originalEntry(operationId)
                if (existing != null) {
                    if (snapshot != null && !snapshot.sameIntent(existing)) return@stage LocalStageResult.Uncertain(operationId)
                    if (LocalPricingCodec.decode(existing.request().payload) != LocalPricingRequestV1.ReceivePolicy(signed))
                        return@stage LocalStageResult.Uncertain(operationId)
                    val refreshed = currentAccess(access.lease)
                    refreshed.checkCurrent()
                    return@stage LocalStageResult.Staged(pending(existing, refreshed.canContinue(existing)))
                }
                if (snapshot != null) return@stage LocalStageResult.Uncertain(operationId)
                stageFresh(access, operationId, signed)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LocalStageResult.Uncertain(operationId) }
    }

    /** Under the journal mutex, after definite original absence. Native recovery already finished. */
    private suspend fun stageFresh(access: GatewayAccess, operationId: OperationId,
        signed: LocalSignedPricePolicyEntryV1): LocalStageResult {
        var writeStarted = false
        try {
            when (val unresolved = journal.pending()) {
                JournalRead.Unavailable -> return LocalStageResult.Uncertain(operationId)
                is JournalRead.Value -> if (unresolved.value.isNotEmpty())
                    return LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
            }
            if (!installed.has(LocalPricingProfile.COMMAND)) return LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
            val value = signed.definition
            access.requireAction(LocalAction.PRICE_POLICY_RECEIVE, value.publicationId)
            val request = MutationRequest(operationId, LocalPricingProfile.COMMAND,
                LocalPricingCodec.encode(LocalPricingRequestV1.ReceivePolicy(signed)))
            val intent = LocalCommandIntent(operationId, LocalAction.PRICE_POLICY_RECEIVE, 1,
                LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, value.publicationId), listOf(
                    LocalScalarInput(LocalField.POLICY_ID, LocalScalarValue.Identifier(value.entryId)),
                    LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(value.skuId)),
                    LocalScalarInput(LocalField.PRICE_RULE_ID, LocalScalarValue.Identifier(value.ruleId)),
                    LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(value.publicationId)),
                    LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))
            val entry = StoredClientIntentV1.staged(journal.owner, intent, request,
                text("Receive company-signed price policy", "استلام سياسة سعر موقعة من الشركة"), summary(value))
            // Same credential lease, freshly authoritative employee hints; no account rebinding.
            val refreshed = currentAccess(access.lease)
            refreshed.requireAction(LocalAction.PRICE_POLICY_RECEIVE, value.publicationId)
            refreshed.checkCurrent()
            writeStarted = true
            return when (journal.stage(entry)) {
                JournalWrite.DURABLE -> {
                    val released = currentAccess(access.lease)
                    released.checkCurrent()
                    LocalStageResult.Staged(pending(entry, released.canContinue(entry)))
                }
                JournalWrite.NO_CAPACITY -> LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(operationId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(failure.problem)
        } catch (_: IllegalArgumentException) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
        } catch (_: Exception) { return LocalStageResult.Uncertain(operationId) }
    }

    private suspend fun originalEntry(operationId: OperationId): StoredClientIntentV1? = required(journal.find(operationId))?.also {
        it.validate(journal.owner)
        require(it.operationId == operationId && it.localAction == LocalAction.PRICE_POLICY_RECEIVE &&
            it.commandName == LocalPricingProfile.COMMAND.name && it.commandVersion == LocalPricingProfile.COMMAND.version &&
            it.actionApprovalId == null && LocalPricingCodec.decode(it.request().payload) is LocalPricingRequestV1.ReceivePolicy)
    }

    private suspend fun retainedPublication(reference: CompanyBusinessPublicationReference): Pair<LocalPricePolicyEntryV1, LocalPricePolicyPublicationResult> {
        val original = when (reference.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> {
                val action = native(publications.review(reference.originalActionId))
                val exact = reviewDefinition(action)
                if (action.originalActionId != reference.originalActionId) unavailable()
                exact to LocalPricePolicyPublicationResult.Review(LocalPricePolicyPublicationReview(action, preview(exact)))
            }
            CompanyBusinessPublicationPhase.COMPLETED -> {
                val output = native(publications.recover(reference.originalActionId))
                val signed = completedOutput(output, reference.originalActionId)
                signed.definition to LocalPricePolicyPublicationResult.Completed(completedView(output, signed))
            }
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw PricePolicyPublicationFailure(
                LocalServiceProblem.STORAGE_UNCERTAIN, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (reference.kind != CompanyBusinessPublicationKind.PRICE_POLICY_V1 || original.first.publicationId != reference.publicationId ||
            original.first.entryId != reference.entityId) unavailable()
        return original
    }

    private suspend fun retainedReferences(): List<CompanyBusinessPublicationReference> {
        val references = native(publications.retained())
        if (references.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS ||
            references.map { it.originalActionId }.distinct().size != references.size ||
            references.map { it.publicationId }.distinct().size != references.size) unavailable()
        return references
    }

    private fun reviewDefinition(action: CompanyBusinessPublicationAction): LocalPricePolicyEntryV1 =
        action.readNativeCompanyPricePolicyDefinition().also {
            if (it.company != journal.owner.scope.company() || action.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        }
    private fun completedOutput(output: CompanyBusinessPublicationOutput, originalActionId: DomainId): LocalSignedPricePolicyEntryV1 {
        val signed = output.readNativeCompanyPricePolicy()
        val bytes = LocalPricingCodec.canonicalPolicy(signed.definition)
        try {
            if (output.originalActionId != originalActionId || signed.definition.company != journal.owner.scope.company() ||
                output.rootFingerprint != publications.trust.rootFingerprint || output.proposal.contentDigest != sha256(bytes) ||
                !publications.trust.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) unavailable()
            return signed
        } finally { bytes.fill(0) }
    }
    private fun completedView(output: CompanyBusinessPublicationOutput, signed: LocalSignedPricePolicyEntryV1): LocalPricePolicyPublicationCompleted =
        LocalPricePolicyPublicationCompleted(output.originalActionId, preview(signed.definition), output.rootFingerprint)
    private fun definition(draft: LocalPricePolicyPublicationDraft): LocalPricePolicyEntryV1 = LocalPricePolicyEntryV1(
        journal.owner.scope.company(), draft.entryId, draft.publicationId, draft.revision, draft.skuId, draft.ruleId,
        draft.branchOverridesEnabled, draft.authorizationReferenceQuantity, draft.effectiveFrom, draft.effectiveUntil)
    private fun preview(value: LocalPricePolicyEntryV1): LocalPricePolicyPublicationPreview = LocalPricePolicyPublicationPreview(
        value.company.companyId, LocalPricePolicyPublicationDraft(value.publicationId, value.entryId, value.revision, value.skuId,
            value.ruleId, value.branchOverridesEnabled, value.authorizationReferenceQuantity, value.effectiveFrom, value.effectiveUntil))
    private fun summary(value: LocalPricePolicyEntryV1): List<LocalRecordValue> = listOf(
        recordValue(text("Company publication / policy revision", "إصدار الشركة ومراجعة السياسة"),
            literal("${value.publicationId.value} / ${value.revision.value}")),
        recordValue(text("SKU / price rule", "الصنف وقاعدة السعر"), literal("${value.skuId.value} / ${value.ruleId.value}")),
        recordValue(text("Authority separation", "فصل الصلاحيات"), text(
            "Signing is complete. Receipt does not activate this policy, change a price or post stock/payment. Branch activation is a separate protected command.",
            "اكتمل التوقيع. الاستلام لا يفعّل السياسة ولا يغير السعر أو المخزون أو المدفوعات. التفعيل في الفرع طلب محمي منفصل.")))

    private suspend fun admission(access: GatewayAccess): PricingCut {
        access.requireAction(LocalAction.PRICE_POLICY_RECEIVE, access.view.scope.branchId)
        return pricingRead(access, LocalPricingQueryV1.IndexStatus)
    }
    private suspend fun publicationResult(body: suspend (GatewayAccess) -> LocalPricePolicyPublicationResult): LocalPricePolicyPublicationResult {
        try { return withAccess { access ->
            val result = body(access)
            val publicationId = when (result) {
                is LocalPricePolicyPublicationResult.Review -> result.review.preview.definition.publicationId
                is LocalPricePolicyPublicationResult.Completed -> result.publication.preview.definition.publicationId
                is LocalPricePolicyPublicationResult.Unavailable -> null
            }
            // A native signing/recovery round trip may outlast a grant change. Refresh
            // the same session before releasing its publication preview to this client.
            if (publicationId != null) currentAccess(access.lease).requireAction(LocalAction.PRICE_POLICY_RECEIVE, publicationId)
            result
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PricePolicyPublicationFailure) { return LocalPricePolicyPublicationResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { return LocalPricePolicyPublicationResult.Unavailable(failure.problem) }
        catch (_: IllegalArgumentException) { return LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: Exception) { return LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
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
        val view = when (val current = endpoint.current(lease.credentials)) {
            is LocalSessionViewResult.Current -> current.view
            LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        if (!lease.isCurrent() || view.actorId != journal.owner.actorId || view.scope != journal.owner.scope ||
            view.deviceId != journal.owner.deviceId) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return GatewayAccess(endpoint, lease, view, installed)
    }
    private fun <T> native(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> throw PricePolicyPublicationFailure(when (result.nativeReason) {
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
private class PricePolicyPublicationFailure(val problem: LocalServiceProblem,
    val nativeReason: SelfOwnedInstallationFailureV1?) : Exception()
