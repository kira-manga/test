package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.FoodCurrentUnpaidPayable
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStockOrigin
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock

/**
 * Composition-root input, not a cast of LocalMasterClient or a health/configuration Boolean.
 * Supply the SAME native gate actually registered on this concrete endpoint. A mismatched gate
 * cannot adopt its private publication. Missing P4/native ownership must remain unavailable.
 */
public class LocalRetailPhysicalBinding(
    internal val endpoint: LocalMasterEndpoint,
    internal val native: LocalPhysicalJournalExecutionOwner,
)

/** Exact pre-await session epoch; contains no refresh or replacement-employee fallback. */
internal class LocalRetailOriginalCredential(val lease: LocalWorkflowCredentialLease, val epoch: LocalRetailPhysicalLifetime)
internal fun interface LocalRetailOriginalCredentialSource { suspend fun acquire(): LocalRetailOriginalCredential? }

/**
 * Actual durable client intent + concrete B4/native adapter. All new request bodies are produced
 * here from typed selectors and current reads. No caller JSON, original fact body, instruction
 * factory, principal, native receipt, allocation assertion or decoded review is an input port.
 */
internal class LocalEndpointRetailPhysicalGateway(
    private val binding: LocalRetailPhysicalBinding,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val originals: LocalRetailOriginalCredentialSource,
    private val installed: LocalWorkflowInstallation,
) : LocalRetailPhysicalGateway {
    private val endpoint = binding.endpoint
    private var preparation: Preparation? = null // One bounded ephemeral handle, never durable custody.
    private val instruction = MutableStateFlow<Instruction?>(null)

    private class Preparation(val owner: LocalEndpointRetailPhysicalGateway, val entry: StoredClientIntentV1,
        override val kind: LocalRetailPhysicalKind) : LocalRetailPhysicalPreparation {
        override val operationId: OperationId get() = entry.operationId
        override val title: LocalText get() = entry.label.ui()
        override val summary: List<LocalRecordValue> get() = entry.summary.map { it.ui() }
        override val subject: LocalPaymentSubject get() = physicalSaved(entry).subject
        override val financialKind: LocalRetailFinancialKind? get() = physicalSaved(entry).financialKind
    }

    override suspend fun paymentContext(collectionId: DomainId, afterTender: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val collection = collection(access, collectionId)
            val tenders = mutableListOf<LocalRetailTenderChoice>()
            var next: DomainId? = null
            var tenderProblem: LocalServiceProblem? = null
            try {
                val page = LocalConfigurationCodec.decodePage(access.read(ReadRequest(LocalConfigurationProfile.QUERY,
                    LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.TENDER,
                        after = afterTender, limit = 25)))).payload)
                require(page.kind == LocalConfigurationProfile.TENDER && page.items.size <= 25 &&
                    page.items.map { it.id }.distinct().size == page.items.size)
                for (item in page.items) if (item.active == true) {
                    val names = item.names ?: unavailable()
                    tenders += LocalRetailTenderChoice(item.id, item.revision, LocalText(names.english, names.arabic))
                }
                next = page.nextAfter
            } catch (failure: GatewayFailure) {
                if (failure.problem == LocalServiceProblem.SESSION_ENDED) throw failure
                // A failed tender list does not invent choices or hide that only cash is available.
                tenderProblem = failure.problem
            }
            LocalRetailPhysicalReply.PaymentContext(LocalRetailPaymentContext(collectionId,
                collection.remainingPayable, tenders, next, tenderProblem))
        }
    }

    override suspend fun foodPaymentContext(collectionId: DomainId): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val value = foodCollection(access, collectionId)
            // Capability display only. Current owner/session/stock/native admission are re-read
            // by preparation and the same production handler, not granted by a profile string.
            if (value.executionProfile != "GENUINE_FOOD_CASH_V1") throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            LocalRetailPhysicalReply.PaymentContext(LocalRetailPaymentContext(collectionId, value.unpaidRemaining,
                emptyList(), null, subject = LocalPaymentSubject.FOOD))
        }
    }

    override suspend fun foodRefundContext(refundId: DomainId): LocalRetailPhysicalReply = guarded {
        withAccess { access -> LocalRetailPhysicalReply.FoodRefundContext(foodRefundUiContext(foodRefundObligationRead(access, refundId).result)) }
    }

    override suspend fun recoverFoodRefund(operationId: OperationId): LocalRetailPhysicalReply = guarded(operationId) {
        withAccess { access ->
            val entry = required(journal.find(operationId))
            if (entry != null) { requireFoodRefundEntry(entry); return@withAccess recoverEntry(access, entry) }
            val original = gatewayFoodRefundOriginal(access, operationId)
            if (original != null) LocalRetailPhysicalReply.Progress(foodRefundFactProgress(original.summary, outcome = original.outcome))
            else LocalRetailPhysicalReply.Progress(foodRefundResolutionFactProgress(gatewayFoodRefundResolution(access, operationId) ?: unavailable()))
        }
    }

    override suspend fun foodRefundOriginal(recordingOperationId: OperationId): LocalRetailPhysicalReply = guarded(recordingOperationId) {
        withAccess { access -> LocalRetailPhysicalReply.FoodRefundOriginal(gatewayFoodRefundSelection(access, recordingOperationId)) }
    }

    override suspend fun recoverFoodRefundAttempt(refundId: DomainId, attemptId: DomainId): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val obligation = foodRefundObligationRead(access, refundId).result
            val own = obligation.ownRecoveries.singleOrNull { it.target.attemptId == attemptId } ?: unavailable()
            val target = own.target
            val read = foodRefundReadResult(access.read(LocalFoodRefundReadCodec.request(LocalFoodRefundQuery.AttemptRecording(
                target.orderId, target.collectionId, target.refundId, target.sessionId, target.attemptId, target.intentOperationId))))
                as? LocalFoodRefundReadResult.AttemptRecording ?: unavailable()
            require(read.target == target && read.amount == own.amount)
            val projection = LocalFoodRefundPhysicalProjection.parse(read.requirementBytes())
            require(projection.target == target && projection.amount == own.amount && projection.requirement.scope == journal.owner.scope &&
                projection.requirement.originalActorId == journal.owner.actorId && projection.requirement.command == LocalFoodRefundProfile.COMMAND &&
                projection.requirement.domainProfile == LocalFoodRefundProfile.DOMAIN)
            val original = read.original
            if (original != null) {
                val actual = gatewayFoodRefundOriginal(access, target.recordingOperationId) ?: unavailable()
                requireSameFoodRefundOriginal(original, actual.summary)
                val resolution = gatewayFoodRefundResolution(access, target.resolutionOperationId)
                if (resolution != null) LocalRetailPhysicalReply.Progress(foodRefundResolutionFactProgress(resolution, target.intentOperationId))
                else LocalRetailPhysicalReply.Progress(foodRefundFactProgress(actual.summary, target.intentOperationId, actual.outcome))
            } else LocalRetailPhysicalReply.Progress(LocalRetailPhysicalProgress(target.intentOperationId,
                LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION,
                text("The existing FOOD payout attempt is recorded. No SQL original is present in this read cut; native or human action may still exist. Recover only these original IDs; never pay or capture again.",
                    "سُجلت محاولة رد الطعام الأصلية. لا تظهر إفادة أصلية في قاعدة البيانات بهذه القراءة؛ قد توجد حركة بشرية أو أدلة أصلية. استرد المعرفات الأصلية فقط ولا تدفع أو تسجل مجددًا."),
                target.recordingOperationId, target.resolutionOperationId,
                originalRecordingOperationId = target.recordingOperationId,
                subject = LocalPaymentSubject.FOOD, financialKind = LocalRetailFinancialKind.REFUND))
        }
    }

    override suspend fun pendingFoodRefunds(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access -> foodRefundPending(access, afterFact, resolutions = false) }
    }

    override suspend fun pendingFoodRefundResolutions(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access -> foodRefundPending(access, afterFact, resolutions = true) }
    }

    private suspend fun foodRefundPending(access: GatewayAccess, after: DomainId?, resolutions: Boolean): LocalRetailPhysicalReply {
        val saved = savedPending(access, foodRefundActions)
        val query = if (resolutions) LocalFoodRefundQuery.OwnResolutionPending(after, 25) else LocalFoodRefundQuery.OwnPending(after, 25)
        val result = foodRefundReadResult(access.read(LocalFoodRefundReadCodec.request(query)))
        val facts: List<LocalRetailPhysicalProgress>
        val next: DomainId?
        if (resolutions) {
            val page = result as? LocalFoodRefundReadResult.OwnResolutionPending ?: unavailable()
            require(page.resolutions.size <= 25 && page.resolutions.map { it.target.resolutionOperationId }.distinct().size == page.resolutions.size &&
                page.resolutions == page.resolutions.sortedBy { it.target.resolutionOperationId.value })
            facts = page.resolutions.map { summary ->
                requireFoodRefundResolutionSummary(summary)
                require(summary.originalActorId == journal.owner.actorId && summary.recordingActorId == journal.owner.actorId &&
                    summary.applicationState == LocalFoodRefundApplicationState.PENDING_RECONCILIATION &&
                    (after == null || summary.target.resolutionOperationId.value > after.value))
                val actual = gatewayFoodRefundResolution(access, summary.target.resolutionOperationId) ?: unavailable()
                requireSameFoodRefundResolution(summary, actual.summary)
                foodRefundResolutionFactProgress(actual)
            }
            next = page.nextAfter
        } else {
            val page = result as? LocalFoodRefundReadResult.OwnPending ?: unavailable()
            require(page.originals.size <= 25 && page.originals.map { it.target.recordingOperationId }.distinct().size == page.originals.size &&
                page.originals == page.originals.sortedBy { it.target.recordingOperationId.value })
            facts = page.originals.map { summary ->
                requireFoodRefundSummary(summary)
                require(summary.originalActorId == journal.owner.actorId && summary.recordingActorId == journal.owner.actorId &&
                    summary.applicationState == LocalFoodRefundApplicationState.PENDING_RECONCILIATION &&
                    (after == null || summary.target.recordingOperationId.value > after.value))
                val actual = gatewayFoodRefundOriginal(access, summary.target.recordingOperationId) ?: unavailable()
                requireSameFoodRefundOriginal(summary, actual.summary)
                foodRefundFactProgress(actual.summary, outcome = actual.outcome)
            }
            next = page.nextAfter
        }
        next?.let { cursor -> require((after == null || cursor.value > after.value) &&
            facts.all { checkNotNull(it.recordingOperationId).value <= cursor.value }) }
        // Separate replacing pages retain the real underlying cursor, including empty filtered pages.
        return LocalRetailPhysicalReply.Saved(saved, facts, next)
    }

    override suspend fun recoverSupplierCash(operationId: OperationId): LocalRetailPhysicalReply = guarded(operationId) {
        withAccess { access ->
            val entry = required(journal.find(operationId))
            if (entry != null) { requireSupplierEntry(entry); return@withAccess recoverEntry(access, entry) }
            // Exact original selector only; a missing SQL row grants no native absence or new Start.
            LocalRetailPhysicalReply.Progress(supplierFactProgress(gatewaySupplierCash(access, operationId), journal.owner))
        }
    }

    override suspend fun pendingSupplierCash(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val saved = savedPending(access, supplierPhysicalActions)
            val query = LocalSupplierOwnCashQueryV1(afterFact, 5)
            val page = LocalSupplierSettlementCodec.ownCash(access.read(ReadRequest(LocalSupplierSettlementProfile.READ_OWN_CASH,
                LocalSupplierSettlementCodec.ownCash(query))).payload)
            if (page.unavailableCode == "purchasing.capacity_unavailable") throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
            if (page.unavailableCode != null || page.entries == null) unavailable()
            val values = checkNotNull(page.entries)
            require(values.size <= query.limit && values.map { it.recordingOperationId }.distinct().size == values.size &&
                values.zipWithNext().all { (a, b) -> a.recordingOperationId.value < b.recordingOperationId.value } &&
                values.all { afterFact == null || it.recordingOperationId.value > afterFact.value })
            page.nextAfter?.let { next -> require((afterFact == null || next.value > afterFact.value) &&
                values.all { it.recordingOperationId.value <= next.value }) }
            // Preserve the real scan cursor even when filtering yielded no own entries.
            LocalRetailPhysicalReply.Saved(saved, values.map { supplierFactProgress(it, journal.owner) }, page.nextAfter)
        }
    }

    override suspend fun prepare(request: LocalRetailPhysicalRequest): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            if (instruction.value?.deliverable() == true) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            if (request is LocalRetailPhysicalRequest.Payment && request.subject == LocalPaymentSubject.SUPPLIER ||
                request is LocalRetailPhysicalRequest.Apply && request.subject == LocalPaymentSubject.SUPPLIER ||
                request is LocalRetailPhysicalRequest.SupervisorReview && request.subject == LocalPaymentSubject.SUPPLIER ||
                request is LocalRetailPhysicalRequest.SupervisorApply && request.subject == LocalPaymentSubject.SUPPLIER ||
                request is LocalRetailPhysicalRequest.Resolve && request.subject == LocalPaymentSubject.SUPPLIER) invalid()
            when (request) {
                is LocalRetailPhysicalRequest.Resolve -> request.observation.requireNonSupplierObservation()
                is LocalRetailPhysicalRequest.FoodRefundResolve -> request.observation.requireNonSupplierObservation()
                else -> Unit
            }
            val prepared = when (request) {
                is LocalRetailPhysicalRequest.SupplierCash -> supplierCash(access, request)
                is LocalRetailPhysicalRequest.SupplierCashApply -> supplierCashApply(access, request)
                is LocalRetailPhysicalRequest.SupplierCashResolve -> supplierCashResolve(access, request)
                is LocalRetailPhysicalRequest.BeginCollection -> begin(access, request)
                is LocalRetailPhysicalRequest.Payment -> if (request.subject == LocalPaymentSubject.FOOD)
                    foodPayment(access, request) else payment(access, request)
                is LocalRetailPhysicalRequest.Refund -> refund(access, request)
                is LocalRetailPhysicalRequest.FoodRefund -> foodRefund(access, request)
                is LocalRetailPhysicalRequest.FoodRefundApply -> foodRefundApply(access, request)
                is LocalRetailPhysicalRequest.FoodRefundResolve -> foodRefundResolve(access, request)
                is LocalRetailPhysicalRequest.Apply -> if (request.subject == LocalPaymentSubject.FOOD)
                    foodApply(access, request) else apply(access, request)
                is LocalRetailPhysicalRequest.SupervisorReview -> if (request.subject == LocalPaymentSubject.FOOD)
                    foodSupervisorReview(access, request) else supervisorReview(access, request)
                is LocalRetailPhysicalRequest.SupervisorApply -> if (request.subject == LocalPaymentSubject.FOOD)
                    foodSupervisorApply(access, request) else supervisorApply(access, request)
                is LocalRetailPhysicalRequest.Resolve -> if (request.subject == LocalPaymentSubject.FOOD)
                    foodResolution(access, request) else resolution(access, request)
            }
            val entry = prepared.entry
            if (!installed.has(entry.request().command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            val pending = required(journal.pending())
            // Only exact immutable non-Start continuations may coexist with outstanding original work.
            if (entry.localAction in physicalStartActions || entry.localAction == LocalAction.RETAIL_COLLECTION_BEGIN) {
                if (pending.isNotEmpty()) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            } else if (entry.localAction !in setOf(LocalAction.RETAIL_SUPERVISOR_REVIEW, LocalAction.FOOD_SUPERVISOR_REVIEW)) {
                val same = pending.singleOrNull { it.localAction == entry.localAction && it.semanticSha256 == entry.semanticSha256 }
                if (same != null) return@withAccess LocalRetailPhysicalReply.Staged(physicalSaved(same))
            }
            // An explicitly requested FRESH review needs a fresh authorization/audit cut, not
            // an old review's replay. An uncertain review still has its own saved same-ID retry.
            val previous = required(journal.find(entry.operationId))
            if (previous != null) {
                requirePhysicalEntry(previous)
                if (!previous.sameIntent(entry)) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                return@withAccess LocalRetailPhysicalReply.Staged(physicalSaved(previous))
            }
            preparation = prepared
            LocalRetailPhysicalReply.Prepared(prepared)
        }
    }

    override suspend fun stage(preparation: LocalRetailPhysicalPreparation): LocalRetailPhysicalReply = guarded(preparation.operationId) {
        withAccess { access ->
            val own = this.preparation
            if (preparation !== own || own == null || own.owner !== this) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            val entry = own.entry
            requirePhysicalEntry(entry)
            val previous = required(journal.find(entry.operationId))
            if (previous != null) {
                if (!previous.sameIntent(entry)) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                return@withAccess LocalRetailPhysicalReply.Staged(physicalSaved(previous))
            }
            if (entry.localAction in physicalStartActions || entry.localAction == LocalAction.RETAIL_COLLECTION_BEGIN) {
                if (required(journal.pending()).isNotEmpty()) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            }
            access.checkCurrent()
            if (!installed.has(entry.request().command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            when (journal.stage(entry)) {
                JournalWrite.DURABLE -> {
                    access.checkCurrent()
                    LocalRetailPhysicalReply.Staged(physicalSaved(entry))
                }
                JournalWrite.NO_CAPACITY -> LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.RETRY_LATER, entry.operationId)
                JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE ->
                    LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, entry.operationId)
            }
        }
    }

    /** Exactly one new invocation after a durable STAGED -> DISPATCH_UNRESOLVED transition. */
    override suspend fun start(operationId: OperationId, originalEntry: LocalRetailPhysicalLifetime): LocalRetailPhysicalReply {
        var captured: LocalRetailOriginalCredential? = null
        var lifetime: PhysicalInteractiveRequestLifetime? = null
        var entryAttachment: LocalRetailPhysicalLifetime.Attachment? = null
        var sessionAttachment: LocalRetailPhysicalLifetime.Attachment? = null
        var candidate: Instruction? = null
        var returned = false
        try {
            currentCoroutineContext().ensureActive()
            if (originalEntry.denied) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            // This source captures the old epoch BEFORE its first await; never call auto-refresh.
            val original = originals.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            captured = original
            val reply = journal.requests.withLock {
                withLease(original.lease) { access ->
                    if (originalEntry.denied || original.epoch.denied) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                    val entry = required(journal.find(operationId)) ?: unavailable()
                    requirePhysicalEntry(entry)
                    if (entry.localAction !in physicalStartActions) invalid()
                    if (entry.localPhase != LocalPendingPhase.STAGED || entry.recorded != null || entry.acknowledged)
                        return@withLease recoverEntry(access, entry) // Never absent => start again.
                    if (instruction.value?.deliverable() == true) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                    val request = entry.request()
                    if (!installed.has(request.command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                    val physical = endpoint.physicalActions ?: throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                    val actual = physical.newRequestLifetime()
                    lifetime = actual
                    entryAttachment = originalEntry.attach(LocalRetailPhysicalDenial { actual.deny() })
                    sessionAttachment = original.epoch.attach(LocalRetailPhysicalDenial { actual.deny() })
                    if (entryAttachment == null || sessionAttachment == null || actual.denied)
                        throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                    if (journal.markDispatched(operationId) != JournalWrite.DURABLE)
                        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    access.checkCurrent()
                    if (actual.denied) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                    val started = physical.start(access.lease.credentials, request, actual)
                    val result = started.recordedOrDeferred
                    val recorded = result as? MutationReply.Recorded
                    val offer = started.offer
                    if (recorded != null && matches(entry, recorded)) {
                        // Pure exact result validation precedes publication/adoption. A matching
                        // operation header alone cannot make a different payment target live.
                        val progress = physicalRecordedProgress(entry, recorded)
                        // Publication/adoption use the same private native preparation exactly once.
                        // Do not spend the one-second publication lag on an extra client receipt IO.
                        if (offer != null && recorded.committed && !recorded.replayed && !actual.denied) {
                            val published = physical.publishInstruction(access.lease.credentials, offer, actual)
                            if (published is PhysicalInstructionReply.Published) {
                                val native = binding.native.adoptPublished(published.instruction, actual)
                                if (native != null) {
                                    try {
                                        val display = instructionDisplay(entry, recorded, native.projection.canonicalRequirement)
                                        val value = Instruction(native, actual, originalEntry, original.epoch,
                                            checkNotNull(entryAttachment), checkNotNull(sessionAttachment),
                                            display, entry.localAction)
                                        candidate = value
                                        if (!instruction.compareAndSet(null, value)) { value.deny(); unavailable() }
                                    } catch (failure: Exception) { native.deny(); throw failure }
                                } else published.instruction.deny()
                            }
                        } else offer?.deny()
                        // SQL Start already committed; a failed client receipt cannot turn it into
                        // an absent/undispatched operation. Its old DISPATCH marker remains durable.
                        journal.remember(operationId, StoredKernelResultV1.from(recorded))
                        access.checkCurrent()
                        val delivery = candidate
                        if (delivery != null && delivery.deliverable()) {
                            LocalRetailPhysicalReply.Instruction(delivery)
                        } else LocalRetailPhysicalReply.Progress(progress)
                    } else {
                        offer?.deny()
                        mutationUnavailable(operationId, result)
                    }
                }
            }
            // withLease performs a final current-session check AFTER its body. A candidate is
            // delivered only when that check and the journal lock exit both completed normally.
            if (reply is LocalRetailPhysicalReply.Instruction) {
                if (reply.value !== candidate || !reply.value.deliverable())
                    throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                returned = true
            }
            return reply
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { return LocalRetailPhysicalReply.Unavailable(failure.problem, operationId) }
        catch (_: Exception) { return LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.UNAVAILABLE, operationId) }
        finally {
            if (!returned) { candidate?.deny(); lifetime?.deny(); entryAttachment?.close(); sessionAttachment?.close() }
            captured?.lease?.destroy()
        }
    }

    /** SQL accounting/review or authorized native linked retention; NEVER a physical-action Start. */
    override suspend fun continueSql(operationId: OperationId): LocalRetailPhysicalReply = guarded(operationId) {
        withAccess { access ->
            val entry = required(journal.find(operationId)) ?: unavailable()
            requirePhysicalEntry(entry)
            if (entry.localAction in physicalStartActions) return@withAccess recoverEntry(access, entry)
            when (val lookup = access.lookup(entry)) {
                is LookupReply.Found -> recorded(access, entry, lookup.result)
                is LookupReply.AbsentAtCurrentWriter -> {
                    if (lookup.operationId != operationId || entry.recorded != null || entry.acknowledged) unavailable()
                    if (!installed.has(entry.request().command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                    if (journal.markDispatched(operationId) != JournalWrite.DURABLE) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    access.checkCurrent()
                    when (val result = endpoint.execute(access.lease.credentials, entry.request())) {
                        is MutationReply.Recorded -> {
                            if (matches(entry, result)) journal.remember(operationId, StoredKernelResultV1.from(result))
                            access.checkCurrent()
                            recorded(access, entry, result)
                        }
                        else -> mutationUnavailable(operationId, result)
                    }
                }
                is LookupReply.Deferred -> LocalRetailPhysicalReply.Unavailable(problem(lookup.reason), operationId)
                is LookupReply.IdentityConflict -> LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.UNAVAILABLE, operationId)
            }
        }
    }

    override suspend fun recover(operationId: OperationId): LocalRetailPhysicalReply = guarded(operationId) {
        withAccess { access ->
            val entry = required(journal.find(operationId))
            if (entry != null) { requireRetailEntry(entry); return@withAccess recoverEntry(access, entry) }
            // Native originals need not have a UI request journal row. These are authenticated,
            // own SQL fact reads only; unavailable NEVER implies native absence or new-ID safety.
            val original = fact(access, operationId, LocalJournalFactPhaseV3.ORIGINAL)
            val read = original ?: fact(access, operationId, LocalJournalFactPhaseV3.RESOLUTION) ?: unavailable()
            LocalRetailPhysicalReply.Progress(factProgress(read))
        }
    }

    override suspend fun recoverRefundOperation(operationId: OperationId): LocalRetailPhysicalReply = guarded(operationId) {
        withAccess { access ->
            val entry = required(journal.find(operationId))
            if (entry != null) {
                requireRetailEntry(entry)
                if (retailFinancialKind(entry) != LocalRetailFinancialKind.REFUND &&
                    entry.localAction != LocalAction.RETAIL_SUPERVISOR_REVIEW) unavailable()
                // Review is intentionally untyped before audit. Its real audited kind is shown
                // read-only; the RETURNS controller cannot turn a PAYMENT review into a refund.
                return@withAccess recoverEntry(access, entry)
            }
            val original = fact(access, operationId, LocalJournalFactPhaseV3.ORIGINAL)
            val read = original ?: fact(access, operationId, LocalJournalFactPhaseV3.RESOLUTION) ?: unavailable()
            require(read.summary.target.kind == SalesRecordingKind.REFUND_ATTESTATION)
            LocalRetailPhysicalReply.Progress(factProgress(read))
        }
    }

    override suspend fun recoverFood(operationId: OperationId): LocalRetailPhysicalReply = guarded(operationId) {
        withAccess { access ->
            val entry = required(journal.find(operationId))
            if (entry != null) { requireFoodEntry(entry); return@withAccess recoverEntry(access, entry) }
            // Native facts may have no UI journal entry. Own SQL originals/resolutions only;
            // absence grants neither native capacity nor another Start.
            val original = foodOriginal(access, operationId)
            if (original != null) LocalRetailPhysicalReply.Progress(foodFactProgress(original.summary, outcome = original.outcome))
            else {
                val resolution = foodResolutionFact(access, operationId) ?: unavailable()
                LocalRetailPhysicalReply.Progress(foodResolutionFactProgress(resolution))
            }
        }
    }

    override suspend fun pending(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val saved = savedPending(access, retailActions)
            val page = LocalFinancialReadCodecV3.decode(access.read(LocalFinancialReadCodecV3.request(
                LocalFinancialQueryV3.OwnPending(afterFact, 25))).payload) as? LocalFinancialReadResultV3.OwnPending ?: unavailable()
            require(page.facts.size <= 25 && page.facts.all { it.originalActorId == journal.owner.actorId })
            LocalRetailPhysicalReply.Saved(saved, page.facts.map { retailFactProgress(it) }, page.nextAfter)
        }
    }

    override suspend fun pendingRefunds(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val saved = savedPending(access, retailRefundActions).filter { it.financialKind != LocalRetailFinancialKind.PAYMENT }
            val page = LocalFinancialReadCodecV3.decode(access.read(LocalFinancialReadCodecV3.request(
                LocalFinancialQueryV3.OwnPending(afterFact, 25))).payload) as? LocalFinancialReadResultV3.OwnPending ?: unavailable()
            require(page.facts.size <= 25 && page.facts.map { it.recordingOperationId }.distinct().size == page.facts.size &&
                page.facts == page.facts.sortedBy { it.recordingOperationId.value })
            page.facts.forEach {
                requireRetailSummary(it)
                require(it.originalActorId == journal.owner.actorId &&
                    it.applicationState == LocalJournalApplicationStateV3.PENDING_RECONCILIATION &&
                    (afterFact == null || it.recordingOperationId.value > afterFact.value))
            }
            page.nextAfter?.let { cursor ->
                require((afterFact == null || cursor.value > afterFact.value) &&
                    page.facts.all { it.recordingOperationId.value <= cursor.value })
            }
            // A storage-key page may contain only another kind/owner or applied rows. Preserve
            // its actual advancing cursor even when this refund projection has no visible facts.
            LocalRetailPhysicalReply.Saved(saved,
                page.facts.filter { it.target.kind == SalesRecordingKind.REFUND_ATTESTATION }.map { retailFactProgress(it) }, page.nextAfter)
        }
    }

    override suspend fun pendingFood(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val saved = savedPending(access, foodPhysicalActions)
            val page = LocalFoodPaymentReadCodec.decode(access.read(LocalFoodPaymentReadCodec.request(
                LocalFoodPaymentQuery.OwnPending(afterFact, 25))).payload)
            if (page == LocalFoodPaymentReadResult.CapacityRequired) throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
            val pending = page as? LocalFoodPaymentReadResult.OwnPending ?: unavailable()
            require(pending.originals.size <= 25 && pending.originals.map { it.target.recordingOperationId }.distinct().size == pending.originals.size)
            pending.originals.forEach { value ->
                requireFoodSummary(value)
                require(value.originalActorId == journal.owner.actorId && value.applicationState == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION &&
                    (afterFact == null || value.target.recordingOperationId.value > afterFact.value))
            }
            LocalRetailPhysicalReply.Saved(saved, pending.originals.map { foodFactProgress(it) }, pending.nextAfter)
        }
    }

    override suspend fun pendingFoodResolutions(afterFact: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val saved = savedPending(access, foodPhysicalActions)
            val result = LocalFoodPaymentReadCodec.decode(access.read(LocalFoodPaymentReadCodec.request(
                LocalFoodPaymentQuery.OwnResolutionPending(afterFact, 25))).payload)
            if (result == LocalFoodPaymentReadResult.CapacityRequired) throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
            val page = result as? LocalFoodPaymentReadResult.OwnResolutionPending ?: unavailable()
            require(page.resolutions.size <= 25 && page.resolutions.map { it.target.resolutionOperationId }.distinct().size == page.resolutions.size)
            require(page.resolutions == page.resolutions.sortedBy { it.target.resolutionOperationId.value })
            page.nextAfter?.let { cursor ->
                require((afterFact == null || cursor.value > afterFact.value) &&
                    page.resolutions.all { it.target.resolutionOperationId.value <= cursor.value })
            }
            val facts = page.resolutions.map { summary ->
                requireFoodResolutionSummary(summary)
                require(summary.originalActorId == journal.owner.actorId && summary.recordingActorId == journal.owner.actorId &&
                    summary.applicationState == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION &&
                    (afterFact == null || summary.target.resolutionOperationId.value > afterFact.value))
                val actual = foodResolutionFact(access, summary.target.resolutionOperationId) ?: unavailable()
                requireSameFoodResolution(summary, actual.summary)
                foodResolutionFactProgress(actual)
            }
            // This is a separate replacing page and cursor, never a truncated merge of two scans.
            LocalRetailPhysicalReply.Saved(saved, facts, page.nextAfter)
        }
    }

    private suspend fun savedPending(access: GatewayAccess, actions: Set<LocalAction>): List<LocalRetailSavedOperation> {
        val saved = mutableListOf<LocalRetailSavedOperation>()
        val entries = required(journal.pending()).filter { it.localAction in actions }.sortedBy { it.operationId.value }
        require(entries.size <= MAX_CLIENT_PENDING)
        for (entry in entries) {
            requirePhysicalEntry(entry)
            when (val lookup = access.lookup(entry)) {
                is LookupReply.Found -> {
                    if (!matches(entry, lookup.result)) unavailable()
                    physicalRecordedProgress(entry, lookup.result)
                    val known = StoredKernelResultV1.from(lookup.result)
                    if (entry.recorded != null && entry.recorded != known) unavailable()
                    journal.remember(entry.operationId, known)
                    saved += physicalSaved(entry.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = known))
                }
                is LookupReply.AbsentAtCurrentWriter -> {
                    if (lookup.operationId != entry.operationId || entry.recorded != null) unavailable()
                    saved += physicalSaved(entry)
                }
                is LookupReply.Deferred -> throw GatewayFailure(problem(lookup.reason))
                is LookupReply.IdentityConflict -> unavailable()
            }
        }
        return saved
    }

    override suspend fun refundDues(after: DomainId?): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            // One real accepted return per page; do not truncate one return's original tenders.
            val result = LocalReturnReadCodec.decode(access.read(LocalReturnReadCodec.request(LocalReturnQueryV1.OwnUnsettled(after, 1))).payload)
                as? LocalReturnReadResultV1.AcceptedReturns ?: unavailable()
            require(result.returns.size <= 1 && result.returns.map { it.returnId }.distinct().size == result.returns.size &&
                result.returns.all { after == null || it.returnId.value > after.value } &&
                (result.nextAfter == null || result.returns.lastOrNull()?.returnId == result.nextAfter))
            LocalRetailPhysicalReply.RefundDues(result.returns.flatMap(::retailRefundDues), result.nextAfter,
                result.returns.flatMap(::retailRefundRecoveries))
        }
    }

    override suspend fun acceptedRefundDues(caseId: DomainId, returnId: DomainId): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            // Only the actual case's qualified, accepted binding supplies the sale/portion.
            // UI labels, a claim assertion or an unavailable source cannot supply entitlement.
            val original = returnCaseRead(access, caseId) ?: unavailable()
            val binding = original.binding ?: unavailable()
            require(original.summary.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
                original.summary.acceptedReturnId == returnId && original.summary.saleId == binding.saleId &&
                original.summary.portionId == binding.portionId)
            val accepted = acceptedRefund(access, binding.saleId, caseId, returnId)
            require(accepted.portionId == binding.portionId && accepted.quantity == binding.quantity)
            LocalRetailPhysicalReply.RefundDues(retailRefundDues(accepted), null, retailRefundRecoveries(accepted))
        }
    }

    override suspend fun recoverRefund(saleId: DomainId, claimId: DomainId, returnId: DomainId,
        attemptId: DomainId): LocalRetailPhysicalReply = guarded {
        withAccess { access ->
            val accepted = acceptedRefund(access, saleId, claimId, returnId)
            val attempt = accepted.attempts.singleOrNull { it.attemptId == attemptId } ?: unavailable()
            if (!attempt.ownAttempt) throw GatewayFailure(LocalServiceProblem.DENIED)
            if (!refundUnresolved(attempt.status) || attempt.activeRecordingProtocolVersion != 3 ||
                attempt.activeRecordingOperationId == null) unavailable()
            val read = LocalFinancialReadCodecV3.decode(access.read(LocalFinancialReadCodecV3.request(
                LocalFinancialQueryV3.AttemptRecording(SalesRecordingKind.REFUND_ATTESTATION, accepted.returnId,
                    attempt.cashSessionId ?: unavailable(), attempt.attemptId, attempt.intentOperationId ?: unavailable()))).payload)
                as? LocalFinancialReadResultV3.AttemptRecording ?: unavailable()
            requireRefundAttemptRecording(accepted, attempt, read, journal.owner)
            val latest = read.resolution ?: read.original
            if (latest == null) LocalRetailPhysicalReply.Progress(LocalRetailPhysicalProgress(read.target.intentOperationId,
                LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION,
                text("The existing V3 refund attempt is recorded; no SQL observation is present in this read cut. Native or human action may still exist. Recover only these original IDs; do not pay or capture again.",
                    "محاولة الرد الأصلية V3 مسجلة؛ لا توجد إفادة في قاعدة البيانات عند هذه القراءة. قد توجد حركة فعلية أو أدلة أصلية. استرد هذه المعرفات فقط؛ لا تكرر الدفع أو تسجيل الإفادة."),
                read.target.recordingOperationId, read.target.resolutionOperationId,
                originalRecordingOperationId = read.target.recordingOperationId, financialKind = LocalRetailFinancialKind.REFUND))
            else {
                val observed = fact(access, latest.recordingOperationId, latest.phase) ?: unavailable()
                requireSameFact(latest, observed.summary)
                LocalRetailPhysicalReply.Progress(factProgress(observed, read.target.intentOperationId))
            }
        }
    }

    private suspend fun recoverEntry(access: GatewayAccess, entry: StoredClientIntentV1): LocalRetailPhysicalReply = when (val lookup = access.lookup(entry)) {
        is LookupReply.Found -> {
            val progress = recorded(access, entry, lookup.result)
            // A Start result itself is never a prompt. Use actual SQL original/resolution reads
            // when present, without reaching native files, waiting for intake, or rearming.
            if (lookup.result.committed && entry.localAction in supplierPhysicalActions) {
                recoverSupplierResult(access, entry, lookup.result)
            } else if (lookup.result.committed && entry.localAction in foodRefundActions) {
                recoverFoodRefundResult(access, entry, lookup.result, progress)
            } else if (lookup.result.committed && entry.localAction == LocalAction.FOOD_PAYMENT_START) {
                val started = LocalFoodPaymentCodec.result(lookup.result.result) as? LocalFoodPaymentResult.AttemptStarted ?: unavailable()
                val target = started.target
                when (val read = LocalFoodPaymentReadCodec.decode(access.read(LocalFoodPaymentReadCodec.request(
                    LocalFoodPaymentQuery.AttemptRecording(target.orderId, target.collectionId, target.sessionId,
                        target.attemptId, target.intentOperationId))).payload)) {
                    is LocalFoodPaymentReadResult.AttemptRecording -> {
                        requireFoodAttemptRecording(entry, started, read)
                        val latest = read.original
                        if (latest != null) {
                            val observed = foodOriginal(access, latest.target.recordingOperationId) ?: unavailable()
                            requireSameFoodOriginal(latest, observed.summary)
                            LocalRetailPhysicalReply.Progress(foodFactProgress(observed.summary, entry.operationId, observed.outcome))
                        } else progress
                    }
                    LocalFoodPaymentReadResult.NotAvailable -> progress // SQL absence is not native absence.
                    LocalFoodPaymentReadResult.CapacityRequired -> throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
                    else -> unavailable()
                }
            } else if (entry.localAction == LocalAction.FOOD_PAYMENT_RESOLUTION && lookup.result.committed) {
                // A lost native-retention/SQL answer is recovered under the original fixed ID,
                // never turned into another resolver input or a new physical instruction.
                val body = LocalFoodPaymentCodec.decode(entry.request().payload) as? LocalFoodPaymentRequest.RecordResolution ?: unavailable()
                val latest = foodResolutionFact(access, body.target.resolutionOperationId)
                if (latest == null) progress else {
                    require(latest.summary.target == body.target && latest.summary.originalBodyDigest == body.originalBodyDigest &&
                        latest.summary.recordingActorId == entry.owner.actorId && latest.outcome == body.outcome)
                    LocalRetailPhysicalReply.Progress(foodResolutionFactProgress(latest, entry.operationId))
                }
            } else {
                val started = if (lookup.result.committed && entry.localAction in retailStartActions)
                    LocalFinancialResultCodecV3.decode(lookup.result.result) as? LocalFinancialResultV3.AttemptStarted else null
                if (started != null) {
                    val target = started.target
                    val read = LocalFinancialReadCodecV3.decode(access.read(LocalFinancialReadCodecV3.request(
                        LocalFinancialQueryV3.AttemptRecording(target.kind, target.documentId, target.sessionId, target.attemptId, target.intentOperationId))).payload)
                    when (read) {
                        is LocalFinancialReadResultV3.AttemptRecording -> {
                            requireAttemptRecording(entry, started, read)
                            val latest = read.resolution ?: read.original
                            if (latest != null) {
                                val observed = fact(access, latest.recordingOperationId, latest.phase) ?: unavailable()
                                requireSameFact(latest, observed.summary)
                                LocalRetailPhysicalReply.Progress(factProgress(observed, entry.operationId))
                            } else progress
                        }
                        LocalFinancialReadResultV3.NotAvailable -> progress // Not native absence or a replacement-action grant.
                        else -> unavailable()
                    }
                } else progress
            }
        }
        is LookupReply.AbsentAtCurrentWriter -> {
            if (lookup.operationId != entry.operationId || entry.recorded != null || entry.acknowledged) unavailable()
            LocalRetailPhysicalReply.Progress(LocalRetailPhysicalProgress(entry.operationId,
                if (entry.localPhase == LocalPendingPhase.STAGED) LocalRetailDurableState.SAVED_NOT_DISPATCHED else LocalRetailDurableState.DISPATCH_UNRESOLVED,
                if (entry.localPhase == LocalPendingPhase.STAGED) text("Original request durably saved; not yet dispatched.", "حُفظ الطلب الأصلي بشكل دائم ولم يُرسل بعد.")
                else text("Original request was dispatched; result is unresolved. Do NOT repeat a physical Start or infer no human action.",
                    "أُرسل الطلب الأصلي؛ النتيجة غير محسومة. لا تكرر البدء الفعلي ولا تفترض عدم حدوث حركة بشرية."),
                subject = when (entry.localAction) {
                    in supplierPhysicalActions -> LocalPaymentSubject.SUPPLIER
                    in foodPhysicalActions, in foodRefundActions -> LocalPaymentSubject.FOOD
                    else -> LocalPaymentSubject.RETAIL
                },
                financialKind = when (entry.localAction) {
                    in supplierPhysicalActions -> LocalRetailFinancialKind.SUPPLIER_CASH
                    in foodRefundActions -> LocalRetailFinancialKind.REFUND
                    in foodPhysicalActions -> LocalRetailFinancialKind.PAYMENT
                    else -> retailFinancialKind(entry)
                }))
        }
        is LookupReply.Deferred -> LocalRetailPhysicalReply.Unavailable(problem(lookup.reason), entry.operationId)
        is LookupReply.IdentityConflict -> LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.UNAVAILABLE, entry.operationId)
    }

    private suspend fun recoverFoodRefundResult(access: GatewayAccess, entry: StoredClientIntentV1,
        recorded: MutationReply.Recorded, progress: LocalRetailPhysicalReply): LocalRetailPhysicalReply {
        requireFoodRefundEntry(entry)
        if (entry.localAction == LocalAction.FOOD_REFUND_START) {
            val started = LocalFoodRefundCodec.result(recorded.result) as? LocalFoodRefundResult.AttemptStarted ?: unavailable()
            val target = started.target
            return when (val read = foodRefundReadResult(access.read(LocalFoodRefundReadCodec.request(LocalFoodRefundQuery.AttemptRecording(
                target.orderId, target.collectionId, target.refundId, target.sessionId, target.attemptId, target.intentOperationId))))) {
                is LocalFoodRefundReadResult.AttemptRecording -> {
                    requireFoodRefundAttemptRecording(entry, started, read)
                    val latest = read.original
                    if (latest == null) progress else {
                        val original = gatewayFoodRefundOriginal(access, target.recordingOperationId) ?: unavailable()
                        requireSameFoodRefundOriginal(latest, original.summary)
                        val resolution = gatewayFoodRefundResolution(access, target.resolutionOperationId)
                        if (resolution != null) LocalRetailPhysicalReply.Progress(foodRefundResolutionFactProgress(resolution, entry.operationId))
                        else LocalRetailPhysicalReply.Progress(foodRefundFactProgress(original.summary, entry.operationId, original.outcome))
                    }
                }
                LocalFoodRefundReadResult.NotAvailable -> progress // Not native absence or another payout grant.
                else -> unavailable()
            }
        }
        if (entry.localAction == LocalAction.FOOD_REFUND_RESOLUTION) {
            val body = LocalFoodRefundCodec.decode(entry.request().payload) as? LocalFoodRefundRequest.RecordResolution ?: unavailable()
            val latest = gatewayFoodRefundResolution(access, body.target.resolutionOperationId) ?: return progress
            require(latest.summary.target == body.target && latest.summary.originalBodyDigest == body.originalBodyDigest &&
                latest.summary.recordingActorId == entry.owner.actorId && latest.outcome == body.outcome)
            return LocalRetailPhysicalReply.Progress(foodRefundResolutionFactProgress(latest, entry.operationId))
        }
        return progress
    }

    /** Known request/result metadata must agree; historical selectors remain read-only evidence. */
    private fun requireAttemptRecording(entry: StoredClientIntentV1, started: LocalFinancialResultV3.AttemptStarted,
        read: LocalFinancialReadResultV3.AttemptRecording) {
        val request = entry.request()
        require(read.target == started.target && read.target == retailTarget(request) &&
            read.requirementDigest == started.requirementDigest && read.clientDeviceId == journal.owner.deviceId)
        PayloadDigest(read.requirementDigest)
        val expectedTender: LocalTenderSelectionV1?
        when (entry.localAction) {
            LocalAction.RETAIL_PAYMENT_START -> {
                val body = LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.Start
                require(read.masterAllocationId == body.masterAllocationId && read.clientAllocationId == body.clientAllocationId && started.amount == body.amount)
                expectedTender = body.tender
            }
            LocalAction.RETAIL_REFUND_START -> {
                val body = LocalReturnCommandCodecV3.decode(request.payload) as LocalReturnRequestV3.Start
                require(read.masterAllocationId == body.masterAllocationId && read.clientAllocationId == body.clientAllocationId && started.amount == body.amount)
                expectedTender = null // Refund tender rights come from the actual original payment, not a caller choice.
            }
            else -> unavailable()
        }
        fun requireSummary(summary: LocalJournalFactSummaryV3, phase: LocalJournalFactPhaseV3) {
            requireRetailSummary(summary)
            require(summary.target == read.target && summary.phase == phase && summary.originalActorId == journal.owner.actorId && summary.amount == started.amount)
            when (val tender = expectedTender) {
                LocalTenderSelectionV1.Cash -> require(summary.tender.kind == "CASH")
                is LocalTenderSelectionV1.External -> require(summary.tender.kind != "CASH" && summary.tender.tenderId == tender.tenderId &&
                    summary.tender.policyRevision == tender.expectedPolicyRevision)
                null -> Unit
            }
        }
        read.original?.let { requireSummary(it, LocalJournalFactPhaseV3.ORIGINAL) }
        read.resolution?.let { resolution ->
            val original = read.original ?: unavailable()
            requireSummary(resolution, LocalJournalFactPhaseV3.RESOLUTION)
            require(resolution.originalBodyDigest == original.bodyDigest && resolution.tender == original.tender && resolution.amount == original.amount)
        }
    }

    private fun requireSameFact(previous: LocalJournalFactSummaryV3, current: LocalJournalFactSummaryV3) {
        // Application state may advance between two authorized reads; immutable fact bytes/lineage may not.
        require(previous.target == current.target && previous.phase == current.phase && previous.recordingOperationId == current.recordingOperationId &&
            previous.originalActorId == current.originalActorId && previous.recordingActorId == current.recordingActorId &&
            previous.bodyDigest == current.bodyDigest && previous.originalBodyDigest == current.originalBodyDigest &&
            previous.recordedAt == current.recordedAt && previous.amount == current.amount && previous.tender == current.tender)
    }

    private suspend fun recorded(access: GatewayAccess, entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalReply {
        if (!matches(entry, reply)) unavailable()
        val retained = StoredKernelResultV1.from(reply)
        if (entry.recorded != null && entry.recorded != retained) unavailable()
        journal.remember(entry.operationId, retained)
        access.checkCurrent()
        return LocalRetailPhysicalReply.Progress(physicalRecordedProgress(entry, reply))
    }

    private fun matches(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Boolean =
        reply.operationId == entry.operationId && reply.originalActorId == journal.owner.actorId

    private fun mutationUnavailable(id: OperationId, result: MutationReply): LocalRetailPhysicalReply =
        LocalRetailPhysicalReply.Unavailable(if (result is MutationReply.Deferred && result.operationId == id)
            problem(result.reason) else LocalServiceProblem.UNAVAILABLE, id)

    private suspend fun collection(access: GatewayAccess, id: DomainId): LocalCollectionViewV1 {
        val read = LocalCollectionReadCodec.decode(access.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.Collection(id))).payload)
        val value = (read as? LocalCollectionReadResultV1.Collection)?.value ?: unavailable()
        require(value.collectionId == id)
        return value
    }

    private suspend fun foodCollection(access: GatewayAccess, id: DomainId): LocalFoodCollectionViewV1 {
        val result = foodFinancialRead(access, LocalFoodFinancialQueryV1.Collection(id)).result
        val value = (result as? LocalFoodFinancialReadResultV1.Collection)?.value ?: unavailable()
        require(value.collectionId == id)
        return value
    }

    private suspend fun ownSession(access: GatewayAccess): LocalCashSessionViewV1 {
        val own = LocalCashReadCodec.decode(access.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload)
            as? LocalCashReadResultV1.OwnCash ?: unavailable()
        val session = own.session ?: unavailable()
        if (own.custodianId != access.view.actorId || session.custodianId != access.view.actorId || session.status != "OPEN")
            throw GatewayFailure(LocalServiceProblem.DENIED)
        return session
    }

    private fun hint(access: GatewayAccess, action: SecurityAction, target: DomainId) {
        if (!access.hasHint(Permission(action, PermissionVerb.PERFORM), target)) throw GatewayFailure(LocalServiceProblem.DENIED)
    }

    private fun prepared(request: MutationRequest, kind: LocalRetailPhysicalKind, title: LocalText,
        summary: List<LocalRecordValue>): Preparation = Preparation(this, physicalIntent(journal.owner, request, title, summary), kind)

    private suspend fun begin(access: GatewayAccess, selected: LocalRetailPhysicalRequest.BeginCollection): Preparation {
        val result = LocalOrderReadCodec.decode(access.read(LocalOrderReadCodec.request(LocalOrderQueryV1.Order(selected.orderId))).payload)
        val order = result.orders.singleOrNull() ?: unavailable()
        require(order.orderId == selected.orderId && order.ownerId == access.view.actorId)
        if (order.collectionId != null || order.saleId != null || order.handoffId != null || order.payable.minor <= 0L) invalid()
        hint(access, SecurityAction.COLLECTION_BEGIN, order.orderId)
        val cash = ownSession(access)
        val cut = LocalCollectionReadCodec.decode(access.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.OrderStockCut(order.orderId))).payload)
        val collectionId = DomainId.random()
        val reservationId = DomainId.random()
        val operationId = OperationId.random()
        val request = when (cut) {
            is LocalCollectionReadResultV1.NormalizedOrderDemand -> {
                require(cut.orderId == order.orderId && cut.orderRevision == order.revision && cut.quoteRevision == order.quoteRevision && cut.lines.isNotEmpty())
                LocalCollectionStockCodecV2.command(operationId, LocalCollectionStockRequestV2.Begin(order.orderId, order.revision,
                    cash.id, cash.revision, collectionId, reservationId))
            }
            is LocalCollectionReadResultV1.OrderStockCut -> {
                require(cut.orderId == order.orderId && cut.orderRevision == order.revision && cut.quoteRevision == order.quoteRevision && cut.items.size in 1..16)
                val body = LocalCollectionRequestV1.Begin(order.orderId, order.revision, cash.id, cash.revision, collectionId, reservationId, cut.items.toList())
                MutationRequest(operationId, LocalSalesStoreProfile.COLLECTION_COMMAND, LocalCollectionCommandCodec.encode(body))
            }
            else -> unavailable()
        }
        return prepared(request,
            LocalRetailPhysicalKind.PREPARE_COLLECTION, text("Prepare positive collection and reserve stock", "إعداد التحصيل وحجز المخزون"), listOf(
                recordValue(text("Order", "الطلب"), literal(order.orderId.value)), recordValue(text("Payable", "المستحق"), moneyText(order.payable)),
                recordValue(text("New collection", "التحصيل الجديد"), literal(collectionId.value)),
                recordValue(text("Own cash session", "جلسة النقدية الخاصة"), literal(cash.id.value)),
                recordValue(text("No physical action", "لا حركة فعلية"), text("This only locks the quote and reserves stock.", "يقفل هذا السعر ويحجز المخزون فقط."))))
    }

    private suspend fun payment(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Payment): Preparation {
        require(selected.subject == LocalPaymentSubject.RETAIL)
        val collection = collection(access, selected.collectionId)
        if (collection.saleId != null || collection.status !in setOf("LOCKED_UNPAID", "PART_PAID") ||
            collection.attempts.any { it.status in setOf("AWAITING_ATTESTATION", "OUTCOME_UNKNOWN") }) invalid()
        val amount = selected.amount ?: collection.remainingPayable
        if (amount.currency != collection.remainingPayable.currency || amount.minor <= 0L || amount.minor > collection.remainingPayable.minor) invalid()
        hint(access, SecurityAction.COLLECTION_ATTEST, collection.collectionId)
        val cash = ownSession(access)
        require(cash.id == collection.sessionId && cash.revision == collection.sessionRevision)
        val stock = LocalCollectionStockReadCodecV2.decode(access.read(LocalCollectionStockReadCodecV2.request(
            LocalCollectionStockQueryV2(collection.collectionId))).payload)
        val reservationRevision: com.ukkera.pos.types.Revision
        val items: List<LocalSalesStockRevisionV1>
        when (stock) {
            is LocalCollectionStockReadResultV2.NormalizedCut -> {
                require(stock.collectionId == collection.collectionId && stock.reservationId == collection.reservationId)
                if (stock.status != "RESERVED" || stock.holdCount != 0L || stock.allocationCount <= 0L) unavailable()
                if (selected.externalTenderId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                reservationRevision = stock.reservationRevision; items = stock.items
            }
            is LocalCollectionStockReadResultV2.StockCut -> {
                require(stock.collectionId == collection.collectionId && stock.reservationId == collection.reservationId)
                if (stock.status != "RESERVED" || stock.holds.isNotEmpty()) unavailable()
                reservationRevision = stock.reservationRevision; items = stock.items
            }
            is LocalCollectionStockReadResultV2.CapacityRequired -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            else -> unavailable()
        }
        // The existing native v3 Start request remains a narrow finite profile. Never truncate.
        if (items.size !in 1..16) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        val tenderId = selected.externalTenderId
        val tender = if (tenderId == null) LocalTenderSelectionV1.Cash else {
            val page = LocalConfigurationCodec.decodePage(access.read(ReadRequest(LocalConfigurationProfile.QUERY,
                LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.TENDER, id = tenderId)))).payload)
            val item = page.items.singleOrNull() ?: unavailable()
            val detail = (page.detail as? LocalConfigurationDetailV1.ExternalTender)?.value ?: unavailable()
            require(page.kind == LocalConfigurationProfile.TENDER && item.id == tenderId && detail.id == tenderId &&
                item.revision == selected.expectedTenderRevision && detail.active && item.active == true)
            LocalTenderSelectionV1.External(tenderId, item.revision)
        }
        val body = LocalCollectionRequestV3.Start(collection.collectionId, collection.collectionRevision, cash.id, cash.revision,
            reservationRevision, items.toList(), DomainId.random(), tender, amount,
            recordingOperationId = OperationId.random(), resolutionOperationId = OperationId.random(),
            masterAllocationId = DomainId.random(), clientAllocationId = DomainId.random())
        return prepared(MutationRequest(OperationId.random(), LocalFinancialJournalProfileV3.COLLECTION_COMMAND, LocalCollectionCommandCodecV3.encode(body)),
            LocalRetailPhysicalKind.PAYMENT, text("Prepare one physical collection attempt", "إعداد محاولة تحصيل فعلية واحدة"), listOf(
                recordValue(text("Collection", "التحصيل"), literal(collection.collectionId.value)), recordValue(text("Amount", "المبلغ"), moneyText(amount)),
                recordValue(text("Tender", "وسيلة الدفع"), literal(tenderId?.value ?: "CASH")),
                recordValue(text("Original cash custody", "العهدة النقدية الأصلية"), literal(cash.id.value)),
                recordValue(text("Before acting", "قبل التصرف"), text("Save, then start once. Act only on a live native instruction.", "احفظ ثم ابدأ مرة واحدة. تصرف فقط عند وجود تعليمات أصلية سارية."))))
    }

    private suspend fun gatewaySupplierCash(access: GatewayAccess, recordingOperationId: OperationId): LocalSupplierCashViewV1 {
        val query = LocalSupplierCashQueryV1(recordingOperationId)
        val value = LocalSupplierSettlementCodec.cash(access.read(ReadRequest(LocalSupplierCashProfile.READ,
            LocalSupplierSettlementCodec.cash(query))).payload)
        require(value.recordingOperationId == recordingOperationId)
        if (value.unavailableCode == "purchasing.capacity_unavailable") throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
        if (value.unavailableCode != null) unavailable()
        requireSupplierRead(value, journal.owner)
        return value
    }

    private suspend fun supplierCash(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupplierCash): Preparation {
        if (selected.amount.currency != com.ukkera.pos.types.Currency.EGP || selected.amount.minor <= 0L ||
            selected.reason.length !in 1..128 || selected.reason.any { !it.isLetterOrDigit() && it !in "._-" }) invalid()
        hint(access, SecurityAction.SUPPLIER_CASH_SETTLE, selected.invoiceId ?: selected.supplierId)
        val cash = ownSession(access)
        val query = LocalSupplierSettlementSourceQueryV1(selected.supplierId, selected.invoiceId, cash.id)
        val observed = supplierSettlementSource(access, query)
        val source = observed.source ?: unavailable()
        // OwnCash supplies the genuine session selector and DOMAIN revision. Supplier Start
        // uses the independent source read's STORAGE generations, never equating the two.
        require(source.supplierId == selected.supplierId && (source as? LocalSupplierCashSource.Invoice)?.invoiceId == selected.invoiceId &&
            (source is LocalSupplierCashSource.Advance) == (selected.invoiceId == null))
        val available = observed.availableCash ?: unavailable()
        if (selected.amount.minor > available.minor || selected.invoiceId != null &&
            selected.amount.minor > (observed.availableToAllocate ?: unavailable()).minor) invalid()
        val body = LocalSupplierCashRequest.StartCash(source, DomainId.random(),
            if (selected.invoiceId == null) null else DomainId.random(), cash.id,
            observed.sessionRevision ?: unavailable(), observed.drawerRevision ?: unavailable(), observed.custodianRevision ?: unavailable(),
            observed.workRevision ?: unavailable(), available, selected.amount, DomainId.random(), OperationId.random(), OperationId.random(),
            DomainId.random(), DomainId.random(), selected.reason)
        val operationId = OperationId.random()
        return prepared(LocalSupplierCashCodec.command(operationId, body), LocalRetailPhysicalKind.SUPPLIER_CASH_OUT,
            text("Prepare one supplier CASH payout", "إعداد دفعة نقدية واحدة للمورد"), supplierCashStartSummary(body, observed, operationId))
    }

    private suspend fun supplierCashApply(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupplierCashApply): Preparation {
        val actual = gatewaySupplierCash(access, selected.originalRecordingOperationId)
        val target = checkNotNull(actual.target)
        val resolution = selected.phase == LocalRetailObservationPhase.RESOLUTION
        val retainedBody = (if (resolution) actual.resolutionBody else actual.originalBody) ?: unavailable()
        val outcome = when (val retained = LocalSupplierCashCodec.decode(StoredBytes(retainedBody.encodeToByteArray()))) {
            is LocalSupplierCashRequest.RecordOriginal -> { require(!resolution && retained.target == target); retained.outcome }
            is LocalSupplierCashRequest.RecordResolution -> { require(resolution && retained.target == target); retained.outcome }
            else -> unavailable()
        }
        hint(access, if (resolution) SecurityAction.SUPPLIER_CASH_RESOLVE else SecurityAction.SUPPLIER_CASH_SETTLE,
            target.invoiceId ?: target.supplierId)
        val body: LocalSupplierCashRequest = if (resolution) LocalSupplierCashRequest.ApplyResolution(target.resolutionOperationId)
            else LocalSupplierCashRequest.ApplyOriginal(target.recordingOperationId)
        return prepared(LocalSupplierCashCodec.command(OperationId.random(), body), LocalRetailPhysicalKind.APPLICATION,
            text("Apply existing supplier cash evidence — SQL only", "تطبيق دليل نقدية المورد الموجود — قاعدة البيانات فقط"), listOf(
                recordValue(text("Original recording ID", "معرف الإفادة الأصلي"), literal(target.recordingOperationId.value)),
                recordValue(text("Original fixed resolution ID", "معرف التسوية الأصلي الثابت"), literal(target.resolutionOperationId.value)),
                recordValue(text("Original Start ID", "معرف البدء الأصلي"), literal(target.intentOperationId.value)),
                recordValue(text("Actual selected phase", "المرحلة المختارة الفعلية"), literal(selected.phase.name)),
                recordValue(text("Actual selected retained fact ID", "معرف الإفادة المحفوظة المختارة الفعلي"), literal(target.factOperation(
                    if (resolution) LocalSupplierCashPhase.RESOLUTION else LocalSupplierCashPhase.ORIGINAL).value)),
                recordValue(text("Original paying owner", "المالك الدافع الأصلي"), literal(journal.owner.actorId.value)),
                recordValue(text("Original paying cash session", "جلسة النقدية الدافعة الأصلية"), literal(target.sessionId.value)),
                recordValue(text("Original attempt amount", "مبلغ المحاولة الأصلية"), moneyText(checkNotNull(actual.amount))),
                recordValue(text("No new physical action", "لا حركة فعلية جديدة"), text(
                    "Only the literal retained observation is applied. No new payout, invoice recognition, stock, expense or credit is created; unsupported amounts/tenders remain pending.",
                    "تُطبق الإفادة الحرفية المحفوظة فقط. لا تُنشأ دفعة أو اعتراف بالفاتورة أو مخزون أو مصروف أو خصم جديد؛ وتظل المبالغ ووسائل الدفع غير المدعومة معلقة."))) +
                supplierObservationSummary(outcome.supplierUiObservation()))
    }

    private suspend fun supplierCashResolve(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupplierCashResolve): Preparation {
        val actual = gatewaySupplierCash(access, selected.originalRecordingOperationId)
        val projection = requireSupplierRead(actual, journal.owner)
        val target = projection.domain.target
        val original = StoredBytes((actual.originalBody ?: unavailable()).encodeToByteArray())
        val originalRequest = LocalSupplierCashCodec.decode(original) as? LocalSupplierCashRequest.RecordOriginal ?: unavailable()
        if (originalRequest.outcome !is LocalSupplierCashOutcome.Unknown || selected.observation is LocalRetailObservation.Unknown ||
            (actual.originalApplied as? LocalSupplierCashResult.Applied)?.action != LocalSupplierCashAction.UNKNOWN || actual.resolutionBody != null) invalid()
        hint(access, SecurityAction.SUPPLIER_CASH_RESOLVE, target.invoiceId ?: target.supplierId)
        val body = LocalSupplierCashRequest.RecordResolution(target, com.ukkera.pos.security.sha256(original.copyBytes()),
            access.view.actorId, selected.observation.supplierCashObservation())
        LocalSupplierCashResolutionDescriptor.create(projection.requirement, original, access.view.actorId, LocalSupplierCashCodec.encode(body))
        return prepared(LocalSupplierCashCodec.command(target.resolutionOperationId, body), LocalRetailPhysicalKind.RESOLUTION,
            text("Retain fixed same-cashier supplier UNKNOWN resolution", "حفظ تسوية مورد ثابتة للنتيجة المجهولة من الموظف نفسه"), listOf(
                recordValue(text("Original recording ID", "معرف الإفادة الأصلي"), literal(target.recordingOperationId.value)),
                recordValue(text("Original fixed resolution ID", "معرف التسوية الأصلي الثابت"), literal(target.resolutionOperationId.value)),
                recordValue(text("No new physical action", "لا حركة فعلية جديدة"), text(
                    "Retain actual evidence about the OLD payout only, under the actual original owner and current resolve grant. Never pay again or replace the fixed ID.",
                    "احفظ الدليل الفعلي عن الدفعة السابقة فقط، تحت المالك الأصلي الحقيقي وصلاحية التسوية الحالية. لا تدفع مجددًا ولا تستبدل المعرف الثابت."))) +
                supplierObservationSummary(selected.observation))
    }

    private suspend fun recoverSupplierResult(access: GatewayAccess, entry: StoredClientIntentV1,
        reply: MutationReply.Recorded): LocalRetailPhysicalReply {
        requireSupplierEntry(entry)
        val result = LocalSupplierCashCodec.result(reply.result)
        val target = when (result) {
            is LocalSupplierCashResult.AttemptStarted -> result.target
            is LocalSupplierCashResult.RawRetained -> result.target
            is LocalSupplierCashResult.Applied -> result.target
            else -> unavailable()
        }
        val actual = gatewaySupplierCash(access, target.recordingOperationId)
        require(actual.target == target)
        if (result is LocalSupplierCashResult.AttemptStarted) requireSupplierProjection(entry,
            requireSupplierRead(actual, journal.owner), result)
        if (entry.localAction == LocalAction.SUPPLIER_CASH_RESOLUTION) {
            val body = LocalSupplierCashCodec.decode(entry.request().payload) as? LocalSupplierCashRequest.RecordResolution ?: unavailable()
            require(actual.resolutionBody == LocalSupplierCashCodec.encode(body).copyBytes().decodeToString())
        }
        if (result is LocalSupplierCashResult.Applied) require(result ==
            (if (result.phase == LocalSupplierCashPhase.ORIGINAL) actual.originalApplied else actual.resolutionApplied))
        return LocalRetailPhysicalReply.Progress(supplierFactProgress(actual, journal.owner, entry.operationId))
    }

    private suspend fun foodPayment(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Payment): Preparation {
        require(selected.subject == LocalPaymentSubject.FOOD)
        if (selected.externalTenderId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        val collection = foodCollection(access, selected.collectionId)
        if (collection.executionProfile != "GENUINE_FOOD_CASH_V1") throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (collection.workClosed || collection.saleId != null || collection.cancellationRefundId != null ||
            collection.status !in setOf("LOCKED_UNPAID", "PART_PAID") ||
            collection.attempts.any { it.status in setOf("AWAITING_ATTESTATION", "OUTCOME_UNKNOWN") } ||
            collection.portions.any { portion -> portion.chargeDecisionRequired || when (portion.stockOrigin) {
                FoodStockOrigin.RESERVED_FOR_COLLECTION -> portion.preparationStatus != FoodPreparationStatus.NOT_SENT ||
                    portion.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER || portion.reservationStatus != "RESERVED"
                FoodStockOrigin.ISSUED_BEFORE_COLLECTION -> portion.reservationStatus != "ISSUED"
            } }) invalid()
        if (collection.portions.any { it.stockOrigin == FoodStockOrigin.ISSUED_BEFORE_COLLECTION })
            requireFoodPaymentIssuedPreparations(access, collection)
        val amount = selected.amount ?: collection.unpaidRemaining
        if (amount.currency != collection.unpaidRemaining.currency || amount.minor <= 0L || amount.minor > collection.unpaidRemaining.minor) invalid()
        hint(access, SecurityAction.COLLECTION_ATTEST, collection.collectionId)
        val cash = ownSession(access)
        // The private collection DTO deliberately does not disclose another cash custodian.
        // This real OwnCash selector is matched to actual collection custody by the kernel.
        val body = LocalFoodPaymentRequest.StartCash(collection.collectionId, collection.orderStorageRevision,
            collection.collectionDomainRevision, cash.id, cash.revision, DomainId.random(), amount,
            recordingOperationId = OperationId.random(), resolutionOperationId = OperationId.random(),
            masterAllocationId = DomainId.random(), clientAllocationId = DomainId.random())
        return prepared(LocalFoodPaymentCodec.command(OperationId.random(), body), LocalRetailPhysicalKind.PAYMENT,
            text("Prepare one food cash collection attempt", "إعداد محاولة تحصيل نقدية طعام واحدة"), listOf(
                recordValue(text("Food collection", "تحصيل الطعام"), literal(collection.collectionId.value)),
                recordValue(text("Original food order", "طلب الطعام الأصلي"), literal(collection.orderId.value)),
                recordValue(text("Amount", "المبلغ"), moneyText(amount)), recordValue(text("Tender", "وسيلة الدفع"), literal("CASH")),
                recordValue(text("Own cash session", "جلسة النقدية الخاصة"), literal(cash.id.value)),
                recordValue(text("Before acting", "قبل التصرف"), text("Save, then start once. Act only on the original live native instruction.",
                    "احفظ ثم ابدأ مرة واحدة. تصرف فقط عند وجود التعليمات الأصلية السارية."))))
    }

    /** The thin collection view is not preparation/issue evidence. Close its whole current
     * owner roster before admitting already-issued work; the master still owns CASH authority. */
    private suspend fun requireFoodPaymentIssuedPreparations(access: GatewayAccess, collection: LocalFoodCollectionViewV1) {
        val expected = collection.portions.associateBy { it.portionId }
        val seen = mutableSetOf<DomainId>()
        var after: DomainId? = null
        do {
            val cut = foodRead(access, LocalFoodQueryV1.Order(collection.orderId, after))
            val detail = cut.result.detail ?: unavailable()
            val order = detail.summary
            val financial = detail.financialHandoff ?: unavailable()
            if (order.revision != collection.orderStorageRevision || order.financialCollectionId != collection.collectionId ||
                order.portionCount != expected.size || detail.collectionState != collection.status ||
                financial.expectation.collectionId != collection.collectionId ||
                financial.expectation.expectedCollectionRevision != collection.collectionDomainRevision ||
                financial.status.name != collection.status || financial.collected != collection.collected ||
                financial.remaining != collection.unpaidRemaining) unavailable()
            if (order.workClosed || order.handoffId != null || order.intendedRecipientId != null || detail.chargeResolutionRequired) invalid()
            for (actual in detail.portions) {
                val portion = expected[actual.portionId] ?: unavailable()
                val preparation = actual.preparation
                if (!seen.add(actual.portionId) || actual.quantity != portion.originalQuantity ||
                    preparation.revision != portion.preparationDomainRevision || preparation.status != portion.preparationStatus ||
                    preparation.fulfillment != portion.fulfillment) unavailable()
                if (portion.stockOrigin == FoodStockOrigin.ISSUED_BEFORE_COLLECTION &&
                    (!FoodCurrentUnpaidPayable.isOrdinaryUnpaid(preparation) || preparation.reservationId == null || preparation.issueId == null ||
                        preparation.fulfillment == FoodFulfillmentStatus.SERVED && order.service.channel != LocalSalesChannel.DINE_IN)) invalid()
            }
            after = cut.result.nextAfter
            if (after != null && seen.size >= expected.size) unavailable()
        } while (after != null)
        if (seen != expected.keys) unavailable()
    }

    private suspend fun foodRefund(access: GatewayAccess, selected: LocalRetailPhysicalRequest.FoodRefund): Preparation {
        val read = foodRefundObligationRead(access, selected.refundId).result
        val context = foodRefundUiContext(read)
        val due = context.dues.singleOrNull { it.originalPaymentId == selected.originalPaymentId } ?: unavailable()
        if (due.unresolved) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        if (due.tender != "CASH") throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        hint(access, SecurityAction.REFUND_RECORD, selected.refundId)
        val cash = ownSession(access)
        val body = LocalFoodRefundRequest.StartCash(selected.refundId, read.value.refundDomainRevision,
            cash.id, cash.revision, DomainId.random(), due.originalPaymentId, due.amount,
            OperationId.random(), OperationId.random(), DomainId.random(), DomainId.random())
        return prepared(LocalFoodRefundCodec.command(OperationId.random(), body), LocalRetailPhysicalKind.REFUND,
            text("Prepare one cash refund of accepted FOOD actual-tender dues", "إعداد رد نقدي واحد لمستحقات طعام مقبولة بوسيلة الدفع الفعلية"), listOf(
                recordValue(text("Accepted FOOD refund", "رد الطعام المقبول"), literal(selected.refundId.value)),
                recordValue(text("Original order", "الطلب الأصلي"), literal(read.orderId.value)),
                recordValue(text("Original payment", "الدفع الأصلي"), literal(due.originalPaymentId.value)),
                recordValue(text("Actual tender due — not the unpaid quoted total", "المستحق بوسيلة الدفع الفعلية — ليس باقي التسعير غير المدفوع"), moneyText(due.amount)),
                recordValue(text("Current paying cash session", "جلسة النقدية الدافعة الحالية"), literal(cash.id.value)),
                recordValue(text("Before acting", "قبل التصرف"), text("Save, then Start once. Pay only on the genuine live native instruction. No stock or revenue action is requested.",
                    "احفظ ثم ابدأ مرة واحدة. ادفع فقط عند وجود تعليمات أصلية سارية فعلًا. لا يُطلب إجراء للمخزون أو الإيراد."))))
    }

    private suspend fun foodRefundApply(access: GatewayAccess, selected: LocalRetailPhysicalRequest.FoodRefundApply): Preparation {
        val phase = selected.phase.foodRefundFactPhase()
        val target: LocalFoodRefundTarget
        val amount: Money
        val body: LocalFoodRefundRequest
        if (phase == LocalFoodRefundFactPhase.ORIGINAL) {
            val actual = gatewayFoodRefundOriginal(access, selected.factOperationId) ?: unavailable()
            target = actual.summary.target; amount = actual.summary.amount
            body = LocalFoodRefundRequest.ApplyOriginal(selected.factOperationId)
        } else {
            val actual = gatewayFoodRefundResolution(access, selected.factOperationId) ?: unavailable()
            target = actual.summary.target; amount = actual.summary.amount
            body = LocalFoodRefundRequest.ApplyResolution(selected.factOperationId)
        }
        return prepared(LocalFoodRefundCodec.command(OperationId.random(), body), LocalRetailPhysicalKind.APPLICATION,
            text("Apply the existing FOOD payout fact — SQL only", "تطبيق إفادة رد الطعام الموجودة — قاعدة البيانات فقط"), listOf(
                recordValue(text("Existing fact", "الإفادة الموجودة"), literal(selected.factOperationId.value)),
                recordValue(text("Phase", "المرحلة"), literal(phase.name)),
                recordValue(text("Accepted FOOD refund", "رد الطعام المقبول"), literal(target.refundId.value)),
                recordValue(text("Original attempt amount", "مبلغ المحاولة الأصلية"), moneyText(amount)),
                recordValue(text("No new action", "لا حركة جديدة"), text("Apply only the retained fact under the same original paying actor/session. No payout, stock, kitchen, entitlement or revenue replay.",
                    "تطبيق الإفادة المحفوظة فقط لنفس الموظف والجلسة الدافعين الأصليين. لا يتكرر الرد أو المخزون أو المطبخ أو الاستحقاق أو الإيراد."))))
    }

    private suspend fun foodRefundResolve(access: GatewayAccess, selected: LocalRetailPhysicalRequest.FoodRefundResolve): Preparation {
        val original = gatewayFoodRefundOriginal(access, selected.originalRecordingOperationId) ?: unavailable()
        val summary = original.summary
        if (original.outcome !is LocalFoodRefundOutcome.Unknown || selected.observation is LocalRetailObservation.Unknown ||
            gatewayFoodRefundResolution(access, summary.target.resolutionOperationId) != null) invalid()
        hint(access, SecurityAction.REFUND_RESOLVE, DomainId(summary.target.recordingOperationId.value))
        val body = LocalFoodRefundRequest.RecordResolution(summary.target, summary.bodyDigest, access.view.actorId,
            selected.observation.foodRefundObservation())
        return prepared(LocalFoodRefundCodec.command(summary.target.resolutionOperationId, body), LocalRetailPhysicalKind.RESOLUTION,
            text("Retain same-cashier evidence about the OLD FOOD payout", "حفظ دليل الموظف نفسه عن رد الطعام السابق"), listOf(
                recordValue(text("Original fact", "الإفادة الأصلية"), literal(summary.target.recordingOperationId.value)),
                recordValue(text("Fixed reserved resolution ID", "معرف التسوية المحجوز الثابت"), literal(summary.target.resolutionOperationId.value)),
                recordValue(text("Original paying actor", "الموظف الدافع الأصلي"), literal(summary.originalActorId.value)),
                recordValue(text("No new action", "لا حركة جديدة"), text("Retain actual evidence through the authorized native owner. Never pay again. Apply the original UNKNOWN before applying this resolution; foreign takeover is unavailable.",
                    "احفظ الدليل الفعلي عبر المالك الأصلي المصرح به. لا تدفع مجددًا. طبق الأصل مجهول النتيجة قبل تطبيق التسوية؛ لا يُتاح الاستحواذ من موظف آخر."))) +
                retailObservationSummary(selected.observation))
    }

    private suspend fun refund(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Refund): Preparation {
        val accepted = acceptedRefund(access, selected.saleId, selected.claimId, selected.returnId)
        val due = retailRefundDues(accepted).singleOrNull { it.originalPaymentId == selected.originalPaymentId } ?: unavailable()
        if (accepted.attempts.any { refundUnresolved(it.status) }) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        hint(access, SecurityAction.REFUND_RECORD, accepted.returnId)
        val cash = ownSession(access)
        val body = LocalReturnRequestV3.Start(accepted.saleId, accepted.claimId, accepted.returnId, accepted.revision,
            cash.id, cash.revision, DomainId.random(), due.originalPaymentId, due.amount, OperationId.random(), OperationId.random(), DomainId.random(), DomainId.random())
        return prepared(MutationRequest(OperationId.random(), LocalFinancialJournalProfileV3.RETURN_COMMAND, LocalReturnCommandCodecV3.encode(body)),
            LocalRetailPhysicalKind.REFUND, text("Prepare one refund of accepted return dues", "إعداد رد واحد لمستحقات مرتجع مقبول"), listOf(
                recordValue(text("Accepted return", "المرتجع المقبول"), literal(accepted.returnId.value)), recordValue(text("Original sale", "البيع الأصلي"), literal(accepted.saleId.value)),
                recordValue(text("Original payment", "الدفع الأصلي"), literal(due.originalPaymentId.value)), recordValue(text("Recorded due", "المستحق المسجل"), moneyText(due.amount)),
                recordValue(text("Original tender", "وسيلة الدفع الأصلية"), literal(due.tender)), recordValue(text("Paying cash session", "جلسة النقدية الدافعة"), literal(cash.id.value))))
    }

    private suspend fun acceptedRefund(access: GatewayAccess, saleId: DomainId, claimId: DomainId,
        returnId: DomainId): LocalAcceptedReturnViewV1 {
        val result = LocalReturnReadCodec.decode(access.read(LocalReturnReadCodec.request(
            LocalReturnQueryV1.Accepted(saleId, claimId, returnId))).payload) as? LocalReturnReadResultV1.AcceptedReturns ?: unavailable()
        val accepted = result.returns.singleOrNull() ?: unavailable()
        require(result.nextAfter == null && accepted.saleId == saleId && accepted.claimId == claimId && accepted.returnId == returnId)
        requireAcceptedRefund(accepted)
        return accepted
    }

    private suspend fun fact(access: GatewayAccess, id: OperationId, phase: LocalJournalFactPhaseV3): LocalFinancialReadResultV3.Fact? {
        return when (val read = LocalFinancialReadCodecV3.decode(access.read(LocalFinancialReadCodecV3.request(LocalFinancialQueryV3.Fact(id, phase))).payload)) {
            LocalFinancialReadResultV3.NotAvailable -> null
            is LocalFinancialReadResultV3.Fact -> read.also {
                requireRetailFact(it)
                require(it.summary.recordingOperationId == id && it.summary.phase == phase && it.summary.originalActorId == journal.owner.actorId)
            }
            else -> unavailable()
        }
    }

    private fun factProgress(value: LocalFinancialReadResultV3.Fact, id: OperationId = value.summary.recordingOperationId): LocalRetailPhysicalProgress =
        retailFactProgress(value.summary, id, value.payment is LocalPaymentOutcomeV3.Unknown || value.refund is LocalRefundOutcomeV3.Unknown)

    private suspend fun foodOriginal(access: GatewayAccess, id: OperationId): LocalFoodPaymentReadResult.Original? =
        when (val read = LocalFoodPaymentReadCodec.decode(access.read(LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(id))).payload)) {
            LocalFoodPaymentReadResult.NotAvailable -> null
            LocalFoodPaymentReadResult.CapacityRequired -> throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
            is LocalFoodPaymentReadResult.Original -> read.also {
                requireFoodOriginal(it)
                require(it.summary.target.recordingOperationId == id && it.summary.originalActorId == journal.owner.actorId &&
                    it.summary.recordingActorId == journal.owner.actorId)
            }
            else -> unavailable()
        }

    private suspend fun foodApply(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Apply): Preparation {
        require(selected.subject == LocalPaymentSubject.FOOD)
        val phase = selected.phase.foodFactPhase()
        val target: LocalFoodPaymentTarget
        val amount: Money
        val body: LocalFoodPaymentRequest
        if (phase == LocalFoodPaymentFactPhase.ORIGINAL) {
            val actual = foodOriginal(access, selected.recordingOperationId) ?: unavailable()
            target = actual.summary.target; amount = actual.summary.amount
            body = LocalFoodPaymentRequest.ApplyOriginal(selected.recordingOperationId)
        } else {
            val actual = foodResolutionFact(access, selected.recordingOperationId) ?: unavailable()
            // Own/private reader already requires SAME original owner and actual recorder.
            target = actual.summary.target; amount = actual.summary.amount
            body = LocalFoodPaymentRequest.ApplyResolution(selected.recordingOperationId)
        }
        return prepared(LocalFoodPaymentCodec.command(OperationId.random(), body),
            LocalRetailPhysicalKind.APPLICATION, text("Apply the recorded food fact — SQL only", "تطبيق إفادة الطعام المسجلة — قاعدة البيانات فقط"), listOf(
                recordValue(text("Recorded fact", "الإفادة المسجلة"), literal(selected.recordingOperationId.value)),
                recordValue(text("Phase", "المرحلة"), literal(phase.name)),
                recordValue(text("Food collection", "تحصيل الطعام"), literal(target.collectionId.value)),
                recordValue(text("Amount", "المبلغ"), moneyText(amount)),
                recordValue(text("No physical action", "لا حركة فعلية"), text("Apply only this existing fact. No instruction or new money collection is requested.",
                    "تطبيق هذه الإفادة الموجودة فقط. لا تُطلب تعليمات حركة أو تحصيل مال جديد."))))
    }

    private suspend fun foodResolutionFact(access: GatewayAccess, id: OperationId): LocalFoodPaymentReadResult.Resolution? =
        when (val result = LocalFoodPaymentReadCodec.decode(access.read(LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Resolution(id))).payload)) {
            LocalFoodPaymentReadResult.NotAvailable -> null
            LocalFoodPaymentReadResult.CapacityRequired -> throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
            is LocalFoodPaymentReadResult.Resolution -> result.also { value ->
                require(value.summary.target.resolutionOperationId == id && value.summary.originalActorId == journal.owner.actorId &&
                    value.summary.recordingActorId == journal.owner.actorId)
                val original = foodOriginal(access, value.summary.target.recordingOperationId) ?: unavailable()
                require(original.outcome is LocalFoodCashOutcome.Unknown)
                requireFoodResolution(value, original.summary)
            }
            else -> unavailable()
        }

    private fun foodSupervisorReview(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupervisorReview): Preparation {
        require(selected.subject == LocalPaymentSubject.FOOD)
        // No foreign own/private read precedes the separate audited supervisor command.
        return prepared(LocalFoodPaymentSupervisorCodec.command(OperationId.random(),
            LocalFoodPaymentSupervisorRequest.Review(selected.recordingOperationId)), LocalRetailPhysicalKind.SUPERVISOR_REVIEW,
            text("Request independently authorized FOOD supervisor review", "طلب مراجعة مشرف للطعام بتصريح مستقل"), listOf(
                recordValue(text("Original FOOD observation", "إفادة الطعام الأصلية"), literal(selected.recordingOperationId.value)),
                recordValue(text("Current reviewer", "المراجع الحالي"), literal(access.view.actorId.value)),
                recordValue(text("Disclosure boundary", "حد الإفصاح"), text("No original details are disclosed before current authorization and durable audit.",
                    "لا تُكشف التفاصيل الأصلية قبل التصريح الحالي والتدقيق الدائم."))))
    }

    private suspend fun foodAuditedReview(access: GatewayAccess, operationId: OperationId): LocalFoodPaymentSupervisorReview {
        val entry = required(journal.find(operationId)) ?: unavailable()
        requireFoodEntry(entry)
        if (entry.localAction != LocalAction.FOOD_SUPERVISOR_REVIEW) invalid()
        val found = access.lookup(entry) as? LookupReply.Found ?: unavailable()
        val actual = foodReview(entry, found.result)
        val known = StoredKernelResultV1.from(found.result)
        if (entry.recorded != null && entry.recorded != known) unavailable()
        journal.remember(operationId, known)
        return actual
    }

    private suspend fun foodSupervisorApply(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupervisorApply): Preparation {
        require(selected.subject == LocalPaymentSubject.FOOD)
        val review = foodAuditedReview(access, selected.reviewOperationId)
        val phase = selected.phase.foodFactPhase()
        val target = review.original.target
        val amount = if (phase == LocalFoodPaymentFactPhase.ORIGINAL) review.original.amount else (review.resolution ?: unavailable()).amount
        val id = foodFactOperation(target, phase)
        return prepared(LocalFoodPaymentSupervisorCodec.command(OperationId.random(), LocalFoodPaymentSupervisorRequest.Apply(id, phase)),
            LocalRetailPhysicalKind.APPLICATION, text("Supervised FOOD application — SQL only", "تطبيق الطعام بإشراف — قاعدة البيانات فقط"), listOf(
                recordValue(text("Audited review", "المراجعة المدققة"), literal(selected.reviewOperationId.value)),
                recordValue(text("Recorded FOOD fact", "إفادة الطعام المسجلة"), literal(id.value)),
                recordValue(text("Original custodian", "موظف العهدة الأصلي"), literal(review.original.originalActorId.value)),
                recordValue(text("Current accounting actor", "موظف المحاسبة الحالي"), literal(access.view.actorId.value)),
                recordValue(text("Amount", "المبلغ"), moneyText(amount)),
                recordValue(text("No new physical or stock action", "لا حركة فعلية أو مخزنية جديدة"), text("Current supervisor authorization is checked again when applying.",
                    "يُفحص تصريح المشرف الحالي مجددًا عند التطبيق."))))
    }

    private suspend fun foodResolution(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Resolve): Preparation {
        require(selected.subject == LocalPaymentSubject.FOOD)
        val review = foodAuditedReview(access, selected.reviewOperationId)
        if (review.resolution != null || review.originalOutcome !is LocalFoodCashOutcome.Unknown || selected.observation is LocalRetailObservation.Unknown) invalid()
        val original = review.original
        val target = original.target
        val body = LocalFoodPaymentRequest.RecordResolution(target, original.bodyDigest, access.view.actorId,
            selected.observation.foodPaymentObservation())
        return prepared(LocalFoodPaymentCodec.command(target.resolutionOperationId, body), LocalRetailPhysicalKind.RESOLUTION,
            text("Save one fixed linked FOOD resolution — no new action", "حفظ تسوية طعام مرتبطة واحدة ثابتة — دون حركة جديدة"), listOf(
                recordValue(text("Original FOOD UNKNOWN", "نتيجة الطعام الأصلية المجهولة"), literal(target.recordingOperationId.value)),
                recordValue(text("Fixed resolution operation", "عملية التسوية الثابتة"), literal(target.resolutionOperationId.value)),
                recordValue(text("Exact original body digest", "بصمة الإفادة الأصلية الدقيقة"), literal(original.bodyDigest.value)),
                recordValue(text("Original custodian", "موظف العهدة الأصلي"), literal(original.originalActorId.value)),
                recordValue(text("Current resolver", "موظف التسوية الحالي"), literal(access.view.actorId.value)),
                recordValue(text("Retention boundary", "حد الحفظ"), text("Saving is input custody only. Current authorization and client-first genuine native retention precede subsequent SQL retention; never collect again.",
                    "الحفظ حيازة للمدخل فقط. يسبق التصريح الحالي والحفظ الأصلي الفعلي لدى العميل حفظ قاعدة البيانات اللاحق؛ لا تكرر التحصيل."))) +
                retailObservationSummary(selected.observation))
    }

    private suspend fun apply(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Apply): Preparation {
        require(selected.subject == LocalPaymentSubject.RETAIL)
        val phase = selected.phase.retailDomainFact()
        val actual = fact(access, selected.recordingOperationId, phase) ?: unavailable()
        // Ordinary application never adopts another recorder's fact. The UI must explicitly
        // request fresh audited Review(original) followed by the separate SupervisorApply path.
        if (actual.summary.recordingActorId != actual.summary.originalActorId) throw GatewayFailure(LocalServiceProblem.DENIED)
        val kind = actual.summary.target.kind
        val body = if (kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.ApplyObservation(selected.recordingOperationId, phase))
            else LocalReturnCommandCodecV3.encode(LocalReturnRequestV3.ApplyObservation(selected.recordingOperationId, phase))
        val key = if (kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalFinancialJournalProfileV3.COLLECTION_COMMAND else LocalFinancialJournalProfileV3.RETURN_COMMAND
        return prepared(MutationRequest(OperationId.random(), key, body), LocalRetailPhysicalKind.APPLICATION,
            text("Apply the already recorded observation — SQL only", "تطبيق الإفادة المسجلة — قاعدة البيانات فقط"), listOf(
                recordValue(text("Recorded fact", "الإفادة المسجلة"), literal(selected.recordingOperationId.value)),
                recordValue(text("Phase", "المرحلة"), literal(phase.name)), recordValue(text("Amount", "المبلغ"), moneyText(actual.summary.amount)),
                recordValue(text("No new action", "لا حركة جديدة"), text("No instruction, collection, refund or stock issue is requested.", "لا تُطلب تعليمات أو تحصيل أو رد أو صرف مخزون جديد."))))
    }

    private fun supervisorReview(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupervisorReview): Preparation {
        require(selected.subject == LocalPaymentSubject.RETAIL)
        // No unauthorized own-fact read or another employee's drawer inspection precedes audit.
        val body = LocalRetailSupervisorCodecV1.encode(LocalRetailSupervisorRequestV1.Review(selected.recordingOperationId))
        return prepared(MutationRequest(OperationId.random(), LocalRetailSupervisorCodecV1.COMMAND, body), LocalRetailPhysicalKind.SUPERVISOR_REVIEW,
            text("Request separately authorized, audited supervisor review", "طلب مراجعة مشرف بتصريح وتدقيق مستقلين"), listOf(
                recordValue(text("Original observation ID", "معرف الإفادة الأصلية"), literal(selected.recordingOperationId.value)),
                recordValue(text("Current reviewer", "المراجع الحالي"), literal(access.view.actorId.value)),
                recordValue(text("Disclosure boundary", "حد الإفصاح"), text("No original details are disclosed until authorization and audit commit.", "لا تُكشف التفاصيل الأصلية قبل التصريح وتثبيت التدقيق."))))
    }

    private suspend fun review(access: GatewayAccess, operationId: OperationId): LocalRetailSupervisorReviewV1 {
        val entry = required(journal.find(operationId)) ?: unavailable()
        requireRetailEntry(entry)
        if (entry.localAction != LocalAction.RETAIL_SUPERVISOR_REVIEW) invalid()
        val found = access.lookup(entry) as? LookupReply.Found ?: unavailable()
        val actual = retailReview(entry, found.result)
        val known = StoredKernelResultV1.from(found.result)
        if (entry.recorded != null && entry.recorded != known) unavailable()
        journal.remember(operationId, known)
        return actual
    }

    private suspend fun supervisorApply(access: GatewayAccess, selected: LocalRetailPhysicalRequest.SupervisorApply): Preparation {
        require(selected.subject == LocalPaymentSubject.RETAIL)
        val reviewed = review(access, selected.reviewOperationId)
        val phase = selected.phase.retailDomainFact()
        val selectedFact = if (phase == LocalJournalFactPhaseV3.ORIGINAL) reviewed.original else reviewed.resolution ?: unavailable()
        val body = LocalRetailSupervisorCodecV1.encode(LocalRetailSupervisorRequestV1.Apply(selectedFact.target.kind, selectedFact.recordingOperationId, phase))
        return prepared(MutationRequest(OperationId.random(), LocalRetailSupervisorCodecV1.COMMAND, body), LocalRetailPhysicalKind.APPLICATION,
            text("Supervised accounting of the recorded fact — SQL only", "معالجة محاسبية بإشراف للإفادة المسجلة — قاعدة البيانات فقط"), listOf(
                recordValue(text("Audited review", "المراجعة المدققة"), literal(selected.reviewOperationId.value)),
                recordValue(text("Recorded observation", "الإفادة المسجلة"), literal(selectedFact.recordingOperationId.value)),
                recordValue(text("Original custody actor", "موظف العهدة الأصلي"), literal(selectedFact.originalActorId.value)),
                recordValue(text("Current accounting actor", "موظف المحاسبة الحالي"), literal(access.view.actorId.value)),
                recordValue(text("Amount", "المبلغ"), moneyText(selectedFact.amount))))
    }

    private suspend fun resolution(access: GatewayAccess, selected: LocalRetailPhysicalRequest.Resolve): Preparation {
        require(selected.subject == LocalPaymentSubject.RETAIL)
        val reviewed = review(access, selected.reviewOperationId)
        val original = reviewed.original
        if (reviewed.resolution != null || reviewed.payment !is LocalPaymentOutcomeV3.Unknown && reviewed.refund !is LocalRefundOutcomeV3.Unknown ||
            selected.observation is LocalRetailObservation.Unknown) invalid()
        val target = original.target
        val body = if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalCollectionCommandCodecV3.encode(
            LocalCollectionRequestV3.RecordResolution(target, original.bodyDigest, access.view.actorId, selected.observation.retailPaymentObservation()))
        else LocalReturnCommandCodecV3.encode(LocalReturnRequestV3.RecordResolution(target, original.bodyDigest,
            access.view.actorId, selected.observation.retailRefundObservation()))
        return prepared(MutationRequest(target.resolutionOperationId,
            if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalFinancialJournalProfileV3.COLLECTION_COMMAND else LocalFinancialJournalProfileV3.RETURN_COMMAND, body),
            LocalRetailPhysicalKind.RESOLUTION, text("Record one fixed resolution of the original UNKNOWN", "تسجيل تسوية واحدة ثابتة للنتيجة الأصلية المجهولة"), listOf(
                recordValue(text("Original UNKNOWN", "النتيجة المجهولة الأصلية"), literal(target.recordingOperationId.value)),
                recordValue(text("Reserved resolution", "التسوية المحجوزة"), literal(target.resolutionOperationId.value)),
                recordValue(text("Original custody actor", "موظف العهدة الأصلي"), literal(original.originalActorId.value)),
                recordValue(text("Current resolver", "موظف التسوية الحالي"), literal(access.view.actorId.value)),
                recordValue(text("No new physical action", "لا حركة فعلية جديدة"), text("Record evidence about the old action only; do not take or pay money again.", "سجل دليل الحركة السابقة فقط؛ لا تحصل أو تدفع مالًا مرة أخرى."))) +
                retailObservationSummary(selected.observation))
    }

    private fun instructionDisplay(entry: StoredClientIntentV1, reply: MutationReply.Recorded, canonical: StoredBytes): LocalRetailInstructionDisplay {
        require(reply.committed && reply.operationId == entry.operationId && reply.originalActorId == journal.owner.actorId)
        if (entry.localAction == LocalAction.SUPPLIER_CASH_START) {
            val started = LocalSupplierCashCodec.result(reply.result) as? LocalSupplierCashResult.AttemptStarted ?: unavailable()
            val value = LocalSupplierCashPhysicalProjection.parse(canonical)
            requireSupplierProjection(entry, value, started)
            return supplierDisplay(value.domain.target, value.domain.amount)
        }
        if (entry.localAction == LocalAction.FOOD_REFUND_START) {
            val started = LocalFoodRefundCodec.result(reply.result) as? LocalFoodRefundResult.AttemptStarted ?: unavailable()
            val value = LocalFoodRefundPhysicalProjection.parse(canonical)
            requireFoodRefundProjection(entry, value, started)
            return foodRefundDisplay(value.target, value.amount)
        }
        if (entry.localAction == LocalAction.FOOD_PAYMENT_START) {
            val started = LocalFoodPaymentCodec.result(reply.result) as? LocalFoodPaymentResult.AttemptStarted ?: unavailable()
            val value = LocalFoodPaymentPhysicalProjection.parse(canonical)
            requireFoodProjection(entry, value, started)
            return foodDisplay(value.target, value.amount)
        }
        val started = LocalFinancialResultCodecV3.decode(reply.result) as? LocalFinancialResultV3.AttemptStarted ?: unavailable()
        val value = LocalRetailPhysicalProjection.parse(canonical)
        requireProjection(entry, value, started)
        return retailDisplay(value.target, value.amount, value.tender)
    }

    private fun requireProjection(entry: StoredClientIntentV1, value: LocalRetailPhysicalProjection, started: LocalFinancialResultV3.AttemptStarted) {
        requireRetailEntry(entry)
        val request = entry.request()
        val requirement = value.requirement
        require(value.target == started.target && value.amount == started.amount &&
            com.ukkera.pos.security.sha256(requirement.canonicalBytes.copyBytes()) == PayloadDigest(started.requirementDigest) &&
            value.target == retailTarget(request) && requirement.scope == journal.owner.scope &&
            requirement.originalActorId == journal.owner.actorId && requirement.clientDeviceId == journal.owner.deviceId &&
            requirement.intentOperationId == entry.operationId && requirement.intentSemanticDigest == entry.semanticSha256 && requirement.command == request.command)
        when (entry.localAction) {
            LocalAction.RETAIL_PAYMENT_START -> {
                val body = LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.Start
                require(value.amount == body.amount && requirement.masterAllocationId == body.masterAllocationId && requirement.clientAllocationId == body.clientAllocationId)
                when (val tender = body.tender) {
                    LocalTenderSelectionV1.Cash -> require(value.tender.kind == "CASH" && value.tender.tenderId == null)
                    is LocalTenderSelectionV1.External -> require(value.tender.kind != "CASH" && value.tender.tenderId == tender.tenderId && value.tender.policyRevision == tender.expectedPolicyRevision)
                }
            }
            LocalAction.RETAIL_REFUND_START -> {
                val body = LocalReturnCommandCodecV3.decode(request.payload) as LocalReturnRequestV3.Start
                require(value.amount == body.amount && requirement.masterAllocationId == body.masterAllocationId && requirement.clientAllocationId == body.clientAllocationId)
            }
            else -> unavailable()
        }
    }

    /** Only this exact private registered wrapper can reach its real native handle. */
    private inner class Instruction(private val native: LocalPhysicalPublishedInstruction,
        private val original: PhysicalInteractiveRequestLifetime, private val entry: LocalRetailPhysicalLifetime,
        private val epoch: LocalRetailPhysicalLifetime, private val entryAttachment: LocalRetailPhysicalLifetime.Attachment,
        private val sessionAttachment: LocalRetailPhysicalLifetime.Attachment,
        override val display: LocalRetailInstructionDisplay, private val startAction: LocalAction) : LocalRetailPhysicalInstruction {
        override fun deliverable(): Boolean {
            if (instruction.value !== this || original.denied || entry.denied || epoch.denied || !native.deliverable()) { deny(); return false }
            return true
        }
        override fun deny() {
            original.deny()
            native.deny() // End-only; native Deciding/Accepted/Uncertain custody survives.
            entryAttachment.close()
            sessionAttachment.close()
            instruction.compareAndSet(this, null)
        }
        override fun capture(observation: LocalRetailObservation): LocalRetailPhysicalCapture {
            if (!deliverable()) return LocalRetailPhysicalCapture.Rejected
            // Input conversion is the only pre-native failure that can safely say no body was
            // accepted. Once the native call begins, even a later validation exception is UNCERTAIN.
            val payment: LocalPaymentOutcomeV3?
            val refund: LocalRefundOutcomeV3?
            val food: LocalFoodCashOutcome?
            val foodRefund: LocalFoodRefundOutcome?
            val supplier: LocalSupplierCashOutcome?
            try {
                if (startAction != LocalAction.SUPPLIER_CASH_START) observation.requireNonSupplierObservation()
                supplier = if (startAction == LocalAction.SUPPLIER_CASH_START) observation.supplierCashObservation() else null
                food = if (startAction == LocalAction.FOOD_PAYMENT_START) observation.foodPaymentObservation() else null
                foodRefund = if (startAction == LocalAction.FOOD_REFUND_START) observation.foodRefundObservation() else null
                payment = if (startAction == LocalAction.RETAIL_PAYMENT_START) observation.retailPaymentObservation() else null
                refund = if (startAction == LocalAction.RETAIL_REFUND_START) observation.retailRefundObservation() else null
                require(listOf(food, foodRefund, payment, refund, supplier).count { it != null } == 1)
            } catch (_: Exception) {
                return LocalRetailPhysicalCapture.InvalidInput
            }
            return try {
                // NO coroutine, service callback or state publication before immutable native copy.
                val result = if (supplier != null) native.captureSupplierCash(supplier)
                    else if (food != null) native.captureFoodPayment(food)
                    else if (foodRefund != null) native.captureFoodRefund(foodRefund)
                    else if (payment != null) native.capturePayment(payment)
                    else native.captureRefund(checkNotNull(refund))
                when (result) {
                    is LocalPhysicalOriginalCaptureResult.CapturedInMemory -> {
                        require(result.operationId == display.recordingOperationId)
                        LocalRetailPhysicalCapture.CapturedInMemory(result.operationId, result.bodyDigest.value)
                    }
                    LocalPhysicalOriginalCaptureResult.Rejected -> LocalRetailPhysicalCapture.Rejected
                    LocalPhysicalOriginalCaptureResult.InvalidInput -> LocalRetailPhysicalCapture.InvalidInput
                    LocalPhysicalOriginalCaptureResult.Uncertain -> LocalRetailPhysicalCapture.Uncertain
                }
            } catch (_: Exception) { LocalRetailPhysicalCapture.Uncertain }
            finally { deny() }
        }
    }

    private suspend fun <T> withAccess(body: suspend (GatewayAccess) -> T): T {
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return try { withLease(lease, body) } finally { lease.destroy() }
    }

    private suspend fun <T> withLease(lease: LocalWorkflowCredentialLease, body: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val current = endpoint.current(lease.credentials)
        currentCoroutineContext().ensureActive()
        if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val view = when (current) {
            is LocalSessionViewResult.Current -> current.view
            LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        if (view.actorId != journal.owner.actorId || view.scope != journal.owner.scope || view.deviceId != journal.owner.deviceId)
            throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val access = GatewayAccess(endpoint, lease, view, installed)
        val result = body(access)
        access.checkCurrent()
        return result
    }

    private suspend fun guarded(id: OperationId? = null, body: suspend () -> LocalRetailPhysicalReply): LocalRetailPhysicalReply = journal.requests.withLock {
        try { body() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalRetailPhysicalReply.Unavailable(failure.problem, id) }
        catch (_: IllegalArgumentException) { LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.INVALID_INPUT, id) }
        catch (_: Exception) { LocalRetailPhysicalReply.Unavailable(LocalServiceProblem.UNAVAILABLE, id) }
    }
}

