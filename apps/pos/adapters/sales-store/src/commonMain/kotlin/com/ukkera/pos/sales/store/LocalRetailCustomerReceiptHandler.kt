package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterRpcRetainedResultSourceAccessProvider
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.types.UtcInstant

/** Ordinary staff observation: no physical acquisition, payment, stock or entitlement writer. */
public class LocalRetailCustomerReceiptHandler : MasterCommandHandler, LocalMasterRpcRetainedResultSourceAccessProvider {
    override public val key: CommandKey get() = LocalRetailCustomerReceiptCodec.COMMAND
    override public val writableKinds: Set<String> = setOf(LocalRetailCustomerReceiptProfileV1.HEAD,
        LocalRetailCustomerReceiptProfileV1.EVENT, LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE)
    override public val claimNamespaces: Set<String> = setOf(LocalRetailCustomerReceiptProfileV1.NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalRetailCustomerReceiptCodec.normalize(input)

    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS) return false
        val request = LocalRetailCustomerReceiptCodec.decode(normalized)
        return request.observation == LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER &&
            LocalRetailCustomerReceiptCodec.encode(request) == normalized
    }

    /** Exact retained disclosure rejoins the original collector, never a new observation/head. */
    override public suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile,
        normalized: StoredBytes, view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        check(supportsRpcWorkflow(profile, normalized) && context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) {
            "sales.customer_receipt.context"
        }
        val request = LocalRetailCustomerReceiptCodec.decode(normalized)
        val original = try {
            readRetailCustomerReceiptOriginalSource(view, request.saleId)
        } catch (_: LocalRetailCustomerReceiptOriginalUnsupported) {
            return null
        } catch (_: LocalSalesSourceUnavailable) {
            return null
        }
        val identity = LocalRetailCustomerReceiptProductionIdentity(context.scope, context.originalActorId,
            context.operationId, context.semanticDigest)
        return listOf(retailCustomerReceiptRequirement(identity, request.saleId, request.expectedHeadRevision,
            original.coordinates.originalCollectorId))
    }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) {
            "sales.customer_receipt.context"
        }
        val request = LocalRetailCustomerReceiptCodec.decode(normalized)
        val identity = LocalRetailCustomerReceiptProductionIdentity(context.scope, context.originalActorId,
            context.operationId, context.semanticDigest)
        val original = try {
            readRetailCustomerReceiptOriginalSource(view, request.saleId)
        } catch (_: LocalRetailCustomerReceiptOriginalUnsupported) {
            return RetailCustomerReceiptUnavailablePrepared(identity, request, LocalRetailCustomerReceiptRefusalV1.ORIGINAL_UNSUPPORTED)
        } catch (_: LocalSalesSourceUnavailable) {
            return RetailCustomerReceiptUnavailablePrepared(identity, request, LocalRetailCustomerReceiptRefusalV1.ORIGINAL_UNAVAILABLE)
        }
        // Broken head/event evidence remains storage uncertainty; it is not original absence.
        val head = findRetailCustomerReceiptHead(view, original)
        requireNewRetailCustomerReceiptEvent(view, request.saleId, context.operationId)
        return RetailCustomerReceiptPrepared(identity, retailCustomerReceiptProduction(identity, request, original,
            head?.head, head?.events?.lastOrNull()?.stored?.digest))
    }
}

private class RetailCustomerReceiptPrepared(
    private val identity: LocalRetailCustomerReceiptProductionIdentity,
    private val production: LocalRetailCustomerReceiptProduction,
) : PreparedMutation() {
    override val requirement: AuthorizationRequirement get() = production.requirement
    override val affectedResources: Set<RecoveryResourceKey> get() = production.affectedResources
    override val allowsActionApproval: Boolean = false
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> =
        setOf(RecoveryResourceKind.ORDER_CUSTODY, RecoveryResourceKind.REFUND_ENTITLEMENT)

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        requireRetailCustomerReceiptAuthorization(authorization, requirement, identity)
        return production.propose(recordedAt, LocalRetailCustomerReceiptAuthorizationV1(authorization.actorId,
            authorization.authenticationId, authorization.generation, authorization.policyRevision))
    }
}

/**
 * Refusal only. With no qualified original collector, no sale data or successful proposal is
 * available. Current direct scoped permission still precedes this nondisclosing refusal;
 * the requesting actor is not installed as a collector or original source ownership anchor.
 */
private class RetailCustomerReceiptUnavailablePrepared(
    private val identity: LocalRetailCustomerReceiptProductionIdentity,
    request: LocalRetailCustomerReceiptRequestV1,
    private val reason: LocalRetailCustomerReceiptRefusalV1,
) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = retailCustomerReceiptRequirement(identity,
        request.saleId, request.expectedHeadRevision, identity.actorId)
    override val affectedResources: Set<RecoveryResourceKey> =
        retailCustomerReceiptResources(request.saleId, identity.actorId, identity.operationId)
    override val allowsActionApproval: Boolean = false

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        requireRetailCustomerReceiptAuthorization(authorization, requirement, identity)
        return retailCustomerReceiptRefused(reason)
    }
}

private fun requireRetailCustomerReceiptAuthorization(authorization: AuthorizedAction,
    requirement: AuthorizationRequirement, identity: LocalRetailCustomerReceiptProductionIdentity) {
    check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
        authorization.isBoundTo(requirement, identity.actorId)) { "sales.customer_receipt.authorization" }
}
