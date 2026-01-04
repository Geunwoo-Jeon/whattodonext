package com.geunwoo.jun.whattodonext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class TaskTimerService : Service() {

    private var countDownTimer: CountDownTimer? = null
    private var overtimeHandler: Handler? = null
    private var overtimeRunnable: Runnable? = null
    private var idleHandler: Handler? = null
    private var idleRunnable: Runnable? = null

    private var taskName: String = ""
    private var taskType: String = ""
    private var totalTimeMillis: Long = 0
    private var remainingTimeMillis: Long = 0
    private var isOvertime: Boolean = false
    private var overtimeMillis: Long = 0
    private var overtimeStartTime: Long = 0
    private var isIdleMode: Boolean = false

    companion object {
        const val CHANNEL_ID = "task_timer_channel"
        const val PUSH_CHANNEL_ID = "task_push_channel"
        const val NOTIFICATION_ID = 1
        const val PUSH_NOTIFICATION_ID = 2
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_START_IDLE = "START_IDLE"
        const val ACTION_TICK = "com.geunwoo.jun.whattodonext.TICK"
        const val ACTION_COMPLETE = "com.geunwoo.jun.whattodonext.COMPLETE"
        const val ACTION_SHOW_TIMER = "com.geunwoo.jun.whattodonext.SHOW_TIMER"
        const val EXTRA_TASK_NAME = "task_name"
        const val EXTRA_TASK_TYPE = "task_type"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
        const val EXTRA_REMAINING_TIME = "remaining_time"
        const val EXTRA_IS_OVERTIME = "is_overtime"
        const val EXTRA_OVERTIME_MILLIS = "overtime_millis"

        private const val IDLE_PUSH_INTERVAL = 3 * 60 * 1000L // 3분
        private const val OVERTIME_PUSH_INTERVAL = 3 * 60 * 1000L // 3분

        fun startTimer(context: Context, taskName: String, taskType: String, durationMillis: Long) {
            val intent = Intent(context, TaskTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_TASK_TYPE, taskType)
                putExtra(EXTRA_DURATION_MILLIS, durationMillis)
            }
            context.startForegroundService(intent)
        }

        fun stopTimer(context: Context) {
            val intent = Intent(context, TaskTimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startIdleMode(context: Context) {
            val intent = Intent(context, TaskTimerService::class.java).apply {
                action = ACTION_START_IDLE
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 기존 idle 모드 중지
                stopIdleHandler()
                isIdleMode = false

                taskName = intent.getStringExtra(EXTRA_TASK_NAME) ?: ""
                taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: "CHALLENGE"
                totalTimeMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0)
                remainingTimeMillis = totalTimeMillis
                isOvertime = false
                overtimeMillis = 0
                startForeground(NOTIFICATION_ID, createOngoingNotification())
                startCountDown()
            }
            ACTION_STOP -> {
                stopCountDown()
                stopOvertimeHandler()
                stopIdleHandler()
                stopForeground(STOP_FOREGROUND_REMOVE)
                cancelPushNotification()
                stopSelf()
            }
            ACTION_START_IDLE -> {
                // 기존 타이머 중지
                stopCountDown()
                stopOvertimeHandler()

                isIdleMode = true
                taskName = ""
                taskType = ""
                startForeground(NOTIFICATION_ID, createIdleNotification())
                startIdleReminder()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // 상시 알림 채널 (소리 없음)
        val ongoingChannel = NotificationChannel(
            CHANNEL_ID,
            "할 일 타이머",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "현재 진행 중인 할 일을 표시합니다"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(ongoingChannel)

        // 푸시 알림 채널 (소리/진동)
        val pushChannel = NotificationChannel(
            PUSH_CHANNEL_ID,
            "타이머 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "목표 시간 도달 및 초과 알림"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(pushChannel)
    }

    private fun createOngoingNotification(): Notification {
        val showTimerIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_TIMER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showTimerPendingIntent = PendingIntent.getActivity(
            this, 0, showTimerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_COMPLETE
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val completePendingIntent = PendingIntent.getActivity(
            this, 1, completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeEmoji = if (taskType == "CHALLENGE") "🔥" else "🌿"

        val timeText = if (isOvertime) {
            "초과 시간: +${formatTime(overtimeMillis)}"
        } else {
            "남은 시간: ${formatTime(remainingTimeMillis)}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$typeEmoji $taskName")
            .setContentText(timeText)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setContentIntent(showTimerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "완료", completePendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun sendPushNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val completeIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_COMPLETE
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val completePendingIntent = PendingIntent.getActivity(
            this, 1, completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeEmoji = if (taskType == "CHALLENGE") "🔥" else "🌿"

        val notification = NotificationCompat.Builder(this, PUSH_CHANNEL_ID)
            .setContentTitle("$typeEmoji $taskName")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setAutoCancel(true)
            .setContentIntent(completePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "완료", completePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(PUSH_NOTIFICATION_ID, notification)
    }

    private fun cancelPushNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(PUSH_NOTIFICATION_ID)
    }

    private fun createIdleNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏱️ 지금 이 순간")
            .setContentText("다음엔 뭘 해볼까요?")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun sendIdlePushNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val messages = listOf(
            "이 시간을 어떻게 쓸까요?",
            "지금 이 순간, 뭘 하고 싶어요?",
            "다음 할 일을 정해볼까요?",
            "도전할까요, 충전할까요?"
        )

        val notification = NotificationCompat.Builder(this, PUSH_CHANNEL_ID)
            .setContentTitle("⏱️ 잠깐!")
            .setContentText(messages.random())
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(PUSH_NOTIFICATION_ID, notification)
    }

    private fun startIdleReminder() {
        idleHandler = Handler(Looper.getMainLooper())

        idleRunnable = object : Runnable {
            override fun run() {
                sendIdlePushNotification()
                idleHandler?.postDelayed(this, IDLE_PUSH_INTERVAL)
            }
        }
        // 첫 알림은 3분 후
        idleHandler?.postDelayed(idleRunnable!!, IDLE_PUSH_INTERVAL)
    }

    private fun stopIdleHandler() {
        idleRunnable?.let { idleHandler?.removeCallbacks(it) }
        idleHandler?.removeCallbacksAndMessages(null)
        idleHandler = null
        idleRunnable = null
    }

    private fun startCountDown() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remainingTimeMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTimeMillis = millisUntilFinished
                updateOngoingNotification()
                broadcastTick()
            }

            override fun onFinish() {
                remainingTimeMillis = 0
                isOvertime = true
                overtimeStartTime = System.currentTimeMillis()

                // 목표 시간 도달 푸시 알림
                sendPushNotification("⏰ 목표 시간이 됐어요!")

                // 초과 시간 추적 시작
                startOvertimeTracking()
            }
        }.start()
    }

    private fun startOvertimeTracking() {
        overtimeHandler = Handler(Looper.getMainLooper())

        // 1초마다 초과 시간 업데이트
        val updateRunnable = object : Runnable {
            override fun run() {
                overtimeMillis = System.currentTimeMillis() - overtimeStartTime
                updateOngoingNotification()
                broadcastTick()
                overtimeHandler?.postDelayed(this, 1000)
            }
        }
        overtimeHandler?.post(updateRunnable)

        // 3분마다 푸시 알림
        overtimeRunnable = object : Runnable {
            override fun run() {
                val minutes = (overtimeMillis / 60000).toInt()
                sendPushNotification("⏰ ${minutes}분 초과! 완료하거나 다음 일로 넘어갈까요?")
                overtimeHandler?.postDelayed(this, OVERTIME_PUSH_INTERVAL)
            }
        }
        overtimeHandler?.postDelayed(overtimeRunnable!!, OVERTIME_PUSH_INTERVAL)
    }

    private fun stopOvertimeHandler() {
        overtimeRunnable?.let { overtimeHandler?.removeCallbacks(it) }
        overtimeHandler?.removeCallbacksAndMessages(null)
        overtimeHandler = null
        overtimeRunnable = null
    }

    private fun stopCountDown() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    private fun updateOngoingNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createOngoingNotification())
    }

    private fun broadcastTick() {
        val intent = Intent(ACTION_TICK).apply {
            putExtra(EXTRA_REMAINING_TIME, remainingTimeMillis)
            putExtra(EXTRA_IS_OVERTIME, isOvertime)
            putExtra(EXTRA_OVERTIME_MILLIS, overtimeMillis)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCountDown()
        stopOvertimeHandler()
        stopIdleHandler()
    }
}
