package com.ukkera.pos.database

import androidx.room3.Room
import com.ukkera.pos.database.independent.dra09officialrpc.Dra09MemoryIdentityStore
import com.ukkera.pos.database.independent.dra09officialrpc.Dra09MemoryTrustStore
import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.rpc.LocalRpcAuthenticationError
import com.ukkera.pos.rpc.LocalRpcAuthenticationRequest
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponse
import com.ukkera.pos.rpc.authenticationExchangeProcedure
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.transport.lan.OrganizationLan
import dev.p2pkit.rpc.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.CharArraySerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Test-process entry point. Official factories and real production codecs are used;
 * the bootstrap and the fixed SDK-peer -> synthetic-fixture mapping below are NOT
 * production enrollment, native custody, the POS ingress or a production owner. */
internal object RoomAuthenticationTcpEndpoint {
    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        val (role, directory, iface, address, scenario) = arguments.toList()
        require(role in setOf("host", "client"))
        require(scenario in setOf("logout", "refresh", "move", "wrong-device", "throttle", "renewal", "verifier-unavailable"))
        val root = Path.of(directory)
        Files.writeString(root.resolve("$role.boot"), "booted")
        await(root, "network.ready")
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first {
            Files.isRegularFile(it.resolve("qa/pos/freezes/auth-v1/cases.json"))
        }
        val designBytes = Files.readAllBytes(repository.resolve("qa/pos/freezes/auth-v1/cases.json"))
        check(authV1Sha256(designBytes) == "e6fa22b836f7fd2c2face14d83544edd8554007faee6214c0ec36492a3c96100")
        val design = Json.parseToJsonElement(designBytes.decodeToString()).jsonObject
        val ids = design.getValue("fixtures").jsonObject.getValue("F-AUTH-BASE-v1").jsonObject
            .getValue("identity_handles").jsonObject
        fun id(name: String) = DomainId(ids.getValue(name).jsonPrimitive.content)
        val branch = BranchScope(id("O1"), id("C1_CLOTHING"), id("B1_CLOTHING_DOWNTOWN"))
        val throttle = if (scenario == "throttle") RoomAuthenticationTcpThrottleSlice(repository) else null
        val renewal = if (scenario == "renewal") RoomAuthenticationTcpRenewalSlice(repository) else null
        val verifierFailure = if (scenario == "verifier-unavailable") RoomAuthenticationTcpVerifierFailureSlice(repository) else null
        val throttleControl = RpcProcedure("test.auth.fixture.throttle", 1, String.serializer(),
            String.serializer(), LocalRpcAuthenticationError.serializer())
        val appId = LocalMasterSdkProfileV1.appId(branch)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val lan = OrganizationLan(listOf("10.203.5.0/30"), iface, address)
        val bootstrap = RpcProcedure("test.auth.fixture.inputs", 1, String.serializer(),
            TcpAuthenticationInputs.serializer(), LocalRpcAuthenticationError.serializer())
        try {
            if (role == "host") {
                val platform = RpcPlatform.jvm(Dra09MemoryIdentityStore())
                val trust = Dra09MemoryTrustStore()
                val identity = RpcClient.create(platform, scope) {
                    this.appId = appId; deviceName = "Controlled Room auth Host identity"
                    this.lan = lan; trustStore = trust; transportReconnect = false
                }
                val fingerprint = identity.localIdentity.fingerprint
                identity.close()
                Files.writeString(root.resolve("host.identity"), fingerprint.value)
                val peerA = PeerFingerprint(await(root, "clientA.identity"))
                val peerB = PeerFingerprint(await(root, "clientB.identity"))
                assertNotEquals(peerA, peerB)
                trust.seed(appId, RpcTrustPurpose.HostClients, setOf(peerA, peerB))
                val wiringBytes = Files.readAllBytes(repository.resolve("qa/pos/wiring/auth-v1/wiring-v3.json"))
                check(authV1Sha256(wiringBytes) == "9c6508917316935a42882019396c24a005434abd7b7f711e5ce4a624815236be")
                val wiring = Json.parseToJsonElement(wiringBytes.decodeToString()).jsonObject
                AuthV1Journal(root, UUID.randomUUID().toString().replace("-", ""), wiring).use { journal ->
                    val databasePath = root.resolve("controlled-auth.db")
                    val fixture = AuthV1Fixture("tcp-$scenario", databasePath, design, journal,
                        AuthV1FixtureSetup(initiallySignedIn = setOf("B", "M")),
                        controlledObservation = renewal ?: throttle ?: verifierFailure,
                        controlledThrottlePolicy = throttle?.policy ?: verifierFailure?.policy,
                        observeHasher = { verifierFailure?.observeHasher(it) ?: throttle?.observeHasher(it) ?: it }, controlledTokenPolicy = renewal?.tokenPolicy)
                    try {
                        fixture.start()
                        val baseline = fixture.snapshot("before-tcp-login")
                        assertTrue(baseline.accounts.getValue("A").state.sessions.isEmpty())
                        val businessBefore = businessRows(databasePath)
                        var signedIn: AuthV1Snapshot? = null
                        val gate = Mutex()
                        val host = RpcHost.create(platform, scope) {
                            this.appId = appId; deviceName = "Controlled Room authentication Host"
                            this.lan = lan; trustStore = trust; advertise = false; requestHistoryCapacity = 32
                            register(bootstrap, authorize = { it.fingerprint in setOf(peerA, peerB) }) { _, _ ->
                                // Disposable random fixture password travels only inside the
                                // authenticated/encrypted test socket, never through a file/log.
                                RpcReply.Success(TcpAuthenticationInputs(fixture.login("A").value,
                                    fixture.copyClientPassword("A"), fixture.actors.getValue("A").value,
                                    fixture.device("A").value, fixture.device("B").value))
                            }
                            if (throttle != null || verifierFailure != null) register(throttleControl,
                                authorize = { it.fingerprint == peerA }) { _, command ->
                                gate.withLock { RpcReply.Success(verifierFailure?.control(command, fixture)
                                    ?: checkNotNull(throttle).control(command, fixture)) }
                            }
                            renewal?.install(this, fixture, peerA, gate)
                            register(authenticationExchangeProcedure,
                                authorize = { it.fingerprint in setOf(peerA, peerB) }) { context, request ->
                                gate.withLock {
                                    check(request.claimForCallback())
                                    try {
                                        val peer = when (context.peer.fingerprint) {
                                            peerA -> "A"
                                            peerB -> "B"
                                            else -> error("Unpinned fixture peer")
                                        }
                                        val connection = fixture.connections.getValue(peer)
                                        val response = when (request.action) {
                                            LocalRpcAuthenticationAction.LOGIN -> {
                                                val result = fixture.endpoint.signIn(connection,
                                                    LocalLoginIdentifier(checkNotNull(request.login)),
                                                    checkNotNull(request.password), request.explicitDeviceMove)
                                                renewal?.remember(result, fixture)
                                                val captured = LocalRpcAuthenticationResponse.capture(request.action, result)
                                                if (signedIn == null) signedIn = fixture.snapshot("first-tcp-sign-in")
                                                captured
                                            }
                                            LocalRpcAuthenticationAction.CURRENT -> withBearer(request, connection) {
                                                LocalRpcAuthenticationResponse.capture(request.action, fixture.endpoint.current(it))
                                            }
                                            LocalRpcAuthenticationAction.REFRESH -> withBearer(request, connection) {
                                                renewal?.advanceForRefresh()
                                                val result = fixture.endpoint.refresh(it)
                                                renewal?.remember(result, fixture)
                                                LocalRpcAuthenticationResponse.capture(request.action, result)
                                            }
                                            LocalRpcAuthenticationAction.LOGOUT -> withBearer(request, connection) {
                                                LocalRpcAuthenticationResponse.capture(request.action, fixture.endpoint.logout(it))
                                            }
                                        }
                                        RpcReply.Success(response)
                                    } finally { request.destroyAndAwaitCleanup() }
                                }
                            }
                        }
                        try {
                            assertEquals(fingerprint, host.localIdentity.fingerprint)
                            host.start()
                            withTimeout(15_000) { host.state.first { it == RpcHostState.Running } }
                            Files.writeString(root.resolve("host.port"), host.endpoint().port.toString())
                            await(root, "client.observed")
                            val after = fixture.snapshot("after-tcp-$scenario")
                            val initialSignIn = gate.withLock { signedIn }
                            val currentA = after.accounts.getValue("A").authV1Active()
                            assertEquals(if (scenario == "logout") 0 else 1, currentA.size)
                            if (scenario != "logout") assertEquals(await(root, "client.observed"), currentA.single().sessionId.value)
                            assertTrue(authV1SamePolicy(baseline.accounts.getValue("A").policy, after.accounts.getValue("A").policy))
                            for (other in listOf("B", "M")) {
                                assertTrue(authV1SameObservedAccount(baseline.accounts.getValue(other), after.accounts.getValue(other)))
                                fixture.currentControl(other, "tcp-$scenario-unrelated")
                            }
                            if (scenario == "wrong-device") {
                                assertTrue(authV1SameObservedAccount(checkNotNull(initialSignIn).accounts.getValue("A"),
                                    after.accounts.getValue("A")))
                            }
                            if (scenario in setOf("refresh", "renewal")) assertTrue(after.events.any {
                                it.kind == LocalAuthEventKind.REFRESHED && it.targetId == fixture.actors.getValue("A") &&
                                    it.sessionId == currentA.single().sessionId
                            })
                            renewal?.verify(after)
                            assertEquals(businessBefore, businessRows(databasePath))
                            Files.writeString(root.resolve("host.observed"), scenario)
                            await(root, "client.pass")
                        } finally {
                            host.close()
                            withContext(NonCancellable) { renewal?.dispose() }
                        }
                    } finally { withContext(NonCancellable) { fixture.dispose() } }
                }
                // A Host pass includes the actual grouped service's confirmed close.
                Files.writeString(root.resolve("host.pass"), scenario)
            } else {
                val trust = Dra09MemoryTrustStore()
                val platformA = RpcPlatform.jvm(Dra09MemoryIdentityStore())
                val platformB = RpcPlatform.jvm(Dra09MemoryIdentityStore())
                suspend fun identity(platform: RpcPlatform, who: String): PeerFingerprint {
                    val client = RpcClient.create(platform, scope) {
                        this.appId = appId; deviceName = "Controlled auth $who identity"
                        this.lan = lan; trustStore = trust; transportReconnect = false
                    }
                    return try { client.localIdentity.fingerprint }
                    finally { client.close() }
                }
                val peerA = identity(platformA, "A")
                val peerB = identity(platformB, "B")
                Files.writeString(root.resolve("clientA.identity"), peerA.value)
                Files.writeString(root.resolve("clientB.identity"), peerB.value)
                val remote = PeerFingerprint(await(root, "host.identity"))
                trust.seed(appId, RpcTrustPurpose.SelectedHosts, setOf(remote))
                suspend fun connect(platform: RpcPlatform, expected: PeerFingerprint): RpcClient {
                    val client = RpcClient.create(platform, scope) {
                        this.appId = appId; deviceName = "Controlled Room authentication Client"
                        this.lan = lan; trustStore = trust; transportReconnect = false
                    }
                    try {
                        assertEquals(expected, client.localIdentity.fingerprint)
                        client.connect(RpcSelectedHost(remote, RpcEndpoint("10.203.5.1", await(root, "host.port").toInt())))
                        withTimeout(15_000) { client.state.first { it == RpcConnectionState.Ready } }
                        return client
                    } catch (failure: Throwable) { client.close(); throw failure }
                }
                val owned = mutableListOf<LocalSessionTokens>()
                var inputs: TcpAuthenticationInputs? = null
                val clientA = connect(platformA, peerA)
                try {
                    val clientB = connect(platformB, peerB)
                    try {
                        inputs = assertIs<RpcReply.Success<TcpAuthenticationInputs>>(clientA.call(bootstrap,
                            "frozen-auth-v1-inputs", timeout = 15.seconds,
                            retry = RpcRetry.RecoverOnly(maxAttempts = 1))).value
                        val data = checkNotNull(inputs)
                        suspend fun login(client: RpcClient, move: Boolean): LocalAuthenticationResult =
                            exchange(client, LocalRpcAuthenticationRequest(DomainId.random().value,
                                LocalRpcAuthenticationAction.LOGIN, login = data.login, password = data.password.copyOf(),
                                explicitDeviceMove = move)).authenticationResult()
                        suspend fun current(client: RpcClient, tokens: LocalSessionTokens): LocalSessionViewResult {
                            val result = exchange(client, bearer(LocalRpcAuthenticationAction.CURRENT, tokens.access)).sessionViewResult()
                            if (result is LocalSessionViewResult.Current) {
                                // Same frozen identity/policy association as authV1FlowEvidence.current.
                                assertEquals(tokens.view.actorId, result.view.actorId)
                                assertEquals(tokens.view.scope, result.view.scope)
                                assertEquals(tokens.view.deviceId, result.view.deviceId)
                                assertEquals(tokens.view.sessionId, result.view.sessionId)
                                assertEquals(tokens.view.generation, result.view.generation)
                                assertEquals(tokens.view.login, result.view.login)
                                assertTrue(authV1SamePolicy(tokens.view.policy, result.view.policy))
                            }
                            return result
                        }
                        fun authenticationRefused(result: LocalAuthenticationResult) {
                            // Preserve authV1FlowEvidence.authenticationRefused's exact reason.
                            assertEquals(LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED), result)
                        }
                        fun retain(result: LocalAuthenticationResult): LocalSessionTokens =
                            assertIs<LocalAuthenticationResult.Authenticated>(result).tokens.also(owned::add)
                        suspend fun control(command: String) {
                            assertEquals("ok", assertIs<RpcReply.Success<String>>(clientA.call(throttleControl,
                                command, timeout = 15.seconds,
                                retry = RpcRetry.RecoverOnly(maxAttempts = 1))).value)
                        }
                        if (throttle != null) {
                            control("begin")
                            for ((index, row) in throttle.schedule.withIndex()) {
                                suspend fun wrongPassword(): LocalAuthenticationResult {
                                    val wrong = data.password.copyOf()
                                    wrong[0] = if (wrong[0] == 'a') 'b' else 'a'
                                    return exchange(clientA, LocalRpcAuthenticationRequest(DomainId.random().value,
                                        LocalRpcAuthenticationAction.LOGIN, login = data.login,
                                        password = wrong, explicitDeviceMove = false)).authenticationResult()
                                }
                                authenticationRefused(wrongPassword())
                                control("completed:$index")
                                if (row.getValue("delay_ms").jsonPrimitive.long > 0) {
                                    control("before:$index")
                                    assertIs<LocalAuthenticationResult.Denied>(wrongPassword())
                                    control("blocked:$index")
                                    // REMP-R04: the same account through a different admitted
                                    // SDK Client at the SAME pre-deadline moment. Use the valid
                                    // password so only the retained throttle can justify refusal.
                                    assertEquals(LocalAuthenticationResult.Denied(LocalAuthenticationDenial.THROTTLED),
                                        login(clientB, false))
                                    control("switched:$index")
                                }
                                control("at:$index")
                            }
                            control("switching-complete")
                        }
                        if (verifierFailure != null) {
                            control("begin")
                            assertEquals(LocalAuthenticationResult.Unavailable, login(clientA, false))
                            control("unavailable")
                            control("restore")
                            assertEquals(LocalAuthenticationResult.Denied(LocalAuthenticationDenial.THROTTLED), login(clientA, false))
                            control("blocked")
                            control("before")
                            assertEquals(LocalAuthenticationResult.Denied(LocalAuthenticationDenial.THROTTLED), login(clientA, false))
                            control("blocked-before")
                            control("eligible")
                        }
                        val original = retain(login(clientA, false))
                        if (verifierFailure != null) control("matched")
                        if (throttle != null) {
                            assertEquals("ok", assertIs<RpcReply.Success<String>>(clientA.call(throttleControl,
                                "matched", timeout = 15.seconds,
                                retry = RpcRetry.RecoverOnly(maxAttempts = 1))).value)
                        }
                        assertEquals(data.actor, original.view.actorId.value)
                        assertEquals(branch, original.view.scope)
                        assertEquals(data.deviceA, original.view.deviceId.value)
                        assertEquals(1, original.view.policy.sessionPolicy.maximumConcurrentSessions)
                        assertEquals(2, original.view.policy.grants.size)
                        assertEquals(setOf(SecurityAction.ORDER_READ, SecurityAction.CASH_READ),
                            original.view.policy.grants.map { it.permission.action }.toSet())
                        assertTrue(original.view.policy.grants.all {
                            it.permission.verb == PermissionVerb.PERFORM && it.scope == branch &&
                                it.effect == GrantEffect.ALLOW && it.ownerAccess == OwnerAccess.OWN_ONLY &&
                                it.namedSubjectId == original.view.actorId
                        })
                        assertEquals(original.view.sessionId, assertIs<LocalSessionViewResult.Current>(current(clientA, original)).view.sessionId)
                        renewal?.assertBoundCurrent(clientA, "original", original.view.sessionId)
                        var surviving = original
                        when (scenario) {
                            "logout" -> {
                                assertEquals(LocalSignOutResult.Revoked, exchange(clientA,
                                    bearer(LocalRpcAuthenticationAction.LOGOUT, original.access)).signOutResult())
                                repeat(2) { assertEquals(LocalSessionViewResult.Denied, current(clientA, original)) }
                                authenticationRefused(exchange(clientA,
                                    bearer(LocalRpcAuthenticationAction.REFRESH, original.refresh)).authenticationResult())
                            }
                            "refresh", "renewal" -> {
                                surviving = retain(exchange(clientA,
                                    bearer(LocalRpcAuthenticationAction.REFRESH, original.refresh)).authenticationResult())
                                assertEquals(original.view.actorId, surviving.view.actorId)
                                assertEquals(original.view.deviceId, surviving.view.deviceId)
                                assertEquals(original.view.workExpiresAt, surviving.view.workExpiresAt)
                                if (renewal != null) {
                                    renewal.assertRotation(original, surviving)
                                    renewal.assertOriginalRefused(clientA)
                                    renewal.assertBoundCurrent(clientA, "successor", surviving.view.sessionId)
                                    renewal.assertOriginalRefused(clientA)
                                    authenticationRefused(exchange(clientA, bearer(LocalRpcAuthenticationAction.REFRESH,
                                        original.refresh)).authenticationResult())
                                }
                                assertEquals(LocalSessionViewResult.Denied, current(clientA, original))
                                assertEquals(surviving.view.sessionId,
                                    assertIs<LocalSessionViewResult.Current>(current(clientA, surviving)).view.sessionId)
                            }
                            "move" -> {
                                val unconfirmed = assertIs<LocalAuthenticationResult.Denied>(login(clientB, false))
                                assertEquals(LocalAuthenticationDenial.EXPLICIT_DEVICE_MOVE_REQUIRED, unconfirmed.reason)
                                assertIs<LocalSessionViewResult.Current>(current(clientA, original))
                                surviving = retain(login(clientB, true))
                                assertEquals(original.view.actorId, surviving.view.actorId)
                                assertEquals(original.view.scope, surviving.view.scope)
                                assertEquals(data.deviceB, surviving.view.deviceId.value)
                                assertEquals(LocalSessionViewResult.Denied, current(clientA, original))
                                authenticationRefused(exchange(clientA,
                                    bearer(LocalRpcAuthenticationAction.REFRESH, original.refresh)).authenticationResult())
                                assertEquals(surviving.view.sessionId,
                                    assertIs<LocalSessionViewResult.Current>(current(clientB, surviving)).view.sessionId)
                            }
                            "wrong-device" -> {
                                authenticationRefused(exchange(clientB,
                                    bearer(LocalRpcAuthenticationAction.REFRESH, original.refresh)).authenticationResult())
                                assertIs<LocalSessionViewResult.Current>(current(clientA, original))
                            }
                        }
                        Files.writeString(root.resolve("client.observed"), surviving.view.sessionId.value)
                        assertEquals(scenario, await(root, "host.observed"))
                    } finally { clientB.close() }
                } finally {
                    inputs?.password?.fill('\u0000')
                    owned.forEach(LocalSessionTokens::destroy)
                    clientA.close()
                }
                Files.writeString(root.resolve("client.pass"), scenario)
            }
        } finally { scope.cancel() }
    }

    private suspend fun await(root: Path, name: String): String = withTimeout(90_000) {
        val file = root.resolve(name)
        while (!Files.exists(file)) delay(25)
        Files.readString(file)
    }

    private suspend fun exchange(client: RpcClient, request: LocalRpcAuthenticationRequest): LocalRpcAuthenticationResponse =
        try {
            assertIs<RpcReply.Success<LocalRpcAuthenticationResponse>>(client.call(authenticationExchangeProcedure,
                request, timeout = 15.seconds, retry = RpcRetry.RecoverOnly(maxAttempts = 1))).value
        } finally { request.destroyAndAwaitCleanup() }

    private fun bearer(action: LocalRpcAuthenticationAction, credential: OpaqueCredential): LocalRpcAuthenticationRequest =
        credential.useBytes { LocalRpcAuthenticationRequest(DomainId.random().value, action, bearer = it.copyOf()) }

    private suspend fun <T> withBearer(request: LocalRpcAuthenticationRequest, connection: DomainId,
        block: suspend (LocalGatewayCredentials) -> T): T {
        val bearer = OpaqueCredential(checkNotNull(request.bearer))
        return try { block(LocalGatewayCredentials(connection, bearer)) } finally { bearer.destroy() }
    }

    private suspend fun businessRows(path: Path): Map<String, List<List<String>>> {
        val database = buildControlledMasterRoomFixture(Room.databaseBuilder<MasterRoomDatabase>(
            path.toString(), MasterRoomConstructor::initialize))
        return try { localAuthorizationRows(database).filterKeys {
            it in setOf("operation_result", "aggregate_state", "unique_claim", "journal_envelope", "outbox_intent")
        } } finally { database.close() }
    }
}

