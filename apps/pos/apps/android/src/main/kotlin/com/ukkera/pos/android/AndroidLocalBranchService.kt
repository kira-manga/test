package com.ukkera.pos.android

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real Android lifetime owner for LOCAL SETUP and the original cohost connection. This
 * nonexported service is started/bound from the visible application. The shared coordinator
 * may reopen a durably requested EXISTING master, but never install, repair journals, promote
 * a client or restore employee sign-in. Explicit setup uses the actual native/Room installer.
 *
 * onUnbind/task removal/backgrounding lock presentation, without closing admitted setup work.
 * Destruction or service disposal unpublishes first, then drains and closes the actual owner.
 * Service disposal is NOT an authorized intentional STOP and publishes no STOP intent; only the
 * explicit authenticated lifecycle ingress may clear/set the durable STOP projection.
 * A process-static slot retains a destroyed service's owner until its real close succeeds.
 * Failed/unknown close does NOT release that slot or let a new service hide the old lifetime.
 *
 * Before an explicit original-client connection it publishes a special-use foreground notice.
 * The actual native service and both protected journals still gate employee sign-in. LAN and
 * foreground-service/OEM qualification remain unverified. START_NOT_STICKY is
 * not role authority; the shared launch record only triggers original native checks. The OS can
 * still stop/kill this service; an interrupted installation is not blindly replayed.
 */
public class AndroidLocalBranchService : Service() {
    private val published = MutableStateFlow<LocalSetupController?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val binder = SetupBinder()
    private var owned: Slot? = null

    override fun onCreate() {
        super.onCreate()
        val acquired = synchronized(processGate) {
            if (retained != null) null
            else Slot(this, AndroidLocalSetupOwner(application, ::beforeOwnerRetirement, ::startLocalHostingNotice)).also {
                retained = it
                owned = it
                published.value = it.owner.controller
            }
        }
        if (acquired == null) {
            // In particular, an old destroyed service with uncertain native close remains
            // retained. No new native owner or reject-all operational provider is substituted.
            stopSelf()
            return
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != ACTION_BIND_SETUP || !ownsPublishedSlot()) return null
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RETAIN_SETUP -> owned?.owner?.reopenRetainedMaster()
            // Disposal/relaunch plumbing only: this is not employee lifecycle authority and
            // must never publish an authorized STOP intent. The authenticated UI ingress owns STOP.
            ACTION_RETIRE_SETUP -> retireOwnedSlot(intentional = false)
            else -> if (owned == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        owned?.owner?.lockPresentation()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        // The owner remains locked until the visible UI explicitly resumes presentation.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        owned?.owner?.lockPresentation()
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) owned?.owner?.lockPresentation()
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        retireOwnedSlot()
        super.onDestroy()
        // Do not cancel the coordinator, clear owned/retained, or infer native close here.
    }

    /** Same-process UI binder only; no Binder RPC, native capability or master client export. */
    public inner class SetupBinder internal constructor() : Binder() {
        /** Null is published before ANY retirement; a controller never represents admission. */
        public val controllers: StateFlow<LocalSetupController?>
            get() { requireSameProcess(); return published.asStateFlow() }

        public fun lockPresentation() {
            requireSameProcess()
            owned?.owner?.lockPresentation()
        }

        /** Ordinary service-owner disposal. Never a product intentional STOP or lifecycle grant. */
        public fun retireSetup() {
            requireSameProcess()
            retireOwnedSlot(intentional = false)
        }
    }

    private fun ownsPublishedSlot(): Boolean = synchronized(processGate) {
        val slot = owned
        slot != null && retained === slot && slot.service === this && !slot.retiring && published.value != null
    }

    private fun retireOwnedSlot(intentional: Boolean = false) {
        val slot = synchronized(processGate) {
            val current = owned ?: return
            if (retained !== current || (current.retiring && !intentional)) return
            current
        }
        slot.owner.requestRetirement(intentional)
    }

    /** Called synchronously before native denial/drain, including owner-internal retirement. */
    private fun beforeOwnerRetirement(owner: AndroidLocalSetupOwner, completion: Deferred<Unit>) {
        val slot = synchronized(processGate) {
            val current = checkNotNull(owned)
            check(retained === current && current.service === this && current.owner === owner && !current.retiring)
            current.retiring = true
            published.value = null
            current
        }
        completion.invokeOnCompletion { failure ->
            if (failure == null) {
                synchronized(processGate) {
                    // Successful completion is the actual coordinator/native close result,
                    // not an Activity teardown, canceled waiter, receipt or health Boolean.
                    if (retained === slot) retained = null
                }
                main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            // Failure retains slot, service and owner. No retries, process kill or replacement.
        }
    }

    private fun requireSameProcess() {
        check(Binder.getCallingUid() == Process.myUid() && Binder.getCallingPid() == Process.myPid()) {
            "Setup binder is same-process only"
        }
    }

    /** Explicit visible-user connection only. No secret, company or employee data in the notice. */
    private fun startLocalHostingNotice() {
        check(Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_ANDROID_API && ownsPublishedSlot())
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(HOSTING_CHANNEL,
                getString(R.string.local_master_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val entry = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, HOSTING_CHANNEL)
            else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.local_master_title))
            .setContentText(getString(R.string.local_master_text))
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(entry)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(HOSTING_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(HOSTING_NOTIFICATION, notification)
    }

    private class Slot(val service: AndroidLocalBranchService, val owner: AndroidLocalSetupOwner) {
        var retiring = false
    }

    public companion object {
        public const val ACTION_BIND_SETUP: String = "com.ukkera.pos.local.action.BIND_ORIGINAL_SETUP_V1"
        public const val ACTION_RETAIN_SETUP: String = "com.ukkera.pos.local.action.RETAIN_ORIGINAL_SETUP_V1"
        public const val ACTION_RETIRE_SETUP: String = "com.ukkera.pos.local.action.RETIRE_ORIGINAL_SETUP_V1"
        private val processGate = Any()
        private var retained: Slot? = null
        private const val HOSTING_CHANNEL = "ukkera.local-master.v1"
        private const val HOSTING_NOTIFICATION = 1401
    }
}
