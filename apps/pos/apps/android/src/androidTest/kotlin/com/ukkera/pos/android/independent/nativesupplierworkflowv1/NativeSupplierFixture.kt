package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import android.app.Application
import android.util.AtomicFile
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.security.*
import com.ukkera.pos.android.independent.nativefixture.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.io.File
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/** Fixed inputs from native-supplier-workflow-v1, retained by the v2 clarification.
 * Native IDs, employee sessions, supplier IDs and command revisions are NOT fixture literals. */
internal object NativeSupplierFixture {
    const val organizationName = "Synthetic Supplier Organization"
    const val companyName = "Synthetic Supplier Company"
    const val branchName = "Synthetic Supplier Branch"
    const val employeeName = "Synthetic Supplier Operator"
    val login = LocalLoginIdentifier("supplier.operator")
    val createNames = LocalNamesV1("Atlas Supplies", "مستلزمات أطلس")
    val updateNames = LocalNamesV1("Atlas Supplies East", "مستلزمات أطلس الشرقية")
    val staleNames = LocalNamesV1("Stale Supplier Name", "اسم مورد قديم")

    // These are explicit native fixture construction inputs, not assumed business revisions
    // or default authority. The real founding/head/installation ceremony must approve them.
    const val installerLogin = "supplier.fixture.installer"
    const val headLogin = "supplier.fixture.head"
    val initialAuthorityEpoch = Revision(1)

    fun founding(protection: SelfOwnedKeyProtectionV1) = LocalCompanyFoundingFactsV1(
        SelfOwnedCompanyFactsV1(companyName, "Synthetic Supplier Installer", installerLogin, protection),
        "Synthetic Supplier Company Head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        LocalCompanyHeadPurposeV1.entries.toSet(),
    )

    fun account(c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val permissions = listOf(
            Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
            Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER),
            Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM),
        )
        return LocalAccountProvisioning(c.firstActorId, login,
            ActivatedLocalPolicy(c.scope, c.firstActorId, Revision(0), permissions.map {
                PermissionGrant(it, c.scope, GrantEffect.ALLOW, PermissionLimits(),
                    OwnerAccess.OWN_ONLY, c.firstActorId, null, null)
            }, LocalSessionPolicy(maximumConcurrentSessions = 1),
                ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000,
                    maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000), approvalPolicy = null),
            LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000), setOf(c.deviceId))
    }

    /** No String conversion, fixture credential, loggable token, or persisted password. */
    fun secret(): CharArray {
        val random = SecureRandom()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@%_-".toCharArray()
        val generated = CharArray(32)
        var delivered = false
        return try {
            for (index in generated.indices) generated[index] = alphabet[random.nextInt(alphabet.size)]
            delivered = true
            generated
        } finally {
            alphabet.fill('\u0000')
            if (!delivered) generated.fill('\u0000')
        }
    }
}

/** Separate execution record; never edits a frozen fixture or exports credential/native files.
 * Every bind is single-assignment, and the record is flushed before dispatch milestones. */
internal class NativeSupplierEvidence(application: Application) : NativeLocalFixtureEvidence {
    private val file: AtomicFile
    private val bindings = JSONObject()
    private val events = JSONArray()
    private val document = JSONObject().put("case", "NSUP-01").put("profile", "P")
        .put("specification", "native-supplier-workflow-v1 + continuation-clarification-v2")
        .put("status", "STARTED_NOT_VERIFIED").put("bindings", bindings).put("events", events)
        .put("whole_case", "NOT_QUALIFIED").put("native_setup_result_delivery_ack", "NOT_RUN_CONTRACT_UNSPECIFIED")
        .put("stale_controller_generated_original", "NOT_QUALIFIED_SAVED_R1_UI_PATH_MISSING")
        .put("unasserted_setup_inputs", JSONArray(listOf("organization_display_name", "employee_display_name",
            "company_activity=clothing", "currency=EGP", "timezone=Africa/Cairo", "locales=en,ar")))
        .put("sdk_rpc_qualification", false).put("installed_ui_qualification", false)
        .put("central_implementation_and_verification", "HELD")

    init {
        val directory = File(application.noBackupFilesDir, "native-supplier-observations")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot retain execution observation" }
        val intent = File(directory, "original-intent.json")
        check(listOf(intent, File("${intent.path}.bak"), File("${intent.path}.new")).none { it.exists() }) {
            "An original supplier execution exists; this fixture cannot erase or replace it"
        }
        check(intent.createNewFile()) { "Execution observation already exists" }
        file = AtomicFile(intent)
        flush()
    }

    @Synchronized override fun bind(symbol: String, value: Any?) {
        check(!bindings.has(symbol)) { "An original fixture symbol cannot be rebound" }
        bindings.put(symbol, value ?: JSONObject.NULL)
        flush()
    }

    @Synchronized override fun event(step: String, facts: JSONObject) {
        events.put(JSONObject().put("step", step).put("facts", facts))
        flush()
    }

    @Synchronized fun status(status: String) { document.put("status", status); flush() }

    @Synchronized private fun flush() {
        val bytes = document.toString(2).encodeToByteArray()
        try {
            val output = file.startWrite()
            try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
            catch (failure: Throwable) {
                try { file.failWrite(output) } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
        finally { bytes.fill(0) }
    }
}

internal suspend fun <T> supplierSecret(original: CharArray, action: suspend (CharArray) -> T): T =
    nativeFixtureSecret(original, action)

internal fun <T> supplierNativeValue(result: LocalCompanyAuthorityResult<T>): T = nativeFixtureValue(result)

internal fun requireSameSupplierCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1) =
    requireSameNativeFixtureCoordinates(a, b)
