package com.ukkera.pos.android.independent.androidapi24v1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId

/** Literal transcriptions of unchanged android-api24-compatibility/fixtures-v1.json. */
internal object AndroidApi24V1NativeInputs {
    val scope = BranchScope(DomainId("a2400000-0000-4000-8000-000000000001"),
        DomainId("a2400000-0000-4000-8000-000000000002"), DomainId("a2400000-0000-4000-8000-000000000003"))
    const val challenge1 = "synthetic-android24-sign-challenge-1"
    const val challenge2 = "synthetic-android24-sign-challenge-2"
    const val originalContent = "synthetic-protected-io-original-content-v1"
    const val symlinkTargetContent = "synthetic-protected-io-do-not-change-target-v1"
    val names = setOf("synthetic-io-01", "synthetic-io-02", "synthetic-io-03")

    fun application(): Application {
        check(InstrumentationRegistry.getArguments().getString("syntheticAndroidApi24") == "true") {
            "Require syntheticAndroidApi24=true on an isolated disposable testing installation"
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(app.packageName == "com.ukkera.pos.local.testing")
        check(Build.VERSION.SDK_INT >= 24 && app.applicationContext === app && !app.isDeviceProtectedStorage)
        check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        check(app.applicationInfo.uid == Process.myUid())
        check(checkNotNull(app.getSystemService(UserManager::class.java)).isUserUnlocked)
        Log.i("AndroidApi24V1", "PARTIAL_NATIVE_SLICES api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}; " +
            "boot/install/launch/business/hardware qualification remain separate")
        return app
    }
}
