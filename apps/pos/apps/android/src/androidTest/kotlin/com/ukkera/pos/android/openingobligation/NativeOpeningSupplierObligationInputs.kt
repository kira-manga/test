package com.ukkera.pos.android.openingobligation

import android.content.res.AssetManager
import android.util.Base64
import com.ukkera.pos.catalog.store.LocalOpeningSupplierDocumentaryEvidenceV1
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationCommands
import com.ukkera.pos.catalog.store.LocalRecordOpeningSupplierObligationV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Frozen A inputs for the absent-S1 variant, not evidence of native scope or authority. */
internal class NativeOpeningSupplierOriginal private constructor(
    val request: MutationRequest,
    val value: LocalRecordOpeningSupplierObligationV1,
    val documentBytes: ByteArray,
    val assetSha256: String,
    val documentaryScope: BranchScope,
) {
    fun retainedInput(): JSONObject = JSONObject()
        .put("fixtureVersion", "1").put("original", "A").put("variant", "ABSENT_SUPPLIER_NONPOSTING")
        .put("documentaryOrganizationId", documentaryScope.organizationId.value)
        .put("documentaryCompanyId", documentaryScope.companyId.value)
        .put("documentaryBranchId", documentaryScope.branchId.value)
        .put("commandName", request.command.name).put("commandVersion", request.command.version)
        .put("operationId", request.operationId.value).put("obligationId", value.obligationId.value)
        .put("supplierId", value.supplierId.value).put("evidenceId", value.sourceEvidenceId.value)
        .put("expectedSupplierRevision", value.expectedSupplierRevision.value.toString())
        .put("reasonCode", value.reasonCode).put("actionApprovalId", JSONObject.NULL)
        .put("sourceIdentity", value.sourceDocument.sourceIdentity).put("reference", value.sourceReference)
        .put("originalDate", value.openingDate.canonicalString()).put("amountMinor", value.amount.minor.toString())
        .put("currency", value.amount.currency.name).put("terms", value.paymentTerms)
        .put("documentByteSize", documentBytes.size).put("documentAssetSha256", assetSha256)
        .put("canonicalOriginalRequestBase64", openingRetentionBase64(request.payload.copyBytes()))

    companion object {
        fun read(assets: AssetManager): NativeOpeningSupplierOriginal {
            val fixtures = assets.open("opening-obligation/fixtures-v1.json").use {
                JSONObject(it.readBytes().decodeToString(throwOnInvalidSequence = true))
            }
            check(fixtures.getString("version") == "1" && fixtures.getBoolean("syntheticOnly"))
            val ids = fixtures.getJSONObject("ids")
            val originals = fixtures.getJSONArray("originals")
            val original = (0 until originals.length()).map(originals::getJSONObject).single { it.getString("label") == "A" }
            check(original.getString("currency") == "EGP" && original.getString("evidenceFile") == "fixtures/evidence-a.txt")
            val document = assets.open("opening-obligation/evidence-a.txt").use { it.readBytes() }
            check(document.size == original.getInt("evidenceByteSize") && sha256(document).value == original.getString("evidenceSha256"))
            val text = document.decodeToString(throwOnInvalidSequence = true)
            check(document.contentEquals(text.encodeToByteArray(throwOnInvalidSequence = true)))
            val value = LocalRecordOpeningSupplierObligationV1(
                obligationId = DomainId("81111111-1111-4111-8111-111111111111"),
                supplierId = DomainId(ids.getString(original.getString("supplier"))), expectedSupplierRevision = Revision(1),
                sourceEvidenceId = DomainId("91111111-1111-4111-8111-111111111111"),
                sourceReference = original.getString("originalReference"),
                sourceDocument = LocalOpeningSupplierDocumentaryEvidenceV1(original.getString("sourceIdentity"), text),
                openingDate = BusinessDate.parseCanonical(original.getString("originalDate")),
                amount = Money(Currency.EGP, original.getString("amountMinor").toLong()),
                paymentTerms = original.getString("terms"), reasonCode = "opening.synthetic.migration")
            return NativeOpeningSupplierOriginal(
                LocalOpeningSupplierObligationCommands.record(OperationId(ids.getString(original.getString("operation"))), value),
                value, document, original.getString("evidenceSha256"),
                BranchScope(DomainId(ids.getString(original.getString("organization"))),
                    DomainId(ids.getString(original.getString("company"))), DomainId(ids.getString(original.getString("postingBranch")))))
        }
    }
}

