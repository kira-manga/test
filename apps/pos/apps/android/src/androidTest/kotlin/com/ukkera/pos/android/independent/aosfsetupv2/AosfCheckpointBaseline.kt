package com.ukkera.pos.android.independent.aosfsetupv2

import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.catalog.store.LocalActivateInitialBranchActivityV1
import com.ukkera.pos.catalog.store.LocalBranchActivityCodecV1
import com.ukkera.pos.catalog.store.LocalBranchActivityProfileV1
import com.ukkera.pos.catalog.store.LocalCompanyActivityPublicationCodecV1
import com.ukkera.pos.catalog.store.LocalPublishedCompanyActivityProfileV1
import com.ukkera.pos.catalog.store.readNativeCompanyActivityProfile
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProjectionV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointTargetV1
import com.ukkera.pos.catalog.store.setup.LocalSetupConfigurationRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupFiscalIdentityV1
import com.ukkera.pos.catalog.store.setup.LocalSetupImportModeV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningChoiceV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import org.json.JSONObject

/** Exact original-bound checkpoint preparation of the frozen AOSF C/D setup-only baseline. */
internal data class AosfCheckpointBaseline(
    val binding: LocalSetupOriginalBindingV1,
    val branchActivityOperationId: OperationId,
    val configurationOperationId: OperationId,
    val catalogImportOperationId: OperationId,
    val openingOperationId: OperationId,
    val projection: LocalSetupCheckpointProjectionV1,
) {
    fun facts(): JSONObject = JSONObject()
        .put("installationId", binding.installationId.value)
        .put("originalRequestId", binding.originalRequestId.value)
        .put("issuedAttemptId", binding.issuedAttemptId.value)
        .put("attemptSequence", binding.attemptSequence)
        .put("definitionDigest", binding.definitionDigest.value)
        .put("genesisDigest", binding.genesisDigest.value)
        .put("receiptDigest", binding.receiptDigest.value)
        .put("branchActivityOperationId", branchActivityOperationId.value)
        .put("configurationOperationId", configurationOperationId.value)
        .put("catalogImportOperationId", catalogImportOperationId.value)
        .put("openingOperationId", openingOperationId.value)
        .put("configuration", projection.configuration?.operationId?.value ?: "ABSENT")
        .put("catalogImport", projection.catalogImport?.operationId?.value ?: "ABSENT")
        .put("opening", projection.opening?.operationId?.value ?: "ABSENT")
}

/**
 * Settles the frozen AOSF setup-only prerequisite chain through the registered production
 * checkpoint commands: the real signed branch-activity publication/activation, required
 * configuration, explicit no-import catalog settlement and explicit-zero opening settlement.
 *
 * The frozen AOSF-C/D business ledger is exactly zero. Therefore this fixture deliberately
 * declares the production no-import/zero choices instead of importing a sentinel SKU or opening
 * stock/float. Every declared row/effect would be verified by the production handlers; none is
 * fabricated here. No ACK or go-live is performed by this function.
 */
