package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.setup.LocalSetupLanNetwork
import dev.p2pkit.transport.lan.OrganizationLan
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native NIC enumeration shared by Android and desktop. Every result must pass SDK LAN policy. */
public object LocalSetupNativeLanNetworks {
    public suspend fun observe(): List<LocalSetupLanNetwork> = withContext(Dispatchers.IO) {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.asSequence().filter { it.isUp && !it.isLoopback }.flatMap { nic ->
            nic.interfaceAddresses.asSequence().mapNotNull { binding ->
                val address = binding.address as? Inet4Address ?: return@mapNotNull null
                val prefix = binding.networkPrefixLength.toInt()
                if (address.isLoopbackAddress || address.isLinkLocalAddress || prefix !in 1..32) return@mapNotNull null
                val bytes = address.address
                val subnet = bytes.mapIndexed { index, byte ->
                    val bits = (prefix - index * 8).coerceIn(0, 8)
                    (byte.toInt() and 255) and (255 shl (8 - bits) and 255)
                }.joinToString(".") + "/" + prefix
                val numeric = address.hostAddress ?: return@mapNotNull null
                // SDK policy rejects public/mixed subnets; observations never expand that policy.
                if (runCatching { OrganizationLan(listOf(subnet), nic.name, numeric, 0) }.isFailure) return@mapNotNull null
                LocalSetupLanNetwork(nic.name, numeric, subnet)
            }
        }.distinct().sortedWith(compareBy({ it.interfaceName }, { it.localAddress })).take(64).toList()
    }
}