/** Requested synthetic data only. The real company head must approve each native definition. */
internal class NativeOpeningSupplierPlan(
    val namespace: DomainId,
    val protection: SelfOwnedKeyProtectionV1,
    val original: NativeOpeningSupplierOriginal,
    val positiveSupplierSetup: NativePositiveOpeningSupplierSetup? = null,
) {
    val installerLogin = "opening-obligation-installer"
    val headLogin = "opening-obligation-head"
    val founderLogin = LocalLoginIdentifier("opening-obligation-founder")
    val operatorLogin = LocalLoginIdentifier("opening-obligation-operator")
    val operatorId = id("enrolled-operator")
    val enrollmentRequestId = id("operator-enrollment")
    val branchName = if (positiveSupplierSetup == null) "Synthetic absent-supplier branch" else "Synthetic positive-opening branch"
    val authorityEpoch = Revision(1)
    private val sessionPolicy = LocalSessionPolicy(maximumConcurrentSessions = 1)
    private val throttlePolicy = ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000,
        maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000)
    private val tokenPolicy = LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000)
    val founding = LocalCompanyFoundingFactsV1(
        SelfOwnedCompanyFactsV1("Synthetic opening supplier company", "Synthetic installer", installerLogin, protection),
        "Synthetic opening company head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        LocalCompanyHeadPurposeV1.entries.toSet())

    init {
        positiveSupplierSetup?.let { check(it.namespace == namespace && it.original === original) }
    }

    /** This installation account has NO business rights and is never signed in by the test. */
    fun founder(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning =
        account(coordinates, coordinates.firstActorId, founderLogin, emptyList())

    /** Negative default: exactly three grants. Only the explicit separate positive input adds S1 administration. */
    fun operator(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        check(operatorId != coordinates.firstActorId)
        val openingGrants = listOf(
            grant(coordinates.scope, SecurityAction.PURCHASING_APPROVE,
                original.value.amount, setOf(original.value.obligationId)),
            grant(coordinates.scope, SecurityAction.PURCHASING_READ),
            grant(coordinates.scope, SecurityAction.INVENTORY_COST_READ))
        val supplierGrant = positiveSupplierSetup?.let { setup ->
            PermissionGrant(Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER), coordinates.scope,
                GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY, operatorId, setOf(setup.supplier.id), null)
        }
        return account(coordinates, operatorId, operatorLogin,
            if (supplierGrant == null) openingGrants else listOf(supplierGrant) + openingGrants)
    }

    fun retainedPlan(): JSONObject = JSONObject().put("namespace", namespace.value).put("keyProtection", protection.name)
        .put("installerLogin", installerLogin).put("headLogin", headLogin).put("founderLogin", founderLogin.value)
        .put("operatorLogin", operatorLogin.value).put("operatorId", operatorId.value)
        .put("enrollmentRequestId", enrollmentRequestId.value).put("branchName", branchName)
        .put("authorityEpoch", authorityEpoch.value.toString()).put("founderBusinessGrants", JSONArray())
        .put("companyName", founding.company.companyName).put("installerName", founding.company.responsiblePersonName)
        .put("headName", founding.headName).put("headLifetime", founding.headLifetime.name)
        .put("headPurposes", JSONArray(founding.purposes.map { it.name }))
        .put("nativeScopeBinding", "ACTUAL_ORIGINAL_ORGANIZATION_COMPANY_BRANCH")
        .put("founderActorBinding", "ACTUAL_ORIGINAL_FIRST_ACTOR_NEVER_SIGNED_IN")
        .put("admittedDeviceBinding", "ACTUAL_ORIGINAL_DEVICE_ONLY")
        .put("requestedAccountPolicyRevision", "0").put("credentialWasProvided", true)
        .put("maximumConcurrentSessions", sessionPolicy.maximumConcurrentSessions)
        .put("freeFailures", throttlePolicy.freeFailures).put("initialDelayMillis", throttlePolicy.initialDelayMillis)
        .put("maximumDelayMillis", throttlePolicy.maximumDelayMillis)
        .put("attemptTimeoutMillis", throttlePolicy.attemptTimeoutMillis)
        .put("accessLifetimeMillis", tokenPolicy.accessLifetimeMillis).put("workLifetimeMillis", tokenPolicy.workLifetimeMillis)
        .put("approvalPolicy", JSONObject.NULL)
        .put("operatorGrantIntent", JSONArray(listOf(
            retainedGrantIntent(SecurityAction.PURCHASING_APPROVE, original.value.amount, original.value.obligationId),
            retainedGrantIntent(SecurityAction.PURCHASING_READ), retainedGrantIntent(SecurityAction.INVENTORY_COST_READ))))
        .put("originalInput", original.retainedInput()).put("wholeCases", "NOT_RUN")
        .also { retained -> positiveSupplierSetup?.let { setup ->
            val openingIntents = retained.getJSONArray("operatorGrantIntent")
            val positiveIntents = JSONArray().put(retainedGrantIntent(SecurityAction.SUPPLIER_ADMINISTRATION,
                target = setup.supplier.id).put("verb", PermissionVerb.ADMINISTER.name))
            for (index in 0 until openingIntents.length()) positiveIntents.put(openingIntents.getJSONObject(index))
            retained.put("operatorGrantIntent", positiveIntents).put("originalInput", setup.retainedOriginalInput())
                .put("supplierConfiguration", setup.retainedConfiguration())
        } }

    private fun retainedGrantIntent(action: SecurityAction, amount: Money? = null, target: DomainId? = null): JSONObject =
        JSONObject().put("action", action.name).put("verb", PermissionVerb.PERFORM.name)
            .put("scopeBinding", "ACTUAL_ORIGINAL_BRANCH").put("effect", GrantEffect.ALLOW.name)
            .put("ownerAccess", OwnerAccess.OWN_ONLY.name).put("namedSubjectId", operatorId.value)
            .put("targetIds", target?.let { JSONArray(listOf(it.value)) } ?: JSONObject.NULL)
            .put("maximumAmountMinor", amount?.minor?.toString() ?: JSONObject.NULL)
            .put("maximumAmountCurrency", amount?.currency?.name ?: JSONObject.NULL)
            .put("maximumPercentageBasisPoints", JSONObject.NULL).put("noMonetaryCeiling", JSONObject.NULL)
            .put("delegationCeiling", JSONObject.NULL)

    private fun grant(scope: BranchScope, action: SecurityAction, amount: Money? = null,
        targets: Set<DomainId>? = null) = PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope,
        GrantEffect.ALLOW, PermissionLimits(maximumAmount = amount), OwnerAccess.OWN_ONLY, operatorId, targets, null)

    private fun account(coordinates: SelfOwnedAllocationCoordinatesV1, actor: DomainId,
        login: LocalLoginIdentifier, grants: List<PermissionGrant>) = LocalAccountProvisioning(actor, login,
        ActivatedLocalPolicy(coordinates.scope, actor, Revision(0), grants,
            sessionPolicy, throttlePolicy, approvalPolicy = null), tokenPolicy,
        setOf(coordinates.deviceId))

    private fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "opening-supplier-obligation-v1/${namespace.value}/$label".encodeToByteArray()).toString())
}

