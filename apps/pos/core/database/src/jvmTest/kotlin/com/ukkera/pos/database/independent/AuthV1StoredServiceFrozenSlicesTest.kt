package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Executes the existing frozen host-slice assertions unchanged through the actual grouped
 * Room/authentication service over its explicit controlled schema-7 fixture. The original
 * sealed executor and schema-compatibility acceptance are not invoked or qualified here.
 * Provisioning, JCA keys, peers and clocks remain synthetic; no business/native authority. */
class AuthV1StoredServiceFrozenSlicesTest {
    @Test fun unchangedFrozenHostSlicesThroughControlledStoredService() = runBlocking {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first {
            Files.isRegularFile(it.resolve("qa/pos/freezes/auth-v1/cases.json"))
        }
        val cases = Files.readAllBytes(root.resolve("qa/pos/freezes/auth-v1/cases.json"))
        check(authV1Sha256(cases) == "e6fa22b836f7fd2c2face14d83544edd8554007faee6214c0ec36492a3c96100")
        val wiring = Files.readAllBytes(root.resolve("qa/pos/wiring/auth-v1/wiring-v3.json"))
        check(authV1Sha256(wiring) == "9c6508917316935a42882019396c24a005434abd7b7f711e5ce4a624815236be")
        val run = Files.createDirectory(Files.createDirectories(Path.of("build", "auth-v1-host-slices").toAbsolutePath().normalize())
            .resolve(UUID.randomUUID().toString()),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val invocation = UUID.randomUUID().toString().replace("-", "")
        try {
            val directory = Files.createDirectory(run.resolve("private-fixture"))
            AuthV1Journal(run, invocation, Json.parseToJsonElement(wiring.decodeToString()).jsonObject).use { journal ->
                AuthV1Main.runFrozenHostSlices(directory,
                    Json.parseToJsonElement(cases.decodeToString()).jsonObject, journal)
                journal.requireAllSlices()
            }
        } finally {
            // Private observations and diagnostics survive failures; no credentials are printed.
            println("Auth-v1 supporting host-slice observations: ${run.toAbsolutePath()}")
        }
    }
}
