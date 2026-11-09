package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*

/** Handover uses the existing protected FOOD source, not a second delivery or settlement ledger. */
internal suspend fun fulfillmentWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.FULFILLMENT) invalid()
    val selected = query.food ?: invalid()
    if (!selected.canAppearIn(LocalWorkflow.FULFILLMENT)) invalid()
    val original = if (selected is LocalFoodUiQuery.Collection)
        foodFinancialWorkflow(access, selected, fulfillmentHandover = true)
    else foodWorkflow(access, LocalWorkflowQuery(LocalWorkflow.FOOD, food = selected))
    val source = original.food ?: unavailable()
    // Preserve this one actual response and its actor/revision coordinates. Only navigation
    // links and forms are narrowed; no prepared, paid or physical state is inferred here.
    val body = when (val value = source.body) {
        is LocalFoodUiBody.Financial -> value.copy(links = value.links.filter {
            it.query.canAppearIn(LocalWorkflow.FULFILLMENT)
        })
        else -> value
    }
    return original.copy(workflow = LocalWorkflow.FULFILLMENT,
        forms = original.forms.filter { it.action.canAppearIn(LocalWorkflow.FULFILLMENT) },
        food = source.copy(body = body))
}
