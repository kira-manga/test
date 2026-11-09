package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalBranchReportCsv
import com.ukkera.pos.sales.store.LocalBranchReportSectionCsvV2
import com.ukkera.pos.sales.store.LocalBranchReportProjectionV1
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportExportCodecV1
import com.ukkera.pos.sales.store.LocalBranchReportExportCoverageV1
import com.ukkera.pos.sales.store.LocalBranchReportExportDestinationV1
import com.ukkera.pos.sales.store.LocalBranchReportExportFileV1
import com.ukkera.pos.sales.store.LocalBranchReportExportOutcomeV1
import com.ukkera.pos.sales.store.LocalBranchReportExportRequestV1
import com.ukkera.pos.sales.store.LocalBranchReportExportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportExportStageV1
import com.ukkera.pos.sales.store.LocalBranchReportExportViewV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Actual master audit observations; none is a publication permit or filesystem certificate. */
private sealed interface ExportAuditDecision {
    data class Recorded(val view: LocalBranchReportExportViewV1, val acknowledgement: LocalBranchReportAuditAcknowledgement) : ExportAuditDecision
    data class Rejected(val acknowledgement: LocalBranchReportAuditAcknowledgement) : ExportAuditDecision
    data class Unavailable(val problem: LocalServiceProblem) : ExportAuditDecision
}

/** Typed begin/finalize/lookup port, not an injected "authorized=true" callback. */
private interface ExportAuditPort {
    suspend fun begin(receipt: LocalBranchReportFileReceipt, command: LocalBranchReportAuditCommand): ExportAuditDecision
    suspend fun finalize(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot, command: LocalBranchReportAuditCommand): ExportAuditDecision
    suspend fun recover(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot, command: LocalBranchReportAuditCommand): ExportAuditDecision
    suspend fun recoverRecorded(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand): ExportAuditDecision
    suspend fun lookup(receipt: LocalBranchReportFileReceipt): LocalBranchReportExportViewV1?
}

private class CurrentMasterExportAudit(private val access: GatewayAccess) : ExportAuditPort {
    override suspend fun begin(receipt: LocalBranchReportFileReceipt, command: LocalBranchReportAuditCommand): ExportAuditDecision =
        submit(receipt, LocalBranchReportAuditSlot.BEGIN, command)
    override suspend fun finalize(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand): ExportAuditDecision {
        check(slot != LocalBranchReportAuditSlot.BEGIN)
        return submit(receipt, slot, command)
    }
    private suspend fun submit(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand): ExportAuditDecision =
        decision(receipt, slot, command, access.reportExportCommand(request(access, receipt, slot, command)))

    override suspend fun recover(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand): ExportAuditDecision = recoverOriginal(receipt, slot, command, repeatIfAbsent = true)

    override suspend fun recoverRecorded(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand): ExportAuditDecision = recoverOriginal(receipt, slot, command, repeatIfAbsent = false)

    private suspend fun recoverOriginal(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand, repeatIfAbsent: Boolean): ExportAuditDecision {
        request(access, receipt, slot, command) // recheck canonical bytes + original semantic identity
        val identity = OperationIdentity(receipt.owner.branchId, command.operationId, receipt.owner.actorId, 1, command.semanticSha256)
        return when (val result = access.reportExportOperation(identity)) {
            is LookupReply.Found -> decision(receipt, slot, command, result.result)
            is LookupReply.AbsentAtCurrentWriter -> {
                if (result.operationId != command.operationId) unavailable()
                if (!repeatIfAbsent) return ExportAuditDecision.Unavailable(LocalServiceProblem.UNAVAILABLE)
                // Explicit reconciliation repeats ONLY the immutable original operation/body.
                // No CSV read, fresh export ID, destination chooser or publication occurs here.
                submit(receipt, slot, command)
            }
            is LookupReply.Deferred -> ExportAuditDecision.Unavailable(if (result.operationId == command.operationId)
                problem(result.reason) else LocalServiceProblem.UNAVAILABLE)
            is LookupReply.IdentityConflict -> ExportAuditDecision.Unavailable(LocalServiceProblem.UNAVAILABLE)
        }
    }

    override suspend fun lookup(receipt: LocalBranchReportFileReceipt): LocalBranchReportExportViewV1? {
        return when (val result = LocalBranchReportExportCodecV1.decodeResult(access.read(LocalBranchReportExportCodecV1.read(receipt.exportId)).payload)) {
            is LocalBranchReportExportResultV1.Recorded -> result.view.also { validateView(receipt, it) }
            LocalBranchReportExportResultV1.NotFound -> null
            is LocalBranchReportExportResultV1.Rejected -> unavailable()
        }
    }

