package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.Closeable
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One OS-authenticated console connection, owning only its actual service-side employee session. */
internal class DesktopMasterEmployeeService(private val gateway: BranchUiGateway?) : Closeable {
    private var employee: BranchUiSession? = null
    private var originalAttachment: LocalSessionAttachmentKey? = null
    private var correlation: DomainId? = null
    private val reviews = LinkedHashMap<DomainId, Any>()
    private val reviewIds = IdentityHashMap<Any, DomainId>()
    private val requests = Mutex()
    /** Cleanup handle only: never consulted by checked() or any interactive dispatch. */
    private class Retired(val correlation: DomainId, val session: BranchUiSession) {
        var result: LocalSessionEndResult? = null
    }
    private var retired: Retired? = null

    suspend fun request(message: IpcMessage.EmployeeRequest, frame: DesktopSetupIpcWire.Frame): IpcMessage.EmployeeReply = requests.withLock {
        try {
            require(message.requestId > 0 && message.secretCount == frame.secrets.size)
            val data = DesktopMasterEmployeeData.decode(message.payload) as List<*>
            val result = when (message.method) {
                "signIn" -> {
                    require(message.session == null && data.size == 2 && frame.secrets.size == 1)
                    close()
                    revokeRetired()
                    val result = gateway?.signIn(data[0] as String, frame.secret(0).copyOf(), data[1] as Boolean)
                        ?: SignInResult.Rejected(SignInFailure.NOT_CONFIGURED)
                    when (result) {
                        is SignInResult.Rejected -> listOf(result.reason)
                        is SignInResult.Authenticated -> {
                            val s = result.session
                            if (!s.isCurrent()) { s.close(); listOf(SignInFailure.UNAVAILABLE) }
                            else {
                                employee = s; originalAttachment = s.attachmentKey; correlation = DomainId.random()
                                listOf(project(s.display), s.attachmentKey, s.administration.configured,
                                    s.companyCatalog.configured, s.companyCatalog.foodConfiguration.configured,
                                    s.companyCatalog.returnRights.configured, s.companyCatalog.pricePolicies.configured,
                                    s.companyCatalog.releases.configured, s.companyCatalog.serialRegistrations.configured,
                                    s.setupClosure.availability())
                            }
                        }
                    }
                }
                "cleanupStatus", "retryCleanup" -> {
                    require(data.isEmpty() && frame.secrets.isEmpty())
                    if (message.method == "retryCleanup") gateway?.retryCleanup() ?: LocalClientCleanupStatus(false)
                    else gateway?.cleanupStatus() ?: LocalClientCleanupStatus(false)
                }
                "close", "logout" -> {
                    require(message.session != null && data.isEmpty() && frame.secrets.isEmpty())
                    if (message.session == correlation?.value) close()
                    else require(message.session == retired?.correlation?.value)
                    val result = revokeRetired()
                    if (message.method == "logout") result else true
                }
                else -> {
                    val s = checked(message.session, explicitRefresh = message.method == "refresh")
                    if (message.method == "current" || message.method == "refresh") {
                        require(data.isEmpty() && frame.secrets.isEmpty())
                        // The current check above already reauthorizes without inheriting a replaced attachment.
                        LocalSessionCheckResult.Current(project(s.display), originalAttachment)
                    } else try { dispatch(s, message.method, data, frame) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // Current employee/session checks succeeded above. An unavailable
                        // individual operation (for example an old review correlation) does
                        // not revoke this otherwise-current session. No outcome is inferred.
                        return@withLock IpcMessage.EmployeeReply(message.requestId, correlation?.value,
                            problem = "OPERATION_UNAVAILABLE")
                    }
                }
            }
            return@withLock IpcMessage.EmployeeReply(message.requestId, correlation?.value,
                DesktopMasterEmployeeData.encode(result, ::retainReview))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return@withLock IpcMessage.EmployeeReply(message.requestId, correlation?.value, problem = "UNAVAILABLE")
        }
    }

    private suspend fun checked(selected: String?, explicitRefresh: Boolean = false): BranchUiSession {
        val s = checkNotNull(employee)
        check(selected != null && selected == correlation?.value)
        if (!s.isCurrent()) { close(); error("Employee attachment ended") }
        val checked = if (explicitRefresh) s.refresh() else s.current()
        if (checked !is LocalSessionCheckResult.Current || !s.isCurrent() ||
            s.attachmentKey != originalAttachment || checked.attachmentKey != originalAttachment) {
            close()
            error("Employee attachment is unavailable or replaced")
        }
        return s
    }

