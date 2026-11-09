package com.ukkera.pos.security

import java.lang.reflect.Modifier
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** JVM-only historical reflection/serialization regression; portable methods moved unchanged. */
class JvmLocalPasswordHasherTest {
    @Test
    fun principalAndPermitCannotBeDecodedFromBodyOrConstructedThroughPublicSourceConstructor() {
        for (type in listOf(
            LocalEmployeePrincipal::class.java,
            CentralHumanPrincipal::class.java,
            AdmittedDevicePrincipal::class.java,
            BranchServicePrincipal::class.java,
            AuthorizedAction::class.java,
        )) {
            assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        }
        assertFailsWith<SerializationException> { Json.decodeFromString<LocalEmployeePrincipal>("{}") }
        assertFailsWith<SerializationException> { Json.decodeFromString<AuthorizedAction>("{}") }
    }
}

/** Historical synthetic fixture glue, not a protected OS key or qualification claim. */
internal actual fun localPasswordRegressionHasher(
    pepper: DeviceProtectedPepper,
    deterministicSalt: Boolean,
): LocalPasswordHasher = if (deterministicSalt) {
    JvmLocalPasswordHasher(pepper, random = SyntheticSaltRandom())
} else {
    JvmLocalPasswordHasher(pepper)
}

internal actual fun localPasswordRegressionHmacSha256(message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256"))
    return mac.doFinal(message)
}

private class SyntheticSaltRandom : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) {
        bytes.indices.forEach { bytes[it] = (it + 1).toByte() }
    }
}
