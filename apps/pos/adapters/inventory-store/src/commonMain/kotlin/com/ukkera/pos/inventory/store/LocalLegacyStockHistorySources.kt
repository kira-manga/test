package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.BusinessOriginalProgram
import com.ukkera.pos.types.DomainId

/** Original receipt facts selected by the actual core native BEFORE owner. These are
 * catalogue/basis DATA, not current employee authority or renewed unit/signing state. */
public interface LocalLegacyStockHistorySources {
    public suspend fun historicalStockItem(command: RuntimeHistoricalCommand, skuId: DomainId): LocalStockItemAuthority?
    public suspend fun historicalStockWarehouse(command: RuntimeHistoricalCommand, warehouseId: DomainId): LocalStockWarehouseAuthority?
    public suspend fun historicalStockBasis(command: RuntimeHistoricalCommand, basisId: DomainId): LocalStockReceivingBasis?
    public suspend fun historicalStockItem(command: BusinessOriginalProgram, skuId: DomainId): LocalStockItemAuthority?
    public suspend fun historicalStockWarehouse(command: BusinessOriginalProgram, warehouseId: DomainId): LocalStockWarehouseAuthority?
    public suspend fun historicalStockBasis(command: BusinessOriginalProgram, basisId: DomainId): LocalStockReceivingBasis?

}