    private fun retainReview(value: Any): DomainId = reviewIds[value] ?: run {
        check(employee?.isCurrent() == true)
        if (reviews.size == 64) {
            // Retire only an ephemeral display correlation, never its native original.
            // A stale view can be re-reviewed by original ID. Completed publications must
            // not exhaust the console after 64 ordinary catalog edits in one session.
            val oldest = reviews.entries.first()
            reviews.remove(oldest.key)
            reviewIds.remove(oldest.value)
        }
        DomainId.random().also { reviews[it] = value; reviewIds[value] = it }
    }
    private inline fun <reified T : Any> retainedReview(id: DomainId): T =
        reviews[id] as? T
            ?: error("Review is stale or belongs to another session")
    /** Only actual protected saved originals may be submitted through this administration surface. */
    private suspend fun requireWorkflow(s: BranchUiSession, id: OperationId) {
        var cursor: String? = null
        val seen = HashSet<String?>()
        repeat(64) {
            check(seen.add(cursor))
            val page = s.workflows.pending(cursor) as? LocalPendingResult.Available
                ?: error("Original workflow is unavailable")
            page.operations.firstOrNull { it.operationId == id }?.let {
                require(it.action.workflow in WORKFLOWS)
                return
            }
            cursor = page.nextCursor ?: error("Original workflow is not retained")
        }
        error("Pending traversal bound")
    }
    private fun filterPending(result: LocalPendingResult): LocalPendingResult = when (result) {
        is LocalPendingResult.Available -> LocalPendingResult.Available(
            result.operations.filter { it.action.workflow in WORKFLOWS }, result.nextCursor)
        is LocalPendingResult.Unavailable -> result
    }

