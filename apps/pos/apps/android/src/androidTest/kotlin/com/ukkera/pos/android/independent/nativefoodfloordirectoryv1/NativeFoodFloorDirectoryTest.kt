package com.ukkera.pos.android.independent.nativefoodfloordirectoryv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.Revision
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Select one frozen supplemental case on a fresh isolated localTesting installation. NFFD-001
 * setup is repeated for each mutation case; protected stores are never cloned/reset here.
 * Only named original-native setup/result/readback/ordinary-close assertions are bound.
 * Raw history/audit/outbox, business sentinels, RPC, faults, UI and whole cases remain unqualified.
 */
@RunWith(AndroidJUnit4::class)
class NativeFoodFloorDirectoryTest {
    @Test
    fun nativeFrozenDirectoryAssertions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeFoodFloorDirectory") == "true") {
            "Explicit syntheticNativeFoodFloorDirectory=true is required for an isolated synthetic testing installation"
        }
        val frozen = NativeFoodFloorFrozen.read(instrumentation.context.assets)
        val selected = checkNotNull(arguments.getString("nativeFoodFloorCase")) {
            "Select one nativeFoodFloorCase=NFFD-001..NFFD-005 explicitly"
        }
        check(frozen.document.getJSONObject("allCaseStatuses").has(selected)) { "Unknown frozen native floor case" }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val intentFile = File(application.noBackupFilesDir, "native-food-floor-directory-intent-v1.json")
        check(listOf(intentFile, File("${intentFile.path}.bak"), File("${intentFile.path}.new")).none { it.exists() }) {
            "An original floor intent exists; this fixture cannot reset or replace an earlier installation"
        }
        check(intentFile.createNewFile()) { "Original floor intent is already retained" }
        val record = JSONObject().put("status", "ORIGINAL_INTENT_RETENTION_PENDING")
            .put("fixtureVersion", frozen.version).put("selectedNativeCase", selected).put("keyProtection", protection.name)
            .put("boundedVerification", "NOT_VERIFIED").put("nativeSupplementWholeCases", "NOT_RUN")
            .put("originalWholeFFDCases", "NOT_RUN").put("unexecutedBindings", JSONArray(listOf(
                "raw history/audit/outbox", "canonical wire goldens/policy compatibility", "business sentinels",
                "admitted RPC/faults/races/restore", "installed UI/other platforms/owner acceptance")))
            .put("commands", JSONArray()).put("results", JSONArray()).put("reads", JSONArray())
            .put("associations", JSONArray()).put("nativeMaterials", JSONArray()).put("observedNamedParts", JSONArray())
        val durableIntent = AtomicFile(intentFile)
        fun retain() {
            val stream = durableIntent.startWrite()
            try {
                stream.write(record.toString().encodeToByteArray())
                durableIntent.finishWrite(stream)
            } catch (failure: Throwable) {
                try { durableIntent.failWrite(stream) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        val owner = NativeFoodFloorOwner(application, NativeFoodFloorPlan(protection, frozen.targets), record, ::retain)
        val secrets = mutableListOf<CharArray>()
        val random = SecureRandom()
        fun secret(): CharArray {
            val bytes = ByteArray(24)
            val characters = try {
                random.nextBytes(bytes)
                val hex = "0123456789abcdef"
                CharArray(bytes.size * 2) { index ->
                    val value = bytes[index / 2].toInt() and 0xff
                    hex[if (index % 2 == 0) value ushr 4 else value and 15]
                }
            } finally { bytes.fill(0) }
            secrets += characters
            return characters
        }
        var primaryFailure: Throwable? = null
        var assertionsFinished = false
        var finalNativeCloseConfirmed = false
        fun remember(next: Throwable) {
            val before = primaryFailure
            if (before == null) primaryFailure = next else if (before !== next) before.addSuppressed(next)
        }
        try {
            retain() // Requested facts are retained before original setup or business dispatch.
            val installer = secret()
            val head = secret()
            val employeePassword = secret()
            owner.prepareAndInstall(installer, head, employeePassword)
            owner.openOperationalAndSignIn(employeePassword)
            nffd001OriginalSetupAndBase(owner, frozen)
            record.getJSONArray("observedNamedParts").put("NFFD-001 original setup/authentication/28 producer results and readback")
            retain()
            when (selected) {
                "NFFD-001" -> Unit
                "NFFD-002" -> nffd002ElevenProtectedReads(owner, frozen)
                "NFFD-003" -> nffd003SelectedRevisionCasAndStale(owner, frozen)
                "NFFD-004" -> nffd004FixedParentWithActiveFloors(owner, frozen)
                "NFFD-005" -> nffd005OriginalAAfterConfirmedCloseReopen(owner, frozen, employeePassword)
                else -> error("Frozen case dispatch is not bound")
            }
            record.getJSONArray("observedNamedParts").put("$selected bounded named assertions")
            retain()
            check(owner.close()) { "Final actual native owner close remains unconfirmed" }
            finalNativeCloseConfirmed = true
            assertionsFinished = true
        } catch (failure: Throwable) {
            owner.hold(); remember(failure)
        } finally {
            secrets.forEach { it.fill('\u0000') }
            var cleanupReportedClosed: Boolean? = null
            try { cleanupReportedClosed = owner.close() } catch (failure: Throwable) { owner.hold(); remember(failure) }
            owner.takeCleanupFailures().forEach(::remember)
            if (cleanupReportedClosed != true) {
                owner.hold(); remember(AssertionError("Actual cleanup or ownership settlement is unconfirmed"))
            }
            val observed = assertionsFinished && finalNativeCloseConfirmed && cleanupReportedClosed == true &&
                primaryFailure == null && !owner.held
            record.put("cleanupReportedClosed", cleanupReportedClosed ?: JSONObject.NULL)
                .put("finalNativeCloseConfirmed", if (observed) true else JSONObject.NULL)
                .put("lastOwnedStep", owner.step).put("boundedVerification", if (observed) "NAMED_ASSERTIONS_OBSERVED" else "NOT_VERIFIED")
                .put("status", if (observed) "NAMED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASES_NOT_RUN" else "HELD")
                .put("nativeSupplementWholeCases", "NOT_RUN").put("originalWholeFFDCases", "NOT_RUN")
                .put("failureClass", primaryFailure?.javaClass?.name ?: JSONObject.NULL)
                .put("suppressedFailureClasses", JSONArray(primaryFailure?.suppressed?.map { it.javaClass.name } ?: emptyList<String>()))
            try { retain() } catch (failure: Throwable) {
                owner.hold(); record.put("status", "HELD").put("boundedVerification", "NOT_VERIFIED")
                remember(failure) // Unknown final retention is not retried.
            }
        }
        primaryFailure?.let { throw it }
    }

    private suspend fun nffd001OriginalSetupAndBase(owner: NativeFoodFloorOwner, frozen: NativeFoodFloorFrozen) {
        val empty = frozen.document.getJSONObject("initialEmptyQuery")
        val expectedEmpty = NativeFoodFloorFrozen.page(empty.getJSONObject("expected"))
        check(expectedEmpty.query == NativeFoodFloorFrozen.query(empty.getJSONObject("query")))
        requirePage(owner, expectedEmpty, "NFFD001_EMPTY")
        for ((index, command) in frozen.seedCommands.withIndex()) {
            owner.execute(command, "NFFD001_SEED_${index + 1}")
            val revision = checkNotNull(command.expected.revision)
            when (val request = command.semantic) {
                is LocalFoodFloorConfigurationRequestV1.Floor -> {
                    val page = owner.read(LocalFoodFloorQueryV1.Floors(null, 20), "NFFD001_SEED_${index + 1}_READBACK")
                    check(page.configured && page.tables.isEmpty() && page.floor == null && page.nextAfter == null)
                    check(page.floors.single { it.definition.id == request.floor.id } == LocalFoodFloorRowV1(request.floor, revision))
                }
                is LocalFoodFloorConfigurationRequestV1.Table -> {
                    val expected = LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Table(request.table.id), true,
                        tables = listOf(LocalFoodTableRowV1(request.table, revision)),
                        floor = frozen.floors.values.single { it.definition.id == request.table.floorId })
                    requirePage(owner, expected, "NFFD001_SEED_${index + 1}_READBACK")
                }
            }
        }
        val actual = snapshot(owner, frozen, frozen.baseState, "NFFD001_FINAL_BASE")
        requireCounts(owner, actual, frozen.baseCounts.getInt("retainedFloors"), frozen.baseCounts.getInt("retainedTables"),
            frozen.baseCounts.getInt("actualSuccessfulProducerCommands"))
    }

    private suspend fun nffd002ElevenProtectedReads(owner: NativeFoodFloorOwner, frozen: NativeFoodFloorFrozen) {
        val before = snapshot(owner, frozen, frozen.baseState, "NFFD002_BEFORE")
        val acceptedBefore = owner.acceptedProducerCount
        for (read in frozen.reads) {
            val expected = NativeFoodFloorFrozen.page(read.getJSONObject("expected"))
            check(expected.query == NativeFoodFloorFrozen.query(read.getJSONObject("query")))
            requirePage(owner, expected, read.getString("id"))
        }
        val after = snapshot(owner, frozen, frozen.baseState, "NFFD002_AFTER")
        check(after == before && owner.acceptedProducerCount == acceptedBefore)
        requireCounts(owner, after, frozen.baseCounts.getInt("retainedFloors"), frozen.baseCounts.getInt("retainedTables"),
            frozen.baseCounts.getInt("actualSuccessfulProducerCommands"))
    }

    private suspend fun nffd003SelectedRevisionCasAndStale(owner: NativeFoodFloorOwner, frozen: NativeFoodFloorFrozen) {
        val oracle = frozen.document.getJSONObject("cas")
        var expected = frozen.baseState
        var previous = snapshot(owner, frozen, expected, "NFFD003_CAPTURE")
        val capture = oracle.getJSONObject("capture")
        check(previous.floors.single { it.definition.id.value == capture.getJSONObject("floor").getString("id") }.storageRevision ==
            Revision(capture.getJSONObject("floor").getString("storageRevision").toLong()))
        check(previous.tables.single { it.definition.id.value == capture.getJSONObject("table").getString("id") }.storageRevision ==
            Revision(capture.getJSONObject("table").getString("storageRevision").toLong()))
        for ((index, command) in frozen.commands("cas").withIndex()) {
            owner.execute(command, "NFFD003_COMMAND_${index + 1}")
            // Expected definitions are transformed only by frozen successful request/result
            // pairs. A returned value is never used to calculate the expected next state.
            expected = expected.expectedAfter(command)
            val actual = snapshot(owner, frozen, expected, "NFFD003_AFTER_${index + 1}")
            requireRevisionVector(frozen, actual, oracle.getJSONArray("expectedRevisionVectorsAfterEachCommand").getJSONObject(index))
            if (command.expected.code == "food.floor.stale") check(actual == previous)
            requireCounts(owner, actual, oracle.getInt("retainedFloors"), oracle.getInt("retainedTables"),
                oracle.getJSONArray("expectedLogicalSuccessfulCountsAfterEachCommand").getInt(index))
            previous = actual
        }
        val final = oracle.getJSONArray("expectedFinalChangedDefinitions").objects()
        check(previous.floors.single { it.definition.id.value == final[0].getJSONObject("definition").getString("id") } ==
            NativeFoodFloorFrozen.floorRow(final[0]))
        check(previous.tables.single { it.definition.id.value == final[1].getJSONObject("definition").getString("id") } ==
            NativeFoodFloorFrozen.tableRow(final[1]))
    }

    private suspend fun nffd004FixedParentWithActiveFloors(owner: NativeFoodFloorOwner, frozen: NativeFoodFloorFrozen) {
        val oracle = frozen.document.getJSONObject("fixedParent")
        val commands = frozen.commands("fixedParent")
        owner.execute(commands[0], "NFFD004_ENABLE_FLOOR_B")
        val expected = frozen.baseState.expectedAfter(commands[0])
        val before = snapshot(owner, frozen, expected, "NFFD004_BOTH_PARENTS_ACTIVE")
        check(before.floors.all { it.definition.active })
        check(before.floors.single { it.definition.id == frozen.floorId("F-B") } ==
            NativeFoodFloorFrozen.floorRow(oracle.getJSONObject("expectedEnabledFloorB")))
        owner.execute(commands[1], "NFFD004_FIXED_PARENT_REFUSAL")
        val after = snapshot(owner, frozen, expected, "NFFD004_AFTER_REFUSAL")
        check(after == before)
        requirePage(owner, NativeFoodFloorFrozen.page(oracle.getJSONObject("expectedRejectedTableDetail")), "NFFD004_ORIGINAL_TABLE_DETAIL")
        requireRevisionVector(frozen, after, oracle.getJSONObject("expectedFinalRevisions"))
        requireCounts(owner, after, oracle.getInt("retainedFloors"), oracle.getInt("retainedTables"),
            oracle.getInt("logicalSuccessfulVersionsResults"))
    }

    private suspend fun nffd005OriginalAAfterConfirmedCloseReopen(owner: NativeFoodFloorOwner,
        frozen: NativeFoodFloorFrozen, password: CharArray) {
        val oracle = frozen.document.getJSONObject("recovery")
        val commands = frozen.commands("recovery")
        val originalA = owner.execute(commands[0], "NFFD005_ORIGINAL_A")
        val afterA = frozen.baseState.expectedAfter(commands[0])
        snapshot(owner, frozen, afterA, "NFFD005_AFTER_A")
        owner.execute(commands[1], "NFFD005_ORIGINAL_B")
        val expected = afterA.expectedAfter(commands[1])
        val beforeClose = snapshot(owner, frozen, expected, "NFFD005_CURRENT_B_BEFORE_CLOSE")
        check(beforeClose.floors.single { it.definition.id == frozen.floorId("F-A") } ==
            NativeFoodFloorFrozen.floorRow(oracle.getJSONObject("expectedCurrentFloor")))
        check(owner.close()) { "Actual native owner close before original A recovery is unconfirmed" }
        owner.openOperationalAndSignIn(password) // Existing original only; no allocation/enrollment/seed replay.
        val lookup = oracle.getJSONObject("postReopenOriginalResultLookup")
        check(lookup.getString("operationId") == originalA.identity.operationId.value)
        owner.lookup(originalA, NativeFoodFloorFrozen.result(lookup.getJSONObject("expectedResult")), "NFFD005_POST_REOPEN_A_LOOKUP")
        owner.retry(originalA, NativeFoodFloorFrozen.command(oracle.getJSONObject("postReopenIdenticalRetry")))
        val after = snapshot(owner, frozen, expected, "NFFD005_POST_LOOKUP_RETRY_CURRENT_B")
        check(after == beforeClose)
        requireRevisionVector(frozen, after, oracle.getJSONObject("expectedFinalRevisions"))
        requireCounts(owner, after, oracle.getInt("retainedFloors"), oracle.getInt("retainedTables"),
            oracle.getInt("logicalSuccessfulVersionsResults"))
    }

    private suspend fun requirePage(owner: NativeFoodFloorOwner, expected: LocalFoodFloorPageV1, label: String): LocalFoodFloorPageV1 {
        val actual = owner.read(expected.query, label)
        check(actual == expected) { "Protected directory response differs from the frozen semantic projection: $label" }
        return actual
    }

    private suspend fun snapshot(owner: NativeFoodFloorOwner, frozen: NativeFoodFloorFrozen,
        expected: NativeFoodFloorState, label: String): NativeFoodFloorState {
        val floors = requirePage(owner, expected.floorsPage(), "${label}_FLOORS")
        val a = requirePage(owner, expected.tablesPage(frozen.floorId("F-A")), "${label}_FLOOR_A_TABLES")
        val b = requirePage(owner, expected.tablesPage(frozen.floorId("F-B")), "${label}_FLOOR_B_TABLES")
        requirePage(owner, expected.tablePage(frozen.tableId("T-010")), "${label}_T010_AND_CURRENT_PARENT")
        return NativeFoodFloorState(floors.floors, (a.tables + b.tables).sortedBy { it.definition.id.value })
    }

    private fun requireCounts(owner: NativeFoodFloorOwner, actual: NativeFoodFloorState,
        floors: Int, tables: Int, successfulProducerCommands: Int) {
        check(actual.floors.size == floors && actual.tables.size == tables &&
            owner.acceptedProducerCount == successfulProducerCommands)
        // This observes accepted producer replies and current revisions, not raw version rows,
        // native prior-source proof, atomic outbox counts or business sentinel deltas.
    }

    private fun requireRevisionVector(frozen: NativeFoodFloorFrozen, actual: NativeFoodFloorState, expected: JSONObject) {
        for ((alias, row) in frozen.floors) check(actual.floors.single { it.definition.id == row.definition.id }.storageRevision ==
            Revision(expected.getString(alias).toLong()))
        for ((alias, row) in frozen.tables) check(actual.tables.single { it.definition.id == row.definition.id }.storageRevision ==
            Revision(expected.getString(alias).toLong()))
    }
}