/** Private retained synthetic input data only: never a credential, permit, native witness or result. */
internal fun retainedOpeningAccount(value: LocalAccountProvisioning): JSONObject = JSONObject()
    .put("actorId", value.actorId.value).put("login", value.login.value)
    .put("organizationId", value.policy.scope.organizationId.value).put("companyId", value.policy.scope.companyId.value)
    .put("branchId", value.policy.scope.branchId.value).put("policyRevision", value.policy.revision.value.toString())
    .put("admittedDeviceIds", JSONArray(value.admittedDeviceIds.map { it.value }))
    .put("maximumConcurrentSessions", value.policy.sessionPolicy.maximumConcurrentSessions)
    .put("freeFailures", value.policy.throttlePolicy.freeFailures)
    .put("initialDelayMillis", value.policy.throttlePolicy.initialDelayMillis)
    .put("maximumDelayMillis", value.policy.throttlePolicy.maximumDelayMillis)
    .put("attemptTimeoutMillis", value.policy.throttlePolicy.attemptTimeoutMillis)
    .put("accessLifetimeMillis", value.tokenPolicy.accessLifetimeMillis)
    .put("workLifetimeMillis", value.tokenPolicy.workLifetimeMillis).put("approvalPolicy", JSONObject.NULL)
    .put("grants", JSONArray(value.policy.grants.map { grant -> JSONObject()
        .put("action", grant.permission.action.name).put("verb", grant.permission.verb.name)
        .put("scopeBinding", "SAME_EXACT_BRANCH_AS_ACCOUNT")
        .put("effect", grant.effect.name).put("ownerAccess", grant.ownerAccess.name)
        .put("namedSubjectId", grant.namedSubjectId?.value ?: JSONObject.NULL)
        .put("targetIds", grant.targetIds?.let { JSONArray(it.map { target -> target.value }) } ?: JSONObject.NULL)
        .put("maximumAmountMinor", grant.limits.maximumAmount?.minor?.toString() ?: JSONObject.NULL)
        .put("maximumAmountCurrency", grant.limits.maximumAmount?.currency?.name ?: JSONObject.NULL)
        .put("maximumPercentageBasisPoints", grant.limits.maximumPercentageBasisPoints ?: JSONObject.NULL)
        .put("noMonetaryCeiling", grant.limits.noMonetaryCeiling?.currency?.name ?: JSONObject.NULL)
        .put("delegationCeiling", JSONObject.NULL) }))