    private suspend fun dispatch(s: BranchUiSession, method: String, data: List<*>, frame: DesktopSetupIpcWire.Frame): Any? =
        when (method) {
            "administration.read" -> { require(data.size == 1 && frame.secrets.size == 0); s.administration.read(data[0] as LocalAdministrationQuery) }
            "administration.pending" -> { require(data.size == 0 && frame.secrets.size == 0); s.administration.pending() }
            "administration.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.administration.prepare(data[0] as LocalAdministrationCommand) }
            "administration.submit" -> { require(data.size == 1 && frame.secrets.size in 0..1); s.administration.submit(data[0] as DomainId, frame.secrets.getOrNull(0)?.copyOf()) }
            "administration.lookup" -> { require(data.size == 1 && frame.secrets.size == 0); s.administration.lookup(data[0] as DomainId) }
            "administration.cancel" -> { require(data.size == 1 && frame.secrets.size == 0); s.administration.cancel(data[0] as DomainId) }
            "administration.acknowledge" -> { require(data.size == 1 && frame.secrets.size == 0); s.administration.acknowledge(data[0] as DomainId) }
            "catalog.prepareIngredient" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.prepareIngredient(data[0] as LocalCompanyIngredientSkuDraft) }
            "catalog.prepareFoodOutput" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.prepareFoodOutput(data[0] as LocalCompanyFoodOutputSkuDraft) }
            "catalog.status" -> { require(data.size == 0 && frame.secrets.size == 0); s.companyCatalog.status() }
            "catalog.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.prepare(data[0] as LocalCompanySkuDraft) }
            "catalog.review" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.review(data[0] as DomainId) }
            "catalog.approve" -> { require(data.size == 2 && frame.secrets.size == 1); s.companyCatalog.approve(retainedReview<LocalCompanyCatalogReview>(data[0] as DomainId), data[1] as String, frame.secrets.getOrNull(0)?.copyOf() ?: error("Missing transient secret")) }
            "catalog.completed" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.completed(data[0] as DomainId) }
            "catalog.stageActivation" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.stageActivation(data[0] as DomainId) }
            "food.status" -> { require(data.size == 0 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.status() }
            "food.sources" -> { require(data.size == 2 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.sources(data[0] as LocalFoodConfigurationSourceKind, data[1] as DomainId?) }
            "food.source" -> { require(data.size == 2 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.source(data[0] as LocalFoodConfigurationSourceKind, data[1] as DomainId) }
            "food.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.prepare(data[0] as LocalFoodRecipeDraft) }
            "food.review" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.review(data[0] as DomainId) }
            "food.approve" -> { require(data.size == 2 && frame.secrets.size == 1); s.companyCatalog.foodConfiguration.approve(retainedReview<LocalFoodRecipeReview>(data[0] as DomainId), data[1] as String, frame.secrets.getOrNull(0)?.copyOf() ?: error("Missing transient secret")) }
            "food.stageActivation" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.stageActivation(data[0] as DomainId) }
            "food.stageStation" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.stageStation(data[0] as LocalFoodStationDraft) }
            "food.stageRoute" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.foodConfiguration.stageRoute(data[0] as LocalFoodRouteDraft) }
            "rights.status" -> { require(data.size == 0 && frame.secrets.size == 0); s.companyCatalog.returnRights.status() }
            "rights.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.returnRights.prepare(data[0] as LocalReturnRightsPublicationDraft) }
            "rights.review" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.returnRights.review(data[0] as DomainId) }
            "rights.approve" -> { require(data.size == 2 && frame.secrets.size == 1); s.companyCatalog.returnRights.approve(retainedReview<LocalReturnRightsPublicationReview>(data[0] as DomainId), data[1] as String, frame.secrets.getOrNull(0)?.copyOf() ?: error("Missing transient secret")) }
            "rights.completed" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.returnRights.completed(data[0] as DomainId) }
            "rights.stageActivation" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.returnRights.stageActivation(data[0] as DomainId) }
            "prices.status" -> { require(data.size == 0 && frame.secrets.size == 0); s.companyCatalog.pricePolicies.status() }
            "prices.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.pricePolicies.prepare(data[0] as LocalPricePolicyPublicationDraft) }
            "prices.review" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.pricePolicies.review(data[0] as DomainId) }
            "prices.approve" -> { require(data.size == 2 && frame.secrets.size == 1); s.companyCatalog.pricePolicies.approve(retainedReview<LocalPricePolicyPublicationReview>(data[0] as DomainId), data[1] as String, frame.secrets.getOrNull(0)?.copyOf() ?: error("Missing transient secret")) }
            "prices.completed" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.pricePolicies.completed(data[0] as DomainId) }
            "prices.stageReceipt" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.pricePolicies.stageReceipt(data[0] as DomainId) }
            "releases.status" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.releases.status(data[0] as DomainId?) }
            "releases.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.releases.prepare(data[0] as LocalCatalogReleasePublicationDraft) }
            "releases.review" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.releases.review(data[0] as DomainId) }
            "releases.approve" -> { require(data.size == 2 && frame.secrets.size == 1); s.companyCatalog.releases.approve(retainedReview<LocalCatalogReleasePublicationReview>(data[0] as DomainId), data[1] as String, frame.secrets.getOrNull(0)?.copyOf() ?: error("Missing transient secret")) }
            "releases.completed" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.releases.completed(data[0] as DomainId) }
            "releases.stageActivation" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.releases.stageActivation(data[0] as DomainId) }
            "serials.status" -> { require(data.size == 2 && frame.secrets.size == 0); s.companyCatalog.serialRegistrations.status(data[0] as DomainId?, data[1] as DomainId?) }
            "serials.prepareAuthority" -> { require(data.size == 0 && frame.secrets.size == 0); s.companyCatalog.serialRegistrations.prepareAuthority() }
            "serials.prepare" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.serialRegistrations.prepare(data[0] as LocalSerialRegistrationDraft) }
            "serials.review" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.serialRegistrations.review(data[0] as DomainId) }
            "serials.approve" -> { require(data.size == 2 && frame.secrets.size == 1); s.companyCatalog.serialRegistrations.approve(retainedReview<LocalSerialPublicationReview>(data[0] as DomainId), data[1] as String, frame.secrets.getOrNull(0)?.copyOf() ?: error("Missing transient secret")) }
            "serials.completed" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.serialRegistrations.completed(data[0] as DomainId) }
            "serials.stageActivation" -> { require(data.size == 1 && frame.secrets.size == 0); s.companyCatalog.serialRegistrations.stageActivation(data[0] as DomainId) }
            "setup.readResult" -> { require(data.size == 0 && frame.secrets.size == 0); s.setupClosure.readResult() }
            "setup.readCheckpoints" -> { require(data.size == 0 && frame.secrets.size == 0); s.setupClosure.readCheckpoints() }
            "setup.pending" -> { require(data.size == 0 && frame.secrets.size == 0); s.setupClosure.pending() }
            "setup.acknowledge" -> { require(data.size == 1 && frame.secrets.size == 0); s.setupClosure.acknowledge(data[0] as OperationId) }
            "setup.goLive" -> { require(data.size == 1 && frame.secrets.size == 0); s.setupClosure.goLive(data[0] as OperationId) }
            "setup.configure" -> { require(data.size == 2 && frame.secrets.size == 0); s.setupClosure.configure(data[0] as OperationId, data[1] as LocalSetupConfigurationInput) }
            "setup.recordCatalogRow" -> { require(data.size == 2 && frame.secrets.size == 0); s.setupClosure.recordCatalogRow(data[0] as OperationId, data[1] as LocalSetupCatalogRow) }
            "setup.completeCatalogImport" -> { require(data.size == 2 && frame.secrets.size == 0); s.setupClosure.completeCatalogImport(data[0] as OperationId, data[1] as LocalSetupCatalogCompletionInput) }
            "setup.recordOpeningStockRow" -> { require(data.size == 2 && frame.secrets.size == 0); s.setupClosure.recordOpeningStockRow(data[0] as OperationId, data[1] as LocalSetupOpeningStockRow) }
            "setup.recordOpeningFloatRow" -> { require(data.size == 2 && frame.secrets.size == 0); s.setupClosure.recordOpeningFloatRow(data[0] as OperationId, data[1] as LocalSetupOpeningFloatRow) }
            "setup.completeOpening" -> { require(data.size == 2 && frame.secrets.size == 0); s.setupClosure.completeOpening(data[0] as OperationId, data[1] as LocalSetupOpeningCompletionInput) }
            "setup.lookup" -> { require(data.size == 1 && frame.secrets.size == 0); s.setupClosure.lookup(data[0] as OperationId) }
            "setup.resume" -> { require(data.size == 1 && frame.secrets.size == 0); s.setupClosure.resume(data[0] as OperationId) }
            "setup.acknowledgeRecorded" -> { require(data.size == 1 && frame.secrets.size == 0); s.setupClosure.acknowledgeRecorded(data[0] as OperationId) }
            "workflows.read" -> { require(data.size == 1 && frame.secrets.size == 0); require((data[0] as LocalWorkflowQuery).workflow in WORKFLOWS); s.workflows.read(data[0] as LocalWorkflowQuery) }
            "workflows.references" -> { require(data.size == 1 && frame.secrets.size == 0); s.workflows.references(data[0] as LocalReferenceQuery) }
            "workflows.stage" -> { require(data.size == 1 && frame.secrets.size == 0); require((data[0] as LocalCommandIntent).action.workflow in WORKFLOWS); s.workflows.stage(data[0] as LocalCommandIntent) }
            "workflows.submit" -> { require(data.size == 1 && frame.secrets.size == 0); requireWorkflow(s, data[0] as OperationId); s.workflows.submit(data[0] as OperationId) }
            "workflows.lookup" -> { require(data.size == 1 && frame.secrets.size == 0); requireWorkflow(s, data[0] as OperationId); s.workflows.lookup(data[0] as OperationId) }
            "workflows.pending" -> { require(data.size == 1 && frame.secrets.size == 0); filterPending(s.workflows.pending(data[0] as String?)) }
            "workflows.acknowledgeRecorded" -> { require(data.size == 1 && frame.secrets.size == 0); requireWorkflow(s, data[0] as OperationId); s.workflows.acknowledgeRecorded(data[0] as OperationId) }
            else -> error("Unknown employee-console method")
        }

    /** Synchronous denial only: closing a console never retires the cohost or Master service. */
    override fun close() {
        val s = employee
        val selected = correlation
        employee = null; originalAttachment = null; correlation = null
        reviews.clear(); reviewIds.clear()
        if (s != null && selected != null) {
            // The root gateway, not this transport handle, owns durable credential/revocation
            // custody. Its logout contract remains callable after synchronous close().
            retired = Retired(selected, s)
            s.close()
        }
    }
    private suspend fun revokeRetired(): LocalSessionEndResult {
        val exact = retired ?: return LocalSessionEndResult.UNAVAILABLE
        exact.result?.let { return it }
        return try { exact.session.logout().also { exact.result = it } }
        finally { exact.session.close() }
    }

    /** Connection loss denies immediately, then awaits only the exact native session cleanup. */
    suspend fun retireConnection(): Unit = requests.withLock {
        close()
        withContext(NonCancellable) { withTimeoutOrNull(25_000L) { revokeRetired() } }
        Unit
        // An unknown result/timeout never closes native journals or Master custody. The root's
        // durable cleanupStatus/retryCleanup and next sign-in retain that original revocation.
    }

    private fun project(display: SessionDisplayContext): SessionDisplayContext =
        display.copy(allowedAreas = display.allowedAreas.intersect(AREAS))
    private companion object {
        val WORKFLOWS = setOf(LocalWorkflow.CATALOG, LocalWorkflow.SETUP)
        val AREAS = setOf(AppArea.CATALOG, AppArea.SETUP, AppArea.PEOPLE, AppArea.DEVICES, AppArea.SESSION)
    }
}
