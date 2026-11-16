package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterPhysicalRpcClientV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.DistributedPhysicalClientJournalOwner
import com.ukkera.pos.sales.store.DistributedPhysicalClientOperation
import com.ukkera.pos.sales.store.DistributedPhysicalClientResolution
import com.ukkera.pos.types.DomainId

/**
 * A separate physical companion of ONE actual remote business connection. Only the native
 * paired-credential root constructs this binding, after obtaining the real selected-original
 * SDK companion. It retains the SAME successful current actor journal. Start additionally
 * requires that actor's genuine catalogue; resolution selects the actual recorded catalogue.
 * A connection identity is only a
 * mismatch check; it is not native force proof or business permission.
 */
public class LocalRemoteRetailPhysicalBinding internal constructor(
    internal val endpoint: LocalMasterClient,
    internal val connectionId: DomainId,
    internal val identity: LocalClientDeviceIdentity,
    internal val rpc: LocalMasterPhysicalRpcClientV1,
    private val actors: LocalRemotePhysicalActors,
) {
    init { require(rpc.connectionId == connectionId && rpc.scope == identity.scope &&
        rpc.originalDeviceId == identity.deviceId) }

    /** UI vocabulary only; installed Master handlers and current authorization still decide. */
    internal val installed: LocalWorkflowInstallation = LocalWorkflowInstallation(
        com.ukkera.pos.database.LocalMasterLanBusinessRpcKeysV1.commandKeys + setOf(
            com.ukkera.pos.sales.store.LocalFinancialJournalProfileV3.COLLECTION_COMMAND,
            com.ukkera.pos.sales.store.LocalFinancialJournalProfileV3.RETURN_COMMAND,
            com.ukkera.pos.sales.store.LocalFoodPaymentProfile.COMMAND,
            com.ukkera.pos.sales.store.LocalFoodRefundProfile.COMMAND,
            com.ukkera.pos.sales.store.LocalSupplierCashProfile.COMMAND,
            com.ukkera.pos.sales.store.LocalRetailSupervisorCodecV1.COMMAND,
            com.ukkera.pos.sales.store.LocalFoodPaymentSupervisorCodec.COMMAND),
        com.ukkera.pos.database.LocalMasterLanBusinessRpcKeysV1.readKeys + setOf(
            com.ukkera.pos.sales.store.LocalFinancialReadCodecV3.QUERY,
            com.ukkera.pos.sales.store.LocalReturnReadCodec.QUERY,
            com.ukkera.pos.sales.store.LocalFoodRefundReadCodec.QUERY,
            com.ukkera.pos.sales.store.LocalSupplierCashProfile.READ,
            com.ukkera.pos.catalog.store.LocalSupplierSettlementProfile.READ_OWN_CASH))

    internal fun isCurrent(): Boolean = rpc.isCurrent() && actors.isCurrent(this)

    internal suspend fun attach(journal: LocalClientIntentJournal): LocalRemoteRetailPhysicalActor? {
        if (!isCurrent() || journal.owner.scope != identity.scope || journal.owner.deviceId != identity.deviceId) return null
        return actors.acquire(this, journal)?.takeIf { it.journal === journal && isCurrent() && it.isCurrent() }
    }

    override fun toString(): String = "LocalRemoteRetailPhysicalBinding([REDACTED])"
}

/** Internal composition ports. Implemented only by the actual paired native root; no public
 * accepting factory, platform receipt, force Boolean, original body or caller actor input. */
internal interface LocalRemotePhysicalActors {
    fun isCurrent(binding: LocalRemoteRetailPhysicalBinding): Boolean
    suspend fun acquire(binding: LocalRemoteRetailPhysicalBinding,
        journal: LocalClientIntentJournal): LocalRemoteRetailPhysicalActor?
}

internal interface LocalRemoteRetailPhysicalActor {
    val journal: LocalClientIntentJournal
    /** Null permits no Start/publication. An existing recording catalogue may still support
     * separately authorized resolution with this current actor's actual journal loan. */
    val native: DistributedPhysicalClientJournalOwner?
    fun isCurrent(): Boolean
    suspend fun begin(request: MutationRequest): DistributedPhysicalClientOperation?
    /** Separate resolver/current-intent and original-recording catalogue custody. Missing
     * native support stays denied; it never falls back to ordinary Execute or a fresh Start. */
    suspend fun beginResolution(request: MutationRequest): DistributedPhysicalClientResolution? = null
}