    private fun decision(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
        command: LocalBranchReportAuditCommand, result: MutationReply): ExportAuditDecision = when (result) {
        is MutationReply.Deferred -> ExportAuditDecision.Unavailable(if (result.operationId == command.operationId)
            problem(result.reason) else LocalServiceProblem.UNAVAILABLE)
        is MutationReply.IdentityConflict -> ExportAuditDecision.Unavailable(LocalServiceProblem.UNAVAILABLE)
        is MutationReply.Recorded -> {
            if (result.operationId != command.operationId || result.originalActorId != receipt.owner.actorId) unavailable()
            val digest = result.result.copyBytes().let { bytes -> try { sha256(bytes).value } finally { bytes.fill(0) } }
            when (val body = LocalBranchReportExportCodecV1.decodeResult(result.result)) {
                is LocalBranchReportExportResultV1.Recorded -> {
                    if (!result.committed) unavailable()
                    val view = body.view
                    validateView(receipt, view)
                    val original = LocalBranchReportExportCodecV1.decodeRequest(request(access, receipt, slot, command).payload)
                    if (view.lastOperationId != command.operationId) unavailable()
                    when (original) {
                        is LocalBranchReportExportRequestV1.Begin -> if (view.stage != LocalBranchReportExportStageV1.REQUESTED || view.revision.value != 0L) unavailable()
                        is LocalBranchReportExportRequestV1.Finish -> if (view.revision != original.expectedRevision.next() ||
                            view.stage.name != original.outcome.name || view.file != original.file) unavailable()
                    }
                    ExportAuditDecision.Recorded(view, LocalBranchReportAuditAcknowledgement(LocalBranchReportAuditStage.valueOf(view.stage.name),
                        view.revision, digest, result.authorityEpoch, result.sequence.value, result.recordedAt))
                }
                is LocalBranchReportExportResultV1.Rejected -> {
                    if (result.committed) unavailable()
                    ExportAuditDecision.Rejected(LocalBranchReportAuditAcknowledgement(LocalBranchReportAuditStage.REJECTED, null,
                        digest, result.authorityEpoch, result.sequence.value, result.recordedAt))
                }
                LocalBranchReportExportResultV1.NotFound -> unavailable()
            }
        }
    }
}

