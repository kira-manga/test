package com.ukkera.pos.android.independent.nativefoodfloordirectoryv1

import android.app.Application
import com.ukkera.pos.android.openingobligation.openingRetentionBase64
import com.ukkera.pos.android.openingobligation.requireSameOpeningAccount
import com.ukkera.pos.android.openingobligation.requireSameOpeningPolicy
import com.ukkera.pos.android.openingobligation.retainedOpeningAccount
import com.ukkera.pos.app.gateway.AndroidLocalBranchRuntime
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Explicit requested account facts; only original native installation activates this policy. */
internal class NativeFoodFloorPlan(
    val protection: SelfOwnedKeyProtectionV1,
    private val targets: Set<DomainId>,
) {
    val installerLogin = "native-floor-installer"
    val headLogin = "native-floor-head"
    val employeeLogin = LocalLoginIdentifier("native-floor-employee")
    val branchName = "Synthetic native floor directory branch"
    val authorityEpoch = Revision(1)
    val founding = LocalCompanyFoundingFactsV1(
        SelfOwnedCompanyFactsV1("Synthetic native floor company", "Synthetic installer", installerLogin, protection),
        "Synthetic floor company head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        LocalCompanyHeadPurposeV1.entries.toSet(),
    )

    fun account(c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val actor = c.firstActorId
        val grants = listOf(
            PermissionGrant(Permission(SecurityAction.FLOOR_CONFIGURE, PermissionVerb.ADMINISTER), c.scope,
                GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY, actor, targets, null),
            PermissionGrant(Permission(SecurityAction.FLOOR_READ, PermissionVerb.PERFORM), c.scope,
                GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY, actor, null, null),
        )
        return LocalAccountProvisioning(actor, employeeLogin,
            ActivatedLocalPolicy(c.scope, actor, Revision(0), grants,
                LocalSessionPolicy(maximumConcurrentSessions = 1),
                ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000,
                    maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000), approvalPolicy = null),
            LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000), setOf(c.deviceId))
    }
}

internal data class NativeFoodFloorOriginal(
    val frozen: NativeFoodFloorCommand,
    val identity: OperationIdentity,
    val reply: MutationReply.Recorded,
)

/**
 * One original-native owner on the host coroutine. Actual enrollment, installation, original
 * peer, Room/native history admission and protected endpoint are retained without replacement
 * factories. An uncertain acquisition, revocation or close holds the owner and forbids reopen.
 */
