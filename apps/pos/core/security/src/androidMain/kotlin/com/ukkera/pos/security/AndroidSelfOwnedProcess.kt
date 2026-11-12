package com.ukkera.pos.security

import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.util.Log

/** Android process facts for the shared owner work: same behavior as the original inline calls. */
internal object AndroidSelfOwnedProcess : SelfOwnedProcessPlatform {
    override fun pid(): Int = Process.myPid()
    override fun uid(): Int = Process.myUid()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    override fun terminate(pid: Int) {
        try { Process.killProcess(pid) } catch (_: Throwable) { /* lease remains held */ }
        try { Process.sendSignal(pid, 9) } catch (_: Throwable) { /* no replacement is admitted */ }
    }
    override fun info(message: String) { Log.i("UkkeraNativeOwner", message) }
    override fun warn(message: String) { Log.w("UkkeraNativeOwner", message) }
    override fun errno(failure: Throwable): Int? = (failure as? ErrnoException)?.errno
}
