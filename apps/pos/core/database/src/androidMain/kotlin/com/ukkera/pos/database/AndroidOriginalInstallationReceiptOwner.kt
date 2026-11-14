package com.ukkera.pos.database

import com.ukkera.pos.security.SelfOwnedLocalSecurityContext

/**
 * Actual private-host P3 construction edge. No application/path/SQL/key/provider/scanner/
 * verifier factory or status flag can be supplied. The opaque context must originate in P2.
 * The shared JVM/Android owner performs the claim through Android's genuine installation owner.
 */
public object AndroidOriginalInstallationReceiptOwner {
    public suspend fun attachOriginal(
        actualContext: SelfOwnedLocalSecurityContext,
    ): LocalMasterInstallationReceiptOpenResult =
        OriginalInstallationReceiptOwnerCore.attachOriginal(AndroidSelfOwnedMasterNativeOwner, actualContext)
}
