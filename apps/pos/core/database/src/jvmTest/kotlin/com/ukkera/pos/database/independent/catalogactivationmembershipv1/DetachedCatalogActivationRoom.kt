package com.ukkera.pos.database.independent.catalogactivationmembershipv1

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.JvmCompanyPublicationKeys
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.catalog.store.PinnedCompanyPublicationKey
import com.ukkera.pos.database.AggregateRow
import com.ukkera.pos.database.BranchRow
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ClaimRow
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterRoomConstructor
import com.ukkera.pos.database.MasterRoomDatabase
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.TransactionView
import com.ukkera.pos.database.buildDurableRoom
import com.ukkera.pos.database.requireDurableWriter
import com.ukkera.pos.database.storageKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Frozen public content; all envelope-only IDs below supply no native authority. */
internal object FrozenCatalogActivationMembershipV1 {
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val document = CatalogActivationMembershipV1UnavailableIntegrationTest::class.java
        .getResourceAsStream("/catalogactivationmembershipv1/catalog-unit-authority-v3.json")!!.use {
            json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }.also { check(it["specificationVersion"]!!.jsonPrimitive.content == "candidate-v3") }
    private val fixtures = document["fixtures"]!!.jsonObject
    private val identities = fixtures["F-CUA-IDENTITIES"]!!.jsonObject
    private val codec = fixtures["F-CUA-CODEC"]!!.jsonObject
    // Constructor metadata for detached data reads; these are not frozen native
    // originals, registered employees, policy grants or installed branch custody.
    internal val branchId = DomainId("10000000-aaaa-4000-8000-000000000003")
    internal val actorId = DomainId("10000000-aaaa-4000-8000-000000000004")
    internal val signingKeyId = DomainId("60000000-ffff-4000-8000-000000000002")
    internal val operationId = OperationId(identities["operation2"]!!.jsonPrimitive.content)

    internal fun canonical(fixtureId: String): String = codec[when (fixtureId) {
        "legacy" -> "legacyCanonicalUtf8"
        "explicit-base" -> "extendedCanonicalUtf8"
        else -> error("Unsupported frozen catalog input")
    }]!!.jsonPrimitive.content

    internal fun content(fixtureId: String): LocalPublishedSkuV1 {
        val canonical = canonical(fixtureId)
        val prefix = "ukkera.company-sku-publication/v1\n"
        check(canonical.startsWith(prefix))
        return json.decodeFromString(LocalPublishedSkuV1.serializer(), canonical.removePrefix(prefix))
    }

    internal fun codeClaim(signed: LocalSignedSkuV1): ClaimKey =
        ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "sku-code/${signed.definition.code}")

    internal fun signedCopy(payload: StoredBytes): LocalSignedSkuV1 {
        val record = json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        return json.decodeFromJsonElement(LocalSignedSkuV1.serializer(), record["body"]!!.jsonObject["value"]!!)
    }

    internal fun catalogCopy(scope: BranchScope, kind: String, id: DomainId, signed: LocalSignedSkuV1): ByteArray =
        buildJsonObject {
            put("scope", json.encodeToJsonElement(BranchScope.serializer(), scope))
            put("kind", kind)
            put("id", id.value)
            put("revision", "0")
            put("body", buildJsonObject {
                put("type", "SKU")
                put("value", json.encodeToJsonElement(LocalSignedSkuV1.serializer(), signed))
            })
            put("authorizedBy", actorId.value)
            put("policyRevision", "1")
            put("recordedAt", "1970-01-01T00:00:00Z")
        }.toString().encodeToByteArray()
}

/** Real cryptographic validity for disposable input; not a company-head ceremony. */
internal class DetachedCatalogEd25519Signature(company: CompanyScope) {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    internal val verifier = JvmCompanyPublicationKeys(listOf(PinnedCompanyPublicationKey(company,
        FrozenCatalogActivationMembershipV1.signingKeyId, pair.public.encoded)))

    internal fun sign(content: LocalPublishedSkuV1): LocalSignedSkuV1 {
        val bytes = LocalConfigurationCodec.canonicalPublication(content)
        val signature = try {
            Signature.getInstance("Ed25519").run { initSign(pair.private); update(bytes); sign() }
        } finally {
            bytes.fill(0)
        }
        val hex = signature.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        signature.fill(0)
        return LocalSignedSkuV1(content, FrozenCatalogActivationMembershipV1.signingKeyId, hex)
    }
}