internal suspend fun prepareAosfCheckpointBaseline(
    owner: AosfNativeOwner,
    plan: AosfFixturePlan,
    settled: AosfSetupOutcome.Installed,
    anchor: AosfAnchor,
    sessions: AosfSessionLifetime,
    tokens: com.ukkera.pos.security.LocalSessionTokens,
    policy: com.ukkera.pos.security.ActivatedLocalPolicy,
    headSecret: CharArray,
    evidence: AosfEvidence,
): AosfCheckpointBaseline {
    val original = checkNotNull(owner.original) { "The actual original owner is not open" }
    check(original.isCurrent() && original.peer.isCurrent())
    val company = BranchScope(settled.branch.organizationId, settled.branch.companyId,
        settled.branch.branchId).company()
    val publications = checkNotNull(original.originalBusinessPublications()) {
        "The actual original native publication owner is unavailable"
    }

    val activityDefinition = LocalPublishedCompanyActivityProfileV1(company, DomainId.random(),
        DomainId.random(), Revision(0), setOf(LocalSalesActivity.SUPERMARKET))
    val activityProposal = CompanyBusinessPublicationProposal(company,
        CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1,
        activityDefinition.publicationId, activityDefinition.profileId,
        LocalCompanyActivityPublicationCodecV1.canonicalPublication(activityDefinition))
    val activityAction = nativeFixtureValue(publications.prepare(activityProposal))
    val activityOutput = withAosfSecret(headSecret) { head ->
        nativeFixtureValue(publications.approve(activityAction, plan.headLogin, head))
    }
    val signedActivity = activityOutput.readNativeCompanyActivityProfile()
    val branchActivityOperationId = OperationId.random()
    val activityReply = command(original, sessions, tokens,
        LocalBranchActivityCodecV1.activateInitial(branchActivityOperationId,
            LocalActivateInitialBranchActivityV1(
                LocalCompanyActivityPublicationCodecV1.signedPublication(signedActivity))),
        "signed branch-activity activation", evidence)
    check(LocalBranchActivityCodecV1.decodeResult(activityReply.result).code ==
        LocalBranchActivityProfileV1.ACTIVATED) {
        "The real signed initial branch activity did not activate"
    }

    val binding = LocalSetupOriginalBindingV1(anchor.installation, DomainId(anchor.setupRequestId),
        DomainId(anchor.issuedAttemptId), anchor.attemptSequence, PayloadDigest(anchor.definitionDigest),
        PayloadDigest(anchor.genesisDigest), PayloadDigest(anchor.receiptDigest))
    val target = LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE)

    val configurationOperationId = OperationId.random()
    val configurationReply = command(original, sessions, tokens,
        LocalSetupCheckpointCodecV1.configure(configurationOperationId,
            LocalSetupConfigurationRequestV1(target, Revision(0), Currency.EGP,
                AosfFixturePlan.businessTimezone, 0,
                LocalSetupFiscalIdentityV1(settled.company.companyName,
                    taxRegistrationAbsent = true))),
        "required configuration checkpoint", evidence)
    val configurationResult = LocalSetupCheckpointCodecV1.decodeResult(configurationReply.result)
    check(configurationResult.code == LocalSetupCheckpointProfileV1.CONFIGURED_CODE &&
        configurationResult.checkpointId == anchor.installation) {
        "The required configuration checkpoint did not settle"
    }

    val catalogImportOperationId = OperationId.random()
    val catalogReply = command(original, sessions, tokens,
        LocalSetupCheckpointCodecV1.completeCatalogImport(catalogImportOperationId,
            LocalSetupCatalogImportCompleteRequestV1(target, LocalSetupImportModeV1.EXPLICIT_NO_IMPORT,
                0, null)),
        "explicit no-import catalog checkpoint", evidence)
    val catalogResult = LocalSetupCheckpointCodecV1.decodeResult(catalogReply.result)
    check(catalogResult.code == LocalSetupCheckpointProfileV1.CATALOG_SETTLED_CODE &&
        catalogResult.checkpointId == anchor.installation && catalogResult.rowCount == 0) {
        "The explicit no-import catalog checkpoint did not settle"
    }

    val openingOperationId = OperationId.random()
    val openingReply = command(original, sessions, tokens,
        LocalSetupCheckpointCodecV1.completeOpening(openingOperationId,
            LocalSetupOpeningCompleteRequestV1(target, LocalSetupOpeningChoiceV1.EXPLICIT_ZERO,
                0, null, LocalSetupOpeningChoiceV1.EXPLICIT_ZERO, 0, null)),
        "explicit-zero opening checkpoint", evidence)
    val openingResult = LocalSetupCheckpointCodecV1.decodeResult(openingReply.result)
    check(openingResult.code == LocalSetupCheckpointProfileV1.OPENING_SETTLED_CODE &&
        openingResult.checkpointId == anchor.installation && openingResult.rowCount == 0) {
        "The explicit-zero opening checkpoint did not settle"
    }

    val projection = readCheckpointProjection(original, sessions, tokens, anchor)
    val configuration = checkNotNull(projection.configuration) {
        "The protected checkpoint read did not disclose the required configuration"
    }
    val catalogImport = checkNotNull(projection.catalogImport) {
        "The protected checkpoint read did not disclose the catalog-import settlement"
    }
    val opening = checkNotNull(projection.opening) {
        "The protected checkpoint read did not disclose the opening settlement"
    }
    check(configuration.binding == binding && configuration.environment == LocalSetupEnvironmentV1.LIVE &&
        configuration.activities == listOf(LocalSalesActivity.SUPERMARKET) &&
        configuration.currency == Currency.EGP &&
        configuration.timeZone == AosfFixturePlan.businessTimezone &&
        configuration.branchActivityOperationId == branchActivityOperationId &&
        configuration.operationId == configurationOperationId &&
        configuration.actorId.value == anchor.actorId &&
        configuration.policyRevision == policy.revision &&
        configuration.masterAuthorityEpoch.value == anchor.authorityEpoch) {
        "The configuration checkpoint does not carry the frozen AOSF fixed inputs/original binding"
    }
    check(catalogImport.binding == binding &&
        catalogImport.environment == LocalSetupEnvironmentV1.LIVE &&
        catalogImport.mode == LocalSetupImportModeV1.EXPLICIT_NO_IMPORT &&
        catalogImport.rowCount == 0 && catalogImport.manifestDigest == null &&
        catalogImport.inputManifestDigest == null &&
        catalogImport.operationId == catalogImportOperationId &&
        catalogImport.actorId.value == anchor.actorId &&
        catalogImport.policyRevision == policy.revision &&
        catalogImport.masterAuthorityEpoch.value == anchor.authorityEpoch) {
        "The catalog-import checkpoint does not carry the frozen original-bound no-import fact"
    }
    check(opening.binding == binding && opening.environment == LocalSetupEnvironmentV1.LIVE &&
        opening.stockChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO &&
        opening.stockRowCount == 0 && opening.stockValueTotal == Money(Currency.EGP, 0L) &&
        opening.stockManifestDigest == null && opening.stockInputManifestDigest == null &&
        opening.floatChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO &&
        opening.floatRowCount == 0 && opening.floatTotal == Money(Currency.EGP, 0L) &&
        opening.floatManifestDigest == null && opening.floatInputManifestDigest == null &&
        opening.operationId == openingOperationId && opening.actorId.value == anchor.actorId &&
        opening.policyRevision == policy.revision &&
        opening.masterAuthorityEpoch.value == anchor.authorityEpoch) {
        "The opening checkpoint does not carry the frozen original-bound explicit-zero fact"
    }

    evidence.event("AOSF_SETUP_CHECKPOINT_BASE_SETTLED", JSONObject()
        .put("branchActivityOperationId", branchActivityOperationId.value)
        .put("configurationOperationId", configurationOperationId.value)
        .put("catalogImportOperationId", catalogImportOperationId.value)
        .put("openingOperationId", openingOperationId.value)
        .put("currency", configuration.currency.name)
        .put("businessTimezone", configuration.timeZone)
        .put("catalogImportMode", catalogImport.mode.name)
        .put("openingStockChoice", opening.stockChoice.name)
        .put("openingFloatChoice", opening.floatChoice.name)
        .put("stockValueMinor", opening.stockValueTotal.minor)
        .put("floatMinor", opening.floatTotal.minor)
        .put("businessLedgerZero", true)
        .put("acknowledgement", "ABSENT")
        .put("goLive", "ABSENT"))
    return AosfCheckpointBaseline(binding, branchActivityOperationId, configurationOperationId,
        catalogImportOperationId, openingOperationId, projection)
}

