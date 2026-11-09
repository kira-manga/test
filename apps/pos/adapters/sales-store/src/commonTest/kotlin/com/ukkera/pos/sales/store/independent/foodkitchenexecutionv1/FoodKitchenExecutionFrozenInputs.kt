package com.ukkera.pos.sales.store.independent.foodkitchenexecutionv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Exact public selections from food-kitchen-execution-v1; no native source or custody is supplied. */
internal object FoodKitchenExecutionFrozenInputs {
    val scope: BranchScope = BranchScope(
        DomainId("61000000-0000-4000-8000-000000000001"),
        DomainId("61000000-0000-4000-8000-000000000010"),
        DomainId("61000000-0000-4000-8000-000000000020"))
    val order: DomainId = DomainId("61000000-0000-4000-8000-000000000100")
    val p1: DomainId = DomainId("61000000-0000-4000-8000-000000000110")
    val p2: DomainId = DomainId("61000000-0000-4000-8000-000000000111")
    val p3: DomainId = DomainId("61000000-0000-4000-8000-000000000112")
    val collection: DomainId = DomainId("61000000-0000-4000-8000-000000000170")
    val cashSession: DomainId = DomainId("61000000-0000-4000-8000-000000000061")
    val selected: List<DomainId> = listOf(p1, p2)
    val activities: List<LocalSalesActivity> = listOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE)
    // DELIVERY's recipient/contact/address and genuine route binding remain UNBOUND.
    // Do not reinterpret that frozen variant as either of these channels.
    val representableServices: List<LocalFoodServiceV1> = listOf(
        LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "61000000-0000-4000-8000-000000000092", "Synthetic seat 1"),
        LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "SYNTHETIC-PICKUP-1"))

    // These two domain revisions are UNBOUND native coordinates, used ONLY for DTO round trips.
    // They are not the frozen order storage revisions or successful-admission expectations.
    val bindingOnlyCollectionDomainRevision: Revision = Revision(1)
    val bindingOnlyCashSessionDomainRevision: Revision = Revision(1)

    fun operation(case: String): OperationId = OperationId(when (case) {
        "FKEX-01.D" -> "62000000-0000-4000-8000-000000000001"
        "FKEX-01.A0" -> "62000000-0000-4000-8000-000000000002"
        "FKEX-01.A1" -> "62000000-0000-4000-8000-000000000003"
        "FKEX-03.S1" -> "62000000-0000-4000-8000-000000000011"
        "FKEX-03.S2" -> "62000000-0000-4000-8000-000000000012"
        "FKEX-03.R1" -> "62000000-0000-4000-8000-000000000013"
        "FKEX-03.R2" -> "62000000-0000-4000-8000-000000000014"
        "FKEX-04.B1" -> "62000000-0000-4000-8000-000000000015"
        "FKEX-04.B2" -> "62000000-0000-4000-8000-000000000016"
        else -> error("Unassigned frozen case")
    })

    fun send(): LocalFoodRequestV1.Send = LocalFoodRequestV1.Send(order, Revision(12), selected)
    fun sendReserved(): LocalFoodFinancialRequestV1.SendReserved = LocalFoodFinancialRequestV1.SendReserved(
        order, Revision(16), collection, bindingOnlyCollectionDomainRevision, selected)
    fun beginCollection(): LocalFoodFinancialRequestV1.BeginCollection = LocalFoodFinancialRequestV1.BeginCollection(
        order, Revision(12), collection, cashSession, bindingOnlyCashSessionDomainRevision)
    fun addToEmpty(): LocalFoodStockRequestV2.ActivityProtectedAdd = guardedAdd(ADD_TO_EMPTY)
    fun addToNonempty(): LocalFoodStockRequestV2.ActivityProtectedAdd = guardedAdd(ADD_TO_NONEMPTY)
    private fun guardedAdd(text: String): LocalFoodStockRequestV2.ActivityProtectedAdd =
        LocalFoodStockCodecV2.decode(StoredBytes(text.encodeToByteArray())) as LocalFoodStockRequestV2.ActivityProtectedAdd

    // Canonical public-field literals copied from the frozen published_add_inputs objects.
    // The manifest does not prescribe native transport envelopes or an original digest golden.
    const val ADD_TO_EMPTY: String = """{"type":"ACTIVITY_PROTECTED_ADD","request":{"orderId":"61000000-0000-4000-8000-000000000100","expectedOrderRevision":"12","expectedCatalogRevision":"9","portions":[{"portionId":"61000000-0000-4000-8000-000000000110","itemId":"61000000-0000-4000-8000-000000000082","recipeId":"61000000-0000-4000-8000-000000000081","expectedRecipeRevision":"7","choiceId":"61000000-0000-4000-8000-000000000086","modifierIds":[],"quantity":{"unitId":"61000000-0000-4000-8000-000000000083","scaled":"1","scale":0},"itemNote":""},{"portionId":"61000000-0000-4000-8000-000000000111","itemId":"61000000-0000-4000-8000-000000000082","recipeId":"61000000-0000-4000-8000-000000000081","expectedRecipeRevision":"7","choiceId":"61000000-0000-4000-8000-000000000086","modifierIds":[],"quantity":{"unitId":"61000000-0000-4000-8000-000000000083","scaled":"1","scale":0},"itemNote":"Synthetic preparation note"}]}}"""
    const val ADD_TO_NONEMPTY: String = """{"type":"ACTIVITY_PROTECTED_ADD","request":{"orderId":"61000000-0000-4000-8000-000000000100","expectedOrderRevision":"12","expectedCatalogRevision":"9","portions":[{"portionId":"61000000-0000-4000-8000-000000000111","itemId":"61000000-0000-4000-8000-000000000082","recipeId":"61000000-0000-4000-8000-000000000081","expectedRecipeRevision":"7","choiceId":"61000000-0000-4000-8000-000000000086","modifierIds":[],"quantity":{"unitId":"61000000-0000-4000-8000-000000000083","scaled":"1","scale":0},"itemNote":"Synthetic preparation note"},{"portionId":"61000000-0000-4000-8000-000000000112","itemId":"61000000-0000-4000-8000-000000000082","recipeId":"61000000-0000-4000-8000-000000000081","expectedRecipeRevision":"7","choiceId":"61000000-0000-4000-8000-000000000086","modifierIds":[],"quantity":{"unitId":"61000000-0000-4000-8000-000000000083","scaled":"1","scale":0},"itemNote":""}]}}"""
}
