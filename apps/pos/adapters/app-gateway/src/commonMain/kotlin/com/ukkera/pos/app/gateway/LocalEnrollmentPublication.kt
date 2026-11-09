package com.ukkera.pos.app.gateway

/** One immutable root publication of the reviewed requested data and its exact original
 * enrollment port. This pair conveys no ceremony authority: the real native/Room endpoint
 * still validates the complete original definition and its single-use dispatch. */
internal class LocalEnrollmentPublication(
    val offering: LocalEnrollmentOffering,
    val enrollment: LocalMasterEnrollmentConnection,
) {
    init {
        check(offering.owner.scope == enrollment.connection.peer.scope &&
            offering.owner.deviceId == enrollment.connection.peer.deviceId)
    }
    override fun toString(): String = "LocalEnrollmentPublication([REDACTED])"
}