internal suspend fun exportLocalBranchReport(access: GatewayAccess, selection: LocalBranchReportExportSelection,
    files: LocalBranchReportFileDelivery, interactive: LocalBranchReportExportLifetime,
    progress: (LocalBranchReportExportProgress) -> Unit): LocalBranchReportExportResult {
    val context = currentCoroutineContext()
    val lifetime = interactive.child { context.isActive && access.lease.isCurrent() }
    val owner = fileOwner(access)
    val audit: ExportAuditPort = CurrentMasterExportAudit(access)
    var destination: LocalBranchReportDestination? = null
    var last: LocalBranchReportFileResult.Recorded? = null
    try {
        requireCurrent(access, lifetime)
        progress(LocalBranchReportExportProgress.CHOOSING_DESTINATION)
        val profile = currentReportProfile(LocalBranchReportSectionV1.valueOf(selection.section.name))
        val fileQuery = LocalBranchReportFileQuery(selection.section.name, selection.after,
            profile?.let { LocalBranchReportFileProfile.valueOf(it.name) }, selection.coverage,
            selectedSaleId = selection.selectedSaleId)
        when (val choice = files.choose(owner, fileQuery, lifetime)) {
            LocalBranchReportFileSelection.Cancelled -> return LocalBranchReportExportResult.ChooserCancelled
            is LocalBranchReportFileSelection.Unavailable -> return LocalBranchReportExportResult.Unavailable(fileProblem = choice.problem, exportId = choice.exportId)
            is LocalBranchReportFileSelection.Selected -> destination = choice.destination
        }
        val selected = checkNotNull(destination)
        var receipt = selected.initialReceipt
        requireCurrent(access, lifetime)
        progress(LocalBranchReportExportProgress.BEGINNING_AUDIT)
        val begun = runAudit(access, audit, files, owner, receipt, LocalBranchReportAuditSlot.BEGIN, begin(receipt), recover = false)
        receipt = begun.receipt
        if (begun.decision !is ExportAuditDecision.Recorded) {
            last = native(files.abandon(selected))
            return outcome(last, decisionProblem(begun.decision))
        }
        requireCurrent(access, lifetime)
        progress(LocalBranchReportExportProgress.READING_CURRENT_PAGE)
        // Begin's global branch-revision advance occurs BEFORE this source cut. Never compare
        // this new cut with the retained VIEW page, silently replace the page, or call it a lease.
        val query = reportQuery(receipt)
        val content = try {
            when (receipt.query.coverage) {
                null -> {
                    val observed = readReportExportPage(access, query, lifetime)
                    ReportExportContent(LocalBranchReportCsv.encodePage(observed.page, observed.branchRevision, observed.observedAt),
                        observed.branchRevision, observed.observedAt, observed.page.nextAfter, observed.page)
                }
                LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2 -> readCompleteReportSection(access, query, lifetime)
            }
        } catch (_: LocalBranchReportSectionCsvV2.CapacityExceeded) {
            last = native(files.abandon(selected))
            return finishObservation(access, audit, files, owner, last, recover = false, problem = LocalServiceProblem.INVALID_INPUT)
        }
        val bytes = content.bytes
        try {
            if (bytes.size !in 1..65_536) {
                last = native(files.abandon(selected))
                return finishObservation(access, audit, files, owner, last, recover = false, problem = LocalServiceProblem.INVALID_INPUT)
            }
            val source = LocalBranchReportExportSource(content.branchRevision, content.observedAt, sha256(bytes).value, bytes.size, content.nextAfter)
            progress(LocalBranchReportExportProgress.WRITING_LOCAL_FILE)
            requireCurrent(access, lifetime)
            native(files.spool(selected, source, bytes))
            content.firstPage?.let { first ->
                // A durable private spool is not a grant. Recheck the actual master after
                // native IO, before disclosure; revoked access or a newer cut stops publication.
                val current = readReportExportPage(access, query, lifetime)
                if (current.branchRevision != content.branchRevision || current.page != first)
                    throw GatewayFailure(LocalServiceProblem.REPORT_RESTART_REQUIRED)
            }
            requireCurrent(access, lifetime)
            last = native(files.publish(selected))
        } finally { bytes.fill(0) }
        requireCurrent(access, lifetime)
        progress(LocalBranchReportExportProgress.REPORTING_OUTCOME)
        return finishObservation(access, audit, files, owner, checkNotNull(last), recover = false)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ExportFileFailure) {
        if (last?.receipt?.phase == LocalBranchReportFilePhase.PUBLISHED && last?.presence == LocalBranchReportFilePresence.VERIFIED_PRESENT)
            return outcome(checkNotNull(last), LocalServiceProblem.STORAGE_UNCERTAIN)
        return LocalBranchReportExportResult.Unavailable(fileProblem = failure.problem, exportId = destination?.exportId ?: failure.exportId)
    } catch (failure: GatewayFailure) {
        if (last?.receipt?.phase == LocalBranchReportFilePhase.PUBLISHED && last?.presence == LocalBranchReportFilePresence.VERIFIED_PRESENT)
            return outcome(checkNotNull(last), failure.problem)
        val selected = destination ?: return LocalBranchReportExportResult.Unavailable(serviceProblem = failure.problem)
        val retained = files.abandon(selected)
        if (retained is LocalBranchReportFileResult.Recorded) return outcome(preservePresence(retained, last), failure.problem)
        return LocalBranchReportExportResult.Unavailable(serviceProblem = failure.problem, exportId = selected.exportId)
    } catch (_: Exception) {
        if (last?.receipt?.phase == LocalBranchReportFilePhase.PUBLISHED && last?.presence == LocalBranchReportFilePresence.VERIFIED_PRESENT)
            return outcome(checkNotNull(last), LocalServiceProblem.UNAVAILABLE)
        val selected = destination ?: return LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.UNAVAILABLE)
        val retained = files.abandon(selected)
        if (retained is LocalBranchReportFileResult.Recorded) return outcome(preservePresence(retained, last), LocalServiceProblem.UNAVAILABLE)
        return LocalBranchReportExportResult.Unavailable(fileProblem = LocalBranchReportFileProblem.STORAGE_UNCERTAIN, exportId = selected.exportId)
    } finally {
        lifetime.end()
        // This updates only private lifecycle evidence; no credentials or publication are used
        // after the original owner ended. Any IO ambiguity keeps its original journal tail.
        destination?.let { files.abandon(it) }
    }
}

