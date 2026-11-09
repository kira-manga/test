package com.ukkera.pos.android.independent.nativesupplierreferencev2

import android.app.Application
import android.content.res.AssetManager
import android.util.AtomicFile
import android.util.Base64
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.CodeNormalization
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.io.File
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/** One selected leg per independently fresh installation. These are not whole-case PASS IDs. */
internal enum class SupplierReferenceV2Leg(val caseId: String, val supplierRole: String) {
    FULL_STATE("SREF2-002", "LEGACY"),
    CURRENT_V1_OMISSION("SREF2-003", "CURRENT");

    companion object {
        fun select(caseId: String): SupplierReferenceV2Leg = entries.singleOrNull { it.caseId == caseId }
            ?: error("Select exactly SREF2-002 or SREF2-003; other cases are not bound by this fixture")
    }
}

/** Exact frozen semantic input/oracle. String.length on Android is the required UTF-16 count. */
internal class SupplierReferenceV2Vector(private val entry: JSONObject) {
    val value: String = entry.getString("value")
    val id: String = entry.getString("id")
    private val expected = entry.getJSONObject("expected")
    val code = Code(expected.getString("original"), expected.getString("normalized_key"),
        CodeNormalization.valueOf(expected.getString("normalization_marker")))

    init {
        check(value.length == entry.getInt("utf16_code_units") && code.original == value &&
            code.normalizedKey == value && code.normalization == CodeNormalization.EXACT_V1)
    }
}

/** Loads the preserved base asset, not output generated from an application response. */
internal class SupplierReferenceV2Inputs private constructor(private val frozen: JSONObject) {
    val initialNames = names("initial")
    val editedNames = names("edited_names")
    val literal = vector("LITERAL")
    val mixed = vector("MIXED")
    val caseVersion: String = frozen.getString("case_version")

    fun operation(symbol: String): OperationId = OperationId(frozen.getJSONObject("operation_ids").getString(symbol))
    fun fixtureSupplierId(leg: SupplierReferenceV2Leg): String =
        frozen.getJSONObject("identities").getString("${leg.supplierRole}_supplier")

    private fun names(symbol: String): LocalNamesV1 = frozen.getJSONObject("names").getJSONObject(symbol).let {
        LocalNamesV1(it.getString("english"), it.getString("arabic"))
    }

    private fun vector(symbol: String): SupplierReferenceV2Vector {
        val values = frozen.getJSONArray("valid_values")
        return SupplierReferenceV2Vector((0 until values.length()).map(values::getJSONObject)
            .single { it.getString("id") == symbol })
    }

    companion object {
        const val CASE_VERSION = "SUPPLIER-REFERENCE-V2-CASES-1.0.0"
        const val CLARIFICATION_VERSION = "SUPPLIER-REFERENCE-V2-CLARIFICATION-1.0.1"

        fun load(assets: AssetManager, leg: SupplierReferenceV2Leg): SupplierReferenceV2Inputs {
            val frozen = JSONObject(assets.open("supplier-reference-v2/fixtures.json").use {
                it.readBytes().decodeToString(throwOnInvalidSequence = true)
            })
            val clarification = assets.open("supplier-reference-v2/clarification-v1.0.1.md").use {
                it.readBytes().decodeToString(throwOnInvalidSequence = true)
            }
            check(frozen.getString("design_id") == "supplier-reference-v2" &&
                frozen.getString("case_version") == CASE_VERSION && frozen.getString("version") == "1.0.0" &&
                frozen.getString("execution_status") == "NOT RUN" && frozen.isNull("tested_revision") &&
                frozen.isNull("execution_evidence") && clarification.contains(CLARIFICATION_VERSION))
            val cases = frozen.getJSONArray("cases")
            check((0 until cases.length()).map(cases::getJSONObject).single {
                it.getString("id") == leg.caseId
            }.getString("execution_status") == "NOT RUN")
            val reference = frozen.getJSONObject("reference_contract")
            check(reference.getString("normalization_marker") == "EXACT_V1" &&
                reference.getString("editor_empty_maps_to") == "EXPLICIT_NULL" &&
                !reference.getBoolean("trim_casefold_number_parse_or_unicode_normalize"))
            return SupplierReferenceV2Inputs(frozen)
        }
    }
}

/** Explicit scaffolding for the real native first-account installation, not assumed authority. */
internal object SupplierReferenceV2NativeSetup {
    const val companyName = "Synthetic Supplier Reference Company"
    const val branchName = "Synthetic Supplier Reference Branch"
    const val installerLogin = "supplier.reference.installer"
    const val headLogin = "supplier.reference.head"
    val employeeLogin = LocalLoginIdentifier("supplier.reference.operator")

