package com.ukkera.pos.database.independent.catalogreleasesourcev1

import com.ukkera.pos.catalog.store.LocalActivateCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseQueryV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseServices
import com.ukkera.pos.catalog.store.LocalSignedCatalogReleaseV1
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSR-011 detached unavailable-source prepare/read and actual file-reopen slices.
 * Real Room/DAO/transactions are used, but no owner, native completion snapshot,
 * current account/grant, valid writer, authorization evaluation or command commit
 * is manufactured. No Apply, closed refusal, recovery, process restart or RPC is
 * executed. Whole CSR-011 and all production profiles remain UNBOUND / NOT_RUN.
 */
class CatalogReleaseSourceFrozenUnavailableRoomTest {
    @Test fun csr011_missingOriginalFrameIsAdmissionHeldWithoutAnyDurableWrite() = runTest {
        val fixture = CatalogReleaseSourceDetachedRoom.create()
        try {
            fixture.insertInertScopeMetadata()
            val signed = detachedReleaseEnvelope(CatalogReleaseSourceFrozenFixture.releases[0])
            assertAdmissionHeldWithoutWrites(fixture, signed)
        } finally { fixture.close() }
    }

    @Test fun csr011_realFileReopenDoesNotManufactureOriginalNativeObservation() = runTest {
        val fixture = CatalogReleaseSourceDetachedRoom.create()
        try {
            fixture.insertInertScopeMetadata()
            val signed = detachedReleaseEnvelope(CatalogReleaseSourceFrozenFixture.releases[0])
            val before = fixture.snapshot()
            assertAdmissionHeldWithoutWrites(fixture, signed)
            fixture.reopen()
            assertEquals(before, fixture.snapshot())
            assertAdmissionHeldWithoutWrites(fixture, signed)
            assertEquals(before, fixture.snapshot())
        } finally { fixture.close() }
    }

    @Test fun csr011_unavailableReadCannotPrepareKnownEmptyOrProtectedOriginalAfterReopen() = runTest {
        val fixture = CatalogReleaseSourceDetachedRoom.create()
        try {
            fixture.insertInertScopeMetadata()
            val before = fixture.snapshot()
            assertReadUnavailableWithoutWrites(fixture)
            fixture.reopen()
            assertEquals(before, fixture.snapshot())
            assertReadUnavailableWithoutWrites(fixture)
            assertEquals(before, fixture.snapshot())
        } finally { fixture.close() }
    }

    private suspend fun assertAdmissionHeldWithoutWrites(fixture: CatalogReleaseSourceDetachedRoom,
        signed: LocalSignedCatalogReleaseV1) {
        val services = LocalCatalogReleaseServices(null)
        val handler = services.commands.single()
        val request = LocalActivateCatalogReleaseV1(signed, null)
        val normalized = handler.normalize(LocalCatalogReleaseCodecV1.encode(request))
        assertEquals(request, LocalCatalogReleaseCodecV1.decode(normalized))
        val actor = CatalogReleaseSourceFrozenFixture.metadataId(802)
        val operationId = OperationId(CatalogReleaseSourceFrozenFixture.metadataId(803).value)
        val context = CommandContext(fixture.scope, actor, operationId, handler.key,
            semanticDigest(fixture.scope, actor, handler.key, normalized))
        val before = fixture.snapshot()

        fixture.withView { connection, actual ->
            assertNull(actual.originalNativeBusinessPublications())
            val changes = connection.catalogReleaseSourceTotalChanges()
            val observed = CatalogReleaseSourceObservedView(actual)
            val prepared = handler.prepare(normalized, observed, context)
            assertTrue(observed.reads > 0)
            assertEquals(observed.reads, observed.nullObservations)
            assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, prepared.admissionRestriction)
            assertEquals(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), prepared.requirement.permission)
            assertEquals(fixture.scope, prepared.requirement.scope)
            val binding = assertNotNull(prepared.requirement.binding)
            assertEquals(signed.definition.catalogId, binding.documentId)
            assertEquals(Revision(0), binding.revision)
            assertEquals(operationId, binding.operationId)
            assertEquals(context.semanticDigest, binding.payloadDigest)
            assertFalse(prepared.allowsActionApproval)
            assertEquals(changes, connection.catalogReleaseSourceTotalChanges())
            assertNull(actual.originalNativeBusinessPublications())
        }
        assertEquals(before, fixture.snapshot())
    }

    private suspend fun assertReadUnavailableWithoutWrites(fixture: CatalogReleaseSourceDetachedRoom) {
        val handler = LocalCatalogReleaseServices(null).reads.single()
        val release = CatalogReleaseSourceFrozenFixture.releases[0]
        val actor = CatalogReleaseSourceFrozenFixture.metadataId(802)
        val before = fixture.snapshot()
        fixture.withView { connection, actual ->
            assertNull(actual.originalNativeBusinessPublications())
            val changes = connection.catalogReleaseSourceTotalChanges()
            for (query in listOf(LocalCatalogReleaseQueryV1(release.catalogId),
                LocalCatalogReleaseQueryV1(release.catalogId, release.publicationId))) {
                val normalized = handler.normalize(LocalCatalogReleaseCodecV1.query(query).payload)
                assertEquals(query, LocalCatalogReleaseCodecV1.decodeQuery(normalized))
                val context = ReadContext(fixture.scope, actor, handler.key,
                    queryDigest(fixture.scope, actor, handler.key, normalized))
                // No PreparedRead is returned, so neither a known-empty projection
                // nor a protected original can be created from this absent source.
                assertFailsWith<IllegalStateException> { handler.prepare(normalized, actual, context) }
            }
            assertEquals(changes, connection.catalogReleaseSourceTotalChanges())
            assertNull(actual.originalNativeBusinessPublications())
        }
        assertEquals(before, fixture.snapshot())
    }
}
