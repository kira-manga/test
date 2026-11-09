package com.ukkera.pos.database

/**
 * Actual retained Host close operation, not Master/device/employee authority or a healthy flag.
 * The native service binds the concrete adapter before any owner construction or SDK work.
 * The adapter must await its exact common owner, acquisition, official role close and Job drain;
 * missing construction outcomes and failed/unknown close throw and retain native ownership.
 * This operation must not close Master custody or re-enter the service's native/Room work slot.
 */
public interface LocalMasterRpcHostResource {
    /** Service push after actual original yield settlement. Only the privately retained
     * reconstructed Host callback may retain/use this token; retention alone is not origin
     * proof. Identity-only resources refuse registration rather than fabricate admission. */
    public fun bindPeerRegistration(registration: LocalMasterRpcPeerRegistration): Unit {
        error("Original RPC peer registration is unavailable")
    }

    public suspend fun closeOwned()
}
