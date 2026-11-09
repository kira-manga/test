package com.ukkera.pos.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import kotlinx.coroutines.Deferred

/**
 * Foreground host for exact, already-created training owners. Service disposal withdraws and
 * drains those owners through the shared coordinator; only confirmed native close releases a
 * slot. Starting this service never creates an owner, grants authority, or writes STOP.
 */
public class AndroidLocalTrainingBranchService : Service() {
    private val owned = mutableMapOf<LocalTrainingCompositionSlot, AndroidLocalTrainingSetupOwner>()
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val slot = slotFrom(intent)
        val owner = synchronized(noticeGate) { activeOwners[slot] }
        if (slot == null || owner == null) {
            if (owned.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        owned[slot] = owner
        startNotice()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        owned.values.forEach { it.lockPresentation() }
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) owned.values.forEach { it.lockPresentation() }
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        // The application registry keeps closing/uncertain owners until genuine close; no
        // replacement can acquire their exact native roots while this asynchronous drain runs.
        owned.values.toList().forEach { (application as PosApplication).retireTrainingOwner(it) }
        owned.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TRAINING_CHANNEL,
            getString(R.string.training_master_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun startNotice() {
        check(Build.VERSION.SDK_INT >= 24)
        val entry = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, TRAINING_CHANNEL)
        else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.training_master_title))
            .setContentText(getString(R.string.training_master_text))
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(entry)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TRAINING_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TRAINING_NOTIFICATION, notification)
        }
    }

    private fun slotFrom(intent: Intent?): LocalTrainingCompositionSlot? {
        if (intent?.action != ACTION_RETAIN_TRAINING) return null
        val name = intent.getStringExtra(EXTRA_TRAINING_SLOT) ?: return null
        return LocalTrainingCompositionSlot.entries.firstOrNull { it.name == name }
    }

    public companion object {
        public const val ACTION_RETAIN_TRAINING: String =
            "com.ukkera.pos.local.action.RETAIN_TRAINING_COMPOSITION_V1"
        public const val EXTRA_TRAINING_SLOT: String =
            "com.ukkera.pos.local.extra.TRAINING_COMPOSITION_SLOT_V1"

        private val noticeGate = Any()
        private val activeOwners = mutableMapOf<LocalTrainingCompositionSlot, AndroidLocalTrainingSetupOwner>()
        private const val TRAINING_CHANNEL = "ukkera.training-composition.v1"
        private const val TRAINING_NOTIFICATION = 1402

        /** Register the real owner before starting; an intent alone cannot create/reopen one. */
        internal fun start(context: Context, owner: AndroidLocalTrainingSetupOwner) = synchronized(noticeGate) {
            val previous = activeOwners[owner.slot]
            check(previous == null || previous === owner)
            activeOwners[owner.slot] = owner
            try {
                val intent = Intent(context, AndroidLocalTrainingBranchService::class.java)
                    .setAction(ACTION_RETAIN_TRAINING)
                    .putExtra(EXTRA_TRAINING_SLOT, owner.slot.name)
                val component = if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
                check(component != null) { "Training composition service is unavailable" }
            } catch (failure: Throwable) {
                if (previous == null && activeOwners[owner.slot] === owner) activeOwners.remove(owner.slot)
                throw failure
            }
        }

        /** Release only the exact closed incarnation; uncertain close retains the owner. */
        internal fun stopAfter(context: Context, owner: AndroidLocalTrainingSetupOwner,
            completion: Deferred<Unit>) {
            completion.invokeOnCompletion { failure ->
                if (failure == null) synchronized(noticeGate) {
                    if (activeOwners[owner.slot] === owner) {
                        activeOwners.remove(owner.slot)
                        if (activeOwners.isEmpty()) context.stopService(
                            Intent(context, AndroidLocalTrainingBranchService::class.java))
                    }
                }
            }
        }
    }
}
