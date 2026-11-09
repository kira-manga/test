package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterSerialPublications
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Private serial requests use the actual Master custody companion; native-head signing
 * remains a separate authority and never inherits branch activation permission.
 * No caller-selected issuer, signing key or accepting proof.
 */
internal class LocalEndpointSerialRegistrationPublicationGateway(
    private val publications: LocalCompanyBusinessPublications,
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
    private val serial: LocalMasterSerialPublications,
) : LocalSerialRegistrationPublicationGateway {
    private val actions = Mutex()
    // Correlation only. Master/native always authenticate the original and current actor.
    private val originIds = mutableMapOf<DomainId, DomainId>()
    override val configured = true
    init { require(publications.trust.company == journal.owner.scope.company()) }

    override suspend fun status(registrationId: DomainId?, skuId: DomainId?): LocalSerialPublicationStatusResult = actions.withLock {
        try { withAccess { access ->
            require((registrationId == null) == (skuId == null))
            val references = references(access)
            val source = if (registrationId == null || !access.allow(LocalAction.SERIAL_REGISTRATION_ACTIVATE, registrationId)) null else {
                access.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, registrationId)
                val cut = cut()
                val previous = history(cut, registrationId).lastOrNull()
                val sku = currentSku(access, cut, requireNotNull(skuId))
                if (previous != null && previous.skuId != skuId) invalid()
                if (cut().generation != cut.generation) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                LocalSerialRegistrationAuthoringSource(registrationId, skuId, sku.definition.publicationId,
                    skuDigest(sku), previous?.let(LocalSerialRegistrationPublicationCodecV1::reference), previous,
                    if (previous?.revision?.value == Long.MAX_VALUE - 1) null else previous?.revision?.next() ?: Revision(1))
            }
            val fresh = current(access.lease)
            references.forEach { requireRecoveryHint(fresh, it.originalActionId) }
            source?.let { fresh.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, it.registrationId) }
            fresh.checkCurrent()
            LocalSerialPublicationStatusResult.Available(LocalSerialPublicationStatus(references, source))
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalSerialPublicationStatusResult.Unavailable(failure.problem) }
        catch (_: IllegalArgumentException) { LocalSerialPublicationStatusResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: Exception) { LocalSerialPublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun prepareAuthority(): LocalSerialPublicationResult = actions.withLock {
        result { access ->
            access.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE)
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() }) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            val action = native(serial.prepareSerialAuthority(access.lease.credentials, DomainId.random()))
            val preview = preview(action)
            LocalSerialPublicationResult.Review(LocalSerialPublicationReview(action, preview))
        }
    }

    override suspend fun prepare(draft: LocalSerialRegistrationDraft): LocalSerialPublicationResult = actions.withLock {
        var unsignedInputMayChange = false
        val prepared = result { access ->
            access.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, draft.registrationId)
            // Recovered completed originals never rebind allocation, predecessor, SKU or validity.
            val reference = references(access).singleOrNull { it.publicationId == draft.publicationId }
            if (reference != null) {
                val original = retained(access, reference)
                val definition = registrationPreview(original)
                if (!matches(draft, definition)) invalid()
                if (original is LocalSerialPublicationResult.Review) validateFresh(access, definition, cut())
                return@result original
            }
            unsignedInputMayChange = true
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() }) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            val allocation = native(publications.originalSerialRegistrationAllocation())
            val definition = LocalPublishedSerialRegistrationV1(allocation.scope, allocation.allocationId,
                allocation.allocationDigest, allocation.serialAuthority.reference, draft.publicationId, draft.registrationId,
                draft.revision, draft.previous, draft.serialId, draft.serialCode, draft.skuId, draft.skuPublicationId,
                draft.skuPublicationDigest, draft.validFrom, draft.validUntil)
            validateFresh(access, definition, cut())
            current(access.lease).requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, definition.registrationId)
            unsignedInputMayChange = false
            val action = native(serial.prepareSerialPublication(access.lease.credentials, DomainId.random(), makeNativeSerialRegistrationProposal(definition)))
            val exact = preview(action)
            if (exact != LocalSerialPublicationPreview.Registration(definition)) unavailable()
            LocalSerialPublicationResult.Review(LocalSerialPublicationReview(action, exact))
        }
        if (prepared is LocalSerialPublicationResult.Unavailable && prepared.problem == LocalServiceProblem.INVALID_INPUT && unsignedInputMayChange)
            prepared.copy(unsignedInputMayChange = true) else prepared
    }

    override suspend fun review(originalActionId: DomainId): LocalSerialPublicationResult = actions.withLock {
        result(requireActivation = false) { access ->
            val reference = references(access).singleOrNull { it.originalActionId == originalActionId } ?: unavailable()
            requireRecoveryHint(access, originalActionId)
            retained(access, reference)
        }
    }

    override suspend fun approve(review: LocalSerialPublicationReview, login: String, secret: CharArray): LocalSerialPublicationResult {
        try { return actions.withLock {
            try {
                currentCoroutineContext().ensureActive()
                val exact = preview(review.action)
                if (exact != review.preview) invalid()
                // Actual named-head authentication and exact retained-action checks are native.
                // Requester catalog revocation cannot revoke this independent signing authority.
                val output = native(publications.approve(review.action, login, secret))
                val completed = completed(output)
                if (output.originalActionId != review.action.originalActionId || completed.publication.preview != exact) unavailable()
                completed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SerialPublicationFailure) { LocalSerialPublicationResult.Unavailable(failure.problem, failure.reason) }
            catch (failure: GatewayFailure) { LocalSerialPublicationResult.Unavailable(failure.problem) }
            catch (_: IllegalArgumentException) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
            catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
        } } finally { secret.fill('\u0000') }
    }

    override suspend fun completed(originalActionId: DomainId): LocalSerialPublicationResult = actions.withLock {
        result(requireActivation = false) { access ->
            references(access).singleOrNull { it.originalActionId == originalActionId } ?: unavailable()
            requireRecoveryHint(access, originalActionId)
            val output = native(serial.recoverSerialPublication(access.lease.credentials, DomainId.random(), originId(originalActionId), originalActionId))
            if (output.originalActionId != originalActionId) unavailable()
            completed(output)
        }
    }

    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = actions.withLock {
        val operationId = OperationId(originalActionId.value)
        var writeStarted = false
        try { withAccess { access ->
            val snapshot = journal.requests.withLock { originalEntry(operationId) }
            val reference = references(access).singleOrNull { it.originalActionId == originalActionId && it.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 } ?: unavailable()
            access.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, reference.entityId)
            val output = native(serial.recoverSerialPublication(access.lease.credentials, DomainId.random(), originId(originalActionId), originalActionId))
            if (output.originalActionId != originalActionId) unavailable()
            completed(output)
            val signed = output.readNativeSerialRegistration()
            val definition = signed.definition
            journal.requests.withLock stage@{
                val existing = originalEntry(operationId)
                if (existing != null) {
                    if (snapshot != null && !snapshot.sameIntent(existing) ||
                        LocalSerialRegistrationCodecV1.decode(existing.request().payload).publication != signed)
                        return@stage LocalStageResult.Uncertain(operationId)
                    val fresh = current(access.lease)
                    fresh.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, definition.registrationId)
                    fresh.checkCurrent()
                    return@stage LocalStageResult.Staged(pending(existing, fresh.canContinue(existing)))
                }
                if (snapshot != null) return@stage LocalStageResult.Uncertain(operationId)
                if (required(journal.pending()).isNotEmpty()) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                val native = cut()
                if (history(native, definition.registrationId).lastOrNull() != definition ||
                    native.registrations.singleOrNull { it.definition == definition } != signed) invalid()
                val reply = access.read(LocalSerialRegistrationCodecV1.query(LocalSerialRegistrationQueryV1(definition.registrationId)))
                val active = LocalSerialRegistrationCodecV1.decodeRead(reply.payload)
                if (active.registrationId != definition.registrationId || active.active?.definition?.revision?.let { it >= definition.revision } == true) invalid()
                active.active?.let { old -> if (native.registrations.singleOrNull { it.definition.publicationId == old.definition.publicationId } != old) unavailable() }
                val request = LocalSerialRegistrationCodecV1.activate(operationId, LocalActivateSerialRegistrationV1(signed, active.activeStorageRevision))
                val intent = LocalCommandIntent(operationId, LocalAction.SERIAL_REGISTRATION_ACTIVATE, 1,
                    LocalRecordReference(LocalRecordKind.SERIAL_REGISTRATION_PUBLICATION, definition.publicationId), listOf(
                        LocalScalarInput(LocalField.DOCUMENT_ID, LocalScalarValue.Identifier(definition.registrationId)),
                        LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(definition.publicationId)),
                        LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))
                val entry = StoredClientIntentV1.staged(journal.owner, intent, request,
                    text("Activate exact serial registration", "تفعيل تسجيل المسلسل الدقيق"), listOf(
                        recordValue(text("Exact serial / code / SKU", "المسلسل / الرمز / الصنف الدقيق"), literal("${definition.serialId.value} / ${definition.serialCode} / ${definition.skuId.value}")),
                        recordValue(text("Registration / publication / revision", "التسجيل / المنشور / المراجعة"), literal("${definition.registrationId.value} / ${definition.publicationId.value} / ${definition.revision.value}")),
                        recordValue(text("Validity (exclusive end)", "الصلاحية (نهاية غير شاملة)"), literal("${definition.validFrom.value.toEpochMilliseconds()} / ${definition.validUntil?.value?.toEpochMilliseconds() ?: "none"}")),
                        recordValue(text("Expected active storage revision", "مراجعة التخزين النشط المتوقعة"), literal(active.activeStorageRevision?.value?.toString() ?: "absent")),
                        recordValue(text("Qualification", "التأهيل"), text("Master must independently qualify time, native originals, current SKU and permissions. Saving does not activate or release stock.", "يجب على الرئيسي تأهيل الوقت والأصول الأصلية والصنف الحالي والصلاحيات مستقلاً. الحفظ لا يفعل التسجيل ولا يتيح المخزون."))))
                val fresh = current(access.lease)
                fresh.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, definition.registrationId)
                fresh.checkCurrent()
                writeStarted = true
                when (journal.stage(entry)) {
                    JournalWrite.DURABLE -> {
                        val released = current(access.lease)
                        released.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, definition.registrationId)
                        released.checkCurrent()
                        LocalStageResult.Staged(pending(entry, released.canContinue(entry)))
                    }
                    JournalWrite.NO_CAPACITY -> LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                    JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(operationId)
                }
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            if (writeStarted) LocalStageResult.Uncertain(operationId,
                failure.problem.takeIf { it == LocalServiceProblem.DENIED || it == LocalServiceProblem.SESSION_ENDED })
            else LocalStageResult.NotStaged(failure.problem)
        }
        catch (_: Exception) { LocalStageResult.Uncertain(operationId) }
    }

    private suspend fun originalEntry(id: OperationId): StoredClientIntentV1? = required(journal.find(id))?.also {
        it.validate(journal.owner)
        require(it.localAction == LocalAction.SERIAL_REGISTRATION_ACTIVATE && it.request().command == LocalSerialRegistrationProfileV1.ACTIVATE && it.actionApprovalId == null)
        LocalSerialRegistrationCodecV1.decode(it.request().payload)
    }

    private suspend fun references(access: GatewayAccess): List<CompanyBusinessPublicationReference> {
        val ownedReferences = native(serial.retainedSerialPublications(access.lease.credentials))
        access.checkCurrent()
        val references = ownedReferences.map { it.publication }
        if (references.size > CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS ||
            references.map { it.publicationId }.distinct().size != references.size ||
            references.map { it.originalActionId }.distinct().size != references.size ||
            ownedReferences.map { it.originRequestId }.distinct().size != ownedReferences.size ||
            references.any { it.kind !in setOf(CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1, CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1) }) unavailable()
        originIds.clear()
        ownedReferences.forEach { originIds[it.publication.originalActionId] = it.originRequestId }
        references.forEach { requireRecoveryHint(access, it.originalActionId) }
        return references
    }

    private fun originId(actionId: DomainId): DomainId = originIds[actionId] ?: unavailable()

    private fun requireRecoveryHint(access: GatewayAccess, actionId: DomainId) {
        // Known client denial is a UI guard only; the Master independently enforces it.
        if (!AuthorizationEvaluator.permitsHistoricalOriginalActorResultRecovery(access.view.policy,
                journal.owner.actorId, journal.owner.scope, OperationId(originId(actionId).value), journal.owner.actorId))
            throw GatewayFailure(LocalServiceProblem.DENIED)
    }

    private suspend fun retained(access: GatewayAccess, reference: CompanyBusinessPublicationReference): LocalSerialPublicationResult {
        requireRecoveryHint(access, reference.originalActionId)
        return when (reference.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> {
                val action = native(serial.reviewSerialPublication(access.lease.credentials, DomainId.random(), originId(reference.originalActionId), reference.originalActionId))
                if (action.originalActionId != reference.originalActionId || action.proposal.publicationId != reference.publicationId || action.proposal.entityId != reference.entityId) unavailable()
                LocalSerialPublicationResult.Review(LocalSerialPublicationReview(action, preview(action)))
            }
            CompanyBusinessPublicationPhase.COMPLETED -> {
                val output = native(serial.recoverSerialPublication(access.lease.credentials, DomainId.random(), originId(reference.originalActionId), reference.originalActionId))
                if (output.originalActionId != reference.originalActionId || output.proposal.publicationId != reference.publicationId || output.proposal.entityId != reference.entityId) unavailable()
                completed(output)
            }
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    }

    private fun preview(action: CompanyBusinessPublicationAction): LocalSerialPublicationPreview {
        if (action.proposal.company != journal.owner.scope.company() || action.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        return when (action.proposal.kind) {
            CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> LocalSerialPublicationPreview.Authority(action.readNativeSerialAuthorityDefinition())
            CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> LocalSerialPublicationPreview.Registration(action.readNativeSerialRegistrationDefinition())
            else -> invalid()
        }
    }

    private fun completed(output: CompanyBusinessPublicationOutput): LocalSerialPublicationResult.Completed {
        val bytes = output.proposal.copyCanonicalContent()
        try {
            if (output.proposal.company != journal.owner.scope.company() || output.rootFingerprint != publications.trust.rootFingerprint ||
                sha256(bytes) != output.proposal.contentDigest || !publications.trust.verify(output.proposal.company, output.signingKeyId, bytes, output.signatureHex)) unavailable()
            val preview = when (output.proposal.kind) {
                CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 -> LocalSerialPublicationPreview.Authority(output.readNativeSerialAuthorityDefinition())
                CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> LocalSerialPublicationPreview.Registration(output.readNativeSerialRegistration().definition)
                else -> invalid()
            }
            return LocalSerialPublicationResult.Completed(LocalSerialPublicationCompleted(output.originalActionId, preview))
        } finally { bytes.fill(0) }
    }

    private suspend fun cut(): SerialNativeCut {
        val snapshot = publications.currentCompletedSnapshot() ?: unavailable()
        if (snapshot.company != journal.owner.scope.company() || snapshot.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        // The completed snapshot is authenticated as a whole by its native owner.
        // The generic retained facade intentionally omits employee-private serial originals.
        val registrations = mutableListOf<LocalSignedSerialRegistrationV1>()
        val skus = mutableListOf<LocalSignedSkuV1>()
        for (output in snapshot.completedPublications) {
            if (output.proposal.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1) {
                completed(output); registrations += output.readNativeSerialRegistration()
            } else if (output.proposal.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1) skus += output.readNativeCompanySku()
        }
        return SerialNativeCut(snapshot.generation, registrations, skus)
    }

    private fun history(cut: SerialNativeCut, id: DomainId): List<LocalPublishedSerialRegistrationV1> =
        cut.registrations.map { it.definition }.filter { it.registrationId == id }.sortedBy { it.revision }.also { rows ->
            rows.forEachIndexed { i, value ->
                val previous = rows.getOrNull(i - 1)
                if (value.revision != Revision(i.toLong() + 1) || value.previous != previous?.let(LocalSerialRegistrationPublicationCodecV1::reference)) unavailable()
                if (previous != null && (value.scope != previous.scope || value.allocationId != previous.allocationId || value.allocationDigest != previous.allocationDigest ||
                    value.serialAuthority != previous.serialAuthority || value.serialId != previous.serialId || value.serialCode != previous.serialCode || value.skuId != previous.skuId)) unavailable()
            }
        }

    private suspend fun currentSku(access: GatewayAccess, cut: SerialNativeCut, id: DomainId): LocalSignedSkuV1 {
        val page = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = id)).page
        val row = page.items.singleOrNull() ?: invalid()
        val detail = page.detail as? LocalConfigurationDetailV1.Sku ?: unavailable()
        return cut.skus.singleOrNull { skuProjectionMatches(it.definition, row, detail) }?.also {
            if (!it.definition.active || it.definition.tracking != LocalTrackingV1.SERIAL) invalid()
        } ?: unavailable()
    }

    private suspend fun validateFresh(access: GatewayAccess, definition: LocalPublishedSerialRegistrationV1, first: SerialNativeCut) {
        val allocation = native(publications.originalSerialRegistrationAllocation())
        if (definition.scope != allocation.scope || definition.scope != journal.owner.scope || definition.allocationId != allocation.allocationId ||
            definition.allocationDigest != allocation.allocationDigest || definition.serialAuthority != allocation.serialAuthority.reference) invalid()
        val latest = history(first, definition.registrationId).lastOrNull()
        if (definition.previous != latest?.let(LocalSerialRegistrationPublicationCodecV1::reference) || definition.revision != (latest?.revision?.next() ?: Revision(1))) invalid()
        if (latest != null && (definition.serialId != latest.serialId || definition.serialCode != latest.serialCode || definition.skuId != latest.skuId)) invalid()
        val sku = currentSku(access, first, definition.skuId)
        if (sku.definition.publicationId != definition.skuPublicationId || skuDigest(sku) != definition.skuPublicationDigest) invalid()
        if (cut().generation != first.generation) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
    }

    private fun skuDigest(sku: LocalSignedSkuV1): PayloadDigest {
        val bytes = LocalConfigurationCodec.canonicalPublication(sku.definition)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    private fun matches(draft: LocalSerialRegistrationDraft, value: LocalPublishedSerialRegistrationV1): Boolean =
        draft == LocalSerialRegistrationDraft(value.publicationId, value.registrationId, value.revision, value.previous,
            value.serialId, value.serialCode, value.skuId, value.skuPublicationId, value.skuPublicationDigest, value.validFrom, value.validUntil)
    private fun registrationPreview(result: LocalSerialPublicationResult): LocalPublishedSerialRegistrationV1 = when (result) {
        is LocalSerialPublicationResult.Review -> (result.review.preview as? LocalSerialPublicationPreview.Registration)?.definition ?: invalid()
        is LocalSerialPublicationResult.Completed -> (result.publication.preview as? LocalSerialPublicationPreview.Registration)?.definition ?: invalid()
        else -> unavailable()
    }
    private suspend fun result(requireActivation: Boolean = true, body: suspend (GatewayAccess) -> LocalSerialPublicationResult): LocalSerialPublicationResult = try {
        withAccess { access ->
            val value = body(access)
            val id = when (value) {
                is LocalSerialPublicationResult.Review -> value.review.action.proposal.entityId
                is LocalSerialPublicationResult.Completed -> when (val p = value.publication.preview) {
                    is LocalSerialPublicationPreview.Authority -> p.definition.authorityId
                    is LocalSerialPublicationPreview.Registration -> p.definition.registrationId
                }
                else -> null
            }
            val fresh = current(access.lease)
            if (requireActivation) fresh.requireAction(LocalAction.SERIAL_REGISTRATION_ACTIVATE, id)
            else when (value) {
                is LocalSerialPublicationResult.Review -> requireRecoveryHint(fresh, value.review.action.originalActionId)
                is LocalSerialPublicationResult.Completed -> requireRecoveryHint(fresh, value.publication.originalActionId)
                else -> Unit
            }
            fresh.checkCurrent()
            value
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SerialPublicationFailure) { LocalSerialPublicationResult.Unavailable(failure.problem, failure.reason) }
    catch (failure: GatewayFailure) { LocalSerialPublicationResult.Unavailable(failure.problem) }
    catch (_: IllegalArgumentException) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
    catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }

    private suspend fun <T> withAccess(body: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        try { val access = current(lease); val result = body(access); access.checkCurrent(); return result }
        finally { lease.destroy() }
    }
    private suspend fun current(lease: LocalWorkflowCredentialLease): GatewayAccess {
        if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val view = when (val result = endpoint.current(lease.credentials)) {
            is LocalSessionViewResult.Current -> result.view
            LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        if (!lease.isCurrent() || view.actorId != journal.owner.actorId || view.scope != journal.owner.scope || view.deviceId != journal.owner.deviceId) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return GatewayAccess(endpoint, lease, view, installed)
    }
    private fun <T> native(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> throw SerialPublicationFailure(when (result.nativeReason) {
            SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED -> LocalServiceProblem.DENIED
            SelfOwnedInstallationFailureV1.THROTTLED, SelfOwnedInstallationFailureV1.BUSY, SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED -> LocalServiceProblem.RETRY_LATER
            SelfOwnedInstallationFailureV1.INVALID_INPUT -> LocalServiceProblem.INVALID_INPUT
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
            else -> LocalServiceProblem.UNAVAILABLE
        }, result.nativeReason)
    }
    private fun pending(entry: StoredClientIntentV1, allowed: Boolean) = LocalPendingOperation(entry.operationId, entry.localAction,
        entry.label.ui(), entry.summary.map { it.ui() }, allowed && entry.recorded == null && !entry.acknowledged,
        entry.localPhase, entry.recorded?.let { if (it.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED })
}

private data class SerialNativeCut(val generation: Long, val registrations: List<LocalSignedSerialRegistrationV1>, val skus: List<LocalSignedSkuV1>)
private class SerialPublicationFailure(val problem: LocalServiceProblem, val reason: SelfOwnedInstallationFailureV1?) : Exception()
