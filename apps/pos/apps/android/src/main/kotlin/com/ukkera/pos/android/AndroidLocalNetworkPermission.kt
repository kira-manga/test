package com.ukkera.pos.android

import android.content.Context
import com.ukkera.pos.rpc.AndroidLocalNetworkAccess

/** Platform socket access only; never employee, device or branch authority. */
internal object AndroidLocalNetworkPermission {
    const val NAME: String = AndroidLocalNetworkAccess.PERMISSION

    fun isGranted(context: Context): Boolean = AndroidLocalNetworkAccess.isGranted(context)

    fun requireGranted(context: Context): Unit = AndroidLocalNetworkAccess.requireGranted(context)
}
