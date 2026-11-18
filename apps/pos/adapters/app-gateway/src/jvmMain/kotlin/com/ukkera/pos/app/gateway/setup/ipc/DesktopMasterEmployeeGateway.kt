@file:Suppress("UNCHECKED_CAST")

package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.catalog.store.setup.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** UI-only proxies; session/credential/native publication objects never leave the service. */
public class DesktopMasterEmployeeGateway internal constructor(
    private val scope: CoroutineScope,
    private val live: () -> Boolean,
    private val send: (DesktopSetupIpcWire.Frame) -> Boolean,
    private val disconnectTransport: () -> Unit,
) : BranchUiGateway {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<IpcMessage.EmployeeReply>>()
    private class OperationUnavailable : IllegalStateException("Master operation is unavailable")
    private val active = MutableStateFlow<Session?>(null)
    override val configured: Boolean get() = live()
    override val signInSecretMaxLength: Int = 256
    internal fun reply(message: IpcMessage.EmployeeReply) { pending.remove(message.requestId)?.complete(message) }
    internal fun disconnect() {
        active.value?.deny()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Master console disconnected")) }
        pending.clear()
    }
    private suspend fun request(session: String?, method: String, data: List<Any?>, vararg secrets: CharArray?): IpcMessage.EmployeeReply {
        val copies = secrets.mapNotNull { it?.copyOf() }
        secrets.forEach { it?.fill('\u0000') }
        var frame: DesktopSetupIpcWire.Frame? = null
        var transferred = false
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<IpcMessage.EmployeeReply>()
        try {
            check(live())
            frame = DesktopSetupIpcWire.Frame(IpcMessage.EmployeeRequest(id, session, method,
                DesktopMasterEmployeeData.encode(data), copies.size), copies)
            pending[id] = result
            check(send(frame))
            transferred = true
            frame = null // writer owns/clears buffers after the transfer
            return withTimeout(120_000L) { result.await() }
        } finally {
            pending.remove(id)
            frame?.clear()
            if (!transferred) copies.forEach { it.fill('\u0000') }
        }
    }
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = signIn(account, secret, false)
    override suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> {
        try {
            if (!configured) return SignInResult.Rejected(SignInFailure.NOT_CONFIGURED)
            active.value?.deny()
            val reply = request(null, "signIn", listOf(account, explicitDeviceMove), secret)
            val data = DesktopMasterEmployeeData.decode(checkNotNull(reply.payload)) as List<*>
            if (data.size == 1) return SignInResult.Rejected(data.single() as SignInFailure)
            require(data.size == 10)
            return SignInResult.Authenticated(Session(checkNotNull(reply.session), data).also { active.value = it })
        } catch (cancelled: CancellationException) { disconnectTransport(); throw cancelled }
        catch (_: Exception) { disconnectTransport(); return SignInResult.Rejected(SignInFailure.UNAVAILABLE) }
        finally { secret.fill('\u0000') }
    }
    override suspend fun cleanupStatus(): LocalClientCleanupStatus = cleanup(false)
    override suspend fun retryCleanup(): LocalClientCleanupStatus = cleanup(true)
    private suspend fun cleanup(retry: Boolean): LocalClientCleanupStatus = try {
        val reply = request(null, if (retry) "retryCleanup" else "cleanupStatus", emptyList())
        DesktopMasterEmployeeData.decode(checkNotNull(reply.payload)) as LocalClientCleanupStatus
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { LocalClientCleanupStatus(false) }

    private inner class Session(private val correlation: String, data: List<*>) : BranchUiSession {
        override val display: SessionDisplayContext = data[0] as SessionDisplayContext
        private val key = MutableStateFlow(data[1] as LocalSessionAttachmentKey?)
        private val state = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
        override val lifecycle: StateFlow<LocalSessionLifecycle> = state
        override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> = key
        override fun isCurrent(): Boolean = active.value === this && live() && state.value == LocalSessionLifecycle.ACTIVE
        fun deny() { state.value = LocalSessionLifecycle.LOCKED; key.value = null }
        private suspend fun call(method: String, values: List<Any?> = emptyList(), vararg secrets: CharArray?): Any? {
            try {
                check(isCurrent())
                val reply = request(correlation, method, values, *secrets)
                check(reply.session == correlation && isCurrent())
                if (reply.problem == "OPERATION_UNAVAILABLE") throw OperationUnavailable()
                check(reply.problem == null)
                return DesktopMasterEmployeeData.decode(checkNotNull(reply.payload))
            } catch (failure: Exception) {
                if (failure !is OperationUnavailable) deny()
                throw failure
            }
            finally { secrets.forEach { it?.fill('\u0000') } }
        }
        override suspend fun current(): LocalSessionCheckResult = checkCurrent("current")
        override suspend fun refresh(): LocalSessionCheckResult = checkCurrent("refresh")
        private suspend fun checkCurrent(method: String): LocalSessionCheckResult {
            val attempted = key.value
            return try {
                val result = call(method) as LocalSessionCheckResult
                if (result !is LocalSessionCheckResult.Current || result.attachmentKey != key.value) deny()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { deny(); LocalSessionCheckResult.Unavailable(LocalServiceProblem.UNAVAILABLE, attempted) }
        }
        override suspend fun mayRestore(route: AppRoute): Boolean =
            RouteRegistry.area(route) in display.allowedAreas && current() is LocalSessionCheckResult.Current
        override fun close() {
            if (state.value != LocalSessionLifecycle.ACTIVE) return
            deny()
            scope.launch { runCatching { request(correlation, "close", emptyList()) } }
        }
        override suspend fun logout(): LocalSessionEndResult {
            deny()
            return try {
                val reply = request(correlation, "logout", emptyList())
                DesktopMasterEmployeeData.decode(checkNotNull(reply.payload)) as LocalSessionEndResult
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalSessionEndResult.UNRESOLVED }
        }
        private val administrationPort: LocalAdministrationUiGateway = object : LocalAdministrationUiGateway {
            override val configured: Boolean get() = isCurrent() && data[2] == true
            override suspend fun read(query: LocalAdministrationQuery): LocalAdministrationReadResult =
                try { call("administration.read", listOf(query)) as LocalAdministrationReadResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAdministrationReadResult.Unavailable }
            override suspend fun pending(): LocalAdministrationPendingResult =
                try { call("administration.pending", listOf()) as LocalAdministrationPendingResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAdministrationPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(command: LocalAdministrationCommand): LocalAdministrationResult =
                try { call("administration.prepare", listOf(command)) as LocalAdministrationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAdministrationResult.Unresolved(command.requestId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun submit(requestId: DomainId, newPassword: CharArray?): LocalAdministrationResult =
                try { call("administration.submit", listOf(requestId) , newPassword) as LocalAdministrationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.UNAVAILABLE) }
                finally { newPassword?.fill('\u0000') }
            override suspend fun lookup(requestId: DomainId): LocalAdministrationResult =
                try { call("administration.lookup", listOf(requestId)) as LocalAdministrationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun cancel(requestId: DomainId): LocalAdministrationResult =
                try { call("administration.cancel", listOf(requestId)) as LocalAdministrationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun acknowledge(requestId: DomainId): Boolean =
                try { call("administration.acknowledge", listOf(requestId)) as Boolean }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
        }
        private val catalogPort: LocalCompanyCatalogGateway = object : LocalCompanyCatalogGateway {
            override val configured: Boolean get() = isCurrent() && data[3] == true
            override val foodConfiguration: LocalFoodConfigurationGateway get() = foodPort
            override val returnRights: LocalReturnRightsPublicationGateway get() = rightsPort
            override val pricePolicies: LocalPricePolicyPublicationGateway get() = pricesPort
            override val releases: LocalCatalogReleasePublicationGateway get() = releasesPort
            override val serialRegistrations: LocalSerialRegistrationPublicationGateway get() = serialsPort
            override suspend fun prepareIngredient(draft: LocalCompanyIngredientSkuDraft): LocalCompanyCatalogReviewResult =
                try { call("catalog.prepareIngredient", listOf(draft)) as LocalCompanyCatalogReviewResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepareFoodOutput(draft: LocalCompanyFoodOutputSkuDraft): LocalCompanyCatalogReviewResult =
                try { call("catalog.prepareFoodOutput", listOf(draft)) as LocalCompanyCatalogReviewResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun status(): LocalCompanyCatalogStatusResult =
                try { call("catalog.status", listOf()) as LocalCompanyCatalogStatusResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(draft: LocalCompanySkuDraft): LocalCompanyCatalogReviewResult =
                try { call("catalog.prepare", listOf(draft)) as LocalCompanyCatalogReviewResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun review(originalActionId: DomainId): LocalCompanyCatalogReviewResult =
                try { call("catalog.review", listOf(originalActionId)) as LocalCompanyCatalogReviewResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun approve(review: LocalCompanyCatalogReview, headLogin: String, headSecret: CharArray): LocalCompanyCatalogApprovalResult =
                try { call("catalog.approve", listOf(review.remoteCorrelation ?: error("Review does not belong to this console"), headLogin) , headSecret) as LocalCompanyCatalogApprovalResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                finally { headSecret.fill('\u0000') }
            override suspend fun completed(originalActionId: DomainId): LocalCompanyCatalogApprovalResult =
                try { call("catalog.completed", listOf(originalActionId)) as LocalCompanyCatalogApprovalResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
                try { call("catalog.stageActivation", listOf(originalActionId)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(OperationId(originalActionId.value)) }
        }
        private val foodPort: LocalFoodConfigurationGateway = object : LocalFoodConfigurationGateway {
            override val configured: Boolean get() = isCurrent() && data[4] == true
            override suspend fun status(): LocalFoodConfigurationStatusResult =
                try { call("food.status", listOf()) as LocalFoodConfigurationStatusResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalFoodConfigurationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun sources(kind: LocalFoodConfigurationSourceKind, after: DomainId?): LocalFoodConfigurationReadResult =
                try { call("food.sources", listOf(kind, after)) as LocalFoodConfigurationReadResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalFoodConfigurationReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun source(kind: LocalFoodConfigurationSourceKind, id: DomainId): LocalFoodConfigurationReadResult =
                try { call("food.source", listOf(kind, id)) as LocalFoodConfigurationReadResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalFoodConfigurationReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(draft: LocalFoodRecipeDraft): LocalFoodRecipeResult =
                try { call("food.prepare", listOf(draft)) as LocalFoodRecipeResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalFoodRecipeResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun review(originalActionId: DomainId): LocalFoodRecipeResult =
                try { call("food.review", listOf(originalActionId)) as LocalFoodRecipeResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalFoodRecipeResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun approve(review: LocalFoodRecipeReview, headLogin: String, headSecret: CharArray): LocalFoodRecipeResult =
                try { call("food.approve", listOf(review.remoteCorrelation ?: error("Review does not belong to this console"), headLogin) , headSecret) as LocalFoodRecipeResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalFoodRecipeResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                finally { headSecret.fill('\u0000') }
            override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
                try { call("food.stageActivation", listOf(originalActionId)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(OperationId(originalActionId.value)) }
            override suspend fun stageStation(draft: LocalFoodStationDraft): LocalStageResult =
                try { call("food.stageStation", listOf(draft)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(draft.operationId) }
            override suspend fun stageRoute(draft: LocalFoodRouteDraft): LocalStageResult =
                try { call("food.stageRoute", listOf(draft)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(draft.operationId) }
        }
        private val rightsPort: LocalReturnRightsPublicationGateway = object : LocalReturnRightsPublicationGateway {
            override val configured: Boolean get() = isCurrent() && data[5] == true
            override suspend fun status(): LocalReturnRightsPublicationStatusResult =
                try { call("rights.status", listOf()) as LocalReturnRightsPublicationStatusResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReturnRightsPublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(draft: LocalReturnRightsPublicationDraft): LocalReturnRightsPublicationResult =
                try { call("rights.prepare", listOf(draft)) as LocalReturnRightsPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun review(originalActionId: DomainId): LocalReturnRightsPublicationResult =
                try { call("rights.review", listOf(originalActionId)) as LocalReturnRightsPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun approve(review: LocalReturnRightsPublicationReview, headLogin: String, headSecret: CharArray): LocalReturnRightsPublicationResult =
                try { call("rights.approve", listOf(review.remoteCorrelation ?: error("Review does not belong to this console"), headLogin) , headSecret) as LocalReturnRightsPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                finally { headSecret.fill('\u0000') }
            override suspend fun completed(originalActionId: DomainId): LocalReturnRightsPublicationResult =
                try { call("rights.completed", listOf(originalActionId)) as LocalReturnRightsPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
                try { call("rights.stageActivation", listOf(originalActionId)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(OperationId(originalActionId.value)) }
        }
        private val pricesPort: LocalPricePolicyPublicationGateway = object : LocalPricePolicyPublicationGateway {
            override val configured: Boolean get() = isCurrent() && data[6] == true
            override suspend fun status(): LocalPricePolicyPublicationStatusResult =
                try { call("prices.status", listOf()) as LocalPricePolicyPublicationStatusResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPricePolicyPublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(draft: LocalPricePolicyPublicationDraft): LocalPricePolicyPublicationResult =
                try { call("prices.prepare", listOf(draft)) as LocalPricePolicyPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun review(originalActionId: DomainId): LocalPricePolicyPublicationResult =
                try { call("prices.review", listOf(originalActionId)) as LocalPricePolicyPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun approve(review: LocalPricePolicyPublicationReview, headLogin: String, headSecret: CharArray): LocalPricePolicyPublicationResult =
                try { call("prices.approve", listOf(review.remoteCorrelation ?: error("Review does not belong to this console"), headLogin) , headSecret) as LocalPricePolicyPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                finally { headSecret.fill('\u0000') }
            override suspend fun completed(originalActionId: DomainId): LocalPricePolicyPublicationResult =
                try { call("prices.completed", listOf(originalActionId)) as LocalPricePolicyPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun stageReceipt(originalActionId: DomainId): LocalStageResult =
                try { call("prices.stageReceipt", listOf(originalActionId)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(OperationId(originalActionId.value)) }
        }
        private val releasesPort: LocalCatalogReleasePublicationGateway = object : LocalCatalogReleasePublicationGateway {
            override val configured: Boolean get() = isCurrent() && data[7] == true
            override suspend fun status(catalogId: DomainId?): LocalCatalogReleasePublicationStatusResult =
                try { call("releases.status", listOf(catalogId)) as LocalCatalogReleasePublicationStatusResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCatalogReleasePublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(draft: LocalCatalogReleasePublicationDraft): LocalCatalogReleasePublicationResult =
                try { call("releases.prepare", listOf(draft)) as LocalCatalogReleasePublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun review(originalActionId: DomainId): LocalCatalogReleasePublicationResult =
                try { call("releases.review", listOf(originalActionId)) as LocalCatalogReleasePublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun approve(review: LocalCatalogReleasePublicationReview, headLogin: String, headSecret: CharArray): LocalCatalogReleasePublicationResult =
                try { call("releases.approve", listOf(review.remoteCorrelation ?: error("Review does not belong to this console"), headLogin) , headSecret) as LocalCatalogReleasePublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                finally { headSecret.fill('\u0000') }
            override suspend fun completed(originalActionId: DomainId): LocalCatalogReleasePublicationResult =
                try { call("releases.completed", listOf(originalActionId)) as LocalCatalogReleasePublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
                try { call("releases.stageActivation", listOf(originalActionId)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(OperationId(originalActionId.value)) }
        }
        private val serialsPort: LocalSerialRegistrationPublicationGateway = object : LocalSerialRegistrationPublicationGateway {
            override val configured: Boolean get() = isCurrent() && data[8] == true
            override suspend fun status(registrationId: DomainId?, skuId: DomainId?): LocalSerialPublicationStatusResult =
                try { call("serials.status", listOf(registrationId, skuId)) as LocalSerialPublicationStatusResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSerialPublicationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepareAuthority(): LocalSerialPublicationResult =
                try { call("serials.prepareAuthority", listOf()) as LocalSerialPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun prepare(draft: LocalSerialRegistrationDraft): LocalSerialPublicationResult =
                try { call("serials.prepare", listOf(draft)) as LocalSerialPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun review(originalActionId: DomainId): LocalSerialPublicationResult =
                try { call("serials.review", listOf(originalActionId)) as LocalSerialPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun approve(review: LocalSerialPublicationReview, login: String, secret: CharArray): LocalSerialPublicationResult =
                try { call("serials.approve", listOf(review.remoteCorrelation ?: error("Review does not belong to this console"), login) , secret) as LocalSerialPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                finally { secret.fill('\u0000') }
            override suspend fun completed(originalActionId: DomainId): LocalSerialPublicationResult =
                try { call("serials.completed", listOf(originalActionId)) as LocalSerialPublicationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSerialPublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
                try { call("serials.stageActivation", listOf(originalActionId)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(OperationId(originalActionId.value)) }
        }
        private val setupPort: LocalSetupClosureUiGateway = object : LocalSetupClosureUiGateway {
            override fun availability(): LocalSetupClosureAvailability =
                if (isCurrent()) data[9] as LocalSetupClosureAvailability else LocalSetupClosureAvailability.NOT_CONFIGURED
            override fun catalogManifestPreview(rows: List<LocalSetupCatalogRow>): LocalSetupManifestPreview =
                LocalSetupManifestPreview(rows.size, LocalSetupCheckpointCodecV1.catalogInputManifestDigest(rows.map {
                    LocalSetupCatalogImportManifestEntryV1(it.sourceId, it.skuId, it.catalogOperationId)
                })?.value)
            override fun openingManifestPreview(stock: List<LocalSetupOpeningStockRow>, float: List<LocalSetupOpeningFloatRow>): LocalSetupOpeningManifestPreview =
                LocalSetupOpeningManifestPreview(
                    LocalSetupManifestPreview(stock.size, LocalSetupCheckpointCodecV1.stockInputManifestDigest(stock.map {
                        LocalSetupOpeningStockManifestEntryV1(it.sourceId, it.receiptId, it.openingOperationId)
                    })?.value),
                    LocalSetupManifestPreview(float.size, LocalSetupCheckpointCodecV1.floatInputManifestDigest(float.map {
                        LocalSetupOpeningFloatManifestEntryV1(it.sourceId, it.sessionId, it.openingOperationId)
                    })?.value))
            override suspend fun readResult(): LocalSetupReadResult<LocalSetupResultView> =
                try { call("setup.readResult", listOf()) as LocalSetupReadResult<LocalSetupResultView> }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun readCheckpoints(): LocalSetupReadResult<LocalSetupCheckpointView> =
                try { call("setup.readCheckpoints", listOf()) as LocalSetupReadResult<LocalSetupCheckpointView> }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun pending(): LocalSetupReadResult<List<LocalSetupPendingView>> =
                try { call("setup.pending", listOf()) as LocalSetupReadResult<List<LocalSetupPendingView>> }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun acknowledge(operationId: OperationId): LocalSetupMutationResult =
                try { call("setup.acknowledge", listOf(operationId)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun goLive(operationId: OperationId): LocalSetupMutationResult =
                try { call("setup.goLive", listOf(operationId)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun configure(operationId: OperationId, input: LocalSetupConfigurationInput): LocalSetupMutationResult =
                try { call("setup.configure", listOf(operationId, input)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun recordCatalogRow(operationId: OperationId, row: LocalSetupCatalogRow): LocalSetupMutationResult =
                try { call("setup.recordCatalogRow", listOf(operationId, row)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun completeCatalogImport(operationId: OperationId, input: LocalSetupCatalogCompletionInput): LocalSetupMutationResult =
                try { call("setup.completeCatalogImport", listOf(operationId, input)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun recordOpeningStockRow(operationId: OperationId, row: LocalSetupOpeningStockRow): LocalSetupMutationResult =
                try { call("setup.recordOpeningStockRow", listOf(operationId, row)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun recordOpeningFloatRow(operationId: OperationId, row: LocalSetupOpeningFloatRow): LocalSetupMutationResult =
                try { call("setup.recordOpeningFloatRow", listOf(operationId, row)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun completeOpening(operationId: OperationId, input: LocalSetupOpeningCompletionInput): LocalSetupMutationResult =
                try { call("setup.completeOpening", listOf(operationId, input)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun lookup(operationId: OperationId): LocalSetupMutationResult =
                try { call("setup.lookup", listOf(operationId)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun resume(operationId: OperationId): LocalSetupMutationResult =
                try { call("setup.resume", listOf(operationId)) as LocalSetupMutationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult =
                try { call("setup.acknowledgeRecorded", listOf(operationId)) as LocalAcknowledgementResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
        }
        private val workflowsPort: LocalWorkflowGateway = object : LocalWorkflowGateway {
            override suspend fun read(query: LocalWorkflowQuery): LocalReadResult =
                try { call("workflows.read", listOf(query)) as LocalReadResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult =
                try { call("workflows.references", listOf(query)) as LocalReferenceResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReferenceResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun stage(intent: LocalCommandIntent): LocalStageResult =
                try { call("workflows.stage", listOf(intent)) as LocalStageResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalStageResult.Uncertain(intent.operationId) }
            override suspend fun submit(operationId: OperationId): LocalOperationResult =
                try { call("workflows.submit", listOf(operationId)) as LocalOperationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun lookup(operationId: OperationId): LocalOperationResult =
                try { call("workflows.lookup", listOf(operationId)) as LocalOperationResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
            override suspend fun pending(cursor: String?): LocalPendingResult =
                try { call("workflows.pending", listOf(cursor)) as LocalPendingResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult =
                try { call("workflows.acknowledgeRecorded", listOf(operationId)) as LocalAcknowledgementResult }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
        }
        override val administration: LocalAdministrationUiGateway get() = administrationPort
        override val companyCatalog: LocalCompanyCatalogGateway get() = catalogPort
        override val setupClosure: LocalSetupClosureUiGateway get() = setupPort
        override val workflows: LocalWorkflowGateway get() = workflowsPort
    }
}
