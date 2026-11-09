package com.ukkera.pos.android.independent.retailhandoffhistoryv1

import android.app.Application
import com.ukkera.pos.app.gateway.AndroidLocalBranchRuntime
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationDetailV1
import com.ukkera.pos.catalog.store.LocalConfigurationPageV1
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationQueryV1
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.readNativeCompanySku
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.LocalGoodsReceiptLine
import com.ukkera.pos.inventory.store.LocalGoodsReceiptRequest
import com.ukkera.pos.inventory.store.LocalReceiptDisposition
import com.ukkera.pos.inventory.store.LocalStockBasisKind
import com.ukkera.pos.inventory.store.LocalStockCommands
import com.ukkera.pos.inventory.store.LocalStockCostPage
import com.ukkera.pos.inventory.store.LocalStockItemVersion
import com.ukkera.pos.inventory.store.LocalStockQuantityPage
import com.ukkera.pos.inventory.store.LocalStockQueries
import com.ukkera.pos.inventory.store.LocalStockRelationalCommandsV2
import com.ukkera.pos.inventory.store.LocalStockResultKind
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashQueryV1
import com.ukkera.pos.sales.store.LocalCashReadCodec
import com.ukkera.pos.sales.store.LocalCashReadResultV1
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalCashSessionViewV1
import com.ukkera.pos.sales.store.LocalCollectionStockCodecV2
import com.ukkera.pos.sales.store.LocalCollectionStockRequestV2
import com.ukkera.pos.sales.store.LocalDrawerChoiceV1
import com.ukkera.pos.sales.store.LocalOrderLineV1
import com.ukkera.pos.sales.store.LocalOrderQueryV1
import com.ukkera.pos.sales.store.LocalOrderReadCodecV2
import com.ukkera.pos.sales.store.LocalOrderSelectionV1
import com.ukkera.pos.sales.store.LocalOrderStockProfileV2
import com.ukkera.pos.sales.store.LocalSalesAction
import com.ukkera.pos.sales.store.LocalSalesResultCodec
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.security.AndroidSelfOwnedEnrollmentSource
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalProvisioner
import com.ukkera.pos.security.LocalProvisionerGrant
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisionerPreparation
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedBranchFactsV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Explicit-invocation, one-isolated-installation fixture preparation. There is intentionally
 * no @Test, runner, automatic execution, SQL seeding, supplied Master/peer/crypto/verifier,
 * schema change or central service. The actual native factories enforce host eligibility.
 *
 * Sequence: prepareAllocation -> installOriginal (explicit original ACK resume if pending)
 * -> openBaselineSources -> separately sign in the required actor -> prepareNextCommand
 * -> HOST RETAINS THE EXACT REQUEST -> dispatchPrepared. A really logs out before B's own
 * opening; B really logs out before A creates R. Finally verifyBCashAndClose authenticates
 * B afresh, observes its own cash and requires actual native master closure.
 *
 * These are preparation operations, not execution of Offer/Cancel/Accept or the 15 whole
 * history cases. A prepared DTO or native Opened result is never counted as a business commit.
 * A failed/unknown call keeps its original checkpoint/pending request; this instance cannot
 * replace its IDs, redispatch, skip a step or claim restart/lost-response qualification.
 * Methods are confined to one owning coroutine; do not call them concurrently.
 */
