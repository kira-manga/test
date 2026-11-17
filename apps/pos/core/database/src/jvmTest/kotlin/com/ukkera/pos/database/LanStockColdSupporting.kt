package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.inventory.store.LocalStockCommands
import com.ukkera.pos.inventory.store.LocalStockQueries
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Self-authored, NOT independent. Fresh-JVM, existing-only native follow-up to an
 * already completed genuine M/C1/C2 stock run. It creates no business original and
 * cannot turn an incomplete TCP run into a pass. Linux SOFTWARE custody only. */
internal object LanStockColdSupporting {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        check(java.lang.Boolean.getBoolean(LanGenuineStockSupport.ENABLE_PROPERTY))
        check(!java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
        require(args.size == 1)
        val root = Path.of(args.single()).toRealPath()
        check(Files.isDirectory(root.resolve("master"), NOFOLLOW_LINKS))
        check(Files.isDirectory(root.resolve("exchange"), NOFOLLOW_LINKS))
        val exchange = LanFixtureExchange(root.resolve("exchange"))
        for (label in listOf("stock.verified", "client1.closed", "client2.closed", "master.closed", "executor.completed"))
            check(exchange.read(label) != null) { "The original TCP stock case did not complete: $label" }
        withTimeout(600_000L) {
            val original = LanGenuineProcessEndpoint.LanGenuineMaster(root.resolve("master"))
            val opened = DesktopSelfOwnedInstallationOwner.open(original.os, SelfOwnedNativeProfileV1.LIVE)
            check(opened is SelfOwnedNativeOpenResult.Opened) { "Existing native Master refused" }
            val coordinates = opened.context.materialAtOpen.coordinates
            check(coordinates.scope.storageKey() == exchange.awaitText("master.scope"))
            val publications = when (val read = opened.context.readOriginalBusinessPublications()) {
                is LocalCompanyAuthorityResult.Value -> read.value
                is LocalCompanyAuthorityResult.Unavailable -> error("Existing publications refused: ${read.reason}")
            }
            val registry = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
                UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
                nativePublications = publications)
            val composed = original.factory.fromOriginalContext(opened.context, registry.commands, registry.reads,
                UnavailablePhysicalRecordingExecutionGate, registry.nativeOriginalImports, registry.stockProjection, registry.historyProducer)
            check(composed is AndroidSelfOwnedMasterOpenResult.Opened) { "Existing Master composition refused" }
            val service = composed.service
            try {
                check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Cold mode: ${service.mode.value}" }
                val originalClient = service.originalLocalClient()
                val sdk = when (val read = originalClient.recoverRpcMasterOriginal()) {
                    is LocalCompanyAuthorityResult.Value -> read.value
                    is LocalCompanyAuthorityResult.Unavailable -> error("Existing SDK identity refused: ${read.reason}")
                }
                check(sdk.copyWireBytes().contentEquals(exchange.await("master.sdk")))
                val client = checkNotNull(service.client)
                val connection = checkNotNull(service.localConnectionId)
                suspend fun <T> signedIn(login: String, password: String, actor: DomainId,
                    use: suspend (LocalGatewayCredentials) -> T): T {
                    val auth = client.signIn(connection, LocalLoginIdentifier(login), password.toCharArray())
                    check(auth is LocalAuthenticationResult.Authenticated) { "Fresh cold sign-in refused: $auth" }
                    val credentials = LocalGatewayCredentials(connection, auth.tokens.access)
                    try {
                        val current = client.current(credentials)
                        check(current is LocalSessionViewResult.Current && current.view.actorId == actor &&
                            current.view.deviceId == coordinates.deviceId && current.view.scope == coordinates.scope)
                        return use(credentials)
                    } finally {
                        try { check(client.logout(credentials) == LocalSignOutResult.Revoked) }
                        finally { auth.tokens.destroy() }
                    }
                }
                val sku = DomainId(exchange.awaitText("stock.sku"))
                val unit = DomainId(exchange.awaitText("stock.unit"))
                signedIn("owner.one", "synthetic-owner-pass-2026", coordinates.firstActorId) { credentials ->
                    val quantities = client.read(credentials, LocalStockCommands.quantities(sku))
                    val costs = client.read(credentials, LocalStockCommands.costs(sku))
                    check(quantities is ReadReply.Data && costs is ReadReply.Data)
                    val quantity = LocalStockQueries.decodeQuantities(quantities.payload).rows.single()
                    val cost = LocalStockQueries.decodeCosts(costs.payload).rows.single()
                    check(quantity.onHand == Quantity(unit, 0, 0) && quantity.reserved == Quantity(unit, 0, 0) &&
                        quantity.available == Quantity(unit, 0, 0) && cost.carryingValue == Money(Currency.EGP, 0))
                }
                for ((role, login) in listOf("client1" to "cashier.a", "client2" to "cashier.b")) {
                    val actor = DomainId(exchange.awaitText("$role.actor"))
                    val filesRoot = root.resolve(role).resolve("stock-executor-inputs")
                    check(Files.isDirectory(filesRoot, NOFOLLOW_LINKS))
                    val files = LanPersistentTestFiles(filesRoot)
                    signedIn(login, "synthetic-cashier-pass-2026", actor) { credentials ->
                        val cashId = DomainId(exchange.awaitText("$role.stock.session"))
                        val cashRead = client.read(credentials, LocalCashReadCodec.request(LocalCashQueryV1.Session(cashId)))
                        check(cashRead is ReadReply.Data)
                        val cash = (LocalCashReadCodec.decode(cashRead.payload) as? LocalCashReadResultV1.Session)?.session
                            ?: error("Existing own cash missing")
                        check(cash.custodianId == actor && cash.expectedCash == Money(Currency.EGP, 100_000) &&
                            cash.drawerId == DomainId(exchange.awaitText("$role.stock.drawer")) &&
                            cash.opening == Money(Currency.EGP, 100_000) && cash.reservedCashOut.minor == 0L &&
                            listOf(cash.payIns, cash.payOuts, cash.received, cash.change, cash.refunds,
                                cash.externalCollections, cash.externalRefunds, cash.safeDrops).all { it.minor == 0L } &&
                            cash.pendingAttemptIds.isEmpty() && cash.pendingTransferIds.isEmpty())
                        for (value in exchange.awaitText("$role.stock.recorded-operations").lines()) {
                            val operation = OperationId(value)
                            val request = files.locked { checkNotNull(files.read(files.name("$value.request"), 1_048_576)) }
                            val identity = DataInputStream(ByteArrayInputStream(request)).use { data ->
                                check(data.readUTF() == value)
                                val key = CommandKey(data.readUTF(), data.readInt())
                                val size = data.readInt(); check(size in 1..65_536)
                                val body = StoredBytes(ByteArray(size).also(data::readFully))
                                check(data.read() == -1)
                                OperationIdentity(coordinates.scope.branchId, operation, actor, 1,
                                    semanticDigest(coordinates.scope, actor, key, body).value)
                            }
                            val reply = client.lookup(credentials, identity)
                            check(reply is LookupReply.Found) { "Cold original lookup refused: ${reply::class.simpleName}" }
                            val result = reply.result
                            check(result.replayed && result.physicalStart == null)
                            val saved = files.locked {
                                files.read(files.name("$value.accepted"), 1_048_576)
                                    ?: checkNotNull(files.read(files.name("$value.accepted.recovered"), 1_048_576))
                            }
                            DataInputStream(ByteArrayInputStream(saved)).use { data ->
                                check(data.readUTF() == result.operationId.value && data.readUTF() == result.originalActorId.value &&
                                    data.readBoolean() == result.committed && data.readUTF() == result.code &&
                                    data.readLong() == result.authorityEpoch.value && data.readLong() == result.sequence.value &&
                                    data.readUTF() == result.recordedAt.canonicalString())
                                data.readBoolean() // Original delivery replay flag does not change the original result.
                                val size = data.readInt(); check(size in 1..65_536)
                                check(StoredBytes(ByteArray(size).also(data::readFully)) == result.result && data.read() == -1)
                            }
                        }
                    }
                }
            } finally { withContext(NonCancellable) { check(service.quiesceAndClose()) } }
            // Same independent SQL cardinality assertions as the completed live helper,
            // now after the actual cold owner certainly closed. No writer is fabricated.
            val databases = Files.walk(root.resolve("master")).use { paths ->
                paths.filter { it.fileName.toString() == "master.db" }.toList()
            }
            check(databases.size == 1)
            BundledSQLiteDriver().open(databases.single().toRealPath().toString(),
                SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { database ->
                for (role in listOf("client1", "client2")) {
                    val actor = exchange.awaitText("$role.actor")
                    for (operation in exchange.awaitText("$role.stock.recorded-operations").lines()) {
                        for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                            database.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use { row ->
                                row.bindText(1, coordinates.scope.storageKey()); row.bindText(2, operation)
                                check(row.step() && row.getLong(0) == 1L && !row.step())
                            }
                        }
                        database.prepare("SELECT actorId,originalActorId FROM audit_event WHERE scope=? AND operationId=?").use { row ->
                            row.bindText(1, coordinates.scope.storageKey()); row.bindText(2, operation)
                            check(row.step() && row.getText(0) == actor && row.getText(1) == actor && !row.step())
                        }
                    }
                }
            }
            exchange.putText("stock.cold.verified", "existing-only-native-operational-same-sdk-stock0-value0-own-cash100000-exact-originals-confirmed-close")
            println("Stock cold existing-only native/current-auth/original-result check passed; whole frozen/platform cases NOT RUN")
        }
    }
}
