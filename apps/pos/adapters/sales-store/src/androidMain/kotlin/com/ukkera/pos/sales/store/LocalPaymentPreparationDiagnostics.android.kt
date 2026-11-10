package com.ukkera.pos.sales.store

internal actual fun reportLocalPaymentPreparation(stage: LocalPaymentPreparationStage) {
    try { android.util.Log.i("UkkeraNativeOwner", "Normalized payment preparation: ${stage.name}") }
    catch (_: Throwable) { /* Observation cannot change protected decisions or cleanup. */ }
}
