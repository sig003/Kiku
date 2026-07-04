package com.bradlab.kiku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 재생을 소유하는 Foreground Service (DESIGN.md §2.6) — 이 앱의 본체.
 *
 * TtsSequencer(재생 엔진)와 TextToSpeech를 서비스가 소유한다. 화면(Activity)이 죽어도
 * 서비스가 살아 소리를 계속 낸다. 화면은 [state]를 구독하고 컨트롤 메서드로 명령만 보낸다(§3 단방향).
 *
 * - startForeground + 미디어 알림 → 백그라운드/화면꺼짐에도 프로세스 생존
 * - MediaSession → 잠금화면·이어폰 미디어 버튼(재생/정지/이전/다음)
 * - AudioFocus → 전화·타앱 소리 시 일시정지, 이어폰 뽑힘(AUDIO_BECOMING_NOISY) 시 일시정지
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var tts: TextToSpeech
    private val ttsReady = CompletableDeferred<Boolean>()
    private lateinit var sequencer: TtsSequencer
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private val repo by lazy { AssetClipRepository(applicationContext) }
    private val progress by lazy { getSharedPreferences("kiku_progress", MODE_PRIVATE) }
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var active = false   // 세션 진행 중(알림 표시 중) 여부
    private var currentShuffled = false      // 랜덤/셔플 재생이면 위치 저장 안 함
    private var currentRandomLevel: String? = null   // 전체 랜덤의 레벨 필터(전체/N4/N3)
    private var lastSavedSentence = -1

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun service(): PlaybackService = this@PlaybackService }

    /** 화면이 구독하는 재생 상태. */
    val state: StateFlow<PlayerUiState> get() = sequencer.state

    private val nm get() = getSystemService(NotificationManager::class.java)

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()  // 이어폰 뽑힘 → 정지
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()  // 전화/타앱 소리 → 정지
            // 음성 학습이라 GAIN 시 자동 재개는 생략(사용자가 직접 재생)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(this) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
        sequencer = TtsSequencer(tts, scope)
        setupMediaSession()
        ensureChannel()
        scope.launch {
            sequencer.state.collect { st ->
                render(st)
                saveProgress(st)   // 진행 위치 저장(순차·실제 클립만)
            }
        }
    }

    /**
     * 클립을 적재. [shuffle]=true면 그 클립 문장을 섞어서(랜덤 재생).
     * 순차(비셔플) 재생이면 저장된 진행 위치부터 복원한다.
     */
    fun open(clipId: Int, shuffle: Boolean = false, randomLevel: String? = null) {
        scope.launch {
            ttsReady.await()
            // 이미 같은 클립을 순차 재생 중이면 위치 유지(재적재 안 함)
            if (!shuffle && !currentShuffled && state.value.clipId == clipId) return@launch
            val base =
                if (clipId == AssetClipRepository.RANDOM_CLIP_ID) {
                    currentRandomLevel = randomLevel
                    repo.randomClip(level = randomLevel)
                } else repo.clip(clipId) ?: return@launch
            currentShuffled = shuffle || clipId == AssetClipRepository.RANDOM_CLIP_ID
            val clip =
                if (shuffle && clipId != AssetClipRepository.RANDOM_CLIP_ID)
                    base.copy(sentences = base.sentences.shuffled())
                else base
            // 순차·실제 클립이면 저장된 위치 복원
            val start = if (!currentShuffled && clip.id >= 1) progress.getInt("pos_${clip.id}", 0) else 0
            lastSavedSentence = -1
            sequencer.pause()
            sequencer.load(clip, start, shuffled = currentShuffled)
        }
    }

    /**
     * 무작위 순서 재생 토글(재생화면 🔀).
     * on=true → 현재 클립을 섞어 처음부터. on=false → 원래 순서로 되돌려 처음부터.
     * (랜덤 클립 id=0은 순서 개념이 없어 off 무시)
     */
    fun setShuffle(on: Boolean) {
        val id = state.value.clipId
        if (id < 0) return
        scope.launch {
            ttsReady.await()
            if (on) {
                val base =
                    if (id == AssetClipRepository.RANDOM_CLIP_ID) repo.randomClip(level = currentRandomLevel)
                    else repo.clip(id) ?: return@launch
                currentShuffled = true
                val clip = if (id != AssetClipRepository.RANDOM_CLIP_ID)
                    base.copy(sentences = base.sentences.shuffled()) else base
                lastSavedSentence = -1
                sequencer.pause()
                sequencer.load(clip, 0, shuffled = true)
            } else {
                if (id == AssetClipRepository.RANDOM_CLIP_ID) return@launch
                val clip = repo.clip(id) ?: return@launch
                currentShuffled = false
                lastSavedSentence = -1
                sequencer.pause()
                sequencer.load(clip, 0, shuffled = false)
            }
        }
    }

    /** 진행 위치 저장: 순차·실제 클립만. 끝까지 들으면 위치를 지워 다음엔 처음부터(정리). */
    private fun saveProgress(st: PlayerUiState) {
        if (currentShuffled || st.clipId < 1) return
        if (st.finished) {
            progress.edit().remove("pos_${st.clipId}").apply()
        } else if (st.sentenceIndex != lastSavedSentence) {
            progress.edit().putInt("pos_${st.clipId}", st.sentenceIndex).apply()
            lastSavedSentence = st.sentenceIndex
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playPause()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    // ── 컨트롤: 화면 바인딩 / 알림 / 미디어세션 공통 진입점 ──────────────

    fun playPause() { if (state.value.playing) pause() else play() }

    fun play() {
        if (!requestFocus()) return
        // started 서비스로 승격 → 화면이 언바인드해도 생존
        startService(Intent(this, PlaybackService::class.java))
        active = true
        startForegroundCompat(buildNotification(state.value))
        registerNoisy()
        sequencer.play()
    }

    fun pause() {
        sequencer.pause()
        unregisterNoisy()
        // 알림은 남겨 재개 가능하게(포그라운드에서만 분리)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun next() = sequencer.next()
    fun prev() = sequencer.prev()
    fun replay() = sequencer.replay()
    fun setSpeed(value: Float) = sequencer.setSpeed(value)

    private fun stopPlayback() {
        sequencer.pause()
        unregisterNoisy()
        abandonFocus()
        active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 미니바 닫기(✕) — 재생 중단 + 세션 상태 비움(진행 위치 저장값은 유지해 다음에 이어듣기). */
    fun dismiss() {
        stopPlayback()
        sequencer.clear()
    }

    // ── 상태 변화 → 알림/미디어세션 갱신 ─────────────────────────────

    private fun render(st: PlayerUiState) {
        updateMediaSession(st)
        if (st.finished) { stopPlayback(); return }
        if (active) nm.notify(NOTIF_ID, buildNotification(st))
    }

    // ── AudioFocus ──────────────────────────────────────────────

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun registerNoisy() {
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
    }

    private fun unregisterNoisy() {
        if (noisyRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    // ── MediaSession (잠금화면·이어폰 버튼) ──────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "kiku").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onStop() = stopPlayback()

                // 이어폰/블루투스 하드웨어 버튼 직접 처리 (일부 기기는 위 콜백으로 안 옴)
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val ev = if (Build.VERSION.SDK_INT >= 33)
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    else @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev != null && ev.action == KeyEvent.ACTION_DOWN) {
                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> playPause()
                            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                            KeyEvent.KEYCODE_MEDIA_NEXT -> next()
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
                            KeyEvent.KEYCODE_MEDIA_STOP -> stopPlayback()
                            else -> return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSession(st: PlayerUiState) {
        val playState = if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(playState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (st.playing) st.speed else 0f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st.title.ifEmpty { "kiku" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "문장 ${st.sentenceIndex + 1}/${st.totalSentences}")
                .build()
        )
    }

    // ── 알림 ────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "재생", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(st: PlayerUiState): Notification {
        val (ppIcon, ppTitle) =
            if (st.playing) android.R.drawable.ic_media_pause to "일시정지"
            else android.R.drawable.ic_media_play to "재생"

        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(st.title.ifEmpty { "kiku" })
            .setContentText("문장 ${st.sentenceIndex + 1}/${st.totalSentences}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
            .setOngoing(st.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "이전", ACTION_PREV))
            .addAction(action(ppIcon, ppTitle, ACTION_PLAY_PAUSE))
            .addAction(action(android.R.drawable.ic_media_next, "다음", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "정지", ACTION_STOP))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        unregisterNoisy()
        abandonFocus()
        sequencer.release()
        mediaSession.release()
        scope.cancel()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "kiku_playback"
        private const val NOTIF_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.bradlab.kiku.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.bradlab.kiku.action.NEXT"
        const val ACTION_PREV = "com.bradlab.kiku.action.PREV"
        const val ACTION_STOP = "com.bradlab.kiku.action.STOP"
    }
}
