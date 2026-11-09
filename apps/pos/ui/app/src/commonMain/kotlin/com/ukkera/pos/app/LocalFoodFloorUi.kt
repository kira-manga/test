package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** One protected configuration observation, not a table occupancy or order grant. */
data class LocalFoodUiFloor(val id: DomainId, val storageRevision: Revision,
    val names: LocalText, val layoutOrder: Int, val active: Boolean) {
    init { require(storageRevision.value > 0 && layoutOrder in 0..9999); requireFloorDisplayNames(names) }
}

data class LocalFoodUiTable(val id: DomainId, val floorId: DomainId, val storageRevision: Revision,
    val names: LocalText, val layoutOrder: Int, val active: Boolean) {
    init { require(storageRevision.value > 0 && layoutOrder in 0..9999 && id != floorId); requireFloorDisplayNames(names) }
}

private fun requireFloorDisplayNames(names: LocalText) {
    require((names.english.isNotBlank() || names.arabic.isNotBlank()) &&
        listOf(names.english, names.arabic).all { it.length <= 160 && it.none { ch -> ch.code < 0x20 || ch.code == 0x7f } })
}
