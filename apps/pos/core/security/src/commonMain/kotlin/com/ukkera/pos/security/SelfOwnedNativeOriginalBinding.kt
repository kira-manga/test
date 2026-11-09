package com.ukkera.pos.security

/**
 * Exact original binding bytes carried by an owned native history lifetime. The closed variants
 * retain their distinct Android V1 and Windows/macOS V2 formats; this interface neither decodes
 * one as the other nor grants custody, history admission or writer authority.
 */
public sealed interface SelfOwnedNativeOriginalBinding {
    public fun copyWireBytes(): ByteArray
}
