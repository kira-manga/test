package com.ukkera.pos.android.openingobligation

import android.app.Application
import android.util.Base64
import com.ukkera.pos.app.gateway.AndroidLocalBranchRuntime
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One dedicated original native installation. No supplied host, clock, peer, verifier,
 * history observer, authorization or driver. Any unknown acquisition/revocation/close
 * keeps this owner HELD; an inert context close cannot certify an undelivered Master.
 * Every method belongs to the one host coroutine. This class is not an automatic runner.
 */
internal class NativeOpeningSupplierObligationOwner(
    private val application: Application,
    val plan: NativeOpeningSupplierPlan,
    private val record: JSONObject,
    private val retain: () -> Unit,
) {
    private var provisioner: LocalProvisioner? = null
    private var context: SelfOwnedLocalSecurityContext? = null
    private var master: AndroidSelfOwnedMasterService? = null
    private var original: AndroidOriginalLocalClient? = null
    private var tokens: LocalSessionTokens? = null
    private var acquisitionUnconfirmed = false
    private var revocationUnconfirmed = false
    private var provisionerCloseAttempted = false
    private var contextCloseAttempted = false
    private var masterCloseAttempted = false
    private var setupStarted = false
    private var enrollmentStarted = false
    private var commandStarted = false
    private var supplierConfigurationStarted = false
    private var supplierResult: MutationReply.Recorded? = null
    private var supplierStorageRevision: Revision? = null
    private var boundOriginal: NativePositiveOpeningSupplierBound? = null
    private var postingPolicyRevision: Revision? = null
    private var coldOpenCount = 0
    private var allocated: SelfOwnedNativeMaterialView? = null
    private var receipt: LocalBranchInstallationReceiptV2? = null
    private var enrollmentActionId: DomainId? = null
    private var identity: OperationIdentity? = null
    private var firstResult: MutationReply.Recorded? = null
    private var retiredSessionId: DomainId? = null
    private val cleanupFailures = mutableListOf<Throwable>()
    var held: Boolean = false
        private set
    var step: String = "NEW"
        private set
    val coordinates: SelfOwnedAllocationCoordinatesV1 get() = checkNotNull(allocated).coordinates

    fun hold() { held = true }
    fun takeCleanupFailures(): List<Throwable> = cleanupFailures.toList().also { cleanupFailures.clear() }
    private fun stage(value: String) { step = value; record.put("status", value); retain() }

    suspend fun prepareAndInstall(installerSecret: CharArray, headSecret: CharArray, founderPassword: CharArray) {
        check(!setupStarted && !held)
        setupStarted = true
        stage("PROVISIONER_ACQUISITION_PENDING")
        acquisitionUnconfirmed = true
        val opened = AndroidSelfOwnedEnrollmentSource.open(application)
        val actual = (opened as? LocalProvisionerOpenResult.Opened)?.provisioner
            ?: error("Genuine native provisioner is unavailable")
        provisioner = actual; acquisitionUnconfirmed = false
        stage("COMPANY_HEAD_PREPARATION_PENDING")
        val founding = nativeValue(actual.companyAuthority.prepareNewCompanyAndHead(plan.founding))
        check(!founding.enrollmentRetained) { "Existing or unknown company enrollment cannot become a fresh fixture" }
        record.put("organizationId", founding.organizationId.value).put("companyId", founding.companyId.value)
        stage("COMPANY_HEAD_COMPLETION_PENDING")
        val company = ownedSecret(installerSecret) { installer -> ownedSecret(headSecret) { head ->
            nativeValue(actual.companyAuthority.completeNewCompanyAndHead(founding, installer, head)) } }
        check(company.organizationId == founding.organizationId && company.companyId == founding.companyId &&
            company.rootFingerprint == founding.rootFingerprint && company.headEnabled &&
            company.policyIssuanceEnabled && company.installationIssuanceEnabled)
        record.put("actualCompanyRootFingerprint", company.rootFingerprint)
        stage("ORIGINAL_BRANCH_PREPARATION_PENDING")
        val branch = actual.prepareBranchWithEnrolledCompany(SelfOwnedBranchFactsV1(company.rootFingerprint,
            plan.branchName, plan.protection))
        val action = (branch as? LocalProvisionerPreparation.Prepared)?.action
            ?: error("Genuine original branch preparation is unavailable")
        stage("ORIGINAL_BRANCH_ISSUANCE_PENDING")
        val grant = ownedSecret(installerSecret) { actual.issueOriginalPreparation(action, plan.installerLogin, it) }
        val packet = (grant as? LocalProvisionerGrant.Retained)?.packet
            ?: error("Original native branch issuance is unresolved")
        check(packet.coordinates.scope.company() == CompanyScope(company.organizationId, company.companyId))
        check(closeProvisioner()) { "Original provisioner closure is unconfirmed" }

        acquireContext(preparing = true)
        allocated = checkNotNull(context).materialAtOpen
        check(sameCoordinates(coordinates, packet.coordinates))
        record.put("branchId", coordinates.scope.branchId.value).put("firstActorId", coordinates.firstActorId.value)
            .put("deviceId", coordinates.deviceId.value).put("installationId", coordinates.installationId.value)
            .put("allocationId", coordinates.allocationId.value).put("assignmentId", coordinates.assignmentId.value)
        check(closeContext()) { "Original preparation context closure is unconfirmed" }
        val account = plan.founder(coordinates)
        check(account.policy.grants.isEmpty())
        record.put("requestedFounder", retainedOpeningAccount(account))
        acquireContext(preparing = false)
        acquireMaster(installationOnly = true)
        val installing = checkNotNull(master)
        check(installing.mode.value == LocalMasterLifecycleMode.ALLOCATION_ONLY)
        stage("ORIGINAL_SETUP_READ_PENDING")
        val setup = installing.readOriginalSetup()
        check(setup.definition == null && setup.receipt == null)
        stage("ORIGINAL_DEFINITION_PREPARATION_PENDING")
        val definition = installing.prepareOriginalDefinition(account, plan.authorityEpoch)
        requireSameOpeningAccount(account, definition.definition.firstAccount)
        check(definition.definition.authorityEpoch == plan.authorityEpoch)
        record.put("originalInstallationRequestId", definition.definition.originalRequestId.value)
            .put("originalInstallationDefinitionDigest", definition.definitionDigest.value)
        stage("ORIGINAL_INSTALLATION_PENDING")
        var outcome = ownedSecret(headSecret) { head -> ownedSecret(founderPassword) { employee ->
            installing.installOriginal(definition.definition, plan.headLogin, head, employee) } }
        if (outcome == LocalMasterInstallationOutcome.AcknowledgementPending) {
            // Only a positively returned original ACK-pending outcome permits this one resume.
            check(close()) { "Installation owner closure before ACK is unconfirmed" }
            acquireContext(preparing = false)
            acquireMaster(installationOnly = true)
            check(checkNotNull(master).mode.value == LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
            stage("ORIGINAL_ACK_RESUME_PENDING")
            outcome = checkNotNull(master).resumeOriginalAcknowledgement()
        }
        val installed = outcome as? LocalMasterInstallationOutcome.Installed
            ?: error("Original native installation did not finish")
        receipt = installed.receipt
        check(installed.receipt.core.originalRequestId == definition.definition.originalRequestId &&
            installed.receipt.core.definitionDigest == definition.definitionDigest)
        record.put("originalInstallationReceiptDigest", installed.receipt.digest.value)
        check(close()) { "Installed native owner closure is unconfirmed" }
        stage("ORIGINAL_INSTALLATION_CLOSED")
    }

    suspend fun openOperational() {
        check(receipt != null && master == null && context == null && provisioner == null &&
            !acquisitionUnconfirmed && !revocationUnconfirmed && !held)
        acquireContext(preparing = false)
        acquireMaster(installationOnly = false)
        val actual = checkNotNull(master)
        check(actual.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Genuine full native history admission remains held" }
        stage("ORIGINAL_LOCAL_CLIENT_ACQUISITION_PENDING")
        val local = actual.originalLocalClient().also { original = it }
        check(local.isCurrent() && local.belongsTo(application) && sameCoordinates(local.material.coordinates, coordinates) &&
            local.receipt.digest == checkNotNull(receipt).digest &&
            LocalOpeningSupplierObligationProfile.COMMAND in local.commandKeys &&
            LocalOpeningSupplierObligationProfile.READ in local.readKeys && LocalConfigurationProfile.QUERY in local.readKeys)
        coldOpenCount++
        record.put("operationalOpenCount", coldOpenCount).put("serviceIncarnationId", local.serviceIncarnationId.value)
        stage("ORIGINAL_OPERATIONAL_OWNER_OPENED")
    }

    suspend fun enrollOperator(headSecret: CharArray, operatorPassword: CharArray) {
        check(!enrollmentStarted && !held && coldOpenCount == 1 && tokens == null)
        enrollmentStarted = true
        val local = checkNotNull(original)
        val account = plan.operator(coordinates)
        record.put("requestedOperator", retainedOpeningAccount(account))
        stage("OPERATOR_ENROLLMENT_PREPARATION_PENDING")
        val action = nativeValue(local.prepareEmployeeEnrollment(plan.enrollmentRequestId, account, credentialWasProvided = true))
        check(action.requested.request.requestId == plan.enrollmentRequestId &&
            action.requested.request.targetId == plan.operatorId && action.requested.credentialWasProvided)
        requireSameOpeningAccount(account, action.requested.definition)
        enrollmentActionId = action.originalActionId
        val requested = action.requested.copyWireBytes()
        try { record.put("operatorOriginalActionId", action.originalActionId.value)
            .put("operatorDefinitionDigest", action.requested.definitionDigest.value)
            .put("operatorRequestedDefinitionBase64", Base64.encodeToString(requested, Base64.NO_WRAP)) }
        finally { requested.fill(0) }
        stage("OPERATOR_HEAD_APPROVAL_PENDING")
        val enrollment = ownedSecret(headSecret) { nativeValue(local.approveEmployeeEnrollment(action, plan.headLogin, it)) }
        check(enrollment.belongsTo(local) && !enrollment.isLookupOnly && enrollment.originalActionId == action.originalActionId &&
            enrollment.originalRequestId == plan.enrollmentRequestId && enrollment.requested.definitionDigest == action.requested.definitionDigest)
        stage("OPERATOR_NATIVE_PROVISION_PENDING")
        val provisioned = ownedSecret(operatorPassword) { enrollment.provisionAccount(plan.enrollmentRequestId, account, it) }
        val applied = provisioned as? LocalCredentialAdministrationResult.Applied
            ?: error("Genuine operator enrollment is unresolved")
        record.put("operatorEnrollmentEventId", applied.eventId.value)
        stage("OPERATOR_ORIGINAL_PROVISION_LOOKUP_PENDING")
        val lookup = enrollment.lookupAccountProvisioning(plan.enrollmentRequestId, account, credentialWasProvided = true)
        check(lookup is LocalProvisioningStatus.Applied && lookup.eventId == applied.eventId)
        check(close()) { "Native owner closure after employee consumption is unconfirmed" }
        check(!local.isCurrent() && !enrollment.isCurrent())
        stage("OPERATOR_ENROLLED_ORIGINAL_OWNER_CLOSED")
    }

    suspend fun requireColdConsumedOperator() {
        check(coldOpenCount >= 2 && !held)
        val local = checkNotNull(original)
        stage("COLD_CONSUMED_OPERATOR_READ_PENDING")
        val witnesses = nativeValue(local.originalConsumedPhysicalEmployees())
        val witness = witnesses.single { it.actorId == plan.operatorId && it.originalRequestId == plan.enrollmentRequestId }
        check(witness.originalActionId == enrollmentActionId && witness.scope == coordinates.scope &&
            witness.deviceId == coordinates.deviceId && witness.installationId == coordinates.installationId &&
            witness.firstActorId == coordinates.firstActorId && witness.actorId != witness.firstActorId &&
            witness.receiptDigest == checkNotNull(receipt).digest && witness.belongsToOriginalOwner(local))
        record.put("coldConsumedOperatorId", witness.actorId.value)
            .put("coldConsumedEnrollmentSequence", witness.enrollmentSequence.value.toString())
            .put("nativeActorAliasBindings", JSONObject().put("authorizedActorA", witness.actorId.value)
                .put("unprivilegedFoundingAccount", witness.firstActorId.value))
        stage("COLD_OPERATOR_CONSUMPTION_OBSERVED")
        // This genuine immutable census is NOT current permission; sign-in below is separate.
    }

    suspend fun signInOperator(password: CharArray) {
        check(tokens == null && !revocationUnconfirmed && !held && coldOpenCount >= 2)
        val local = checkNotNull(original)
        check(local.isCurrent() && local.peer.isCurrent())
        stage("OPERATOR_FRESH_AUTHENTICATION_PENDING")
        val result = ownedSecret(password) { local.client.signIn(local.peer.connectionId, plan.operatorLogin, it) }
        val authenticated = result as? LocalAuthenticationResult.Authenticated
            ?: error("Genuine operator authentication is unavailable")
        val current = authenticated.tokens.also { tokens = it }
        check(current.view.actorId == plan.operatorId && current.view.actorId != coordinates.firstActorId &&
            current.view.login == plan.operatorLogin && current.view.scope == coordinates.scope && current.view.deviceId == local.peer.deviceId &&
            (retiredSessionId == null || current.view.sessionId != retiredSessionId))
        requireSameOpeningPolicy(plan.operator(coordinates).policy, current.view.policy)
        record.put("currentOperatorSessionId", current.view.sessionId.value)
            .put("currentOperatorPolicyRevision", current.view.policy.revision.value.toString())
        stage("OPERATOR_FRESH_AUTHENTICATION_OBSERVED")
    }

    suspend fun requireAbsentSupplier() {
        stage("ABSENT_SUPPLIER_READ_PENDING")
        val query = LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, id = plan.original.value.supplierId)
        val result = read(ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(query)))
        val page = LocalConfigurationCodec.decodePage(result.payload)
        check(page.kind == LocalConfigurationProfile.SUPPLIER && page.items.isEmpty() && page.detail == null && page.nextAfter == null)
        stage("GENUINE_SUPPLIER_ABSENCE_OBSERVED")
    }

    suspend fun requireEmptyOpeningRead(label: String) {
        stage("${label}_OPENING_READ_PENDING")
        val query = LocalOpeningSupplierObligationQueryV1()
        val result = read(ReadRequest(LocalOpeningSupplierObligationProfile.READ, LocalOpeningSupplierObligationCodec.query(query)))
        val page = LocalOpeningSupplierObligationCodec.decodePage(result.payload)
        check(page.query == query && page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE &&
            page.entries.isEmpty() && page.nextAfter == null) { "Unknown/capacity opening read cannot establish zero debt" }
        record.put("${label}_openingReadRevision", result.branchRevision.value.toString())
        stage("${label}_EMPTY_OPENING_READ_OBSERVED")
    }

    /** One new opening command only. No positive supplier, stock, cash or other business setup. */
    suspend fun refuseOriginal(): MutationReply.Recorded {
        check(!commandStarted && !held && coldOpenCount == 2)
        val current = currentTokens()
        val local = checkNotNull(original)
        val request = plan.original.request
        identity = OperationIdentity(coordinates.scope.branchId, request.operationId, current.view.actorId, 1,
            semanticDigest(coordinates.scope, current.view.actorId, request.command, request.payload).value)
        record.put("originalActorId", current.view.actorId.value).put("originalSessionId", current.view.sessionId.value)
            .put("originalPolicyRevision", current.view.policy.revision.value.toString())
            .put("originalSemanticDigest", checkNotNull(identity).semanticSha256)
        stage("ONE_ORIGINAL_OPENING_DISPATCH_PENDING")
        commandStarted = true
        val outcome = local.client.execute(credentials(), request)
        val recorded = outcome as? MutationReply.Recorded ?: error("Original opening outcome is unresolved")
        firstResult = recorded // Retain the returned result before any assertion/decoding.
        record.put("originalOutcome", retainedResult(recorded))
        stage("ORIGINAL_RECORDED_OUTCOME_DELIVERED")
        requireRefusal(recorded)
        check(!recorded.replayed)
        stage("ORIGINAL_NONPOSTING_REFUSAL_OBSERVED")
        return recorded
    }

    /** Earlier-outcome lookup only: never a new ID, password dispatch, form or business retry. */
    suspend fun recoverOriginal(): MutationReply.Recorded {
        check(!held && coldOpenCount == 3 && firstResult != null)
        currentTokens()
        stage("FRESH_AUTHORIZED_ORIGINAL_LOOKUP_PENDING")
        val lookup = checkNotNull(original).client.lookup(credentials(), checkNotNull(identity))
        val returned = (lookup as? LookupReply.Found)?.result ?: error("Original native refusal recovery is unresolved")
        requireRefusal(returned)
        val before = checkNotNull(firstResult)
        check(returned.operationId == before.operationId && returned.originalActorId == before.originalActorId &&
            returned.committed == before.committed && returned.code == before.code && returned.result == before.result &&
            returned.authorityEpoch == before.authorityEpoch && returned.sequence == before.sequence && returned.recordedAt == before.recordedAt)
        record.put("recoveredOutcome", retainedResult(returned))
        stage("SAME_ORIGINAL_NATIVE_REFUSAL_RECOVERED")
        return returned
    }

    /** One real delegated setup command, distinct from opening A and from employee enrollment. */
    suspend fun configureSupplier() {
        check(!supplierConfigurationStarted && !held && coldOpenCount == 2 && boundOriginal == null)
        val current = currentTokens()
        val request = positiveSetup().configuration
        check(LocalConfigurationProfile.COMMAND in checkNotNull(original).commandKeys)
        record.put("supplierConfigurationActorId", current.view.actorId.value)
            .put("supplierConfigurationPolicyRevision", current.view.policy.revision.value.toString())
            .put("supplierConfigurationSemanticDigest", semanticDigest(coordinates.scope, current.view.actorId,
                request.command, request.payload).value)
        stage("ONE_ORIGINAL_SUPPLIER_CONFIGURATION_DISPATCH_PENDING")
        supplierConfigurationStarted = true
        val outcome = checkNotNull(original).client.execute(credentials(), request)
        val recorded = outcome as? MutationReply.Recorded ?: error("Original supplier configuration outcome is unresolved")
        supplierResult = recorded // Preserve the real delivered outcome before decoding or assertions.
        record.put("supplierConfigurationOutcome", retainedResult(recorded))
        stage("ORIGINAL_SUPPLIER_CONFIGURATION_OUTCOME_DELIVERED")
        check(recorded.committed && !recorded.replayed && recorded.operationId == request.operationId &&
            recorded.originalActorId == plan.operatorId && recorded.authorityEpoch == checkNotNull(receipt).core.authorityEpoch)
        val decoded = LocalConfigurationCodec.decodeResult(recorded.result)
        check(decoded.code == "configuration.applied" && decoded.code == recorded.code &&
            decoded.id == plan.original.value.supplierId && decoded.revision == Revision(0))
        record.put("supplierConfigurationDomainRevision", checkNotNull(decoded.revision).value.toString())
        stage("GENUINE_DELEGATED_SUPPLIER_CONFIGURATION_OBSERVED")
    }

    /** Actual current read only; this is neither a supplied source stamp nor history approval. */
    suspend fun requireActiveSupplier(label: String): Revision {
        check(supplierConfigurationStarted && supplierResult != null && !held)
        stage("${label}_ACTIVE_SUPPLIER_READ_PENDING")
        val query = LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER, id = plan.original.value.supplierId)
        val result = read(ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(query)))
        val page = LocalConfigurationCodec.decodePage(result.payload)
        check(page.kind == LocalConfigurationProfile.SUPPLIER && page.nextAfter == null && page.items.size == 1)
        val item = page.items.single()
        val detail = page.detail as? LocalConfigurationDetailV1.Supplier ?: error("Actual supplier detail is unavailable")
        check(item.id == plan.original.value.supplierId && item.active == true && item.names == positiveSetup().supplier.names &&
            detail.value == positiveSetup().supplier && item.revision == Revision(0) && item.storageRevision == Revision(1))
        supplierStorageRevision?.let { check(item.storageRevision == it) }
        boundOriginal?.let { check(item.storageRevision == it.value.expectedSupplierRevision) }
        supplierStorageRevision = item.storageRevision
        record.put("${label}_supplierDomainRevision", item.revision.value.toString())
            .put("${label}_supplierStorageRevision", item.storageRevision.value.toString())
            .put("${label}_supplierReadBranchRevision", result.branchRevision.value.toString())
            .put("${label}_supplierReadResultBase64", openingRetentionBase64(result.payload.copyBytes()))
        stage("${label}_GENUINE_ACTIVE_SUPPLIER_OBSERVED")
        return item.storageRevision // Bind from this real query, never from configuration result.revision.
    }

    suspend fun bindOriginalFromProtectedSupplier() {
        check(coldOpenCount == 2 && boundOriginal == null && !commandStarted && !held)
        val observedStorageRevision = requireActiveSupplier("AFTER_CONFIGURATION")
        val bound = positiveSetup().bindSupplierRevision(observedStorageRevision)
        boundOriginal = bound
        record.put("boundOriginalInput", bound.retainedRequest())
        stage("EXACT_OPENING_ORIGINAL_BOUND_AND_RETAINED_BEFORE_DISPATCH")
    }

    /** One original positive A, only after ordinary cold admission of the actual supplier history. */
    suspend fun postPositiveOriginal(): MutationReply.Recorded {
        check(!commandStarted && !held && coldOpenCount == 3 && supplierResult != null)
        val current = currentTokens()
        val local = checkNotNull(original)
        val bound = checkNotNull(boundOriginal)
        check(bound.value.expectedSupplierRevision == supplierStorageRevision)
        val request = bound.request
        identity = OperationIdentity(coordinates.scope.branchId, request.operationId, current.view.actorId, 1,
            semanticDigest(coordinates.scope, current.view.actorId, request.command, request.payload).value)
        postingPolicyRevision = current.view.policy.revision
        record.put("originalActorId", current.view.actorId.value).put("originalSessionId", current.view.sessionId.value)
            .put("originalPolicyRevision", current.view.policy.revision.value.toString())
            .put("originalSemanticDigest", checkNotNull(identity).semanticSha256)
        stage("ONE_ORIGINAL_OPENING_DISPATCH_PENDING")
        commandStarted = true
        val outcome = local.client.execute(credentials(), request)
        val recorded = outcome as? MutationReply.Recorded ?: error("Original opening outcome is unresolved")
        firstResult = recorded // Retain the returned result before any assertion/decoding.
        record.put("originalOutcome", retainedResult(recorded))
        stage("ORIGINAL_RECORDED_OUTCOME_DELIVERED")
        requirePositivePosting(recorded)
        check(!recorded.replayed)
        stage("ORIGINAL_POSITIVE_OPENING_A_COMMIT_OBSERVED")
        return recorded
    }

    /** Frozen A supplies amount/date/reference/terms/evidence; native reply supplies only provenance. */
    suspend fun requirePositiveOpeningReads(label: String) {
        check(firstResult != null && boundOriginal != null && !held)
        val expected = plan.original
        val queries = listOf(
            "BY_ID" to LocalOpeningSupplierObligationQueryV1(id = expected.value.obligationId),
            "SUPPLIER_LIST" to LocalOpeningSupplierObligationQueryV1(supplierId = expected.value.supplierId),
            "BRANCH_LIST" to LocalOpeningSupplierObligationQueryV1())
        for ((kind, query) in queries) {
            stage("${label}_${kind}_OPENING_READ_PENDING")
            val result = read(ReadRequest(LocalOpeningSupplierObligationProfile.READ, LocalOpeningSupplierObligationCodec.query(query)))
            val page = LocalOpeningSupplierObligationCodec.decodePage(result.payload)
            check(page.query == query && page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE &&
                page.entries.size == 1 && page.nextAfter == null) { "Unknown/partial opening projection cannot establish A's balance" }
            requirePositiveOpeningView(page.entries.single())
            check(page.entries.sumOf { it.amount.minor } == expected.value.amount.minor)
            record.put("${label}_${kind}_openingDueMinor", page.entries.sumOf { it.amount.minor }.toString())
                .put("${label}_${kind}_openingReadBranchRevision", result.branchRevision.value.toString())
                .put("${label}_${kind}_openingReadResultBase64", openingRetentionBase64(result.payload.copyBytes()))
            stage("${label}_${kind}_FROZEN_OPENING_A_OBSERVED")
        }
    }

    /** Earlier-outcome lookup only: never a new ID, password dispatch, form or business retry. */
    suspend fun recoverPositiveOriginal(): MutationReply.Recorded {
        check(!held && coldOpenCount == 4 && firstResult != null)
        currentTokens()
        stage("FRESH_AUTHORIZED_ORIGINAL_LOOKUP_PENDING")
        val lookup = checkNotNull(original).client.lookup(credentials(), checkNotNull(identity))
        val returned = (lookup as? LookupReply.Found)?.result ?: error("Original native positive A recovery is unresolved")
        requirePositivePosting(returned)
        val before = checkNotNull(firstResult)
        check(returned.operationId == before.operationId && returned.originalActorId == before.originalActorId &&
            returned.committed == before.committed && returned.code == before.code && returned.result == before.result &&
            returned.authorityEpoch == before.authorityEpoch && returned.sequence == before.sequence && returned.recordedAt == before.recordedAt)
        record.put("recoveredOutcome", retainedResult(returned))
        stage("SAME_ORIGINAL_NATIVE_POSITIVE_A_RECOVERED")
        return returned
    }

    suspend fun logout(): Unit = withContext(NonCancellable) {
        val current = tokens ?: return@withContext
        // Cleanup may run after HELD. Capture these genuine credentials directly, without
        // weakening currentTokens() for any ordinary protected request.
        val local = checkNotNull(original)
        val logoutCredentials = LocalGatewayCredentials(local.peer.connectionId, current.access)
        var failure: Throwable? = null
        fun remember(next: Throwable) {
            held = true
            val first = failure
            if (first == null) failure = next else if (first !== next) first.addSuppressed(next)
        }
        revocationUnconfirmed = true
        try {
            // Retention failure must not strand a delivered token without attempting its
            // one real revocation. An uncertain native revocation is never retried.
            try { stage("REAL_OPERATOR_LOGOUT_PENDING") } catch (next: Throwable) { remember(next) }
            try {
                val result = local.client.logout(logoutCredentials)
                check(result == LocalSignOutResult.Revoked) { "Actual native revocation remains unconfirmed" }
                revocationUnconfirmed = false
                retiredSessionId = current.view.sessionId
                record.put("retiredOperatorSessionId", current.view.sessionId.value)
                try { stage("REAL_OPERATOR_LOGOUT_REVOKED") } catch (next: Throwable) { remember(next) }
            } catch (next: Throwable) { remember(next) }
        } finally {
            try { current.destroy() } catch (next: Throwable) { remember(next) }
            tokens = null
        }
        failure?.let { throw it }
    }

    /** False/throwing close retains owner/unknown state. Never retry an attempted uncertain close. */
    suspend fun close(): Boolean = withContext(NonCancellable) {
        var closed = !acquisitionUnconfirmed && !revocationUnconfirmed
        if (tokens != null) try { logout() } catch (failure: Throwable) { rememberCleanupFailure(failure); closed = false }
        val actual = master
        if (actual != null) {
            if (masterCloseAttempted) closed = false
            else {
                masterCloseAttempted = true
                if (!cleanupStage("OWNED_NATIVE_MASTER_CLOSE_PENDING")) closed = false
                try {
                    val result = actual.quiesceAndClose()
                    record.put("lastOwnedMasterClosed", result)
                    if (result) { master = null; original = null; context = null }
                    else {
                        rememberCleanupFailure(AssertionError("Actual native Master close returned unconfirmed"))
                        closed = false
                    }
                } catch (failure: Throwable) {
                    record.put("lastOwnedMasterClosed", JSONObject.NULL)
                    rememberCleanupFailure(failure); closed = false
                }
                if (!cleanupRetain()) closed = false
            }
        } else if (context != null && !closeContext()) closed = false
        if (provisioner != null && !closeProvisioner()) closed = false
        closed && !acquisitionUnconfirmed && !revocationUnconfirmed && cleanupFailures.isEmpty()
    }

    private suspend fun acquireContext(preparing: Boolean) {
        check(context == null && master == null && !acquisitionUnconfirmed && !held)
        stage(if (preparing) "ORIGINAL_NATIVE_PREPARATION_ACQUISITION_PENDING" else "ORIGINAL_NATIVE_EXISTING_ACQUISITION_PENDING")
        acquisitionUnconfirmed = true
        val result = if (preparing) AndroidSelfOwnedInstallationOwner.prepareOriginal(application)
            else AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val owned = (result as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Original native context acquisition is unresolved")
        context = owned; contextCloseAttempted = false; acquisitionUnconfirmed = false
        allocated?.let { check(sameCoordinates(it.coordinates, owned.materialAtOpen.coordinates)) }
    }

    private suspend fun acquireMaster(installationOnly: Boolean) {
        check(master == null && context != null && !acquisitionUnconfirmed && !held)
        stage(if (installationOnly) "ORIGINAL_INSTALLATION_MASTER_ACQUISITION_PENDING" else "ORDINARY_FULL_HISTORY_MASTER_ACQUISITION_PENDING")
        acquisitionUnconfirmed = true
        val result = if (installationOnly) AndroidSelfOwnedMasterService.forOriginalInstallation(application, checkNotNull(context))
            else AndroidLocalBranchRuntime.openOriginalMaster(application, checkNotNull(context))
        val owned = (result as? AndroidSelfOwnedMasterOpenResult.Opened)?.service
            ?: error("Actual native Master acquisition is unresolved")
        master = owned; context = null; masterCloseAttempted = false; acquisitionUnconfirmed = false
    }

    private suspend fun closeProvisioner(): Boolean = withContext(NonCancellable) {
        val actual = provisioner ?: return@withContext true
        if (provisionerCloseAttempted) return@withContext false
        provisionerCloseAttempted = true
        var closed = cleanupStage("OWNED_PROVISIONER_CLOSE_PENDING")
        try { actual.close(); provisioner = null; record.put("lastOwnedProvisionerClosed", true) }
        catch (failure: Throwable) {
            record.put("lastOwnedProvisionerClosed", JSONObject.NULL)
            rememberCleanupFailure(failure); closed = false
        }
        if (!cleanupRetain()) closed = false
        closed
    }

    private suspend fun closeContext(): Boolean = withContext(NonCancellable) {
        val actual = context ?: return@withContext true
        if (contextCloseAttempted) return@withContext false
        contextCloseAttempted = true
        var closed = cleanupStage("OWNED_NATIVE_CONTEXT_CLOSE_PENDING")
        try { actual.close(); context = null; record.put("lastOwnedContextClosed", true) }
        catch (failure: Throwable) {
            record.put("lastOwnedContextClosed", JSONObject.NULL)
            rememberCleanupFailure(failure); closed = false
        }
        if (!cleanupRetain()) closed = false
        closed
    }

    private fun rememberCleanupFailure(failure: Throwable) { held = true; cleanupFailures += failure }
    private fun cleanupStage(value: String): Boolean =
        try { stage(value); true } catch (failure: Throwable) { rememberCleanupFailure(failure); false }
    private fun cleanupRetain(): Boolean =
        try { retain(); true } catch (failure: Throwable) { rememberCleanupFailure(failure); false }

    private fun currentTokens(): LocalSessionTokens {
        check(!held && !revocationUnconfirmed && original?.isCurrent() == true && original?.peer?.isCurrent() == true)
        return checkNotNull(tokens)
    }
    private fun credentials(): LocalGatewayCredentials = LocalGatewayCredentials(checkNotNull(original).peer.connectionId, currentTokens().access)
    private suspend fun read(request: ReadRequest): ReadReply.Data =
        checkNotNull(original).client.read(credentials(), request) as? ReadReply.Data ?: error("Genuine protected read is unavailable")
    private fun requireRefusal(value: MutationReply.Recorded) {
        val expected = plan.original
        check(!value.committed && value.operationId == expected.request.operationId && value.originalActorId == plan.operatorId &&
            value.authorityEpoch == checkNotNull(receipt).core.authorityEpoch)
        val decoded = LocalOpeningSupplierObligationCodec.decodeResult(value.result)
        check(decoded.id == expected.value.obligationId && decoded.revision == null && decoded.code == value.code)
        // No exact rejection message/code is prescribed. The genuine codec binds this to an opening refusal.
    }
    private fun positiveSetup(): NativePositiveOpeningSupplierSetup =
        checkNotNull(plan.positiveSupplierSetup) { "A separate explicit positive supplier plan is required" }
    private fun requirePositivePosting(value: MutationReply.Recorded) {
        val expected = plan.original
        check(value.committed && value.operationId == expected.request.operationId && value.originalActorId == plan.operatorId &&
            value.authorityEpoch == checkNotNull(receipt).core.authorityEpoch)
        val decoded = LocalOpeningSupplierObligationCodec.decodeResult(value.result)
        check(decoded.id == expected.value.obligationId && decoded.revision == Revision(1) &&
            decoded.code == LocalOpeningSupplierObligationProfile.RECORDED && decoded.code == value.code)
    }

    private fun requirePositiveOpeningView(value: LocalOpeningSupplierObligationViewV1) {
        val expected = plan.original
        val outcome = checkNotNull(firstResult)
        check(value.id == expected.value.obligationId && value.revision == Revision(1) &&
            value.supplierId == expected.value.supplierId && value.supplierRevision == checkNotNull(boundOriginal).value.expectedSupplierRevision &&
            value.supplierNames == positiveSetup().supplier.names && value.sourceEvidenceId == expected.value.sourceEvidenceId &&
            value.sourceReference == expected.value.sourceReference && value.sourceDocument.sourceIdentity == expected.value.sourceDocument.sourceIdentity &&
            value.sourceDocument.text == expected.value.sourceDocument.text && value.openingDate == expected.value.openingDate &&
            value.amount == expected.value.amount && value.paymentTerms == expected.value.paymentTerms && value.reasonCode == expected.value.reasonCode &&
            value.operationId == expected.request.operationId && value.actorId == outcome.originalActorId && value.actorId == plan.operatorId &&
            value.recordedAt == outcome.recordedAt && value.policyRevision == checkNotNull(postingPolicyRevision))
        val document = value.sourceDocument.text.encodeToByteArray(throwOnInvalidSequence = true)
        try { check(document.contentEquals(expected.documentBytes)) } finally { document.fill(0) }
        val metadata = value.sourceDocumentMetadata
        check(metadata.scope == coordinates.scope && metadata.evidenceId == expected.value.sourceEvidenceId &&
            metadata.ownerObligationId == expected.value.obligationId && metadata.supplierId == expected.value.supplierId &&
            metadata.captureOperationId == expected.request.operationId && metadata.capturedBy == outcome.originalActorId &&
            metadata.capturedAt == outcome.recordedAt && metadata.byteSize == expected.documentBytes.size &&
            metadata.sha256 == expected.assetSha256)
    }
    private fun retainedResult(value: MutationReply.Recorded): JSONObject = JSONObject()
        .put("operationId", value.operationId.value).put("originalActorId", value.originalActorId.value)
        .put("committed", value.committed).put("code", value.code).put("replayed", value.replayed)
        .put("authorityEpoch", value.authorityEpoch.value.toString()).put("journalSequence", value.sequence.value.toString())
        .put("recordedAt", value.recordedAt.canonicalString())
        .put("originalResultBase64", openingRetentionBase64(value.result.copyBytes()))
    private fun <T> nativeValue(result: LocalCompanyAuthorityResult<T>): T =
        (result as? LocalCompanyAuthorityResult.Value<T>)?.value ?: error("Genuine native authority operation is unavailable")
    private suspend fun <T> ownedSecret(source: CharArray, use: suspend (CharArray) -> T): T {
        val owned = source.copyOf()
        return try { use(owned) } finally { owned.fill('\u0000') }
    }
    private fun sameCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1): Boolean =
        a.scope == b.scope && a.allocationId == b.allocationId && a.installationId == b.installationId &&
            a.assignmentId == b.assignmentId && a.deviceId == b.deviceId && a.storageInstanceId == b.storageInstanceId &&
            a.storageKeyId == b.storageKeyId && a.pepperKeyId == b.pepperKeyId && a.targetSigningKeyId == b.targetSigningKeyId &&
            a.firstActorId == b.firstActorId
}