/** Bounded observations, never a transferable authorization or native publication capability. */
private data class ObservedReportExportPage(val page: LocalBranchReportResultV1.Page,
    val branchRevision: Revision, val observedAt: UtcInstant)
private data class ReportExportContent(val bytes: ByteArray, val branchRevision: Revision,
    val observedAt: UtcInstant, val nextAfter: DomainId?, val firstPage: LocalBranchReportResultV1.Page? = null)

private suspend fun readReportExportPage(access: GatewayAccess, query: LocalBranchReportQueryV1,
    lifetime: LocalBranchReportExportLifetime): ObservedReportExportPage {
    requireCurrent(access, lifetime)
    // Every page and confirming read takes the normal current PERFORM+EXPORT master boundary.
    val read = access.read(LocalBranchReportReadCodec.request(query))
    val page = LocalBranchReportReadCodec.decode(read.payload) as? LocalBranchReportResultV1.Page ?: unavailable()
    if (page.rows.size > query.limit) unavailable()
    val ids = page.rows.map { it.id.value }
    if (page.section != query.section || page.profile != query.profile || page.delivery != LocalBranchReportDeliveryV1.EXPORT ||
        page.after != query.after || page.selectedSaleId != query.selectedSaleId || ids != ids.distinct().sorted() ||
        query.after?.let { after -> ids.any { it <= after.value } } == true ||
        page.nextAfter != null && page.nextAfter != page.rows.lastOrNull()?.id ||
        query.section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS &&
            (page.after != null || page.nextAfter != null || page.rows.size != 1 || page.rows.single().id != query.selectedSaleId)) unavailable()
    validateBranchReportRows(query.section, page.rows, read.observedAt, query.profile)
    requireCurrent(access, lifetime)
    return ObservedReportExportPage(page, read.branchRevision, read.observedAt)
}

private suspend fun readCompleteReportSection(access: GatewayAccess, query: LocalBranchReportQueryV1,
    lifetime: LocalBranchReportExportLifetime): ReportExportContent {
    check(query.after == null)
    val csv = LocalBranchReportSectionCsvV2(query)
    try {
        val first = readReportExportPage(access, query, lifetime)
        var current = first
        while (true) {
            if (current.branchRevision != first.branchRevision) throw GatewayFailure(LocalServiceProblem.REPORT_RESTART_REQUIRED)
            csv.append(current.page, current.branchRevision, current.observedAt)
            val next = current.page.nextAfter ?: break
            if (csv.pageCount >= LocalBranchReportSectionCsvV2.MAX_PAGES) throw LocalBranchReportSectionCsvV2.CapacityExceeded()
            current = readReportExportPage(access, query.copy(after = next), lifetime)
        }
        // Per-page observation times remain in the CSV. This confirms one unchanged source
        // revision with current access, not a transaction spanning the paginated reads.
        val confirming = readReportExportPage(access, query, lifetime)
        if (confirming.branchRevision != first.branchRevision || confirming.page != first.page)
            throw GatewayFailure(LocalServiceProblem.REPORT_RESTART_REQUIRED)
        return ReportExportContent(csv.finish(confirming.branchRevision, confirming.observedAt),
            confirming.branchRevision, confirming.observedAt, null, first.page)
    } finally { csv.clear() }
}

internal suspend fun listLocalBranchReportExports(access: GatewayAccess, files: LocalBranchReportFileDelivery,
    lifetime: LocalBranchReportExportLifetime, after: DomainId?): LocalBranchReportExportHistory {
    requireCurrent(access, lifetime)
    val history = files.history(fileOwner(access), after)
    if (history is LocalBranchReportFileHistory.Unavailable) return LocalBranchReportExportHistory.Unavailable(fileProblem = history.problem)
    history as LocalBranchReportFileHistory.Available
    val audit: ExportAuditPort = CurrentMasterExportAudit(access)
    val records = history.receipts.map { receipt ->
        requireCurrent(access, lifetime)
        // Every disclosed native row has a fresh actual current-EXPORT metadata lookup. A
        // NotFound result says only that this audit is absent, not that a file was never written.
        val view = audit.lookup(receipt)
        requireCurrent(access, lifetime)
        LocalBranchReportExportRecord(receipt, masterStage = view?.stage?.let { LocalBranchReportAuditStage.valueOf(it.name) })
    }
    return LocalBranchReportExportHistory.Available(records, history.nextAfter)
}

