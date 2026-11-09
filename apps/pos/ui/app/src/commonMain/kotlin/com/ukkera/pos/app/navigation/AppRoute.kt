package com.ukkera.pos.app.navigation

import androidx.navigation3.runtime.NavKey
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SecurityRoot { BOOTSTRAP, BRANCH_SIGN_IN, LOCAL_ENROLLMENT, BRANCH_WORKSPACE, COMPANY_SIGN_IN, COMPANY_WORKSPACE }

@Serializable
enum class AppArea { START, SIGN_IN, CASHIER, ORDERS, DRAWER, STOCK, REPORTS, SETUP, CATALOG, PEOPLE, PURCHASING, DEVICES, COMPANY_SETTINGS, SESSION }

/** Stable, minimal Nav3 keys. No session, token, price, cart or mutable business object is saved. */
@Serializable
sealed interface AppRoute : NavKey {
    @Serializable @SerialName("start.v1") data object Start : AppRoute
    @Serializable @SerialName("branch-sign-in.v1") data object BranchSignIn : AppRoute
    @Serializable @SerialName("local-enrollment.v1") data object LocalEnrollment : AppRoute
    @Serializable @SerialName("company-sign-in.v1") data object CompanySignIn : AppRoute
    /** Status only after current local policy leaves no operational area; never a grant. */
    @Serializable @SerialName("session-access.v1") data object SessionAccess : AppRoute
    @Serializable @SerialName("cashier.v1") data object Cashier : AppRoute
    @Serializable @SerialName("quote-preview.v1") data object QuotePreview : AppRoute
    @Serializable @SerialName("orders.v1") data object Orders : AppRoute
    @Serializable @SerialName("order.v1") data class Order(val orderId: DomainId) : AppRoute
    @Serializable @SerialName("food.v1") data object Food : AppRoute
    @Serializable @SerialName("food-order.v1") data class FoodOrder(val orderId: DomainId) : AppRoute
    @Serializable @SerialName("food-station.v1") data class FoodStation(val stationId: DomainId) : AppRoute
    @Serializable @SerialName("food-handoff.v1") data class FoodHandoff(val orderId: DomainId) : AppRoute
    @Serializable @SerialName("drawer.v1") data object Drawer : AppRoute
    @Serializable @SerialName("stock.v1") data object Stock : AppRoute
    @Serializable @SerialName("reports.v1") data object Reports : AppRoute
    @Serializable @SerialName("setup.v1") data object Setup : AppRoute
    @Serializable @SerialName("setup-step.v1") data class SetupStep(val step: Int) : AppRoute {
        init { require(step in 0..7) }
    }
    @Serializable @SerialName("catalog.v1") data object Catalog : AppRoute
    @Serializable @SerialName("people.v1") data object People : AppRoute
    @Serializable @SerialName("purchasing.v1") data object Purchasing : AppRoute
    @Serializable @SerialName("devices.v1") data object Devices : AppRoute
    @Serializable @SerialName("company-settings.v1") data object CompanySettings : AppRoute
}

/** Route registry is exhaustive at compile time and shared by navigation, restore and adapters. */
object RouteRegistry {
    fun area(route: AppRoute): AppArea = when (route) {
        AppRoute.Start -> AppArea.START
        AppRoute.BranchSignIn, AppRoute.LocalEnrollment, AppRoute.CompanySignIn -> AppArea.SIGN_IN
        AppRoute.SessionAccess -> AppArea.SESSION
        AppRoute.Cashier, AppRoute.QuotePreview -> AppArea.CASHIER
        AppRoute.Orders, is AppRoute.Order, AppRoute.Food, is AppRoute.FoodOrder, is AppRoute.FoodStation, is AppRoute.FoodHandoff -> AppArea.ORDERS
        AppRoute.Drawer -> AppArea.DRAWER
        AppRoute.Stock -> AppArea.STOCK
        AppRoute.Reports -> AppArea.REPORTS
        AppRoute.Setup, is AppRoute.SetupStep -> AppArea.SETUP
        AppRoute.Catalog -> AppArea.CATALOG
        AppRoute.People -> AppArea.PEOPLE
        AppRoute.Purchasing -> AppArea.PURCHASING
        AppRoute.Devices -> AppArea.DEVICES
        AppRoute.CompanySettings -> AppArea.COMPANY_SETTINGS
    }

    fun rootRoute(area: AppArea, root: SecurityRoot): AppRoute = when (area) {
        AppArea.START -> AppRoute.Start
        AppArea.SESSION -> AppRoute.SessionAccess
        AppArea.SIGN_IN -> when (root) {
            SecurityRoot.COMPANY_SIGN_IN -> AppRoute.CompanySignIn
            SecurityRoot.LOCAL_ENROLLMENT -> AppRoute.LocalEnrollment
            else -> AppRoute.BranchSignIn
        }
        AppArea.CASHIER -> AppRoute.Cashier
        AppArea.ORDERS -> AppRoute.Orders
        AppArea.DRAWER -> AppRoute.Drawer
        AppArea.STOCK -> AppRoute.Stock
        AppArea.REPORTS -> AppRoute.Reports
        AppArea.SETUP -> AppRoute.Setup
        AppArea.CATALOG -> AppRoute.Catalog
        AppArea.PEOPLE -> AppRoute.People
        AppArea.PURCHASING -> AppRoute.Purchasing
        AppArea.DEVICES -> AppRoute.Devices
        AppArea.COMPANY_SETTINGS -> AppRoute.CompanySettings
    }

    fun areas(root: SecurityRoot): List<AppArea> = when (root) {
        SecurityRoot.BOOTSTRAP -> listOf(AppArea.START)
        SecurityRoot.BRANCH_SIGN_IN, SecurityRoot.LOCAL_ENROLLMENT, SecurityRoot.COMPANY_SIGN_IN -> listOf(AppArea.SIGN_IN)
        // Local administration is filtered by CURRENT local grants, not a central human login.
        SecurityRoot.BRANCH_WORKSPACE -> listOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.DRAWER, AppArea.STOCK,
            AppArea.CATALOG, AppArea.PURCHASING, AppArea.REPORTS, AppArea.PEOPLE, AppArea.DEVICES, AppArea.SETUP)
        SecurityRoot.COMPANY_WORKSPACE -> listOf(AppArea.CATALOG, AppArea.PEOPLE, AppArea.PURCHASING, AppArea.REPORTS, AppArea.DEVICES, AppArea.COMPANY_SETTINGS)
    }

    fun allows(root: SecurityRoot, route: AppRoute): Boolean =
        if (route == AppRoute.SessionAccess) root == SecurityRoot.BRANCH_WORKSPACE
        else area(route) in areas(root) && when (route) {
            AppRoute.BranchSignIn -> root == SecurityRoot.BRANCH_SIGN_IN
            AppRoute.LocalEnrollment -> root == SecurityRoot.LOCAL_ENROLLMENT
            AppRoute.CompanySignIn -> root == SecurityRoot.COMPANY_SIGN_IN
            else -> true
        }

    fun restorable(route: AppRoute): Boolean = when (route) {
        // Quotes and setup edits require durable application state, not a route-only restoration.
        AppRoute.QuotePreview, is AppRoute.SetupStep, AppRoute.SessionAccess -> false
        else -> true
    }
}
