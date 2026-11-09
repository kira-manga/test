package com.ukkera.pos.database

/** Finite native-history admission, not business authorization or a refusal producer. */
internal fun nativeStreamingRejectionAdmission(
    command: CommandKey,
    prepared: PreparedStreamingMutation,
    rejection: StreamedMutationDecision.Reject,
): DeferredReason? {
    if (command != StockStreamingCommandsV2.RETURN && command != StockStreamingCommandsV2.FOOD) return null
    if (prepared.supportsRecordedRejection(rejection)) return null
    return if (rejection.code == "precondition.expired") DeferredReason.PRECONDITION_EXPIRED
    else DeferredReason.CURRENT_SOURCE_UNAVAILABLE
}
