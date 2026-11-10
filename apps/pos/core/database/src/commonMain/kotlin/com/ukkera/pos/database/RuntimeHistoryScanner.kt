package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Actual original raw-reader consumer. Traverses the entire fixed schema, every physical
 * row and protected plaintext, every event/mutation/chunk, every per-key lineage/current
 * row, all retained commands/fragments and fixed protected relationship passes. It accepts
 * no SQL/key/path/current-view/decryptor callback and performs no write or repair. The
 * optional trusted composition port installs actual retained business producer checks;
 * returning true cannot replace core's original requirement/effects/native source closure.
 *
 * Positive selection covers complete original C1 and an explicitly proved original-account,
 * original-device throttle/session suffix. Event-order semantic replay and every native image
 * join immutable original dependencies, session maintenance and unextended work deadlines.
 * A bounded captured ordinary business suffix also requires actual native-before images,
 * complete original producer/resource traversals and every mutation/source crosswalk.
 * A distinct captured native-original suffix additionally needs the genuine joined employee
 * Start and whole retained certificate/source/writer closure; it is never employee replay.
 * An additive native-selected original employee prepass seeds a separate bounded reducer;
 * its full initial enrollment/event-order/lineage/current/relationship closure is mandatory.
 * The actual controller's closed chronological ACCOUNT-image protocol supplies the opened
 * C1 credential and bounded reset AFTER at their event cuts; census hashes are closure only.
 * Reset still requires full target reduction, both images and the original native final.
 * Distinct first original-cohost revocation joins the complete sorted account census,
 * N+2 actual mutations/plaintexts and genuine admission origin; revoked startup stays held.
 * Already-supported employee command/read/physical facts use its exact registered original
 * access proof; native ORIGINAL facts keep a separate whole earlier-Start authority join.
 * Unknown/unsupported producers/other-device/changed-policy/recovery histories stay held.
 * No prefix, reconstructed read facade or mini receipt query grants admission.
 * Counts are independent orphan crosschecks AFTER row-by-row validation, never substitutes.
 * A separate fenced-pending-original entry derives C1 child DATA from the original native
 * pending head and authenticated SQL receipt, then performs the SAME complete traversal.
 * Its native/issuer holds remain; only the actual closed-scan coordinator may classify it.
 * The ordinary receipt reader is not extended into a settlement or safe-absence producer.
 * No deployment/build/capacity/device qualification is inferred from a source selection.
 */