internal suspend fun recoverLocalBranchReportExport(access: GatewayAccess, exportId: DomainId,
    files: LocalBranchReportFileDelivery, lifetime: LocalBranchReportExportLifetime): LocalBranchReportExportResult {
    var local: LocalBranchReportFileResult.Recorded? = null
    val originals = mutableListOf<LocalBranchReportRecoveredAudit>()
    try {
        val owner = fileOwner(access)
        val audit: ExportAuditPort = CurrentMasterExportAudit(access)
        requireCurrent(access, lifetime)
        var receipt = native(files.retained(exportId, owner)).receipt
        // Resolve only already-recorded opaque results before fresh EXPORT metadata access.
        // Current identity/recovery authority still comes from the Master on each lookup.
        // Absence cannot dispatch a new command; native inspection and new Finish stay below.
        for (slot in LocalBranchReportAuditSlot.entries) {
            val command = receipt.auditCommand(slot) ?: continue
            if (command.acknowledgement != null) continue
            requireCurrent(access, lifetime)
            val decision = audit.recoverRecorded(receipt, slot, command)
            val acknowledgement = when (decision) {
                is ExportAuditDecision.Recorded -> decision.acknowledgement
                is ExportAuditDecision.Rejected -> decision.acknowledgement
                is ExportAuditDecision.Unavailable -> continue
            }
            requireCurrent(access, lifetime)
            receipt = native(files.rememberAudit(exportId, owner, slot, command, acknowledgement)).receipt
            originals += LocalBranchReportRecoveredAudit(slot, command.operationId, acknowledgement)
        }
        var view = audit.lookup(receipt) // current EXPORT/own actor; NOT publication authority
        requireCurrent(access, lifetime)
        local = native(files.inspect(exportId, owner)) // inspect original artifacts; never create/link
        receipt = local.receipt
        requireCurrent(access, lifetime)
        if (receipt.begin == null) return outcome(local) // selection abandoned before any audit dispatch
        if (receipt.begin?.acknowledgement == null) {
            val begun = runAudit(access, audit, files, owner, receipt, LocalBranchReportAuditSlot.BEGIN, null, recover = true)
            receipt = begun.receipt
            local = local.copy(receipt = receipt)
            if (begun.decision !is ExportAuditDecision.Recorded) return outcome(local, decisionProblem(begun.decision))
            view = audit.lookup(receipt)
        }
        if (receipt.begin?.acknowledgement?.stage == LocalBranchReportAuditStage.REJECTED) return outcome(local, LocalServiceProblem.UNAVAILABLE)
        requireCurrent(access, lifetime)
        if (view == null) return outcome(local, LocalServiceProblem.UNAVAILABLE)
        if (view.stage == LocalBranchReportExportStageV1.REQUESTED) {
            val result = finishObservation(access, audit, files, owner, local, recover = true)
            if (result !is LocalBranchReportExportResult.Outcome || result.record.serviceProblem != null) return result
            receipt = result.record.receipt
            local = local.copy(receipt = receipt)
            view = audit.lookup(receipt)
        } else if (receipt.finish?.let { it.acknowledgement == null } == true) {
            val finished = runAudit(access, audit, files, owner, receipt, LocalBranchReportAuditSlot.FINISH, null, recover = true)
            receipt = finished.receipt
            local = local.copy(receipt = receipt)
            if (finished.decision !is ExportAuditDecision.Recorded) return outcome(local, decisionProblem(finished.decision))
            view = audit.lookup(receipt)
        }
        if (receipt.recovery?.let { it.acknowledgement == null } == true) {
            // The third operation may already have committed even though its reply/local
            // acknowledgement was lost. Resolve that ORIGINAL body even when the current
            // metadata already says PUBLISHED, without needing or creating another file.
            val recovered = runAudit(access, audit, files, owner, receipt, LocalBranchReportAuditSlot.RECOVER_PUBLISHED, null, recover = true)
            receipt = recovered.receipt
            local = local.copy(receipt = receipt)
            if (recovered.decision !is ExportAuditDecision.Recorded) return outcome(local, decisionProblem(recovered.decision))
            view = audit.lookup(receipt)
        }
        requireCurrent(access, lifetime)
        if (view?.stage == LocalBranchReportExportStageV1.PUBLICATION_OUTCOME_UNKNOWN &&
            local.receipt.phase == LocalBranchReportFilePhase.PUBLISHED && local.presence == LocalBranchReportFilePresence.VERIFIED_PRESENT) {
            // The native implementation proved the SAME already-published file. Android's
            // still-private/unadmitted artifact never passes this condition. No republish occurs.
            val correction = LocalBranchReportExportRequestV1.Finish(exportId, view.revision, receipt.receiptId,
                LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED, file(receipt))
            val recovered = runAudit(access, audit, files, owner, receipt, LocalBranchReportAuditSlot.RECOVER_PUBLISHED, correction, recover = true)
            local = local.copy(receipt = recovered.receipt)
            return outcome(local, decisionProblem(recovered.decision))
        }
        return outcome(local)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ExportFileFailure) {
        if (local?.receipt?.phase == LocalBranchReportFilePhase.PUBLISHED && local?.presence == LocalBranchReportFilePresence.VERIFIED_PRESENT)
            return outcome(checkNotNull(local), LocalServiceProblem.STORAGE_UNCERTAIN)
        return LocalBranchReportExportResult.Unavailable(fileProblem = failure.problem, exportId = exportId)
    }
    catch (failure: GatewayFailure) { return local?.let { outcome(it, failure.problem) }
        ?: if (originals.isNotEmpty() && failure.problem != LocalServiceProblem.SESSION_ENDED &&
            recoveredAuditsCurrent(access, files, lifetime, exportId, originals))
            LocalBranchReportExportResult.AuditRecovered(exportId, originals.toList(), failure.problem)
        else LocalBranchReportExportResult.Unavailable(serviceProblem = failure.problem, exportId = exportId) }
    catch (_: Exception) { return local?.let { outcome(it, LocalServiceProblem.UNAVAILABLE) }
        ?: LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.UNAVAILABLE, exportId = exportId) }
}

