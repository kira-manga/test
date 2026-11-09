package com.ukkera.pos.android.independent.retailhandoffhistoryv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.sales.store.LocalOrderStockProfileV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes only the unchanged v1 semantic BASELINE through actual native services.
 * It does not assert any whole RHH case, process restart, admitted LAN RPC or installed UI.
 * No Activity/service is launched and no production verifier, database or peer is replaced.
 */
@RunWith(AndroidJUnit4::class)
class NativeRetailBaselineTest {
    @Test
    fun nativeBaselineMatchesFrozenRetailFixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticRetail") == "true") {
            "Explicit syntheticRetail=true is required; run only on a disposable testing installation"
        }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val frozen = instrumentation.context.assets.open("fixtures-v1.json").use {
            JSONObject(it.bufferedReader().readText()).getJSONObject("baseline_cut")
        }
        // This is an explicit permitted protection profile, never hardware fallback.
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly"
        })
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val plan = NativeRetailFixturePlan(DomainId(UUID.randomUUID().toString()),
            UtcInstant.parseCanonical(now), BusinessDate.parseCanonical(now.substring(0, 10)), protection)
        val intentFile = File(application.noBackupFilesDir, "retail-baseline-test-intent.json")
        // A second invocation must not replace unknown native setup or pending requests.
        // Resetting a confirmed disposable installation is an explicit operator action.
        check(intentFile.createNewFile()) { "Retained baseline intent exists; inspect it before resetting synthetic data" }
        val intent = JSONObject().put("namespace", plan.namespace.value).put("protection", protection.name)
            .put("status", "PREPARATION_PENDING").put("requests", JSONArray())
        val durableIntent = AtomicFile(intentFile)
        fun retainIntent() {
            val stream = durableIntent.startWrite()
            try {
                stream.write(intent.toString().encodeToByteArray())
                durableIntent.finishWrite(stream)
            } catch (failure: Throwable) {
                durableIntent.failWrite(stream)
                throw failure
            }
        }
        retainIntent() // No native setup or business dispatch occurs before retention.
        val preparation = NativeRetailFixturePreparation(application, plan)
        val random = SecureRandom()
        fun secret(): CharArray {
            val bytes = ByteArray(24).also(random::nextBytes)
            return try {
                val hex = "0123456789abcdef"
                CharArray(bytes.size * 2) { index ->
                    val value = bytes[index / 2].toInt() and 0xff
                    hex[if (index % 2 == 0) value ushr 4 else value and 15]
                }
            } finally { bytes.fill(0) }
        }
        val installer = secret()
        val head = secret()
        val passwordA = secret()
        val passwordB = secret()
        var primaryFailure: Throwable? = null
        try {
            val actual = preparation.prepareAllocation(installer, head)
            // Canonical native actor/scope aliases are observed, not presumed UUID constants.
            intent.put("actorA", actual.coordinates.firstActorId.value).put("actorB", plan.actorB.value)
                .put("branchId", actual.coordinates.scope.branchId.value)
            retainIntent()
            var installed = preparation.installOriginal(head, passwordA)
            if (installed == LocalMasterInstallationOutcome.AcknowledgementPending) {
                installed = preparation.resumeOriginalAcknowledgement()
            }
            assertTrue("Actual original installation must finish", installed is LocalMasterInstallationOutcome.Installed)
            preparation.openBaselineSources(head, passwordB)
            var signedInActor: DomainId? = null
            while (preparation.nextCommandKind != null) {
                val requiredActor = checkNotNull(preparation.requiredActorForNextCommand)
                if (signedInActor != requiredActor) {
                    preparation.signOutCurrent()
                    if (requiredActor == actual.coordinates.firstActorId) preparation.signInA(passwordA)
                    else preparation.signInB(passwordB)
                    signedInActor = requiredActor
                }
                val attempt = preparation.prepareNextCommand()
                val retained = JSONObject().put("step", attempt.step.name)
                    .put("operationId", attempt.request.operationId.value).put("actorId", attempt.identity.originalActorId.value)
                    .put("semanticDigest", attempt.identity.semanticSha256).put("status", "DISPATCH_PENDING")
                intent.getJSONArray("requests").put(retained)
                retainIntent() // Original identity remains retained if the call never returns.
                val reply = preparation.dispatchPrepared(attempt)
                retained.put("status", "RECORDED").put("committed", reply.committed).put("replayed", reply.replayed)
                retainIntent()
            }
            val observed = preparation.verifyBCashAndClose(passwordB)
            assertEquals(NativeRetailFixturePhase.BASELINE_PREPARED_CLOSED, observed.phase)
            assertNull(observed.hold)
            val order = checkNotNull(observed.orderR)
            assertEquals(LocalOrderStockProfileV2.NORMALIZED, order.stockProfile)
            assertEquals(actual.coordinates.firstActorId, order.order.ownerId)
            assertEquals(frozen.getLong("quoted_payable_minor"), order.order.payable.minor)
            assertEquals(frozen.getString("quantity_units").toLong(), order.order.lines.single().quantity.scaled)
            assertNull(order.order.collectionId)
            assertNull(order.order.saleId)
            assertNull(order.order.handoffId)
            val before = checkNotNull(observed.stockBeforeOrder)
            val after = checkNotNull(observed.stockAfterOrder)
            assertEquals(before.quantities.flatMap { it.rows }, after.quantities.flatMap { it.rows })
            assertEquals(before.costs.flatMap { it.rows }, after.costs.flatMap { it.rows })
            val quantity = after.quantities.flatMap { it.rows }.single()
            assertEquals(frozen.getString("sellable_stock_units").toLong(), quantity.available.scaled)
            assertEquals(frozen.getString("reserved_quantity").toLong(), quantity.reserved.scaled)
            val cost = after.costs.flatMap { it.rows }.single()
            fun egpMinor(key: String): Long = frozen.getString(key).toBigDecimal().movePointRight(2).longValueExact()
            assertEquals(egpMinor("stock_value_egp"), cost.carryingValue.minor)
            val drawerA = checkNotNull(observed.drawerSessionA)
            val drawerB = checkNotNull(observed.drawerSessionB)
            assertEquals(actual.coordinates.firstActorId, drawerA.custodianId)
            assertEquals(plan.actorB, drawerB.custodianId)
            assertEquals(egpMinor("drawer_A_balance_egp"), drawerA.expectedCash.minor)
            assertEquals(egpMinor("drawer_B_balance_egp"), drawerB.expectedCash.minor)
            assertEquals(egpMinor("total_actual_drawer_cash_egp"), drawerA.expectedCash.minor + drawerB.expectedCash.minor)
            assertTrue(observed.commands.all { it.reply is MutationReply.Recorded &&
                (it.reply as MutationReply.Recorded).committed && !(it.reply as MutationReply.Recorded).replayed })
            assertFalse(observed.commands.isEmpty())
            intent.put("status", "BASELINE_VERIFIED_WHOLE_CASES_NOT_RUN")
            retainIntent()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            installer.fill('\u0000'); head.fill('\u0000'); passwordA.fill('\u0000'); passwordB.fill('\u0000')
            val closed = preparation.close()
            // On failure an acquisition may not have returned its owner yet; the cleanup
            // helper's Boolean alone cannot certify disposal of an undelivered handle.
            intent.put("cleanupReportedClosed", closed)
                .put("nativeCloseConfirmed", if (primaryFailure == null) closed else JSONObject.NULL)
                .put("lastStep", preparation.checkpoint.step)
            if (primaryFailure != null || !closed) intent.put("status", "HELD")
            try { retainIntent() } catch (failure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
            }
            if (!closed) {
                val failure = AssertionError("Actual native close remains unconfirmed")
                if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
            }
        }
    }
}
