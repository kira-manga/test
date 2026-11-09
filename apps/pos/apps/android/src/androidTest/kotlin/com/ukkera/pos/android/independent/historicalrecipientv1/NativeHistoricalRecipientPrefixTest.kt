package com.ukkera.pos.android.independent.historicalrecipientv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.retailhandoffhistoryv1.NativeRetailFixtureCommand
import com.ukkera.pos.android.independent.retailhandoffhistoryv1.NativeRetailFixturePhase
import com.ukkera.pos.android.independent.retailhandoffhistoryv1.NativeRetailFixturePlan
import com.ukkera.pos.android.independent.retailhandoffhistoryv1.NativeRetailFixturePreparation
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCollectionStockCodecV2
import com.ukkera.pos.sales.store.LocalCollectionStockRequestV2
import com.ukkera.pos.sales.store.LocalOrderQueryV1
import com.ukkera.pos.sales.store.LocalOrderReadCodecV2
import com.ukkera.pos.sales.store.LocalProfiledOrderV2
import com.ukkera.pos.sales.store.LocalSalesAction
import com.ukkera.pos.sales.store.LocalSalesResultCodec
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.readOriginalBusinessPublications
import com.ukkera.pos.security.readOriginalPurchasingPolicies
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A focused native PARTIAL binding of the unchanged historical-recipient v1 freeze.
 * It prepares the existing retail fixture, records CREATE, then genuinely enrolls an extra
 * b.original recipient. The real Offer targets existing business custodian B; the observer
 * queries CREATE twice in each of three complete scans: before enrollment, after enrollment
 * but before Offer, and after Offer. A fourth complete scan observes only Offer, probing the
 * retired original Offer inside its own new Offer callback. Every observation uses an actual
 * installed scanner and the registry's whole joins; the extra recipient stays query-only.
 *
 * This does NOT construct F-HREC-BASE's A7/P4 -> A10/P5 chronology, EMP-A/EMP-C/R17 baseline,
 * original manager acceptance, unavailable-history/cancellation/capacity, process restart,
 * admitted RPC or any whole case. The extra P0 lane is a setup/repeat/lifecycle prefix only;
 * its full independent account-revision oracle remains blocked by A's missing account-summary
 * authority. Full READ_ONLY and exact refusal/lifecycle bindings are also incomplete. All 12
 * original and three supplemental frozen whole cases remain NOT_RUN.
 * The retained synthetic intent is a no-replacement guard, not production recovery storage.
 */
