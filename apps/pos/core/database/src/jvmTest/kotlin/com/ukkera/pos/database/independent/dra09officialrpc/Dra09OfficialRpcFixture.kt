package com.ukkera.pos.database.independent.dra09officialrpc

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClientRpcBridgeV1
import com.ukkera.pos.database.LocalMasterClientRpcErrorV1
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcExecuteResponseV1
import com.ukkera.pos.database.LocalMasterRpcExecuteValueV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupResponseV1
import com.ukkera.pos.database.LocalMasterRpcLookupValueV1
import com.ukkera.pos.database.LocalMasterRpcRecordedV1
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalDevicePairingTargetDescriptor
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.NativeDevicePairingApprovedMasterIdentityV1
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OriginalAllocationCommitmentV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedTargetDescriptorV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import dev.p2pkit.core.AppId
import dev.p2pkit.core.security.JvmSecureIdentityStore
import dev.p2pkit.rpc.RpcClient
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcConnectionState
import dev.p2pkit.rpc.RpcHost
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcHostState
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcReply
import dev.p2pkit.rpc.RpcRetrySafety
import dev.p2pkit.rpc.RpcSelectedHost
import dev.p2pkit.rpc.RpcTrustPurpose
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer

/**
 * DR-A09 harness. The official rc12 Host, Client, authenticated-v2 identity, Noise
 * handshake, framing and `RpcClient.callWithAcceptance` run over test-only in-memory
 * loopback bytes registered through the official transport SPI. The POS
 * association/approval objects are synthetic DATA built from the running SDK
 * identities; they are not native custody, device admission or a production provider.
 * The identity/trust stores are test-only in-memory implementations of the public SDK
 * interfaces. Trust pins are controlled pre-established DATA, not a pairing ceremony.
 * No production source is changed.
 */
