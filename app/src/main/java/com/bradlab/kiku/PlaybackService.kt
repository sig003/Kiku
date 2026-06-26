package com.bradlab.kiku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 백그라운드 재생 검증용 Foreground Service (DESIGN.md §2.6, §9 두 번째 리스크 프로브).
 *
 * 목적: TTS가 Foreground Service 안에서 **화면이 꺼지거나 다른 앱으로 나가도 계속 소리를 내는지** 확인.
 * 지금은 짧은 일/한 문장 리스트를 무한 반복 재생만 한다 — 화면 잠그고 소리 이어지는지 보면 됨.
 *
 * 추후: 여기에 TtsSequencer(PlaybackStep) + MediaSession + AudioFocus를 얹어 본 구현으로 확장(§2.6).
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("백그라운드 재생 준비 중…"))
        if (loopJob == null) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        val ready = CompletableDeferred<Boolean>()
        tts = TextToSpeech(this) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

        loopJob = scope.launch {
            if (!ready.await()) {
                update("TTS 초기화 실패")
                return@launch
            }
            val engine = tts ?: return@launch
            // 검증용 샘플 — 일↔한 번갈아. 충분히 길어 화면 잠그고 이어지는지 확인 가능.
            val lines = listOf(
                "昨日は会社を休みました。" to Locale.JAPANESE,
                "어제는 회사를 쉬었습니다." to Locale.KOREAN,
                "今日はいい天気ですね。" to Locale.JAPANESE,
                "오늘은 날씨가 좋네요." to Locale.KOREAN,
                "電車に乗って学校へ行きます。" to Locale.JAPANESE,
                "전철을 타고 학교에 갑니다." to Locale.KOREAN,
            )
            var round = 1
            while (isActive) {
                lines.forEachIndexed { i, (text, locale) ->
                    if (!isActive) return@launch
                    update("재생 중 · ${round}회차 ${i + 1}/${lines.size}")
                    engine.setSpeechRate(1.0f)
                    engine.speakAndAwait(text, locale) // TtsCheck.kt의 suspend 확장 재사용 (§2.4)
                    delay(800)
                }
                round++
            }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "재생", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("kiku — 백그라운드 재생 검증")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(0, "정지", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "kiku_playback"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.bradlab.kiku.action.STOP"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_STOP))
    }
}