private fun LocalRetailEvidence.retailDomainFact(): LocalCashEvidenceV1 = LocalCashEvidenceV1(evidenceId, occurredAt)
private fun LocalRetailObservation.retailPaymentObservation(): LocalPaymentOutcomeV3 = when (this) {
    is LocalRetailObservation.Unknown -> LocalPaymentOutcomeV3.Unknown(evidence.retailDomainFact())
    is LocalRetailObservation.NotPaid -> LocalPaymentOutcomeV3.NotPaid(evidence.retailDomainFact(), reason)
    is LocalRetailObservation.CashReceived -> LocalPaymentOutcomeV3.CashReceived(received, change, evidence.retailDomainFact())
    is LocalRetailObservation.ExternalAttested -> LocalPaymentOutcomeV3.ExternalAttested(evidence.retailDomainFact(), terminal, reference)
    is LocalRetailObservation.CashPaid -> invalid()
}
private fun LocalRetailObservation.retailRefundObservation(): LocalRefundOutcomeV3 = when (this) {
    is LocalRetailObservation.Unknown -> LocalRefundOutcomeV3.Unknown(evidence.retailDomainFact())
    is LocalRetailObservation.NotPaid -> LocalRefundOutcomeV3.NotPaid(evidence.retailDomainFact(), reason)
    is LocalRetailObservation.CashPaid -> LocalRefundOutcomeV3.CashPaid(evidence.retailDomainFact())
    is LocalRetailObservation.ExternalAttested -> LocalRefundOutcomeV3.ExternalAttested(evidence.retailDomainFact(), terminal, reference)
    is LocalRetailObservation.CashReceived -> invalid()
}
private fun LocalRetailObservation.foodPaymentObservation(): LocalFoodCashOutcome = when (this) {
    is LocalRetailObservation.Unknown -> LocalFoodCashOutcome.Unknown(evidence.retailDomainFact())
    is LocalRetailObservation.NotPaid -> LocalFoodCashOutcome.NotPaid(evidence.retailDomainFact(), reason)
    is LocalRetailObservation.CashReceived -> LocalFoodCashOutcome.CashReceived(received, change, evidence.retailDomainFact())
    is LocalRetailObservation.ExternalAttested -> LocalFoodCashOutcome.ExternalAttested(evidence.retailDomainFact(), terminal, reference)
    is LocalRetailObservation.CashPaid -> invalid()
}
