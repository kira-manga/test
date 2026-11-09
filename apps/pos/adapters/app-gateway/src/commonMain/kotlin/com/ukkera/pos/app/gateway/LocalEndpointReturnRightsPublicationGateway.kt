package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Real original company signing, followed by a separate immutable employee activation intent. */
public class LocalEndpointReturnRightsPublicationGateway(
    private val publications: LocalCompanyBusinessPublications,
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
) : LocalReturnRightsPublicationGateway {
    // Native signing/recovery never runs under the ordinary employee journal mutex.
    private val actions = Mutex()
    override val configured: Boolean = true

    init { require(publications.trust.company == journal.owner.scope.company()) }

    override suspend fun status(): LocalReturnRightsPublicationStatusResult = actions.withLock {
        try { withAccess { access ->
            val cut = policyRead(access)
            val references = retainedReferences().filter { it.kind == CompanyBusinessPublicationKind.RETURN_RIGHTS_V1 }
            val vacant = journal.requests.withLock { required(journal.pending()).isEmpty() }
            LocalReturnRightsPublicationStatusResult.Available(LocalReturnRightsPublicationStatus(references,
                vacant && installed.has(LocalReturnRightsActivationProfileV1.ACTIVATE) &&
                    access.allow(LocalAction.RETURN_RIGHTS_ACTIVATE, access.view.scope.branchId), cut.reply.observedAt))
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ReturnRightsPublicationFailure) {
            LocalReturnRightsPublicationStatusResult.Unavailable(failure.problem, failure.nativeReason)
        }
        catch (failure: GatewayFailure) { LocalReturnRightsPublicationStatusResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalReturnRightsPublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun prepare(draft: LocalReturnRightsPublicationDraft): LocalReturnRightsPublicationResult = actions.withLock {
        publicationResult { access ->
            policyRead(access)
            val proposed = definition(draft)
            val retained = retainedReferences().singleOrNull { it.publicationId == draft.publicationId }
            if (retained != null) {
                if (retained.kind != CompanyBusinessPublicationKind.RETURN_RIGHTS_V1 || retained.entityId != draft.policyId) invalid()
                val original = retainedPublication(retained)
                // Draft/preview classes do not supply value identity. Compare the complete
                // mapped documentary definition, including the explicit original company revision.
                if (proposed != original.definition) invalid()
                return@publicationResult original.result
            }
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() })
                throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            requireEnvelopeCapacity(proposed)
            access.checkCurrent()
            val reviewed = reviewedPublication(native(publications.prepare(proposed.toNativeCompanyReturnRightsProposal())))
            if (reviewed.definition != proposed) unavailable()
            reviewed.result
        }
    }

    override suspend fun review(originalActionId: DomainId): LocalReturnRightsPublicationResult = actions.withLock {
        publicationResult { access ->
            policyRead(access)
            val reference = retainedReferences().singleOrNull {
                it.originalActionId == originalActionId && it.kind == CompanyBusinessPublicationKind.RETURN_RIGHTS_V1
            } ?: unavailable()
            retainedPublication(reference).result
        }
    }

    override suspend fun approve(review: LocalReturnRightsPublicationReview, headLogin: String,
        headSecret: CharArray): LocalReturnRightsPublicationResult {
        try { return actions.withLock {
            publicationResult { access ->
                policyRead(access)
                val exact = reviewDefinition(review.action)
                if (review.preview.companyId != exact.company.companyId || definition(review.preview.definition) != exact) invalid()
                access.checkCurrent()
                // Only the actual native owner authenticates the original company head.
                // Employee permission, the preview and a UI confirmation are not signing authority.
                val completed = completedOutput(native(publications.approve(review.action, headLogin, headSecret)),
                    review.action.originalActionId)
                if (completed.signed.definition != exact || completed.signed.signingKeyId != review.action.signingKeyId) unavailable()
                LocalReturnRightsPublicationResult.Completed(completedView(completed))
            }
        } } finally { headSecret.fill('\u0000') }
    }

    override suspend fun completed(originalActionId: DomainId): LocalReturnRightsPublicationResult = actions.withLock {
        publicationResult { access ->
            policyRead(access)
            LocalReturnRightsPublicationResult.Completed(completedView(
                completedOutput(native(publications.recover(originalActionId)), originalActionId)))
        }
    }

    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = actions.withLock {
        val operationId = OperationId(originalActionId.value)
        try { withAccess { access ->
            // Inspect original custody before ANY fresh CURRENT/CAS read. Native output must
            // still be authenticated on both paths, but never while holding journal.requests.
            val snapshot = journal.requests.withLock { originalEntry(operationId) }
            access.checkCurrent()
            val output = completedOutput(native(publications.recover(originalActionId)), originalActionId)
            val captured = if (snapshot == null) captureActiveRevision(access) else null
            journal.requests.withLock stage@{
                val existing = originalEntry(operationId)
                if (existing != null) {
                    if (snapshot != null && !snapshot.sameIntent(existing)) return@stage LocalStageResult.Uncertain(operationId)
                    joinOriginalOutput(existing, output)
                    access.checkCurrent()
                    // A concurrent original wins even if a fresh CAS was read meanwhile.
                    // Never compare it to a regenerated request containing that new CAS.
                    return@stage LocalStageResult.Staged(pending(existing, access.canContinue(existing)))
                }
                if (snapshot != null) return@stage LocalStageResult.Uncertain(operationId)
                when (captured) {
                    is ActiveRevisionCapture.Blocked -> LocalStageResult.NotStaged(captured.problem)
                    is ActiveRevisionCapture.Captured -> stageFresh(access, operationId, output, captured.revision)
                    ActiveRevisionCapture.Uncertain, null -> LocalStageResult.Uncertain(operationId)
                }
            }
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        // Without a final definite absence/no-write proof, preserve the original operation.
        catch (_: Exception) { LocalStageResult.Uncertain(operationId) }
    }

    /** Called only under journal.requests after the final original lookup established absence. */
    private suspend fun stageFresh(access: GatewayAccess, operationId: OperationId,
        output: ExactReturnRightsOutput, expectedRevision: Revision): LocalStageResult {
        var writeStarted = false
        try {
            when (val unresolved = journal.pending()) {
                JournalRead.Unavailable -> return LocalStageResult.Uncertain(operationId)
                is JournalRead.Value -> if (unresolved.value.isNotEmpty()) return LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
            }
            if (!installed.has(LocalReturnRightsActivationProfileV1.ACTIVATE))
                return LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
            access.requireAction(LocalAction.RETURN_RIGHTS_ACTIVATE, access.view.scope.branchId)
            val request = LocalReturnRightsActivationCodecV1.activate(operationId,
                LocalActivateReturnRightsV1(expectedRevision, output.envelope))
            val entry = StoredClientIntentV1.staged(journal.owner, activationIntent(request, output, expectedRevision), request,
                text("Activate company-signed documentary return-rights policy", "تفعيل سياسة حقوق مرتجعات وثائقية موقعة من الشركة"),
                activationSummary(output, expectedRevision))
            access.checkCurrent()
            // Refresh employee authority on the SAME lease at the final no-original/write
            // boundary. This never recaptures the active pointer or changes the saved CAS.
            val refreshed = currentAccess(access.lease)
            refreshed.requireAction(LocalAction.RETURN_RIGHTS_ACTIVATE, refreshed.view.scope.branchId)
            refreshed.checkCurrent()
            writeStarted = true
            return when (journal.stage(entry)) {
                JournalWrite.DURABLE -> {
                    refreshed.checkCurrent()
                    LocalStageResult.Staged(pending(entry, refreshed.canContinue(entry)))
                }
                // This is a definite no-write result, not an ordinary unavailable transport.
                JournalWrite.NO_CAPACITY -> LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(operationId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(failure.problem)
        }
        catch (_: IllegalArgumentException) {
            return if (writeStarted) LocalStageResult.Uncertain(operationId) else LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
        }
        catch (_: Exception) { return LocalStageResult.Uncertain(operationId) }
    }

    private suspend fun captureActiveRevision(access: GatewayAccess): ActiveRevisionCapture {
        try {
            if (!installed.has(LocalReturnRightsActivationProfileV1.ACTIVATE))
                throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            val cut = policyRead(access)
            val revision = when (cut.result.status) {
                LocalReturnRightsReadStatusV1.ABSENT -> Revision(0)
                LocalReturnRightsReadStatusV1.PUBLISHED -> cut.result.activation?.activeRevision ?: unavailable()
                LocalReturnRightsReadStatusV1.SOURCE_UNAVAILABLE -> unavailable()
            }
            if (revision.value == Long.MAX_VALUE) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            return ActiveRevisionCapture.Captured(revision)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { return ActiveRevisionCapture.Blocked(failure.problem) }
        catch (_: Exception) { return ActiveRevisionCapture.Uncertain }
    }

    private suspend fun originalEntry(operationId: OperationId): StoredClientIntentV1? = required(journal.find(operationId))?.also {
        it.validate(journal.owner)
        require(it.operationId == operationId && it.localAction == LocalAction.RETURN_RIGHTS_ACTIVATE &&
            it.commandName == LocalReturnRightsActivationProfileV1.ACTIVATE.name &&
            it.commandVersion == LocalReturnRightsActivationProfileV1.ACTIVATE.version && it.actionApprovalId == null)
    }

    private fun joinOriginalOutput(entry: StoredClientIntentV1, output: ExactReturnRightsOutput) {
        require(entry.operationId.value == output.output.originalActionId.value)
        val saved = LocalReturnRightsActivationCodecV1.decodeActivate(entry.request().payload)
        // Decode/compare only. The saved expected revision, payload and input/semantic identity
        // remain untouched; exact native content is not permission to stage another request.
        require(saved.signedPublication == output.envelope)
    }

    private suspend fun retainedReferences(): List<CompanyBusinessPublicationReference> {
        val references = native(publications.retained())
        if (references.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS ||
            references.map { it.originalActionId }.distinct().size != references.size ||
            references.map { it.publicationId }.distinct().size != references.size) unavailable()
        return references
    }

    private suspend fun retainedPublication(reference: CompanyBusinessPublicationReference): RetainedReturnRightsPublication {
        val original = when (reference.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> reviewedPublication(native(publications.review(reference.originalActionId)))
            CompanyBusinessPublicationPhase.COMPLETED -> {
                val output = completedOutput(native(publications.recover(reference.originalActionId)), reference.originalActionId)
                RetainedReturnRightsPublication(output.signed.definition, LocalReturnRightsPublicationResult.Completed(completedView(output)))
            }
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw ReturnRightsPublicationFailure(LocalServiceProblem.STORAGE_UNCERTAIN,
                SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val id = when (val result = original.result) {
            is LocalReturnRightsPublicationResult.Review -> result.review.action.originalActionId
            is LocalReturnRightsPublicationResult.Completed -> result.publication.originalActionId
            is LocalReturnRightsPublicationResult.Unavailable -> unavailable()
        }
        if (reference.kind != CompanyBusinessPublicationKind.RETURN_RIGHTS_V1 || id != reference.originalActionId ||
            original.definition.publicationId != reference.publicationId || original.definition.policyId != reference.entityId) unavailable()
        return original
    }

    private fun reviewedPublication(action: CompanyBusinessPublicationAction): RetainedReturnRightsPublication {
        val exact = reviewDefinition(action)
        return RetainedReturnRightsPublication(exact,
            LocalReturnRightsPublicationResult.Review(LocalReturnRightsPublicationReview(action, preview(exact))))
    }

    private fun reviewDefinition(action: CompanyBusinessPublicationAction): LocalPublishedReturnRightsPolicy {
        val exact = try { action.readNativeCompanyReturnRightsPolicyDefinition() }
            catch (_: IllegalArgumentException) { unavailable() }
        if (exact.company != journal.owner.scope.company() || action.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        return exact
    }

    private fun completedOutput(output: CompanyBusinessPublicationOutput, originalActionId: DomainId): ExactReturnRightsOutput {
        try {
            val signed = output.readNativeCompanyReturnRightsPolicy()
            val content = LocalReturnRightsPublicationCodec.canonicalPublication(signed.definition)
            try {
                val digest = sha256(content)
                if (output.originalActionId != originalActionId || signed.definition.company != journal.owner.scope.company() ||
                    output.rootFingerprint != publications.trust.rootFingerprint || output.proposal.contentDigest != digest ||
                    !publications.trust.verify(signed.definition.company, signed.signingKeyId, content, signed.signatureHex)) unavailable()
                return ExactReturnRightsOutput(output, signed, LocalReturnRightsPublicationCodec.signedPublication(signed), digest)
            } finally { content.fill(0) }
        } catch (_: IllegalArgumentException) { unavailable() }
    }

    private fun completedView(output: ExactReturnRightsOutput): LocalReturnRightsPublicationCompleted =
        LocalReturnRightsPublicationCompleted(output.output.originalActionId, preview(output.signed.definition), output.output.rootFingerprint)

    private fun definition(draft: LocalReturnRightsPublicationDraft): LocalPublishedReturnRightsPolicy = LocalPublishedReturnRightsPolicy(
        journal.owner.scope.company(), draft.publicationId, draft.policyId, draft.companyRevision, draft.jurisdictionCode,
        draft.legalSourceUri, draft.legalSourceAccessDate, draft.rules.map {
            LocalReturnRightsRule(it.categoryCode, LocalReturnRightsReason.valueOf(it.reason.name),
                LocalReturnRightsTreatment.valueOf(it.treatment.name), it.receiptWindowDays, it.coveredResolutionDays,
                it.exceptionBasis?.let { basis -> LocalReturnRightsExceptionBasis.valueOf(basis.name) })
        }, draft.continuingRights.english, draft.continuingRights.arabic)

    private fun preview(value: LocalPublishedReturnRightsPolicy): LocalReturnRightsPublicationPreview = LocalReturnRightsPublicationPreview(
        value.company.companyId, LocalReturnRightsPublicationDraft(value.publicationId, value.policyId, value.revision,
            value.jurisdictionCode, value.legalSourceUri, value.legalSourceAccessDate,
            text(value.continuingRightsEnglish, value.continuingRightsArabic), value.rules.map {
                LocalReturnRightsPublicationRule(it.categoryCode, LocalReturnRightsPublicationReason.valueOf(it.reason.name),
                    LocalReturnRightsPublicationTreatment.valueOf(it.treatment.name), it.receiptWindowDays, it.coveredResolutionDays,
                    it.exceptionBasis?.let { basis -> LocalReturnRightsPublicationException.valueOf(basis.name) })
            }))

    /** Exact V1 envelope framing budget; no placeholder signature or chosen key is created. */
    private fun requireEnvelopeCapacity(value: LocalPublishedReturnRightsPolicy) {
        val content = LocalReturnRightsPublicationCodec.canonicalPublication(value)
        try {
            val definitionSize = content.size - CompanyBusinessPublicationKind.RETURN_RIGHTS_V1.domain.length
            val framing = "{\"definition\":".length + ",\"signingKeyId\":\"".length + 36 +
                "\",\"signatureHex\":\"".length + 128 + "\"}".length
            require(definitionSize + framing <= CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
        } finally { content.fill(0) }
    }

    private fun activationIntent(request: MutationRequest, output: ExactReturnRightsOutput,
        expectedRevision: Revision): LocalCommandIntent = LocalCommandIntent(request.operationId, LocalAction.RETURN_RIGHTS_ACTIVATE, 1,
        LocalRecordReference(LocalRecordKind.RETURN_RIGHTS_PUBLICATION, output.signed.definition.publicationId), listOf(
            LocalScalarInput(LocalField.POLICY_ID, LocalScalarValue.Identifier(output.signed.definition.policyId)),
            LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(output.signed.definition.publicationId)),
            LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Text(output.digest.value)),
            LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(expectedRevision.value)),
            LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))

    private fun activationSummary(output: ExactReturnRightsOutput, expectedRevision: Revision): List<LocalRecordValue> = listOf(
        recordValue(text("Policy / explicit company revision", "السياسة ومراجعة الشركة الصريحة"),
            literal("${output.signed.definition.policyId.value} / ${output.signed.definition.revision.value}")),
        recordValue(text("Original company publication", "إصدار الشركة الأصلي"), literal(output.signed.definition.publicationId.value)),
        recordValue(text("Exact documentary content SHA-256", "بصمة المحتوى الوثائقي الدقيق SHA-256"), literal(output.digest.value)),
        recordValue(text("Original expected local active revision", "مراجعة التفعيل المحلية المتوقعة الأصلية"), literal(expectedRevision.value.toString())),
        recordValue(text("Authority separation", "فصل الصلاحيات"), text(
            "Company signing is complete. This separate employee request stages documentary activation at this branch only. It does not qualify a return, prove customer receipt or authorize a refund; no stock or money changes here.",
            "اكتمل توقيع الشركة. يحفظ طلب الموظف المنفصل طلب التفعيل الوثائقي في هذا الفرع فقط. لا يثبت أهلية مرتجع أو استلام العميل ولا يخول رد الأموال؛ لا يتغير المخزون أو النقد هنا.")))

    private data class PolicyCut(val result: LocalReturnRightsReadResultV1, val reply: ReadReply.Data)
    private suspend fun policyRead(access: GatewayAccess): PolicyCut {
        access.requireAction(LocalAction.RETURN_RIGHTS_ACTIVATE, access.view.scope.branchId)
        val reply = access.read(LocalReturnRightsActivationCodecV1.readCurrent())
        val result = try { LocalReturnRightsActivationCodecV1.decodeRead(reply.payload) }
            catch (_: IllegalArgumentException) { unavailable() }
        val activation = result.activation
        if (activation != null && (activation.scope != access.view.scope || activation.reference.company != journal.owner.scope.company())) unavailable()
        return PolicyCut(result, reply)
    }

    private suspend fun publicationResult(body: suspend (GatewayAccess) -> LocalReturnRightsPublicationResult): LocalReturnRightsPublicationResult {
        try { return withAccess(body) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ReturnRightsPublicationFailure) { return LocalReturnRightsPublicationResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { return LocalReturnRightsPublicationResult.Unavailable(failure.problem) }
        catch (_: IllegalArgumentException) { return LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: Exception) { return LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
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

    /** Refresh identity/policy only; this lease's owner and lifetime never change. */
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
        is LocalCompanyAuthorityResult.Unavailable -> throw ReturnRightsPublicationFailure(when (result.nativeReason) {
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

private data class RetainedReturnRightsPublication(val definition: LocalPublishedReturnRightsPolicy, val result: LocalReturnRightsPublicationResult)
private data class ExactReturnRightsOutput(val output: CompanyBusinessPublicationOutput,
    val signed: LocalSignedReturnRightsPolicy, val envelope: String, val digest: PayloadDigest)
private sealed interface ActiveRevisionCapture {
    data class Captured(val revision: Revision) : ActiveRevisionCapture
    data class Blocked(val problem: LocalServiceProblem) : ActiveRevisionCapture
    data object Uncertain : ActiveRevisionCapture
}
private class ReturnRightsPublicationFailure(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1?) : Exception()
