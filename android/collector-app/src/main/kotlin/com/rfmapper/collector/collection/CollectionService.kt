package com.rfmapper.collector.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rfmapper.collector.CollectorGraph
import com.rfmapper.collector.R
import com.rfmapper.collector.ui.MainActivity
import com.rfmapper.core.radio.CollectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a collection session alive across screen-off and app-backgrounded.
 *
 * A foreground service is not optional here. Without one the process is a candidate for death the
 * moment the screen locks, and the specification's headline requirement is a six-hour session that
 * produces a monotonically growing count with no unexplained gap.
 *
 * Two details are load-bearing:
 *
 *  - **A partial wake lock.** The foreground service keeps the *process* alive, but not the CPU.
 *    Without the wake lock, Doze suspends the scan loop between maintenance windows and the sample
 *    rate silently collapses to a few per hour.
 *  - **A notification that shows live counters, not a static string.** It is the operator's only
 *    view of the session while the phone is in a pocket, and "3 h 12 m · 41 203 observations" is
 *    what makes a stalled session visible within a minute instead of at the end of the day.
 */
class CollectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusJob: Job? = null

    private val coordinator: CollectionCoordinator
        get() = CollectorGraph.from(this).coordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        // Deliberately not START_STICKY. A restarted service with no session would show a running
        // notification while collecting nothing, which is worse than being visibly stopped: the
        // operator would trust a session that does not exist.
        return START_NOT_STICKY
    }

    private fun startSession() {
        startForegroundSafely(notification(CollectionStatus.idle()))
        acquireWakeLock()

        scope.launch {
            runCatching { coordinator.start() }
                .onFailure {
                    // The only expected failure is a missing observer identity, which the UI
                    // prevents. Stopping rather than lingering keeps the notification honest.
                    stopSession()
                }
        }

        statusJob?.cancel()
        statusJob = scope.launch {
            coordinator.status.collectLatest { status ->
                notificationManager().notify(NOTIFICATION_ID, notification(status))
            }
        }
    }

    private fun stopSession() {
        scope.launch {
            runCatching { coordinator.stop() }
            releaseWakeLock()
            ServiceCompat.stopForeground(this@CollectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        // Wrapped because from API 34 an incorrect or missing service type throws rather than
        // degrading, and a crash at session start would lose the whole field trip.
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // A timeout is mandatory: an un-timed lock held by a crashed session would drain the
            // battery to zero with nothing to show for it.
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun notification(status: CollectionStatus): Notification {
        val content = if (status.isRunning) describe(status) else getString(R.string.app_name)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CollectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                if (status.isSurveying) "Survey capture in progress" else "Collecting",
            )
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun describe(status: CollectionStatus): String {
        val counts = status.counts
        val elapsed = status.elapsedMillis(System.currentTimeMillis())?.let(::formatDuration) ?: "—"
        val age = status.lastObservationAgeMillis(System.currentTimeMillis())

        return buildString {
            append("$elapsed · ${counts.total} obs")
            append(" · W ${counts.wifi} / B ${counts.ble}")
            if (counts.rtt > 0) append(" / R ${counts.rtt}")
            // A stale last-observation age is the earliest visible sign of a stalled session, so it
            // is shown before any of the other diagnostics.
            if (age != null && age > STALE_WARNING_MILLIS) {
                append("\nNo observation for ${formatDuration(age)}")
            }
            if (status.suspectedGap) append("\nSuspected service interruption")
            if (status.throttle?.isThrottled == true) append("\nWi-Fi scans throttled")
            if (status.degradation.isDegraded) append("\nDegraded: check permissions")
            if (counts.dropped > 0) append("\nDropped ${counts.dropped} samples")
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // LOW rather than MIN: a MIN-importance channel can be collapsed out of sight, and an
            // invisible session notification defeats the purpose of having one.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.rfmapper.collector.action.STOP"
        private const val CHANNEL_ID = "collection"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "rfmapper:collection"

        /** Long enough for the longest session the specification contemplates, plus margin. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 14L * 60 * 60 * 1000

        private const val STALE_WARNING_MILLIS = 5 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, CollectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CollectionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