internal class RecordingDetachedCatalogVerifier(private val actual: CompanyPublicationSignatureVerifier) :
    CompanyPublicationSignatureVerifier {
    internal val decisions = mutableListOf<Boolean>()
    override fun verify(company: CompanyScope, keyId: DomainId, canonicalDefinition: ByteArray,
        signatureHex: String): Boolean = actual.verify(company, keyId, canonicalDefinition, signatureHex).also {
        decisions += it
    }
}

/** Every storage call delegates to the production transaction reader. */
internal class RecordingDetachedCatalogView(private val actual: TransactionView) : CommandReadView by actual {
    internal var nativeObservationReads = 0
        private set
    internal var unavailableObservationReads = 0
        private set
    override suspend fun originalNativeBusinessPublications(): NativeCompanyBusinessPublicationSnapshot? {
        nativeObservationReads++
        return actual.originalNativeBusinessPublications().also {
            if (it == null) unavailableObservationReads++
        }
    }
}

/**
 * Genuine detached file-backed Room storage. No native owner, writer identity,
 * authorization source, admission proof or accepting history fixture is installed.
 */
internal class DetachedCatalogActivationRoom private constructor(
    private val directory: Path,
    private val path: Path,
    internal val scope: BranchScope,
    private var database: MasterRoomDatabase,
) : AutoCloseable {
    internal var hasCatalogCopies = false
        private set

    internal suspend fun insertCatalogCopies(signed: LocalSignedSkuV1) = withConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val dao = database.facts()
            // Invalid authority fields and writable=false provide only the FK parent.
            // No positive Master appointment or protected grant is manufactured.
            dao.insertBranch(BranchRow(scope.storageKey(), "", "", 0, 0, 0, "", false, ""))
            for ((kind, id) in listOf(LocalConfigurationProfile.SKU to signed.definition.skuId,
                LocalConfigurationProfile.PUBLICATION to signed.definition.publicationId)) {
                val payload = FrozenCatalogActivationMembershipV1.catalogCopy(scope, kind, id, signed)
                dao.insertAggregate(AggregateRow(scope.storageKey(), kind, id.value, 1,
                    LocalConfigurationProfile.schema(kind), payload, sha256(payload).value))
            }
            val claim = FrozenCatalogActivationMembershipV1.codeClaim(signed)
            dao.insertClaim(ClaimRow(scope.storageKey(), claim.namespace, claim.key, signed.definition.skuId.value))
        }
        hasCatalogCopies = true
    }

    internal suspend fun <T> withView(block: suspend (Transactor, TransactionView) -> T): T =
        withConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val view = TransactionView(scope, database.facts())
                try { block(connection, view) } finally { view.close() }
            }
        }

    /** Exact quoted SQL values preserve every selected column, including BLOB bytes. */
    internal suspend fun snapshot(): Map<String, List<List<String>>> = withConnection { connection ->
        listOf("branch_authority", "aggregate_state", "unique_claim", "operation_result", "journal_envelope",
            "audit_event", "outbox_intent").associateWith { table ->
            val columns = connection.usePrepared("PRAGMA table_info($table)") { statement ->
                buildList { while (statement.step()) add(statement.getText(1)) }
            }
            check(columns.isNotEmpty() && columns.all { name -> name.all { it.isLetterOrDigit() || it == '_' } })
            val quoted = columns.joinToString(",") { "quote(\"$it\")" }
            connection.usePrepared("SELECT $quoted FROM $table ORDER BY $quoted") { statement ->
                buildList { while (statement.step()) add(columns.indices.map { statement.getText(it) }) }
            }
        }
    }

    internal fun reopen() {
        database.close()
        database = open(path)
    }

    private suspend fun <T> withConnection(block: suspend (Transactor) -> T): T =
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            block(connection)
        }

    override fun close() {
        try { database.close() } finally { directory.toFile().deleteRecursively() }
    }

    internal companion object {
        internal fun create(company: CompanyScope): DetachedCatalogActivationRoom {
            val directory = Files.createTempDirectory("catalog-membership-read-")
            val path = directory.resolve("fixture.sqlite")
            return try {
                DetachedCatalogActivationRoom(directory, path, BranchScope(company.organizationId, company.companyId,
                    FrozenCatalogActivationMembershipV1.branchId), open(path))
            } catch (failure: Throwable) {
                directory.toFile().deleteRecursively()
                throw failure
            }
        }

        private fun open(path: Path): MasterRoomDatabase = buildDurableRoom(
            Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize),
        )
    }
}

internal suspend fun Transactor.catalogChangeCount(): Long = usePrepared("SELECT total_changes()") {
    check(it.step())
    it.getLong(0)
}