/** A denied EXPORT read cannot distinguish withdrawn action grants from a disabled identity. */
private suspend fun recoveredAuditsCurrent(access: GatewayAccess, files: LocalBranchReportFileDelivery,
    lifetime: LocalBranchReportExportLifetime, exportId: DomainId, originals: List<LocalBranchReportRecoveredAudit>): Boolean {
    try {
        requireCurrent(access, lifetime)
        val receipt = native(files.retained(exportId, fileOwner(access))).receipt
        val audit: ExportAuditPort = CurrentMasterExportAudit(access)
        for (original in originals) {
            requireCurrent(access, lifetime)
            val command = receipt.auditCommand(original.slot) ?: return false
            if (command.operationId != original.operationId) return false
            val acknowledgement = when (val decision = audit.recoverRecorded(receipt, original.slot, command)) {
                is ExportAuditDecision.Recorded -> decision.acknowledgement
                is ExportAuditDecision.Rejected -> decision.acknowledgement
                is ExportAuditDecision.Unavailable -> return false
            }
            if (acknowledgement != original.acknowledgement) return false
        }
        requireCurrent(access, lifetime)
        return true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return false }
}

private suspend fun finishObservation(access: GatewayAccess, audit: ExportAuditPort, files: LocalBranchReportFileDelivery,
    owner: LocalBranchReportFileOwner, local: LocalBranchReportFileResult.Recorded, recover: Boolean,
    problem: LocalServiceProblem? = null): LocalBranchReportExportResult {
    val receipt = local.receipt
    val reportedOutcome = when {
        receipt.phase == LocalBranchReportFilePhase.PUBLISHED && local.presence == LocalBranchReportFilePresence.VERIFIED_PRESENT ->
            LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED
        receipt.phase == LocalBranchReportFilePhase.CANCELLED -> LocalBranchReportExportOutcomeV1.CANCELLED_BEFORE_PUBLICATION
        else -> LocalBranchReportExportOutcomeV1.PUBLICATION_OUTCOME_UNKNOWN
    }
    val finish = LocalBranchReportExportRequestV1.Finish(receipt.exportId, Revision(0), receipt.receiptId, reportedOutcome, file(receipt))
    val result = runAudit(access, audit, files, owner, receipt, LocalBranchReportAuditSlot.FINISH, finish, recover)
    return outcome(local.copy(receipt = result.receipt), decisionProblem(result.decision) ?: problem)
}

