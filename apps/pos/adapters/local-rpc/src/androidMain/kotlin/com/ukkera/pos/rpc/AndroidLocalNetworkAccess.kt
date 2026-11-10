package com.ukkera.pos.rpc

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** OS socket access only; this grants no device, employee, or local Master authority. */
public object AndroidLocalNetworkAccess {
    public const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    public fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    public fun requireGranted(context: Context) {
        if (!isGranted(context)) throw SecurityException("Local network permission is required")
    }
}
