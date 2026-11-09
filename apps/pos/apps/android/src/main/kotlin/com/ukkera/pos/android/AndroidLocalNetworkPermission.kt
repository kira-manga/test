package com.ukkera.pos.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Platform socket access only; never employee, device or branch authority. */
internal object AndroidLocalNetworkPermission {
    const val NAME: String = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED

    fun requireGranted(context: Context) {
        if (!isGranted(context)) throw SecurityException("Local network permission is required")
    }
}
