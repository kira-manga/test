package com.ukkera.pos.android.independent.aosfsetupv2

import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.database.setup.LocalSetupAcknowledgementRecordV1
import com.ukkera.pos.database.setup.LocalSetupGoLiveRecordV1
import com.ukkera.pos.database.setup.LocalSetupResultProjectionV1
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Symbolic settlement anchor bound from the production installation result and independently
 * cross-checked against the original native/Room readback. It deliberately has no session,
 * go-live actor, delivery-ACK or uncertain-logical-operation field.
 */
internal data class AosfAnchor(
    val rootId: String,
    val rootFingerprint: String,
    val organizationId: String,
    val companyId: String,
    val branchId: String,
    val installationId: String,
    val assignmentId: String,
    val deviceId: String,
    val setupRequestId: String,
    val issuedAttemptId: String,
    val attemptSequence: Long,
    val definitionDigest: String,
    val receiptDigest: String,
    val genesisDigest: String,
    val actorId: String,
    val actorLogin: String,
    val roleFingerprint: String,
    val authorityEpoch: Long,
    val storageInstanceId: String,
    val storageKeyId: String,
    val pepperKeyId: String,
    val targetSigningKeyId: String,
    val protection: String,
    val storageKeySecurity: String,
    val targetSigningFingerprint: String,
    val nativeBindingDigest: String,
) {
    fun requireSame(other: AosfAnchor) {
        check(this == other) { "The original settlement anchor changed" }
    }

    val installation: DomainId get() = DomainId(installationId)

    /**
     * The protected read must identify the exact saved original, not the reader, a retry or a
     * later request. Current authority epoch is the master's current observation; installation
     * authority epoch is the immutable original settlement fact.
     */
    fun requireProjection(projection: LocalSetupResultProjectionV1) {
        val original = projection.original
        check(original.organizationId.value == organizationId &&
            original.companyId.value == companyId && original.branchId.value == branchId &&
            original.installationId.value == installationId &&
            original.assignmentId.value == assignmentId && original.deviceId.value == deviceId &&
            original.firstActorId.value == actorId &&
            original.originalRequestId.value == setupRequestId &&
            original.issuedAttemptId.value == issuedAttemptId &&
            original.attemptSequence == attemptSequence &&
            original.definitionDigest.value == definitionDigest &&
            original.genesisDigest.value == genesisDigest &&
            original.receiptDigest.value == receiptDigest &&
            original.installationAuthorityEpoch.value == authorityEpoch &&
            original.currentAuthorityEpoch.value == authorityEpoch &&
            original.nativeSettlement) {
            "The protected saved-setup projection does not identify the actual original installation"
        }
    }

    fun requireAcknowledgement(projection: LocalSetupResultProjectionV1,
        actorId: DomainId, operationId: OperationId, policyRevision: Revision) {
        val acknowledgement = checkNotNull(projection.acknowledgement) {
            "The protected saved-setup projection did not disclose the committed acknowledgement"
        }
        requireAcknowledgementRecord(acknowledgement, actorId, operationId, policyRevision)
    }

    fun requireAcknowledgementRecord(acknowledgement: LocalSetupAcknowledgementRecordV1,
        actorId: DomainId, operationId: OperationId, policyRevision: Revision) {
        check(acknowledgement.organizationId.value == organizationId &&
            acknowledgement.companyId.value == companyId &&
            acknowledgement.branchId.value == branchId &&
            acknowledgement.installationId.value == installationId &&
            acknowledgement.originalRequestId.value == setupRequestId &&
            acknowledgement.issuedAttemptId.value == issuedAttemptId &&
            acknowledgement.attemptSequence == attemptSequence &&
            acknowledgement.definitionDigest.value == definitionDigest &&
            acknowledgement.genesisDigest.value == genesisDigest &&
            acknowledgement.actorId == actorId &&
            acknowledgement.operationId == operationId &&
            acknowledgement.policyRevision == policyRevision &&
            acknowledgement.masterAuthorityEpoch.value == authorityEpoch) {
            "The saved acknowledgement is not bound to the original result/actor/operation/epoch"
        }
    }

    fun requireGoLive(projection: LocalSetupResultProjectionV1, actorId: DomainId,
        operationId: OperationId, policyRevision: Revision) {
        requireGoLiveRecord(checkNotNull(projection.goLive) {
            "The protected saved-setup projection did not disclose the committed go-live"
        }, actorId, operationId, policyRevision)
    }

    fun requireGoLiveRecord(goLive: LocalSetupGoLiveRecordV1, actorId: DomainId,
        operationId: OperationId, policyRevision: Revision) {
        check(goLive.organizationId.value == organizationId &&
            goLive.companyId.value == companyId && goLive.branchId.value == branchId &&
            goLive.installationId.value == installationId &&
            goLive.originalRequestId.value == setupRequestId &&
            goLive.issuedAttemptId.value == issuedAttemptId &&
            goLive.attemptSequence == attemptSequence &&
            goLive.definitionDigest.value == definitionDigest &&
            goLive.genesisDigest.value == genesisDigest &&
            goLive.receiptDigest.value == receiptDigest &&
            goLive.actorId == actorId && goLive.operationId == operationId &&
            goLive.policyRevision == policyRevision &&
            goLive.masterAuthorityEpoch.value == authorityEpoch &&
            goLive.prerequisites.all { it.settled }) {
            "The saved go-live is not bound to the original result/responsible actor/operation/epoch"
        }
    }

    fun requireNoGoLive(projection: LocalSetupResultProjectionV1) {
        check(projection.goLive == null) {
            "A delivery acknowledgement/read created or disclosed an unintended go-live fact"
        }
    }

    fun retained(): org.json.JSONObject = org.json.JSONObject()
        .put("rootId", rootId).put("rootFingerprint", rootFingerprint)
        .put("organizationId", organizationId).put("companyId", companyId)
        .put("branchId", branchId).put("installationId", installationId)
        .put("assignmentId", assignmentId).put("deviceId", deviceId)
        .put("setupRequestId", setupRequestId).put("issuedAttemptId", issuedAttemptId)
        .put("attemptSequence", attemptSequence).put("definitionDigest", definitionDigest)
        .put("receiptDigest", receiptDigest).put("genesisDigest", genesisDigest)
        .put("actorId", actorId).put("actorLogin", actorLogin).put("roleFingerprint", roleFingerprint)
        .put("authorityEpoch", authorityEpoch).put("storageInstanceId", storageInstanceId)
        .put("storageKeyId", storageKeyId).put("pepperKeyId", pepperKeyId)
        .put("targetSigningKeyId", targetSigningKeyId).put("protection", protection)
        .put("storageKeySecurity", storageKeySecurity)
        .put("targetSigningFingerprint", targetSigningFingerprint)
        .put("nativeBindingDigest", nativeBindingDigest)

    companion object {
        fun bind(settled: AosfSetupOutcome.Installed, setup: LocalOriginalInstallationSetup,
            original: AndroidOriginalLocalClient): AosfAnchor {
            val receipt = setup.receipt ?: error("Saved original installation receipt is unavailable")
            val definition = setup.definition ?: error("Saved original setup definition is unavailable")
            val coordinates = receipt.core.allocation.coordinates
            val branch = settled.branch
            check(receipt.digest.value == settled.receipt.receiptDigest)
            check(definition.definitionDigest.value == settled.receipt.definitionDigest)
            check(receipt.genesis.value == settled.receipt.genesisDigest)
            check(receipt.core.originalRequestId.value == settled.receipt.originalRequestId.value &&
                receipt.core.definitionDigest.value == settled.receipt.definitionDigest)
            check(coordinates.scope.organizationId.value == branch.organizationId.value &&
                coordinates.scope.companyId.value == branch.companyId.value &&
                coordinates.scope.branchId.value == branch.branchId.value &&
                coordinates.installationId.value == branch.installationId.value &&
                coordinates.assignmentId.value == branch.allocationId.value &&
                coordinates.deviceId.value == branch.deviceId.value &&
                coordinates.firstActorId.value == branch.firstActorId.value)
            check(definition.definition.firstAccount.actorId.value == coordinates.firstActorId.value)
            check(setup.company.organizationId.value == coordinates.scope.organizationId.value &&
                setup.company.companyId.value == coordinates.scope.companyId.value)
            check(setup.company.rootFingerprint == settled.company.rootFingerprint)
            check(receipt.core.authorityEpoch.value == 1L)
            check(original.material.coordinates.storageInstanceId == coordinates.storageInstanceId &&
                original.material.coordinates.storageKeyId == coordinates.storageKeyId &&
                original.material.coordinates.pepperKeyId == coordinates.pepperKeyId &&
                original.material.coordinates.targetSigningKeyId == coordinates.targetSigningKeyId)
            check(original.material.branchName == settled.native.branchName)
            check(original.material.protection.name == settled.native.protection.name)
            return AosfAnchor(
                rootId = setup.company.rootId.value,
                rootFingerprint = setup.company.rootFingerprint,
                organizationId = coordinates.scope.organizationId.value,
                companyId = coordinates.scope.companyId.value,
                branchId = coordinates.scope.branchId.value,
                installationId = coordinates.installationId.value,
                assignmentId = coordinates.assignmentId.value,
                deviceId = coordinates.deviceId.value,
                setupRequestId = receipt.core.originalRequestId.value,
                issuedAttemptId = receipt.core.issuedAttemptId.value,
                attemptSequence = receipt.core.attemptSequence,
                definitionDigest = receipt.core.definitionDigest.value,
                receiptDigest = receipt.digest.value,
                genesisDigest = receipt.genesis.value,
                actorId = coordinates.firstActorId.value,
                actorLogin = definition.definition.firstAccount.login.value,
                roleFingerprint = policyFingerprint(definition.definition.firstAccount.policy),
                authorityEpoch = receipt.core.authorityEpoch.value,
                storageInstanceId = coordinates.storageInstanceId.value,
                storageKeyId = coordinates.storageKeyId.value,
                pepperKeyId = coordinates.pepperKeyId.value,
                targetSigningKeyId = coordinates.targetSigningKeyId.value,
                protection = original.material.protection.name,
                storageKeySecurity = original.material.storageKeySecurity.name,
                targetSigningFingerprint = original.material.targetSigningFingerprint,
                nativeBindingDigest = original.material.originalNativeBindingDigest,
            )
        }

        fun policyFingerprint(policy: ActivatedLocalPolicy): String {
            fun grant(grant: PermissionGrant): String = listOf(
                grant.permission.action.name, grant.permission.verb.name,
                grant.scope.organizationId.value, grant.scope.companyId.value,
                (grant.scope as? com.ukkera.pos.security.BranchScope)?.branchId?.value ?: "",
                grant.effect.name, grant.ownerAccess.name, grant.namedSubjectId?.value ?: "",
                grant.targetIds?.map { it.value }?.sorted()?.joinToString(",") ?: "",
                grant.limits.maximumAmount?.let { "${it.currency.name}:${it.minor}" } ?: "",
                grant.limits.maximumPercentageBasisPoints?.toString() ?: "",
                grant.limits.noMonetaryCeiling?.currency?.name ?: "",
                grant.delegationCeiling?.let {
                    "${it.maximumAmount?.let { amount -> "${amount.currency.name}:${amount.minor}" } ?: ""}:" +
                        "${it.maximumPercentageBasisPoints ?: ""}:${it.noMonetaryCeiling?.currency?.name ?: ""}"
                } ?: "",
            ).joinToString("|")
            val canonical = buildString {
                append(policy.scope.organizationId.value).append('|').append(policy.scope.companyId.value)
                append('|').append(policy.scope.branchId.value).append('|').append(policy.actorId.value)
                append('|').append(policy.revision.value).append('|')
                append(policy.sessionPolicy.maximumConcurrentSessions).append('|')
                append(policy.throttlePolicy.freeFailures).append(':').append(policy.throttlePolicy.initialDelayMillis)
                append(':').append(policy.throttlePolicy.maximumDelayMillis).append(':')
                append(policy.throttlePolicy.attemptTimeoutMillis).append('|')
                append(policy.approvalPolicy?.let {
                    "${it.validForMillis}:${it.namedSelfApprovalActions.map { action -> action.name }.sorted()}"
                } ?: "")
                policy.grants.map(::grant).sorted().forEach { append('|').append(it) }
            }
            return sha256(canonical.encodeToByteArray()).value
        }
    }
}