    fun setup(): NativeLocalFixtureSetup = NativeLocalFixtureSetup(companyName, branchName, installerLogin,
        headLogin, Revision(1), ::founding, ::account)

    private fun founding(protection: SelfOwnedKeyProtectionV1) = LocalCompanyFoundingFactsV1(
        SelfOwnedCompanyFactsV1(companyName, "Synthetic Reference Installer", installerLogin, protection),
        "Synthetic Reference Company Head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        LocalCompanyHeadPurposeV1.entries.toSet())

    fun account(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val permissions = listOf(
            Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
            Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER),
            Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM))
        return LocalAccountProvisioning(coordinates.firstActorId, employeeLogin,
            ActivatedLocalPolicy(coordinates.scope, coordinates.firstActorId, Revision(0), permissions.map {
                PermissionGrant(it, coordinates.scope, GrantEffect.ALLOW, PermissionLimits(),
                    OwnerAccess.OWN_ONLY, coordinates.firstActorId, null, null)
            }, LocalSessionPolicy(maximumConcurrentSessions = 1),
                ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000,
                    maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000), approvalPolicy = null),
            LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000),
            setOf(coordinates.deviceId))
    }

    /** Runtime-only mutable secrets; caller and every API copy retain their own wipe obligation. */
    fun secret(): CharArray {
        val random = SecureRandom()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@%_-".toCharArray()
        val result = CharArray(32)
        var delivered = false
        return try {
            for (index in result.indices) result[index] = alphabet[random.nextInt(alphabet.size)]
            delivered = true
            result
        } finally {
            alphabet.fill('\u0000')
            if (!delivered) result.fill('\u0000')
        }
    }
}

/** Local synthetic observations only. Reservation precedes setup and never erases prior work. */
internal class SupplierReferenceV2Evidence(application: Application, leg: SupplierReferenceV2Leg,
    declaredRevision: String) : NativeLocalFixtureEvidence {
    private val file: AtomicFile
    private val bindings = JSONObject()
    private val events = JSONArray()
    private val document = JSONObject().put("selected_case", leg.caseId).put("selected_supplier_role", leg.supplierRole)
        .put("case_version", SupplierReferenceV2Inputs.CASE_VERSION)
        .put("clarification", SupplierReferenceV2Inputs.CLARIFICATION_VERSION)
        .put("declared_tested_revision", declaredRevision)
        .put("revision_attribution", "CALLER_DECLARATION_NOT_INSTALLATION_PROOF")
        .put("status", "STARTED_NOT_VERIFIED").put("whole_case", "NOT_QUALIFIED")
        .put("bindings", bindings).put("events", events)
        .put("unbound", JSONArray(listOf(
            "SREF2-001: remaining valid/invalid vectors and maximum outer encoding",
            "SREF2-002: independent CURRENT sequence and space-only editor step",
            "SREF2-003: actual pre-transition V1 canonical bytes/originals and old-binary goldens",
            "SREF2-004/005: CAS races, resends and changed-payload conflicts",
            "SREF2-006: independent grants, other actor/scope/device, expiry, revocation and ordered races",
            "SREF2-007: partial/unsupported capability and failure/no-fallback variants, pagination",
            "SREF2-008/009: uncertain/lost reply/cancellation/storage faults and original-source refusal",
            "SREF2-010/D01: installed shared forms, typing, layouts, language/access cleanup and filtering",
            "SREF2-D02: other native platform roles and actual admitted physical SUPPLIERS RPC",
            "Separately authorized protected stock/payable/cash views (native no-effects census is supplementary)",
            "Native setup delivery ACK (contract unspecified); complete original supplier workflow and owner acceptance")))
        .put("central_implementation_and_verification", "HELD")

    init {
        val directory = File(application.noBackupFilesDir, "native-supplier-reference-v2-observations")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot retain supplier reference observations" }
        val invocation = File(directory, "original-invocation.json")
        check(listOf(invocation, File("${invocation.path}.bak"), File("${invocation.path}.new")).none { it.exists() }) {
            "A prior or uncertain supplier reference invocation exists; independent execution cannot replace it"
        }
        check(invocation.createNewFile()) { "Supplier reference invocation reservation failed" }
        file = AtomicFile(invocation)
        flush()
    }

    @Synchronized override fun bind(symbol: String, value: Any?) {
        check(!bindings.has(symbol)) { "An original supplier reference binding cannot be reassigned" }
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
        } finally { bytes.fill(0) }
    }
}

internal fun supplierReferenceV2Base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
