package com.ukkera.pos.sales.store

/** Fixed source phases only. Never accepts or emits operation, stored or credential data. */
internal enum class LocalPaymentPreparationStage {
    COLLECTION_ENTERED, COLLECTION_RETURNED,
    STOCK_ENTERED, STOCK_RETURNED,
    REQUIREMENT_ENTERED, REQUIREMENT_RETURNED,
    VACANCY_RETURNED, OBSERVATIONS_RETURNED, LITERAL_STOCK_RETURNED, EVIDENCE_SEALED,
}
internal expect fun reportLocalPaymentPreparation(stage: LocalPaymentPreparationStage)