internal class Dra09OfficialRpcFixture private constructor(
    val host: RpcHost,
    val client: RpcClient,
    val scope: BranchScope,
    val appId: AppId,
    val actor: DomainId,
    val connectionId: DomainId,
    val lifetime: Job,
    private val applicationScope: CoroutineScope,
    val marker: Path,
    val probe: RpcProcedure<String, String, String>,
    val executeProcedure: RpcProcedure<LocalMasterRpcExecuteRequestV1,
        LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1>,
    val credentials: LocalGatewayCredentials,
    val bridge: LocalMasterClientRpcBridgeV1,
) {
    val executeCount = AtomicInteger()
    val lookupCount = AtomicInteger()
    val executeResponse = AtomicReference<(LocalMasterRpcExecuteRequestV1) -> LocalMasterRpcExecuteResponseV1>()
    val lookupResponse = AtomicReference<(LocalMasterRpcLookupRequestV1) -> LocalMasterRpcLookupResponseV1>()

    private val recordedAt = UtcInstant.parseCanonical("2026-11-09T00:00:00Z")

    fun recorded(operationId: OperationId): LocalMasterRpcRecordedV1 = LocalMasterRpcRecordedV1(
        operationId = operationId,
        originalActorId = actor,
        committed = true,
        code = "committed",
        result = StoredBytes(ByteArray(4096) { (it % 251).toByte() }),
        authorityEpoch = Revision(1),
        sequence = JournalSequence(1),
        recordedAt = recordedAt,
        replayed = false,
    )

    fun defaultExecuteResponse(request: LocalMasterRpcExecuteRequestV1): LocalMasterRpcExecuteResponseV1 =
        LocalMasterRpcExecuteResponseV1(
            requestingActorId = actor,
            value = LocalMasterRpcExecuteValueV1.Recorded(recorded(request.request.operationId)),
        )

    fun defaultLookupResponse(request: LocalMasterRpcLookupRequestV1): LocalMasterRpcLookupResponseV1 =
        LocalMasterRpcLookupResponseV1(
            requestingActorId = actor,
            value = LocalMasterRpcLookupValueV1.Found(recorded(request.identity.operationId).copy(replayed = true)),
        )

    fun identity(operationId: OperationId): OperationIdentity = OperationIdentity(
        branchId = scope.branchId,
        operationId = operationId,
        originalActorId = actor,
        semanticDigestVersion = 1,
        semanticSha256 = "a".repeat(64),
    )

    fun request(operationId: OperationId): MutationRequest =
        MutationRequest(
            operationId = operationId,
            command = CommandKey("dr.a09.command", 1),
            payload = StoredBytes(ByteArray(64) { (it + 1).toByte() }),
        )

    suspend fun awaitRetainedPayload(): Long = withTimeout(5_000) {
        host.diagnostics.first { it.retainedPayloadBytes > 0 }.retainedPayloadBytes
    }

    suspend fun awaitCompleted(count: Long) = withTimeout(10_000) {
        host.diagnostics.first { it.completedCalls >= count }
    }

    suspend fun close() {
        var failure: Throwable? = null
        try {
            runCatching { withTimeout(30_000) { client.close() } }.exceptionOrNull()?.let { failure = it }
        } finally {
            runCatching { withTimeout(30_000) { host.close() } }.exceptionOrNull()?.let {
                if (failure == null) failure = it
            }
            lifetime.cancel()
            applicationScope.cancel()
            runCatching { Files.deleteIfExists(marker) }
            runCatching { Files.deleteIfExists(marker.parent) }
        }
        failure?.let { throw it }
    }

    companion object {
        private val nextNamespace = AtomicInteger(1)

        suspend fun start(executeVersion: Int = 1, registerLookup: Boolean = true): Dra09OfficialRpcFixture {
            val namespace = nextNamespace.getAndIncrement()
            fun id(suffix: Int): DomainId = DomainId(
                namespace.toString(16).padStart(8, '0') + "-0000-4000-8000-" +
                    suffix.toString(16).padStart(12, '0'),
            )

            val scope = BranchScope(id(1), id(2), id(3))
            val appId = LocalMasterSdkProfileV1.appId(scope)
            val actor = id(4)
            val connectionId = id(5)
                        val hostTrust = Dra09MemoryTrustStore()
            val clientTrust = Dra09MemoryTrustStore()
            val clientStore = Dra09MemoryIdentityStore()
            val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val lifetime = SupervisorJob()
            val markerDir = Files.createTempDirectory("dra09-official-rpc-")
            val marker = markerDir.resolve("accepted.marker")
            val probe = RpcProcedure("dr.a09.probe", 1,
                String.serializer(), String.serializer(), String.serializer())
            val executeProcedure = if (executeVersion == 1) LocalMasterClientRpcProtocolV1.EXECUTE
            else RpcProcedure(
                name = "pos.business.execute", version = 2,
                request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
                response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
                applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
                retrySafety = RpcRetrySafety.NeverReinvoke,
                requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
                responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
                errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
            )
            // Controlled policy DATA only. The test transport is registered through the
            // official SPI; this address is never dialed and the official strict LAN
            // factory's same-host hairpin rejection is not weakened.
            val networkPolicy = OrganizationLan(
                allowedSubnets = listOf("10.0.0.0/8"),
                interfaceName = "dr-a09",
                localAddress = "10.103.0.29",
            )
            val hostPlatform = Dra09TestRpcPlatform.create(Dra09MemoryIdentityStore())
            val clientPlatform = Dra09TestRpcPlatform.create(Dra09MemoryIdentityStore())
            var host: RpcHost? = null
            var client: RpcClient? = null
            var temporaryClient: RpcClient? = null
            val fixtureRef = AtomicReference<Dra09OfficialRpcFixture?>()
            try {
                // Establish the official trust pins without using the enrollment/approval
                // ceremony. A controlled probe client reads the actual identity that the
                // persistent in-memory store will retain for the real client. Both trust
                // stores are then seeded before the real host/client runtimes load them.
                temporaryClient = RpcClient.create(clientPlatform, applicationScope) {
                    this.appId = appId
                    deviceName = "DR-A09 Client"
                    this.lan = networkPolicy
                    trustStore = Dra09MemoryTrustStore()
                    transportReconnect = false
                }
                val clientIdentity = temporaryClient.localIdentity
                withTimeout(15_000) { temporaryClient.close() }
                temporaryClient = null
                hostTrust.seed(appId, RpcTrustPurpose.HostClients, setOf(clientIdentity.fingerprint))

                val createdHost = RpcHost.create(hostPlatform, applicationScope) {
                    this.appId = appId
                    deviceName = "DR-A09 Master"
                    this.lan = networkPolicy
                    trustStore = hostTrust
                    advertise = false
                    requestHistoryCapacity = 16
                    register(probe, authorize = { true }) { _, value -> RpcReply.Success(value) }
                    register(executeProcedure, authorize = { true }) { _, value ->
                        val fixture = checkNotNull(fixtureRef.get()) { "DR-A09 fixture is not installed" }
                        fixture.executeCount.incrementAndGet()
                        RpcReply.Success(
                            fixture.executeResponse.get()?.invoke(value) ?: fixture.defaultExecuteResponse(value),
                        )
                    }
                    if (registerLookup) register(LocalMasterClientRpcProtocolV1.LOOKUP,
                        authorize = { true }) { _, value ->
                        val fixture = checkNotNull(fixtureRef.get()) { "DR-A09 fixture is not installed" }
                        fixture.lookupCount.incrementAndGet()
                        RpcReply.Success(
                            fixture.lookupResponse.get()?.invoke(value) ?: fixture.defaultLookupResponse(value),
                        )
                    }
                }
                host = createdHost
                clientTrust.seed(appId, RpcTrustPurpose.SelectedHosts, setOf(createdHost.localIdentity.fingerprint))
                val createdClient = RpcClient.create(clientPlatform, applicationScope) {
                    this.appId = appId
                    deviceName = "DR-A09 Client"
                    this.lan = networkPolicy
                    trustStore = clientTrust
                    transportReconnect = false
                }
                client = createdClient
                withTimeout(30_000) { createdHost.start() }
                withTimeout(10_000) { createdHost.state.first { it == RpcHostState.Running } }
                val hostPort = Dra09LoopbackTransportFactory.portFor(appId, "DR-A09 Master")
                val selected = RpcSelectedHost(
                    createdHost.localIdentity.fingerprint,
                    RpcEndpoint(networkPolicy.localAddress, hostPort),
                )
                withTimeout(15_000) { createdClient.connect(selected) }
                withTimeout(10_000) { createdClient.state.first { it == RpcConnectionState.Ready } }

                val bridge = bindControlledBridge(
                    client = createdClient,
                    master = captureControlledMaster(createdHost.localIdentity, scope, ::id, namespace),
                    scope = scope,
                    lifetime = lifetime,
                    connectionId = connectionId,
                    id = ::id,
                )
                val fixture = Dra09OfficialRpcFixture(
                    host = createdHost,
                    client = createdClient,
                    scope = scope,
                    appId = appId,
                    actor = actor,
                    connectionId = connectionId,
                    lifetime = lifetime,
                    applicationScope = applicationScope,
                    marker = marker,
                    probe = probe,
                    executeProcedure = executeProcedure,
                    credentials = LocalGatewayCredentials(connectionId, OpaqueCredential(ByteArray(52) { 9 })),
                    bridge = bridge,
                )
                fixtureRef.set(fixture)
                return fixture
            } catch (failure: Throwable) {
                runCatching { withTimeout(15_000) { temporaryClient?.close() } }
                runCatching { withTimeout(15_000) { client?.close() } }
                runCatching { withTimeout(15_000) { host?.close() } }
                lifetime.cancel()
                applicationScope.cancel()
                runCatching { Files.deleteIfExists(marker) }
                runCatching { Files.deleteIfExists(markerDir) }
                throw failure
            }
        }

        /**
         * Controlled POS association DATA only. The SDK identities are read from the actual
         * running Host/Client; no native keys, pairing custody or device admission are created.
         */
        internal fun captureControlledMaster(
            hostIdentity: RpcLocalIdentity,
            scope: BranchScope,
            id: (Int) -> DomainId,
            namespace: Int,
        ): LocalMasterSdkOriginalV1 {
            fun digest(character: Char): PayloadDigest = PayloadDigest(character.toString().repeat(64))
            val appId = hostIdentity.appId
            val coordinates = SelfOwnedAllocationCoordinatesV1(
                allocationId = id(6), scope = scope, installationId = id(7),
                assignmentId = id(8), deviceId = id(9), storageInstanceId = id(10),
                storageKeyId = id(11), pepperKeyId = id(12), targetSigningKeyId = id(13),
                firstActorId = id(14),
            )
            val allocation = OriginalAllocationCommitmentV2(
                coordinates = coordinates,
                originalNativeBindingDigest = digest('1'),
                p1RootDocumentDigest = digest('2'),
                p1DelegationDigest = digest('3'),
                p1PreparationDigest = digest('4'),
                targetPublicKey = ByteArray(65).also { it[0] = 4; it[1] = namespace.toByte() },
            )
            val nativeApplication = SelfOwnedTargetDescriptorV1(
                "synthetic.dra09", 12_009, "a".repeat(64), "b".repeat(64),
            )
            return LocalMasterSdkOriginalV1.capture(
                originalAcquisitionId = id(15),
                allocation = allocation,
                nativeApplication = nativeApplication,
                appId = appId,
                peerId = hostIdentity.peerId,
                fingerprint = hostIdentity.fingerprint,
                pairingQr = hostIdentity.pairingQr,
                exactDeviceName = hostIdentity.deviceName,
            )
        }

        internal fun bindControlledBridge(
            client: RpcClient,
            master: LocalMasterSdkOriginalV1,
            scope: BranchScope,
            lifetime: Job,
            connectionId: DomainId,
            id: (Int) -> DomainId,
        ): LocalMasterClientRpcBridgeV1 {
            fun digest(character: Char): PayloadDigest = PayloadDigest(character.toString().repeat(64))
            val clientIdentity = client.localIdentity
            val appId = master.appId
            val nativeApplication = master.nativeApplication
            val targetDevice = id(20)
            val targetKey = id(21)
            val targetPoint = ByteArray(65).also { it[0] = 4; it[64] = 2 }
            val admission = LocalDeviceAdmission(
                scope = scope, deviceId = targetDevice, revision = Revision(0), admitted = true,
                publicKeyIdentifier = targetKey.value, publicFingerprint = sha256(targetPoint).value,
                capabilities = emptySet(),
            )
            val targetDescriptor = LocalDevicePairingTargetDescriptor.capture(admission, targetKey, targetPoint)
            val clientSdk = LocalDevicePairingSdkIdentityV2.capture(
                originalAcquisitionId = id(16),
                originalPreparationId = id(17),
                target = targetDescriptor,
                nativeApplication = nativeApplication,
                appId = appId,
                peerId = clientIdentity.peerId,
                fingerprint = clientIdentity.fingerprint,
                pairingQr = clientIdentity.pairingQr,
            )
            val original = LocalDevicePairingSdkOriginal(clientSdk, clientIdentity.deviceName)
            val approved = LocalDevicePairingApprovedMaster(
                NativeDevicePairingApprovedMasterIdentityV1(
                    masterSdkOriginal = master,
                    targetSdkIdentity = clientSdk,
                    originalPairingActionId = id(18),
                    originalRequestId = id(19),
                    scope = scope,
                    targetDeviceId = targetDevice,
                    targetKeyId = targetKey,
                    rootId = id(22),
                    rootKeyId = id(23),
                    foundingActionId = id(24),
                    rootFingerprint = "c".repeat(64),
                    rootDocumentDigest = digest('d'),
                    approvalCertificateDigest = digest('e'),
                    challengeDigest = digest('f'),
                    possessionDigest = digest('5'),
                ),
            )
            return LocalMasterClientRpcBridgeV1.bind(
                client, original, approved, lifetime, connectionId,
                LocalMasterRpcWorkflowProfile.SUPPLIERS,
            )
        }
    }
}