/** The caller gives this helper an owned copy, never an access/refresh/head secret. */
internal fun openingRetentionBase64(bytes: ByteArray): String =
    try { Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }

internal fun requireSameOpeningPolicy(expected: ActivatedLocalPolicy, actual: ActivatedLocalPolicy) {
    check(expected.actorId == actual.actorId && expected.scope == actual.scope && expected.revision == actual.revision &&
        expected.sessionPolicy == actual.sessionPolicy && expected.throttlePolicy == actual.throttlePolicy &&
        expected.approvalPolicy == null && actual.approvalPolicy == null && expected.grants.size == actual.grants.size)
    check(expected.grants.zip(actual.grants).all { (a, b) ->
        a.permission == b.permission && a.scope == b.scope && a.effect == b.effect && a.limits == b.limits &&
            a.ownerAccess == b.ownerAccess && a.namedSubjectId == b.namedSubjectId && a.targetIds == b.targetIds &&
            a.delegationCeiling == b.delegationCeiling })
}

internal fun requireSameOpeningAccount(expected: LocalAccountProvisioning, actual: LocalAccountProvisioning) {
    check(expected.actorId == actual.actorId && expected.login == actual.login && expected.tokenPolicy == actual.tokenPolicy &&
        expected.admittedDeviceIds == actual.admittedDeviceIds)
    requireSameOpeningPolicy(expected.policy, actual.policy)
}
