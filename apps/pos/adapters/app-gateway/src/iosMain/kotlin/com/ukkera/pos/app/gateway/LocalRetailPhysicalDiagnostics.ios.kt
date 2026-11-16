package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.DeferredReason

internal actual fun reportLocalRetailPhysicalFailure(stage: LocalRetailPhysicalFailureStage,
    reason: DeferredReason?, failure: Throwable?) = Unit
