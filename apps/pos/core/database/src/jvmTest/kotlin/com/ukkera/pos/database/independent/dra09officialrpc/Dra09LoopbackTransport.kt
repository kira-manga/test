package com.ukkera.pos.database.independent.dra09officialrpc

import dev.p2pkit.core.AppId
import dev.p2pkit.core.ConnectionState
import dev.p2pkit.core.P2pKit
import dev.p2pkit.core.TransportKind
import dev.p2pkit.core.dsl.P2pKitBuilder
import dev.p2pkit.core.dsl.TransportsBuilder
import dev.p2pkit.core.dsl.jvmSecureIdentityStore
import dev.p2pkit.core.security.JvmSecureIdentityStore
import dev.p2pkit.core.transport.DataTransport
import dev.p2pkit.core.transport.HasLocalTcpEndpoint
import dev.p2pkit.core.transport.InternalPeer
import dev.p2pkit.core.transport.RawConnection
import dev.p2pkit.core.transport.TransportCapability
import dev.p2pkit.core.transport.TransportContext
import dev.p2pkit.core.transport.TransportDescriptor
import dev.p2pkit.core.transport.TransportFactory
import dev.p2pkit.core.transport.TransportPair
import dev.p2pkit.provisioning.desktop.jvm
import dev.p2pkit.rpc.RpcPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile

/**
 * Test-only in-memory byte transport registered through the official public
 * [TransportFactory]/[DataTransport] SPI. It is not a production transport, does not
 * touch a socket, and does not weaken OrganizationLan policy. It exists because the
 * official strict RPC LAN profile deliberately rejects same-host hairpin connections,
 * so a single-process same-host fixture cannot use its real TCP path.
 *
 * The official `RpcClient`, `RpcHost`, authenticated-v2 identity, Noise handshake,
 * framing and RPC engines still run over these bytes; the POS bridge is bound to the
 * resulting genuine `RpcClient`.
 */
internal object Dra09LoopbackTransportFactory : TransportFactory {
    private val nextPort = AtomicInteger(45_000)
    private val ports = ConcurrentHashMap<String, Int>()

    override val descriptor: TransportDescriptor =
        TransportDescriptor(TransportKind.LAN, setOf(TransportCapability.DATA))

    override fun build(context: TransportContext): TransportPair {
        val port = nextPort.getAndIncrement()
        ports[portKey(context.appId, context.deviceName)] = port
        return TransportPair(data = Dra09LoopbackTransport(port), discovery = null)
    }

    /** Test-only loopback port; exported so the fixture need not consult an OS LAN address. */
    fun portFor(appId: AppId, deviceName: String): Int =
        checkNotNull(ports[portKey(appId, deviceName)]) { "DR-A09 loopback transport is not built" }

    private fun portKey(appId: AppId, deviceName: String): String = "${appId.value}|$deviceName"
}

private class Dra09LoopbackTransport(private val port: Int) : DataTransport, HasLocalTcpEndpoint {
    private val registry = Registry
    private val _tcpPort = MutableStateFlow<Int?>(port)
    private val incoming = Channel<RawConnection>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val type: TransportKind = TransportKind.LAN
    override val priority: Int = 100
    override val tcpPort: StateFlow<Int?> = _tcpPort.asStateFlow()

    override suspend fun start(): Result<Unit> {
        if (closed.get()) return Result.failure(IllegalStateException("DR-A09 loopback transport is closed"))
        if (started.compareAndSet(false, true)) registry.put(port, this)
        return Result.success(Unit)
    }

    override suspend fun stop() {
        registry.remove(port, this)
        started.set(false)
    }

    override fun canConnect(peer: InternalPeer): Boolean =
        peer.transportHints.any { it.type == TransportKind.LAN && it.port?.let(registry::containsKey) == true }