internal class NativeFoodFloorOwner(
    private val application: Application,
    private val plan: NativeFoodFloorPlan,
    private val record: JSONObject,
    private val retain: () -> Unit,
) {
    private var provisioner: LocalProvisioner? = null
    private var context: SelfOwnedLocalSecurityContext? = null
    private var master: AndroidSelfOwnedMasterService? = null
    private var local: AndroidOriginalLocalClient? = null
    private var tokens: LocalSessionTokens? = null
    private var material: SelfOwnedNativeMaterialView? = null
    private var receipt: LocalBranchInstallationReceiptV2? = null
    private var setupStarted = false
    private var acquisitionUnconfirmed = false
    private var revocationUnconfirmed = false
    private var provisionerCloseAttempted = false
    private var contextCloseAttempted = false
    private var masterCloseAttempted = false
    private var previousClient: AndroidOriginalLocalClient? = null
    private var retiredSessionId: DomainId? = null
    private val dispatched = mutableSetOf<OperationId>()
    private val accepted = mutableListOf<NativeFoodFloorOriginal>()
    private val cleanupFailures = mutableListOf<Throwable>()
    var held = false
        private set
    var step = "NEW"
        private set
    val coordinates: SelfOwnedAllocationCoordinatesV1 get() = checkNotNull(material).coordinates
    val acceptedProducerCount: Int get() = accepted.size

    fun hold() { held = true }
    fun takeCleanupFailures(): List<Throwable> = cleanupFailures.toList().also { cleanupFailures.clear() }
    private fun stage(value: String) { step = value; record.put("status", value); retain() }

    suspend fun prepareAndInstall(installerSecret: CharArray, headSecret: CharArray, employeePassword: CharArray) {
        check(!setupStarted && !held)
        setupStarted = true
        stage("ORIGINAL_PROVISIONER_ACQUISITION_PENDING")
        acquisitionUnconfirmed = true
        val opened = AndroidSelfOwnedEnrollmentSource.open(application)
        val actual = (opened as? LocalProvisionerOpenResult.Opened)?.provisioner
            ?: error("Original native provisioner is unavailable")
        provisioner = actual; acquisitionUnconfirmed = false
        stage("ORIGINAL_COMPANY_PREPARATION_PENDING")
        val founding = nativeValue(actual.companyAuthority.prepareNewCompanyAndHead(plan.founding))
        check(!founding.enrollmentRetained) { "Existing enrollment cannot become an independent fresh case" }
        record.put("organizationId", founding.organizationId.value).put("companyId", founding.companyId.value)
            .put("setupHeadAuthorityId", founding.headAuthorityId.value)
            .put("setupHeadEnrollmentId", founding.headEnrollmentId.value)
        stage("ORIGINAL_COMPANY_COMPLETION_PENDING")
        val company = ownedSecret(installerSecret) { installer -> ownedSecret(headSecret) { head ->
            nativeValue(actual.companyAuthority.completeNewCompanyAndHead(founding, installer, head))
        } }
        check(company.organizationId == founding.organizationId && company.companyId == founding.companyId &&
            company.rootFingerprint == founding.rootFingerprint && company.headAuthorityId == founding.headAuthorityId &&
            company.headEnrollmentId == founding.headEnrollmentId && company.headEnabled &&
            company.policyIssuanceEnabled && company.installationIssuanceEnabled)
        stage("ORIGINAL_BRANCH_PREPARATION_PENDING")
        val preparation = actual.prepareBranchWithEnrolledCompany(SelfOwnedBranchFactsV1(
            company.rootFingerprint, plan.branchName, plan.protection))
        val action = (preparation as? LocalProvisionerPreparation.Prepared)?.action
            ?: error("Original native branch preparation is unavailable")
        stage("ORIGINAL_BRANCH_ISSUANCE_PENDING")
        val issued = ownedSecret(installerSecret) { actual.issueOriginalPreparation(action, plan.installerLogin, it) }
        val packet = (issued as? LocalProvisionerGrant.Retained)?.packet
            ?: error("Original native branch issuance is unresolved")
        check(packet.coordinates.scope.company() == CompanyScope(company.organizationId, company.companyId))
        check(closeProvisioner()) { "Original provisioner close is unconfirmed" }

        acquireContext(preparing = true)
        material = checkNotNull(context).materialAtOpen
        check(sameCoordinates(coordinates, packet.coordinates) && sameTarget(checkNotNull(material).target, packet.target))
        check(coordinates.firstActorId != company.headAuthorityId && coordinates.firstActorId != company.headEnrollmentId)
        record.put("branchId", coordinates.scope.branchId.value).put("originalEmployeeId", coordinates.firstActorId.value)
            .put("deviceId", coordinates.deviceId.value).put("installationId", coordinates.installationId.value)
            .put("allocationId", coordinates.allocationId.value).put("assignmentId", coordinates.assignmentId.value)
        check(closeContext()) { "Original preparation context close is unconfirmed" }
        val account = plan.account(coordinates)
        record.put("requestedOriginalEmployee", retainedOpeningAccount(account))
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
        check(sameCoordinates(coordinates, definition.definition.allocation.coordinates) &&
            definition.definition.authorityEpoch == plan.authorityEpoch)
        record.put("originalInstallationRequestId", definition.definition.originalRequestId.value)
            .put("originalDefinitionDigest", definition.definitionDigest.value)
        stage("ORIGINAL_INSTALLATION_PENDING")
        var outcome = ownedSecret(headSecret) { head -> ownedSecret(employeePassword) { password ->
            installing.installOriginal(definition.definition, plan.headLogin, head, password)
        } }
        if (outcome == LocalMasterInstallationOutcome.AcknowledgementPending) {
            check(close()) { "Original installation close before explicit ACK is unconfirmed" }
            acquireContext(preparing = false)
            acquireMaster(installationOnly = true)
            check(checkNotNull(master).mode.value == LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
            stage("ORIGINAL_ACK_RESUME_PENDING")
            outcome = checkNotNull(master).resumeOriginalAcknowledgement()
        }
        val installed = outcome as? LocalMasterInstallationOutcome.Installed
            ?: error("Original installation did not complete")
        receipt = installed.receipt
        check(installed.receipt.core.originalRequestId == definition.definition.originalRequestId &&
            installed.receipt.core.definitionDigest == definition.definitionDigest &&
            sameCoordinates(coordinates, installed.receipt.core.allocation.coordinates))
        record.put("originalReceiptDigest", installed.receipt.digest.value)
        check(close()) { "Installed original native owner close is unconfirmed" }
        stage("ORIGINAL_INSTALLATION_CLOSED")
    }

    suspend fun openOperationalAndSignIn(password: CharArray) {
        check(receipt != null && master == null && context == null && provisioner == null && tokens == null &&
            !acquisitionUnconfirmed && !revocationUnconfirmed && !held)
        acquireContext(preparing = false)
        acquireMaster(installationOnly = false)
        val actual = checkNotNull(master)
        check(actual.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Actual original full-history admission is held" }
        stage("ORIGINAL_LOCAL_CLIENT_ACQUISITION_PENDING")
        val original = actual.originalLocalClient().also { local = it }
        observeMaterial("ORIGINAL_LOCAL_CLIENT", original.material)
        check(original.isCurrent() && original.belongsTo(application) &&
            sameCoordinates(coordinates, original.receipt.core.allocation.coordinates) &&
            original.receipt.digest == checkNotNull(receipt).digest &&
            LocalFoodFloorProfileV1.COMMAND in original.commandKeys && LocalFoodFloorReadCodecV1.QUERY in original.readKeys)
        previousClient?.let { old -> check(old !== original && !old.isCurrent() &&
            old.serviceIncarnationId != original.serviceIncarnationId) }
        record.put("currentServiceIncarnationId", original.serviceIncarnationId.value)
        stage("ORIGINAL_EMPLOYEE_FRESH_AUTHENTICATION_PENDING")
        val result = ownedSecret(password) { original.client.signIn(original.peer.connectionId, plan.employeeLogin, it) }
        val current = (result as? LocalAuthenticationResult.Authenticated)?.tokens
            ?: error("Actual original employee authentication is unavailable")
        tokens = current // Retain the delivered secrets before checking any returned association.
        requireSession(current.view)
        check(retiredSessionId == null || current.view.sessionId != retiredSessionId)
        observeCurrent("FRESH_AUTHENTICATION")
        stage("ORIGINAL_EMPLOYEE_FRESH_AUTHENTICATION_OBSERVED")
    }

    suspend fun execute(command: NativeFoodFloorCommand, label: String): NativeFoodFloorOriginal {
        val current = observeCurrent("${label}_BEFORE_COMMAND")
        check(dispatched.add(command.request.operationId)) { "A fresh dispatch cannot replace an earlier original operation" }
        val identity = OperationIdentity(coordinates.scope.branchId, command.request.operationId, current.actorId,
            command.request.command.version, semanticDigest(coordinates.scope, current.actorId,
                command.request.command, command.request.payload).value)
        record.getJSONArray("commands").put(JSONObject().put("label", label)
            .put("operationId", identity.operationId.value).put("originalActorId", identity.originalActorId.value)
            .put("semanticDigest", identity.semanticSha256)
            .put("canonicalInputBase64", openingRetentionBase64(command.request.payload.copyBytes())))
        stage("${label}_ORIGINAL_DISPATCH_PENDING")
        val result = checkNotNull(local).client.execute(credentials(), command.request)
        val reply = result as? MutationReply.Recorded ?: error("Original floor command outcome is unresolved")
        retainReply(label, reply)
        val original = NativeFoodFloorOriginal(command, identity, reply)
        requireExpectedReply(original, reply, command.expected)
        check(!reply.replayed) { "A replay cannot qualify a required new producer transition" }
        if (reply.committed) accepted += original
        record.put("observedAcceptedProducerCommands", accepted.size)
        observeCurrent("${label}_AFTER_COMMAND")
        stage("${label}_ORIGINAL_RESULT_OBSERVED")
        return original
    }

    suspend fun read(query: LocalFoodFloorQueryV1, label: String): LocalFoodFloorPageV1 {
        observeCurrent("${label}_BEFORE_READ")
        stage("${label}_PROTECTED_READ_PENDING")
        val reply = checkNotNull(local).client.read(credentials(), LocalFoodFloorReadCodecV1.read(query))
        val data = reply as? ReadReply.Data ?: error("Actual protected floor read is unavailable")
        record.getJSONArray("reads").put(JSONObject().put("label", label)
            .put("branchRevision", data.branchRevision.value.toString()).put("observedAt", data.observedAt.canonicalString())
            .put("actualResponseBase64", openingRetentionBase64(data.payload.copyBytes())))
        retain()
        // The production codec rejects unknown fields; compare its complete semantic DTO to
        // the frozen public projection, without treating generated bytes as a wire golden.
        val page = LocalFoodFloorReadCodecV1.decodeResult(data.payload)
        check(page.query == query)
        observeCurrent("${label}_AFTER_READ")
        stage("${label}_PROTECTED_RESPONSE_OBSERVED")
        return page
    }

    suspend fun lookup(original: NativeFoodFloorOriginal, expected: LocalConfigurationResultV1, label: String): MutationReply.Recorded {
        observeCurrent("${label}_BEFORE_LOOKUP")
        stage("${label}_ORIGINAL_LOOKUP_PENDING")
        val outcome = checkNotNull(local).client.lookup(credentials(), original.identity)
        val returned = (outcome as? LookupReply.Found)?.result ?: error("Authorized original-result lookup is unavailable")
        retainReply(label, returned)
        requireExpectedReply(original, returned, expected)
        requireSameOriginal(original.reply, returned)
        observeCurrent("${label}_AFTER_LOOKUP")
        stage("${label}_ORIGINAL_RESULT_CORRELATED")
        return returned
    }

    suspend fun retry(original: NativeFoodFloorOriginal, retry: NativeFoodFloorCommand) {
        check(retry.request.operationId == original.frozen.request.operationId &&
            retry.request.command == original.frozen.request.command && retry.request.payload == original.frozen.request.payload &&
            retry.semantic == original.frozen.semantic)
        observeCurrent("IDENTICAL_A_RETRY_BEFORE_DISPATCH")
        stage("IDENTICAL_A_RETRY_PENDING")
        // The exact already-retained original object is dispatched. No new ID/input is made.
        val outcome = checkNotNull(local).client.execute(credentials(), original.frozen.request)
        val returned = outcome as? MutationReply.Recorded ?: error("Identical original A retry is unresolved")
        retainReply("IDENTICAL_A_RETRY", returned)
        requireExpectedReply(original, returned, retry.expected)
        requireSameOriginal(original.reply, returned)
        observeCurrent("IDENTICAL_A_RETRY_AFTER_DISPATCH")
        stage("IDENTICAL_A_RETRY_ORIGINAL_RESULT_CORRELATED")
    }

    private suspend fun observeCurrent(label: String): LocalSessionView {
        val original = checkNotNull(local)
        check(!held && !revocationUnconfirmed && original.isCurrent() && original.belongsTo(application) && original.peer.isCurrent())
        val result = original.client.current(credentials())
        val view = (result as? LocalSessionViewResult.Current)?.view ?: error("Current native employee association is unavailable")
        requireSession(view)
        check(view.sessionId == checkNotNull(tokens).view.sessionId)
        record.getJSONArray("associations").put(JSONObject().put("label", label)
            .put("organizationId", view.scope.organizationId.value).put("companyId", view.scope.companyId.value)
            .put("branchId", view.scope.branchId.value).put("actorId", view.actorId.value).put("deviceId", view.deviceId.value)
            .put("sessionId", view.sessionId.value).put("policyRevision", view.policy.revision.value.toString())
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        retain()
        return view // This observation is not a grant; each actual command/read reauthorizes.
    }

    private fun requireSession(view: LocalSessionView) {
        val original = checkNotNull(local)
        check(view.actorId == coordinates.firstActorId && view.scope == coordinates.scope &&
            view.deviceId == coordinates.deviceId && view.deviceId == original.peer.deviceId &&
            view.login == plan.employeeLogin && original.peer.scope == coordinates.scope)
        requireSameOpeningPolicy(plan.account(coordinates).policy, view.policy)
    }

    private fun requireExpectedReply(original: NativeFoodFloorOriginal, value: MutationReply.Recorded,
        expected: LocalConfigurationResultV1) {
        check(value.operationId == original.identity.operationId && value.originalActorId == original.identity.originalActorId &&
            value.originalActorId == coordinates.firstActorId && value.authorityEpoch == checkNotNull(receipt).core.authorityEpoch &&
            value.committed == (expected.code == "food.floor.saved"))
        check(LocalFoodFloorCodecV1.decodeResult(value.result) == expected)
    }

    private fun requireSameOriginal(before: MutationReply.Recorded, after: MutationReply.Recorded) {
        check(before.operationId == after.operationId && before.originalActorId == after.originalActorId &&
            before.committed == after.committed && before.code == after.code && before.result == after.result &&
            before.authorityEpoch == after.authorityEpoch && before.sequence == after.sequence && before.recordedAt == after.recordedAt)
    }

    private fun retainReply(label: String, value: MutationReply.Recorded) {
        record.getJSONArray("results").put(JSONObject().put("label", label)
            .put("operationId", value.operationId.value).put("originalActorId", value.originalActorId.value)
            .put("committed", value.committed).put("envelopeOutcome", value.code).put("replayed", value.replayed)
            .put("authorityEpoch", value.authorityEpoch.value.toString()).put("journalSequence", value.sequence.value.toString())
            .put("recordedAt", value.recordedAt.canonicalString())
            .put("actualResultBase64", openingRetentionBase64(value.result.copyBytes())))
        retain()
    }

    private fun credentials(): LocalGatewayCredentials {
        check(!held && !revocationUnconfirmed)
        return LocalGatewayCredentials(checkNotNull(local).peer.connectionId, checkNotNull(tokens).access)
    }

    private suspend fun logout(): Unit = withContext(NonCancellable) {
        val current = tokens ?: return@withContext
        val original = checkNotNull(local)
        var failure: Throwable? = null
        fun remember(next: Throwable) {
            held = true
            val before = failure
            if (before == null) failure = next else if (before !== next) before.addSuppressed(next)
        }
        revocationUnconfirmed = true
        try {
            try { stage("ACTUAL_EMPLOYEE_LOGOUT_PENDING") } catch (next: Throwable) { remember(next) }
            try {
                check(original.client.logout(LocalGatewayCredentials(original.peer.connectionId, current.access)) == LocalSignOutResult.Revoked)
                revocationUnconfirmed = false
                retiredSessionId = current.view.sessionId
                record.put("retiredSessionId", current.view.sessionId.value)
            } catch (next: Throwable) { remember(next) }
        } finally {
            try { current.destroy() } catch (next: Throwable) { remember(next) }
            tokens = null
        }
        failure?.let { throw it }
    }

    /** Only positively completed actual close permits replacement original ownership. */
    suspend fun close(): Boolean = withContext(NonCancellable) {
        var closed = !acquisitionUnconfirmed && !revocationUnconfirmed
        if (tokens != null) try { logout() } catch (failure: Throwable) { rememberCleanupFailure(failure); closed = false }
        val actual = master
        if (actual != null) {
            if (masterCloseAttempted) closed = false else {
                masterCloseAttempted = true
                if (!cleanupStage("ACTUAL_NATIVE_MASTER_CLOSE_PENDING")) closed = false
                var closeReturned = false
                try {
                    val result = actual.quiesceAndClose()
                    closeReturned = true
                    record.put("lastOwnedMasterClosed", result)
                    if (result) {
                        val old = local
                        old?.let {
                            val retired = !it.isCurrent()
                            record.put("lastOwnedOriginalClientRetired", retired)
                            check(retired)
                            previousClient = it
                        }
                        // A failed retirement observation keeps the actual acquired owner.
                        // Confirmed native close alone cannot authorize its replacement.
                        master = null; local = null; context = null
                    } else {
                        rememberCleanupFailure(AssertionError("Actual native Master close is unconfirmed")); closed = false
                    }
                } catch (failure: Throwable) {
                    if (!closeReturned) record.put("lastOwnedMasterClosed", JSONObject.NULL)
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
        stage(if (preparing) "ORIGINAL_CONTEXT_PREPARATION_PENDING" else "ORIGINAL_EXISTING_CONTEXT_PENDING")
        acquisitionUnconfirmed = true
        val result = if (preparing) AndroidSelfOwnedInstallationOwner.prepareOriginal(application)
            else AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val owned = (result as? SelfOwnedNativeOpenResult.Opened)?.context ?: error("Original context acquisition is unresolved")
        context = owned; contextCloseAttempted = false; acquisitionUnconfirmed = false
        observeMaterial(if (preparing) "ORIGINAL_PREPARATION_CONTEXT" else "ORIGINAL_EXISTING_CONTEXT", owned.materialAtOpen)
    }

    private suspend fun acquireMaster(installationOnly: Boolean) {
        check(master == null && context != null && !acquisitionUnconfirmed && !held)
        stage(if (installationOnly) "ORIGINAL_INSTALLATION_MASTER_PENDING" else "ORIGINAL_FULL_HISTORY_MASTER_PENDING")
        acquisitionUnconfirmed = true
        val result = if (installationOnly) AndroidSelfOwnedMasterService.forOriginalInstallation(application, checkNotNull(context))
            else AndroidLocalBranchRuntime.openOriginalMaster(application, checkNotNull(context))
        val owned = (result as? AndroidSelfOwnedMasterOpenResult.Opened)?.service ?: error("Original Master acquisition is unresolved")
        master = owned; context = null; masterCloseAttempted = false; acquisitionUnconfirmed = false
    }

    private suspend fun closeProvisioner(): Boolean = withContext(NonCancellable) {
        val actual = provisioner ?: return@withContext true
        if (provisionerCloseAttempted) return@withContext false
        provisionerCloseAttempted = true
        var closed = cleanupStage("ACTUAL_PROVISIONER_CLOSE_PENDING")
        try { actual.close(); provisioner = null; record.put("lastOwnedProvisionerClosed", true) }
        catch (failure: Throwable) { rememberCleanupFailure(failure); closed = false }
        if (!cleanupRetain()) closed = false
        closed
    }

    private suspend fun closeContext(): Boolean = withContext(NonCancellable) {
        val actual = context ?: return@withContext true
        if (contextCloseAttempted) return@withContext false
        contextCloseAttempted = true
        var closed = cleanupStage("ACTUAL_CONTEXT_CLOSE_PENDING")
        try { actual.close(); context = null; record.put("lastOwnedContextClosed", true) }
        catch (failure: Throwable) { rememberCleanupFailure(failure); closed = false }
        if (!cleanupRetain()) closed = false
        closed
    }

    private fun rememberCleanupFailure(failure: Throwable) { held = true; cleanupFailures += failure }
    private fun cleanupStage(value: String): Boolean =
        try { stage(value); true } catch (failure: Throwable) { rememberCleanupFailure(failure); false }
    private fun cleanupRetain(): Boolean =
        try { retain(); true } catch (failure: Throwable) { rememberCleanupFailure(failure); false }
    private fun <T> nativeValue(value: LocalCompanyAuthorityResult<T>): T =
        (value as? LocalCompanyAuthorityResult.Value<T>)?.value ?: error("Original native company operation is unavailable")
    private suspend fun <T> ownedSecret(source: CharArray, use: suspend (CharArray) -> T): T {
        val owned = source.copyOf()
        return try { use(owned) } finally { owned.fill('\u0000') }
    }
    private fun observeMaterial(label: String, value: SelfOwnedNativeMaterialView) {
        val c = value.coordinates
        record.getJSONArray("nativeMaterials").put(JSONObject().put("label", label)
            .put("organizationId", c.scope.organizationId.value).put("companyId", c.scope.companyId.value)
            .put("branchId", c.scope.branchId.value).put("allocationId", c.allocationId.value)
            .put("installationId", c.installationId.value).put("assignmentId", c.assignmentId.value)
            .put("deviceId", c.deviceId.value).put("firstActorId", c.firstActorId.value)
            .put("storageInstanceId", c.storageInstanceId.value).put("storageKeyId", c.storageKeyId.value)
            .put("pepperKeyId", c.pepperKeyId.value).put("targetSigningKeyId", c.targetSigningKeyId.value)
            .put("target", JSONObject().put("applicationId", value.target.applicationId)
                .put("applicationUid", value.target.applicationUid).put("apkSignerSha256", value.target.apkSignerSha256)
                .put("androidIdCommitment", value.target.androidIdCommitment))
            .put("branchName", value.branchName).put("protection", value.protection.name)
            .put("targetSigningFingerprint", value.targetSigningFingerprint)
            .put("originalNativeBindingDigest", value.originalNativeBindingDigest)
            .put("storageKeySecurity", value.storageKeySecurity.name)
            // Lifetime-sensitive description only: never equality, readiness or ceremony authority.
            .put("newCeremoniesAllowedAtObservedAcquisition", value.newCeremoniesAllowed))
        retain()
        material?.let { original ->
            check(sameCoordinates(original.coordinates, value.coordinates) && sameTarget(original.target, value.target) &&
                original.branchName == value.branchName && original.protection == value.protection &&
                original.targetSigningFingerprint == value.targetSigningFingerprint &&
                original.originalNativeBindingDigest == value.originalNativeBindingDigest &&
                original.storageKeySecurity == value.storageKeySecurity)
        }
        check(value.target.applicationId == application.packageName && value.target.applicationUid == application.applicationInfo.uid &&
            value.branchName == plan.branchName && value.protection == plan.protection)
    }
    private fun sameTarget(a: SelfOwnedTargetDescriptorV1, b: SelfOwnedTargetDescriptorV1): Boolean =
        a.applicationId == b.applicationId && a.applicationUid == b.applicationUid &&
            a.apkSignerSha256 == b.apkSignerSha256 && a.androidIdCommitment == b.androidIdCommitment
    private fun sameCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1): Boolean =
        a.scope == b.scope && a.allocationId == b.allocationId && a.installationId == b.installationId &&
            a.assignmentId == b.assignmentId && a.deviceId == b.deviceId && a.storageInstanceId == b.storageInstanceId &&
            a.storageKeyId == b.storageKeyId && a.pepperKeyId == b.pepperKeyId && a.targetSigningKeyId == b.targetSigningKeyId &&
            a.firstActorId == b.firstActorId
}