private suspend fun readCheckpointProjection(
    original: com.ukkera.pos.database.AndroidOriginalLocalClient,
    sessions: AosfSessionLifetime,
    tokens: com.ukkera.pos.security.LocalSessionTokens,
    anchor: AosfAnchor,
): LocalSetupCheckpointProjectionV1 {
    val reply = original.client.read(sessions.credentials(tokens),
        LocalSetupCheckpointCodecV1.read(anchor.installation))
    val data = reply as? ReadReply.Data
        ?: error("The authorized setup-checkpoint read was not disclosed: $reply")
    return LocalSetupCheckpointCodecV1.decodeProjection(data.payload)
}

private suspend fun command(
    original: com.ukkera.pos.database.AndroidOriginalLocalClient,
    sessions: AosfSessionLifetime,
    tokens: com.ukkera.pos.security.LocalSessionTokens,
    request: MutationRequest,
    label: String,
    evidence: AosfEvidence,
): MutationReply.Recorded {
    check(request.command in original.commandKeys) {
        "The production master did not register the $label command"
    }
    val reply = original.client.execute(sessions.credentials(tokens), request)
    val recorded = reply as? MutationReply.Recorded
        ?: error("$label was not durably recorded: $reply")
    check(recorded.committed && !recorded.replayed && recorded.operationId == request.operationId) {
        "$label was not a fresh durable production commit"
    }
    evidence.event("PRODUCTION_SETUP_COMMAND", JSONObject()
        .put("label", label).put("command", request.command.name)
        .put("operationId", request.operationId.value))
    return recorded
}
