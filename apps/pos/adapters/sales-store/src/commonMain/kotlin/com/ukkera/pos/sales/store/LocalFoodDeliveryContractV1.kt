package com.ukkera.pos.sales.store

import kotlinx.serialization.Serializable
import kotlin.text.CharacterCodingException

/** Owned intake data, never a service label, kitchen instruction or courier assignment. */
@Serializable
public data class LocalFoodDeliveryDetailsV1(
    public val recipient: String,
    public val contact: String,
    public val address: String,
) {
    init {
        foodDeliveryText(recipient, 160)
        foodDeliveryText(contact, 160)
        foodDeliveryText(address, 600)
    }

    override fun toString(): String = "LocalFoodDeliveryDetailsV1([REDACTED])"
}

private fun foodDeliveryText(value: String, maximum: Int) {
    foodText(value, maximum, false)
    // Validate literal UTF-16 independently; replacement characters must not hide an invalid sequence.
    try {
        value.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("food.delivery.text.invalid")
    }
}
