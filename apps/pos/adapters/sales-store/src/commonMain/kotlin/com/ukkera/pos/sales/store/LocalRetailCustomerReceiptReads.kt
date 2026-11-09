package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Both explicit read routes authorize independently against current protected Master state. */
public class LocalRetailCustomerReceiptReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalRetailCustomerReceiptReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalRetailCustomerReceiptReadCodec.encode(LocalRetailCustomerReceiptReadCodec.decodeQuery(input))

    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS &&
            when (val query = LocalRetailCustomerReceiptReadCodec.decodeQuery(normalized)) {
                is LocalRetailCustomerReceiptQueryV1.Head, is LocalRetailCustomerReceiptQueryV1.Event ->
                    LocalRetailCustomerReceiptReadCodec.encode(query) == normalized
            }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.customer_receipt.context" }
        val query = LocalRetailCustomerReceiptReadCodec.decodeQuery(normalized)
        val saleId: DomainId
        val access: LocalRetailCustomerReceiptReadAccessV1
        when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> { saleId = query.saleId; access = query.access }
            is LocalRetailCustomerReceiptQueryV1.Event -> { saleId = query.saleId; access = query.access }
        }
        val original = try {
            readRetailCustomerReceiptOriginalSource(view, saleId)
        } catch (_: LocalRetailCustomerReceiptOriginalUnsupported) {
            return unavailableReceiptRead(context, saleId, access)
        } catch (_: LocalSalesSourceUnavailable) {
            return unavailableReceiptRead(context, saleId, access)
        }
        val head = try {
            findRetailCustomerReceiptHead(view, original)
        } catch (_: LocalSalesSourceUnavailable) {
            return unavailableReceiptRead(context, saleId, access, original.coordinates.originalCollectorId)
        }
        val value = when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> if (head == null) {
                LocalRetailCustomerReceiptReadResultV1.NotRecorded(original.publicOriginal())
            } else LocalRetailCustomerReceiptReadResultV1.Head(retailCustomerReceiptHeadView(original, head.head.value))
            is LocalRetailCustomerReceiptQueryV1.Event -> {
                val event = head?.events?.singleOrNull { it.value.operationId == query.operationId }
                if (event != null) LocalRetailCustomerReceiptReadResultV1.Event(retailCustomerReceiptEventView(event.value))
                else {
                    // A requested orphan row/source cannot be presented as a clean missing event.
                    val absent = try {
                        retailCustomerReceiptEventAbsent(view, saleId, query.operationId)
                    } catch (_: LocalSalesSourceUnavailable) {
                        return unavailableReceiptRead(context, saleId, access, original.coordinates.originalCollectorId)
                    }
                    if (!absent) return unavailableReceiptRead(context, saleId, access, original.coordinates.originalCollectorId)
                    LocalRetailCustomerReceiptReadResultV1.EventNotRecorded(saleId, query.operationId)
                }
            }
        }
        return protectedReceiptRead(context, saleId, access, original.coordinates.originalCollectorId,
            head?.head?.value?.headRevision ?: Revision(0), value)
    }
}

private fun unavailableReceiptRead(context: ReadContext, saleId: DomainId,
    access: LocalRetailCustomerReceiptReadAccessV1, collectorId: DomainId = context.actorId): PreparedRead =
    protectedReceiptRead(context, saleId, access, collectorId, Revision(0), LocalRetailCustomerReceiptReadResultV1.NotAvailable)

private fun protectedReceiptRead(context: ReadContext, saleId: DomainId,
    access: LocalRetailCustomerReceiptReadAccessV1, collectorId: DomainId,
    revision: Revision, result: LocalRetailCustomerReceiptReadResultV1): PreparedRead {
    val action = when (access) {
        LocalRetailCustomerReceiptReadAccessV1.RECORD -> SecurityAction.CUSTOMER_RECEIPT_RECORD
        LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP -> SecurityAction.RETURN_RECEIPT_LOOKUP
    }
    val requirement = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
        ActionBinding(saleId, revision, context.semanticDigest, null, "customer.receipt.read"),
        owner = if (access == LocalRetailCustomerReceiptReadAccessV1.RECORD) OwnerConstraint.OwnerPrivate(collectorId)
            else OwnerConstraint.Unrestricted)
    return object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = listOf(requirement)

        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                authorizations.single().approvalId == null && authorizations.single().isBoundTo(requirement, context.actorId)) {
                "sales.customer_receipt.authorization"
            }
            return try {
                LocalRetailCustomerReceiptReadCodec.result(result)
            } catch (_: LocalSalesWireCapacityExceeded) {
                LocalRetailCustomerReceiptReadCodec.result(LocalRetailCustomerReceiptReadResultV1.CapacityRequired)
            }
        }
    }
}
