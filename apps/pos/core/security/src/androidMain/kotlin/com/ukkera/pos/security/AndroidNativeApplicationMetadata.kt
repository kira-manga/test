package com.ukkera.pos.security

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Installed-package observations only; historical signing certificates do not grant trust. */
public object AndroidNativeApplicationMetadata {
    @Suppress("DEPRECATION")
    public fun installedPackageInfo(manager: PackageManager, name: String): PackageInfo =
        manager.getPackageInfo(name,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    public fun singleInstalledSignerSha256(installed: PackageInfo): String? {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val signing = installed.signingInfo ?: return null
            if (signing.hasMultipleSigners()) return null
            signing.apkContentsSigners
        } else installed.signatures
        if (signers == null || signers.size != 1) return null
        val certificate = signers.single().toByteArray()
        return try { AndroidP256Primitives.hashHex(certificate) } finally { certificate.fill(0) }
    }

    @Suppress("DEPRECATION")
    public fun versionCode(installed: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong() and 0xffff_ffffL
}
