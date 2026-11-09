package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Empty installation discovery request. It contains no activity, actor or permission claims. */
@Serializable
public data object LocalFoodQuotationProfileQueryV1

/** Explicit installation metadata only; no field grants execution or access to a saved check. */
@Serializable
public data class LocalFoodQuotationProfileReadResultV1(public val installed: Boolean)

public object LocalFoodQuotationProfileReadCodecV1 {
    public val QUERY: ReadKey = ReadKey("sales.food.quotation.profile", 1)
    internal const val MAX_BYTES: Int = 2_048

    public fun encode(query: LocalFoodQuotationProfileQueryV1 = LocalFoodQuotationProfileQueryV1): StoredBytes =
        localSalesEncode(query, LocalFoodQuotationProfileQueryV1.serializer(), MAX_BYTES)

    public fun read(query: LocalFoodQuotationProfileQueryV1 = LocalFoodQuotationProfileQueryV1): ReadRequest =
        ReadRequest(QUERY, encode(query))

    public fun result(bytes: StoredBytes): LocalFoodQuotationProfileReadResultV1 =
        localSalesDecode(bytes, LocalFoodQuotationProfileReadResultV1.serializer(), MAX_BYTES)

    internal fun query(bytes: StoredBytes): LocalFoodQuotationProfileQueryV1 =
        localSalesDecode(bytes, LocalFoodQuotationProfileQueryV1.serializer(), MAX_BYTES)
}

/** Install only with the explicit quotation writer, schemas and full source/native history support.
 * Construction checks immutable composition membership, never live activity or stock authority.
 * The Master's current session/device/policy read gate still controls release of this metadata. */
public class LocalFoodQuotationProfileReadHandler(
    private val quotationSource: TransactionFoodRecipeQuotationVerificationSourceV1,
    stock: LocalStockRepository,
) : MasterReadHandler {
    init { require(stock.hasRelationalProducer(StockStreamingCommandsV2.FOOD)) { "food.quotation.producer_not_installed" } }

    override val key: ReadKey = LocalFoodQuotationProfileReadCodecV1.QUERY

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalFoodQuotationProfileReadCodecV1.encode(LocalFoodQuotationProfileReadCodecV1.query(input))

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS && normalize(normalized) == normalized

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        LocalFoodQuotationProfileReadCodecV1.query(normalized)
        check(context.scope == view.scope && context.key == key &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val result = localSalesEncode(LocalFoodQuotationProfileReadResultV1(installed = true),
            LocalFoodQuotationProfileReadResultV1.serializer(), LocalFoodQuotationProfileReadCodecV1.MAX_BYTES)
        // Use the same protected catalog-read permission as the menu that discovers this
        // profile. An empty requirement list is not an authenticated-read capability.
        val requirement = AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), view.scope)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().isBoundTo(requirement, context.actorId))
                return result
            }
        }
    }
}