internal class NativeRetailFixturePreparation(
    private val application: Application,
    val plan: NativeRetailFixturePlan,
) {
    val checkpoint: NativeRetailFixtureCheckpoint = NativeRetailFixtureCheckpoint()
    private var provisioner: LocalProvisioner? = null
    private var context: SelfOwnedLocalSecurityContext? = null
    private var master: AndroidSelfOwnedMasterService? = null
    private var original: AndroidOriginalLocalClient? = null
    private var tokens: LocalSessionTokens? = null
    private var revocationUnconfirmed: Boolean = false
    private var acknowledgementAttempted: Boolean = false
    private var baselineSourcesAttempted: Boolean = false
    private var provisionerCloseAttempted: Boolean = false
    private var contextCloseAttempted: Boolean = false
    private var masterCloseAttempted: Boolean = false
    private var masterAcquisitionUnconfirmed: Boolean = false
    private var commandIndex: Int = 0
    private var pending: NativeRetailFixtureCommandAttempt? = null

    val nextCommandKind: NativeRetailFixtureCommand?
        get() = NativeRetailFixtureCommand.entries.getOrNull(commandIndex)

    val requiredActorForNextCommand: DomainId?
        get() = nextCommandKind?.let {
            if (it == NativeRetailFixtureCommand.OPEN_DRAWER_B) plan.actorB else checkpoint.allocatedMaterial?.coordinates?.firstActorId
        }

    val pendingOriginal: NativeRetailFixtureCommandAttempt? get() = pending

    /** NEW synthetic founding only. Do not use this to recover retained or uncertain authority. */
    suspend fun prepareAllocation(installerSecret: CharArray, headSecret: CharArray): SelfOwnedNativeMaterialView {
        check(checkpoint.phase == NativeRetailFixturePhase.NEW)
        checkpoint.phase = NativeRetailFixturePhase.ALLOCATION_ATTEMPTED
        var complete = false
        try {
            checkpoint.step = "open_provisioner"
            val opened = AndroidSelfOwnedEnrollmentSource.open(application)
            checkpoint.provisionerOpen = opened
            val actual = when (opened) {
                is LocalProvisionerOpenResult.Opened -> opened.provisioner.also { provisioner = it }
                is LocalProvisionerOpenResult.Unavailable -> hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            }
            checkpoint.step = "prepare_company_and_head"
            val prepared = actual.companyAuthority.prepareNewCompanyAndHead(plan.foundingFacts)
            checkpoint.foundingPreparation = prepared
            val founding = nativeValue(prepared)
            expect(!founding.enrollmentRetained, NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
            checkpoint.step = "complete_company_and_head"
            val enrolled = ownedSecret(installerSecret) { installer ->
                ownedSecret(headSecret) { head -> actual.companyAuthority.completeNewCompanyAndHead(founding, installer, head) }
            }
            checkpoint.foundingCompletion = enrolled
            val company = nativeValue(enrolled)
            expect(company.headEnabled && company.policyIssuanceEnabled && company.installationIssuanceEnabled,
                NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            expect(company.organizationId == founding.organizationId && company.companyId == founding.companyId &&
                company.rootFingerprint == founding.rootFingerprint, NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "prepare_original_branch"
            val branch = actual.prepareBranchWithEnrolledCompany(SelfOwnedBranchFactsV1(
                company.rootFingerprint, plan.branchName, plan.protection,
            ))
            checkpoint.branchPreparation = branch
            val action = when (branch) {
                is LocalProvisionerPreparation.Prepared -> branch.action
                is LocalProvisionerPreparation.Unavailable -> hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            }
            checkpoint.step = "issue_original_branch_preparation"
            val grant = ownedSecret(installerSecret) { actual.issueOriginalPreparation(action, plan.installerLogin, it) }
            checkpoint.branchGrant = grant
            val packet = when (grant) {
                is LocalProvisionerGrant.Retained -> grant.packet
                is LocalProvisionerGrant.Unresolved -> hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            }
            expect(packet.coordinates.scope.company() == companyScope(company.organizationId, company.companyId),
                NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            expect(closeProvisioner(), NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED)

            checkpoint.step = "prepare_original_target"
            val target = AndroidSelfOwnedInstallationOwner.prepareOriginal(application)
            checkpoint.targetPreparation = target
            val preparedContext = nativeContext(target)
            val material = preparedContext.materialAtOpen
            checkpoint.allocatedMaterial = material
            expect(sameCoordinates(material.coordinates, packet.coordinates), NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            // A creating PREPARATION context is deliberately not a D7/history loan.
            // Await its real close, then use a separate existing-only acquisition below.
            expect(closeContext("close_preparation_target"), NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED)
            complete = true
            return material
        } finally {
            if (!complete) {
                close()
                checkpoint.phase = NativeRetailFixturePhase.HELD
                if (checkpoint.hold == null) checkpoint.hold = NativeRetailFixtureHold.CALL_DID_NOT_RETURN
            } else checkpoint.phase = NativeRetailFixturePhase.ALLOCATED_CLOSED
        }
    }

    /** The first account is complete requested data; only the real native head approves it. */
    suspend fun installOriginal(headSecret: CharArray, passwordA: CharArray,
        accountA: LocalAccountProvisioning = plan.requestedAccountA(coordinates())): LocalMasterInstallationOutcome {
        check(checkpoint.phase == NativeRetailFixturePhase.ALLOCATED_CLOSED && master == null && context == null)
        checkpoint.phase = NativeRetailFixturePhase.INSTALLATION_ATTEMPTED
        checkpoint.firstAccount = accountA
        var deliveredOutcome = false
        try {
            checkpoint.step = "open_original_installation_target"
            val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
            checkpoint.installationOpen = opened
            val actualContext = nativeContext(opened)
            expect(sameCoordinates(actualContext.materialAtOpen.coordinates, coordinates()), NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            expect(accountA.actorId == coordinates().firstActorId && accountA.policy.scope == coordinates().scope &&
                accountA.policy.revision == Revision(0) && accountA.admittedDeviceIds == setOf(coordinates().deviceId) &&
                accountA.login == plan.loginA, NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "open_installation_only_master"
            masterAcquisitionUnconfirmed = true
            val service = AndroidSelfOwnedMasterService.forOriginalInstallation(application, actualContext)
            checkpoint.installationMasterOpen = service
            val actualMaster = nativeMaster(service)
            checkpoint.installationMode = actualMaster.mode.value
            expect(actualMaster.mode.value == LocalMasterLifecycleMode.ALLOCATION_ONLY, NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
            checkpoint.step = "read_original_installation_setup"
            checkpoint.originalSetup = actualMaster.readOriginalSetup()
            expect(checkNotNull(checkpoint.originalSetup).definition == null && checkNotNull(checkpoint.originalSetup).receipt == null,
                NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
            checkpoint.step = "prepare_original_installation_definition"
            val definition = actualMaster.prepareOriginalDefinition(accountA, plan.authorityEpoch)
            checkpoint.installationDefinition = definition
            expect(sameAccount(definition.definition.firstAccount, accountA) &&
                definition.definition.authorityEpoch == plan.authorityEpoch, NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "install_original_definition"
            val outcome = ownedSecret(headSecret) { head ->
                ownedSecret(passwordA) { employee -> actualMaster.installOriginal(definition.definition, plan.headLogin, head, employee) }
            }
            checkpoint.installationOutcome = outcome
            if (outcome is LocalMasterInstallationOutcome.Installed) {
                expect(outcome.receipt.core.originalRequestId == definition.definition.originalRequestId &&
                    outcome.receipt.core.definitionDigest == definition.definitionDigest,
                    NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
                checkpoint.originalSetup = actualMaster.readOriginalSetup()
            }
            deliveredOutcome = true
            return outcome
        } finally {
            val closed = close()
            checkpoint.phase = when {
                !closed || !deliveredOutcome || checkpoint.hold != null -> NativeRetailFixturePhase.HELD
                checkpoint.installationOutcome is LocalMasterInstallationOutcome.Installed -> NativeRetailFixturePhase.INSTALLED_CLOSED
                checkpoint.installationOutcome == LocalMasterInstallationOutcome.AcknowledgementPending -> NativeRetailFixturePhase.ACK_PENDING_CLOSED
                else -> NativeRetailFixturePhase.HELD
            }
            if (checkpoint.phase == NativeRetailFixturePhase.HELD && checkpoint.hold == null)
                checkpoint.hold = if (closed) NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED else NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED
        }
    }

    /** Original ACK completion only. No fresh definition, password dispatch, IDs or SQL replay. */
    suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome {
        check(checkpoint.phase == NativeRetailFixturePhase.ACK_PENDING_CLOSED && !acknowledgementAttempted)
        acknowledgementAttempted = true
        checkpoint.phase = NativeRetailFixturePhase.INSTALLATION_ATTEMPTED
        var deliveredOutcome = false
        try {
            checkpoint.step = "open_original_ack_target"
            val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
            checkpoint.acknowledgementOpen = opened
            val actualContext = nativeContext(opened)
            expect(sameCoordinates(actualContext.materialAtOpen.coordinates, coordinates()), NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            masterAcquisitionUnconfirmed = true
            val service = AndroidSelfOwnedMasterService.forOriginalInstallation(application, actualContext)
            checkpoint.acknowledgementMasterOpen = service
            val actualMaster = nativeMaster(service)
            expect(actualMaster.mode.value == LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING, NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
            checkpoint.step = "resume_original_ack"
            val outcome = actualMaster.resumeOriginalAcknowledgement()
            checkpoint.acknowledgementOutcome = outcome
            if (outcome is LocalMasterInstallationOutcome.Installed) {
                val definition = checkNotNull(checkpoint.installationDefinition)
                expect(outcome.receipt.core.originalRequestId == definition.definition.originalRequestId &&
                    outcome.receipt.core.definitionDigest == definition.definitionDigest,
                    NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
                checkpoint.originalSetup = actualMaster.readOriginalSetup()
            }
            deliveredOutcome = true
            return outcome
        } finally {
            val closed = close()
            checkpoint.phase = if (closed && deliveredOutcome && checkpoint.hold == null &&
                checkpoint.acknowledgementOutcome is LocalMasterInstallationOutcome.Installed)
                NativeRetailFixturePhase.INSTALLED_CLOSED else NativeRetailFixturePhase.HELD
            if (checkpoint.phase == NativeRetailFixturePhase.HELD && checkpoint.hold == null)
                checkpoint.hold = if (closed) NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED else NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED
        }
    }

    /** Separate complete-history operational admission, native B enrollment and native signed SKU. */
    suspend fun openBaselineSources(headSecret: CharArray, passwordB: CharArray,
        accountB: LocalAccountProvisioning = plan.requestedAccountB(coordinates())) {
        check(checkpoint.phase == NativeRetailFixturePhase.INSTALLED_CLOSED && !baselineSourcesAttempted)
        baselineSourcesAttempted = true
        checkpoint.phase = NativeRetailFixturePhase.BASELINE_ATTEMPTED
        var delivered = false
        try {
            checkpoint.step = "open_original_runtime_target"
            val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
            checkpoint.runtimeOpen = opened
            val actualContext = nativeContext(opened)
            checkpoint.currentOriginalMaterial = actualContext.materialAtOpen
            expect(sameCoordinates(actualContext.materialAtOpen.coordinates, coordinates()), NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "open_original_operational_master"
            masterAcquisitionUnconfirmed = true
            val service = AndroidLocalBranchRuntime.openOriginalMaster(application, actualContext)
            checkpoint.runtimeMasterOpen = service
            val actualMaster = nativeMaster(service)
            checkpoint.runtimeMode = actualMaster.mode.value
            expect(actualMaster.mode.value == LocalMasterLifecycleMode.OPERATIONAL, NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
            val local = actualMaster.originalLocalClient().also { original = it }
            expect(local.isCurrent() && local.belongsTo(application) && sameCoordinates(local.material.coordinates, coordinates()),
                NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            val installed = checkNotNull(checkpoint.originalSetup).receipt
            expect(installed != null && local.receipt.digest == installed.digest, NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)

            checkpoint.secondAccount = accountB
            expect(accountB.actorId == plan.actorB && accountB.actorId != coordinates().firstActorId &&
                accountB.policy.scope == coordinates().scope && accountB.policy.revision == Revision(0) &&
                accountB.admittedDeviceIds == setOf(coordinates().deviceId) && accountB.login == plan.loginB,
                NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "prepare_native_employee_b"
            val preparation = local.prepareEmployeeEnrollment(plan.enrollmentRequestB, accountB, credentialWasProvided = true)
            checkpoint.enrollmentPreparationB = preparation
            val action = nativeValue(preparation)
            expect(action.requested.request.requestId == plan.enrollmentRequestB && action.requested.request.targetId == plan.actorB &&
                action.requested.credentialWasProvided && sameAccount(action.requested.definition, accountB),
                NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "approve_native_employee_b"
            val approval = ownedSecret(headSecret) { local.approveEmployeeEnrollment(action, plan.headLogin, it) }
            checkpoint.enrollmentApprovalB = approval
            val enrollment = nativeValue(approval)
            expect(enrollment.belongsTo(local) && !enrollment.isLookupOnly && enrollment.originalActionId == action.originalActionId &&
                enrollment.originalRequestId == plan.enrollmentRequestB &&
                enrollment.requested.definitionDigest == action.requested.definitionDigest, NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            checkpoint.step = "provision_native_employee_b"
            val result = ownedSecret(passwordB) { enrollment.provisionAccount(plan.enrollmentRequestB, accountB, it) }
            checkpoint.enrollmentOutcomeB = result
            expect(result is LocalCredentialAdministrationResult.Applied, NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            checkpoint.step = "lookup_original_employee_b"
            val lookup = enrollment.lookupAccountProvisioning(plan.enrollmentRequestB, accountB, credentialWasProvided = true)
            checkpoint.enrollmentLookupB = lookup
            expect(lookup is LocalProvisioningStatus.Applied &&
                lookup.eventId == (result as LocalCredentialAdministrationResult.Applied).eventId,
                NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)

            checkpoint.step = "prepare_native_signed_sku"
            val publications = local.originalBusinessPublications()
                ?: hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            val definition = plan.requestedSku(coordinates().scope.company())
            val content = LocalConfigurationCodec.canonicalPublication(definition)
            val proposal = try {
                CompanyBusinessPublicationProposal(definition.company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                    plan.publicationId, plan.skuId, content)
            } finally { content.fill(0) }
            checkpoint.publicationProposal = proposal
            val publication = publications.prepare(proposal)
            checkpoint.publicationPreparation = publication
            val publicationAction = nativeValue(publication)
            checkpoint.step = "approve_native_signed_sku"
            val signed = ownedSecret(headSecret) { publications.approve(publicationAction, plan.headLogin, it) }
            checkpoint.publicationApproval = signed
            val output = nativeValue(signed)
            expect(output.originalActionId == publicationAction.originalActionId && output.readNativeCompanySku().definition == definition &&
                output.proposal.contentDigest == proposal.contentDigest && output.signingKeyId == publicationAction.signingKeyId &&
                output.rootFingerprint == nativeValue(checkNotNull(checkpoint.foundingCompletion)).rootFingerprint,
                NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
            val canonical = LocalConfigurationCodec.canonicalPublication(definition)
            try {
                expect(NativeCompanyPublicationSignatureVerifier(publications.trust).verify(
                    definition.company, output.signingKeyId, canonical, output.signatureHex,
                ), NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
            } finally { canonical.fill(0) }
            delivered = true
        } finally {
            if (!delivered) {
                close()
                checkpoint.phase = NativeRetailFixturePhase.HELD
                if (checkpoint.hold == null) checkpoint.hold = NativeRetailFixtureHold.CALL_DID_NOT_RETURN
            }
        }
    }

    suspend fun signInA(password: CharArray) = signIn(coordinates().firstActorId, password)
    suspend fun signInB(password: CharArray) = signIn(plan.actorB, password)

    /** Real durable logout, not just token destruction. A nonreturned/uncertain revocation holds reuse. */
    suspend fun signOutCurrent() {
        val current = tokens ?: return
        val local = checkNotNull(original)
        checkpoint.step = "sign_out_current_employee"
        val attempt = NativeRetailFixtureSignOutAttempt(current.view.actorId, current.view.sessionId)
        checkpoint.signOutValues += attempt
        revocationUnconfirmed = true
        try {
            val outcome = try {
                local.client.logout(LocalGatewayCredentials(local.peer.connectionId, current.access))
            } catch (failure: Throwable) {
                markUndeliveredCall()
                throw failure
            }
            attempt.result = outcome
            if (outcome == LocalSignOutResult.Revoked) revocationUnconfirmed = false
            expect(outcome == LocalSignOutResult.Revoked, NativeRetailFixtureHold.REVOCATION_REQUIRED)
        } finally {
            current.destroy()
            tokens = null
        }
    }

    /**
     * Reads only genuine current protected projections to bind exact revisions. No mutation
     * is dispatched by this method. Retain the returned immutable request before calling
     * dispatchPrepared; the captured original session/policy/assignment also remain recorded.
     */
    suspend fun prepareNextCommand(): NativeRetailFixtureCommandAttempt {
        check(checkpoint.phase == NativeRetailFixturePhase.BASELINE_ATTEMPTED && pending == null)
        val step = checkNotNull(nextCommandKind)
        checkpoint.step = "prepare_${step.name.lowercase()}"
        val current = currentTokens()
        expect(current.view.actorId == requiredActorForNextCommand, NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
        val request = when (step) {
            NativeRetailFixtureCommand.INSTALL_SKU -> configuration(step,
                LocalConfigurationRequestV1.InstallCompanySku(nativeValue(checkNotNull(checkpoint.publicationApproval)).readNativeCompanySku()))
            NativeRetailFixtureCommand.CONFIGURE_WAREHOUSE -> {
                expectFreshConfiguration(LocalConfigurationProfile.WAREHOUSE, plan.warehouseId)
                configuration(step, LocalConfigurationRequestV1.ConfigureWarehouse(null, plan.warehouse))
            }
            NativeRetailFixtureCommand.CONFIGURE_ROUTE -> {
                expectFreshConfiguration(LocalConfigurationProfile.SALES_ROUTE, plan.routeId)
                configuration(step, LocalConfigurationRequestV1.ConfigureSalesRoute(null, plan.route))
            }
            NativeRetailFixtureCommand.APPROVE_OPENING -> {
                expectFreshConfiguration(LocalConfigurationProfile.BASIS, plan.openingApprovalId)
                configuration(step, LocalConfigurationRequestV1.ApproveReceiving(plan.openingApproval))
            }
            NativeRetailFixtureCommand.INITIALIZE_STOCK ->
                LocalStockRelationalCommandsV2.initializeFresh(plan.operation(step), plan.stockInitializationId, plan.reasonCode)
            NativeRetailFixtureCommand.OPENING_STOCK -> prepareOpeningStock(step)
            NativeRetailFixtureCommand.CONFIGURE_CUSTODIAN_A -> cash(step, LocalCashRequestV1.ConfigureCustodian(coordinates().firstActorId))
            NativeRetailFixtureCommand.CONFIGURE_CUSTODIAN_B -> cash(step, LocalCashRequestV1.ConfigureCustodian(plan.actorB))
            NativeRetailFixtureCommand.CONFIGURE_DRAWER_A -> cash(step, LocalCashRequestV1.ConfigureDrawer(plan.drawerA, "Synthetic A drawer", Currency.EGP))
            NativeRetailFixtureCommand.CONFIGURE_DRAWER_B -> cash(step, LocalCashRequestV1.ConfigureDrawer(plan.drawerB, "Synthetic B drawer", Currency.EGP))
            NativeRetailFixtureCommand.OPEN_DRAWER_A -> prepareDrawerOpening(step, plan.drawerA, plan.cashSessionA, plan.cashOpeningSourceA, plan.cashOpeningA)
            NativeRetailFixtureCommand.OPEN_DRAWER_B -> prepareDrawerOpening(step, plan.drawerB, plan.cashSessionB, plan.cashOpeningSourceB, plan.cashOpeningB)
            NativeRetailFixtureCommand.CREATE_ORDER_R -> prepareOrderR(step)
        }
        val actor = current.view.actorId
        val scope = coordinates().scope
        val attempt = NativeRetailFixtureCommandAttempt(step, request,
            OperationIdentity(scope.branchId, request.operationId, actor, 1, semanticDigest(scope, actor, request.command, request.payload).value),
            current.view.sessionId, current.view.policy.revision, coordinates().assignmentId)
        checkpoint.commandValues += attempt
        pending = attempt
        return attempt
    }

    /** Exactly one dispatch of the already returned request. Unknown replies do not advance. */
    suspend fun dispatchPrepared(attempt: NativeRetailFixtureCommandAttempt): MutationReply.Recorded {
        check(checkpoint.phase == NativeRetailFixturePhase.BASELINE_ATTEMPTED && pending === attempt && !attempt.callStarted)
        checkpoint.step = "dispatch_${attempt.step.name.lowercase()}"
        val current = currentTokens()
        expect(current.view.actorId == attempt.identity.originalActorId && current.view.sessionId == attempt.sessionId &&
            current.view.policy.revision == attempt.policyRevision && coordinates().assignmentId == attempt.assignmentId,
            NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
        attempt.callStarted = true // Retain unknown original BEFORE suspendable dispatch.
        val local = checkNotNull(original)
        val reply = try {
            local.client.execute(LocalGatewayCredentials(local.peer.connectionId, current.access), attempt.request)
        } catch (failure: Throwable) {
            markUndeliveredCall()
            throw failure
        }
        attempt.reply = reply // Retain the actual result before checking or decoding it.
        val recorded = reply as? MutationReply.Recorded ?: hold(NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
        expect(recorded.committed && !recorded.replayed && recorded.operationId == attempt.request.operationId &&
            recorded.originalActorId == attempt.identity.originalActorId && recorded.authorityEpoch == local.receipt.core.authorityEpoch,
            NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
        requireExpectedResult(attempt, recorded)
        when (attempt.step) {
            NativeRetailFixtureCommand.OPENING_STOCK -> observeOpeningReceipt()
            NativeRetailFixtureCommand.OPEN_DRAWER_A -> checkpoint.drawerSessionA = observeOwnDrawer(plan.cashSessionA, plan.drawerA, plan.cashOpeningA)
            NativeRetailFixtureCommand.OPEN_DRAWER_B -> checkpoint.drawerSessionB = observeOwnDrawer(plan.cashSessionB, plan.drawerB, plan.cashOpeningB)
            NativeRetailFixtureCommand.CREATE_ORDER_R -> observeOrderR(recorded)
            else -> Unit
        }
        commandIndex += 1
        pending = null
        return recorded
    }

    /** Final B own-cash read after R, no business mutation. No old A/B bearer survives closure. */
    suspend fun verifyBCashAndClose(passwordB: CharArray): NativeRetailFixtureCheckpoint {
        check(nextCommandKind == null && pending == null && checkpoint.orderR != null &&
            checkpoint.phase == NativeRetailFixturePhase.BASELINE_ATTEMPTED)
        var checked = false
        try {
            signOutCurrent()
            signInB(passwordB)
            checkpoint.step = "observe_b_drawer_after_r"
            val observed = observeOwnDrawer(plan.cashSessionB, plan.drawerB, plan.cashOpeningB)
            expect(observed == checkpoint.drawerSessionB)
            expect(ownCash().unsettledOrderIds.isEmpty())
            expect(checkNotNull(checkpoint.drawerSessionA).expectedCash + observed.expectedCash == Money(Currency.EGP, 150_000))
            signOutCurrent()
            checked = true
        } finally {
            val closed = close()
            checkpoint.phase = if (checked && closed && !revocationUnconfirmed) NativeRetailFixturePhase.BASELINE_PREPARED_CLOSED
                else NativeRetailFixturePhase.HELD
            if (checkpoint.phase == NativeRetailFixturePhase.HELD && checkpoint.hold == null)
                checkpoint.hold = NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED
        }
        if (checkpoint.phase != NativeRetailFixturePhase.BASELINE_PREPARED_CLOSED) hold(NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED)
        return checkpoint
    }

    /**
     * Drain the real owner even on failure/cancellation. False/throwing closure retains its
     * actual handle and forbids a replacement acquisition. Token destruction is not logout.
     */
    suspend fun close(): Boolean = withContext(NonCancellable) {
        var knownClosed = !masterAcquisitionUnconfirmed
        if (tokens != null) {
            try { signOutCurrent() } catch (_: Throwable) { knownClosed = false }
        }
        val actual = master
        if (actual != null) {
            if (masterCloseAttempted) knownClosed = false
            else {
                masterCloseAttempted = true
                val attempt = NativeRetailFixtureCloseAttempt("close_owned_master")
                checkpoint.closeValues += attempt
                try {
                    val result = actual.quiesceAndClose()
                    attempt.masterClosed = result
                    attempt.returnedNormally = true
                    if (result) { master = null; context = null; original = null }
                    else knownClosed = false
                } catch (_: Throwable) { knownClosed = false }
            }
            // After custody transfer, the old context's inert close is not master-close proof.
        } else if (context != null && !closeContext("close_owned_context")) knownClosed = false
        if (provisioner != null && !closeProvisioner()) knownClosed = false
        if (!knownClosed && checkpoint.hold == null) checkpoint.hold = NativeRetailFixtureHold.ORIGINAL_CLOSE_REQUIRED
        knownClosed && !revocationUnconfirmed
    }

    private suspend fun signIn(actor: DomainId, password: CharArray) {
        check(checkpoint.phase == NativeRetailFixturePhase.BASELINE_ATTEMPTED && pending == null && tokens == null)
        expect(!revocationUnconfirmed, NativeRetailFixtureHold.REVOCATION_REQUIRED)
        checkpoint.step = "sign_in_employee"
        val local = checkNotNull(original)
        expect(local.isCurrent() && local.peer.isCurrent(), NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
        val login = when (actor) {
            coordinates().firstActorId -> plan.loginA
            plan.actorB -> plan.loginB
            else -> hold(NativeRetailFixtureHold.ORIGINAL_IDENTITY_REQUIRED)
        }
        val attempt = NativeRetailFixtureAuthenticationAttempt(actor)
        checkpoint.authenticationValues += attempt
        val result = try {
            ownedSecret(password) { local.client.signIn(local.peer.connectionId, login, it) }
        } catch (failure: Throwable) {
            markUndeliveredCall()
            throw failure
        }
        when (result) {
            is LocalAuthenticationResult.Authenticated -> {
                val returned = result.tokens.also { tokens = it }
                attempt.delivery = NativeRetailFixtureAuthenticationDelivery.AUTHENTICATED
                attempt.view = returned.view
                expect(returned.view.actorId == actor && returned.view.login == login &&
                    returned.view.scope == coordinates().scope && returned.view.deviceId == local.peer.deviceId,
                    NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
            }
            is LocalAuthenticationResult.Denied -> {
                attempt.delivery = NativeRetailFixtureAuthenticationDelivery.DENIED; attempt.denial = result.reason
                hold(NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
            }
            LocalAuthenticationResult.Unavailable -> {
                attempt.delivery = NativeRetailFixtureAuthenticationDelivery.UNAVAILABLE
                hold(NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
            }
            LocalAuthenticationResult.StorageUncertain -> {
                attempt.delivery = NativeRetailFixtureAuthenticationDelivery.STORAGE_UNCERTAIN
                hold(NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
            }
        }
    }

    private suspend fun prepareOpeningStock(step: NativeRetailFixtureCommand): MutationRequest {
        val approval = configurationPage(LocalConfigurationProfile.BASIS, plan.openingApprovalId)
        expect((approval.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
        val basisRevision = approval.items.singleOrNull()?.revision ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        val empty = readStock()
        expect(empty.quantities.all { it.itemStorageRevision == null && it.rows.isEmpty() && it.unitId == null && it.quantityScale == null } &&
            empty.costs.all { it.itemStorageRevision == null && it.rows.isEmpty() }, NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
        val request = LocalGoodsReceiptRequest(plan.stockReceiptId, plan.openingApprovalId, basisRevision,
            listOf(LocalStockItemVersion(plan.skuId, null)), listOf(LocalGoodsReceiptLine(
                plan.stockReceiptLineId, plan.openingAllocationId, plan.stockSourceId, plan.skuId, plan.warehouseId,
                plan.quantityTen, conversion = null, lotId = null, expiresAt = null,
                disposition = LocalReceiptDisposition.RELEASED, originalValue = plan.stockValue,
            )), plan.reasonCode)
        return LocalStockRelationalCommandsV2.opening(plan.operation(step), request)
    }

    private suspend fun prepareDrawerOpening(step: NativeRetailFixtureCommand, drawerId: DomainId,
        sessionId: DomainId, sourceId: DomainId, opening: Money): MutationRequest {
        val current = currentTokens()
        val own = ownCash()
        expect(own.custodianId == current.view.actorId && own.session == null &&
            own.unsettledOrderIds.isEmpty() && own.unresolvedReturnIds.isEmpty())
        val drawer = drawerChoices().singleOrNull { it.id == drawerId } ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        expect(drawer.currency == Currency.EGP && !drawer.assigned && !drawer.awaitingClosingReceipt)
        return cash(step, LocalCashRequestV1.Open(sessionId, drawerId, drawer.revision, own.assignmentRevision,
            plan.businessDate, sourceId, opening, if (step == NativeRetailFixtureCommand.OPEN_DRAWER_A) plan.cashEvidenceA else plan.cashEvidenceB))
    }

    private suspend fun prepareOrderR(step: NativeRetailFixtureCommand): MutationRequest {
        expect(checkpoint.drawerSessionA != null && checkpoint.drawerSessionB != null &&
            checkpoint.enrollmentLookupB is LocalProvisioningStatus.Applied, NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
        expect(observeOwnDrawer(plan.cashSessionA, plan.drawerA, plan.cashOpeningA) == checkpoint.drawerSessionA)
        val stock = readStock()
        requireFrozenStock(stock)
        checkpoint.stockBeforeOrder = stock
        val catalog = configurationPage(LocalConfigurationProfile.SKU, plan.skuId)
        val sku = catalog.detail as? LocalConfigurationDetailV1.Sku ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        val expected = plan.requestedSku(coordinates().scope.company())
        expect(sku.skuId == expected.skuId && sku.productId == expected.productId && sku.baseUnitId == expected.baseUnitId &&
            sku.quantityScale == expected.quantityScale && sku.tracking == expected.tracking && sku.fulfillment == expected.fulfillment &&
            sku.active && sku.canSell && sku.prices == expected.prices)
        val route = configurationPage(LocalConfigurationProfile.SALES_ROUTE, plan.routeId)
        expect((route.detail as? LocalConfigurationDetailV1.SalesRoute)?.value == plan.route)
        val selectedRevision = catalog.catalogRevision ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        val selection = LocalOrderSelectionV1(plan.activity, plan.channel, Currency.EGP, selectedRevision,
            listOf(LocalOrderLineV1(plan.orderLineId, plan.orderPortionId, plan.skuId, plan.quantityTwo)), serviceContextId = null)
        return LocalCollectionStockCodecV2.command(plan.operation(step), LocalCollectionStockRequestV2.CreateOrder(plan.orderId, selection))
    }

    private fun requireExpectedResult(attempt: NativeRetailFixtureCommandAttempt, reply: MutationReply.Recorded) {
        when (attempt.step) {
            NativeRetailFixtureCommand.INSTALL_SKU, NativeRetailFixtureCommand.CONFIGURE_WAREHOUSE,
            NativeRetailFixtureCommand.CONFIGURE_ROUTE, NativeRetailFixtureCommand.APPROVE_OPENING -> {
                val result = LocalConfigurationCodec.decodeResult(reply.result)
                val id = when (attempt.step) {
                    NativeRetailFixtureCommand.INSTALL_SKU -> plan.skuId
                    NativeRetailFixtureCommand.CONFIGURE_WAREHOUSE -> plan.warehouseId
                    NativeRetailFixtureCommand.CONFIGURE_ROUTE -> plan.routeId
                    else -> plan.openingApprovalId
                }
                expect(result.code == "configuration.applied" && result.id == id && result.revision != null,
                    NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
            }
            NativeRetailFixtureCommand.INITIALIZE_STOCK -> {
                val result = LocalStockRelationalCommandsV2.decodeInitializationResult(attempt.request.payload, reply.result)
                expect(result.initializationId == plan.stockInitializationId && result.freshEmpty,
                    NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
            }
            NativeRetailFixtureCommand.OPENING_STOCK -> {
                val result = LocalStockRelationalCommandsV2.decodeResult(attempt.request.payload, reply.result)
                expect(result.kind == LocalStockResultKind.OPENED && result.documentId == plan.stockReceiptId && result.failure == null &&
                    result.items.singleOrNull()?.skuId == plan.skuId && result.items.singleOrNull()?.storageRevision != null,
                    NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
            }
            else -> {
                val result = LocalSalesResultCodec.decode(reply.result) as? LocalSalesResultV1.Applied
                    ?: hold(NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
                val expected = when (attempt.step) {
                    NativeRetailFixtureCommand.CONFIGURE_CUSTODIAN_A -> LocalSalesAction.CUSTODIAN_CONFIGURED to coordinates().firstActorId
                    NativeRetailFixtureCommand.CONFIGURE_CUSTODIAN_B -> LocalSalesAction.CUSTODIAN_CONFIGURED to plan.actorB
                    NativeRetailFixtureCommand.CONFIGURE_DRAWER_A -> LocalSalesAction.DRAWER_CONFIGURED to plan.drawerA
                    NativeRetailFixtureCommand.CONFIGURE_DRAWER_B -> LocalSalesAction.DRAWER_CONFIGURED to plan.drawerB
                    NativeRetailFixtureCommand.OPEN_DRAWER_A -> LocalSalesAction.CASH_OPENED to plan.cashSessionA
                    NativeRetailFixtureCommand.OPEN_DRAWER_B -> LocalSalesAction.CASH_OPENED to plan.cashSessionB
                    NativeRetailFixtureCommand.CREATE_ORDER_R -> LocalSalesAction.ORDER_CREATED to plan.orderId
                    else -> error("retail.fixture.command.invalid")
                }
                expect(result.action == expected.first && result.documentId == expected.second && result.stockItems.isEmpty(),
                    NativeRetailFixtureHold.COMMITTED_OUTCOME_REQUIRED)
                if (attempt.step == NativeRetailFixtureCommand.CREATE_ORDER_R) expect(result.amount == plan.orderPayable)
            }
        }
    }

    private suspend fun observeOpeningReceipt() {
        checkpoint.step = "observe_committed_opening"
        val receipt = LocalStockQueries.decodeReceipt(read(LocalStockCommands.receiptCosts(plan.stockReceiptId)).payload)
            ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        checkpoint.stockReceipt = receipt
        expect(receipt.receiptId == plan.stockReceiptId && receipt.basisId == plan.openingApprovalId &&
            receipt.basisKind == LocalStockBasisKind.OPENING && receipt.supplierId == null && receipt.costsIncluded &&
            receipt.recordedBy == coordinates().firstActorId && receipt.lines.size == 1)
        val line = receipt.lines.single()
        expect(line.lineId == plan.stockReceiptLineId && line.allocationId == plan.openingAllocationId && line.sourceId == plan.stockSourceId &&
            line.skuId == plan.skuId && line.warehouseId == plan.warehouseId && line.recordedQuantity == plan.quantityTen &&
            line.baseQuantity == plan.quantityTen && line.originalValue == plan.stockValue && line.disposition == LocalReceiptDisposition.RELEASED &&
            line.conversion == null && line.lotId == null && line.expiresAt == null && line.serialId == null)
        requireFrozenStock(readStock())
    }

    private suspend fun observeOrderR(recorded: MutationReply.Recorded) {
        checkpoint.step = "observe_committed_r"
        val decoded = LocalOrderReadCodecV2.decode(read(LocalOrderReadCodecV2.request(LocalOrderQueryV1.Order(plan.orderId))).payload)
        expect(decoded.nextAfter == null && decoded.orders.size == 1)
        val profiled = decoded.orders.single()
        checkpoint.orderR = profiled
        val order = profiled.order
        val result = LocalSalesResultCodec.decode(recorded.result) as LocalSalesResultV1.Applied
        val revision = result.revisions.singleOrNull { it.kind == LocalSalesStoreProfile.ORDER_KIND && it.id == plan.orderId }?.revision
            ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        expect(profiled.stockProfile == LocalOrderStockProfileV2.NORMALIZED && order.orderId == plan.orderId &&
            order.ownerId == coordinates().firstActorId && recorded.originalActorId == coordinates().firstActorId && order.revision == revision &&
            order.payable == plan.orderPayable && order.beforeDiscount == plan.orderPayable && order.discount == Money(Currency.EGP, 0) &&
            order.collectionId == null && order.saleId == null && order.handoffId == null && order.handoffRecipientId == null &&
            order.selection.activity == plan.activity && order.selection.channel == plan.channel && order.selection.currency == Currency.EGP &&
            order.selection.lines == listOf(LocalOrderLineV1(plan.orderLineId, plan.orderPortionId, plan.skuId, plan.quantityTwo)) &&
            order.lines.size == 1 && order.lines.single().portionId == plan.orderPortionId &&
            order.lines.single().quantity == plan.quantityTwo && order.lines.single().payable == plan.orderPayable)
        val before = checkNotNull(checkpoint.stockBeforeOrder)
        val after = readStock()
        checkpoint.stockAfterOrder = after
        requireFrozenStock(after)
        expect(before.quantities.flatMap { it.rows } == after.quantities.flatMap { it.rows } &&
            before.costs.flatMap { it.rows } == after.costs.flatMap { it.rows } &&
            before.quantities.first().itemStorageRevision == after.quantities.first().itemStorageRevision &&
            before.costs.first().itemStorageRevision == after.costs.first().itemStorageRevision)
        expect(observeOwnDrawer(plan.cashSessionA, plan.drawerA, plan.cashOpeningA) == checkpoint.drawerSessionA)
        val own = ownCash()
        expect(own.unsettledOrderIds == listOf(plan.orderId) && own.unresolvedReturnIds.isEmpty())
    }

    private suspend fun observeOwnDrawer(sessionId: DomainId, drawerId: DomainId, amount: Money): LocalCashSessionViewV1 {
        val result = LocalCashReadCodec.decode(read(LocalCashReadCodec.request(LocalCashQueryV1.Session(sessionId))).payload)
            as? LocalCashReadResultV1.Session ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        val session = result.session
        expect(session.id == sessionId && session.drawerId == drawerId && session.custodianId == currentTokens().view.actorId &&
            session.businessDate == plan.businessDate && session.status == "OPEN" && session.opening == amount && session.expectedCash == amount &&
            session.pendingAttemptIds.isEmpty() && session.pendingTransferIds.isEmpty() &&
            session.closingExpected == null && session.counted == null && session.variance == null && session.retainedFloat == null &&
            session.actualSafeDelivery == null && session.closingTransferId == null &&
            listOf(session.reservedCashOut, session.received, session.change, session.refunds, session.payIns, session.payOuts,
                session.safeDrops, session.externalCollections, session.externalRefunds).all { it == Money(Currency.EGP, 0) })
        val own = ownCash()
        expect(own.custodianId == session.custodianId && own.session == session && own.unresolvedReturnIds.isEmpty())
        return session
    }

    private suspend fun ownCash(): LocalCashReadResultV1.OwnCash =
        LocalCashReadCodec.decode(read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload) as? LocalCashReadResultV1.OwnCash
            ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)

    private suspend fun drawerChoices(): List<LocalDrawerChoiceV1> {
        var cursor: DomainId? = null
        var revision: Revision? = null
        val drawers = mutableListOf<LocalDrawerChoiceV1>()
        do {
            val result = read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers(cursor, 25)))
            if (revision == null) revision = result.branchRevision else expect(revision == result.branchRevision, NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            val page = LocalCashReadCodec.decode(result.payload) as? LocalCashReadResultV1.Drawers ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            expect(page.drawers.all { cursor == null || it.id.value > checkNotNull(cursor).value }, NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            drawers += page.drawers
            val next = page.nextAfter
            if (next != null) expect(page.drawers.isNotEmpty() && next == page.drawers.last().id &&
                (cursor == null || next.value > checkNotNull(cursor).value), NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            cursor = next
        } while (cursor != null)
        expect(drawers.map { it.id }.distinct().size == drawers.size, NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        return drawers
    }

    private suspend fun readStock(): NativeRetailFixtureStockObservation {
        val quantities = mutableListOf<LocalStockQuantityPage>()
        val costs = mutableListOf<LocalStockCostPage>()
        var branchRevision: Revision? = null
        var cursor: DomainId? = null
        do {
            val result = read(LocalStockCommands.quantities(plan.skuId, cursor, 25))
            if (branchRevision == null) branchRevision = result.branchRevision else expect(branchRevision == result.branchRevision, NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            val page = LocalStockQueries.decodeQuantities(result.payload)
            expect(page.skuId == plan.skuId && page.rows.all { cursor == null || it.positionId.value > checkNotNull(cursor).value },
                NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            quantities += page
            val next = page.nextAfter
            if (next != null) expect(page.rows.isNotEmpty() && next == page.rows.last().positionId &&
                (cursor == null || next.value > checkNotNull(cursor).value), NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            cursor = next
        } while (cursor != null)
        do {
            val result = read(LocalStockCommands.costs(plan.skuId, cursor, 25))
            expect(branchRevision == result.branchRevision, NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            val page = LocalStockQueries.decodeCosts(result.payload)
            expect(page.skuId == plan.skuId && page.rows.all { cursor == null || it.poolId.value > checkNotNull(cursor).value },
                NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            costs += page
            val next = page.nextAfter
            if (next != null) expect(page.rows.isNotEmpty() && next == page.rows.last().poolId &&
                (cursor == null || next.value > checkNotNull(cursor).value), NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
            cursor = next
        } while (cursor != null)
        expect(quantities.all { it.itemStorageRevision == quantities.first().itemStorageRevision } &&
            costs.all { it.itemStorageRevision == quantities.first().itemStorageRevision }, NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        return NativeRetailFixtureStockObservation(quantities.toList(), costs.toList(), checkNotNull(branchRevision))
    }

    private fun requireFrozenStock(observed: NativeRetailFixtureStockObservation) {
        val quantities = observed.quantities.flatMap { it.rows }
        val costs = observed.costs.flatMap { it.rows }
        expect(quantities.map { it.positionId }.distinct().size == quantities.size && quantities.size == 1 && costs.size == 1 &&
            observed.quantities.all { it.itemStorageRevision != null && it.unitId == plan.baseUnitId && it.quantityScale == 0 })
        val row = quantities.single()
        val cost = costs.single()
        val zero = Quantity(plan.baseUnitId, 0, 0)
        expect(row.position.skuId == plan.skuId && row.position.warehouseId == plan.warehouseId &&
            row.position.custody == StockCustody.RELEASED && row.position.supplierId == null && row.position.lotId == null &&
            row.position.serialId == null && row.onHand == plan.quantityTen && row.available == plan.quantityTen && row.reserved == zero &&
            row.safety == StockSafety.CLEAR && !row.expiredAtRead && row.expiresAt == null &&
            row.heldQuantity == zero && row.heldShortageQuantity == zero && cost.warehouseId == plan.warehouseId &&
            cost.quantity == plan.quantityTen && cost.carryingValue == plan.stockValue)
        // Exact ratio: EGP200 / 10 units = EGP20/unit. No rounded/floating cost field.
        expect(cost.carryingValue.minor == 2_000L * cost.quantity.scaled && cost.quantity.scale == 0)
    }

    private suspend fun configurationPage(kind: String, id: DomainId): LocalConfigurationPageV1 {
        val page = LocalConfigurationCodec.decodePage(read(ReadRequest(LocalConfigurationProfile.QUERY,
            LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id = id)))).payload)
        expect(page.kind == kind && page.nextAfter == null && page.items.size <= 1 && page.items.all { it.id == id },
            NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
        return page
    }

    private suspend fun expectFreshConfiguration(kind: String, id: DomainId) {
        val current = configurationPage(kind, id)
        expect(current.items.isEmpty() && current.detail == null, NativeRetailFixtureHold.ORIGINAL_MODE_REQUIRED)
    }

    private suspend fun read(request: ReadRequest): ReadReply.Data {
        val current = currentTokens()
        val local = checkNotNull(original)
        val attempt = NativeRetailFixtureReadAttempt(checkpoint.step, current.view.actorId, request)
        checkpoint.readValues += attempt
        val result = local.client.read(LocalGatewayCredentials(local.peer.connectionId, current.access), request)
        attempt.reply = result
        return result as? ReadReply.Data ?: hold(NativeRetailFixtureHold.CURRENT_READ_REQUIRED)
    }

    private fun currentTokens(): LocalSessionTokens {
        val current = tokens ?: hold(NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
        expect(original?.isCurrent() == true && original?.peer?.isCurrent() == true && !revocationUnconfirmed,
            NativeRetailFixtureHold.CURRENT_AUTHENTICATION_REQUIRED)
        return current
    }

    private fun configuration(step: NativeRetailFixtureCommand, request: LocalConfigurationRequestV1): MutationRequest =
        MutationRequest(plan.operation(step), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request))

    private fun cash(step: NativeRetailFixtureCommand, request: LocalCashRequestV1): MutationRequest =
        MutationRequest(plan.operation(step), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request))

    private fun nativeContext(result: SelfOwnedNativeOpenResult): SelfOwnedLocalSecurityContext = when (result) {
        is SelfOwnedNativeOpenResult.Opened -> result.context.also { context = it; contextCloseAttempted = false }
        is SelfOwnedNativeOpenResult.Unresolved -> hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
    }

    private fun nativeMaster(result: AndroidSelfOwnedMasterOpenResult): AndroidSelfOwnedMasterService = when (result) {
        is AndroidSelfOwnedMasterOpenResult.Opened -> result.service.also {
            master = it; masterCloseAttempted = false; masterAcquisitionUnconfirmed = false
        }
        is AndroidSelfOwnedMasterOpenResult.Unresolved -> hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
    }

    private fun <T> nativeValue(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> hold(NativeRetailFixtureHold.NATIVE_OUTCOME_REQUIRED)
    }

    private suspend fun closeProvisioner(): Boolean = withContext(NonCancellable) {
        val actual = provisioner ?: return@withContext true
        if (provisionerCloseAttempted) return@withContext false
        provisionerCloseAttempted = true
        val attempt = NativeRetailFixtureCloseAttempt("close_original_provisioner")
        checkpoint.closeValues += attempt
        try { actual.close(); attempt.returnedNormally = true; provisioner = null; true } catch (_: Throwable) { false }
    }

    private suspend fun closeContext(step: String): Boolean = withContext(NonCancellable) {
        val actual = context ?: return@withContext true
        if (contextCloseAttempted) return@withContext false
        contextCloseAttempted = true
        val attempt = NativeRetailFixtureCloseAttempt(step)
        checkpoint.closeValues += attempt
        try { actual.close(); attempt.returnedNormally = true; context = null; true } catch (_: Throwable) { false }
    }

    private fun coordinates(): SelfOwnedAllocationCoordinatesV1 = checkNotNull(checkpoint.allocatedMaterial).coordinates

    private fun sameCoordinates(left: SelfOwnedAllocationCoordinatesV1, right: SelfOwnedAllocationCoordinatesV1): Boolean =
        left.scope == right.scope && left.allocationId == right.allocationId && left.installationId == right.installationId &&
            left.assignmentId == right.assignmentId && left.deviceId == right.deviceId && left.storageInstanceId == right.storageInstanceId &&
            left.storageKeyId == right.storageKeyId && left.pepperKeyId == right.pepperKeyId &&
            left.targetSigningKeyId == right.targetSigningKeyId && left.firstActorId == right.firstActorId

    private fun sameAccount(left: LocalAccountProvisioning, right: LocalAccountProvisioning): Boolean {
        val a = left.policy
        val b = right.policy
        val grantsA = a.grants
        val grantsB = b.grants
        val approvalA = a.approvalPolicy
        val approvalB = b.approvalPolicy
        val sameApproval = if (approvalA == null || approvalB == null) approvalA == null && approvalB == null
            else approvalA.validForMillis == approvalB.validForMillis &&
                approvalA.namedSelfApprovalActions == approvalB.namedSelfApprovalActions
        return left.actorId == right.actorId && left.login == right.login && left.tokenPolicy == right.tokenPolicy &&
            left.admittedDeviceIds == right.admittedDeviceIds && a.scope == b.scope && a.actorId == b.actorId &&
            a.revision == b.revision && a.sessionPolicy == b.sessionPolicy && a.throttlePolicy == b.throttlePolicy &&
            sameApproval && grantsA.size == grantsB.size && grantsA.zip(grantsB).all { (x, y) ->
                x.permission == y.permission && x.scope == y.scope && x.effect == y.effect && x.limits == y.limits &&
                    x.ownerAccess == y.ownerAccess && x.namedSubjectId == y.namedSubjectId && x.targetIds == y.targetIds &&
                    x.delegationCeiling == y.delegationCeiling
            }
    }

    private fun companyScope(organization: DomainId, company: DomainId) = com.ukkera.pos.security.CompanyScope(organization, company)

    private fun expect(value: Boolean, reason: NativeRetailFixtureHold = NativeRetailFixtureHold.FROZEN_BASELINE_MISMATCH) {
        if (!value) hold(reason)
    }

    private fun hold(reason: NativeRetailFixtureHold): Nothing {
        checkpoint.hold = reason
        checkpoint.phase = NativeRetailFixturePhase.HELD
        throw NativeRetailFixtureHeld(checkpoint.step, reason)
    }

    private fun markUndeliveredCall() {
        checkpoint.phase = NativeRetailFixturePhase.HELD
        if (checkpoint.hold == null) checkpoint.hold = NativeRetailFixtureHold.CALL_DID_NOT_RETURN
    }

    private suspend fun <T> ownedSecret(source: CharArray, use: suspend (CharArray) -> T): T {
        val owned = source.copyOf()
        return try { use(owned) } finally { owned.fill('\u0000') }
    }

    override fun toString(): String = "NativeRetailFixturePreparation([REDACTED])"
}
