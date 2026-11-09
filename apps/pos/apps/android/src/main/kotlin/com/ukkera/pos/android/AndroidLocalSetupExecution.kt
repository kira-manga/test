package com.ukkera.pos.android

import android.os.Process
import android.os.SystemClock
import com.ukkera.pos.app.gateway.setup.LocalSetupExecution
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** OS clock/process and independent scheduling; timeout and retirement policy are common. */
internal class AndroidLocalSetupExecution : LocalSetupExecution {
    override val dispatcher: CoroutineDispatcher = Dispatchers.IO
    private val pid = Process.myPid()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ukkera-setup-owner-watchdog").apply { isDaemon = true }
    }

    override fun elapsedMillis(): Long {
        check(pid > 0 && Process.myPid() == pid)
        return SystemClock.elapsedRealtime() // Includes deep sleep, unlike wall-clock time.
    }

    override fun startWatching(tick: () -> Unit) {
        try { watchdog.scheduleWithFixedDelay(tick, 250L, 250L, TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) { watchdog.shutdownNow(); throw failure }
    }

    override fun stopWatching(): Unit = watchdog.shutdown()
}
