package com.ukkera.pos.app.gateway.independent.kitchenhints

import com.ukkera.pos.app.LocalFoodDeliveryMode
import com.ukkera.pos.app.LocalFoodPreparation
import com.ukkera.pos.app.LocalFoodTaskState
import com.ukkera.pos.app.LocalFoodUiTask
import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Inert constructor scaffolding, never transport membership, authentication, policy activation,
 * current authority, clock/UOM semantics, or production handler installation. No journal exists.
 */
internal object KitchenHintUnitScaffolding {
    // This connection placeholder is NOT a frozen business/session/device ID or admitted peer.
    private val inertConnectionId: DomainId = DomainId("7f400000-0000-4000-8000-000000000001")

    internal fun access(
        row: JsonObject,
        arguments: List<FrozenKitchenGrantArguments>,
    ): KitchenHintUnitAccess {
        val session = FrozenKitchenHintInputs.session(row)
        val scope = FrozenKitchenHintInputs.scope(session.text("scopeRef"))
        val actor = FrozenKitchenHintInputs.id(session.text("actorIdRef"))
        // Complete exact grant input only. Incompatible frozen policy rows are not selected;
        // a new incompatibility fails setup and is never caught as a passing hint assertion.
        val grants = arguments.map { it.construct() }
        val policy = ActivatedLocalPolicy(
            scope = scope,
            actorId = actor,
            revision = Revision(session.long("activatedPolicy")),
            grants = grants,
            // Required constructor-only defaults, not a real provisioned session/throttle policy.
            sessionPolicy = LocalSessionPolicy(),
            throttlePolicy = ThrottlePolicy(),
        )
        val coordinates = FrozenKitchenHintCoordinates.display(row)
        val view = LocalSessionView(
            actorId = actor,
            scope = scope,
            deviceId = FrozenKitchenHintInputs.id(session.text("deviceIdRef")),
            sessionId = FrozenKitchenHintInputs.id(session.text("sessionIdRef")),
            generation = SessionGeneration(session.long("sessionGeneration")),
            login = LocalLoginIdentifier(session.text("login")),
            policy = policy,
            accessExpiresAt = coordinates.accessExpiry,
            workExpiresAt = coordinates.workExpiry,
        )
        val command = FrozenKitchenHintInputs.actionDeclaration("START").obj("command")
        val registryHint = LocalWorkflowInstallation(
            // Exact frozen named/versioned command coordinate only; no handler is installed.
            commands = setOf(CommandKey(command.text("key"), command.long("version").toInt())),
            reads = emptySet(),
        )
        val endpoint = KitchenHintFailOnCallMasterClient()
        // Sixteen owned zero bytes meet OpaqueCredential's constructor bound only.
        // They are deliberately not authentication tokens and are never read/printed/persisted.
        val ownedInertBearer = OpaqueCredential(ByteArray(16))
        try {
            val lease = LocalWorkflowCredentialLease(
                credentials = LocalGatewayCredentials(inertConnectionId, ownedInertBearer),
                currentLifetime = {
                    throw AssertionError("The inert hint-unit lifetime is not a current-authority observation")
                },
            )
            return KitchenHintUnitAccess(
                access = GatewayAccess(endpoint, lease, view, registryHint),
                endpoint = endpoint,
                constructorOnlyObservation = coordinates.observation,
                ownedInertBearer = ownedInertBearer,
            )
        } catch (failure: Throwable) {
            ownedInertBearer.destroy()
            throw failure
        }
    }

    internal fun task(
        row: JsonObject,
        taskState: LocalFoodTaskState,
        preparationState: LocalFoodPreparation,
    ): LocalFoodUiTask {
        val input = FrozenKitchenHintInputs.observation(row).obj("task")
        return LocalFoodUiTask(
            orderId = FrozenKitchenHintInputs.id(input.text("orderIdRef")),
            orderStorageRevision = Revision(input.long("orderStorageRevision")),
            portionId = FrozenKitchenHintInputs.id(input.text("portionIdRef")),
            preparationDomainRevision = Revision(input.long("preparationDomainRevision")),
            taskId = FrozenKitchenHintInputs.id(input.text("taskIdRef")),
            stationId = FrozenKitchenHintInputs.id(input.text("stationIdRef")),
            description = input.text("description"),
            quantity = FrozenKitchenHintCoordinates.quantity(row),
            itemNote = input.text("itemNote"),
            serviceReference = input.text("serviceReference"),
            taskState = taskState,
            preparation = preparationState,
            deliveryMode = LocalFoodDeliveryMode.valueOf(input.text("deliveryMode")),
        )
    }
}

internal class KitchenHintUnitAccess(
    internal val access: GatewayAccess,
    internal val endpoint: KitchenHintFailOnCallMasterClient,
    /** Retained supplied monotonic DATA; no helper accepts or validates this as a clock observation. */
    internal val constructorOnlyObservation: MonotonicMoment,
    private val ownedInertBearer: OpaqueCredential,
) {
    /** Clear only owned inert storage; no bearer access, token inspection, or endpoint call. */
    internal fun destroy(): Unit = ownedInertBearer.destroy()
}

/** Every client-port operation and even availability observation fails immediately if touched. */
internal class KitchenHintFailOnCallMasterClient : LocalMasterClient {
    internal var businessCommandAttempts: Int = 0
        private set

    override val health: StateFlow<LocalMasterEndpointHealth>
        get() = forbidden("health")

    override suspend fun signIn(
        connectionId: DomainId,
        login: LocalLoginIdentifier,
        password: CharArray,
        explicitDeviceMove: Boolean,
    ): LocalAuthenticationResult = forbidden("signIn")

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden("refresh")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = forbidden("current")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden("logout")

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        businessCommandAttempts += 1
        return forbidden("execute")
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = forbidden("lookup")
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply = forbidden("read")

    private fun forbidden(port: String): Nothing =
        throw AssertionError("The synthetic hint-unit MasterClient must not be called: $port")
}
