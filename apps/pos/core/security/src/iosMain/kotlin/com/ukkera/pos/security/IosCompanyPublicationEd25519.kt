package com.ukkera.pos.security

/** No native-local company publication owner or qualified Ed25519 adapter on Apple in this cut. */
internal actual fun nativeBusinessPublicationVerifyEd25519(publicKey: ByteArray, content: ByteArray, signature: ByteArray): Boolean = false
