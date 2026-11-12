package com.ukkera.pos.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Winsvc
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Windows Service Control Manager entry for the packaged Master service process. This first
 * increment only reports its native packaged-service identity; it opens no storage or keys.
 */
internal object DesktopWindowsService {
    const val ARGUMENT = "--service"
    private val stopped = CountDownLatch(1)
    @Volatile private var handle: Winsvc.SERVICE_STATUS_HANDLE? = null

    // JNA callbacks stay strongly referenced for the whole process lifetime.
    private val handler = Winsvc.HandlerEx { control, _, _, _ ->
        when (control) {
            Winsvc.SERVICE_CONTROL_STOP, Winsvc.SERVICE_CONTROL_SHUTDOWN -> {
                report(Winsvc.SERVICE_STOP_PENDING, 0)
                stopped.countDown()
            }
        }
        0
    }

    private val main = object : Winsvc.SERVICE_MAIN_FUNCTION {
        override fun callback(argc: Int, argv: Pointer?) {
            handle = Advapi32.INSTANCE.RegisterServiceCtrlHandlerEx(
                DesktopWindowsServiceIdentity.TEST.serviceName, handler, null)
            if (handle == null) return
            report(Winsvc.SERVICE_RUNNING, Winsvc.SERVICE_ACCEPT_STOP or Winsvc.SERVICE_ACCEPT_SHUTDOWN)
            runCatching { probe() }
            stopped.await()
            report(Winsvc.SERVICE_STOPPED, 0)
        }
    }

    /** Blocks in the SCM dispatcher until the service stops; nonzero when not started by SCM. */
    fun run(): Int {
        @Suppress("UNCHECKED_CAST")
        val table = Winsvc.SERVICE_TABLE_ENTRY().toArray(2) as Array<Winsvc.SERVICE_TABLE_ENTRY>
        table[0].lpServiceName = DesktopWindowsServiceIdentity.TEST.serviceName
        table[0].lpServiceProc = main
        table[1].lpServiceName = null
        table[1].lpServiceProc = null
        table.forEach { it.write() }
        return if (Advapi32.INSTANCE.StartServiceCtrlDispatcher(table)) 0 else 3
    }

    private fun report(state: Int, accepted: Int) {
        val status = Winsvc.SERVICE_STATUS().apply {
            dwServiceType = 0x10 // SERVICE_WIN32_OWN_PROCESS
            dwCurrentState = state
            dwControlsAccepted = accepted
            dwWaitHint = if (state == Winsvc.SERVICE_STOP_PENDING) 10_000 else 0
        }
        handle?.let { Advapi32.INSTANCE.SetServiceStatus(it, status) }
    }

    /** Test-runner diagnostic: written only when the runner pre-created the probe directory. */
    private fun probe() {
        val directory = File(System.getenv("ProgramData") ?: return, "UkkeraPOSTestProbe")
        if (!directory.isDirectory) return
        val line = when (val result = WindowsInstalledApplicationIdentity.capturePackagedService(
            DesktopWindowsServiceIdentity.TEST.target())) {
            is SelfOwnedInstalledIdentityResultV2.Data -> "DATA pfn=${result.value.target.let {
                (it as? com.ukkera.pos.security.SelfOwnedWindowsPackagedServiceIdentityV2)?.packageFamilyName }} " +
                "appContainer=${result.value.isAppContainer}"
            is SelfOwnedInstalledIdentityResultV2.Refused -> "REFUSED ${result.code}"
        }
        File(directory, "service-probe.txt").writeText(line + "\n")
    }
}