/** Test-only ephemeral input transfer over the pinned encrypted SDK socket. */
@Serializable(with = TcpAuthenticationInputsSerializer::class)
internal class TcpAuthenticationInputs(val login: String, val password: CharArray,
    val actor: String, val deviceA: String, val deviceB: String) {
    override fun toString(): String = "TcpAuthenticationInputs([REDACTED])"
}

/** The outgoing copy is wiped even if encoding fails; partial decoded buffers are
 * wiped on failure. SDK-owned encoded packets are outside this fixture's custody. */
internal object TcpAuthenticationInputsSerializer : KSerializer<TcpAuthenticationInputs> {
    override val descriptor = buildClassSerialDescriptor("TcpAuthenticationInputs") {
        element<String>("login"); element<CharArray>("password"); element<String>("actor")
        element<String>("deviceA"); element<String>("deviceB")
    }
    override fun serialize(encoder: Encoder, value: TcpAuthenticationInputs) {
        try {
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, value.login)
                encodeSerializableElement(descriptor, 1, CharArraySerializer(), value.password)
                encodeStringElement(descriptor, 2, value.actor)
                encodeStringElement(descriptor, 3, value.deviceA)
                encodeStringElement(descriptor, 4, value.deviceB)
            }
        } finally { value.password.fill('\u0000') }
    }
    override fun deserialize(decoder: Decoder): TcpAuthenticationInputs {
        var login: String? = null; var password: CharArray? = null; var actor: String? = null
        var deviceA: String? = null; var deviceB: String? = null
        try {
            decoder.decodeStructure(descriptor) {
                while (true) when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> login = decodeStringElement(descriptor, index)
                    1 -> { password?.fill('\u0000'); password = decodeSerializableElement(descriptor, index, CharArraySerializer()) }
                    2 -> actor = decodeStringElement(descriptor, index)
                    3 -> deviceA = decodeStringElement(descriptor, index)
                    4 -> deviceB = decodeStringElement(descriptor, index)
                    else -> throw SerializationException("Unexpected fixture input")
                }
            }
            return TcpAuthenticationInputs(checkNotNull(login), checkNotNull(password), checkNotNull(actor),
                checkNotNull(deviceA), checkNotNull(deviceB)).also { password = null }
        } finally { password?.fill('\u0000') }
    }
}
