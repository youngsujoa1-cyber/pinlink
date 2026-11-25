package com.youngsu.pinlink.location

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.youngsu.pinlink.R
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.ui.MainActivity

/**
 * 위치 공유 포그라운드 서비스.
 *
 *  - 앱이 닫혀 있어도 이 알림이 떠 있는 동안 위치를 올린다(foregroundServiceType="location").
 *  - 실제 측정·업로드는 [PinSession] 이 하고, 이 서비스는 프로세스를 살려 두고 상태를 알린다.
 *  - 고른 시간이 끝나면 PinSession 이 스스로 멈추고, 알림도 함께 사라진다.
 */
class ShareService : Service() {

    private var lastText: String? = null

    private val watcher: () -> Unit = { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        PinSession.init(applicationContext)
        createChannel()
        startAsForeground()
        PinSession.onServiceStarted()
        PinSession.addListener(watcher)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PinSession.applyMode(ShareMode.PAUSED)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        PinSession.onServiceStarted()
        return START_STICKY
    }

    private fun startAsForeground() {
        lastText = null
        val notification = buildNotification(currentText())
        val ok = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
            )
        }.isSuccess
        if (!ok) {
            // 위치 권한이 도중에 회수된 경우 등 — 포그라운드로 못 뜨면 서비스를 내려 앱이 죽지 않게 한다
            PinSession.applyMode(ShareMode.PAUSED)
            stopSelf()
        }
    }

    private fun currentText(): String {
        val code = PinSession.roomCode
        if (code.isEmpty()) return getString(R.string.notif_title)
        if (!PinSession.mode.isSharing) return getString(R.string.notif_text_paused, code)
        val remaining = PinLogic.remainingMillis(PinSession.expiresAt, System.currentTimeMillis())
        return when {
            remaining == 0L -> getString(R.string.notif_text_room, code)
            remaining < 0L -> getString(R.string.notif_text_paused, code)
            else -> getString(R.string.notif_text_expiry, code, PinLogic.formatDuration(remaining))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun updateNotification() {
        if (!PinSession.mode.isSharing) {
            stopSelf()
            return
        }
        val text = currentText()
        if (text == lastText) return
        lastText = text
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        PinSession.removeListener(watcher)
        PinSession.onServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pinlink_sharing"
        private const val NOTIFICATION_ID = 2401

        /** 알림의 '중지' 버튼 */
        const val ACTION_STOP = "com.youngsu.pinlink.action.STOP_SHARING"
    }
}
