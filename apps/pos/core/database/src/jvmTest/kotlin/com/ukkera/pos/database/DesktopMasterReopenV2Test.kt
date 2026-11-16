package com.ukkera.pos.database

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.inventory.store.LocalStockRelationalCommandsV2
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Self-authored, NOT independent. Linux POSIX/software-key seams, actual V2 founding,
 * installation, closed-scan reopen, authentication and normalized stock command. This checks
 * the native-owner reopen correction, NOT the blocked deposit workflow or macOS/Windows custody.
 * The publication verifier always rejects; no missing desktop publication authority is supplied. */
class DesktopMasterReopenV2Test {
    @Test fun installedCompanyReopensAndAuthenticatesBeforeExecutingNormalizedStock(): Unit = runBlocking {
        val fixture = DepositNativeFixture()
        try {
            fixture.install(CompanyPublicationSignatureVerifier { _, _, _, _ -> false })
            val request = LocalStockRelationalCommandsV2.initializeFresh(OperationId(testId(30001).value),
                testId(30002), "synthetic.opening")
            val first = assertIs<MutationReply.Recorded>(fixture.execute(request))
            assertTrue(first.committed, first.code)
            val replay = assertIs<MutationReply.Recorded>(fixture.execute(request))
            assertTrue(replay.replayed)
            assertEquals(first.result, replay.result)
        } finally { fixture.close() }
    }
}
