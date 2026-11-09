package com.ukkera.pos.sales.store

import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal fun foodRule(condition: Boolean, rule: SalesRule) { if (!condition) throw SalesRuleViolation(rule) }
internal fun LocalFoodRequestV1.foodOrderId(): DomainId = when (this) {
    is LocalFoodRequestV1.Create -> orderId
    is LocalFoodRequestV1.Add -> orderId
    is LocalFoodRequestV1.EditServiceDetails -> orderId
    is LocalFoodRequestV1.Send -> orderId
    is LocalFoodRequestV1.Station -> orderId
    is LocalFoodRequestV1.Serve -> orderId
    is LocalFoodRequestV1.Cancel -> orderId
    is LocalFoodRequestV1.DiscardStarted -> orderId
    is LocalFoodRequestV1.OfferHandoff -> orderId
    is LocalFoodRequestV1.AcceptHandoff -> orderId
    is LocalFoodRequestV1.CancelHandoff -> orderId
}
internal fun LocalFoodRequestV1.foodOrderRevision(): Revision = when (this) {
    is LocalFoodRequestV1.Create -> Revision(0)
    is LocalFoodRequestV1.Add -> expectedOrderRevision
    is LocalFoodRequestV1.EditServiceDetails -> expectedOrderRevision
    is LocalFoodRequestV1.Send -> expectedOrderRevision
    is LocalFoodRequestV1.Station -> expectedOrderRevision
    is LocalFoodRequestV1.Serve -> expectedOrderRevision
    is LocalFoodRequestV1.Cancel -> expectedOrderRevision
    is LocalFoodRequestV1.DiscardStarted -> expectedOrderRevision
    is LocalFoodRequestV1.OfferHandoff -> expectedOrderRevision
    is LocalFoodRequestV1.AcceptHandoff -> expectedOrderRevision
    is LocalFoodRequestV1.CancelHandoff -> expectedOrderRevision
}
internal fun LocalFoodRequestV1.foodPortionId(): DomainId = when (this) {
    is LocalFoodRequestV1.Station -> portionId
    is LocalFoodRequestV1.Serve -> portionId
    is LocalFoodRequestV1.Cancel -> portionId
    is LocalFoodRequestV1.DiscardStarted -> portionId
    else -> error("food.request.no_portion")
}
internal fun LocalFoodRequestV1.foodPortionRevision(): Revision = when (this) {
    is LocalFoodRequestV1.Station -> expectedPortionRevision
    is LocalFoodRequestV1.Serve -> expectedPortionRevision
    is LocalFoodRequestV1.Cancel -> expectedPortionRevision
    is LocalFoodRequestV1.DiscardStarted -> expectedPortionRevision
    else -> error("food.request.no_portion")
}
internal fun foodAction(request: LocalFoodRequestV1): SecurityAction = when (request) {
    is LocalFoodRequestV1.Create -> SecurityAction.ORDER_CREATE
    is LocalFoodRequestV1.Station, is LocalFoodRequestV1.DiscardStarted -> SecurityAction.KITCHEN_TASK
    is LocalFoodRequestV1.OfferHandoff, is LocalFoodRequestV1.AcceptHandoff, is LocalFoodRequestV1.CancelHandoff -> SecurityAction.ORDER_HANDOFF
    else -> SecurityAction.ORDER_EDIT
}
internal fun foodTarget(request: LocalFoodRequestV1): DomainId = when (request) {
    is LocalFoodRequestV1.Station -> request.stationId
    is LocalFoodRequestV1.DiscardStarted -> request.stationId
    else -> request.foodOrderId()
}
internal fun foodResultCode(request: LocalFoodRequestV1): String = when (request) {
    is LocalFoodRequestV1.Create -> "food.order.created"
    is LocalFoodRequestV1.Add -> "food.order.added"
    is LocalFoodRequestV1.EditServiceDetails -> "food.order.service_details.updated"
    is LocalFoodRequestV1.Send -> "food.sent"
    is LocalFoodRequestV1.Station -> when (request.action) {
        LocalFoodStationActionV1.START -> "food.started"
        LocalFoodStationActionV1.READY -> "food.station.ready"
        LocalFoodStationActionV1.CONFIRM_UNSTARTED -> "food.cancellation.confirmed"
    }
    is LocalFoodRequestV1.Serve -> "food.served.uncollected"
    is LocalFoodRequestV1.Cancel -> "food.cancellation.requested"
    is LocalFoodRequestV1.DiscardStarted -> "food.waste.classified"
    is LocalFoodRequestV1.OfferHandoff -> "food.handoff.offered"
    is LocalFoodRequestV1.AcceptHandoff -> "food.handoff.accepted"
    is LocalFoodRequestV1.CancelHandoff -> "food.handoff.cancelled"
}