@RunWith(AndroidJUnit4::class)
class NativeHistoricalRecipientPrefixTest {
    @Test
    fun nativeLateEnrollmentPreservesEarlierAbsenceAndRetiresBorrowedCallbacks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticHistoricalRecipient") == "true") {
            "Explicit syntheticHistoricalRecipient=true requires a disposable testing installation"
        }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        check(Build.VERSION.SDK_INT >= 31) { "Actual API31+ native security facilities are required" }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no protection fallback"
        })
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val plan = NativeRetailFixturePlan(DomainId(UUID.randomUUID().toString()),
            UtcInstant.parseCanonical(now), BusinessDate.parseCanonical(now.substring(0, 10)), protection)
        val recipientId = id(plan, "late-recipient")
        val enrollmentId = id(plan, "late-enrollment")
        val offerId = OperationId(id(plan, "offer-operation").value)
        val handoffId = id(plan, "handoff")
        val intentFile = File(application.noBackupFilesDir, "historical-recipient-prefix-intent-v1.json")
        check(!File(intentFile.path + ".bak").exists() && !File(intentFile.path + ".new").exists() &&
            intentFile.createNewFile()) { "Retained synthetic intent exists; never erase or replace unknown native work" }
        val durableIntent = AtomicFile(intentFile)
        val intent = JSONObject().put("namespace", plan.namespace.value).put("keyProtection", protection.name)
            .put("evidenceTime", now).put("businessDate", plan.businessDate.toString())
            .put("orderId", plan.orderId.value).put("recipientId", recipientId.value)
            .put("enrollmentId", enrollmentId.value).put("offerOperationId", offerId.value)
            .put("handoffId", handoffId.value).put("status", "PREPARATION_PENDING")
            .put("requests", JSONArray()).put("wholeCases", "NOT_RUN")
        fun retain() {
            val bytes = intent.toString().encodeToByteArray()
            val stream = durableIntent.startWrite()
            try {
                stream.write(bytes)
                durableIntent.finishWrite(stream)
            } catch (failure: Throwable) {
                durableIntent.failWrite(stream)
                throw failure
            } finally { bytes.fill(0) }
        }
        fun stage(value: String) { intent.put("status", value); retain() }
        retain() // Namespace/original setup intent precedes every native acquisition.

        val preparation = NativeRetailFixturePreparation(application, plan)
        val random = SecureRandom()
        val installerSecret = secret(random)
        val headSecret = secret(random)
        val passwordA = secret(random)
        val passwordB = secret(random)
        val recipientPassword = secret(random)
        var context: SelfOwnedLocalSecurityContext? = null
        var master: AndroidSelfOwnedMasterService? = null
        var local: AndroidOriginalLocalClient? = null
        var tokens: LocalSessionTokens? = null
        var masterDeliveryUnconfirmed = false
        var masterCloseAttempted = false
        var revocationUnconfirmed = false
        val cleanupFailures = mutableListOf<Throwable>()
        var primaryFailure: Throwable? = null

        suspend fun closeCurrent(): Boolean = withContext(NonCancellable) {
            var closed = !masterDeliveryUnconfirmed
            val current = tokens
            if (current != null) {
                revocationUnconfirmed = true
                try {
                    val original = checkNotNull(local)
                    val result = original.client.logout(LocalGatewayCredentials(original.peer.connectionId, current.access))
                    check(result == LocalSignOutResult.Revoked) { "Actual employee revocation remains unconfirmed" }
                    revocationUnconfirmed = false
                } catch (failure: Throwable) { cleanupFailures += failure; closed = false }
                finally { current.destroy(); tokens = null }
            }
            val actual = master
            if (actual != null) {
                if (masterCloseAttempted) closed = false
                else {
                    masterCloseAttempted = true
                    try {
                        if (actual.quiesceAndClose()) { master = null; local = null; context = null }
                        else closed = false
                    } catch (failure: Throwable) { cleanupFailures += failure; closed = false }
                }
            } else {
                val owned = context
                if (owned != null) {
                    try { owned.close(); context = null }
                    catch (failure: Throwable) { cleanupFailures += failure; closed = false }
                    // A stale context's inert close cannot certify an undelivered Master.
                }
            }
            closed && !revocationUnconfirmed
        }

        try {
            val actual = preparation.prepareAllocation(installerSecret, headSecret)
            val coordinates = actual.coordinates
            intent.put("branchId", coordinates.scope.branchId.value).put("actorA", coordinates.firstActorId.value)
                .put("actorB", plan.actorB.value)
            stage("ORIGINAL_INSTALLATION_PENDING")
            var installed = preparation.installOriginal(headSecret, passwordA)
            if (installed == LocalMasterInstallationOutcome.AcknowledgementPending)
                installed = preparation.resumeOriginalAcknowledgement()
            check(installed is LocalMasterInstallationOutcome.Installed) { "Actual original installation did not finish" }
            val installedReceipt = installed.receipt
            stage("BASELINE_SOURCES_PENDING")
            preparation.openBaselineSources(headSecret, passwordB)
            var signedInActor: DomainId? = null
            var createCut: NativeRecipientRecordedCut? = null
            while (preparation.nextCommandKind != null) {
                val requiredActor = checkNotNull(preparation.requiredActorForNextCommand)
                if (signedInActor != requiredActor) {
                    preparation.signOutCurrent()
                    if (requiredActor == coordinates.firstActorId) preparation.signInA(passwordA)
                    else preparation.signInB(passwordB)
                    signedInActor = requiredActor
                }
                val attempt = preparation.prepareNextCommand()
                val requestIntent = requestIntent(attempt.request).put("step", attempt.step.name)
                    .put("actorId", attempt.identity.originalActorId.value).put("sessionId", attempt.sessionId.value)
                    .put("policyRevision", attempt.policyRevision.value).put("assignmentId", attempt.assignmentId.value)
                    .put("semanticDigest", attempt.identity.semanticSha256).put("status", "DISPATCH_PENDING")
                intent.getJSONArray("requests").put(requestIntent)
                retain() // The exact immutable business request is retained before dispatch.
                val reply = preparation.dispatchPrepared(attempt)
                retainReply(requestIntent, reply)
                retain()
                if (attempt.step == NativeRetailFixtureCommand.CREATE_ORDER_R) createCut = NativeRecipientRecordedCut(
                    attempt.request, coordinates.scope, attempt.identity.originalActorId, attempt.sessionId,
                    attempt.policyRevision, attempt.assignmentId, reply)
            }
            val baseline = preparation.verifyBCashAndClose(passwordB)
            assertEquals("Actual reused native fixture closed", NativeRetailFixturePhase.BASELINE_PREPARED_CLOSED, baseline.phase)
            stage("BASELINE_CLOSED")
            val created = checkNotNull(createCut)

            suspend fun openObserved(cuts: List<NativeRecipientRecordedCut>, newlyEnrolledAt: OperationId? = null,
                newlyEnrolledDefinition: LocalAccountProvisioning? = null,
                oldCallbacks: List<RuntimeHistoricalCommand> = emptyList()): NativeHistoricalRecipientObserver {
                check(master == null && context == null && !masterDeliveryUnconfirmed && !revocationUnconfirmed)
                stage("ORIGINAL_NATIVE_REOPEN_PENDING")
                val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
                val originalContext = (opened as? SelfOwnedNativeOpenResult.Opened)?.context
                    ?: error("Actual original native context is unavailable")
                context = originalContext // Retain the returned owner before any further suspension.
                check(sameCoordinates(coordinates, originalContext.materialAtOpen.coordinates))
                val publications = nativeValue(originalContext.readOriginalBusinessPublications())
                val purchasingTrust = when (val read = originalContext.readOriginalPurchasingPolicies()) {
                    is LocalCompanyAuthorityResult.Value -> read.value.trust
                    is LocalCompanyAuthorityResult.Unavailable -> null // Same production fail-closed missing-trust input.
                }
                val commerce = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
                    UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity,
                    UnavailableSalesPhysicalRecordingCapacityV2, purchasingPolicyTrust = purchasingTrust)
                // Only the real registry producer is wrapped. No accepting physical gate is
                // supplied: this prefix contains no physical observations/payment recording.
                val observer = NativeHistoricalRecipientObserver(commerce.historyProducer, plan.orderId, recipientId, cuts,
                    newlyEnrolledAt, newlyEnrolledDefinition, oldCallbacks)
                stage("COMPLETE_NATIVE_SCAN_PENDING")
                masterDeliveryUnconfirmed = true
                val result = AndroidSelfOwnedMasterService.fromOriginalContext(application, originalContext,
                    commerce.commands, commerce.reads, nativeOriginalImports = commerce.nativeOriginalImports,
                    stockProjection = commerce.stockProjection, historyProducer = observer)
                val service = (result as? AndroidSelfOwnedMasterOpenResult.Opened)?.service
                    ?: error("Actual complete native-history Master is unavailable")
                master = service; context = null; masterDeliveryUnconfirmed = false; masterCloseAttempted = false
                check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Whole native startup remains held" }
                val client = service.originalLocalClient().also { local = it }
                check(client.isCurrent() && client.belongsTo(application) && sameCoordinates(coordinates, client.material.coordinates) &&
                    client.receipt.digest == installedReceipt.digest)
                observer.requireObservedOriginals()
                observer.requireRetainedQueriesRefused() // HREC-009: callback returned, owner still open.
                return observer
            }

            val firstScan = openObserved(listOf(created))
            val enrollmentClient = checkNotNull(local)
            val definition = requestedRecipient(coordinates, recipientId, plan.orderId)
            // Complete explicit constructor inputs, plus the native full-definition digest
            // returned below, are retained without credentials. This is not an authority DTO.
            intent.put("lateRecipientInput", recipientInput(definition, enrollmentId))
            stage("LATE_NATIVE_ENROLLMENT_PREPARATION_PENDING")
            val action = nativeValue(enrollmentClient.prepareEmployeeEnrollment(enrollmentId, definition, credentialWasProvided = true))
            check(action.requested.request.requestId == enrollmentId && action.requested.request.targetId == recipientId &&
                action.requested.credentialWasProvided)
            requireSameDefinition(definition, action.requested.definition)
            val requestedWire = action.requested.copyWireBytes()
            try { intent.put("lateEnrollmentRequestedDefinitionBase64", Base64.encodeToString(requestedWire, Base64.NO_WRAP)) }
            finally { requestedWire.fill(0) }
            intent.put("lateEnrollmentActionId", action.originalActionId.value)
                .put("lateEnrollmentDefinitionDigest", action.requested.definitionDigest.value)
            stage("LATE_NATIVE_APPROVAL_PENDING")
            val enrollment = nativeValue(ownedSecret(headSecret) {
                enrollmentClient.approveEmployeeEnrollment(action, plan.headLogin, it)
            })
            check(enrollment.belongsTo(enrollmentClient) && !enrollment.isLookupOnly && enrollment.originalActionId == action.originalActionId &&
                enrollment.originalRequestId == enrollmentId && enrollment.requested.definitionDigest == action.requested.definitionDigest)
            stage("LATE_NATIVE_PROVISION_PENDING")
            val provisioned = ownedSecret(recipientPassword) { enrollment.provisionAccount(enrollmentId, definition, it) }
            check(provisioned is LocalCredentialAdministrationResult.Applied) { "Actual late native enrollment did not commit" }
            val lookup = enrollment.lookupAccountProvisioning(enrollmentId, definition, credentialWasProvided = true)
            check(lookup is LocalProvisioningStatus.Applied && lookup.eventId == provisioned.eventId)
            intent.put("lateEnrollmentEventId", provisioned.eventId.value)
            stage("LATE_NATIVE_ENROLLMENT_APPLIED")
            // The extra recipient is deliberately NEVER signed in. Its P0 definition is not
            // A7/P4, and existing B alone supplies the Offer's business custodian/work rows.
            assertTrue("Actual first native owner must close after enrollment", closeCurrent())
            firstScan.requireRetainedQueriesRefused()
            stage("FIRST_OBSERVED_OWNER_CLOSED_AFTER_ENROLLMENT")
            // HREC-PREFIX-V1-001 P4: a new complete scan, not the earlier retained callback.
            val secondScan = openObserved(listOf(created), oldCallbacks = firstScan.retainedCommands())

            suspend fun signInA() {
                check(tokens == null && !revocationUnconfirmed)
                stage("A_SIGN_IN_PENDING")
                val client = checkNotNull(local)
                val result = ownedSecret(passwordA) { client.client.signIn(client.peer.connectionId, plan.loginA, it) }
                val returned = (result as? LocalAuthenticationResult.Authenticated)?.tokens
                    ?: error("Actual A authentication is unavailable")
                tokens = returned // Retain genuine tokens before validating their public view.
                check(returned.view.actorId == coordinates.firstActorId && returned.view.login == plan.loginA &&
                    returned.view.scope == coordinates.scope && returned.view.deviceId == client.peer.deviceId)
            }
            suspend fun readOrder(): LocalProfiledOrderV2 {
                val client = checkNotNull(local)
                val current = checkNotNull(tokens)
                val reply = client.client.read(LocalGatewayCredentials(client.peer.connectionId, current.access),
                    LocalOrderReadCodecV2.request(LocalOrderQueryV1.Order(plan.orderId))) as? ReadReply.Data
                    ?: error("Actual protected order read is unavailable")
                val page = LocalOrderReadCodecV2.decode(reply.payload)
                check(page.nextAfter == null && page.orders.size == 1)
                return page.orders.single()
            }
            signInA()
            val beforeOffer = readOrder()
            assertEquals("Partial READ_ONLY: native scan/enrollment does not change recorded order", baseline.orderR, beforeOffer)
            val current = checkNotNull(tokens)
            val request = LocalCollectionStockCodecV2.command(offerId, LocalCollectionStockRequestV2.OfferHandoff(
                plan.orderId, beforeOffer.order.revision, handoffId, plan.actorB,
                LocalCashEvidenceV1(id(plan, "offer-evidence"), plan.evidenceTime)))
            val offerIntent = requestIntent(request).put("actorId", current.view.actorId.value)
                .put("sessionId", current.view.sessionId.value).put("policyRevision", current.view.policy.revision.value)
                .put("semanticDigest", semanticDigest(coordinates.scope, current.view.actorId, request.command, request.payload).value)
                .put("status", "DISPATCH_PENDING")
            intent.getJSONArray("requests").put(offerIntent)
            retain()
            val offerClient = checkNotNull(local) // The post-enrollment owner, never the retired enrollment client.
            val offer = offerClient.client.execute(LocalGatewayCredentials(offerClient.peer.connectionId, current.access), request)
                as? MutationReply.Recorded ?: error("Actual Offer outcome is unresolved")
            retainReply(offerIntent, offer); retain()
            check(offer.committed && !offer.replayed && offer.operationId == request.operationId &&
                offer.originalActorId == current.view.actorId && offer.authorityEpoch == offerClient.receipt.core.authorityEpoch)
            val result = LocalSalesResultCodec.decode(offer.result) as? LocalSalesResultV1.Applied
                ?: error("Actual Offer lacks its applied result")
            check(result.action == LocalSalesAction.HANDOFF_OFFERED && result.documentId == plan.orderId)
            val offered = NativeRecipientRecordedCut(request, coordinates.scope, current.view.actorId, current.view.sessionId,
                current.view.policy.revision, coordinates.assignmentId, offer)
            val afterOffer = readOrder()
            assertTrue("Actual second native owner must close after Offer", closeCurrent())
            secondScan.requireRetainedQueriesRefused()
            firstScan.requireRetainedQueriesRefused() // HREC-010: actual normal close retires old authority.
            stage("SECOND_OBSERVED_OWNER_CLOSED_AFTER_OFFER")

            val thirdScan = openObserved(listOf(created, offered),
                newlyEnrolledAt = request.operationId, newlyEnrolledDefinition = definition,
                oldCallbacks = firstScan.retainedCommands() + secondScan.retainedCommands())
            signInA()
            assertEquals("Partial READ_ONLY: new complete scan preserves the recorded offered order", afterOffer, readOrder())
            // Known committed original only: this is an explicit replay, never an unknown
            // retry/new operation. Preserve the whole original request before this dispatch.
            offerIntent.put("status", "KNOWN_RECORDED_REPLAY_PENDING"); retain()
            val reopenedLocal = checkNotNull(local)
            val replay = reopenedLocal.client.execute(LocalGatewayCredentials(reopenedLocal.peer.connectionId,
                checkNotNull(tokens).access), request) as? MutationReply.Recorded
                ?: error("Known-original replay is unresolved")
            assertTrue("Replay stays the same committed original", replay.committed && replay.replayed)
            assertEquals("Replay operation", offer.operationId, replay.operationId)
            assertEquals("Replay original actor", offer.originalActorId, replay.originalActorId)
            assertEquals("Replay original epoch", offer.authorityEpoch, replay.authorityEpoch)
            assertEquals("Replay original sequence", offer.sequence, replay.sequence)
            assertEquals("Replay original timestamp", offer.recordedAt, replay.recordedAt)
            assertEquals("Replay whole original result", offer.result, replay.result)
            assertEquals("Partial READ_ONLY: known replay creates no new order effect", afterOffer, readOrder())
            offerIntent.put("status", "KNOWN_RECORDED_REPLAY_RETURNED"); retain()
            assertTrue("Actual third native owner must close", closeCurrent())
            thirdScan.requireRetainedQueriesRefused()
            secondScan.requireRetainedQueriesRefused()
            firstScan.requireRetainedQueriesRefused()
            stage("THIRD_OBSERVED_OWNER_CLOSED")
            val oldOffer = thirdScan.retainedCommands().single { it.operationId == request.operationId }
            // HREC-PREFIX-V1-003 LATER-CALLBACK: the exact old Offer is probed inside the
            // later genuine Offer callback. The delegate still joins every native command;
            // selecting only Offer here adds no primary CREATE observation to the six.
            val laterScan = openObserved(listOf(offered),
                newlyEnrolledAt = request.operationId, newlyEnrolledDefinition = definition,
                oldCallbacks = listOf(oldOffer))
            assertTrue("Actual fourth native owner must close", closeCurrent())
            laterScan.requireRetainedQueriesRefused()
            thirdScan.requireRetainedQueriesRefused()
            secondScan.requireRetainedQueriesRefused()
            firstScan.requireRetainedQueriesRefused()
            assertFalse("The partial host must not replace uncertainty with successful cleanup", revocationUnconfirmed)
            stage("PARTIAL_NATIVE_PREFIX_RETURNED_WHOLE_CASES_NOT_RUN")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            installerSecret.fill('\u0000'); headSecret.fill('\u0000'); passwordA.fill('\u0000')
            passwordB.fill('\u0000'); recipientPassword.fill('\u0000')
            val currentClosed = closeCurrent()
            val preparationClosed = try { preparation.close() }
                catch (failure: Throwable) { cleanupFailures += failure; false }
            val closed = currentClosed && preparationClosed && !masterDeliveryUnconfirmed && !revocationUnconfirmed
            intent.put("cleanupReportedClosed", closed)
                .put("nativeCloseConfirmed", if (primaryFailure == null) closed else JSONObject.NULL)
            if (primaryFailure != null || !closed) intent.put("status", "HELD")
            try { retain() } catch (failure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
            }
            for (failure in cleanupFailures) primaryFailure?.addSuppressed(failure)
            if (!closed) {
                val failure = AssertionError("Actual native close/revocation remains unconfirmed; retain original synthetic intent")
                if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
            }
        }
    }

    private fun requestedRecipient(coordinates: SelfOwnedAllocationCoordinatesV1, actor: DomainId,
        order: DomainId): LocalAccountProvisioning = LocalAccountProvisioning(actor, LocalLoginIdentifier("b.original"),
        ActivatedLocalPolicy(coordinates.scope, actor, Revision(0), listOf(PermissionGrant(
            Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM), coordinates.scope, GrantEffect.ALLOW,
            PermissionLimits(), OwnerAccess.OWN_ONLY, actor, setOf(order), null)),
            LocalSessionPolicy(maximumConcurrentSessions = 1),
            ThrottlePolicy(3, 1_000, 900_000, 120_000), approvalPolicy = null),
        LocalTokenPolicy(1_800_000, 28_800_000), setOf(coordinates.deviceId))

    private fun recipientInput(definition: LocalAccountProvisioning, requestId: DomainId): JSONObject = JSONObject()
        .put("requestId", requestId.value).put("actorId", definition.actorId.value).put("login", definition.login.value)
        .put("organizationId", definition.policy.scope.organizationId.value).put("companyId", definition.policy.scope.companyId.value)
        .put("branchId", definition.policy.scope.branchId.value).put("policyRevision", definition.policy.revision.value)
        .put("credentialWasProvided", true).put("admittedDeviceIds", JSONArray(definition.admittedDeviceIds.map { it.value }))
        .put("grant", JSONObject().put("action", "ORDER_HANDOFF").put("verb", "PERFORM").put("effect", "ALLOW")
            .put("ownerAccess", "OWN_ONLY").put("namedSubjectId", definition.actorId.value)
            .put("targetIds", JSONArray(definition.policy.grants.single().targetIds.orEmpty().map { it.value }))
            .put("limits", "PermissionLimits()").put("delegationCeiling", JSONObject.NULL))
        .put("sessionPolicy", "LocalSessionPolicy(maximumConcurrentSessions=1)")
        .put("throttlePolicy", "ThrottlePolicy(3,1000,900000,120000)").put("approvalPolicy", JSONObject.NULL)
        .put("tokenPolicy", "LocalTokenPolicy(1800000,28800000)")

    private fun requireSameDefinition(expected: LocalAccountProvisioning, actual: LocalAccountProvisioning) {
        check(expected.actorId == actual.actorId && expected.login == actual.login && expected.tokenPolicy == actual.tokenPolicy &&
            expected.admittedDeviceIds == actual.admittedDeviceIds && expected.policy.scope == actual.policy.scope &&
            expected.policy.actorId == actual.policy.actorId && expected.policy.revision == actual.policy.revision &&
            expected.policy.sessionPolicy == actual.policy.sessionPolicy && expected.policy.throttlePolicy == actual.policy.throttlePolicy &&
            expected.policy.approvalPolicy == null && actual.policy.approvalPolicy == null)
        val a = expected.policy.grants.single()
        val b = actual.policy.grants.single()
        check(a.permission == b.permission && a.scope == b.scope && a.effect == b.effect && a.limits == b.limits &&
            a.ownerAccess == b.ownerAccess && a.namedSubjectId == b.namedSubjectId && a.targetIds == b.targetIds &&
            a.delegationCeiling == b.delegationCeiling)
    }

    private fun requestIntent(request: MutationRequest): JSONObject = JSONObject().put("operationId", request.operationId.value)
        .put("commandName", request.command.name).put("commandVersion", request.command.version)
        .put("payloadBase64", base64(request.payload)).put("actionApprovalId", request.actionApprovalId?.value ?: JSONObject.NULL)

    private fun retainReply(intent: JSONObject, reply: MutationReply.Recorded) {
        intent.put("status", "RECORDED").put("committed", reply.committed).put("replayed", reply.replayed)
            .put("originalActorId", reply.originalActorId.value).put("authorityEpoch", reply.authorityEpoch.value)
            .put("sequence", reply.sequence.value).put("recordedAt", reply.recordedAt.toString())
            .put("resultBase64", base64(reply.result))
    }

    private fun base64(value: StoredBytes): String {
        val bytes = value.copyBytes()
        return try { Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }
    }

    private fun sameCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1): Boolean =
        a.scope == b.scope && a.allocationId == b.allocationId && a.installationId == b.installationId &&
            a.assignmentId == b.assignmentId && a.deviceId == b.deviceId && a.storageInstanceId == b.storageInstanceId &&
            a.storageKeyId == b.storageKeyId && a.pepperKeyId == b.pepperKeyId &&
            a.targetSigningKeyId == b.targetSigningKeyId && a.firstActorId == b.firstActorId

    private fun <T> nativeValue(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> error("Actual native authority is unavailable")
    }

    private suspend fun <T> ownedSecret(source: CharArray, use: suspend (CharArray) -> T): T {
        val copy = source.copyOf()
        return try { use(copy) } finally { copy.fill('\u0000') }
    }

    private fun id(plan: NativeRetailFixturePlan, label: String): DomainId {
        val bytes = "historical-recipient-prefix-v1/${plan.namespace.value}/$label".encodeToByteArray()
        return try { DomainId(UUID.nameUUIDFromBytes(bytes).toString()) } finally { bytes.fill(0) }
    }

    private fun secret(random: SecureRandom): CharArray {
        val bytes = ByteArray(24).also(random::nextBytes)
        return try {
            val hex = "0123456789abcdef"
            CharArray(bytes.size * 2) { index ->
                val value = bytes[index / 2].toInt() and 0xff
                hex[if (index % 2 == 0) value ushr 4 else value and 15]
            }
        } finally { bytes.fill(0) }
    }
}
