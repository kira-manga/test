package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalAdministrationAction
import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalAdministrationReceipt
import com.ukkera.pos.security.localAdministrationRequestDigest
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable

/** Client control state, NOT a master result, session grant or stored password. */
@Serializable
internal enum class StoredAdministrationPhase { PREPARED, DISPATCHED, APPLIED, CANCELLED }

@Serializable
internal data class StoredClientAdministrationV1(
    val version: Int = 1,
    val owner: LocalClientIdentity,
    val command: LocalAdministrationCommand,
    val phase: StoredAdministrationPhase = StoredAdministrationPhase.PREPARED,
    /** Once requested, recovery can only lookup/retry cancellation, never dispatch the action. */
    val cancellationRequested: Boolean = false,
    /** Exact Applied response from this original invocation, even if a later audit read is denied. */
    val appliedEventId: DomainId? = null,
    val receipt: LocalAdministrationReceipt? = null,
) {
    val terminal: Boolean get() = phase in setOf(StoredAdministrationPhase.APPLIED, StoredAdministrationPhase.CANCELLED)
    fun validate(device: LocalClientDeviceIdentity) {
        require(version == 1 && owner.scope == device.scope && owner.deviceId == device.deviceId)
        require(!cancellationRequested || phase != StoredAdministrationPhase.PREPARED)
        require((appliedEventId != null) == (phase == StoredAdministrationPhase.APPLIED))
        require(appliedEventId == null || appliedEventId == command.requestId)
        require(receipt == null || terminal)
        if (phase == StoredAdministrationPhase.CANCELLED) require(receipt != null)
        receipt?.let {
            require(it.requestId == command.requestId && it.action == command.action && it.targetId == command.targetId &&
                it.originalOperatorId == owner.actorId && it.requestDigest == localAdministrationRequestDigest(owner.scope, owner.actorId, command))
            if (phase == StoredAdministrationPhase.CANCELLED) require(it.resultingAccountRevision == null)
            else require((command.action == LocalAdministrationAction.REVOKE_DEVICE) == (it.resultingAccountRevision == null))
        }
        require(journalJson.encodeToString(serializer(), this).encodeToByteArray().size <= MAX_CLIENT_ADMINISTRATION_BYTES)
    }
    override fun toString(): String = "StoredClientAdministrationV1([REDACTED])"
}

internal const val MAX_CLIENT_ADMINISTRATIONS: Int = 64
internal const val MAX_CLIENT_ADMINISTRATION_BYTES: Int = 8192