    override suspend fun connect(peer: InternalPeer): RawConnection {
        val hint = peer.transportHints.firstOrNull { it.type == TransportKind.LAN && it.port != null }
            ?: throw IllegalStateException("DR-A09 loopback peer has no LAN transport hint")
        val target = hint.port?.let(registry::get)
            ?: throw IllegalStateException("DR-A09 loopback target is not started")
        val client = Pipe()
        val server = Pipe(peer = client)
        client.peer = server
        if (!target.accept(server)) {
            client.close()
            throw IllegalStateException("DR-A09 loopback target refused the connection")
        }
        return client
    }

    override fun incomingConnections(): Flow<RawConnection> = incoming.receiveAsFlow()

    private fun accept(connection: RawConnection): Boolean {
        if (closed.get()) return false
        return incoming.trySend(connection).isSuccess
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            registry.remove(port, this)
            started.set(false)
            incoming.close()
        }
    }

    private class Pipe(peer: Pipe? = null) : RawConnection {
        @Volatile var peer: Pipe? = peer
        private val _state = MutableStateFlow(ConnectionState.Connected)
        // A null element is an in-band EOF. Closing the channel directly could let the
        // peer observe Closed before draining the final PairApproved bytes.
        private val bytes = Channel<ByteArray?>(Channel.UNLIMITED)
        private val finished = AtomicBoolean(false)

        override val state: StateFlow<ConnectionState> = _state.asStateFlow()

        override suspend fun write(bytes: ByteArray) {
            if (finished.get()) throw IllegalStateException("DR-A09 loopback pipe is closed")
            val target = peer ?: throw IllegalStateException("DR-A09 loopback pipe has no peer")
            target.bytes.send(bytes.copyOf())
        }

        override fun read(): Flow<ByteArray> =
            bytes.receiveAsFlow().takeWhile { it != null }.map { checkNotNull(it) }

        override suspend fun close() {
            if (!finished.compareAndSet(false, true)) return
            _state.value = ConnectionState.Closed
            bytes.close()
            // Deliver EOF only after all previously written bytes, without forcing the
            // peer's state Closed before it has a chance to read them.
            peer?.bytes?.trySend(null)
        }
    }

    private companion object {
        object Registry {
            private val transports = ConcurrentHashMap<Int, Dra09LoopbackTransport>()
            fun put(port: Int, transport: Dra09LoopbackTransport) { transports[port] = transport }
            fun remove(port: Int, transport: Dra09LoopbackTransport) { transports.remove(port, transport) }
            fun get(port: Int): Dra09LoopbackTransport? = transports[port]
            fun containsKey(port: Int): Boolean = transports.containsKey(port)
        }
    }
}

/**
 * Constructs the official RPC platform over the test-only loopback transport.
 *
 * `RpcPlatform`'s Kotlin constructor is internal (JVM-public in the official artifact).
 * Normal application code must use its platform factories; this reflection is confined
 * to the independent DR-A09 harness. It creates no accepting production provider and
 * changes no application source.
 */
internal object Dra09TestRpcPlatform {
    @Suppress("UNCHECKED_CAST")
    fun create(store: JvmSecureIdentityStore): RpcPlatform {
        val constructor = Class.forName("dev.p2pkit.rpc.RpcPlatform")
            .getConstructor(kotlin.jvm.functions.Function1::class.java)
        val factory = object : kotlin.jvm.functions.Function1<Any, P2pKit> {
            override fun invoke(settings: Any): P2pKit = P2pKit.create {
                val configure = settings.javaClass.getMethod("configure", P2pKitBuilder::class.java)
                configure.invoke(settings, this)
                val transportsBuilder = P2pKitBuilder::class.java
                    .getMethod("getTransportsBuilder\$p2p_core").invoke(this) as TransportsBuilder
                val registrations = TransportsBuilder::class.java
                    .getMethod("getRegistrations\$p2p_core").invoke(transportsBuilder) as MutableList<Any>
                registrations.clear()
                jvmSecureIdentityStore(store)
                transports { register(Dra09LoopbackTransportFactory) }
                networkProvisioning { jvm() }
            }
        }
        return constructor.newInstance(factory) as RpcPlatform
    }
}