internal class RuntimeHistoryScanner private constructor(private val session: SelfOwnedNativeHistoryReadSession,
    private val pendingOriginalRecovery: Boolean = false,
    private val producer: RuntimeHistoryBusinessProducer? = null,
    private val stockCodec: StockCanonicalProjectionV2? = null,
    coldReplay: RuntimeHistoryColdReplay? = null,
    private val earlierTarget: RuntimeHistoryColdReplayTarget? = null,
    private val coldScanMemory: RuntimeHistoryColdScanMemory? = null,
    private val receiptMembershipBuilder: RuntimeRetailCustomerReceiptMembershipBuilder? = null,
    private val receiptMembershipMemory: RuntimeHistoryColdMemory? = null) {
    private val original = session.original
    private val native = original.record
    private val allocation = native.allocation
    private val coordinates = allocation.coordinates
    private val scope = coordinates.scope
    private val scopeKey = scope.storageKey()
    private val definition = requireNotNull(native.definition)
    private val attempt = requireNotNull(native.attempt)
    private var observedFirstChild = native.firstChild
    private val firstChild: LocalInstallationCompletedChildFactsV2 get() = requireNotNull(observedFirstChild)
    // A pending C1's genesis is already committed by its original native pending head, not
    // inferred from an arbitrary SQL row. The receipt/complete first-child data are still
    // verified later, across the entire original event/lineage/seed traversal.
    private val originalGenesis = native.firstChild?.installationGenesisDigest ?: run {
        historyShapeV2(pendingOriginalRecovery && native.parent == null && native.receipt == null &&
            attempt.phase == NativeHistoryAttemptPhaseV2.RETIRING && native.denialGeneration > attempt.denialGeneration &&
            native.pending?.child?.historySequence == 1L && native.retiredAttempts.all { it.issuerRetirementDigest != null })
        requireNotNull(native.pending).child.installationGenesisDigest
    }
    private val dao = RuntimeHistoryDao(session)
    private var coldTranscript: RuntimeHistoryColdTranscript? = null
    private var cold = coldReplay
    private var earlierOriginal: RuntimeHistoricalCommand? = null
    private val receiptEventClassifier = producer?.retailCustomerReceiptEventClassifier
    private var immutableReceiptEvents = 0L
    private var receiptMembershipFinished = false
    private val holds = linkedSetOf<RuntimeHistoryReadHold>()
    private val census = LongArray(RuntimeHistoryTableV2.entries.size)
    private val liveTips = LongArray(census.size)
    private val expectedRoots = buildList {
        for (table in RuntimeHistoryTableV2.entries) add(NativeHistoryReadPassV2.CENSUS to table)
        if (session.hasEmployeeSeedTraversal) add(NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS to RuntimeHistoryTableV2.EVENT)
        add(NativeHistoryReadPassV2.EVENTS to RuntimeHistoryTableV2.EVENT)
        add(NativeHistoryReadPassV2.LINEAGE to RuntimeHistoryTableV2.ROW_VERSION)
        add(NativeHistoryReadPassV2.COMMANDS to RuntimeHistoryTableV2.JOURNAL)
        add(NativeHistoryReadPassV2.STOCK_AGGREGATES to RuntimeHistoryTableV2.AGGREGATE)
        for (table in STOCK_TABLES) add(NativeHistoryReadPassV2.STOCK_REVERSE to table)
        add(NativeHistoryReadPassV2.ACCOUNT_RELATIONS to RuntimeHistoryTableV2.ACCOUNT)
        add(NativeHistoryReadPassV2.ACCESS_RELATIONS to RuntimeHistoryTableV2.ACCESS)
        add(NativeHistoryReadPassV2.ARCHIVE_RELATIONS to RuntimeHistoryTableV2.ARCHIVE)
        add(NativeHistoryReadPassV2.APPROVAL_RELATIONS to RuntimeHistoryTableV2.APPROVAL)
    }
    private var rootAt = 0
    private var censusRowId: Long? = null
    private var traversedBytes = 0L
    private var censusBytes = 0L
    private var censusReceiptEvents = 0L
    private var expectedPlaintext: ExpectedPlaintext? = null
    private var currentBranch: BranchRow? = null
    private var head: RuntimeHistoryHeadRowV2? = null
    private var originalReceiptBytes: ByteArray? = null
    private var originalReceipt: LocalBranchInstallationReceiptV2? = null
    private val originalSeedDigests = linkedMapOf<String, PayloadDigest>()
    private var c1Branch: BranchRow? = null
    private var event: EventScan? = null
    private var previousEvent: RuntimeHistoryEventHeaderV2? = null
    private var previousEventDigest: PayloadDigest? = null
    private var firstEventDigest: PayloadDigest? = null
    private var eventCount = 0L
    private var matchedFinalDeadlineDecisions = 0L
    private var matchedProtectedFinalDecisions = 0L
    // Namespace derives only from the authenticated event purpose and typed original
    // intent below. An independently issued approval ID may equal an operation UUID.
    private enum class ProtectedFinalRequestLane {
        ADMINISTRATION, APPROVAL_ISSUANCE, APPROVAL_RETIREMENT, APPROVED_OPERATION, SERIAL_PUBLICATION_REQUEST
    }
    private val protectedFinalRequestKeys = mutableSetOf<Pair<ProtectedFinalRequestLane, DomainId>>()
    private var eventMutationCount = 0L
    private var eventChunkCount = 0L
    private var originalThrottleEvents = 0L
    private var originalThrottleMutations = 0L
    private var originalThrottleImages = 0L
    private var originalAuthenticationEvents = 0L
    private var originalAuthenticationMutations = 0L
    private var originalAuthenticationImages = 0L
    private var originalSessionRevocationEvents = 0L
    private var originalSessionRevocationMutations = 0L
    private var originalSessionRevocationImages = 0L
    private var originalAdministrationCancellationEvents = 0L
    private var originalAdministrationCancellationMutations = 0L
    private var originalAdministrationCancellationImages = 0L
    private var deviceRevocationEvents = 0L
    private var originalDeviceRevocationMutations = 0L
    private var originalDeviceRevocationImages = 0L
    private var employeeDeviceRevocationImages = 0L
    // ADMISSION has no native authentication-capsule traversal. Retain this one fully
    // committed original-cohost event and exact versions, then independently join ALL
    // actual native LINEAGE images (including the genuine admission BEFORE/AFTER).
    // No native table/statement/tag registry or old serializer is widened.
    private class DeviceRevocationEvent(val header: RuntimeHistoryEventHeaderV2, val digest: PayloadDigest,
        val evidence: RuntimeDeviceRevocationEvidenceV1, versions: List<RuntimeHistoryRowVersionRowV2>) {
        val versions = versions.map { it.copy(key = it.key.copyOf()) }
        fun clear() { versions.forEach { it.key.fill(0) } }
    }
    private var deviceRevocationEvent: DeviceRevocationEvent? = null
    private val employees = earlierTarget?.evidence?.let { evidence ->
        val target = checkNotNull(earlierTarget)
        RuntimeEmployeeEnrollmentHistoryJoin.forEarlierOrdinaryReplay(session, target.header,
            target.eventDigest, evidence)
    } ?: RuntimeEmployeeEnrollmentHistoryJoin(session)
    private val deviceAdmissions = RuntimeDeviceAdmissionHistoryJoin(session)
    private val policies = RuntimeEmployeePolicyActivationHistoryJoin(session)
    private val approvals = RuntimeActionApprovalHistoryJoin(session)
    private val serialPublications = RuntimeSerialPublicationHistoryJoin(session)
    private var originalPolicyActivationEvents = 0L
    private var originalPolicyActivationMutations = 0L
    private var employeeThrottleImages = 0L
    private var employeeAuthenticationImages = 0L
    private var employeeSessionRevocationImages = 0L
    private var employeeAdministrationCancellationImages = 0L
    private var originalBusinessEvents = 0L
    private var originalBusinessMutations = 0L
    private var employeeBusinessEvents = 0L
    private var employeeBusinessMutations = 0L
    private var originalApprovedBusinessEvents = 0L
    private var originalApprovedBusinessMutations = 0L
    private var employeeApprovedBusinessEvents = 0L
    private var employeeApprovedBusinessMutations = 0L
    private var employeeNativeOriginalEvents = 0L
    private var employeeNativeOriginalMutations = 0L
    private var originalSupervisorReviewEvents = 0L
    private var originalSupervisorReviewMutations = 0L
    private var employeeSupervisorReviewEvents = 0L
    private var employeeSupervisorReviewMutations = 0L
    private var originalPurchasingPolicyRejectionEvents = 0L
    private var originalPurchasingPolicyRejectionMutations = 0L
    private var employeePurchasingPolicyRejectionEvents = 0L
    private var employeePurchasingPolicyRejectionMutations = 0L
    private var originalOrderReassignmentRejectionEvents = 0L
    private var originalOrderReassignmentRejectionMutations = 0L
    private var employeeOrderReassignmentRejectionEvents = 0L
    private var employeeOrderReassignmentRejectionMutations = 0L
    private var originalOpeningSupplierObligationRejectionEvents = 0L
    private var originalOpeningSupplierObligationRejectionMutations = 0L
    private var employeeOpeningSupplierObligationRejectionEvents = 0L
    private var employeeOpeningSupplierObligationRejectionMutations = 0L
    private var originalInvoiceCaptureRejectionEvents = 0L
    private var originalInvoiceCaptureRejectionMutations = 0L
    private var employeeInvoiceCaptureRejectionEvents = 0L
    private var employeeInvoiceCaptureRejectionMutations = 0L
    private var originalCapturedInvoiceMatchRejectionEvents = 0L
    private var originalCapturedInvoiceMatchRejectionMutations = 0L
    private var employeeCapturedInvoiceMatchRejectionEvents = 0L
    private var employeeCapturedInvoiceMatchRejectionMutations = 0L
    private var originalBranchActivityActivationRejectionEvents = 0L
    private var originalBranchActivityActivationRejectionMutations = 0L
    private var employeeBranchActivityActivationRejectionEvents = 0L
    private var employeeBranchActivityActivationRejectionMutations = 0L
    private var originalFloorConfigurationRejectionEvents = 0L
    private var originalFloorConfigurationRejectionMutations = 0L
    private var employeeFloorConfigurationRejectionEvents = 0L
    private var employeeFloorConfigurationRejectionMutations = 0L
    private var originalConfigurationRejectionEvents = 0L
    private var originalConfigurationRejectionMutations = 0L
    private var employeeConfigurationRejectionEvents = 0L
    private var employeeConfigurationRejectionMutations = 0L
    private var originalReturnRightsActivationRejectionEvents = 0L
    private var originalReturnRightsActivationRejectionMutations = 0L
    private var employeeReturnRightsActivationRejectionEvents = 0L
    private var employeeReturnRightsActivationRejectionMutations = 0L
    private var originalRetailCustomerReceiptRejectionEvents = 0L
    private var originalRetailCustomerReceiptRejectionMutations = 0L
    private var employeeRetailCustomerReceiptRejectionEvents = 0L
    private var employeeRetailCustomerReceiptRejectionMutations = 0L
    private var originalFoodCheckRejectionEvents = 0L
    private var originalFoodCheckRejectionMutations = 0L
    private var employeeFoodCheckRejectionEvents = 0L
    private var employeeFoodCheckRejectionMutations = 0L
    private var originalFoodPaymentRejectionEvents = 0L
    private var originalFoodPaymentRejectionMutations = 0L
    private var employeeFoodPaymentRejectionEvents = 0L
    private var employeeFoodPaymentRejectionMutations = 0L
    private var originalReturnCaseQualificationRejectionEvents = 0L
    private var originalReturnStockDispositionRejectionEvents = 0L
    private var originalReturnCaseQualificationRejectionMutations = 0L
    private var originalReturnStockDispositionRejectionMutations = 0L
    private var employeeReturnCaseQualificationRejectionEvents = 0L
    private var employeeReturnStockDispositionRejectionEvents = 0L
    private var employeeReturnCaseQualificationRejectionMutations = 0L
    private var employeeReturnStockDispositionRejectionMutations = 0L
    private var business = if (!pendingOriginalRecovery && producer != null && stockCodec != null)
        RuntimeBusinessHistoryJoin(session, producer, stockCodec, coldReplay?.index, coldReplay,
            receiptMembershipBuilder, receiptMembershipMemory.takeIf { coldReplay == null }) else null
    private var authenticationReplay: RuntimeOriginalAuthenticationReplay? = null
    // ROLE_TRANSITION events transfer their full event reservation to coldScanMemory before
    // decoding; these immutable evidence copies share that cumulative retained allowance.
    private val masterLifecycleEvidence = linkedMapOf<Long, RuntimeMasterLifecycleEvidenceV1>()
    private val masterLifecycleImages = linkedSetOf<Long>()
    private var censusOriginalAccountDigest: PayloadDigest? = null
    // Older controller protocol only. New native chronological seeds never borrow this credential.
    private var censusOriginalAccount: ProtectedLocalAccount? = null
    private var censusOriginalPolicy: ActivatedLocalPolicy? = null
    private var censusOriginalAdmission: LocalDeviceAdmission? = null
    private var censusOriginalConfiguration: LocalAuthConfiguration? = null
    private var censusOriginalAccountThrottle: PersistentThrottleState? = null
    private var censusOriginalDeviceThrottle: PersistentThrottleState? = null
    private var lineageCount = 0L
    private var tipCount = 0L
    private var lineage: LineageScan? = null
    private var command: CommandScan? = null
    private var commandCount = 0L
    private var commandAuditCount = 0L
    private var commandOutboxCount = 0L
    private var commandFragmentCount = 0L
    private var previousCommandEpoch: Long? = null
    private var previousCommandSequence = 0L
    private var previousCommandHash: String? = null
    private var stockAggregate: AggregateIdentity? = null
    private var stockProjectionCount = 0
    private var stockProjectionEnded = false
    private var stockAggregateCount = 0L
    private var stockIndex: IndexIdentity? = null
    private var stockReverseCount = 0L
    private var stockReverseAggregateCount = 0
    private var accountRelations: AccountRelations? = null
    private var accessRelations: AccessRelations? = null
    private var archiveRelations: ArchiveRelations? = null
    private var approvalRelations: ApprovalRelations? = null
    private var accountRelationCount = 0L
    private var policyRelationCount = 0L
    private var configurationRelationCount = 0L
    private var throttleRelationCount = 0L
    private var accessRelationCount = 0L
    private var refreshRelationCount = 0L
    private var archiveRelationCount = 0L
    private var approvalRelationCount = 0L
    private var censusAccountSessionCount = 0L
    private var matchedCurrentSessionCount = 0L
    private var matchedAccessArchiveCount = 0L

    private suspend fun scan(): RuntimeHistoryReadSelection {
        historyShapeV2(earlierTarget == null && cold == null)
        historyShapeV2(native.database != null && attempt.approvals != null)
        if (!pendingOriginalRecovery) historyShapeV2(firstChild.originalAllocationDigest == allocation.digest &&
            firstChild.definitionDigest == definition.definitionDigest)
        val schema = RuntimeHistorySchemaCensusV2()
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.schemaPage() ?: break
            historyShapeV2(page.items.isNotEmpty() && page.items.size <= RuntimeHistoryProfileV2.MAX_METADATA_PAGE)
            page.items.forEach(schema::consume)
        }
        schema.finish(original)
        // The native controller alone advertises this DATA protocol. Capture completes
        // before replay; neither a partial recording nor an allocation failure can fall
        // back to a different source. Older controllers keep their exact guarded path.
        // Preserve the already-supported guarded small-history route. Only a history
        // exceeding its cumulative command bound selects the new complete cold route;
        // unsupported cold producers still fail closed, never retry through a prefix.
        val transcript = coldScanMemory?.let {
            RuntimeHistoryColdTranscript.capture(session, it.memory).also { captured -> coldTranscript = captured }
        }
        if (transcript != null) {
            val index = RuntimeHistoryColdIndex.build(transcript)
            val replay = RuntimeHistoryColdReplay(transcript, index, checkNotNull(producer), checkNotNull(stockCodec))
            business?.clear()
            cold = replay
            business = RuntimeBusinessHistoryJoin(session, checkNotNull(producer), checkNotNull(stockCodec), index, replay,
                receiptMembershipBuilder)
        }
        val replay = transcript?.openCursor()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val item = if (replay == null) dao.next() else replay.next()
                if (item == null) break
                try { consume(item) } finally { item.clear() }
            }
        } finally { replay?.close() }
        historyShapeV2(rootAt == expectedRoots.size && expectedPlaintext == null && event == null && lineage == null && command == null)
        finishCoverage()
        val receiptBytes = requireNotNull(originalReceiptBytes)
        val receipt = requireNotNull(OriginalInstallationReceiptSelection.selectCandidate(
            definition, attempt.facts, requireNotNull(attempt.approvals), firstChild,
            ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(scope, coordinates.installationId), Revision(0)), receiptBytes))
        requireOriginalSeeds(receipt)
        requireOriginalReceiptClosure(receipt)
        // Every historical EVENT lineage has been inspected, including keys whose
        // later versions would otherwise disappear from the current census. This
        // independent check precedes the empty-AGGREGATE shortcut.
        historyShapeV2(immutableReceiptEvents == censusReceiptEvents)
        val receiptMembership = if (holds.isEmpty() && receiptEventClassifier != null && receiptMembershipBuilder != null) {
            receiptMembershipBuilder.requireClassifier(receiptEventClassifier)
            if (count(RuntimeHistoryTableV2.AGGREGATE) == 0L) {
                // Empty is established by this COMPLETE native census and all original
                // closure checks above, not by a missing classifier or missing commands.
                historyShapeV2(censusReceiptEvents == 0L && receiptMembershipBuilder.count == 0L)
            } else checkNotNull(business).requireRetailCustomerReceiptMembershipClosed(
                receiptEventClassifier, receiptMembershipBuilder)
            historyShapeV2(receiptMembershipBuilder.count == immutableReceiptEvents)
            receiptMembershipBuilder.finish().also { receiptMembershipFinished = true }
        } else null
        currentCoroutineContext().ensureActive()
        return RuntimeHistoryReadSelection(receipt, firstChild, holds.toSet(), original.recordDigest, receiptMembership)
    }

    private suspend fun consume(item: RuntimeHistoryReadItem) {
        historyShapeV2(rootAt < expectedRoots.size)
        val metadata = item.metadata
        requirePass(metadata.pass)
        coldScanMemory?.beforeInput(item)
        if (item is RuntimeHistoryReadItem.Plaintext) {
            val expected = requireNotNull(expectedPlaintext)
            historyShapeV2(metadata.pass == expected.pass && metadata.table == expected.table && metadata.rowId == expected.rowId &&
                metadata.protectedBinding == expected.binding)
            expectedPlaintext = null
            charge(item.bytes.size.toLong())
            val value = decodeProtected(expected, item.bytes)
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && expected.table == RuntimeHistoryTableV2.AUTH_EVENT &&
                requireNotNull(lineage).eventHeader?.purpose == RuntimeWritePurposeV2.ROLE_TRANSITION) {
                val event = value as? LocalAuthEvent ?: malformed()
                val audit = event.lifecycle
                // LINEAGE_EVENT_CHUNKS has already validated and cleared its raw capsule.
                // Keep only that decoded lifecycle intent through the following image join.
                val intent = requireNotNull(lineage).lifecycleIntent ?: malformed()
                val evidence = masterLifecycleEvidence[requireNotNull(lineage).row.sequence]
                    ?: malformed()
                historyShapeV2(event.kind == LocalAuthEventKind.MASTER_LIFECYCLE &&
                    evidence.matchesEvent(event) &&
                    event.targetId == coordinates.installationId && event.operatorId != null &&
                    event.sessionId != null && event.accountRevision != null && event.contentDigest != null &&
                    audit != null && audit.installationId == event.targetId &&
                    audit.deviceId != null && audit.sessionGeneration != null &&
                    audit.admissionRevision != null && audit.issuerContextId != null &&
                    audit.coordinatesDigest == lifecycleCoordinatesDigest(coordinates) &&
                    audit.requestDigest == lifecycleRequestDigest(coordinates,
                        LocalMasterLifecycleRequest(event.eventId, audit.action)) &&
                    audit.requestDigest == event.contentDigest &&
                    intent.purposeTag == RuntimeWritePurposeV2.ROLE_TRANSITION.tag &&
                    intent.originalRequestId == event.eventId && intent.originalActorId == event.operatorId &&
                    intent.originalRequestDigest == event.contentDigest && intent.originalOperationId == null &&
                    intent.resources.isEmpty())
                historyShapeV2(masterLifecycleImages.add(requireNotNull(lineage).row.sequence))
                requireNotNull(lineage).lifecycleIntent = null
            }
            if (expected.pass == NativeHistoryReadPassV2.CENSUS) {
                captureOriginalAuthenticationCensus(value, item.bytes)
                employees.census(expected.binding, item.bytes, value)
                deviceAdmissions.census(expected.binding, item.bytes, value)
            }
            if (expected.pass == NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE) {
                employees.seedPlaintext(expected.binding, item.bytes, value)
                // This exact original seed was already bound/charged/decoded and joined.
                // It is not a current relation row. Do not fall through to that rejecting
                // generic route or broaden its allowlist to skip the actual seed checks.
                return
            }
            if (expected.pass == NativeHistoryReadPassV2.EVENT_MUTATIONS) {
                historyShapeV2(session.hasChronologicalAccountImageTraversal && value is ProtectedLocalAccount)
                requireNotNull(event).accountPlaintext(expected.binding, item.bytes, value as ProtectedLocalAccount)
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE)
                employees.lineageSourcePlaintext(requireNotNull(lineage).row, expected.binding, item.bytes)
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE)
                policies.lineageSourcePlaintext(requireNotNull(lineage).row, expected.binding, item.bytes, value)
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE)
                deviceAdmissions.lineageSourcePlaintext(requireNotNull(lineage).row, expected.binding, item.bytes, value)
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE) {
                val active = requireNotNull(lineage)
                approvals.lineageImage(requireNotNull(active.eventHeader), requireNotNull(active.eventDigest),
                    active.row, expected.binding, item.bytes)
                serialPublications.lineageImage(requireNotNull(active.eventHeader), requireNotNull(active.eventDigest),
                    active.row, expected.binding, item.bytes)
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_CURRENT) {
                approvals.current(expected.binding, item.bytes)
                serialPublications.current(expected.binding, item.bytes)
                deviceAdmissions.current(expected.binding, item.bytes, value)
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && value is ActivatedLocalPolicy) {
                val active = requireNotNull(lineage)
                historyShapeV2(active.row.tableTag == RuntimeHistoryTableV2.POLICY.tag && active.afterPolicyDigest == null)
                active.afterPolicyDigest = sha256(item.bytes)
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && value is LocalDeviceAdmission) {
                val active = requireNotNull(lineage)
                historyShapeV2(active.row.tableTag == RuntimeHistoryTableV2.ADMISSION.tag && active.afterAdmissionDigest == null)
                active.afterAdmissionDigest = sha256(item.bytes)
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && value is ProtectedLocalAccount) {
                val active = requireNotNull(lineage)
                historyShapeV2(active.row.tableTag == RuntimeHistoryTableV2.ACCOUNT.tag && active.afterAccountDigest == null)
                active.afterAccountDigest = sha256(item.bytes)
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE) {
                requireNotNull(lineage).authenticationImage?.let { image ->
                    historyShapeV2(image.matches(expected.binding) && image.plaintextDigest == sha256(item.bytes))
                    if (requireNotNull(lineage).employeeAuthenticationImage) employeeAuthenticationImages = addCount(employeeAuthenticationImages)
                    else originalAuthenticationImages = addCount(originalAuthenticationImages)
                }
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE) {
                requireNotNull(lineage).sessionRevocationImage?.let { image ->
                    historyShapeV2(image.matches(expected.binding) && image.plaintextDigest == sha256(item.bytes))
                    if (requireNotNull(lineage).employeeSessionRevocationImage) employeeSessionRevocationImages = addCount(employeeSessionRevocationImages)
                    else originalSessionRevocationImages = addCount(originalSessionRevocationImages)
                }
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE) {
                requireNotNull(lineage).administrationCancellationImage?.let { image ->
                    historyShapeV2(image.matches(expected.binding) && image.plaintextDigest == sha256(item.bytes))
                    if (requireNotNull(lineage).employeeAdministrationCancellationImage)
                        employeeAdministrationCancellationImages = addCount(employeeAdministrationCancellationImages)
                    else originalAdministrationCancellationImages = addCount(originalAdministrationCancellationImages)
                }
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE) {
                requireNotNull(lineage).deviceRevocationImage?.let { image ->
                    historyShapeV2(image.matches(expected.binding) && image.plaintextDigest == sha256(item.bytes))
                    if (requireNotNull(lineage).employeeDeviceRevocationImage)
                        employeeDeviceRevocationImages = addCount(employeeDeviceRevocationImages)
                    else originalDeviceRevocationImages = addCount(originalDeviceRevocationImages)
                }
            }
            if (expected.pass == NativeHistoryReadPassV2.CENSUS && value is LocalBranchInstallationReceiptV2) {
                historyShapeV2(originalReceiptBytes == null)
                originalReceiptBytes = item.bytes.copyOf(); originalReceipt = value
            }
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && requireNotNull(lineage).row.sequence == 1L)
                recordOriginalProtectedSeed(expected, item.bytes, value)
            if (expected.pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && value is PersistentThrottleState)
                consumeThrottleImage(value)
            if (expected.pass == NativeHistoryReadPassV2.CENSUS && value is ProtectedLocalAccount)
                censusAccountSessionCount = historyAddV2(censusAccountSessionCount, value.state.sessions.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
            consumeProtectedRelation(expected.pass, value)
            return
        }
        historyShapeV2(expectedPlaintext == null) // Each announced protected row MUST be opened next.
        when (item) {
            is RuntimeHistoryReadItem.Row -> {
                charge(item.encodedBytes)
                validateRow(item.row)
                if (item.row.table.protectedTable != null) expectedPlaintext = expectedPlaintext(item.row, metadata.pass)
                when (metadata.pass) {
                    NativeHistoryReadPassV2.CENSUS -> consumeCensus(item)
                    NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS -> {
                        val row = item.row.event()
                        try {
                            val header = RuntimeHistoryCodecV2.decodeHeader(row.header)
                            requireHeaderOriginal(header)
                            coldScanMemory?.retainSeedCapsule(header)
                            employees.startSeed(row, header)
                        } catch (failure: Throwable) { row.header.fill(0); throw failure }
                    }
                    NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_MUTATIONS -> {
                        val version = item.row.version()
                        try { employees.seedMutation(version) } finally { version.key.fill(0) }
                    }
                    NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE -> employees.seedImage(item.row)
                    NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_CHUNKS -> {
                        val chunk = item.row.chunk()
                        try { employees.seedChunk(chunk) } finally { chunk.bytes.fill(0) }
                    }
                    NativeHistoryReadPassV2.EVENTS -> startEvent(item.row.event())
                    NativeHistoryReadPassV2.EVENT_MUTATIONS -> {
                        if (metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                            historyShapeV2(item.row.table == RuntimeHistoryTableV2.ROW_VERSION)
                            val version = item.row.version()
                            try { requireNotNull(event).mutation(version); eventMutationCount = addCount(eventMutationCount) }
                            finally { version.key.fill(0) }
                        } else {
                            historyShapeV2(session.hasChronologicalAccountImageTraversal && metadata.kind == NativeHistoryRowKindV2.IMAGE &&
                                item.row.table == RuntimeHistoryTableV2.ACCOUNT)
                            requireNotNull(event).accountImage(item.row, item.encodedBytes, requireNotNull(expectedPlaintext).binding)
                        }
                    }
                    NativeHistoryReadPassV2.EVENT_CHUNKS -> {
                        val chunk = item.row.chunk()
                        try { requireNotNull(event).chunk(chunk); eventChunkCount = addCount(eventChunkCount) }
                        finally { chunk.bytes.fill(0) }
                    }
                    NativeHistoryReadPassV2.LINEAGE -> startLineage(item.row.version())
                    NativeHistoryReadPassV2.LINEAGE_EVENT -> consumeLineageEvent(item.row.event())
                    NativeHistoryReadPassV2.LINEAGE_EVENT_CHUNKS -> {
                        val active = requireNotNull(lineage)
                        historyShapeV2(active.eventEnded && !active.capsuleEnded &&
                            RuntimeHistoryTableV2.fromTag(active.row.tableTag).hasAuthenticationImages)
                        val chunk = item.row.chunk()
                        try { requireNotNull(active.capsule).append(chunk) } finally { chunk.bytes.fill(0) }
                    }
                    NativeHistoryReadPassV2.LINEAGE_IMAGE -> consumeLineageImage(item.row)
                    NativeHistoryReadPassV2.LINEAGE_TIP -> consumeLineageTip(item.row.tip())
                    NativeHistoryReadPassV2.LINEAGE_CURRENT -> consumeLineageCurrent(item.row)
                    NativeHistoryReadPassV2.COMMANDS -> startCommand(item.row.journal())
                    NativeHistoryReadPassV2.COMMAND_OPERATION -> requireNotNull(command).operation(item.row.operation())
                    NativeHistoryReadPassV2.COMMAND_AUDIT -> {
                        requireNotNull(command).audit(item.row.audit()); commandAuditCount = addCount(commandAuditCount)
                    }
                    NativeHistoryReadPassV2.COMMAND_OUTBOX -> {
                        val row = item.row.outbox()
                        try { requireNotNull(command).outbox(row); commandOutboxCount = addCount(commandOutboxCount) }
                        finally { row.body.fill(0) }
                    }
                    NativeHistoryReadPassV2.COMMAND_FRAGMENT -> {
                        requireNotNull(command).fragment(item.row); commandFragmentCount = addCount(commandFragmentCount)
                    }
                    NativeHistoryReadPassV2.STOCK_AGGREGATES, NativeHistoryReadPassV2.STOCK_PROJECTION,
                    NativeHistoryReadPassV2.STOCK_REVERSE, NativeHistoryReadPassV2.STOCK_REVERSE_AGGREGATE -> consumeStock(item.row, metadata.pass)
                    NativeHistoryReadPassV2.APPROVAL_OPERATIONS -> consumeApprovalOperation(item.row.operation())
                    else -> historyShapeV2(item.row.table.protectedTable != null)
                }
            }
            is RuntimeHistoryReadItem.Boundary -> boundary(metadata)
            is RuntimeHistoryReadItem.Plaintext -> error("unreachable")
        }
    }

    private fun captureOriginalAuthenticationCensus(value: Any, bytes: ByteArray) {
        when (value) {
            is ProtectedLocalAccount -> if (value.state.actorId == coordinates.firstActorId) {
                historyShapeV2(censusOriginalAccountDigest == null); censusOriginalAccountDigest = sha256(bytes)
                if (!session.hasChronologicalAccountImageTraversal) {
                    historyShapeV2(censusOriginalAccount == null); censusOriginalAccount = value
                }
            }
            is ActivatedLocalPolicy -> if (value.actorId == coordinates.firstActorId) {
                historyShapeV2(censusOriginalPolicy == null); censusOriginalPolicy = value
            }
            is LocalDeviceAdmission -> if (value.deviceId == coordinates.deviceId) {
                historyShapeV2(censusOriginalAdmission == null); censusOriginalAdmission = value
                if (!value.admitted) holds += RuntimeHistoryReadHold.ORIGINAL_COHOST_DEVICE_REVOKED
            }
            is LocalAuthConfiguration -> if (value.actorId == coordinates.firstActorId) {
                historyShapeV2(censusOriginalConfiguration == null); censusOriginalConfiguration = value
            }
            is PersistentThrottleState -> if (value.key.actorId == coordinates.firstActorId) when (value.key.dimension) {
                ThrottleDimension.Account -> {
                    historyShapeV2(censusOriginalAccountThrottle == null); censusOriginalAccountThrottle = value
                }
                ThrottleDimension.Device(coordinates.deviceId) -> {
                    historyShapeV2(censusOriginalDeviceThrottle == null); censusOriginalDeviceThrottle = value
                }
                else -> Unit
            }
        }
    }
    private fun holdUnjoinedAuthorization() {
        authenticationReplay?.clear(); authenticationReplay = null
        business?.abandon(); employees.abandon(); policies.abandon(); deviceAdmissions.abandon()
        holds += RuntimeHistoryReadHold.NON_INSTALLATION_CAPTURED_AUTHORIZATION_FACTS_UNJOINED
    }

    /** Consume one whole original at its genuine chronological authorization cut.
     * The independent COMMANDS traversal still runs later; no joined marker replaces it. */
    private suspend fun joinColdCommand(header: RuntimeHistoryEventHeaderV2,
        evidence: RuntimeCommandAuthorizationEvidenceV1?, nativeOriginal: RuntimeNativeOriginalImportEvidenceV1?) {
        val replay = checkNotNull(cold)
        val join = checkNotNull(business)
        val paymentRejection = evidence?.let { value ->
            RuntimeHistoricalFoodPaymentRejectionProfile.fromEvidence(value)
                ?.matches(header, value)
        } == true
        val target = earlierTarget
        val priorPaymentRejection = target != null && paymentRejection && nativeOriginal == null &&
            header.sequence < target.header.sequence && header.commandSequence < target.header.commandSequence
        historyShapeV2(join.enabled && (header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY ||
            paymentRejection && nativeOriginal == null) &&
            (evidence == null) != (nativeOriginal == null) &&
            (target == null || (header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && target.header == header) ||
                priorPaymentRejection))
        val operationId = evidence?.operationId ?: checkNotNull(nativeOriginal).producer.operationId
        join.prepareColdCommand(operationId)
        val capture = join.coldCapture(operationId)
        historyShapeV2(capture.header == header && capture.employee === evidence &&
            (capture.foodPaymentRejection != null) == paymentRejection &&
            (nativeOriginal == null || capture.nativeOriginal?.let { copied ->
                copied.scope == nativeOriginal.scope && copied.producer == nativeOriginal.producer &&
                    copied.canonicalRequirement == nativeOriginal.canonicalRequirement && copied.certificate == nativeOriginal.certificate &&
                    copied.provenance == nativeOriginal.provenance && copied.nativeRecordDigest == nativeOriginal.nativeRecordDigest &&
                    copied.capturedChanges == nativeOriginal.capturedChanges
            } == true))
        val joined = replay.index.withIndependentCommand(header.sequence) { journal, operation ->
            if (target == null) join.join(journal, operation)
            else if (priorPaymentRejection) {
                // Fully join every closed prior refusal, but never lend it as the
                // successful target or mark the main chronological index again.
                historyShapeV2(earlierOriginal == null)
                val refusal = join.joinForColdLoan(journal, operation) ?: return@withIndependentCommand false
                withContext(NonCancellable) {
                    join.closeColdLoan(refusal)
                    capture.employeeFoodPaymentRejection?.let { proof ->
                        employees.retireJoinedFoodPaymentRejection(proof, capture.header, capture.eventDigest, checkNotNull(evidence))
                    }
                    join.retireColdCommand(operationId)
                }
                true
            } else {
                historyShapeV2(earlierOriginal == null)
                join.joinForColdLoan(journal, operation)?.also { earlierOriginal = it } != null
            }
        }
        if (!joined) { holdUnjoinedAuthorization(); return }
        if (target == null) {
            capture.employeeHistory?.let { proof ->
                employees.retireJoinedCommand(proof, capture.header, capture.eventDigest, checkNotNull(evidence))
            }
            capture.employeeFoodPaymentRejection?.let { proof ->
                employees.retireJoinedFoodPaymentRejection(proof, capture.header, capture.eventDigest, checkNotNull(evidence))
            }
            join.retireColdCommand(operationId)
        }
    }

    /** Reuse the complete original census/seed/event interpreter up to exactly the
     * requested ordinary or closed native-original event. A native target uses the
     * actual independently replayed Start; its DATA never issues an employee proof.
     * This is a computation scope, never full scan/native closure. */
    private suspend fun replayEarlierOrdinary(): RuntimeHistoryColdOriginalLoan {
        val replay = checkNotNull(cold)
        val target = checkNotNull(earlierTarget)
        var rows: RuntimeHistoryColdTranscript.Cursor? = null
        var failure: Throwable? = null
        var transferred = false
        try {
            val cursor = replay.transcript.openCursor(afterLast = target.afterLast).also { rows = it }
            while (true) {
                val item = cursor.next() ?: break
                consume(item)
            }
            historyShapeV2(holds.isEmpty() && expectedPlaintext == null && event == null &&
                previousEvent == target.header && previousEventDigest == target.eventDigest &&
                eventCount == target.header.sequence && expectedRoots[rootAt].first == NativeHistoryReadPassV2.EVENTS)
            val command = checkNotNull(earlierOriginal)
            val join = checkNotNull(business)
            join.requireActive(command)
            historyShapeV2(command.capture.header == target.header && command.capture.eventDigest == target.eventDigest &&
                command.capture.employee === target.evidence && command.capture.joined)
            historyShapeV2((command.capture.nativeOriginal == null) == (target.nativeOriginal == null))
            cursor.close(); rows = null
            return object : RuntimeHistoryColdOriginalLoan {
                override val session: SelfOwnedNativeHistoryReadSession get() = this@RuntimeHistoryScanner.session
                override val capture: RuntimeBusinessHistoryJoin.CommandCapture get() = command.capture
                override val original: RetainedOriginalV2 get() = command.original
                private var closed = false

                override suspend fun close(): Unit = withContext(NonCancellable) {
                    if (closed) return@withContext
                    closed = true
                    var closeFailure: Throwable? = null
                    try {
                        join.closeColdLoan(command)
                        capture.employeeHistory?.let { proof ->
                            employees.retireJoinedCommand(proof, capture.header, capture.eventDigest, checkNotNull(target.evidence))
                        }
                        if (target.nativeOriginal == null) employees.requireEarlierOrdinaryCommandSettled()
                        else employees.requireEarlierNativeOriginalPrefixSettled(target.header, target.eventDigest, target.nativeOriginal)
                        join.retireColdCommand(capture.producer.operationId)
                    } catch (caught: Throwable) { closeFailure = caught; throw caught }
                    finally {
                        try { clear() } catch (cleanup: Throwable) {
                            if (closeFailure == null) throw cleanup
                            if (closeFailure !== cleanup) closeFailure.addSuppressed(cleanup)
                        } finally { target.close() }
                    }
                }
            }.also { transferred = true }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            withContext(NonCancellable) {
                try { rows?.close() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else if (failure !== cleanup) checkNotNull(failure).addSuppressed(cleanup)
                }
                if (!transferred) try { clear() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else if (failure !== cleanup) checkNotNull(failure).addSuppressed(cleanup)
                }
            }
            if (!transferred && failure != null) throw checkNotNull(failure)
        }
    }

    /** Project only actors with actual ACCOUNT mutations in a successfully joined event.
     * All within-event account updates have finished; no later command cut is guessed. */
    private fun captureRecipientAccounts(header: RuntimeHistoryEventHeaderV2, actors: Set<DomainId>) {
        val join = business?.takeIf { it.enabled } ?: return
        for (actor in actors) {
            if (actor == coordinates.firstActorId)
                join.captureRecipientAccountAfter(header, authenticationReplay ?: malformed())
            else employees.captureRecipientAccountAfter(join, header, actor)
        }
    }

    private fun charge(bytes: Long) { traversedBytes = historyAddV2(traversedBytes, bytes, RuntimeHistoryProfileV2.MAX_DATASET_BYTES) }
    private fun addCount(value: Long): Long = historyAddV2(value, 1, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    private fun count(table: RuntimeHistoryTableV2): Long = census[table.ordinal]
    private fun requirePass(pass: NativeHistoryReadPassV2) {
        val root = expectedRoots[rootAt].first
        val allowed = when (root) {
            NativeHistoryReadPassV2.CENSUS -> setOf(root)
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS -> setOf(root, NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_MUTATIONS,
                NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE, NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_CHUNKS)
            NativeHistoryReadPassV2.EVENTS -> setOf(root, NativeHistoryReadPassV2.EVENT_MUTATIONS, NativeHistoryReadPassV2.EVENT_CHUNKS)
            NativeHistoryReadPassV2.LINEAGE -> setOf(root, NativeHistoryReadPassV2.LINEAGE_EVENT, NativeHistoryReadPassV2.LINEAGE_IMAGE,
                NativeHistoryReadPassV2.LINEAGE_TIP, NativeHistoryReadPassV2.LINEAGE_CURRENT, NativeHistoryReadPassV2.LINEAGE_EVENT_CHUNKS)
            NativeHistoryReadPassV2.COMMANDS -> setOf(root, NativeHistoryReadPassV2.COMMAND_OPERATION, NativeHistoryReadPassV2.COMMAND_AUDIT,
                NativeHistoryReadPassV2.COMMAND_OUTBOX, NativeHistoryReadPassV2.COMMAND_FRAGMENT)
            NativeHistoryReadPassV2.STOCK_AGGREGATES -> setOf(root, NativeHistoryReadPassV2.STOCK_PROJECTION)
            NativeHistoryReadPassV2.STOCK_REVERSE -> setOf(root, NativeHistoryReadPassV2.STOCK_REVERSE_AGGREGATE)
            NativeHistoryReadPassV2.ACCOUNT_RELATIONS -> setOf(root, NativeHistoryReadPassV2.ACCOUNT_POLICY, NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION, NativeHistoryReadPassV2.ACCOUNT_THROTTLES)
            NativeHistoryReadPassV2.ACCESS_RELATIONS -> setOf(root, NativeHistoryReadPassV2.ACCESS_REFRESH, NativeHistoryReadPassV2.ACCESS_ARCHIVE,
                NativeHistoryReadPassV2.ACCESS_ACCOUNTS, NativeHistoryReadPassV2.ACCESS_ADMISSIONS, NativeHistoryReadPassV2.ACCESS_CONFIGURATIONS)
            NativeHistoryReadPassV2.ARCHIVE_RELATIONS -> setOf(root, NativeHistoryReadPassV2.ARCHIVE_ACCESS)
            NativeHistoryReadPassV2.APPROVAL_RELATIONS -> setOf(root, NativeHistoryReadPassV2.APPROVAL_OPERATIONS)
            else -> emptySet()
        }
        historyShapeV2(pass in allowed)
    }

    private fun consumeCensus(item: RuntimeHistoryReadItem.Row) {
        val row = item.row
        historyShapeV2(expectedRoots[rootAt].second == row.table)
        censusRowId?.let { historyShapeV2(row.rowId > it) }; censusRowId = row.rowId
        census[row.table.ordinal] = addCount(count(row.table))
        censusBytes = historyAddV2(censusBytes, item.encodedBytes, RuntimeHistoryProfileV2.MAX_DATASET_BYTES)
        when (row.table) {
            RuntimeHistoryTableV2.BRANCH -> { historyShapeV2(currentBranch == null); currentBranch = row.branch() }
            RuntimeHistoryTableV2.HEAD -> { historyShapeV2(head == null); head = row.head() }
            RuntimeHistoryTableV2.AGGREGATE -> if (row.text("kind") == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND)
                censusReceiptEvents = addCount(censusReceiptEvents)
            RuntimeHistoryTableV2.WRITE_FRAME, RuntimeHistoryTableV2.CAPTURE -> historyShapeV2(false)
            else -> Unit
        }
    }

    private fun startEvent(row: RuntimeHistoryEventRowV2) {
        try {
            historyShapeV2(event == null && row.sequence == eventCount + 1)
            val header = RuntimeHistoryCodecV2.decodeHeader(row.header)
            requireHeaderOriginal(header)
            if (row.sequence == 1L) historyShapeV2(header.parentKind == RuntimeHistoryParentKindV2.ORIGINAL_ALLOCATION.tag &&
                header.parentDigest == allocation.digest && header.parentSequence == 0L && header.purpose == RuntimeWritePurposeV2.ORIGINAL_INSTALLATION)
            else historyShapeV2(header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag && header.parentSequence == eventCount &&
                header.parentDigest == previousEventDigest && header.purpose != RuntimeWritePurposeV2.ORIGINAL_INSTALLATION)
            previousEvent?.let { previous ->
                historyShapeV2(header.installationGenesisDigest == previous.installationGenesisDigest && header.commandEpoch >= previous.commandEpoch)
                if (header.commandEpoch == previous.commandEpoch) historyShapeV2(header.commandSequence >= previous.commandSequence)
            }
            val workspace = coldScanMemory?.memory
            val reserved = if (workspace == null) 0L else RuntimeHistoryColdScanMemory.eventBytes(header)
            workspace?.reserve(reserved)
            try { event = EventScan(row, header, reserved) }
            catch (failure: Throwable) { workspace?.release(reserved); throw failure }
        } catch (failure: Throwable) { row.header.fill(0); throw failure }
    }
    private fun requireHeaderOriginal(header: RuntimeHistoryEventHeaderV2) {
        historyShapeV2(header.scope == scopeKey && header.storageId == coordinates.storageInstanceId &&
            header.bindingDigest == allocation.originalNativeBindingDigest && header.originalAllocationDigest == allocation.digest &&
            header.definitionDigest == definition.definitionDigest && header.installationAttemptId == attempt.facts.issuedAttemptId &&
            header.installationAttemptSequence == attempt.facts.attemptSequence && header.assignmentId == coordinates.assignmentId &&
            header.installationGenesisDigest == originalGenesis)
    }
    private inner class EventScan(val row: RuntimeHistoryEventRowV2, val header: RuntimeHistoryEventHeaderV2,
        private val workspaceBytes: Long) {
        private var cleared = false
        private var retainedByScanner = false
        private val stream = RuntimeHistoryEventStreamV2(header)
        private val capsule = RuntimeHistoryCapsule(header)
        private val authorizationMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val businessMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val employeeEnrollmentMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val employeePolicyActivationMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val deviceAdmissionMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val approvalIssuanceMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val approvalRetirementMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val serialPublicationMutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private var decodedColdNativeOriginal: RuntimeNativeOriginalImportEvidenceV1? = null
        // One announced native ACCOUNT image at a time. No next mutation or boundary may
        // pass it unopened. All images stream; only C1 or ordinal-zero bounded admin AFTER
        // survives as a private candidate until this complete event's verified replay cut.
        private var accountImageVersion: RuntimeHistoryRowVersionRowV2? = null
        private var accountImageBinding: ProtectedLocalBinding? = null
        private var accountCandidate: ProtectedLocalAccount? = null
        private var accountCandidateBinding: ProtectedLocalBinding? = null
        private var accountCandidateDigest: PayloadDigest? = null
        private var mutations = 0
        private var chunks = 0
        var mutationsEnded = false
        fun mutation(value: RuntimeHistoryRowVersionRowV2) {
            historyShapeV2(!mutationsEnded && accountImageVersion == null && accountImageBinding == null)
            stream.appendMutation(value); mutations++
            // Authentication plus the separately bounded ordinary candidate. Unknown/large
            // histories still stream fully and retain holds; they are never truncated.
            if ((header.changeCount in 1..2 && header.purpose in setOf(RuntimeWritePurposeV2.THROTTLE_RESERVE,
                    RuntimeWritePurposeV2.THROTTLE_COMPLETE)) ||
                (header.changeCount in 1..RuntimeAuthenticationEvidenceV1.MAX_IMAGES && header.purpose in setOf(
                    RuntimeWritePurposeV2.SESSION_ISSUE, RuntimeWritePurposeV2.SESSION_REFRESH, RuntimeWritePurposeV2.SESSION_LOGOUT)) ||
                (header.changeCount == RuntimeSessionRevocationHistoryEvidence.MAX_IMAGES &&
                    header.purpose == RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION) ||
                (header.changeCount == RuntimeAdministrationCancellationEvidenceV1.MAX_IMAGES &&
                    header.purpose in setOf(RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION, RuntimeWritePurposeV2.DEVICE_ADMINISTRATION)) ||
                (header.changeCount in RuntimeDeviceRevocationEvidenceV2.MIN_IMAGES..RuntimeDeviceRevocationEvidenceV2.MAX_IMAGES &&
                    header.purpose == RuntimeWritePurposeV2.DEVICE_ADMINISTRATION) ||
                (header.changeCount == 1 && header.purpose == RuntimeWritePurposeV2.ROLE_TRANSITION))
                authorizationMutations += value.copy(key = value.key.copyOf())
            if (business?.enabled == true && (header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
                header.changeCount in 1..RuntimeBusinessHistoryJoin.MAX_VERSIONS ||
                header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT &&
                    header.changeCount in CLOSED_REJECTION_CHANGE_COUNTS))
                businessMutations += value.copy(key = value.key.copyOf())
            if (header.purpose == RuntimeWritePurposeV2.PROVISIONING && header.changeCount in 6..261)
                employeeEnrollmentMutations += value.copy(key = value.key.copyOf())
            if (header.purpose == RuntimeWritePurposeV2.PROVISIONING && header.changeCount in 2..3)
                employeePolicyActivationMutations += value.copy(key = value.key.copyOf())
            if (deviceAdmissions.hasSource(header.sequence)) {
                historyShapeV2(header.purpose == RuntimeWritePurposeV2.PROVISIONING && header.changeCount in 3..4098)
                deviceAdmissionMutations += value.copy(key = value.key.copyOf())
            }
            if (header.purpose == RuntimeWritePurposeV2.APPROVAL_ISSUE && header.changeCount == 4)
                approvalIssuanceMutations += value.copy(key = value.key.copyOf())
            if (header.purpose == RuntimeWritePurposeV2.APPROVAL_RETIRE && header.changeCount == 1)
                approvalRetirementMutations += value.copy(key = value.key.copyOf())
            if (header.purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST && header.changeCount == 1)
                serialPublicationMutations += value.copy(key = value.key.copyOf())
            if (header.sequence == 1L) historyShapeV2(value.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && value.version == 1L)
            if (session.hasChronologicalAccountImageTraversal && value.tableTag == RuntimeHistoryTableV2.ACCOUNT.tag &&
                value.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag)
                accountImageVersion = value.copy(key = value.key.copyOf())
        }
        fun accountImage(value: RuntimeHistoryRowViewV2, encodedBytes: Long, binding: ProtectedLocalBinding) {
            val version = requireNotNull(accountImageVersion)
            historyShapeV2(session.hasChronologicalAccountImageTraversal && !mutationsEnded && accountImageBinding == null &&
                version.tableTag == RuntimeHistoryTableV2.ACCOUNT.tag && version.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag &&
                value.table == RuntimeHistoryTableV2.ACCOUNT && value.rowId == version.afterRowId &&
                value.digest().value == version.afterDigest && encodedBytes == requireNotNull(version.imageBytes).toLong())
            val key = value.key()
            try { historyShapeV2(key.contentEquals(version.key)) } finally { key.fill(0) }
            historyShapeV2(binding.key == ProtectedLocalRecordKey.Account(scope, DomainId(value.text("actorId"))) &&
                binding.revision == Revision(value.number("revision")))
            accountImageBinding = binding
        }
        fun accountPlaintext(binding: ProtectedLocalBinding, bytes: ByteArray, value: ProtectedLocalAccount) {
            val version = requireNotNull(accountImageVersion)
            historyShapeV2(session.hasChronologicalAccountImageTraversal && !mutationsEnded && accountImageBinding == binding)
            if (header.sequence == 1L) {
                val state = value.state
                historyShapeV2(accountCandidate == null && binding.key == ProtectedLocalRecordKey.Account(scope, coordinates.firstActorId) &&
                    binding.revision == Revision(0) && state.scope == scope && state.actorId == coordinates.firstActorId && state.enabled &&
                    state.revision == Revision(0) && state.generation == SessionGeneration(0) && state.sessions.isEmpty() &&
                    value.credentialRevision == Revision(0) && value.credential != null &&
                    version.version == 1L && version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag)
                accountCandidate = value; accountCandidateBinding = binding; accountCandidateDigest = sha256(bytes)
            } else if (header.purpose == RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION &&
                header.changeCount == RuntimeSessionRevocationHistoryEvidence.MAX_IMAGES && version.ordinal == 0) {
                historyShapeV2(accountCandidate == null)
                accountCandidate = value; accountCandidateBinding = binding; accountCandidateDigest = sha256(bytes)
            }
            // Explicit IMAGE length/cells and immediate protected metadata consumed above
            // close this object; ImageNode emits no GROUP_END. Keep no historical credential map.
            version.key.fill(0); accountImageVersion = null; accountImageBinding = null
        }
        fun chunk(value: RuntimeHistoryChunkRowV2) {
            historyShapeV2(mutationsEnded && accountImageVersion == null && accountImageBinding == null)
            stream.appendChunk(value)
            capsule.append(value)
            chunks++
        }
        fun endMutations() {
            historyShapeV2(!mutationsEnded && mutations == header.changeCount && accountImageVersion == null && accountImageBinding == null)
            mutationsEnded = true
        }
        suspend fun finish() {
            historyShapeV2(mutationsEnded && chunks == header.chunkCount && accountImageVersion == null && accountImageBinding == null)
            val commitment = stream.finish()
            historyShapeV2(commitment.digest.value == row.eventDigest && commitment.encodedBytes == row.encodedBytes)
            val decoded = capsule.finish()
            val coldOrdinary = if (coldScanMemory != null && header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
                RuntimeCommandAuthorizationEvidenceCodec.hasProfile(capsule.facts))
                RuntimeCommandAuthorizationEvidenceCodec.decode(capsule.facts) else null
            val coldNativeOriginal = if (coldScanMemory != null && header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
                RuntimeNativeOriginalImportEvidenceCodec.hasProfile(capsule.facts))
                RuntimeNativeOriginalImportEvidenceCodec.decode(capsule.facts).also { decodedColdNativeOriginal = it } else null
            val coldPaymentRejection = if (coldScanMemory != null && header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT &&
                RuntimeCommandAuthorizationEvidenceCodec.hasProfile(capsule.facts))
                RuntimeCommandAuthorizationEvidenceCodec.decode(capsule.facts).takeIf { value ->
                    RuntimeHistoricalFoodPaymentRejectionProfile.fromEvidence(value)
                        ?.matches(header, value) == true
                } else null
            val disposableBusinessEvent = coldOrdinary != null && coldOrdinary.actionApprovalId == null || coldPaymentRejection != null ||
                coldNativeOriginal?.let { RuntimeBusinessHistoryJoin.supportsColdNativeOriginal(it) } == true
            if (coldScanMemory != null && !disposableBusinessEvent) {
                // Security reducers and coordinator snapshots may outlive EventScan.
                // Transfer their already reserved allowance before any reducer runs.
                coldScanMemory.retainEventReservation(workspaceBytes)
                retainedByScanner = true
            }
            var foundingRecipientOrigin: RuntimeOriginalAuthenticationReplay? = null
            var recipientEnrollment = false
            var changedRecipientActors: Set<DomainId> = emptySet()
            var changedRecipientPolicies: Set<DomainId> = emptySet()
            val approvalIssuance = boundApprovalIssuance(header, capsule, decoded)
            val approvalRetirement = boundApprovalRetirement(header, capsule, decoded)
            val approvedCommand = boundApprovedCommand(header, capsule, decoded)
            val serialPublication = boundSerialPublicationRequest(header, capsule, decoded)
            val masterLifecycle = if (RuntimeMasterLifecycleEvidenceCodec.hasProfile(capsule.facts))
                RuntimeMasterLifecycleEvidenceCodec.decode(capsule.facts) else null
            historyShapeV2(masterLifecycle == null || header.purpose == RuntimeWritePurposeV2.ROLE_TRANSITION)
            native.finalDeadlineDecisions.singleOrNull { it.child.historySequence == header.sequence }?.let { decision ->
                val child = NativeHistoryRecordedHeadV2(header.sequence, commitment.digest, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment)
                historyShapeV2(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
                    decision.scope == scope && decision.originalAllocationDigest == allocation.digest && decision.child == child &&
                    decision.originalIntentDigest == sha256(capsule.intent) && decoded.originalActorId != null &&
                    decision.declaration.originalRequestId == decoded.originalRequestId &&
                    decision.declaration.originalOperationId == decoded.originalOperationId &&
                    decision.declaration.originalRequestDigest == decoded.originalRequestDigest &&
                    decision.finalWall < decision.declaration.mustCommitBefore)
                matchedFinalDeadlineDecisions = addCount(matchedFinalDeadlineDecisions)
            }
            val protectedDecision = native.protectedFinalDecisions.singleOrNull { it.child.historySequence == header.sequence }
            protectedDecision?.let { decision ->
                val child = NativeHistoryRecordedHeadV2(header.sequence, commitment.digest, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment)
                historyShapeV2(header.sequence > 1 && decision.scope == scope &&
                    decision.originalAllocationDigest == allocation.digest && decision.child == child &&
                    decision.originalIntentDigest == sha256(capsule.intent) &&
                    decision.declaration.actorId == decoded.originalActorId &&
                    decision.declaration.originalRequestId == decoded.originalRequestId &&
                    decision.declaration.originalRequestDigest == decoded.originalRequestDigest)
                val lane = when (header.purpose) {
                    RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION, RuntimeWritePurposeV2.DEVICE_ADMINISTRATION -> {
                        historyShapeV2(decoded.originalOperationId == null && decoded.resources.isEmpty() &&
                            approvalIssuance == null && approvalRetirement == null && approvedCommand == null)
                        ProtectedFinalRequestLane.ADMINISTRATION
                    }
                    RuntimeWritePurposeV2.APPROVAL_ISSUE -> {
                        historyShapeV2(approvalIssuance != null && approvalRetirement == null && approvedCommand == null &&
                            decision.declaration == approvalIssuance.protectedFinalDeclaration())
                        ProtectedFinalRequestLane.APPROVAL_ISSUANCE
                    }
                    RuntimeWritePurposeV2.APPROVAL_RETIRE -> {
                        historyShapeV2(approvalRetirement != null && approvalIssuance == null && approvedCommand == null &&
                            decision.declaration == approvalRetirement.protectedFinalDeclaration())
                        ProtectedFinalRequestLane.APPROVAL_RETIREMENT
                    }
                    RuntimeWritePurposeV2.COMMAND_APPLY, RuntimeWritePurposeV2.COMMAND_REJECT -> {
                        historyShapeV2(approvedCommand != null && approvalIssuance == null && approvalRetirement == null &&
                            decision.declaration == approvedCommand.approvalUse.protectedFinalDeclaration(approvedCommand.command()))
                        ProtectedFinalRequestLane.APPROVED_OPERATION
                    }
                    RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST -> {
                        historyShapeV2(serialPublication != null && approvalIssuance == null && approvalRetirement == null &&
                            approvedCommand == null && decision.declaration == serialPublication.protectedFinalDeclaration())
                        ProtectedFinalRequestLane.SERIAL_PUBLICATION_REQUEST
                    }
                    else -> malformed()
                }
                historyShapeV2(protectedFinalRequestKeys.add(lane to decoded.originalRequestId))
                matchedProtectedFinalDecisions = addCount(matchedProtectedFinalDecisions)
            }
            if (header.sequence == 1L) {
                val expectedDefinition = definition.copyWireBytes()
                try { historyShapeV2(expectedDefinition.contentEquals(capsule.dependencies)) } finally { expectedDefinition.fill(0) }
                val receipt = LocalBranchInstallationCodecV2.decodeReceipt(capsule.facts)
                historyShapeV2(header.changeCount == 8 && header.commandEpoch == definition.definition.authorityEpoch.value &&
                    header.commandSequence == 0L && header.commandDigest == receipt.genesis && header.installationGenesisDigest == receipt.genesis &&
                    decoded.originalRequestId == definition.definition.originalRequestId && decoded.originalRequestDigest == definition.definitionDigest &&
                    decoded.originalOperationId == null && decoded.originalActorId == null && decoded.resources.isEmpty() &&
                    receipt.digest == firstChild.receiptDigest && commitment.digest == firstChild.historyDigest)
                firstEventDigest = commitment.digest
                if (session.hasChronologicalAccountImageTraversal) {
                    val seed = requireNotNull(accountCandidate)
                    historyShapeV2(accountCandidateBinding == ProtectedLocalBinding(
                        ProtectedLocalRecordKey.Account(scope, coordinates.firstActorId), Revision(0)) &&
                        accountCandidateDigest == receipt.core.seeds.account)
                    if (!pendingOriginalRecovery) authenticationReplay = RuntimeOriginalAuthenticationReplay.fromNativeAdmissionSeed(original,
                        seed, censusOriginalAccountDigest, censusOriginalPolicy, censusOriginalAdmission, censusOriginalConfiguration,
                        censusOriginalAccountThrottle, censusOriginalDeviceThrottle)
                    foundingRecipientOrigin = authenticationReplay
                } else if (!pendingOriginalRecovery) {
                    // Exact older unchanged-credential lane only. It receives no reset AFTER
                    // and cannot gain reset admission by borrowing today's credential.
                    authenticationReplay = RuntimeOriginalAuthenticationReplay.fromCensus(original,
                        censusOriginalAccount, censusOriginalPolicy, censusOriginalAdmission, censusOriginalConfiguration,
                        censusOriginalAccountThrottle, censusOriginalDeviceThrottle)
                }
            } else {
                val throttle = boundThrottle(header, capsule, decoded)
                val authentication = boundAuthentication(header, capsule, decoded)
                val sessionRevocation = boundSessionRevocation(header, capsule, decoded)
                val administrationCancellation = boundAdministrationCancellation(header, capsule, decoded)
                val deviceRevocation = boundDeviceRevocation(header, capsule, decoded)
                val additionalDeviceRevocation = boundAdditionalDeviceRevocation(header, capsule, decoded)
                val employeeEnrollment = employees.enrollment(header, commitment.digest, capsule, decoded,
                    employeeEnrollmentMutations, requireNotNull(previousEvent), requireNotNull(previousEventDigest))
                val employeePolicyActivation = policies.event(header, commitment.digest, capsule, decoded,
                    employeePolicyActivationMutations, requireNotNull(previousEvent), requireNotNull(previousEventDigest))
                val deviceAdmission = deviceAdmissions.event(header, commitment.digest, capsule, decoded,
                    deviceAdmissionMutations, requireNotNull(previousEvent), requireNotNull(previousEventDigest))
                historyShapeV2(deviceAdmission == null || !employeeEnrollment && employeePolicyActivation == null)
                // Tag 12 final data requires its distinct strict cancellation OR original-
                // cohost device binder, never a generic device effect/old two-image profile.
                historyShapeV2(protectedDecision == null || approvalIssuance != null || approvalRetirement != null || approvedCommand != null ||
                    serialPublication != null || administrationCancellation != null || deviceRevocation != null || additionalDeviceRevocation != null ||
                    sessionRevocation != null && header.purpose == RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION)
                if (throttle != null || authentication != null || sessionRevocation != null || administrationCancellation != null ||
                    deviceRevocation != null || additionalDeviceRevocation != null || employeeEnrollment || employeePolicyActivation != null || deviceAdmission != null || approvalIssuance != null ||
                    approvalRetirement != null || serialPublication != null) {
                    val previous = requireNotNull(previousEvent)
                    historyShapeV2(header.commandEpoch == previous.commandEpoch && header.commandSequence == previous.commandSequence &&
                        header.commandDigest == previous.commandDigest && header.roleCommitment == previous.roleCommitment &&
                        header.recoveryCommitment == previous.recoveryCommitment)
                }
                if (serialPublication != null) {
                    val joined = protectedDecision != null && serialPublications.event(header, commitment.digest,
                        serialPublication, serialPublicationMutations, protectedDecision, authenticationReplay, employees)
                    if (!joined) holdUnjoinedAuthorization()
                } else if (approvalRetirement != null) {
                    val joined = protectedDecision != null && approvals.retirement(header, commitment.digest,
                        approvalRetirement, approvalRetirementMutations, protectedDecision, authenticationReplay, employees)
                    if (!joined) holdUnjoinedAuthorization()
                } else if (approvalIssuance != null) {
                    val joined = protectedDecision != null && approvals.issuance(header, commitment.digest,
                        approvalIssuance, approvalIssuanceMutations, protectedDecision, authenticationReplay, employees)
                    if (!joined) holdUnjoinedAuthorization()
                    else if (isOriginalThrottle(approvalIssuance.throttle))
                        originalThrottleMutations = historyAddV2(originalThrottleMutations,
                            approvalIssuance.throttle.changed().size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                } else if (employeeEnrollment) {
                    historyShapeV2(decoded.originalActorId == null && decoded.originalOperationId == null && decoded.resources.isEmpty())
                    recipientEnrollment = true
                } else if (deviceAdmission != null) {
                    if (!employees.deviceAdmission(deviceAdmission, authenticationReplay)) holdUnjoinedAuthorization()
                    else deviceAdmissions.reduced(deviceAdmission)
                } else if (employeePolicyActivation != null) {
                    val targetOwner = employees.policyActivation(employeePolicyActivation, authenticationReplay)
                    if (targetOwner == null) holdUnjoinedAuthorization()
                    else {
                        policies.reduced(employeePolicyActivation,targetOwner)
                        if (targetOwner == RuntimeEmployeePolicyActivationTargetOwner.ORIGINAL) {
                            originalPolicyActivationEvents = addCount(originalPolicyActivationEvents)
                            originalPolicyActivationMutations = historyAddV2(originalPolicyActivationMutations,
                                employeePolicyActivation.evidence.images.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        }
                        changedRecipientPolicies = setOf(employeePolicyActivation.requested.request.targetId)
                    }
                } else if (additionalDeviceRevocation != null) {
                    historyShapeV2(authorizationMutations.size == header.changeCount)
                    val previousEmployeeMutations = employees.deviceRevocationMutations
                    val joined = deviceAdmissions.withdrawalEvent(header, commitment.digest, capsule, decoded,
                        additionalDeviceRevocation, authorizationMutations, protectedDecision, employees, authenticationReplay)
                    if (!joined) holdUnjoinedAuthorization()
                    else {
                        val employeeMutations = employees.deviceRevocationMutations - previousEmployeeMutations
                        deviceRevocationEvents = addCount(deviceRevocationEvents)
                        // The admission UPDATE belongs to deviceAdmissions. Each account
                        // effect belongs to its actor; the audit belongs only to its operator.
                        originalDeviceRevocationMutations = historyAddV2(originalDeviceRevocationMutations,
                            additionalDeviceRevocation.images.size.toLong() - 1L - employeeMutations,
                            RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        changedRecipientActors = additionalDeviceRevocation.images.filter { it.table == RuntimeHistoryTableV2.ACCOUNT }
                            .map { it.recordId }.toSet()
                    }
                } else if (deviceRevocation != null) {
                    historyShapeV2(authorizationMutations.size == header.changeCount && deviceRevocationEvent == null)
                    for ((index, image) in deviceRevocation.images.withIndex())
                        requireDeviceRevocationMutation(authorizationMutations[index], deviceRevocation, image, index)
                    deviceRevocationEvent = DeviceRevocationEvent(header, commitment.digest, deviceRevocation, authorizationMutations)
                    protectedDecision?.let { historyShapeV2(it.declaration == deviceRevocation.protectedFinalDeclaration()) }
                    val previousEmployeeMutations = employees.deviceRevocationMutations
                    val joined = protectedDecision != null && employees.deviceRevocation(deviceRevocation, protectedDecision, authenticationReplay)
                    if (!joined) holdUnjoinedAuthorization()
                    else {
                        val employeeMutations = employees.deviceRevocationMutations - previousEmployeeMutations
                        deviceRevocationEvents = addCount(deviceRevocationEvents)
                        originalDeviceRevocationMutations = historyAddV2(originalDeviceRevocationMutations,
                            deviceRevocation.images.size.toLong() - employeeMutations, RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        changedRecipientActors = deviceRevocation.images.filter { it.table == RuntimeHistoryTableV2.ACCOUNT }
                            .map { it.recordId }.toSet()
                    }
                } else if (administrationCancellation != null) {
                    historyShapeV2(header.changeCount == RuntimeAdministrationCancellationEvidenceV1.MAX_IMAGES &&
                        authorizationMutations.size == header.changeCount)
                    requireAdministrationCancellationMutation(authorizationMutations.single(), administrationCancellation.images.single())
                    protectedDecision?.let { historyShapeV2(it.declaration == administrationCancellation.protectedFinalDeclaration()) }
                    // No replay count/state is advanced without the actual operator/native-final
                    // proof. A missing witness remains unjoined, never synthesized from the audit.
                    val joined = protectedDecision != null && employees.administrationCancellation(
                        administrationCancellation, protectedDecision, authenticationReplay)
                    if (!joined) holdUnjoinedAuthorization()
                    else if (administrationCancellation.authorization.actorId == coordinates.firstActorId) {
                        originalAdministrationCancellationEvents = addCount(originalAdministrationCancellationEvents)
                        originalAdministrationCancellationMutations = historyAddV2(originalAdministrationCancellationMutations,
                            administrationCancellation.images.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                    }
                } else if (sessionRevocation != null) {
                    historyShapeV2(header.changeCount == RuntimeSessionRevocationHistoryEvidence.MAX_IMAGES &&
                        authorizationMutations.size == header.changeCount)
                    for ((index, image) in sessionRevocation.images.withIndex()) {
                        requireSessionRevocationMutation(authorizationMutations[index], image, index)
                        if (sessionRevocation.retained != null || sessionRevocation.disable != null || sessionRevocation.reset != null)
                            requireAccountAdministrationPredecessor(authorizationMutations[index], sessionRevocation.command.expectedRevision, index)
                    }
                    protectedDecision?.let { historyShapeV2(it.declaration == sessionRevocation.protectedFinalDeclaration()) }
                    val replay = authenticationReplay
                    val selfRevocation = sessionRevocation.self
                    val retainedRevocation = sessionRevocation.retained
                    val accountDisable = sessionRevocation.disable
                    val credentialReset = sessionRevocation.reset
                    if (credentialReset != null && session.hasChronologicalAccountImageTraversal) {
                        historyShapeV2(accountCandidate != null && credentialReset.images[0].matches(requireNotNull(accountCandidateBinding)) &&
                            accountCandidateDigest == credentialReset.images[0].plaintextDigest)
                    }
                    // These distinct two-image profiles require a real native final witness;
                    // no target reducer is advanced when it is missing or interrupted.
                    val targetOwner = when {
                        retainedRevocation != null && protectedDecision != null ->
                            employees.retainedSessionRevocation(retainedRevocation, protectedDecision, replay)
                        accountDisable != null && protectedDecision != null ->
                            employees.accountDisable(accountDisable, protectedDecision, replay)
                        credentialReset != null && protectedDecision != null && session.hasChronologicalAccountImageTraversal ->
                            employees.credentialReset(credentialReset, protectedDecision, replay, requireNotNull(accountCandidate))
                        else -> null
                    }
                    val originalJoined = if (selfRevocation == null)
                        targetOwner == RuntimeRetainedSessionRevocationTargetOwner.ORIGINAL
                    else isOriginalSessionRevocation(selfRevocation) && replay != null &&
                        replay.selfSessionRevocation(selfRevocation, protectedDecision)
                    val employeeJoined = if (selfRevocation == null)
                        targetOwner == RuntimeRetainedSessionRevocationTargetOwner.ENROLLED_EMPLOYEE
                    else !originalJoined && employees.selfSessionRevocation(selfRevocation, protectedDecision)
                    if (!originalJoined && !employeeJoined) holdUnjoinedAuthorization()
                    else if (protectedDecision == null) {
                        // Old or interrupted originals lack the actual final decision. Fully
                        // traverse their data, but never backfill a sample or grant restart.
                        holds += RuntimeHistoryReadHold.NON_INSTALLATION_CAPTURED_AUTHORIZATION_FACTS_UNJOINED
                        business?.abandonRecipientHistory()
                    } else if (originalJoined) {
                        originalSessionRevocationEvents = addCount(originalSessionRevocationEvents)
                        originalSessionRevocationMutations = historyAddV2(originalSessionRevocationMutations,
                            sessionRevocation.images.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                    }
                    if (protectedDecision != null && (originalJoined || employeeJoined))
                        changedRecipientActors = sessionRevocation.images.filter { it.table == RuntimeHistoryTableV2.ACCOUNT }
                            .map { it.recordId }.toSet()
                } else if (authentication != null) {
                    historyShapeV2(authentication.images.size == header.changeCount && authorizationMutations.size == header.changeCount)
                    for ((index, expected) in authentication.images.withIndex()) {
                        val mutation = authorizationMutations[index]
                        val key = expected.key(scope)
                        try { historyShapeV2(mutation.ordinal == index && mutation.tableTag == expected.tableTag &&
                            mutation.key.contentEquals(key) && mutation.mutationKind == expected.mutation.tag &&
                            mutation.afterKind == (if (expected.table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag
                                else RuntimeHistoryAfterKindV2.IMAGE.tag) &&
                            (expected.mutation != RuntimeHistoryMutationKindV2.INSERT || mutation.version == 1L)) }
                        finally { key.fill(0) }
                    }
                    val replay = authenticationReplay
                    val joined = if (isOriginalAuthentication(authentication, header.sequence) && replay != null) {
                        replay.authentication(authentication)
                        originalAuthenticationEvents = addCount(originalAuthenticationEvents)
                        originalAuthenticationMutations = historyAddV2(originalAuthenticationMutations,
                            authentication.images.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        true
                    } else employees.authentication(authentication) ||
                        employees.additionalDeviceAuthentication(authentication, header.sequence)
                    if (!joined) holdUnjoinedAuthorization()
                    else authentication.throttle?.takeIf { it.deviceId != coordinates.deviceId }?.let {
                        // Later enrollment owns its already-proved device seed and closure.
                        // Earlier employees still advance the admission-owned bucket tip.
                        if (!employees.ownsEnrollmentDeviceThrottle(it.actorId, it.deviceId,
                                it.dependencies.admission, header.sequence))
                            deviceAdmissions.authenticationThrottle(it, header.sequence)
                    }
                    if (business?.enabled == true)
                        changedRecipientActors = authentication.images.filter { it.table == RuntimeHistoryTableV2.ACCOUNT }
                            .map { it.recordId }.toSet()
                } else if (throttle != null) {
                    val changed = throttle.changed()
                    historyShapeV2(changed.size in 1..2 && authorizationMutations.size == changed.size && header.changeCount == changed.size)
                    for ((index, state) in changed.withIndex()) {
                        val mutation = authorizationMutations[index]
                        val key = runtimeThrottleKey(state.key)
                        try { historyShapeV2(mutation.tableTag == RuntimeHistoryTableV2.THROTTLE.tag && mutation.ordinal == index &&
                            mutation.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag && mutation.key.contentEquals(key) &&
                            mutation.version == state.revision.value + 1 && mutation.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag) }
                        finally { key.fill(0) }
                    }
                    val replay = authenticationReplay
                    val joined = if (isOriginalAuthenticationThrottle(throttle, header.sequence) && replay != null) {
                        replay.throttle(throttle)
                        originalThrottleEvents = addCount(originalThrottleEvents)
                        originalThrottleMutations = historyAddV2(originalThrottleMutations, changed.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        true
                    } else employees.throttle(throttle) || employees.additionalDeviceThrottle(throttle, header.sequence)
                    if (!joined) holdUnjoinedAuthorization()
                    else if (throttle.deviceId != coordinates.deviceId &&
                        !employees.ownsEnrollmentDeviceThrottle(throttle.actorId, throttle.deviceId,
                            throttle.dependencies.admission, header.sequence))
                        deviceAdmissions.authenticationThrottle(throttle, header.sequence)
                } else if (approvedCommand != null) {
                    // Neither the wrapper nor its approval locator can enter the old direct
                    // command registries. Both actors and the earlier issued original join
                    // at this exact event cut before the complete business producer.
                    val proof = protectedDecision?.let { approvals.command(header, commitment.digest,
                        approvedCommand, businessMutations, it, authenticationReplay, employees) }
                    val captured = proof != null && business?.takeIf { it.enabled }?.captureApproved(
                        proof, decoded, capsule.dependencies, businessMutations) == true
                    if (!captured) holdUnjoinedAuthorization()
                    else {
                        val previous = requireNotNull(previousEvent)
                        historyShapeV2(header.commandEpoch == previous.commandEpoch && header.commandSequence == previous.commandSequence + 1 &&
                            header.roleCommitment == previous.roleCommitment && header.recoveryCommitment == previous.recoveryCommitment)
                        if (checkNotNull(proof).requesterIsOriginal) {
                            originalApprovedBusinessEvents = addCount(originalApprovedBusinessEvents)
                            originalApprovedBusinessMutations = historyAddV2(originalApprovedBusinessMutations,
                                header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        } else {
                            employeeApprovedBusinessEvents = addCount(employeeApprovedBusinessEvents)
                            employeeApprovedBusinessMutations = historyAddV2(employeeApprovedBusinessMutations,
                                header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                        }
                    }
                } else if (header.purpose == RuntimeWritePurposeV2.ROLE_TRANSITION) {
                    // Explicit current-authority lifecycle audit. The exact history evidence
                    // must exist and join the original/request/actor plus the genuine
                    // historical session/account/policy cut. Non-null metadata alone is not proof.
                    val evidence = masterLifecycle ?: malformed()
                    historyShapeV2(header.changeCount == 1 && authorizationMutations.size == 1 &&
                        authorizationMutations[0].tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag &&
                        authorizationMutations[0].mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                        decoded.originalActorId == evidence.actorId && decoded.originalOperationId == null &&
                        decoded.originalRequestId == evidence.eventId &&
                        decoded.originalRequestDigest == evidence.requestDigest &&
                        decoded.resources.isEmpty() && evidence.scope == scope &&
                        evidence.installationId == coordinates.installationId &&
                        evidence.coordinatesDigest == lifecycleCoordinatesDigest(coordinates))
                    val lifecycleEvent = evidence.event()
                    val audit = checkNotNull(lifecycleEvent.lifecycle)
                    val authorized = if (evidence.actorId == coordinates.firstActorId)
                        authenticationReplay?.lifecycle(lifecycleEvent, audit) == true
                    else employees.lifecycle(lifecycleEvent, audit)
                    historyShapeV2(authorized)
                    masterLifecycleEvidence[header.sequence] = evidence
                } else {
                    val command = if (RuntimeCommandAuthorizationEvidenceCodec.hasProfile(capsule.facts)) {
                        val decodedCommand = coldOrdinary ?: coldPaymentRejection ?: RuntimeCommandAuthorizationEvidenceCodec.decode(capsule.facts)
                        if (earlierTarget?.header?.sequence == header.sequence)
                            earlierTarget.select(header, commitment.digest, decodedCommand) else decodedCommand
                    } else null
                    val nativeOriginal = if (RuntimeNativeOriginalImportEvidenceCodec.hasProfile(capsule.facts)) {
                        val decodedOriginal = coldNativeOriginal ?: RuntimeNativeOriginalImportEvidenceCodec.decode(capsule.facts)
                        if (earlierTarget?.header?.sequence == header.sequence)
                            earlierTarget.selectNative(header, commitment.digest, decodedOriginal) else decodedOriginal
                    } else null
                    val replay = authenticationReplay
                    val join = business
                    var employeeCommand: RuntimeEmployeeCommandHistoryAuthorization? = null
                    var employeeSupervisorReview: RuntimeEmployeeSupervisorReviewHistoryAuthorization? = null
                    var originalSupervisorReview = false
                    var employeePurchasingPolicyRejection: RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization? = null
                    var originalPurchasingPolicyRejection = false
                    var employeeOrderReassignmentRejection: RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization? = null
                    var originalOrderReassignmentRejection = false
                    var employeeOpeningSupplierObligationRejection: RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization? = null
                    var originalOpeningSupplierObligationRejection = false
                    var employeeInvoiceCaptureRejection: RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization? = null
                    var originalInvoiceCaptureRejection = false
                    var employeeCapturedInvoiceMatchRejection: RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization? = null
                    var originalCapturedInvoiceMatchRejection = false
                    var employeeBranchActivityActivationRejection: RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization? = null
                    var originalBranchActivityActivationRejection = false
                    var employeeFloorConfigurationRejection: RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization? = null
                    var originalFloorConfigurationRejection = false
                    var employeeConfigurationRejection: RuntimeEmployeeConfigurationRejectionHistoryAuthorization? = null
                    var originalConfigurationRejection = false
                    var employeeReturnRightsActivationRejection: RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization? = null
                    var originalReturnRightsActivationRejection = false
                    var employeeRetailCustomerReceiptRejection: RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization? = null
                    var originalRetailCustomerReceiptRejection = false
                    var employeeFoodCheckRejection: RuntimeEmployeeFoodCheckRejectionHistoryAuthorization? = null
                    var originalFoodCheckRejection = false
                    var employeeFoodPaymentRejection: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization? = null
                    var originalFoodPaymentRejection = false
                    var employeeReturnCaseQualificationRejection: RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization? = null
                    var employeeReturnStockDispositionRejection: RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization? = null
                    var originalReturnCaseQualificationRejection = false
                    var originalReturnStockDispositionRejection = false
                    var employeeNativeOriginal = false
                    val observePrior = earlierTarget != null && header.sequence < earlierTarget.header.sequence &&
                        header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
                        (command != null && nativeOriginal == null && command.actionApprovalId == null ||
                            command == null && nativeOriginal != null)
                    val captured = if (join == null || !join.enabled) false else if (observePrior) {
                        val target = checkNotNull(earlierTarget)
                        if (nativeOriginal != null)
                            join.observeEarlierColdNativeOriginal(target, header, nativeOriginal, decoded,
                                capsule.dependencies, businessMutations)
                        else {
                            val priorCommand = checkNotNull(command)
                            join.hasUnprotectedColdOrdinaryMutations(header, priorCommand, businessMutations) &&
                                (if (priorCommand.authorization.actorId == coordinates.firstActorId)
                                    replay != null && replay.command(priorCommand)
                                else if (target.evidence != null) employees.observeEarlierOrdinaryCommand(priorCommand, header)
                                else employees.observeEarlierNativeOriginalPrefixCommand(priorCommand, header,
                                    target.header, target.eventDigest, checkNotNull(target.nativeOriginal)))
                        }
                    } else if (nativeOriginal != null) {
                        historyShapeV2(command == null)
                        if (nativeOriginal.producer.originalActorId == coordinates.firstActorId)
                            join.captureNative(header, commitment.digest, nativeOriginal, decoded, capsule.dependencies, businessMutations)
                        else join.captureEmployeeNative(header, commitment.digest, nativeOriginal, decoded, capsule.dependencies, businessMutations)
                            .also { employeeNativeOriginal = it }
                    } else if (command == null) false else if (header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT) {
                        val key = CommandKey(command.commandName, command.commandVersion)
                        val review = RuntimeHistoricalSupervisorReviewProfile.fromCommand(key)
                        val policy = RuntimeHistoricalPurchasingPolicyRejectionProfile.fromCommand(key)
                        val reassignment = RuntimeHistoricalOrderReassignmentRejectionProfile.fromCommand(key)
                        val openingObligation = RuntimeHistoricalOpeningSupplierObligationRejectionProfile.fromCommand(key)
                        val invoiceCapture = RuntimeHistoricalInvoiceCaptureRejectionProfile.fromCommand(key)
                        val capturedInvoiceMatch = RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.fromCommand(key)
                        val activityActivation = RuntimeHistoricalBranchActivityActivationRejectionProfile.fromCommand(key)
                        val floorConfiguration = RuntimeHistoricalFloorConfigurationRejectionProfile.fromCommand(key)
                        // Explicit configuration/pricing subtypes share only the closed
                        // five-mutation capture and original employee proof machinery.
                        val configuration = RuntimeHistoricalConfigurationRejectionProfile.fromCommand(key)
                        val returnRightsActivation = RuntimeHistoricalReturnRightsActivationRejectionProfile.fromCommand(key)
                        val retailCustomerReceipt = RuntimeHistoricalRetailCustomerReceiptRejectionProfile.fromCommand(key)
                        val foodCheck = RuntimeHistoricalFoodCheckRejectionProfile.fromCommand(key)
                        val foodPayment = RuntimeHistoricalFoodPaymentRejectionProfile.fromEvidence(command)
                        val returnCaseQualification = RuntimeHistoricalReturnCaseQualificationRejectionProfile.fromCommand(key)
                        val returnStockDisposition = RuntimeHistoricalReturnStockDispositionRejectionProfile.fromCommand(key)
                        when {
                            review?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureSupervisorReview(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalSupervisorReview = it }
                                else {
                                    employeeSupervisorReview = employees.supervisorReview(command, header, commitment.digest)
                                    employeeSupervisorReview?.let { join.captureEmployeeSupervisorReview(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            policy?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.capturePurchasingPolicyRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalPurchasingPolicyRejection = it }
                                else {
                                    employeePurchasingPolicyRejection = employees.purchasingPolicyRejection(command, header, commitment.digest)
                                    employeePurchasingPolicyRejection?.let { join.captureEmployeePurchasingPolicyRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            reassignment?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureOrderReassignmentRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalOrderReassignmentRejection = it }
                                else {
                                    employeeOrderReassignmentRejection = employees.orderReassignmentRejection(command, header, commitment.digest)
                                    employeeOrderReassignmentRejection?.let { join.captureEmployeeOrderReassignmentRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            openingObligation?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureOpeningSupplierObligationRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalOpeningSupplierObligationRejection = it }
                                else {
                                    employeeOpeningSupplierObligationRejection = employees.openingSupplierObligationRejection(command, header, commitment.digest)
                                    employeeOpeningSupplierObligationRejection?.let { join.captureEmployeeOpeningSupplierObligationRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            invoiceCapture?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureInvoiceCaptureRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalInvoiceCaptureRejection = it }
                                else {
                                    employeeInvoiceCaptureRejection = employees.invoiceCaptureRejection(command, header, commitment.digest)
                                    employeeInvoiceCaptureRejection?.let { join.captureEmployeeInvoiceCaptureRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            capturedInvoiceMatch?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureCapturedInvoiceMatchRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalCapturedInvoiceMatchRejection = it }
                                else {
                                    employeeCapturedInvoiceMatchRejection = employees.capturedInvoiceMatchRejection(command, header, commitment.digest)
                                    employeeCapturedInvoiceMatchRejection?.let { join.captureEmployeeCapturedInvoiceMatchRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            activityActivation?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureBranchActivityActivationRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalBranchActivityActivationRejection = it }
                                else {
                                    employeeBranchActivityActivationRejection = employees.branchActivityActivationRejection(command, header, commitment.digest)
                                    employeeBranchActivityActivationRejection?.let { join.captureEmployeeBranchActivityActivationRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            floorConfiguration?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureFloorConfigurationRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalFloorConfigurationRejection = it }
                                else {
                                    employeeFloorConfigurationRejection = employees.floorConfigurationRejection(command, header, commitment.digest)
                                    employeeFloorConfigurationRejection?.let { join.captureEmployeeFloorConfigurationRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            configuration?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureConfigurationRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalConfigurationRejection = it }
                                else {
                                    employeeConfigurationRejection = employees.configurationRejection(command, header, commitment.digest)
                                    employeeConfigurationRejection?.let { join.captureEmployeeConfigurationRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            returnRightsActivation?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureReturnRightsActivationRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalReturnRightsActivationRejection = it }
                                else {
                                    employeeReturnRightsActivationRejection = employees.returnRightsActivationRejection(command, header, commitment.digest)
                                    employeeReturnRightsActivationRejection?.let { join.captureEmployeeReturnRightsActivationRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            retailCustomerReceipt?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureRetailCustomerReceiptRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalRetailCustomerReceiptRejection = it }
                                else {
                                    employeeRetailCustomerReceiptRejection = employees.retailCustomerReceiptRejection(command, header, commitment.digest)
                                    employeeRetailCustomerReceiptRejection?.let { join.captureEmployeeRetailCustomerReceiptRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            foodCheck?.matches(header, command) == true &&
                                foodCheck.hasMarker(header, command, capsule.dependencies) -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureFoodCheckRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalFoodCheckRejection = it }
                                else {
                                    employeeFoodCheckRejection = employees.foodCheckRejection(command, header, commitment.digest)
                                    employeeFoodCheckRejection?.let { join.captureEmployeeFoodCheckRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            foodPayment?.matches(header, command) == true -> {
                                val target = earlierTarget
                                // Only strictly earlier ordinary refusals enter a prefix.
                                // Their full source/producer join retires before its target.
                                if (target != null && (header.sequence >= target.header.sequence ||
                                        header.commandSequence >= target.header.commandSequence)) false
                                else if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && (when {
                                        target == null -> replay.command(command)
                                        target.evidence != null -> employees.foodPaymentRejectionEarlierOrdinaryFoundingPrefix(replay, command, header)
                                        else -> employees.foodPaymentRejectionEarlierNativeOriginalFoundingPrefix(replay, command, header,
                                            target.header, target.eventDigest, checkNotNull(target.nativeOriginal))
                                    }) && join.captureFoodPaymentRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalFoodPaymentRejection = it }
                                else {
                                    employeeFoodPaymentRejection = if (target == null || target.evidence != null)
                                        employees.foodPaymentRejection(command, header, commitment.digest)
                                    else employees.foodPaymentRejectionEarlierNativeOriginalPrefix(command, header, commitment.digest,
                                        target.header, target.eventDigest, checkNotNull(target.nativeOriginal))
                                    employeeFoodPaymentRejection?.let { join.captureEmployeeFoodPaymentRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            returnCaseQualification?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureReturnCaseQualificationRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalReturnCaseQualificationRejection = it }
                                else {
                                    employeeReturnCaseQualificationRejection = employees.returnCaseQualificationRejection(command, header, commitment.digest)
                                    employeeReturnCaseQualificationRejection?.let { join.captureEmployeeReturnCaseQualificationRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            returnStockDisposition?.matches(header, command) == true -> {
                                if (command.authorization.actorId == coordinates.firstActorId)
                                    (replay != null && replay.command(command) && join.captureReturnStockDispositionRejection(header, commitment.digest,
                                        command, decoded, capsule.dependencies, businessMutations)).also { originalReturnStockDispositionRejection = it }
                                else {
                                    employeeReturnStockDispositionRejection = employees.returnStockDispositionRejection(command, header, commitment.digest)
                                    employeeReturnStockDispositionRejection?.let { join.captureEmployeeReturnStockDispositionRejection(header, commitment.digest,
                                        it, decoded, capsule.dependencies, businessMutations) } == true
                                }
                            }
                            else -> false
                        }
                    } else if (command.authorization.actorId == coordinates.firstActorId)
                        replay != null && (if (earlierTarget?.evidence == null) replay.command(command) else
                            employees.commandEarlierOrdinaryFoundingTarget(replay, header, commitment.digest, command)) &&
                            join.capture(header, commitment.digest, command, decoded, capsule.dependencies, businessMutations)
                    else {
                        employeeCommand = employees.command(command, header, commitment.digest)
                        employeeCommand?.let { join.captureEmployee(header, commitment.digest, it, decoded, capsule.dependencies, businessMutations) } == true
                    }
                    if (captured) {
                        val previous = requireNotNull(previousEvent)
                        historyShapeV2(header.commandEpoch == previous.commandEpoch && header.commandSequence == previous.commandSequence + 1 &&
                            header.roleCommitment == previous.roleCommitment && header.recoveryCommitment == previous.recoveryCommitment)
                        if (!observePrior) when {
                            employeeReturnCaseQualificationRejection != null -> {
                                employeeReturnCaseQualificationRejectionEvents = addCount(employeeReturnCaseQualificationRejectionEvents)
                                employeeReturnCaseQualificationRejectionMutations = historyAddV2(employeeReturnCaseQualificationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalReturnCaseQualificationRejection -> {
                                originalReturnCaseQualificationRejectionEvents = addCount(originalReturnCaseQualificationRejectionEvents)
                                originalReturnCaseQualificationRejectionMutations = historyAddV2(originalReturnCaseQualificationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeReturnStockDispositionRejection != null -> {
                                employeeReturnStockDispositionRejectionEvents = addCount(employeeReturnStockDispositionRejectionEvents)
                                employeeReturnStockDispositionRejectionMutations = historyAddV2(employeeReturnStockDispositionRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalReturnStockDispositionRejection -> {
                                originalReturnStockDispositionRejectionEvents = addCount(originalReturnStockDispositionRejectionEvents)
                                originalReturnStockDispositionRejectionMutations = historyAddV2(originalReturnStockDispositionRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeFoodCheckRejection != null -> {
                                employeeFoodCheckRejectionEvents = addCount(employeeFoodCheckRejectionEvents)
                                employeeFoodCheckRejectionMutations = historyAddV2(employeeFoodCheckRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalFoodCheckRejection -> {
                                originalFoodCheckRejectionEvents = addCount(originalFoodCheckRejectionEvents)
                                originalFoodCheckRejectionMutations = historyAddV2(originalFoodCheckRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeFoodPaymentRejection != null -> {
                                employeeFoodPaymentRejectionEvents = addCount(employeeFoodPaymentRejectionEvents)
                                employeeFoodPaymentRejectionMutations = historyAddV2(employeeFoodPaymentRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalFoodPaymentRejection -> {
                                originalFoodPaymentRejectionEvents = addCount(originalFoodPaymentRejectionEvents)
                                originalFoodPaymentRejectionMutations = historyAddV2(originalFoodPaymentRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeRetailCustomerReceiptRejection != null -> {
                                employeeRetailCustomerReceiptRejectionEvents = addCount(employeeRetailCustomerReceiptRejectionEvents)
                                employeeRetailCustomerReceiptRejectionMutations = historyAddV2(employeeRetailCustomerReceiptRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalRetailCustomerReceiptRejection -> {
                                originalRetailCustomerReceiptRejectionEvents = addCount(originalRetailCustomerReceiptRejectionEvents)
                                originalRetailCustomerReceiptRejectionMutations = historyAddV2(originalRetailCustomerReceiptRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeReturnRightsActivationRejection != null -> {
                                employeeReturnRightsActivationRejectionEvents = addCount(employeeReturnRightsActivationRejectionEvents)
                                employeeReturnRightsActivationRejectionMutations = historyAddV2(employeeReturnRightsActivationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalReturnRightsActivationRejection -> {
                                originalReturnRightsActivationRejectionEvents = addCount(originalReturnRightsActivationRejectionEvents)
                                originalReturnRightsActivationRejectionMutations = historyAddV2(originalReturnRightsActivationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeFloorConfigurationRejection != null -> {
                                employeeFloorConfigurationRejectionEvents = addCount(employeeFloorConfigurationRejectionEvents)
                                employeeFloorConfigurationRejectionMutations = historyAddV2(employeeFloorConfigurationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalFloorConfigurationRejection -> {
                                originalFloorConfigurationRejectionEvents = addCount(originalFloorConfigurationRejectionEvents)
                                originalFloorConfigurationRejectionMutations = historyAddV2(originalFloorConfigurationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeConfigurationRejection != null -> {
                                employeeConfigurationRejectionEvents = addCount(employeeConfigurationRejectionEvents)
                                employeeConfigurationRejectionMutations = historyAddV2(employeeConfigurationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalConfigurationRejection -> {
                                originalConfigurationRejectionEvents = addCount(originalConfigurationRejectionEvents)
                                originalConfigurationRejectionMutations = historyAddV2(originalConfigurationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeBranchActivityActivationRejection != null -> {
                                employeeBranchActivityActivationRejectionEvents = addCount(employeeBranchActivityActivationRejectionEvents)
                                employeeBranchActivityActivationRejectionMutations = historyAddV2(employeeBranchActivityActivationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalBranchActivityActivationRejection -> {
                                originalBranchActivityActivationRejectionEvents = addCount(originalBranchActivityActivationRejectionEvents)
                                originalBranchActivityActivationRejectionMutations = historyAddV2(originalBranchActivityActivationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeInvoiceCaptureRejection != null -> {
                                employeeInvoiceCaptureRejectionEvents = addCount(employeeInvoiceCaptureRejectionEvents)
                                employeeInvoiceCaptureRejectionMutations = historyAddV2(employeeInvoiceCaptureRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalInvoiceCaptureRejection -> {
                                originalInvoiceCaptureRejectionEvents = addCount(originalInvoiceCaptureRejectionEvents)
                                originalInvoiceCaptureRejectionMutations = historyAddV2(originalInvoiceCaptureRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeCapturedInvoiceMatchRejection != null -> {
                                employeeCapturedInvoiceMatchRejectionEvents = addCount(employeeCapturedInvoiceMatchRejectionEvents)
                                employeeCapturedInvoiceMatchRejectionMutations = historyAddV2(employeeCapturedInvoiceMatchRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalCapturedInvoiceMatchRejection -> {
                                originalCapturedInvoiceMatchRejectionEvents = addCount(originalCapturedInvoiceMatchRejectionEvents)
                                originalCapturedInvoiceMatchRejectionMutations = historyAddV2(originalCapturedInvoiceMatchRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeOpeningSupplierObligationRejection != null -> {
                                employeeOpeningSupplierObligationRejectionEvents = addCount(employeeOpeningSupplierObligationRejectionEvents)
                                employeeOpeningSupplierObligationRejectionMutations = historyAddV2(employeeOpeningSupplierObligationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalOpeningSupplierObligationRejection -> {
                                originalOpeningSupplierObligationRejectionEvents = addCount(originalOpeningSupplierObligationRejectionEvents)
                                originalOpeningSupplierObligationRejectionMutations = historyAddV2(originalOpeningSupplierObligationRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeOrderReassignmentRejection != null -> {
                                employeeOrderReassignmentRejectionEvents = addCount(employeeOrderReassignmentRejectionEvents)
                                employeeOrderReassignmentRejectionMutations = historyAddV2(employeeOrderReassignmentRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalOrderReassignmentRejection -> {
                                originalOrderReassignmentRejectionEvents = addCount(originalOrderReassignmentRejectionEvents)
                                originalOrderReassignmentRejectionMutations = historyAddV2(originalOrderReassignmentRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeePurchasingPolicyRejection != null -> {
                                employeePurchasingPolicyRejectionEvents = addCount(employeePurchasingPolicyRejectionEvents)
                                employeePurchasingPolicyRejectionMutations = historyAddV2(employeePurchasingPolicyRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalPurchasingPolicyRejection -> {
                                originalPurchasingPolicyRejectionEvents = addCount(originalPurchasingPolicyRejectionEvents)
                                originalPurchasingPolicyRejectionMutations = historyAddV2(originalPurchasingPolicyRejectionMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeSupervisorReview != null -> {
                                employeeSupervisorReviewEvents = addCount(employeeSupervisorReviewEvents)
                                employeeSupervisorReviewMutations = historyAddV2(employeeSupervisorReviewMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            originalSupervisorReview -> {
                                originalSupervisorReviewEvents = addCount(originalSupervisorReviewEvents)
                                originalSupervisorReviewMutations = historyAddV2(originalSupervisorReviewMutations,
                                    header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeCommand != null -> {
                                employeeBusinessEvents = addCount(employeeBusinessEvents)
                                employeeBusinessMutations = historyAddV2(employeeBusinessMutations, header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            employeeNativeOriginal -> {
                                employeeNativeOriginalEvents = addCount(employeeNativeOriginalEvents)
                                employeeNativeOriginalMutations = historyAddV2(employeeNativeOriginalMutations, header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                            else -> {
                                historyShapeV2(header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY)
                                originalBusinessEvents = addCount(originalBusinessEvents)
                                originalBusinessMutations = historyAddV2(originalBusinessMutations, header.changeCount.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
                            }
                        }
                        if (cold != null && !observePrior) joinColdCommand(header, command, nativeOriginal)
                    } else holdUnjoinedAuthorization()
                }
                if (header.purpose in setOf(RuntimeWritePurposeV2.COMMAND_APPLY, RuntimeWritePurposeV2.COMMAND_REJECT))
                    historyShapeV2(decoded.originalOperationId != null && decoded.originalActorId != null)
            }
            native.pending?.takeIf { it.child.historySequence == header.sequence }?.let { pending ->
                historyShapeV2(pending.child.historyDigest == commitment.digest)
                val expected = SelfOwnedNativeHistoryWireV2.encodeIntent(pending.originalIntent)
                try { historyShapeV2(expected.contentEquals(capsule.intent)) } finally { expected.fill(0) }
            }
            val join = business
            if (join?.enabled == true) {
                foundingRecipientOrigin?.let { join.captureFoundingRecipientOrigin(header, commitment.digest, it) }
                if (recipientEnrollment) employees.captureRecipientEnrollment(join, header, commitment.digest)
                captureRecipientAccounts(header, changedRecipientActors)
                for (actor in changedRecipientPolicies) {
                    if (actor == coordinates.firstActorId)
                        join.captureRecipientPolicyAfter(header, authenticationReplay ?: malformed())
                    else employees.captureRecipientPolicyAfter(join,header,actor)
                }
            }
            previousEvent = header; previousEventDigest = commitment.digest; eventCount = addCount(eventCount)
        }
        fun clear() {
            if (cleared) return
            cleared = true
            row.header.fill(0); capsule.clear()
            decodedColdNativeOriginal?.canonicalRequirement?.bytes()?.fill(0)
            decodedColdNativeOriginal?.certificate?.bytes()?.fill(0)
            decodedColdNativeOriginal = null
            accountImageVersion?.key?.fill(0); accountImageVersion = null; accountImageBinding = null
            accountCandidate = null; accountCandidateBinding = null; accountCandidateDigest = null
            authorizationMutations.forEach { it.key.fill(0) }; authorizationMutations.clear()
            businessMutations.forEach { it.key.fill(0) }; businessMutations.clear()
            employeeEnrollmentMutations.forEach { it.key.fill(0) }; employeeEnrollmentMutations.clear(); stream.clear()
            employeePolicyActivationMutations.forEach { it.key.fill(0) }; employeePolicyActivationMutations.clear()
            deviceAdmissionMutations.forEach { it.key.fill(0) }; deviceAdmissionMutations.clear()
            approvalIssuanceMutations.forEach { it.key.fill(0) }; approvalIssuanceMutations.clear()
            approvalRetirementMutations.forEach { it.key.fill(0) }; approvalRetirementMutations.clear()
            serialPublicationMutations.forEach { it.key.fill(0) }; serialPublicationMutations.clear()
            if (!retainedByScanner) coldScanMemory?.memory?.release(workspaceBytes)
        }
    }

    /** Exact additive approval profiles are bound before any chronological reducer.
     * These are DATA checks; only an authentic native final and earlier original actors
     * can supply the approval join. Legacy/direct profiles remain unchanged. */
    private fun boundApprovalIssuance(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeActionApprovalIssuanceEvidenceV1? {
        if (!RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(capsule.facts)) return null
        val evidence = RuntimeActionApprovalEvidenceCodec.decodeIssuance(capsule.facts)
        historyShapeV2(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.APPROVAL_ISSUE &&
            header.changeCount == 4 && evidence.requestor.scope == scope &&
            intent.originalRequestId == evidence.approvalId && intent.originalActorId == evidence.requestor.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() &&
            intent.originalRequestDigest == evidence.value().issueRequestDigest)
        val dependencies = RuntimeActionApprovalEvidenceCodec.dependencies(evidence)
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }

    /** A separately versioned, one-audit retirement at the exact native before cut.
     * The old AP expiry is deliberately irrelevant; current requester/final native
     * authority and complete original/operation joins are supplied by the reducer. */
    private fun boundApprovalRetirement(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeActionApprovalRetirementEvidenceV1? {
        if (!RuntimeActionApprovalRetirementEvidenceCodec.hasProfile(capsule.facts)) return null
        val evidence = RuntimeActionApprovalRetirementEvidenceCodec.decode(capsule.facts)
        historyShapeV2(header.sequence > 2 && header.purpose == RuntimeWritePurposeV2.APPROVAL_RETIRE && header.changeCount == 1 &&
            evidence.requestor.scope == scope && header.parentKind == 2 &&
            evidence.beforeHistorySequence == header.parentSequence && evidence.beforeHistoryDigest == header.parentDigest &&
            evidence.beforeCommandEpoch.value == header.commandEpoch && evidence.beforeCommandSequence.value == header.commandSequence &&
            evidence.beforeCommandDigest == header.commandDigest &&
            intent.originalRequestId == evidence.retirementId() && intent.originalActorId == evidence.requestor.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() &&
            intent.originalRequestDigest == runtimeApprovalDigest(evidence.value()))
        val dependencies = RuntimeActionApprovalRetirementEvidenceCodec.dependencies(evidence)
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }

    private fun boundApprovedCommand(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeApprovedCommandEvidenceV1? {
        if (!RuntimeActionApprovalEvidenceCodec.hasCommandProfile(capsule.facts)) return null
        val evidence = RuntimeActionApprovalEvidenceCodec.decodeCommand(capsule.facts)
        val command = evidence.command()
        historyShapeV2(header.sequence > 1 && header.purpose in setOf(
            RuntimeWritePurposeV2.COMMAND_APPLY, RuntimeWritePurposeV2.COMMAND_REJECT) &&
            command.purposeTag == header.purpose.tag && command.capturedChanges == header.changeCount &&
            command.authorization.scope == scope && command.actionApprovalId != null &&
            intent.originalOperationId?.value == command.operationId.value && intent.originalRequestId.value == command.operationId.value &&
            intent.originalActorId == command.authorization.actorId && intent.originalRequestDigest == command.semanticDigest)
        // Dependency framing and every actual read are closed by captureApproved's
        // original business producer; the capsule already binds their exact digest.
        return evidence
    }

    /** Tag 19 has only this closed canonical capsule. Generic owned decisions or copied
     * actor/session facts cannot fall through to an ordinary command or authentication lane. */
    private fun boundSerialPublicationRequest(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeSerialPublicationRequestEvidenceV1? {
        if (!RuntimeSerialPublicationRequestEvidenceCodec.hasProfile(capsule.facts)) {
            historyShapeV2(header.purpose != RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST)
            return null
        }
        val evidence = RuntimeSerialPublicationRequestEvidenceCodec.decode(capsule.facts)
        val request = evidence.requestValue()
        historyShapeV2(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST &&
            header.changeCount == 1 && header.imageBytes == 0L && request.scope == scope &&
            request.originalAllocationDigest == allocation.digest && intent.originalRequestId == request.requestId &&
            intent.originalActorId == evidence.authorization.actorId && intent.originalOperationId == null && intent.resources.isEmpty() &&
            intent.originalRequestDigest == evidence.semanticDigest)
        val dependencies = RuntimeSerialPublicationRequestEvidenceCodec.dependencies(evidence)
        try {
            historyShapeV2(dependencies.contentEquals(capsule.dependencies))
            val canonical = SelfOwnedNativeHistoryWireV2.encodeIntent(NativeHistoryOriginalIntentV2(
                NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG, request.requestId, evidence.semanticDigest, null,
                evidence.authorization.actorId, sha256(dependencies), sha256(capsule.facts), emptyList()))
            try { historyShapeV2(canonical.contentEquals(capsule.intent)) } finally { canonical.fill(0) }
        } finally { dependencies.fill(0) }
        return evidence
    }

    /** Typed signed DATA still needs both the complete lineage join below and the original
     * seed authority. Old owned-decision capsules keep their existing unsupported hold. */
    private fun boundThrottle(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeThrottleEvidenceV1? {
        if (!RuntimeThrottleEvidenceCodec.hasProfile(capsule.facts)) return null
        val evidence = RuntimeThrottleEvidenceCodec.decode(capsule.facts)
        historyShapeV2(header.sequence > 1 && evidence.purpose == header.purpose && evidence.scope == scope &&
            intent.originalRequestId == evidence.attemptId && intent.originalActorId == evidence.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == sha256(capsule.facts))
        val dependencies = RuntimeThrottleEvidenceCodec.dependencies(evidence)
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }

    private fun isOriginalThrottle(evidence: RuntimeThrottleEvidenceV1): Boolean {
        val seeds = native.receipt?.core?.seeds ?: return false
        return !pendingOriginalRecovery && evidence.scope == scope && evidence.actorId == coordinates.firstActorId &&
            evidence.deviceId == coordinates.deviceId && evidence.dependencies.admission == seeds.admission &&
            evidence.dependencies.configuration == seeds.configuration
        // The mutable account dependency and BOTH throttle buckets are joined separately by
        // complete event-order replay from original seeds; this identity predicate grants none.
    }

    /** Separate extra-device authentication lane. The cohost-only approval predicate above
     * is unchanged; native source selection here is not current authority or an accepting seed. */
    private fun isOriginalAuthenticationThrottle(evidence: RuntimeThrottleEvidenceV1, sequence: Long): Boolean {
        if (isOriginalThrottle(evidence)) return true
        val seeds = native.receipt?.core?.seeds ?: return false
        return !pendingOriginalRecovery && evidence.scope == scope && evidence.actorId == coordinates.firstActorId &&
            evidence.dependencies.configuration == seeds.configuration &&
            deviceAdmissions.matchesAuthenticationOrigin(evidence.actorId, evidence.deviceId, evidence.dependencies.admission, sequence)
    }

    private fun isEmployeeAuthenticationThrottle(evidence: RuntimeThrottleEvidenceV1, sequence: Long): Boolean =
        employees.matchesThrottle(evidence) || employees.matchesAdditionalDeviceThrottle(evidence, sequence)

    private fun boundAuthentication(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeAuthenticationEvidenceV1? {
        if (!RuntimeAuthenticationEvidenceCodec.hasProfile(capsule.facts)) return null
        val evidence = RuntimeAuthenticationEvidenceCodec.decode(capsule.facts)
        historyShapeV2(header.sequence > 1 && evidence.purpose == header.purpose && evidence.scope == scope &&
            intent.originalRequestId == evidence.eventId && intent.originalActorId == evidence.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == sha256(capsule.facts))
        val dependencies = RuntimeAuthenticationEvidenceCodec.dependencies(evidence)
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }

    private fun isOriginalAuthentication(evidence: RuntimeAuthenticationEvidenceV1, sequence: Long): Boolean {
        val seeds = native.receipt?.core?.seeds ?: return false
        return !pendingOriginalRecovery && evidence.scope == scope && evidence.actorId == coordinates.firstActorId &&
            evidence.dependencies.configuration == seeds.configuration &&
            ((evidence.deviceId == coordinates.deviceId && evidence.dependencies.admission == seeds.admission) ||
                deviceAdmissions.matchesAuthenticationOrigin(evidence.actorId, evidence.deviceId, evidence.dependencies.admission, sequence))
    }

    private fun isEmployeeAuthentication(evidence: RuntimeAuthenticationEvidenceV1, sequence: Long): Boolean =
        employees.matchesAuthentication(evidence) || employees.matchesAdditionalDeviceAuthentication(evidence, sequence)

    private fun boundSessionRevocation(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeSessionRevocationHistoryEvidence? {
        // Exact discriminators/readers stay separate. Old self-session history can never
        // acquire another target/action under retained-session, disable or reset formats.
        val evidence = when {
            RuntimeSessionRevocationEvidenceCodec.hasProfile(capsule.facts) ->
                RuntimeSessionRevocationHistoryEvidence.self(RuntimeSessionRevocationEvidenceCodec.decode(capsule.facts))
            RuntimeRetainedSessionRevocationEvidenceCodec.hasProfile(capsule.facts) ->
                RuntimeSessionRevocationHistoryEvidence.retained(RuntimeRetainedSessionRevocationEvidenceCodec.decode(capsule.facts))
            RuntimeAccountDisableEvidenceCodec.hasProfile(capsule.facts) ->
                RuntimeSessionRevocationHistoryEvidence.disable(RuntimeAccountDisableEvidenceCodec.decode(capsule.facts))
            RuntimeCredentialResetEvidenceCodec.hasProfile(capsule.facts) ->
                RuntimeSessionRevocationHistoryEvidence.reset(RuntimeCredentialResetEvidenceCodec.decode(capsule.facts))
            else -> return null
        }
        historyShapeV2(header.sequence > 1 && header.purpose == evidence.purpose &&
            header.changeCount == RuntimeSessionRevocationHistoryEvidence.MAX_IMAGES && evidence.authorization.scope == scope &&
            intent.originalRequestId == evidence.command.requestId && intent.originalActorId == evidence.authorization.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == evidence.semanticDigest)
        val dependencies = evidence.dependencies()
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }
    /** A separate one-audit profile, not an alternate cardinality for old two-image facts. */
    private fun boundAdministrationCancellation(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeAdministrationCancellationEvidenceV1? {
        if (!RuntimeAdministrationCancellationEvidenceCodec.hasProfile(capsule.facts)) return null
        val evidence = RuntimeAdministrationCancellationEvidenceCodec.decode(capsule.facts)
        historyShapeV2(header.sequence > 1 && header.purpose == evidence.purpose &&
            header.changeCount == RuntimeAdministrationCancellationEvidenceV1.MAX_IMAGES && evidence.authorization.scope == scope &&
            intent.originalRequestId == evidence.command.requestId && intent.originalActorId == evidence.authorization.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == evidence.semanticDigest)
        val dependencies = RuntimeAdministrationCancellationEvidenceCodec.dependencies(evidence)
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }

    private fun requireAdministrationCancellationMutation(mutation: RuntimeHistoryRowVersionRowV2,
        expected: RuntimeAuthenticationImageV1) {
        val key = expected.key(scope)
        try { historyShapeV2(mutation.ordinal == 0 && expected.table == RuntimeHistoryTableV2.AUTH_EVENT &&
            mutation.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag && mutation.key.contentEquals(key) &&
            mutation.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && mutation.version == 1L &&
            mutation.beforeRowId == null && mutation.beforeDigest == null && mutation.previousSequence == null &&
            mutation.previousOrdinal == null && mutation.previousVersion == null && mutation.afterRowId != null && mutation.afterDigest != null &&
            mutation.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag && mutation.imageOffset == null && mutation.imageBytes == null) }
        finally { key.fill(0) }
    }

    /** Distinct N+2 device profile. The complete native original definition supplies the
     * admitted C1 seed; neither today's revoked admission nor a target identity grants the
     * original operator authority. Reuse only a fully committed EVENT's exact header/hash
     * after the independent lineage capsule has supplied ALL its authenticated bytes. */
    private fun boundDeviceRevocation(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeDeviceRevocationEvidenceV1? {
        if (!RuntimeDeviceRevocationEvidenceCodec.hasProfile(capsule.facts)) return null
        val retained = deviceRevocationEvent?.takeIf { it.header.sequence == header.sequence }
        if (retained != null) historyShapeV2(retained.header == header && sha256(capsule.facts) == header.factDigest &&
            sha256(capsule.dependencies) == header.dependencyDigest)
        val evidence = retained?.evidence ?: RuntimeDeviceRevocationEvidenceCodec.decode(capsule.facts)
        val origin = definition.definition.masterAdmission
        val originDigest = deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(origin))
        historyShapeV2(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.DEVICE_ADMINISTRATION &&
            header.changeCount == evidence.images.size && evidence.authorization.scope == scope &&
            evidence.authorization.deviceId == coordinates.deviceId && evidence.command.targetId == coordinates.deviceId &&
            origin.scope == scope && origin.deviceId == coordinates.deviceId && origin.admitted && origin.revision == Revision(0) &&
            evidence.admissionBeforeDigest == originDigest && originDigest == native.receipt?.core?.seeds?.admission &&
            intent.originalRequestId == evidence.command.requestId && intent.originalActorId == evidence.authorization.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == evidence.semanticDigest)
        if (retained == null) {
            val dependencies = RuntimeDeviceRevocationEvidenceCodec.dependencies(evidence)
            try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        }
        return evidence
    }
    /** V2 remains a declaration until the admission join proves the earlier consumed
     * native origin, current operator, complete accounts and exact native final/effects. */
    private fun boundAdditionalDeviceRevocation(header: RuntimeHistoryEventHeaderV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2): RuntimeDeviceRevocationEvidenceV2? {
        if (!RuntimeDeviceRevocationEvidenceCodecV2.hasProfile(capsule.facts)) return null
        val evidence = RuntimeDeviceRevocationEvidenceCodecV2.decode(capsule.facts)
        historyShapeV2(header.sequence > 1 && header.purpose == evidence.purpose && header.changeCount == evidence.images.size &&
            evidence.authorization.scope == scope && evidence.authorization.deviceId == coordinates.deviceId &&
            evidence.command.targetId != coordinates.deviceId && intent.purposeTag == evidence.purpose.tag &&
            intent.originalRequestId == evidence.command.requestId && intent.originalActorId == evidence.authorization.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == evidence.semanticDigest)
        val dependencies = RuntimeDeviceRevocationEvidenceCodecV2.dependencies(evidence)
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
        return evidence
    }

    private fun requireDeviceRevocationMutation(mutation: RuntimeHistoryRowVersionRowV2,
        value: RuntimeDeviceRevocationEvidenceV1, expected: RuntimeDeviceRevocationImageV1, ordinal: Int) {
        val key = expected.key(scope)
        try {
            historyShapeV2(mutation.ordinal == ordinal && mutation.tableTag == expected.tableTag && mutation.key.contentEquals(key) &&
                mutation.mutationKind == expected.mutation.tag && mutation.afterRowId != null && mutation.afterDigest != null &&
                mutation.afterKind == (if (expected.table == RuntimeHistoryTableV2.AUTH_EVENT)
                    RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag else RuntimeHistoryAfterKindV2.IMAGE.tag))
            if (expected.table == RuntimeHistoryTableV2.AUTH_EVENT) historyShapeV2(ordinal == value.images.lastIndex &&
                mutation.version == 1L && mutation.beforeRowId == null && mutation.beforeDigest == null && mutation.previousSequence == null &&
                mutation.previousOrdinal == null && mutation.previousVersion == null && mutation.imageOffset == null && mutation.imageBytes == null)
            else {
                val previousSequence = mutation.previousSequence
                val previousVersion = mutation.previousVersion
                historyShapeV2(mutation.beforeRowId != null && mutation.beforeDigest != null && mutation.afterRowId == mutation.beforeRowId &&
                    previousSequence != null && previousSequence < mutation.sequence && mutation.previousOrdinal != null &&
                    previousVersion != null && previousVersion >= 1L && mutation.version == previousVersion + 1L &&
                    mutation.imageOffset != null && mutation.imageBytes != null)
                if (expected.table == RuntimeHistoryTableV2.ADMISSION) historyShapeV2(ordinal == 0 && mutation.previousSequence == 1L &&
                    mutation.previousVersion == 1L && mutation.version == 2L)
                else historyShapeV2(ordinal in 1 until value.images.lastIndex &&
                    value.affectedAccounts[ordinal - 1].actorId == expected.recordId &&
                    value.affectedAccounts[ordinal - 1].afterRevision == expected.revision)
                // Logical lineage version is NOT an account revision: the original reducer's
                // terminal lockdown can jump revision/generation to MAX without truncation.
            }
        } finally { key.fill(0) }
    }
    /** Exact EVENT-to-LINEAGE crosswalk, not a count replacing genuine protected images. */
    private fun deviceRevocationLineageImage(active: LineageScan, value: RuntimeDeviceRevocationEvidenceV1): RuntimeDeviceRevocationImageV1 {
        val retained = checkNotNull(deviceRevocationEvent)
        historyShapeV2(retained.header == active.eventHeader && retained.evidence == value)
        val version = retained.versions.getOrNull(active.row.ordinal) ?: malformed()
        val expected = value.images.getOrNull(active.row.ordinal) ?: malformed()
        val eventBytes = RuntimeHistoryCodecV2.encodeMutation(version)
        val lineageBytes = RuntimeHistoryCodecV2.encodeMutation(active.row)
        try { historyShapeV2(eventBytes.contentEquals(lineageBytes)) } finally { eventBytes.fill(0); lineageBytes.fill(0) }
        requireDeviceRevocationMutation(active.row, value, expected, active.row.ordinal)
        if (expected.table == RuntimeHistoryTableV2.ADMISSION)
            historyShapeV2(active.previousAdmissionDigest == value.admissionBeforeDigest)
        else if (expected.table == RuntimeHistoryTableV2.ACCOUNT)
            historyShapeV2(active.previousAccountDigest == value.accounts.single { it.actorId == expected.recordId }.accountBeforeDigest)
        return expected
    }

    private fun isOriginalSessionRevocation(evidence: RuntimeSessionRevocationEvidenceV1): Boolean {
        val seeds = native.receipt?.core?.seeds ?: return false
        return !pendingOriginalRecovery && evidence.authorization.scope == scope &&
            evidence.authorization.actorId == coordinates.firstActorId && evidence.authorization.deviceId == coordinates.deviceId &&
            evidence.authorization.dependencies.admission == seeds.admission
        // Original credential/account/access/configuration continuity and this ADMINISTER
        // requirement still require the unchanged bounded event-order replay, not this predicate.
    }
    private fun requireSessionRevocationMutation(mutation: RuntimeHistoryRowVersionRowV2,
        expected: RuntimeAuthenticationImageV1, ordinal: Int) {
        val key = expected.key(scope)
        try { historyShapeV2(mutation.ordinal == ordinal && mutation.tableTag == expected.tableTag &&
            mutation.key.contentEquals(key) && mutation.mutationKind == expected.mutation.tag &&
            mutation.afterKind == (if (expected.table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag
                else RuntimeHistoryAfterKindV2.IMAGE.tag) &&
            (expected.mutation != RuntimeHistoryMutationKindV2.INSERT || mutation.version == 1L)) }
        finally { key.fill(0) }
    }

    /** The distinct target BEFORE is the exact previous native account image, whose full
     * plaintext is independently traversed in LINEAGE and predicted from its own seeds.
     * The audit is a fresh immutable original request, never a replace/reuse of old history. */
    private fun requireAccountAdministrationPredecessor(mutation: RuntimeHistoryRowVersionRowV2,
        expectedRevision: Revision, ordinal: Int) {
        val previousSequence = mutation.previousSequence
        if (ordinal == 0) historyShapeV2(mutation.beforeRowId != null && mutation.beforeDigest != null &&
            mutation.afterRowId == mutation.beforeRowId && previousSequence != null &&
            previousSequence < mutation.sequence && mutation.previousOrdinal != null &&
            mutation.previousVersion == expectedRevision.value + 1L &&
            mutation.version == expectedRevision.value + 2L)
        else historyShapeV2(ordinal == 1 && mutation.beforeRowId == null && mutation.beforeDigest == null &&
            mutation.previousSequence == null && mutation.previousOrdinal == null && mutation.previousVersion == null &&
            mutation.version == 1L && mutation.afterRowId != null && mutation.afterDigest != null)
    }

    private class LineageScan(val row: RuntimeHistoryRowVersionRowV2) {
        var eventRows = 0
        var eventEnded = false
        var imageRows = 0
        var imageEnded = false
        var tipRows = 0
        var tipEnded = false
        var currentRows = 0
        var currentEnded = false
        var keyClosed = false
        var receiptEvent = false
        var eventHeader: RuntimeHistoryEventHeaderV2? = null
        var eventDigest: PayloadDigest? = null
        var capsule: RuntimeHistoryCapsule? = null
        var lifecycleIntent: NativeHistoryOriginalIntentV2? = null
        var capsuleEnded = !RuntimeHistoryTableV2.fromTag(row.tableTag).hasAuthenticationImages
        var throttleEvidence: RuntimeThrottleEvidenceV1? = null
        var authenticationImage: RuntimeAuthenticationImageV1? = null
        var sessionRevocationImage: RuntimeAuthenticationImageV1? = null
        var administrationCancellationImage: RuntimeAuthenticationImageV1? = null
        var deviceRevocationImage: RuntimeDeviceRevocationImageV1? = null
        var employeeDeviceRevocationImage = false
        var employeeAuthenticationImage = false
        var employeeSessionRevocationImage = false
        var employeeAdministrationCancellationImage = false
        var employeeThrottleEvidence = false
        var previousThrottle: PersistentThrottleState? = null
        var afterThrottle: PersistentThrottleState? = null
        var previousAccountDigest: PayloadDigest? = null
        var afterAccountDigest: PayloadDigest? = null
        var previousPolicyDigest: PayloadDigest? = null
        var afterPolicyDigest: PayloadDigest? = null
        var previousAdmissionDigest: PayloadDigest? = null
        var afterAdmissionDigest: PayloadDigest? = null
        fun requireVersionComplete() {
            historyShapeV2(eventRows == 1 && eventEnded && capsuleEnded)
            if (row.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) historyShapeV2(imageRows == 0)
            else historyShapeV2(imageRows == 1 && imageEnded)
        }
        fun clear() { row.key.fill(0); capsule?.clear(); capsule = null; throttleEvidence = null; authenticationImage = null; sessionRevocationImage = null;
            administrationCancellationImage = null; deviceRevocationImage = null
            previousAdmissionDigest = null; afterAdmissionDigest = null
            previousPolicyDigest = null; afterPolicyDigest = null
            eventHeader = null; eventDigest = null; lifecycleIntent = null
            previousThrottle = null; afterThrottle = null; previousAccountDigest = null; afterAccountDigest = null }
    }
    private fun startLineage(row: RuntimeHistoryRowVersionRowV2) {
        try {
            if (cold == null) business?.lineageVersion(row)
            employees.lineageSourceImage(row)
            policies.lineageSourceImage(row)
            val previous = lineage
            var previousThrottle: PersistentThrottleState? = null
            var previousAccountDigest: PayloadDigest? = null
            var previousAdmissionDigest: PayloadDigest? = null
            var previousPolicyDigest: PayloadDigest? = null
            if (previous == null) historyShapeV2(row.version == 1L)
            else {
                previous.requireVersionComplete()
                val same = previous.row.tableTag == row.tableTag && previous.row.key.contentEquals(row.key)
                if (same) {
                    // Receipt observations are immutable facts. A rewrite or
                    // tombstone cannot vanish through a current-only selector.
                    historyShapeV2(!previous.receiptEvent)
                    previousThrottle = previous.afterThrottle
                    previousAccountDigest = previous.afterAccountDigest
                    previousAdmissionDigest = previous.afterAdmissionDigest
                    previousPolicyDigest = previous.afterPolicyDigest
                    historyShapeV2(!previous.keyClosed && row.version == previous.row.version + 1 && row.previousVersion == previous.row.version &&
                        row.previousSequence == previous.row.sequence && row.previousOrdinal == previous.row.ordinal)
                    if (previous.row.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
                        historyShapeV2(row.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && row.beforeRowId == null && row.beforeDigest == null)
                    else historyShapeV2(row.mutationKind != RuntimeHistoryMutationKindV2.INSERT.tag &&
                        row.beforeRowId == previous.row.afterRowId && row.beforeDigest == previous.row.afterDigest)
                    if (row.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag) historyShapeV2(row.afterRowId == row.beforeRowId)
                } else historyShapeV2(previous.keyClosed && row.version == 1L && (row.tableTag > previous.row.tableTag ||
                    row.tableTag == previous.row.tableTag && compareBytes(previous.row.key, row.key) < 0))
                previous.clear()
            }
            lineage = LineageScan(row).also {
                it.previousThrottle = previousThrottle; it.previousAccountDigest = previousAccountDigest
                it.previousAdmissionDigest = previousAdmissionDigest
                it.previousPolicyDigest = previousPolicyDigest
            }; lineageCount = addCount(lineageCount)
        } catch (failure: Throwable) { row.key.fill(0); throw failure }
    }
    private fun consumeLineageEvent(row: RuntimeHistoryEventRowV2) {
        try {
            val active = requireNotNull(lineage)
            historyShapeV2(++active.eventRows == 1 && row.sequence == active.row.sequence)
            active.eventHeader = RuntimeHistoryCodecV2.decodeHeader(row.header).also(::requireHeaderOriginal)
            active.eventDigest = PayloadDigest(row.eventDigest)
            employees.lineageSourceEvent(active.row, row, checkNotNull(active.eventHeader))
            deviceAdmissions.lineageSourceEvent(active.row, row, checkNotNull(active.eventHeader), active.previousAdmissionDigest,
                active.previousAccountDigest)
            policies.lineageSourceEvent(active.row,row,checkNotNull(active.eventHeader),when (active.row.tableTag) {
                RuntimeHistoryTableV2.POLICY.tag -> active.previousPolicyDigest
                RuntimeHistoryTableV2.ACCOUNT.tag -> active.previousAccountDigest
                else -> null
            })
            deviceRevocationEvent?.takeIf { it.header.sequence == active.row.sequence }?.let { retained ->
                historyShapeV2(retained.header == active.eventHeader && retained.digest.value == row.eventDigest)
                if (active.row.tableTag == RuntimeHistoryTableV2.ADMISSION.tag)
                    active.deviceRevocationImage = deviceRevocationLineageImage(active, retained.evidence)
                // Account/audit receive the independently complete native-selected capsule
                // before their image below. ADMISSION receives no such native subpass; its
                // exact committed event/version and actual predecessor/plaintext are joined.
            }
            deviceAdmissions.withdrawalImage(active.row)?.let { image ->
                // The target UPDATE is counted solely by its admission join. Actual
                // account effects and the operator audit belong to their exact actor lanes.
                if (image.table == RuntimeHistoryTableV2.ACCOUNT || image.table == RuntimeHistoryTableV2.AUTH_EVENT) {
                    active.deviceRevocationImage = image
                    active.employeeDeviceRevocationImage = if (image.table == RuntimeHistoryTableV2.ACCOUNT)
                        image.recordId != coordinates.firstActorId else deviceAdmissions.employeeWithdrawalAudit(active.row)
                }
            }
            if (RuntimeHistoryTableV2.fromTag(active.row.tableTag).hasAuthenticationImages) {
                coldScanMemory?.beforeTraversalAllocation(
                    RuntimeHistoryColdScanMemory.eventBytes(checkNotNull(active.eventHeader)))
                active.capsule = RuntimeHistoryCapsule(checkNotNull(active.eventHeader), capsuleOnly = true)
            }
        } finally { row.header.fill(0) }
    }
    private fun consumeLineageImage(row: RuntimeHistoryRowViewV2) {
        val active = requireNotNull(lineage); val version = active.row
        historyShapeV2(active.eventEnded && active.capsuleEnded && ++active.imageRows == 1 && version.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag &&
            row.table.tag == version.tableTag && row.rowId == version.afterRowId && row.digest().value == version.afterDigest)
        val key = row.key()
        try { historyShapeV2(key.contentEquals(version.key)) } finally { key.fill(0) }
        if (row.table == RuntimeHistoryTableV2.AGGREGATE &&
            row.text("kind") == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND) {
            historyShapeV2(version.version == 1L && version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                version.previousSequence == null && version.previousOrdinal == null && version.previousVersion == null &&
                version.beforeRowId == null && version.beforeDigest == null &&
                row.number("revision") == 1L && row.text("schema") == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_SCHEMA)
            active.receiptEvent = true
        }
        if (cold == null) business?.lineageImage(version, row)
        // Native IMAGE is one explicit-length object, not a SELECT stream with a group end.
        if (version.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag) active.imageEnded = true
        if (version.sequence == 1L) {
            historyShapeV2(version.version == 1L && version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag)
            if (row.table == RuntimeHistoryTableV2.BRANCH) {
                historyShapeV2(c1Branch == null); c1Branch = row.branch()
            } else historyShapeV2(row.table in ORIGINAL_PROTECTED_TABLES)
        }
        if (row.table == RuntimeHistoryTableV2.BRANCH) {
            val branch = row.branch(); val header = requireNotNull(active.eventHeader)
            historyShapeV2(branch.epoch == header.commandEpoch && branch.sequence == header.commandSequence &&
                branch.journalHash == header.commandDigest.value && RuntimeHistoryCodecV2.roleCommitment(branch) == header.roleCommitment &&
                RuntimeHistoryCodecV2.recoveryCommitment(branch) == header.recoveryCommitment)
        }
    }
    private fun consumeThrottleImage(state: PersistentThrottleState) {
        val active = requireNotNull(lineage)
        historyShapeV2(active.row.tableTag == RuntimeHistoryTableV2.THROTTLE.tag && active.afterThrottle == null && active.capsuleEnded)
        active.afterThrottle = state
        active.throttleEvidence?.let { evidence ->
            historyShapeV2(active.row.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag &&
                active.previousThrottle == evidence.before(state.key) && state == evidence.after(state.key) &&
                state != active.previousThrottle && evidence.changed().any { it.key == state.key })
            if (active.authenticationImage == null) {
                if (active.employeeThrottleEvidence) employeeThrottleImages = addCount(employeeThrottleImages)
                else originalThrottleImages = addCount(originalThrottleImages)
            }
        }
    }
    private fun consumeLineageTip(tip: RuntimeHistoryRowTipRowV2) {
        try {
            val active = requireNotNull(lineage); val row = active.row
            active.requireVersionComplete()
            historyShapeV2(++active.tipRows == 1 && tip.tableTag == row.tableTag && tip.key.contentEquals(row.key) &&
                tip.version == row.version && tip.sequence == row.sequence && tip.ordinal == row.ordinal &&
                tip.targetRowId == row.afterRowId && tip.rowDigest == row.afterDigest &&
                (tip.tombstone == 1) == (row.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag))
            serialPublications.tip(row)
            tipCount = addCount(tipCount)
        } finally { tip.key.fill(0) }
    }
    private fun consumeLineageCurrent(row: RuntimeHistoryRowViewV2) {
        val active = requireNotNull(lineage); val tip = active.row
        historyShapeV2(active.tipRows == 1 && ++active.currentRows == 1 && row.table.tag == tip.tableTag &&
            row.rowId == tip.afterRowId && row.digest().value == tip.afterDigest)
        val key = row.key()
        try { historyShapeV2(key.contentEquals(tip.key)) } finally { key.fill(0) }
        liveTips[row.table.ordinal] = addCount(liveTips[row.table.ordinal])
    }

    private fun validateRow(row: RuntimeHistoryRowViewV2) {
        if (row.table.application) historyShapeV2(row.text("scope") == scopeKey)
        when (row.table) {
            RuntimeHistoryTableV2.BRANCH -> {
                val branch = row.branch()
                historyShapeV2(branch.assignmentId == coordinates.assignmentId.value && branch.deviceId == coordinates.deviceId.value)
                val recovery = Json.decodeFromString(RecoveryAdmissionState.serializer(), branch.recoveryJson)
                historyShapeV2(recovery.branchId == scope.branchId && Json.encodeToString(RecoveryAdmissionState.serializer(), recovery) == branch.recoveryJson)
                // Constructor checks all bounded identities. Complete source/evidence and
                // resource adjustment provenance is a separate, non-waivable join below.
                val adjustments = recovery.reconciliations.mapNotNull { it.adjustment }
                historyShapeV2(adjustments.map { it.adjustmentId }.distinct().size == adjustments.size)
                for (r in recovery.reconciliations) {
                    val adjustment = r.adjustment
                    historyShapeV2(r.kind.resourceKind == r.resource.kind && (adjustment == null || adjustment.resource == r.resource) &&
                        r.acknowledgedObligationIds.all { id -> recovery.obligations.any { it.obligationId == id } })
                }
                for (r in recovery.readmissions) historyShapeV2(r.acknowledgedObligationIds.all { id -> recovery.obligations.any { it.obligationId == id } })
                if (recovery.incidentId != null || recovery.obligations.isNotEmpty() || recovery.revision.value != 0L)
                    holds += RuntimeHistoryReadHold.RECOVERY_ORIGIN_AND_RESOURCE_CROSSWALKS_UNJOINED
            }
            RuntimeHistoryTableV2.OPERATION -> {
                CommandKey(row.text("command"), row.int("commandVersion")); requireProtocolName(row.text("outcome"))
                UtcInstant.parseCanonical(row.text("committedAt")).also(::requireEvidenceV1Time)
                val bytes = row.bytes("result")
                try { historyShapeV2(sha256(bytes).value == row.text("resultHash")) } finally { bytes.fill(0) }
            }
            RuntimeHistoryTableV2.AGGREGATE -> {
                AggregateKey(row.text("kind"), DomainId(row.text("id"))); requireProtocolName(row.text("schema"))
                val bytes = row.bytes("payload")
                try { historyShapeV2(sha256(bytes).value == row.text("payloadHash")) } finally { bytes.fill(0) }
                if (row.text("kind") in StockRelationalKindsV2.all)
                    holds += RuntimeHistoryReadHold.STOCK_CANONICAL_CODEC_AND_ORIGINAL_PRODUCER_UNJOINED
                if (row.text("kind") in RuntimeHistoricalFoodCurrentState.KINDS)
                    holds += RuntimeHistoryReadHold.FOOD_CURRENT_TYPED_CENSUS_WRITER_CLOSURE_UNJOINED
            }
            RuntimeHistoryTableV2.CLAIM -> {
                val claim = row.claim(); ClaimKey(claim.namespace, claim.key)
                if (claim.namespace in RuntimeHistoricalFoodCurrentState.CLAIM_NAMESPACES)
                    holds += RuntimeHistoryReadHold.FOOD_CURRENT_TYPED_CENSUS_WRITER_CLOSURE_UNJOINED
            }
            RuntimeHistoryTableV2.JOURNAL -> {
                val bytes = row.bytes("envelope")
                try { historyShapeV2(sha256(bytes).value == row.text("hash")) } finally { bytes.fill(0) }
            }
            RuntimeHistoryTableV2.AUDIT -> {
                UtcInstant.parseCanonical(row.text("recordedAt")).also(::requireEvidenceV1Time)
                val kind = row.text("kind")
                historyShapeV2(kind == "result.supervisor-read" || kind == "native-original.retained" || kind.startsWith("command."))
                if (kind.startsWith("command.")) requireProtocolName(kind.removePrefix("command."))
            }
            RuntimeHistoryTableV2.OUTBOX -> {
                requireProtocolName(row.text("kind")); val bytes = row.bytes("body")
                try { historyShapeV2(sha256(bytes).value == row.text("bodyHash")) } finally { bytes.fill(0) }
            }
            RuntimeHistoryTableV2.JOURNAL_FRAGMENT -> {
                val bytes = row.bytes("payload")
                try { historyShapeV2(bytes.size.toLong() == row.number("byteCount") && sha256(bytes).value == row.text("payloadHash")) }
                finally { bytes.fill(0) }
            }
            RuntimeHistoryTableV2.HEAD -> row.head().also {
                historyShapeV2(it.scope == scopeKey && it.storageId == coordinates.storageInstanceId.value &&
                    it.bindingDigest == allocation.originalNativeBindingDigest.value && it.installationGenesisDigest == originalGenesis.value)
            }
            RuntimeHistoryTableV2.EVENT -> { val value = row.event()
                try { requireHeaderOriginal(RuntimeHistoryCodecV2.decodeHeader(value.header)) } finally { value.header.fill(0) } }
            RuntimeHistoryTableV2.ROW_VERSION -> { val value = row.version(); value.key.fill(0) }
            RuntimeHistoryTableV2.ROW_TIP -> { val value = row.tip(); value.key.fill(0) }
            RuntimeHistoryTableV2.CHUNK -> { val value = row.chunk()
                try { historyShapeV2(RuntimeHistoryCodecV2.chunkDigest(value.sequence, value.ordinal, value.bytes).value == value.digest) }
                finally { value.bytes.fill(0) } }
            RuntimeHistoryTableV2.WRITE_FRAME, RuntimeHistoryTableV2.CAPTURE -> historyShapeV2(false)
            else -> {
                if (row.table in STOCK_TABLES) {
                    validateStockSelectors(row)
                    holds += RuntimeHistoryReadHold.STOCK_CANONICAL_CODEC_AND_ORIGINAL_PRODUCER_UNJOINED
                } else historyShapeV2(row.table.protectedTable != null)
            }
        }
    }

    /**
     * All scalar selectors of EVERY physical index row are decoded before any filtered
     * crosswalk, including invalid BLOB keys/empty-vs-NULL distinctions. This is a structural
     * projection check, not the missing authoritative payload/producer reconstruction.
     */
    private fun validateStockSelectors(row: RuntimeHistoryRowViewV2) { row.runtimeStockIndex() }

    private data class ExpectedPlaintext(val pass: NativeHistoryReadPassV2, val table: RuntimeHistoryTableV2, val rowId: Long,
        val binding: ProtectedLocalBinding, val loginKey: String?)
    private fun expectedPlaintext(row: RuntimeHistoryRowViewV2, pass: NativeHistoryReadPassV2): ExpectedPlaintext {
        historyShapeV2(row.text("scope") == scopeKey && row.text("keyId") == coordinates.storageKeyId.value)
        fun id(name: String): DomainId = DomainId(row.text(name))
        val key = when (row.table) {
            RuntimeHistoryTableV2.ACCOUNT -> ProtectedLocalRecordKey.Account(scope, id("actorId"))
            RuntimeHistoryTableV2.POLICY -> ProtectedLocalRecordKey.EffectivePolicy(scope, id("actorId"))
            RuntimeHistoryTableV2.ADMISSION -> ProtectedLocalRecordKey.Admission(scope, id("deviceId"))
            RuntimeHistoryTableV2.THROTTLE -> {
                val dimension = row.text("dimensionKey")
                val selector = if (dimension == "account") ThrottleDimension.Account else {
                    historyShapeV2(dimension.startsWith("device:") && dimension.length == 43)
                    ThrottleDimension.Device(DomainId(dimension.substring(7)))
                }
                ProtectedLocalRecordKey.Throttle(ThrottleKey(scope, id("actorId"), selector))
            }
            RuntimeHistoryTableV2.ACCESS -> ProtectedLocalRecordKey.SessionIssuance(scope, id("sessionId"))
            RuntimeHistoryTableV2.CONFIGURATION -> ProtectedLocalRecordKey.AuthConfiguration(scope, id("actorId"))
            RuntimeHistoryTableV2.REFRESH -> ProtectedLocalRecordKey.RefreshIssuance(scope, id("sessionId"))
            RuntimeHistoryTableV2.AUTH_EVENT -> ProtectedLocalRecordKey.AuthEvent(scope, id("eventId"))
            RuntimeHistoryTableV2.ARCHIVE -> ProtectedLocalRecordKey.SessionArchive(scope, id("sessionId"))
            RuntimeHistoryTableV2.APPROVAL -> ProtectedLocalRecordKey.ActionApproval(scope, id("approvalId"))
            else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        return ExpectedPlaintext(pass, row.table, row.rowId, ProtectedLocalBinding(key, Revision(row.number("revision"))),
            if (row.table == RuntimeHistoryTableV2.CONFIGURATION) row.text("loginKey") else null)
    }
    private fun decodeProtected(expected: ExpectedPlaintext, bytes: ByteArray): Any {
        val binding = expected.binding
        return when (expected.table) {
            RuntimeHistoryTableV2.ACCOUNT -> ProtectedLocalSecurityCodec.decodeAccount(binding, bytes).also { account ->
                historyShapeV2(account.state.scope == scope)
                account.credential?.let { historyShapeV2(it.pepperReference.keyId == coordinates.pepperKeyId) }
            }
            RuntimeHistoryTableV2.POLICY -> LocalEffectivePolicyCodec.decodePolicy(binding, bytes)
            RuntimeHistoryTableV2.ADMISSION -> ProtectedLocalSecurityCodec.decodeAdmission(binding, bytes)
            RuntimeHistoryTableV2.THROTTLE -> ProtectedLocalSecurityCodec.decodeThrottle(binding, bytes)
            RuntimeHistoryTableV2.ACCESS -> ProtectedLocalSecurityCodec.decodeIssuance(binding, bytes)
            RuntimeHistoryTableV2.CONFIGURATION -> LocalAuthenticationCodec.decodeConfiguration(binding, bytes).also {
                historyShapeV2(it.loginKey == expected.loginKey)
            }
            RuntimeHistoryTableV2.REFRESH -> LocalAuthenticationCodec.decodeRefresh(binding, bytes)
            RuntimeHistoryTableV2.AUTH_EVENT -> {
                if (binding.key == ProtectedLocalRecordKey.AuthEvent(scope, coordinates.installationId)) {
                    val receipt = LocalBranchInstallationCodecV2.decodeEvent(binding, bytes)
                    if (observedFirstChild == null) {
                        historyShapeV2(pendingOriginalRecovery && expected.pass == NativeHistoryReadPassV2.CENSUS &&
                            binding.revision == Revision(0) && receipt.genesis == originalGenesis)
                        val r = receipt.core
                        observedFirstChild = LocalInstallationCompletedChildFactsV2(r.allocation.digest,
                            r.definitionDigest, r.originalRequestId, r.issuedAttemptId, r.attemptSequence,
                            receipt.genesis, receipt.digest, requireNotNull(native.pending).child.historyDigest, r.authorityEpoch)
                    }
                    historyShapeV2(receipt.genesis == firstChild.installationGenesisDigest && receipt.digest == firstChild.receiptDigest)
                    native.receipt?.let { historyShapeV2(it.digest == receipt.digest) }
                    receipt
                } else LocalAuthenticationCodec.decodeEvent(binding, bytes).also { historyShapeV2(it.kind != LocalAuthEventKind.BRANCH_INSTALLED) }
            }
            RuntimeHistoryTableV2.ARCHIVE -> LocalAuthenticationCodec.decodeArchive(binding, bytes)
            RuntimeHistoryTableV2.APPROVAL -> LocalActionApprovalCodec.decode(binding, bytes).also { value ->
                val record = value.record
                historyShapeV2(record.requirement.scope == scope && value.confirmationDigest ==
                    LocalActionApprovalCodec.confirmationDigest(record.requestorId, record.requestorPolicyRevision, record.requirement))
                if (record.revision.value == 1L) historyShapeV2(value.hasApproverIncarnation)
            }
            else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        }
    }
    private fun recordOriginalProtectedSeed(expected: ExpectedPlaintext, bytes: ByteArray, value: Any) {
        historyShapeV2(expected.binding.revision == Revision(0))
        val name = when (value) {
            is ProtectedLocalAccount -> {
                val state = value.state
                historyShapeV2(state.scope == scope && state.actorId == coordinates.firstActorId && state.enabled &&
                    state.revision == Revision(0) && state.generation.value == 0L && state.sessions.isEmpty() &&
                    value.credentialRevision == Revision(0) && value.credential != null)
                "account"
            }
            is ActivatedLocalPolicy -> { historyShapeV2(value.actorId == coordinates.firstActorId); "policy" }
            is LocalAuthConfiguration -> { historyShapeV2(value.actorId == coordinates.firstActorId); "configuration" }
            is LocalDeviceAdmission -> { historyShapeV2(value.deviceId == coordinates.deviceId && value.admitted); "admission" }
            is PersistentThrottleState -> {
                historyShapeV2(value.key.scope == scope && value.key.actorId == coordinates.firstActorId && value.failures == 0 &&
                    value.remainingDelayMillis == 0L && value.activeAttempt == null)
                when (val dimension = value.key.dimension) {
                    ThrottleDimension.Account -> "accountThrottle"
                    is ThrottleDimension.Device -> { historyShapeV2(dimension.deviceId == coordinates.deviceId); "deviceThrottle" }
                }
            }
            is LocalBranchInstallationReceiptV2 -> "receipt"
            else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        historyShapeV2(originalSeedDigests.put(name, sha256(bytes)) == null)
    }
    private fun requireOriginalSeeds(receipt: LocalBranchInstallationReceiptV2) {
        val seed = receipt.core.seeds
        val expected = mapOf("account" to seed.account, "policy" to seed.policy, "configuration" to seed.configuration,
            "admission" to seed.admission, "accountThrottle" to seed.accountThrottle, "deviceThrottle" to seed.deviceThrottle)
        historyShapeV2(originalSeedDigests.keys == expected.keys + "receipt")
        for ((key, digest) in expected) historyShapeV2(originalSeedDigests[key] == digest)
        val encoded = LocalBranchInstallationCodecV2.encodeEvent(receipt)
        try { historyShapeV2(originalSeedDigests["receipt"] == sha256(encoded) && encoded.contentEquals(requireNotNull(originalReceiptBytes))) }
        finally { encoded.fill(0) }
        val branch = requireNotNull(c1Branch)
        val recovery = RoomBranchInstallationStorage.recoveryJson(scope)
        historyShapeV2(branch.scope == scopeKey && branch.assignmentId == coordinates.assignmentId.value &&
            branch.deviceId == coordinates.deviceId.value && branch.epoch == definition.definition.authorityEpoch.value && branch.revision == 0L &&
            branch.sequence == 0L && branch.journalHash == receipt.genesis.value && branch.writable && branch.recoveryJson == recovery)
        val raw = recovery.encodeToByteArray(throwOnInvalidSequence = true)
        try { historyShapeV2(sha256(raw) == seed.recoveryState) } finally { raw.fill(0) }
        historyShapeV2(firstEventDigest == firstChild.historyDigest)
    }

    private fun startCommand(row: JournalRow) {
        try {
            historyShapeV2(command == null && row.scope == scopeKey && row.assignmentId == coordinates.assignmentId.value)
            val previousEpoch = previousCommandEpoch
            if (previousEpoch == null && row.epoch == definition.definition.authorityEpoch.value)
                historyShapeV2(row.sequence == 1L && row.previousHash == firstChild.installationGenesisDigest.value)
            else if (previousEpoch == row.epoch)
                historyShapeV2(row.sequence == previousCommandSequence + 1 && row.previousHash == previousCommandHash)
            else {
                historyShapeV2(row.sequence == 1L && row.epoch > (previousEpoch ?: definition.definition.authorityEpoch.value))
                holds += RuntimeHistoryReadHold.RECOVERY_ORIGIN_AND_RESOURCE_CROSSWALKS_UNJOINED
            }
            // The first fragment may contain only a four-byte frame length. The
            // decoder allocates that announced frame before further chunks arrive.
            coldScanMemory?.beforeTraversalAllocation(16L * STOCK_FRAME_BYTES_V2 + 32L * 1024)
            command = CommandScan(row)
        } catch (failure: Throwable) { row.envelope.fill(0); throw failure }
    }
    private data class JobIdentity(val kind: String, val digest: PayloadDigest, val bytes: Int)
    private inner class CommandScan(val journal: JournalRow) {
        private var operation: OperationRow? = null
        private var legacy: RetainedCommandEvidence? = null
        private val root: JournalRootV2? = if (RetainedCommandFramesV2.isRoot(journal.envelope)) RetainedCommandFramesV2.decodeRoot(journal.envelope) else null
        private var operationRows = 0
        private var commandAudits = 0
        private var outboxRows = 0
        private var fragmentRows = 0L
        private var previousFragmentBytes: Int? = null
        private val jobs = mutableListOf<JobIdentity>() // Existing bounded <=32 jobs, not the write stream.
        private val fragmentChain = StockFragmentChainV2()
        private val frameOrder = StockFrameOrderV2()
        private var normalized: StoredBytes? = null
        private var result: StoredBytes? = null
        private var parsedJobs = 0
        private val parser = StockFrameStreamDecoderV2 { bytes ->
            try {
                val frame = RetainedCommandFramesV2.decode(bytes)
                val canonical = RetainedCommandFramesV2.encode(frame)
                try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
                frameOrder.append(frame, bytes.size)
                when (frame) {
                    is RetainedCommandFrameV2.Normalized -> { historyShapeV2(normalized == null); normalized = frame.body }
                    is RetainedCommandFrameV2.Result -> {
                        historyShapeV2(result == null); result = frame.body
                        val raw = frame.body.copyBytes()
                        try { historyShapeV2(sha256(raw) == requireNotNull(root).identity.resultHash) } finally { raw.fill(0) }
                    }
                    is RetainedCommandFrameV2.Job -> {
                        historyShapeV2(frame.ordinal == parsedJobs.toLong() + 1 && parsedJobs < jobs.size)
                        val expected = jobs[parsedJobs++]; val raw = frame.value.body.copyBytes()
                        try { historyShapeV2(frame.value.kind == expected.kind && raw.size == expected.bytes && sha256(raw) == expected.digest) }
                        finally { raw.fill(0) }
                    }
                    is RetainedCommandFrameV2.Write -> {
                        val raw = frame.value.payload.copyBytes()
                        try { historyShapeV2(sha256(raw) == frame.value.payloadDigest) } finally { raw.fill(0) }
                    }
                    is RetainedCommandFrameV2.Claim, is RetainedCommandFrameV2.Source,
                    is RetainedCommandFrameV2.Effect, is RetainedCommandFrameV2.Resource -> Unit
                }
                // Typed framing alone is not a native mutation/producer crosswalk. The
                // separately core-owned join must close EVERY source, mutation and actual
                // installed business producer before any ordinary hold can be removed.
            } finally { bytes.fill(0) }
        }
        var operationsEnded = false
        var auditsEnded = false
        var outboxEnded = false

        fun operation(row: OperationRow) {
            try {
                historyShapeV2(++operationRows == 1 && operation == null && row.scope == scopeKey && row.operationId == journal.operationId)
                operation = row
                if (root == null) legacy = requireNotNull(decodeRetainedCommand(scope, OperationId(row.operationId), row, journal))
                else {
                    val i = root.identity; val producer = i.producer
                    historyShapeV2(i.scope == scope && row.operationId == producer.operationId.value && row.actorId == producer.originalActorId.value &&
                        row.command == producer.command.name && row.commandVersion == producer.command.version && row.digest == producer.semanticDigest.value &&
                        row.outcome == "committed" && row.resultHash == i.resultHash.value && sha256(row.result) == i.resultHash &&
                        row.sequence == i.sequence.value && journal.sequence == row.sequence && row.epoch == i.authorityEpoch.value && journal.epoch == row.epoch &&
                        row.committedAt == i.recordedAt.toString() && row.authenticationId == i.authenticationId.value && row.policyRevision == i.policyRevision.value &&
                        journal.assignmentId == i.assignmentId.value && journal.previousHash == i.previousJournalHash.value)
                }
            } catch (failure: Throwable) { row.result.fill(0); throw failure }
        }
        fun audit(row: AuditRow) {
            historyShapeV2(operationsEnded && !auditsEnded)
            val op = requireNotNull(operation)
            historyShapeV2(row.scope == scopeKey && row.operationId == op.operationId && row.originalActorId == op.actorId)
            val originalKind = if (legacy?.nativeOriginalProvenance == null) "command.${op.outcome}" else "native-original.retained"
            if (row.kind == originalKind) {
                historyShapeV2(++commandAudits == 1 && row.actorId == op.actorId && row.policyRevision == op.policyRevision &&
                    row.recordedAt == op.committedAt && row.digest == op.digest)
            } else {
                historyShapeV2(row.kind == "result.supervisor-read")
                val raw = framedText(listOf(op.operationId, op.actorId))
                try { historyShapeV2(row.digest == sha256(raw).value) } finally { raw.fill(0) }
                historyShapeV2(UtcInstant.parseCanonical(row.recordedAt) >= UtcInstant.parseCanonical(op.committedAt))
            }
        }
        fun outbox(row: OutboxRow) {
            historyShapeV2(auditsEnded && !outboxEnded && row.scope == scopeKey && row.operationId == journal.operationId && row.ordinal == outboxRows)
            if (outboxRows == 0) {
                val fields = listOf("ukkera.journal-upload/v1", scope.branchId.value, journal.epoch.toString(), journal.sequence.toString(), journal.hash)
                val raw = framedText(fields)
                try { historyShapeV2(row.kind == SYNC_JOB && row.body.contentEquals(raw) && sha256(raw).value == row.bodyHash) }
                finally { raw.fill(0) }
            } else {
                historyShapeV2(row.kind != SYNC_JOB && jobs.size < MAX_JOBS)
                jobs += JobIdentity(row.kind, PayloadDigest(row.bodyHash), row.body.size)
                legacy?.let { old ->
                    historyShapeV2(outboxRows <= old.jobs.size)
                    val expected = old.jobs[outboxRows - 1]
                    val raw = expected.body.copyBytes()
                    try { historyShapeV2(expected.kind == row.kind && raw.contentEquals(row.body)) } finally { raw.fill(0) }
                }
            }
            outboxRows++
        }
        suspend fun fragment(row: RuntimeHistoryRowViewV2) {
            val actualRoot = requireNotNull(root)
            historyShapeV2(outboxEnded && row.text("scope") == scopeKey && row.text("operationId") == journal.operationId &&
                row.number("ordinal") == fragmentRows && fragmentRows < actualRoot.fragmentCount &&
                (previousFragmentBytes == null || previousFragmentBytes == STOCK_JOURNAL_FRAGMENT_BYTES_V2))
            val raw = row.bytes("payload")
            try {
                historyShapeV2(raw.size.toLong() == row.number("byteCount") && sha256(raw).value == row.text("payloadHash"))
                fragmentChain.append(fragmentRows, raw); parser.append(raw)
                fragmentRows = addCount(fragmentRows); previousFragmentBytes = raw.size
            } finally { raw.fill(0) }
        }
        fun endOperations() { historyShapeV2(!operationsEnded && operationRows == 1); operationsEnded = true }
        fun endAudits() { historyShapeV2(operationsEnded && !auditsEnded && commandAudits == 1); auditsEnded = true }
        fun endOutbox() { historyShapeV2(auditsEnded && !outboxEnded && outboxRows > 0); outboxEnded = true }
        suspend fun finish() {
            historyShapeV2(operationsEnded && auditsEnded && outboxEnded && commandAudits == 1 && outboxRows > 0)
            val op = requireNotNull(operation)
            if (root == null) historyShapeV2(fragmentRows == 0L && outboxRows == requireNotNull(legacy).jobs.size + 1)
            else {
                parser.finish(); frameOrder.finish(root.counts, root.sectionBytes)
                val actual = fragmentChain.finish()
                historyShapeV2(fragmentRows == root.fragmentCount && actual.count == root.fragmentCount && actual.bytes == root.streamBytes &&
                    actual.commitment == root.commitment && parsedJobs == jobs.size && root.counts[4] == jobs.size.toLong())
                val raw = requireNotNull(result).copyBytes()
                try { historyShapeV2(raw.contentEquals(op.result)) } finally { raw.fill(0) }
                historyShapeV2(semanticDigest(scope, root.identity.producer.originalActorId, root.identity.producer.command,
                    requireNotNull(normalized)) == root.identity.producer.semanticDigest)
            }
            val joinedBusiness = business
            if (cold == null && joinedBusiness?.enabled == true && !joinedBusiness.join(journal, op)) holdUnjoinedAuthorization()
            previousCommandEpoch = journal.epoch; previousCommandSequence = journal.sequence; previousCommandHash = journal.hash
            commandCount = addCount(commandCount)
        }
        fun clear() {
            journal.envelope.fill(0); operation?.result?.fill(0); operation = null
            // The original fixed decoder owns its unfinished frame; its explicit disposal
            // is part of the coordinated stock source, not a second decoder implementation.
            parser.clear()
            normalized = null; result = null; legacy = null; jobs.clear()
        }
    }

    private data class AggregateIdentity(val scope: String, val kind: String, val id: String, val revision: Long, val hash: String)
    private data class IndexIdentity(val table: RuntimeHistoryTableV2, val scope: String, val kind: String, val id: String,
        val revision: Long, val hash: String, val producerOperationId: String)
    private fun aggregateIdentity(row: RuntimeHistoryRowViewV2): AggregateIdentity =
        AggregateIdentity(row.text("scope"), row.text("kind"), row.text("id"), row.number("revision"), row.text("payloadHash"))
    private fun indexIdentity(row: RuntimeHistoryRowViewV2): IndexIdentity = IndexIdentity(row.table, row.text("scope"), row.text("recordKind"),
        row.text("recordId"), row.number("aggregateGeneration"), row.text("payloadHash"), row.text("producerOperationId"))
    private fun matches(index: IndexIdentity, aggregate: AggregateIdentity) {
        historyShapeV2(index.table == projectionTable(aggregate.kind) && index.scope == aggregate.scope && index.kind == aggregate.kind &&
            index.id == aggregate.id && index.revision == aggregate.revision && index.hash == aggregate.hash)
    }
    private fun consumeStock(row: RuntimeHistoryRowViewV2, pass: NativeHistoryReadPassV2) {
        when (pass) {
            NativeHistoryReadPassV2.STOCK_AGGREGATES -> {
                finishStockAggregate()
                stockAggregate = aggregateIdentity(row); stockProjectionCount = 0; stockProjectionEnded = false
                stockAggregateCount = addCount(stockAggregateCount)
            }
            NativeHistoryReadPassV2.STOCK_PROJECTION -> {
                historyShapeV2(++stockProjectionCount == 1); matches(indexIdentity(row), requireNotNull(stockAggregate))
            }
            NativeHistoryReadPassV2.STOCK_REVERSE -> {
                historyShapeV2(stockIndex == null)
                stockIndex = indexIdentity(row); stockReverseAggregateCount = 0; stockReverseCount = addCount(stockReverseCount)
            }
            NativeHistoryReadPassV2.STOCK_REVERSE_AGGREGATE -> {
                historyShapeV2(++stockReverseAggregateCount == 1); matches(requireNotNull(stockIndex), aggregateIdentity(row))
            }
            else -> error("unreachable")
        }
    }
    private fun finishStockAggregate() {
        stockAggregate?.let {
            if (projectionTable(it.kind) == null) historyShapeV2(stockProjectionCount == 0 && !stockProjectionEnded)
            else historyShapeV2(stockProjectionCount == 1 && stockProjectionEnded)
        }
        stockAggregate = null
    }
    private fun projectionTable(kind: String): RuntimeHistoryTableV2? = runtimeStockProjectionTable(kind)


    /** Exact current account siblings; their present grants never re-authorize past events. */
    private inner class AccountRelations(account: ProtectedLocalAccount) {
        private val state = account.state
        private var stage = 0
        private var policies = 0
        private var configurations = 0
        private var accountThrottles = 0
        private var previousDimension: String? = null
        fun policy(value: ActivatedLocalPolicy) {
            historyShapeV2(stage == 0 && ++policies == 1 && value.scope == state.scope && value.actorId == state.actorId)
            policyRelationCount = addCount(policyRelationCount)
        }
        fun configuration(value: LocalAuthConfiguration) {
            historyShapeV2(stage == 1 && ++configurations == 1 && value.scope == state.scope && value.actorId == state.actorId)
            configurationRelationCount = addCount(configurationRelationCount)
        }
        fun throttle(value: PersistentThrottleState) {
            historyShapeV2(stage == 2 && value.key.scope == state.scope && value.key.actorId == state.actorId)
            val dimension = when (val d = value.key.dimension) {
                ThrottleDimension.Account -> { historyShapeV2(++accountThrottles == 1); "account" }
                is ThrottleDimension.Device -> "device:${d.deviceId.value}"
            }
            previousDimension?.let { historyShapeV2(it < dimension) }; previousDimension = dimension
            throttleRelationCount = addCount(throttleRelationCount)
        }
        fun end(pass: NativeHistoryReadPassV2) {
            when (pass) {
                NativeHistoryReadPassV2.ACCOUNT_POLICY -> historyShapeV2(stage == 0 && policies == 1)
                NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION -> historyShapeV2(stage == 1 && configurations == 1)
                NativeHistoryReadPassV2.ACCOUNT_THROTTLES -> historyShapeV2(stage == 2 && accountThrottles == 1)
                else -> historyShapeV2(false)
            }
            stage++
        }
        fun finish() { historyShapeV2(stage == 3) }
    }

    /** Fixed exhaustive candidates are native-owned; only exact decoded identities count. */
    private inner class AccessRelations(private val access: LocalAccessIssuance) {
        private var stage = 0
        private var refresh: LocalRefreshIssuance? = null
        private var archive: LocalSessionArchive? = null
        private var refreshes = 0
        private var archives = 0
        private var accountCandidates = 0L
        private var admissionCandidates = 0L
        private var configurationCandidates = 0L
        private var accounts = 0
        private var admissions = 0
        private var configurations = 0
        private var currentSessions = 0
        fun refresh(value: LocalRefreshIssuance) {
            historyShapeV2(stage == 0 && ++refreshes == 1)
            historyShapeV2(value.scope == access.scope && value.actorId == access.actorId && value.sessionId == access.sessionId &&
                value.deviceId == access.deviceId && value.generation == access.generation &&
                value.credentialRevisionAtIssue == access.credentialRevisionAtIssue && value.admissionRevisionAtIssue == access.admissionRevisionAtIssue &&
                value.issuerContextId == access.issuerContextId && value.issuedAt == access.issuedAt &&
                access.expiresAtElapsedMillis <= value.expiresAtElapsedMillis)
            if (value.originalSessionId == value.sessionId) historyShapeV2(value.workStartedAt == value.issuedAt)
            refresh = value; refreshRelationCount = addCount(refreshRelationCount)
        }
        fun archive(value: LocalSessionArchive) {
            historyShapeV2(stage == 1 && ++archives == 1)
            requireSameSession(access, value.session)
            historyShapeV2(value.session.status == SessionStatus.REVOKED)
            if (value.archivedAt.bootId == access.issuedAt.bootId)
                historyShapeV2(value.archivedAt.elapsedMillis >= access.issuedAt.elapsedMillis)
            archive = value
        }
        fun account(value: ProtectedLocalAccount) {
            val state = value.state
            historyShapeV2(stage == 2 && state.scope == scope)
            accountCandidates = addCount(accountCandidates)
            if (state.actorId != access.actorId) return
            historyShapeV2(++accounts == 1 && state.generation.value >= access.generation.value &&
                value.credentialRevision.value >= access.credentialRevisionAtIssue.value)
            state.sessions.singleOrNull { it.sessionId == access.sessionId }?.let { session ->
                requireSameSession(access, session); historyShapeV2(++currentSessions == 1)
            }
            // Disabled/revoked/expired historical issuances remain evidence. No current-time
            // eligibility predicate, credential verifier or permissive login check is invoked.
        }
        fun admission(value: LocalDeviceAdmission) {
            historyShapeV2(stage == 3 && value.scope == scope)
            admissionCandidates = addCount(admissionCandidates)
            if (value.deviceId != access.deviceId) return
            historyShapeV2(++admissions == 1 && value.revision.value >= access.admissionRevisionAtIssue.value)
        }
        fun configuration(value: LocalAuthConfiguration) {
            historyShapeV2(stage == 4 && value.scope == scope)
            configurationCandidates = addCount(configurationCandidates)
            if (value.actorId != access.actorId) return
            historyShapeV2(++configurations == 1 && value.revision.value >= requireNotNull(refresh).configurationRevisionAtIssue.value)
        }
        fun end(pass: NativeHistoryReadPassV2) {
            when (pass) {
                NativeHistoryReadPassV2.ACCESS_REFRESH -> historyShapeV2(stage == 0 && refreshes == 1)
                NativeHistoryReadPassV2.ACCESS_ARCHIVE -> historyShapeV2(stage == 1 && archives in 0..1)
                NativeHistoryReadPassV2.ACCESS_ACCOUNTS -> historyShapeV2(stage == 2 && accounts == 1 && accountCandidates == count(RuntimeHistoryTableV2.ACCOUNT))
                NativeHistoryReadPassV2.ACCESS_ADMISSIONS -> historyShapeV2(stage == 3 && admissions == 1 && admissionCandidates == count(RuntimeHistoryTableV2.ADMISSION))
                NativeHistoryReadPassV2.ACCESS_CONFIGURATIONS -> historyShapeV2(stage == 4 && configurations == 1 && configurationCandidates == count(RuntimeHistoryTableV2.CONFIGURATION))
                else -> historyShapeV2(false)
            }
            stage++
        }
        fun finish() {
            historyShapeV2(stage == 5 && currentSessions + archives == 1 && (archive != null) == (archives == 1))
            if (currentSessions == 1) matchedCurrentSessionCount = addCount(matchedCurrentSessionCount)
            if (archives == 1) matchedAccessArchiveCount = addCount(matchedAccessArchiveCount)
        }
    }
    private fun requireSameSession(access: LocalAccessIssuance, session: LocalSessionRecord) {
        historyShapeV2(session.scope == access.scope && session.actorId == access.actorId && session.sessionId == access.sessionId &&
            session.deviceId == access.deviceId && session.generation == access.generation &&
            session.authenticationPurpose == access.authenticationPurpose)
    }
    private inner class ArchiveRelations(private val archive: LocalSessionArchive) {
        private var accesses = 0
        private var ended = false
        fun access(value: LocalAccessIssuance) {
            historyShapeV2(!ended && ++accesses == 1)
            requireSameSession(value, archive.session)
            if (archive.archivedAt.bootId == value.issuedAt.bootId)
                historyShapeV2(archive.archivedAt.elapsedMillis >= value.issuedAt.elapsedMillis)
        }
        fun end() { historyShapeV2(!ended && accesses == 1); ended = true }
        fun finish() { historyShapeV2(ended && accesses == 1) }
    }
    private inner class ApprovalRelations(private val approval: ProtectedLocalActionApproval) {
        private var candidates = 0L
        private var matched = 0
        private var ended = false
        fun operation(row: OperationRow) {
            historyShapeV2(!ended && row.scope == scopeKey)
            candidates = addCount(candidates)
            approvals.retirementOperation(approval.record.approvalId, row)
            val record = approval.record
            val consumed = record.consumedByOperationId ?: return // An unrelated row grants nothing.
            if (row.operationId != consumed.value) return
            val binding = requireNotNull(record.requirement.binding)
            historyShapeV2(++matched == 1 && binding.operationId == consumed && row.actorId == record.requestorId.value &&
                row.authenticationId == approval.requestorSessionId.value && row.policyRevision == record.requestorPolicyRevision.value &&
                row.digest == binding.payloadDigest.value && row.outcome == "committed" && approval.hasApproverIncarnation)
            // Current row linkage alone grants nothing. The separate approval original
            // owner must also close both humans, exact issuance/use and native images.
        }
        fun end() {
            historyShapeV2(!ended && candidates == count(RuntimeHistoryTableV2.OPERATION))
            historyShapeV2(matched == if (approval.record.consumedByOperationId == null) 0 else 1)
            ended = true
        }
        fun finish() {
            historyShapeV2(ended)
            if (!approvals.relation(approval))
                holds += RuntimeHistoryReadHold.NON_INSTALLATION_CAPTURED_AUTHORIZATION_FACTS_UNJOINED
        }
    }
    private fun consumeProtectedRelation(pass: NativeHistoryReadPassV2, value: Any) {
        when (pass) {
            NativeHistoryReadPassV2.CENSUS, NativeHistoryReadPassV2.LINEAGE_IMAGE, NativeHistoryReadPassV2.LINEAGE_CURRENT -> Unit
            NativeHistoryReadPassV2.EVENT_MUTATIONS -> historyShapeV2(
                session.hasChronologicalAccountImageTraversal && value is ProtectedLocalAccount)
            NativeHistoryReadPassV2.ACCOUNT_RELATIONS -> {
                historyShapeV2(accountRelations == null)
                accountRelations = AccountRelations(value as? ProtectedLocalAccount ?: malformed())
                accountRelationCount = addCount(accountRelationCount)
            }
            NativeHistoryReadPassV2.ACCOUNT_POLICY -> requireNotNull(accountRelations).policy(value as? ActivatedLocalPolicy ?: malformed())
            NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION -> requireNotNull(accountRelations).configuration(value as? LocalAuthConfiguration ?: malformed())
            NativeHistoryReadPassV2.ACCOUNT_THROTTLES -> requireNotNull(accountRelations).throttle(value as? PersistentThrottleState ?: malformed())
            NativeHistoryReadPassV2.ACCESS_RELATIONS -> {
                historyShapeV2(accessRelations == null)
                accessRelations = AccessRelations(value as? LocalAccessIssuance ?: malformed())
                accessRelationCount = addCount(accessRelationCount)
            }
            NativeHistoryReadPassV2.ACCESS_REFRESH -> requireNotNull(accessRelations).refresh(value as? LocalRefreshIssuance ?: malformed())
            NativeHistoryReadPassV2.ACCESS_ARCHIVE -> requireNotNull(accessRelations).archive(value as? LocalSessionArchive ?: malformed())
            NativeHistoryReadPassV2.ACCESS_ACCOUNTS -> requireNotNull(accessRelations).account(value as? ProtectedLocalAccount ?: malformed())
            NativeHistoryReadPassV2.ACCESS_ADMISSIONS -> requireNotNull(accessRelations).admission(value as? LocalDeviceAdmission ?: malformed())
            NativeHistoryReadPassV2.ACCESS_CONFIGURATIONS -> requireNotNull(accessRelations).configuration(value as? LocalAuthConfiguration ?: malformed())
            NativeHistoryReadPassV2.ARCHIVE_RELATIONS -> {
                historyShapeV2(archiveRelations == null)
                archiveRelations = ArchiveRelations(value as? LocalSessionArchive ?: malformed())
                archiveRelationCount = addCount(archiveRelationCount)
            }
            NativeHistoryReadPassV2.ARCHIVE_ACCESS -> requireNotNull(archiveRelations).access(value as? LocalAccessIssuance ?: malformed())
            NativeHistoryReadPassV2.APPROVAL_RELATIONS -> {
                historyShapeV2(approvalRelations == null)
                approvalRelations = ApprovalRelations(value as? ProtectedLocalActionApproval ?: malformed())
                approvalRelationCount = addCount(approvalRelationCount)
            }
            else -> historyShapeV2(false)
        }
    }
    private fun consumeApprovalOperation(row: OperationRow) {
        try { requireNotNull(approvalRelations).operation(row) } finally { row.result.fill(0) }
    }

    private suspend fun boundary(metadata: RuntimeHistoryReadMetadata) {
        val pass = metadata.pass
        val table = requireNotNull(metadata.table)
        if (metadata.kind == NativeHistoryRowKindV2.PASS_END) {
            historyShapeV2(expectedRoots[rootAt] == (pass to table))
            when (pass) {
                NativeHistoryReadPassV2.CENSUS -> censusRowId = null
                NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS -> employees.endSeedPass()
                NativeHistoryReadPassV2.EVENTS -> {
                    historyShapeV2(event == null)
                    business?.closeRecipientEvents()
                }
                NativeHistoryReadPassV2.LINEAGE -> {
                    lineage?.let { it.requireVersionComplete(); historyShapeV2(it.keyClosed); it.clear() }; lineage = null
                    if (cold == null) business?.closeLineages()
                }
                NativeHistoryReadPassV2.COMMANDS -> historyShapeV2(command == null)
                NativeHistoryReadPassV2.STOCK_AGGREGATES -> finishStockAggregate()
                NativeHistoryReadPassV2.STOCK_REVERSE -> historyShapeV2(stockIndex == null)
                NativeHistoryReadPassV2.ACCOUNT_RELATIONS -> historyShapeV2(accountRelations == null)
                NativeHistoryReadPassV2.ACCESS_RELATIONS -> historyShapeV2(accessRelations == null)
                NativeHistoryReadPassV2.ARCHIVE_RELATIONS -> historyShapeV2(archiveRelations == null)
                NativeHistoryReadPassV2.APPROVAL_RELATIONS -> historyShapeV2(approvalRelations == null)
                else -> historyShapeV2(false)
            }
            rootAt++
            return
        }
        historyShapeV2(metadata.kind == NativeHistoryRowKindV2.GROUP_END)
        when (pass) {
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_MUTATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.ROW_VERSION); employees.endSeedMutations()
            }
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE -> employees.endSeedImage(table)
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_CHUNKS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.CHUNK); employees.endSeedChunks()
            }
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.EVENT); employees.endSeed()
            }
            NativeHistoryReadPassV2.EVENT_MUTATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.ROW_VERSION); requireNotNull(event).endMutations()
            }
            NativeHistoryReadPassV2.EVENT_CHUNKS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.CHUNK)
                val active = requireNotNull(event)
                try { active.finish() } finally { active.clear(); event = null }
            }
            NativeHistoryReadPassV2.LINEAGE_EVENT -> {
                val active = requireNotNull(lineage)
                historyShapeV2(table == RuntimeHistoryTableV2.EVENT && active.eventRows == 1 && !active.eventEnded)
                active.eventEnded = true
            }
            NativeHistoryReadPassV2.LINEAGE_EVENT_CHUNKS -> {
                val active = requireNotNull(lineage)
                historyShapeV2(table == RuntimeHistoryTableV2.CHUNK && RuntimeHistoryTableV2.fromTag(active.row.tableTag).hasAuthenticationImages &&
                    active.eventEnded && !active.capsuleEnded)
                val capsule = requireNotNull(active.capsule)
                val intent = capsule.finish()
                policies.lineageSourceCapsule(active.row,capsule,intent)
                deviceAdmissions.lineageSourceCapsule(active.row,capsule,intent)
                val header = checkNotNull(active.eventHeader)
                val authentication = boundAuthentication(header, capsule, intent)?.takeIf {
                    isOriginalAuthentication(it, header.sequence) || isEmployeeAuthentication(it, header.sequence)
                }
                val sessionRevocation = boundSessionRevocation(header, capsule, intent)
                val administrationCancellation = boundAdministrationCancellation(header, capsule, intent)
                val deviceRevocation = boundDeviceRevocation(header, capsule, intent)
                val additionalDeviceRevocation = boundAdditionalDeviceRevocation(header, capsule, intent)
                val approvalIssuance = boundApprovalIssuance(header, capsule, intent)
                val approvalRetirement = boundApprovalRetirement(header, capsule, intent)
                val approvedCommand = boundApprovedCommand(header, capsule, intent)
                val serialPublication = boundSerialPublicationRequest(header, capsule, intent)
                if (serialPublication != null) {
                    val registered = serialPublications.evidence(header, requireNotNull(active.eventDigest), active.row)
                    if (registered != null) historyShapeV2(registered == serialPublication)
                    // Only the dedicated join compares this immutable audit's full native
                    // image, current row and tip. It extends no generic owned-decision facts.
                } else if (approvalRetirement != null) {
                    val registered = approvals.retirementEvidence(header, requireNotNull(active.eventDigest), active.row)
                    if (registered != null) historyShapeV2(registered == approvalRetirement)
                    // The immutable tombstone is opened only by approvals.lineageImage;
                    // it is neither an ordinary authentication nor a consumed AP image.
                } else if (approvalIssuance != null) {
                    // An issuance nests the actual MATCH throttle completion but has its
                    // own purpose and approval image grammar. Do not reinterpret it as an
                    // ordinary authentication or throttle event.
                    val registered = approvals.issuanceEvidence(header, requireNotNull(active.eventDigest), active.row)
                    if (registered != null) {
                        historyShapeV2(registered == approvalIssuance)
                        if (active.row.tableTag == RuntimeHistoryTableV2.THROTTLE.tag) {
                            active.throttleEvidence = registered.throttle.takeIf {
                                isOriginalThrottle(it) || employees.matchesThrottle(it)
                            }
                            active.employeeThrottleEvidence = active.throttleEvidence?.let(employees::matchesThrottle) == true
                        }
                    }
                } else if (approvedCommand != null) {
                    val registered = approvals.commandEvidence(header, requireNotNull(active.eventDigest), active.row)
                    if (registered != null) historyShapeV2(registered == approvedCommand)
                    // Protected Apply siblings are opened by approvals.lineageImage;
                    // Reject has none. Neither extends old authentication image profiles.
                } else if (additionalDeviceRevocation != null) {
                    val expected = deviceAdmissions.withdrawalImage(active.row)
                    if (expected != null) {
                        historyShapeV2(expected == additionalDeviceRevocation.images.getOrNull(active.row.ordinal) &&
                            (expected.table == RuntimeHistoryTableV2.ACCOUNT || expected.table == RuntimeHistoryTableV2.AUTH_EVENT))
                        active.deviceRevocationImage = expected
                        active.employeeDeviceRevocationImage = if (expected.table == RuntimeHistoryTableV2.ACCOUNT)
                            expected.recordId != coordinates.firstActorId else deviceAdmissions.employeeWithdrawalAudit(active.row)
                    }
                } else if (deviceRevocation != null) {
                    val expected = deviceRevocationLineageImage(active, deviceRevocation)
                    active.deviceRevocationImage = expected
                    active.employeeDeviceRevocationImage = employees.matchesDeviceRevocationImage(deviceRevocation, expected)
                } else if (sessionRevocation != null) {
                    val expected = sessionRevocation.images.getOrNull(active.row.ordinal) ?: malformed()
                    requireSessionRevocationMutation(active.row, expected, active.row.ordinal)
                    sessionRevocation.retained?.let {
                        requireAccountAdministrationPredecessor(active.row, it.command.expectedRevision, active.row.ordinal)
                        if (active.row.ordinal == 0) historyShapeV2(active.previousAccountDigest == it.target.accountBeforeDigest)
                    }
                    sessionRevocation.disable?.let {
                        requireAccountAdministrationPredecessor(active.row, it.command.expectedRevision, active.row.ordinal)
                        if (active.row.ordinal == 0) historyShapeV2(active.previousAccountDigest == it.target.accountBeforeDigest)
                    }
                    sessionRevocation.reset?.let {
                        requireAccountAdministrationPredecessor(active.row, it.command.expectedRevision, active.row.ordinal)
                        if (active.row.ordinal == 0) historyShapeV2(active.previousAccountDigest == it.target.accountBeforeDigest)
                    }
                    active.sessionRevocationImage = expected
                    active.employeeSessionRevocationImage = sessionRevocation.self?.let(employees::matchesSessionRevocation)
                        ?: sessionRevocation.retained?.let(employees::matchesRetainedSessionRevocationTarget)
                        ?: sessionRevocation.disable?.let(employees::matchesAccountDisableTarget)
                        ?: employees.matchesCredentialResetTarget(checkNotNull(sessionRevocation.reset))
                } else if (administrationCancellation != null) {
                    val expected = administrationCancellation.images.single()
                    requireAdministrationCancellationMutation(active.row, expected)
                    active.administrationCancellationImage = expected
                    active.employeeAdministrationCancellationImage = employees.matchesAdministrationCancellation(administrationCancellation)
                } else if (authentication != null) {
                    val expected = authentication.images.getOrNull(active.row.ordinal) ?: malformed()
                    val key = expected.key(scope)
                    try { historyShapeV2(expected.tableTag == active.row.tableTag &&
                        key.contentEquals(active.row.key) && active.row.mutationKind == expected.mutation.tag) }
                    finally { key.fill(0) }
                    active.authenticationImage = expected
                    active.employeeAuthenticationImage = isEmployeeAuthentication(authentication, header.sequence)
                    if (active.row.tableTag == RuntimeHistoryTableV2.THROTTLE.tag) {
                        active.throttleEvidence = authentication.throttle?.takeIf {
                            isOriginalAuthenticationThrottle(it, header.sequence) || isEmployeeAuthenticationThrottle(it, header.sequence)
                        }
                        active.employeeThrottleEvidence = active.throttleEvidence?.let {
                            isEmployeeAuthenticationThrottle(it, header.sequence)
                        } == true
                    }
                } else if (active.row.tableTag == RuntimeHistoryTableV2.THROTTLE.tag) {
                    active.throttleEvidence = boundThrottle(header, capsule, intent)?.takeIf {
                        isOriginalAuthenticationThrottle(it, header.sequence) || isEmployeeAuthenticationThrottle(it, header.sequence)
                    }
                    active.employeeThrottleEvidence = active.throttleEvidence?.let {
                        isEmployeeAuthenticationThrottle(it, header.sequence)
                    } == true
                }
                if (header.purpose == RuntimeWritePurposeV2.ROLE_TRANSITION &&
                    active.row.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag) {
                    historyShapeV2(active.lifecycleIntent == null)
                    active.lifecycleIntent = intent
                }
                capsule.clear(); active.capsule = null; active.capsuleEnded = true
            }
            NativeHistoryReadPassV2.LINEAGE_IMAGE -> {
                val active = requireNotNull(lineage)
                historyShapeV2(table.tag == active.row.tableTag && active.row.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag &&
                    active.eventEnded && active.imageRows == 1 && !active.imageEnded)
                active.imageEnded = true
            }
            NativeHistoryReadPassV2.LINEAGE_CURRENT -> {
                val active = requireNotNull(lineage)
                historyShapeV2(table.tag == active.row.tableTag && active.tipRows == 1 && active.currentRows == 1 && !active.currentEnded)
                active.currentEnded = true
            }
            NativeHistoryReadPassV2.LINEAGE_TIP -> {
                val active = requireNotNull(lineage); active.requireVersionComplete()
                historyShapeV2(table == RuntimeHistoryTableV2.ROW_TIP && active.tipRows == 1 && !active.tipEnded)
                if (active.row.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) historyShapeV2(active.currentRows == 0 && !active.currentEnded)
                else historyShapeV2(active.currentRows == 1 && active.currentEnded)
                active.tipEnded = true
            }
            NativeHistoryReadPassV2.LINEAGE -> {
                val active = requireNotNull(lineage); active.requireVersionComplete()
                historyShapeV2(table.tag == active.row.tableTag && active.tipEnded && !active.keyClosed); active.keyClosed = true
                if (active.receiptEvent) {
                    historyShapeV2(active.tipRows == 1 && active.currentRows == 1 && active.currentEnded)
                    immutableReceiptEvents = addCount(immutableReceiptEvents)
                }
            }
            NativeHistoryReadPassV2.COMMAND_OPERATION -> {
                historyShapeV2(table == RuntimeHistoryTableV2.OPERATION); requireNotNull(command).endOperations()
            }
            NativeHistoryReadPassV2.COMMAND_AUDIT -> {
                historyShapeV2(table == RuntimeHistoryTableV2.AUDIT); requireNotNull(command).endAudits()
            }
            NativeHistoryReadPassV2.COMMAND_OUTBOX -> {
                historyShapeV2(table == RuntimeHistoryTableV2.OUTBOX); requireNotNull(command).endOutbox()
            }
            NativeHistoryReadPassV2.COMMAND_FRAGMENT -> {
                historyShapeV2(table == RuntimeHistoryTableV2.JOURNAL_FRAGMENT)
                val active = requireNotNull(command)
                try { active.finish() } finally { active.clear(); command = null }
            }
            NativeHistoryReadPassV2.STOCK_PROJECTION -> {
                historyShapeV2(table == projectionTable(requireNotNull(stockAggregate).kind) && stockProjectionCount == 1 && !stockProjectionEnded)
                stockProjectionEnded = true
            }
            NativeHistoryReadPassV2.STOCK_REVERSE_AGGREGATE -> {
                historyShapeV2(table == RuntimeHistoryTableV2.AGGREGATE && stockIndex != null && stockReverseAggregateCount == 1)
                stockIndex = null
            }
            NativeHistoryReadPassV2.ACCOUNT_POLICY, NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION, NativeHistoryReadPassV2.ACCOUNT_THROTTLES -> {
                val expected = when (pass) {
                    NativeHistoryReadPassV2.ACCOUNT_POLICY -> RuntimeHistoryTableV2.POLICY
                    NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION -> RuntimeHistoryTableV2.CONFIGURATION
                    else -> RuntimeHistoryTableV2.THROTTLE
                }
                historyShapeV2(table == expected); requireNotNull(accountRelations).end(pass)
            }
            NativeHistoryReadPassV2.ACCOUNT_RELATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.ACCOUNT); requireNotNull(accountRelations).finish(); accountRelations = null
            }
            NativeHistoryReadPassV2.ACCESS_REFRESH, NativeHistoryReadPassV2.ACCESS_ARCHIVE, NativeHistoryReadPassV2.ACCESS_ACCOUNTS,
            NativeHistoryReadPassV2.ACCESS_ADMISSIONS, NativeHistoryReadPassV2.ACCESS_CONFIGURATIONS -> {
                val expected = when (pass) {
                    NativeHistoryReadPassV2.ACCESS_REFRESH -> RuntimeHistoryTableV2.REFRESH
                    NativeHistoryReadPassV2.ACCESS_ARCHIVE -> RuntimeHistoryTableV2.ARCHIVE
                    NativeHistoryReadPassV2.ACCESS_ACCOUNTS -> RuntimeHistoryTableV2.ACCOUNT
                    NativeHistoryReadPassV2.ACCESS_ADMISSIONS -> RuntimeHistoryTableV2.ADMISSION
                    else -> RuntimeHistoryTableV2.CONFIGURATION
                }
                historyShapeV2(table == expected); requireNotNull(accessRelations).end(pass)
            }
            NativeHistoryReadPassV2.ACCESS_RELATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.ACCESS); requireNotNull(accessRelations).finish(); accessRelations = null
            }
            NativeHistoryReadPassV2.ARCHIVE_ACCESS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.ACCESS); requireNotNull(archiveRelations).end()
            }
            NativeHistoryReadPassV2.ARCHIVE_RELATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.ARCHIVE); requireNotNull(archiveRelations).finish(); archiveRelations = null
            }
            NativeHistoryReadPassV2.APPROVAL_OPERATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.OPERATION); requireNotNull(approvalRelations).end()
            }
            NativeHistoryReadPassV2.APPROVAL_RELATIONS -> {
                historyShapeV2(table == RuntimeHistoryTableV2.APPROVAL); requireNotNull(approvalRelations).finish(); approvalRelations = null
            }
            else -> historyShapeV2(false)
        }
    }

    private fun finishCoverage() {
        historyShapeV2(matchedFinalDeadlineDecisions == native.finalDeadlineDecisions.size.toLong())
        historyShapeV2(matchedProtectedFinalDecisions == native.protectedFinalDecisions.size.toLong() &&
            protectedFinalRequestKeys.size.toLong() == matchedProtectedFinalDecisions)
        historyShapeV2(accountRelations == null && accessRelations == null && archiveRelations == null && approvalRelations == null &&
            stockAggregate == null && stockIndex == null && count(RuntimeHistoryTableV2.BRANCH) == 1L && count(RuntimeHistoryTableV2.HEAD) == 1L)
        historyShapeV2(eventCount > 0 && eventCount == count(RuntimeHistoryTableV2.EVENT) &&
            eventMutationCount == count(RuntimeHistoryTableV2.ROW_VERSION) && lineageCount == eventMutationCount &&
            eventChunkCount == count(RuntimeHistoryTableV2.CHUNK) && tipCount == count(RuntimeHistoryTableV2.ROW_TIP) &&
            count(RuntimeHistoryTableV2.WRITE_FRAME) == 0L && count(RuntimeHistoryTableV2.CAPTURE) == 0L)
        for (table in RuntimeHistoryTableV2.entries.filter { it.application }) historyShapeV2(liveTips[table.ordinal] == count(table))
        historyShapeV2(commandCount == count(RuntimeHistoryTableV2.JOURNAL) && commandCount == count(RuntimeHistoryTableV2.OPERATION) &&
            commandAuditCount == count(RuntimeHistoryTableV2.AUDIT) && commandOutboxCount == count(RuntimeHistoryTableV2.OUTBOX) &&
            commandFragmentCount == count(RuntimeHistoryTableV2.JOURNAL_FRAGMENT))
        historyShapeV2(stockAggregateCount == count(RuntimeHistoryTableV2.AGGREGATE) && stockReverseCount == STOCK_TABLES.sumOf(::count))
        historyShapeV2(accountRelationCount == count(RuntimeHistoryTableV2.ACCOUNT) && policyRelationCount == count(RuntimeHistoryTableV2.POLICY) &&
            configurationRelationCount == count(RuntimeHistoryTableV2.CONFIGURATION) && throttleRelationCount == count(RuntimeHistoryTableV2.THROTTLE) &&
            accessRelationCount == count(RuntimeHistoryTableV2.ACCESS) && refreshRelationCount == count(RuntimeHistoryTableV2.REFRESH) &&
            archiveRelationCount == count(RuntimeHistoryTableV2.ARCHIVE) && approvalRelationCount == count(RuntimeHistoryTableV2.APPROVAL) &&
            matchedAccessArchiveCount == count(RuntimeHistoryTableV2.ARCHIVE) && matchedCurrentSessionCount == censusAccountSessionCount)
        // Each counted session was joined by the exact immutable access PK and full identity,
        // each account's session IDs are unique, and the original census included ALL sessions.
        // Equality therefore closes the reverse current-session relation without an unbounded
        // map or a caller-selected key; it is not a count in place of the per-session checks.
        val sql = requireNotNull(head)
        val last = requireNotNull(previousEvent)
        historyShapeV2(sql.sequence == eventCount && last.sequence == eventCount && sql.eventDigest == requireNotNull(previousEventDigest).value &&
            sql.installationGenesisDigest == last.installationGenesisDigest.value && sql.commandEpoch == last.commandEpoch &&
            sql.commandSequence == last.commandSequence && sql.commandDigest == last.commandDigest.value &&
            sql.roleCommitment == last.roleCommitment.value && sql.recoveryCommitment == last.recoveryCommitment.value)
        val actual = NativeHistoryRecordedHeadV2(sql.sequence, PayloadDigest(sql.eventDigest), PayloadDigest(sql.installationGenesisDigest),
            sql.commandEpoch, sql.commandSequence, PayloadDigest(sql.commandDigest), PayloadDigest(sql.roleCommitment), PayloadDigest(sql.recoveryCommitment))
        historyShapeV2(actual == native.parent || actual == native.pending?.child)
        historyShapeV2((attempt.phase in setOf(NativeHistoryAttemptPhaseV2.DATABASE_BOUND, NativeHistoryAttemptPhaseV2.NATIVE_PENDING,
            NativeHistoryAttemptPhaseV2.COMPLETED) || pendingOriginalRecovery && attempt.phase == NativeHistoryAttemptPhaseV2.RETIRING) &&
            attempt.denialGeneration <= native.denialGeneration)
        // Matching an authenticated pending child is DATA reconciliation only. This reader
        // never marks it settled, returns a completion capability or retires its attempt.
        val branch = requireNotNull(currentBranch)
        historyShapeV2(branch.epoch == sql.commandEpoch && branch.sequence == sql.commandSequence && branch.journalHash == sql.commandDigest &&
            RuntimeHistoryCodecV2.roleCommitment(branch).value == sql.roleCommitment &&
            RuntimeHistoryCodecV2.recoveryCommitment(branch).value == sql.recoveryCommitment)
        val commandEpoch = previousCommandEpoch
        if (commandEpoch == sql.commandEpoch) historyShapeV2(sql.commandSequence == previousCommandSequence && sql.commandDigest == previousCommandHash)
        else {
            historyShapeV2((commandEpoch == null || commandEpoch < sql.commandEpoch) && sql.commandSequence == 0L)
            if (sql.commandEpoch == definition.definition.authorityEpoch.value) historyShapeV2(sql.commandDigest == firstChild.installationGenesisDigest.value)
            else holds += RuntimeHistoryReadHold.RECOVERY_ORIGIN_AND_RESOURCE_CROSSWALKS_UNJOINED
        }
        historyShapeV2(firstEventDigest == firstChild.historyDigest && originalReceipt != null && originalReceiptBytes != null && censusBytes <= traversedBytes)
    }

    /**
     * Original C1 is positively supported only after the complete traversal above. Empty
     * later-business tables do not demand unrelated producer/capacity implementations, but
     * an observed suffix is never ignored to obtain this original receipt. STOP/RETIRE and
     * later native denial generations do not revoke this historical observation or grant RUN.
     */
    private suspend fun requireOriginalReceiptClosure(receipt: LocalBranchInstallationReceiptV2) {
        if (native.pending != null || native.parent?.historySequence != eventCount ||
            attempt.phase != NativeHistoryAttemptPhaseV2.COMPLETED || native.receipt == null)
            holds += RuntimeHistoryReadHold.ORIGINAL_INSTALLATION_NATIVE_COMPLETION_UNRESOLVED
        if (native.issuerAcknowledgement == null || native.retiredAttempts.any { it.issuerRetirementDigest == null })
            holds += RuntimeHistoryReadHold.ORIGINAL_INSTALLATION_ISSUER_TERMINAL_JOIN_UNRESOLVED
        native.receipt?.let { historyShapeV2(it.digest == receipt.digest && it.genesis == receipt.genesis) }
        native.issuerAcknowledgement?.let { retained ->
            val expected = LocalBranchInstallationWireV2.acknowledgement(definition,attempt.facts,requireNotNull(attempt.approvals),receipt,firstChild)
            val expectedBytes = expected.copyWireBytes()
            try {
                val actualBytes = retained.frame.copyWireBytes()
                try { historyShapeV2(expectedBytes.contentEquals(actualBytes)) } finally { actualBytes.fill(0) }
            } finally { expectedBytes.fill(0) }
            // Native verified this exact root signature before opening and again after close.
            // A self-signed head-reference document without this terminal ACK is insufficient.
        }
        // Lifecycle changes insert exactly one immutable audit and never mutate the
        // authentication reducers' accounts/sessions. Account that separate, fully joined
        // row only after EVERY authorised event has its exact protected lineage image.
        historyShapeV2(masterLifecycleImages == masterLifecycleEvidence.keys)
        val lifecycleAudits = masterLifecycleEvidence.size.toLong()
        if (eventCount == 1L) {
            employees.finish(); policies.finish(); deviceAdmissions.finish(); approvals.requireClosure(); serialPublications.requireClosure()
            // Exactly BranchRow + six non-event protected seeds + receipt-bearing auth-event.
            // Seven semantic digests include recovery embedded in BranchRow, NOT an extra row.
            for (table in RuntimeHistoryTableV2.entries.filter { it.application }) {
                val expected = when (table) {
                    RuntimeHistoryTableV2.BRANCH,RuntimeHistoryTableV2.ACCOUNT,RuntimeHistoryTableV2.POLICY,
                    RuntimeHistoryTableV2.ADMISSION,RuntimeHistoryTableV2.CONFIGURATION,RuntimeHistoryTableV2.AUTH_EVENT -> 1L
                    RuntimeHistoryTableV2.THROTTLE -> 2L
                    else -> 0L
                }
                historyShapeV2(count(table) == expected)
            }
            historyShapeV2(eventMutationCount == 8L && lineageCount == eventMutationCount &&
                originalThrottleImages == 0L && originalAuthenticationImages == 0L && originalSessionRevocationImages == 0L &&
                originalAdministrationCancellationImages == 0L && employeeAdministrationCancellationImages == 0L &&
                deviceRevocationEvents == 0L && originalDeviceRevocationImages == 0L && employeeDeviceRevocationImages == 0L &&
                originalPolicyActivationEvents == 0L && policies.eventCount == 0L && policies.originalImages == 0L && policies.employeeImages == 0L &&
                serialPublications.eventCount == 0L && serialPublications.imageCount == 0L && employees.serialPublicationRequestMutations == 0L &&
                tipCount == 8L && commandCount == 0L &&
                censusAccountSessionCount == 0L && firstEventDigest == previousEventDigest &&
                requireNotNull(previousEvent).commandSequence == 0L)
        } else if (originalThrottleEvents + originalAuthenticationEvents + originalSessionRevocationEvents + originalAdministrationCancellationEvents +
            deviceRevocationEvents + deviceAdmissions.eventCount + originalPolicyActivationEvents + serialPublications.originalEvents + employees.eventCount +
            approvals.issuanceEvents + approvals.retirementEvents + lifecycleAudits == eventCount - 1L &&
            authenticationReplay != null) {
            val replay = checkNotNull(authenticationReplay)
            replay.finish(); employees.finish(); policies.finish(); deviceAdmissions.finish(); approvals.requireClosure(); serialPublications.requireClosure()
            // The serial-specific proof already accounts its AUTH_EVENT in the authentic
            // actor's reducer. Do not add those immutable rows/tips a second time here.
            for (table in RuntimeHistoryTableV2.entries.filter { it.application })
                historyShapeV2(count(table) == replay.count(table) + employees.count(table) + deviceAdmissions.count(table) + approvals.count(table) +
                    (if (table == RuntimeHistoryTableV2.AUTH_EVENT) lifecycleAudits else 0L))
            historyShapeV2(eventMutationCount == replay.mutationCount + employees.mutationCount + deviceAdmissions.mutationCount + approvals.nonbusinessProtectedMutationCount + lifecycleAudits &&
                eventMutationCount == 8L + originalThrottleMutations + originalAuthenticationMutations + originalSessionRevocationMutations +
                    originalAdministrationCancellationMutations + originalDeviceRevocationMutations + deviceAdmissions.mutationCount + originalPolicyActivationMutations +
                    serialPublications.originalEvents + employees.mutationCount + approvals.nonbusinessProtectedMutationCount + lifecycleAudits &&
                originalThrottleImages == originalThrottleMutations && originalAuthenticationImages == originalAuthenticationMutations &&
                originalSessionRevocationImages == originalSessionRevocationMutations &&
                originalAdministrationCancellationImages == originalAdministrationCancellationMutations &&
                employeeThrottleImages == employees.throttleMutations && employeeAuthenticationImages == employees.authenticationMutations &&
                employeeSessionRevocationImages == employees.sessionRevocationMutations &&
                employeeAdministrationCancellationImages == employees.administrationCancellationMutations &&
                originalDeviceRevocationImages == originalDeviceRevocationMutations &&
                employeeDeviceRevocationImages == employees.deviceRevocationMutations &&
                policies.originalImages == originalPolicyActivationMutations && policies.employeeImages == employees.policyActivationMutations &&
                policies.mutationCount == originalPolicyActivationMutations + employees.policyActivationMutations &&
                approvals.imageCount == approvals.protectedMutationCount && approvals.commandEvents == 0L &&
                serialPublications.imageCount == serialPublications.mutationCount && serialPublications.tipCount == serialPublications.eventCount &&
                serialPublications.employeeEvents == employees.serialPublicationRequestMutations &&
                lineageCount == eventMutationCount && tipCount == replay.tipCount + employees.tipCount + deviceAdmissions.tipCount + approvals.tipCount + lifecycleAudits && commandCount == 0L &&
                censusAccountSessionCount == replay.sessionCount + employees.sessionCount && requireNotNull(previousEvent).commandSequence == 0L)
        } else if (authenticationReplay != null && business?.enabled == true &&
            originalThrottleEvents + originalAuthenticationEvents + originalSessionRevocationEvents + originalAdministrationCancellationEvents +
                deviceRevocationEvents + deviceAdmissions.eventCount + originalPolicyActivationEvents + serialPublications.originalEvents + employees.eventCount +
                originalBusinessEvents + employeeBusinessEvents + employeeNativeOriginalEvents +
                originalSupervisorReviewEvents + employeeSupervisorReviewEvents +
                originalPurchasingPolicyRejectionEvents + employeePurchasingPolicyRejectionEvents +
                originalOrderReassignmentRejectionEvents + employeeOrderReassignmentRejectionEvents +
                originalOpeningSupplierObligationRejectionEvents + employeeOpeningSupplierObligationRejectionEvents +
                originalInvoiceCaptureRejectionEvents + employeeInvoiceCaptureRejectionEvents +
                originalCapturedInvoiceMatchRejectionEvents + employeeCapturedInvoiceMatchRejectionEvents +
                originalBranchActivityActivationRejectionEvents + employeeBranchActivityActivationRejectionEvents +
                originalFloorConfigurationRejectionEvents + employeeFloorConfigurationRejectionEvents +
                originalConfigurationRejectionEvents + employeeConfigurationRejectionEvents +
                originalReturnRightsActivationRejectionEvents + employeeReturnRightsActivationRejectionEvents +
                originalRetailCustomerReceiptRejectionEvents + employeeRetailCustomerReceiptRejectionEvents +
                originalFoodCheckRejectionEvents + employeeFoodCheckRejectionEvents +
                originalFoodPaymentRejectionEvents + employeeFoodPaymentRejectionEvents +
                originalReturnCaseQualificationRejectionEvents + employeeReturnCaseQualificationRejectionEvents +
                originalReturnStockDispositionRejectionEvents + employeeReturnStockDispositionRejectionEvents + approvals.issuanceEvents + approvals.retirementEvents +
                originalApprovedBusinessEvents + employeeApprovedBusinessEvents + lifecycleAudits == eventCount - 1L) {
            val replay = checkNotNull(authenticationReplay)
            val joined = checkNotNull(business)
            val businessEvents = originalBusinessEvents + employeeBusinessEvents + employeeNativeOriginalEvents +
                originalSupervisorReviewEvents + employeeSupervisorReviewEvents +
                originalPurchasingPolicyRejectionEvents + employeePurchasingPolicyRejectionEvents +
                originalOrderReassignmentRejectionEvents + employeeOrderReassignmentRejectionEvents +
                originalOpeningSupplierObligationRejectionEvents + employeeOpeningSupplierObligationRejectionEvents +
                originalInvoiceCaptureRejectionEvents + employeeInvoiceCaptureRejectionEvents +
                originalCapturedInvoiceMatchRejectionEvents + employeeCapturedInvoiceMatchRejectionEvents +
                originalBranchActivityActivationRejectionEvents + employeeBranchActivityActivationRejectionEvents +
                originalFloorConfigurationRejectionEvents + employeeFloorConfigurationRejectionEvents +
                originalConfigurationRejectionEvents + employeeConfigurationRejectionEvents +
                originalReturnRightsActivationRejectionEvents + employeeReturnRightsActivationRejectionEvents +
                originalRetailCustomerReceiptRejectionEvents + employeeRetailCustomerReceiptRejectionEvents +
                originalFoodCheckRejectionEvents + employeeFoodCheckRejectionEvents +
                originalFoodPaymentRejectionEvents + employeeFoodPaymentRejectionEvents +
                originalReturnCaseQualificationRejectionEvents + employeeReturnCaseQualificationRejectionEvents +
                originalReturnStockDispositionRejectionEvents + employeeReturnStockDispositionRejectionEvents +
                originalApprovedBusinessEvents + employeeApprovedBusinessEvents
            val businessMutations = originalBusinessMutations + employeeBusinessMutations + employeeNativeOriginalMutations +
                originalSupervisorReviewMutations + employeeSupervisorReviewMutations +
                originalPurchasingPolicyRejectionMutations + employeePurchasingPolicyRejectionMutations +
                originalOrderReassignmentRejectionMutations + employeeOrderReassignmentRejectionMutations +
                originalOpeningSupplierObligationRejectionMutations + employeeOpeningSupplierObligationRejectionMutations +
                originalInvoiceCaptureRejectionMutations + employeeInvoiceCaptureRejectionMutations +
                originalCapturedInvoiceMatchRejectionMutations + employeeCapturedInvoiceMatchRejectionMutations +
                originalBranchActivityActivationRejectionMutations + employeeBranchActivityActivationRejectionMutations +
                originalFloorConfigurationRejectionMutations + employeeFloorConfigurationRejectionMutations +
                originalConfigurationRejectionMutations + employeeConfigurationRejectionMutations +
                originalReturnRightsActivationRejectionMutations + employeeReturnRightsActivationRejectionMutations +
                originalRetailCustomerReceiptRejectionMutations + employeeRetailCustomerReceiptRejectionMutations +
                originalFoodCheckRejectionMutations + employeeFoodCheckRejectionMutations +
                originalFoodPaymentRejectionMutations + employeeFoodPaymentRejectionMutations +
                originalReturnCaseQualificationRejectionMutations + employeeReturnCaseQualificationRejectionMutations +
                originalReturnStockDispositionRejectionMutations + employeeReturnStockDispositionRejectionMutations +
                originalApprovedBusinessMutations + employeeApprovedBusinessMutations
            replay.finish(); employees.finish(); policies.finish(); deviceAdmissions.finish(); joined.finish(commandCount, businessMutations)
            approvals.requireClosure(); serialPublications.requireClosure()
            for (table in RuntimeHistoryTableV2.entries.filter { it.application })
                historyShapeV2(count(table) == replay.count(table) + employees.count(table) + deviceAdmissions.count(table) + joined.currentCount(table) + approvals.count(table) +
                    (if (table == RuntimeHistoryTableV2.AUTH_EVENT) lifecycleAudits else 0L))
            historyShapeV2(businessEvents == commandCount &&
                originalApprovedBusinessEvents == approvals.originalCommandEvents &&
                originalApprovedBusinessMutations == approvals.originalCommandMutations &&
                employeeApprovedBusinessEvents == approvals.employeeCommandEvents &&
                employeeApprovedBusinessMutations == approvals.employeeCommandMutations &&
                originalApprovedBusinessEvents + employeeApprovedBusinessEvents == joined.approvedCommandEvents &&
                originalApprovedBusinessMutations + employeeApprovedBusinessMutations == joined.approvedCommandMutations &&
                employeeApprovedBusinessEvents == joined.employeeApprovedCommandEvents &&
                employeeApprovedBusinessMutations == joined.employeeApprovedCommandMutations &&
                approvals.imageCount == approvals.protectedMutationCount &&
                serialPublications.imageCount == serialPublications.mutationCount && serialPublications.tipCount == serialPublications.eventCount &&
                serialPublications.employeeEvents == employees.serialPublicationRequestMutations &&
                employeeBusinessEvents == employees.commandCount &&
                employeeBusinessMutations == employees.commandMutationCount && employeeBusinessEvents == joined.employeeCommandEvents &&
                employeeBusinessMutations == joined.employeeCommandMutations && employeeNativeOriginalEvents == joined.employeeNativeOriginalEvents &&
                employeeNativeOriginalMutations == joined.employeeNativeOriginalMutations &&
                employeeSupervisorReviewEvents == employees.supervisorReviewCount &&
                employeeSupervisorReviewMutations == employees.supervisorReviewMutationCount &&
                employeeSupervisorReviewEvents == joined.employeeSupervisorReviewEvents &&
                employeeSupervisorReviewMutations == joined.employeeSupervisorReviewMutations &&
                originalSupervisorReviewEvents + employeeSupervisorReviewEvents == joined.supervisorReviewEvents &&
                originalSupervisorReviewMutations + employeeSupervisorReviewMutations == joined.supervisorReviewMutations &&
                employeePurchasingPolicyRejectionEvents == employees.purchasingPolicyRejectionCount &&
                employeePurchasingPolicyRejectionMutations == employees.purchasingPolicyRejectionMutationCount &&
                employeePurchasingPolicyRejectionEvents == joined.employeePurchasingPolicyRejectionEvents &&
                employeePurchasingPolicyRejectionMutations == joined.employeePurchasingPolicyRejectionMutations &&
                originalPurchasingPolicyRejectionEvents + employeePurchasingPolicyRejectionEvents == joined.purchasingPolicyRejectionEvents &&
                originalPurchasingPolicyRejectionMutations + employeePurchasingPolicyRejectionMutations == joined.purchasingPolicyRejectionMutations &&
                employeeOrderReassignmentRejectionEvents == employees.orderReassignmentRejectionCount &&
                employeeOrderReassignmentRejectionMutations == employees.orderReassignmentRejectionMutationCount &&
                employeeOrderReassignmentRejectionEvents == joined.employeeOrderReassignmentRejectionEvents &&
                employeeOrderReassignmentRejectionMutations == joined.employeeOrderReassignmentRejectionMutations &&
                originalOrderReassignmentRejectionEvents + employeeOrderReassignmentRejectionEvents == joined.orderReassignmentRejectionEvents &&
                originalOrderReassignmentRejectionMutations + employeeOrderReassignmentRejectionMutations == joined.orderReassignmentRejectionMutations &&
                employeeOpeningSupplierObligationRejectionEvents == employees.openingSupplierObligationRejectionCount &&
                employeeOpeningSupplierObligationRejectionMutations == employees.openingSupplierObligationRejectionMutationCount &&
                employeeOpeningSupplierObligationRejectionEvents == joined.employeeOpeningSupplierObligationRejectionEvents &&
                employeeOpeningSupplierObligationRejectionMutations == joined.employeeOpeningSupplierObligationRejectionMutations &&
                originalOpeningSupplierObligationRejectionEvents + employeeOpeningSupplierObligationRejectionEvents == joined.openingSupplierObligationRejectionEvents &&
                originalOpeningSupplierObligationRejectionMutations + employeeOpeningSupplierObligationRejectionMutations == joined.openingSupplierObligationRejectionMutations &&
                employeeInvoiceCaptureRejectionEvents == employees.invoiceCaptureRejectionCount &&
                employeeInvoiceCaptureRejectionMutations == employees.invoiceCaptureRejectionMutationCount &&
                employeeInvoiceCaptureRejectionEvents == joined.employeeInvoiceCaptureRejectionEvents &&
                employeeInvoiceCaptureRejectionMutations == joined.employeeInvoiceCaptureRejectionMutations &&
                originalInvoiceCaptureRejectionEvents + employeeInvoiceCaptureRejectionEvents == joined.invoiceCaptureRejectionEvents &&
                originalInvoiceCaptureRejectionMutations + employeeInvoiceCaptureRejectionMutations == joined.invoiceCaptureRejectionMutations &&
                employeeCapturedInvoiceMatchRejectionEvents == employees.capturedInvoiceMatchRejectionCount &&
                employeeCapturedInvoiceMatchRejectionMutations == employees.capturedInvoiceMatchRejectionMutationCount &&
                employeeCapturedInvoiceMatchRejectionEvents == joined.employeeCapturedInvoiceMatchRejectionEvents &&
                employeeCapturedInvoiceMatchRejectionMutations == joined.employeeCapturedInvoiceMatchRejectionMutations &&
                originalCapturedInvoiceMatchRejectionEvents + employeeCapturedInvoiceMatchRejectionEvents == joined.capturedInvoiceMatchRejectionEvents &&
                originalCapturedInvoiceMatchRejectionMutations + employeeCapturedInvoiceMatchRejectionMutations == joined.capturedInvoiceMatchRejectionMutations &&
                employeeBranchActivityActivationRejectionEvents == employees.branchActivityActivationRejectionCount &&
                employeeBranchActivityActivationRejectionMutations == employees.branchActivityActivationRejectionMutationCount &&
                employeeBranchActivityActivationRejectionEvents == joined.employeeBranchActivityActivationRejectionEvents &&
                employeeBranchActivityActivationRejectionMutations == joined.employeeBranchActivityActivationRejectionMutations &&
                originalBranchActivityActivationRejectionEvents + employeeBranchActivityActivationRejectionEvents == joined.branchActivityActivationRejectionEvents &&
                originalBranchActivityActivationRejectionMutations + employeeBranchActivityActivationRejectionMutations == joined.branchActivityActivationRejectionMutations &&
                employeeFloorConfigurationRejectionEvents == employees.floorConfigurationRejectionCount &&
                employeeFloorConfigurationRejectionMutations == employees.floorConfigurationRejectionMutationCount &&
                employeeFloorConfigurationRejectionEvents == joined.employeeFloorConfigurationRejectionEvents &&
                employeeFloorConfigurationRejectionMutations == joined.employeeFloorConfigurationRejectionMutations &&
                originalFloorConfigurationRejectionEvents + employeeFloorConfigurationRejectionEvents == joined.floorConfigurationRejectionEvents &&
                originalFloorConfigurationRejectionMutations + employeeFloorConfigurationRejectionMutations == joined.floorConfigurationRejectionMutations &&
                employeeConfigurationRejectionEvents == employees.configurationRejectionCount &&
                employeeConfigurationRejectionMutations == employees.configurationRejectionMutationCount &&
                employeeConfigurationRejectionEvents == joined.employeeConfigurationRejectionEvents &&
                employeeConfigurationRejectionMutations == joined.employeeConfigurationRejectionMutations &&
                originalConfigurationRejectionEvents + employeeConfigurationRejectionEvents == joined.configurationRejectionEvents &&
                originalConfigurationRejectionMutations + employeeConfigurationRejectionMutations == joined.configurationRejectionMutations &&
                employeeReturnRightsActivationRejectionEvents == employees.returnRightsActivationRejectionCount &&
                employeeReturnRightsActivationRejectionMutations == employees.returnRightsActivationRejectionMutationCount &&
                employeeReturnRightsActivationRejectionEvents == joined.employeeReturnRightsActivationRejectionEvents &&
                employeeReturnRightsActivationRejectionMutations == joined.employeeReturnRightsActivationRejectionMutations &&
                originalReturnRightsActivationRejectionEvents + employeeReturnRightsActivationRejectionEvents == joined.returnRightsActivationRejectionEvents &&
                originalReturnRightsActivationRejectionMutations + employeeReturnRightsActivationRejectionMutations == joined.returnRightsActivationRejectionMutations &&
                employeeRetailCustomerReceiptRejectionEvents == employees.retailCustomerReceiptRejectionCount &&
                employeeRetailCustomerReceiptRejectionMutations == employees.retailCustomerReceiptRejectionMutationCount &&
                employeeRetailCustomerReceiptRejectionEvents == joined.employeeRetailCustomerReceiptRejectionEvents &&
                employeeRetailCustomerReceiptRejectionMutations == joined.employeeRetailCustomerReceiptRejectionMutations &&
                originalRetailCustomerReceiptRejectionEvents + employeeRetailCustomerReceiptRejectionEvents == joined.retailCustomerReceiptRejectionEvents &&
                originalRetailCustomerReceiptRejectionMutations + employeeRetailCustomerReceiptRejectionMutations == joined.retailCustomerReceiptRejectionMutations &&
                employeeFoodCheckRejectionEvents == employees.foodCheckRejectionCount &&
                employeeFoodCheckRejectionMutations == employees.foodCheckRejectionMutationCount &&
                employeeFoodCheckRejectionEvents == joined.employeeFoodCheckRejectionEvents &&
                employeeFoodCheckRejectionMutations == joined.employeeFoodCheckRejectionMutations &&
                originalFoodCheckRejectionEvents + employeeFoodCheckRejectionEvents == joined.foodCheckRejectionEvents &&
                originalFoodCheckRejectionMutations + employeeFoodCheckRejectionMutations == joined.foodCheckRejectionMutations &&
                employeeFoodPaymentRejectionEvents == employees.foodPaymentRejectionCount &&
                employeeFoodPaymentRejectionMutations == employees.foodPaymentRejectionMutationCount &&
                employeeFoodPaymentRejectionEvents == joined.employeeFoodPaymentRejectionEvents &&
                employeeFoodPaymentRejectionMutations == joined.employeeFoodPaymentRejectionMutations &&
                originalFoodPaymentRejectionEvents + employeeFoodPaymentRejectionEvents == joined.foodPaymentRejectionEvents &&
                originalFoodPaymentRejectionMutations + employeeFoodPaymentRejectionMutations == joined.foodPaymentRejectionMutations &&
                employeeReturnCaseQualificationRejectionEvents == employees.returnCaseQualificationRejectionCount &&
                employeeReturnCaseQualificationRejectionMutations == employees.returnCaseQualificationRejectionMutationCount &&
                employeeReturnCaseQualificationRejectionEvents == joined.employeeReturnCaseQualificationRejectionEvents &&
                employeeReturnCaseQualificationRejectionMutations == joined.employeeReturnCaseQualificationRejectionMutations &&
                originalReturnCaseQualificationRejectionEvents + employeeReturnCaseQualificationRejectionEvents == joined.returnCaseQualificationRejectionEvents &&
                originalReturnCaseQualificationRejectionMutations + employeeReturnCaseQualificationRejectionMutations == joined.returnCaseQualificationRejectionMutations &&
                employeeReturnStockDispositionRejectionEvents == employees.returnStockDispositionRejectionCount &&
                employeeReturnStockDispositionRejectionMutations == employees.returnStockDispositionRejectionMutationCount &&
                employeeReturnStockDispositionRejectionEvents == joined.employeeReturnStockDispositionRejectionEvents &&
                employeeReturnStockDispositionRejectionMutations == joined.employeeReturnStockDispositionRejectionMutations &&
                originalReturnStockDispositionRejectionEvents + employeeReturnStockDispositionRejectionEvents == joined.returnStockDispositionRejectionEvents &&
                originalReturnStockDispositionRejectionMutations + employeeReturnStockDispositionRejectionMutations == joined.returnStockDispositionRejectionMutations &&
                eventMutationCount == replay.mutationCount + employees.mutationCount + deviceAdmissions.mutationCount + businessMutations + approvals.nonbusinessProtectedMutationCount + lifecycleAudits &&
                eventMutationCount == 8L + originalThrottleMutations + originalAuthenticationMutations + originalSessionRevocationMutations +
                    originalAdministrationCancellationMutations + originalDeviceRevocationMutations + deviceAdmissions.mutationCount + originalPolicyActivationMutations +
                    serialPublications.originalEvents + employees.mutationCount + businessMutations + approvals.nonbusinessProtectedMutationCount + lifecycleAudits &&
                originalThrottleImages == originalThrottleMutations && originalAuthenticationImages == originalAuthenticationMutations &&
                originalSessionRevocationImages == originalSessionRevocationMutations &&
                originalAdministrationCancellationImages == originalAdministrationCancellationMutations &&
                employeeThrottleImages == employees.throttleMutations && employeeAuthenticationImages == employees.authenticationMutations &&
                employeeSessionRevocationImages == employees.sessionRevocationMutations &&
                employeeAdministrationCancellationImages == employees.administrationCancellationMutations &&
                originalDeviceRevocationImages == originalDeviceRevocationMutations &&
                employeeDeviceRevocationImages == employees.deviceRevocationMutations &&
                policies.originalImages == originalPolicyActivationMutations && policies.employeeImages == employees.policyActivationMutations &&
                policies.mutationCount == originalPolicyActivationMutations + employees.policyActivationMutations &&
                lineageCount == eventMutationCount && tipCount == replay.tipCount + employees.tipCount + deviceAdmissions.tipCount + joined.tipDelta + approvals.tipCount + lifecycleAudits &&
                censusAccountSessionCount == replay.sessionCount + employees.sessionCount)
            // Every historical/current stock selector, payload, actual source and COMPLETE
            // installed stock/commerce producer has been joined, not merely counted above.
            holds -= RuntimeHistoryReadHold.STOCK_CANONICAL_CODEC_AND_ORIGINAL_PRODUCER_UNJOINED
            // FOOD additionally requires every literal native CURRENT document/identity and
            // its already joined last writer, plus the installed typed complete relationships.
            // Unsupported current food, money/issue/handoff or older writers retain this hold.
            if (RuntimeHistoryReadHold.FOOD_CURRENT_TYPED_CENSUS_WRITER_CLOSURE_UNJOINED in holds && joined.joinCurrentFood())
                holds -= RuntimeHistoryReadHold.FOOD_CURRENT_TYPED_CENSUS_WRITER_CLOSURE_UNJOINED
        } else {
            holds += RuntimeHistoryReadHold.NON_INSTALLATION_CAPTURED_AUTHORIZATION_FACTS_UNJOINED
        }
    }

    private fun framedText(values: List<String>): ByteArray {
        val fields = mutableListOf<ByteArray>()
        return try {
            for (value in values) fields += value.encodeToByteArray(throwOnInvalidSequence = true)
            frame(fields)
        } finally { fields.forEach { it.fill(0) } }
    }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        for (at in 0 until minOf(left.size, right.size)) {
            val difference = (left[at].toInt() and 255) - (right[at].toInt() and 255)
            if (difference != 0) return difference
        }
        return left.size.compareTo(right.size)
    }
    private suspend fun clear(): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        if (!receiptMembershipFinished) try { receiptMembershipBuilder?.abort() }
        catch (caught: Throwable) { failure = caught }
        // Nested original loans retain actual authorization owners. Settle them before
        // discarding their replay state or closing the one native DATA workspace.
        try { business?.clearCold() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        try { clearMemory() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        try { coldTranscript?.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        } finally { coldTranscript = null }
        failure?.let { throw it }
    }
    private fun clearMemory() {
        originalReceiptBytes?.fill(0); originalReceiptBytes = null; originalReceipt = null
        event?.clear(); event = null
        lineage?.clear(); lineage = null
        authenticationReplay?.clear(); authenticationReplay = null
        masterLifecycleEvidence.clear()
        masterLifecycleImages.clear()
        employees.clear(); policies.clear(); deviceAdmissions.clear(); approvals.clear(); serialPublications.clear()
        earlierOriginal = null
        protectedFinalRequestKeys.clear()
        deviceRevocationEvent?.clear(); deviceRevocationEvent = null
        censusOriginalAccount = null; censusOriginalPolicy = null; censusOriginalAdmission = null; censusOriginalConfiguration = null
        censusOriginalAccountDigest = null
        censusOriginalAccountThrottle = null; censusOriginalDeviceThrottle = null
        command?.clear(); command = null
        expectedPlaintext = null; currentBranch = null; c1Branch = null; head = null
        previousEvent = null; previousEventDigest = null; firstEventDigest = null
        originalSeedDigests.clear(); holds.clear(); census.fill(0); liveTips.fill(0)
        observedFirstChild = null
        stockAggregate = null; stockIndex = null
        accountRelations = null; accessRelations = null; archiveRelations = null; approvalRelations = null
        coldScanMemory?.close()
        // Original codecs also retain immutable Strings/StoredBytes/records. Dropping these
        // does not claim erasure of those representations. Explicitly owned mutable buffers
        // have disposal paths; native separately owns its retained resources and copies.
    }
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)

    companion object {
        private val CLOSED_REJECTION_CHANGE_COUNTS = setOf(RuntimeHistoricalSupervisorReviewProfile.CAPTURED_CHANGES,
            RuntimeHistoricalPurchasingPolicyRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalOpeningSupplierObligationRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalInvoiceCaptureRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalBranchActivityActivationRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalFloorConfigurationRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalConfigurationRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalReturnRightsActivationRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalRetailCustomerReceiptRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalReturnCaseQualificationRejectionProfile.CAPTURED_CHANGES,
            RuntimeHistoricalReturnStockDispositionRejectionProfile.CAPTURED_CHANGES)
        private val STOCK_TABLES = listOf(RuntimeHistoryTableV2.STOCK_POSITION, RuntimeHistoryTableV2.STOCK_ALLOCATION,
            RuntimeHistoryTableV2.STOCK_RESERVATION, RuntimeHistoryTableV2.STOCK_VALUE)
        private val ORIGINAL_PROTECTED_TABLES = setOf(RuntimeHistoryTableV2.ACCOUNT, RuntimeHistoryTableV2.POLICY,
            RuntimeHistoryTableV2.CONFIGURATION, RuntimeHistoryTableV2.ADMISSION, RuntimeHistoryTableV2.THROTTLE, RuntimeHistoryTableV2.AUTH_EVENT)
        internal suspend fun replayEarlierOrdinary(replay: RuntimeHistoryColdReplay,
            producer: RuntimeHistoryBusinessProducer, stockCodec: StockCanonicalProjectionV2,
            target: RuntimeHistoryColdReplayTarget): RuntimeHistoryColdOriginalLoan {
            val budget = RuntimeHistoryColdScanMemory.open(replay.session, replay.transcript.records.memory)
            val scanner = try {
                RuntimeHistoryScanner(replay.session, producer = producer, stockCodec = stockCodec,
                    coldReplay = replay, earlierTarget = target, coldScanMemory = budget)
            } catch (failure: Throwable) {
                try { budget.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
            return scanner.replayEarlierOrdinary()
        }
        suspend fun scanOriginal(session: SelfOwnedNativeHistoryReadSession,
            producer: RuntimeHistoryBusinessProducer? = null, stockCodec: StockCanonicalProjectionV2? = null,
            receiptMembershipFactory: (suspend (RuntimeHistoryColdMemory) -> RuntimeRetailCustomerReceiptMembershipBuilder)? = null):
            RuntimeHistoryReadSelection {
            val useCold = producer != null && stockCodec != null && session.hasDataWorkspace &&
                (session.original.record.parent?.commandSequence ?: 0L) > RuntimeHistoryColdMemory.MAX_COMMANDS
            historyShapeV2(receiptMembershipFactory == null || producer?.retailCustomerReceiptEventClassifier != null && stockCodec != null)
            val memory = if (useCold || receiptMembershipFactory != null) RuntimeHistoryColdMemory() else null
            val budget = if (useCold) RuntimeHistoryColdScanMemory.open(session, checkNotNull(memory)) else null
            val membership = try { receiptMembershipFactory?.invoke(checkNotNull(memory)) }
            catch (failure: Throwable) {
                try { budget?.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
            val scanner = try {
                RuntimeHistoryScanner(session, producer = producer, stockCodec = stockCodec, coldScanMemory = budget,
                    receiptMembershipBuilder = membership, receiptMembershipMemory = memory)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { membership?.abort() } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                    try { budget?.close() } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
            return scanner.scanAndClear()
        }
        /** Separate coordinator entry; the read-only receipt path cannot settle this data.
         * Empty/missing/unsupported histories still fail or retain holds, never safe absence. */
        suspend fun scanFencedPendingOriginal(session: SelfOwnedNativeHistoryReadSession): RuntimeHistoryReadSelection {
            val record = session.original.record
            historyShapeV2(record.firstChild == null && record.receipt == null && record.parent == null &&
                record.attempt?.phase == NativeHistoryAttemptPhaseV2.RETIRING && record.pending != null)
            val scanner = RuntimeHistoryScanner(session, pendingOriginalRecovery = true)
            return scanner.scanAndClear()
        }
    }

    private suspend fun scanAndClear(): RuntimeHistoryReadSelection {
        var failure: Throwable? = null
        val selection = try { scan() } catch (caught: Throwable) { failure = caught; null }
        try { clear() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup
            else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        try { currentCoroutineContext().ensureActive() } catch (caught: Throwable) {
            if (failure == null) failure = caught
            else if (failure !== caught) failure.addSuppressed(caught)
        }
        if (failure != null) {
            // A finished DATA candidate must not escape when any later cleanup or
            // cancellation prevents delivery. Its parked native resource stays
            // lifetime-owned; abort does not retry a consumed native read/close.
            if (receiptMembershipFinished) withContext(NonCancellable) {
                try { receiptMembershipBuilder?.abort() } catch (cleanup: Throwable) {
                    if (failure !== cleanup) checkNotNull(failure).addSuppressed(cleanup)
                }
            }
            throw checkNotNull(failure)
        }
        return checkNotNull(selection)
    }
}

/** Internal selection DATA, not Found, a transferable complete-history proof or a writer grant. */
internal class RuntimeHistoryReadSelection(
    val receipt: LocalBranchInstallationReceiptV2,
    val firstChild: LocalInstallationCompletedChildFactsV2,
    holds: Set<RuntimeHistoryReadHold>,
    val recordDigest: PayloadDigest,
    val retailCustomerReceiptMembership: RuntimeRetailCustomerReceiptMembershipSeed? = null,
) {
    private val unresolved = holds.toSet()
    val holds: Set<RuntimeHistoryReadHold> get() = unresolved.toSet()
    override fun toString(): String = "RuntimeHistoryReadSelectionV1([REDACTED])"
}
