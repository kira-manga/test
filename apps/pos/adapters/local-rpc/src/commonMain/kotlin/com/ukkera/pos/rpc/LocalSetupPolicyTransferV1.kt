package com.ukkera.pos.rpc

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Setup-console transfer of complete policy/account DATA between the desktop UI and its own Master
 * service process. Reuses the existing RPC policy wire; decoding re-runs every constructor check.
 * DATA only: the receiving Master still requires the named head approval for every effect.
 */
public object LocalSetupPolicyTransferV1 {
    private const val MAXIMUM_CHARS = 1_048_576
    private val json = Json { encodeDefaults = true }

    public fun encodePolicy(policy: ActivatedLocalPolicy): String =
        json.encodeToString(LocalRpcPolicy.serializer(), LocalRpcPolicy.capture(policy))

    public fun decodePolicy(text: String): ActivatedLocalPolicy {
        require(text.length in 1..MAXIMUM_CHARS)
        return json.decodeFromString(LocalRpcPolicy.serializer(), text).value()
    }

    public fun encodeAccount(account: LocalAccountProvisioning): String = json.encodeToString(Account.serializer(),
        Account(account.actorId, account.login.value, LocalRpcPolicy.capture(account.policy),
            account.tokenPolicy.accessLifetimeMillis, account.tokenPolicy.workLifetimeMillis,
            account.admittedDeviceIds.sortedBy { it.value }))

    public fun decodeAccount(text: String): LocalAccountProvisioning {
        require(text.length in 1..MAXIMUM_CHARS)
        val value = json.decodeFromString(Account.serializer(), text)
        require(value.admittedDeviceIds.size in 1..256 && value.admittedDeviceIds.distinct().size == value.admittedDeviceIds.size)
        return LocalAccountProvisioning(value.actorId, LocalLoginIdentifier(value.login), value.policy.value(),
            LocalTokenPolicy(value.accessLifetimeMillis, value.workLifetimeMillis), value.admittedDeviceIds.toSet())
    }

    @Serializable
    private class Account(val actorId: DomainId, val login: String, val policy: LocalRpcPolicy,
        val accessLifetimeMillis: Long, val workLifetimeMillis: Long, val admittedDeviceIds: List<DomainId>)
}
