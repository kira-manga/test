package com.ukkera.pos.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ukkera.pos.app.setup.LocalSetupConnectionPhase
import com.ukkera.pos.app.setup.LocalSetupController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Visible Activity attachment only. The service, not this object, owns admitted native work.
 * No provider, native context or readiness Boolean travels through this UI connection. */
internal class AndroidSetupConnection(private val activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val published = MutableStateFlow<LocalSetupController?>(null)
    private val phaseValue = MutableStateFlow(LocalSetupConnectionPhase.DETACHED)
    val controller: StateFlow<LocalSetupController?> = published.asStateFlow()
    val phase: StateFlow<LocalSetupConnectionPhase> = phaseValue.asStateFlow()
    private var active: Attachment? = null
    private var destroyed = false

    /** Called on the main thread, only while the Activity is visible. Never auto-enrolls. */
    fun connect() {
        if (destroyed || active != null) return
        val attachment = Attachment()
        active = attachment
        phaseValue.value = LocalSetupConnectionPhase.CONNECTING
        try {
            val component = activity.startService(Intent(activity, AndroidLocalBranchService::class.java)
                .setAction(AndroidLocalBranchService.ACTION_RETAIN_SETUP))
            if (component == null) error("Local setup service unavailable")
            attachment.bound = activity.bindService(Intent(activity, AndroidLocalBranchService::class.java)
                .setAction(AndroidLocalBranchService.ACTION_BIND_SETUP), attachment, Context.BIND_AUTO_CREATE)
            if (!attachment.bound) unavailable(attachment)
        } catch (_: Exception) {
            // A failed connection is not proof that native acquisition/close never happened.
            // Do not stop/recreate the service, delete files or publish an in-memory substitute.
            unavailable(attachment)
        }
    }

    /** Locks private presentation and detaches only this Activity. Does not retire the owner. */
    fun disconnect() {
        val previous = active
        try { published.value?.lockPresentation() } catch (_: Exception) { /* still detach */ }
        published.value = null
        active = null // Ignore late callbacks from the old binding generation.
        previous?.updates?.cancel()
        if (previous?.bound == true) {
            previous.bound = false
            try { activity.unbindService(previous) } catch (_: Exception) { /* not native close */ }
        }
        phaseValue.value = LocalSetupConnectionPhase.DETACHED
    }

    fun close() {
        if (destroyed) return
        destroyed = true
        disconnect()
        scope.cancel()
    }

    private fun unavailable(attachment: Attachment) {
        if (active !== attachment) return
        try { published.value?.lockPresentation() } catch (_: Exception) { /* unpublish below */ }
        published.value = null
        attachment.updates?.cancel()
        attachment.updates = null
        active = null
        if (attachment.bound) {
            attachment.bound = false
            try { activity.unbindService(attachment) } catch (_: Exception) { /* not native close */ }
        }
        phaseValue.value = LocalSetupConnectionPhase.UNAVAILABLE
    }

    private inner class Attachment : ServiceConnection {
        var bound = false
        var updates: Job? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (destroyed || active !== this) return
            if (name != ComponentName(activity, AndroidLocalBranchService::class.java)) {
                unavailable(this); return
            }
            val setup = service as? AndroidLocalBranchService.SetupBinder
            if (setup == null) { unavailable(this); return }
            updates?.cancel()
            val current = this
            updates = scope.launch {
                try {
                    setup.controllers.collect { value ->
                        if (active !== current || destroyed) return@collect
                        published.value = value
                        phaseValue.value = if (value == null) LocalSetupConnectionPhase.UNAVAILABLE
                            else LocalSetupConnectionPhase.ATTACHED
                        // Rebinding never silently unlocks a private setup review.
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { unavailable(current) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) { unavailable(this) }
        override fun onBindingDied(name: ComponentName) { unavailable(this) }
        override fun onNullBinding(name: ComponentName) { unavailable(this) }
    }
}