private data class AuditedReceipt(val receipt: LocalBranchReportFileReceipt, val decision: ExportAuditDecision)
private suspend fun runAudit(access: GatewayAccess, audit: ExportAuditPort, files: LocalBranchReportFileDelivery,
    owner: LocalBranchReportFileOwner, initial: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
    definition: LocalBranchReportExportRequestV1?, recover: Boolean): AuditedReceipt {
    var receipt = initial
    var command = receipt.auditCommand(slot)
    val existing = command != null
    if (command == null) {
        val request = LocalBranchReportExportCodecV1.command(operationId(receipt, slot), checkNotNull(definition))
        val bytes = request.payload.copyBytes()
        command = try { LocalBranchReportAuditCommand(request.operationId, bytes.hex(),
            semanticDigest(access.view.scope, access.view.actorId, request.command, request.payload).value) } finally { bytes.fill(0) }
        receipt = native(files.prepareAudit(receipt.exportId, owner, slot, command)).receipt
    }
    val original = checkNotNull(command)
    // Once staged, an UNKNOWN body's outcome/metadata never changes even if later inspection
    // finds a completed file. A distinct third original operation performs that one correction.
    val result = if (recover && existing) audit.recover(receipt, slot, original)
        else if (slot == LocalBranchReportAuditSlot.BEGIN) audit.begin(receipt, original) else audit.finalize(receipt, slot, original)
    val acknowledgement = when (result) {
        is ExportAuditDecision.Recorded -> result.acknowledgement
        is ExportAuditDecision.Rejected -> result.acknowledgement
        is ExportAuditDecision.Unavailable -> null
    }
    if (acknowledgement != null) receipt = native(files.rememberAudit(receipt.exportId, owner, slot, original, acknowledgement)).receipt
    return AuditedReceipt(receipt, result)
}

private fun request(access: GatewayAccess, receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot,
    original: LocalBranchReportAuditCommand): MutationRequest {
    if (!receipt.owner.sameActor(fileOwner(access)) || original.operationId != operationId(receipt, slot)) unavailable()
    val bytes = original.canonicalBodyHex.unhex()
    val payload = try { StoredBytes(bytes) } finally { bytes.fill(0) }
    val decoded = LocalBranchReportExportCodecV1.decodeRequest(payload)
    if (LocalBranchReportExportCodecV1.encode(decoded) != payload || decoded.exportId != receipt.exportId ||
        semanticDigest(access.view.scope, access.view.actorId, LocalBranchReportExportCodecV1.COMMAND, payload).value != original.semanticSha256) unavailable()
    when (decoded) {
        is LocalBranchReportExportRequestV1.Begin -> if (slot != LocalBranchReportAuditSlot.BEGIN || decoded != begin(receipt)) unavailable()
        is LocalBranchReportExportRequestV1.Finish -> {
            if (slot == LocalBranchReportAuditSlot.BEGIN || decoded.nativeReceiptId != receipt.receiptId ||
                decoded.expectedRevision.value != (if (slot == LocalBranchReportAuditSlot.FINISH) 0L else 1L) ||
                decoded.file != file(receipt) || slot == LocalBranchReportAuditSlot.RECOVER_PUBLISHED &&
                decoded.outcome != LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED) unavailable()
        }
    }
    return MutationRequest(original.operationId, LocalBranchReportExportCodecV1.COMMAND, payload)
}

private fun validateView(receipt: LocalBranchReportFileReceipt, view: LocalBranchReportExportViewV1) {
    if (view.exportId != receipt.exportId || view.ownerId != receipt.owner.actorId || view.query != reportQuery(receipt) ||
        view.coverage != reportCoverage(receipt) ||
        view.nativeReceiptId != receipt.receiptId || view.destination.name != receipt.destinationKind.name ||
        view.originalOperationId != receipt.beginOperationId || view.file != null && view.file != file(receipt)) unavailable()
    val valid = when (view.stage) {
        LocalBranchReportExportStageV1.REQUESTED -> view.revision.value == 0L && view.lastOperationId == receipt.beginOperationId && view.file == null
        LocalBranchReportExportStageV1.CANCELLED_BEFORE_PUBLICATION, LocalBranchReportExportStageV1.PUBLICATION_OUTCOME_UNKNOWN ->
            view.revision.value == 1L && view.lastOperationId == receipt.finishOperationId
        LocalBranchReportExportStageV1.CLIENT_ATTESTED_PUBLISHED -> view.file != null &&
            (view.revision.value == 1L && view.lastOperationId == receipt.finishOperationId ||
                view.revision.value == 2L && view.lastOperationId == receipt.recoveryOperationId)
    }
    if (!valid) unavailable()
}

