package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import android.app.Application
import android.util.AtomicFile
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import java.io.File
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/** Inputs for SIDC-02's separately frozen UNALLOCATED_MIXED no-PO variant.
 * Runtime identities, catalog sources and storage revisions are observed, not fixture labels. */
internal object NativeInvoiceCaptureFixture {
    val login = LocalLoginIdentifier("invoice.capture.author")
    const val headLogin = "invoice.capture.fixture.head"
    const val invoiceReference = "000073-C/فاتورة"
    const val businessDate = "2026-11-05"
    const val reasonCode = "synthetic.invoice.capture.sidc02b"

    val setup = NativeLocalFixtureSetup(
        companyName = "Synthetic Invoice Capture Company",
        branchName = "Synthetic Invoice Capture Branch",
        installerLogin = "invoice.capture.fixture.installer",
        headLogin = headLogin,
        initialAuthorityEpoch = Revision(1),
        founding = { protection -> LocalCompanyFoundingFactsV1(
            SelfOwnedCompanyFactsV1("Synthetic Invoice Capture Company", "Synthetic Invoice Capture Installer",
                "invoice.capture.fixture.installer", protection),
            "Synthetic Invoice Capture Company Head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
            LocalCompanyHeadPurposeV1.entries.toSet(),
        ) },
        account = ::account,
    )

    private val permissions = setOf(
        Permission(SecurityAction.PURCHASING_INVOICE_CAPTURE, PermissionVerb.PERFORM),
        Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
        Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM),
        Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
        Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
        Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM),
    )

    fun account(c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning = LocalAccountProvisioning(
        c.firstActorId, login,
        ActivatedLocalPolicy(c.scope, c.firstActorId, Revision(0), permissions.map { permission ->
            PermissionGrant(permission, c.scope, GrantEffect.ALLOW,
                if (permission.action == SecurityAction.PURCHASING_INVOICE_CAPTURE)
                    PermissionLimits(maximumAmount = Money(Currency.EGP, 100_000)) else PermissionLimits(),
                OwnerAccess.OWN_ONLY, c.firstActorId, null, null)
        }, LocalSessionPolicy(maximumConcurrentSessions = 1),
            ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000,
                maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000), approvalPolicy = null),
        LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000), setOf(c.deviceId),
    )

    /** The actual activated capture employee has no baseline provisioning or financial actions.
     * The monetary fixture input is retained; this leg does not test a monetary-ceiling boundary. */
    fun requireCapturePolicy(actual: ActivatedLocalPolicy, c: SelfOwnedAllocationCoordinatesV1) {
        val requested = account(c).policy
        check(actual.scope == c.scope && actual.actorId == c.firstActorId && actual.revision == requested.revision &&
            actual.sessionPolicy == requested.sessionPolicy && actual.throttlePolicy == requested.throttlePolicy &&
            actual.approvalPolicy == null)
        check(actual.grants.map { it.permission }.toSet() == permissions && actual.grants.size == permissions.size)
        actual.grants.forEach { grant ->
            check(grant.scope == c.scope && grant.effect == GrantEffect.ALLOW && grant.ownerAccess == OwnerAccess.OWN_ONLY &&
                grant.namedSubjectId == c.firstActorId && grant.targetIds == null && grant.delegationCeiling == null)
            val expected = requested.grants.single { it.permission == grant.permission }
            check(grant.limits == expected.limits)
        }
    }

    /** Runtime-only fixture secrets. No password String, hard-coded credential or transcript. */
    fun secret(): CharArray {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@%_-".toCharArray()
        val random = SecureRandom()
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

/** Separate synthetic observation, never a frozen fixture, credential store or native receipt.
 * Preserve every original observation; an existing/uncertain file is not erased or replaced. */
internal class NativeInvoiceCaptureEvidence(application: Application) : NativeLocalFixtureEvidence {
    private val file: AtomicFile
    private val bindings = JSONObject()
    private val events = JSONArray()
    private val document = JSONObject()
        .put("case", "SIDC-02").put("variant", "b_no_po_context")
        .put("specification", "supplier-invoice-discrepancy-capture-v1")
        .put("status", "STARTED_NOT_VERIFIED")
        .put("bindings", bindings).put("events", events)
        .put("whole_case", "NOT_QUALIFIED")
        .put("editor_controller_installed_ui", "NOT_RUN")
        .put("additional_client_rpc", "NOT_RUN")
        .put("device_owner_campaign", "NOT_RUN")
        .put("icr_verification", false)
        .put("central_implementation_and_verification", "HELD")

    init {
        val directory = File(application.noBackupFilesDir, "native-invoice-capture-observations")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot retain capture observation" }
        val intent = File(directory, "sidc02b-original-intent.json")
        check(listOf(intent, File("${intent.path}.bak"), File("${intent.path}.new")).none { it.exists() }) {
            "An original capture observation exists; it cannot be erased or replaced"
        }
        check(intent.createNewFile()) { "Capture observation already exists" }
        file = AtomicFile(intent)
        flush()
    }

    @Synchronized override fun bind(symbol: String, value: Any?) {
        check(!bindings.has(symbol)) { "An original capture symbol cannot be rebound" }
        bindings.put(symbol, value ?: JSONObject.NULL)
        flush()
    }

    @Synchronized override fun event(step: String, facts: JSONObject) {
        events.put(JSONObject().put("step", step).put("facts", facts))
        flush()
    }

    @Synchronized fun status(value: String) { document.put("status", value); flush() }

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
        } finally { bytes.fill(0) }
    }
}

internal suspend fun <T> captureSecret(original: CharArray, action: suspend (CharArray) -> T): T =
    nativeFixtureSecret(original, action)