internal class Dra09MemoryIdentityStore : JvmSecureIdentityStore {
    private val values = mutableMapOf<String, ByteArray>()

    override fun read(namespace: String): ByteArray? = synchronized(values) { values[namespace]?.copyOf() }

    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray =
        synchronized(values) { values.getOrPut(namespace) { value.copyOf() }.copyOf() }

    override fun delete(namespace: String): Boolean = synchronized(values) { values.remove(namespace) != null }
}

internal class Dra09MemoryTrustStore : RpcTrustStore {
    private val values = mutableMapOf<Pair<AppId, RpcTrustPurpose>, Set<dev.p2pkit.core.PeerFingerprint>>()

    fun seed(appId: AppId, purpose: RpcTrustPurpose, fingerprints: Set<dev.p2pkit.core.PeerFingerprint>) {
        synchronized(values) { values[appId to purpose] = fingerprints.toSet() }
    }

    override suspend fun load(appId: AppId, purpose: RpcTrustPurpose): Set<dev.p2pkit.core.PeerFingerprint> =
        synchronized(values) { values[appId to purpose].orEmpty() }

    override suspend fun replace(appId: AppId, purpose: RpcTrustPurpose,
        fingerprints: Set<dev.p2pkit.core.PeerFingerprint>) {
        synchronized(values) { values[appId to purpose] = fingerprints.toSet() }
    }
}


internal fun writeDurableMarker(path: Path, value: String) {
    Files.createDirectories(path.parent)
    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
        val buffer = ByteBuffer.wrap(value.encodeToByteArray())
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}

internal suspend fun <T> withDra09Fixture(
    executeVersion: Int = 1,
    registerLookup: Boolean = true,
    block: suspend (Dra09OfficialRpcFixture) -> T,
): T {
    val fixture = withTimeout(60_000) { Dra09OfficialRpcFixture.start(executeVersion, registerLookup) }
    try {
        return withTimeout(60_000) { block(fixture) }
    } finally {
        fixture.close()
    }
}