private fun fileOwner(access: GatewayAccess): LocalBranchReportFileOwner = LocalBranchReportFileOwner(access.view.scope.organizationId,
    access.view.scope.companyId, access.view.scope.branchId, access.view.actorId, access.view.deviceId, access.view.sessionId,
    access.view.generation.value, access.lease.credentials.connectionId)
private fun reportQuery(receipt: LocalBranchReportFileReceipt): LocalBranchReportQueryV1 = LocalBranchReportQueryV1(
    LocalBranchReportSectionV1.valueOf(receipt.query.section), receipt.query.after,
    if (receipt.query.section == LocalBranchReportSectionV1.RETAIL_TENDER_BREAKDOWNS.name) 1 else 5,
    LocalBranchReportDeliveryV1.EXPORT, receipt.query.profile?.let { LocalBranchReportProjectionV1.valueOf(it.name) },
    selectedSaleId = receipt.query.selectedSaleId)
private fun begin(receipt: LocalBranchReportFileReceipt): LocalBranchReportExportRequestV1.Begin = LocalBranchReportExportRequestV1.Begin(
    receipt.exportId, reportQuery(receipt), receipt.receiptId, LocalBranchReportExportDestinationV1.valueOf(receipt.destinationKind.name),
    reportCoverage(receipt))
private fun reportCoverage(receipt: LocalBranchReportFileReceipt): LocalBranchReportExportCoverageV1? =
    receipt.query.coverage?.let { LocalBranchReportExportCoverageV1.valueOf(it.name) }
private fun file(receipt: LocalBranchReportFileReceipt): LocalBranchReportExportFileV1? = receipt.source?.let {
    LocalBranchReportExportFileV1(it.branchRevision, it.observedAt, PayloadDigest(it.contentSha256), it.byteCount)
}
private fun operationId(receipt: LocalBranchReportFileReceipt, slot: LocalBranchReportAuditSlot) = when (slot) {
    LocalBranchReportAuditSlot.BEGIN -> receipt.beginOperationId
    LocalBranchReportAuditSlot.FINISH -> receipt.finishOperationId
    LocalBranchReportAuditSlot.RECOVER_PUBLISHED -> receipt.recoveryOperationId
}
private suspend fun requireCurrent(access: GatewayAccess, lifetime: LocalBranchReportExportLifetime) {
    currentCoroutineContext().ensureActive()
    access.checkCurrent()
    if (!lifetime.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
}
private fun decisionProblem(decision: ExportAuditDecision): LocalServiceProblem? = when (decision) {
    is ExportAuditDecision.Recorded -> null
    is ExportAuditDecision.Rejected -> LocalServiceProblem.UNAVAILABLE
    is ExportAuditDecision.Unavailable -> decision.problem
}
private fun outcome(local: LocalBranchReportFileResult.Recorded, problem: LocalServiceProblem? = null): LocalBranchReportExportResult.Outcome {
    val receipt = local.receipt
    val stage = listOfNotNull(receipt.recovery, receipt.finish, receipt.begin).mapNotNull { it.acknowledgement?.stage }
        .firstOrNull { it != LocalBranchReportAuditStage.REJECTED }
        ?: receipt.begin?.acknowledgement?.stage
    return LocalBranchReportExportResult.Outcome(LocalBranchReportExportRecord(receipt, local.presence, stage, problem))
}
private fun preservePresence(local: LocalBranchReportFileResult.Recorded, known: LocalBranchReportFileResult.Recorded?): LocalBranchReportFileResult.Recorded =
    if (known?.receipt?.exportId == local.receipt.exportId && known.receipt.source == local.receipt.source &&
        known.receipt.phase == LocalBranchReportFilePhase.PUBLISHED && local.receipt.phase == LocalBranchReportFilePhase.PUBLISHED)
        local.copy(presence = known.presence) else local
private class ExportFileFailure(val problem: LocalBranchReportFileProblem, val exportId: DomainId?) : Exception()
private fun native(result: LocalBranchReportFileResult): LocalBranchReportFileResult.Recorded = when (result) {
    is LocalBranchReportFileResult.Recorded -> result
    is LocalBranchReportFileResult.Unavailable -> throw ExportFileFailure(result.problem, result.exportId)
}
private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
private fun String.unhex(): ByteArray {
    require(length in 2..8192 && length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
