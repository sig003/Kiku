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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.SoundPool
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
    private var hasFocus = false             // 현재 오디오 포커스 보유 여부(중복 요청·누수 방지)
    private var resumeOnFocusGain = false    // 일시적 포커스 상실(전화·알림)로 멈췄으면 되돌아올 때 자동 재개
    private var noisyRegistered = false
    private var active = false   // 세션 진행 중(알림 표시 중) 여부
    private var currentShuffled = false      // 랜덤/셔플 재생이면 위치 저장 안 함
    private var currentRandomLevel: String? = null   // 전체 랜덤의 레벨 필터(전체/N4/N3)
    private var lastSavedSentence = -1

    // 딩동 효과음(퀴즈 반복 신호). SoundPool = 짧은 SFX 저지연 재생.
    private var soundPool: SoundPool? = null
    private var chimeSoundId = 0
    private var chimeLoaded = false
    private val chimeDurationMs = 1400L   // dingdong.wav 길이(1.4초)에 맞춘 대기

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
            AudioManager.AUDIOFOCUS_LOSS -> {   // 영구 상실(타 미디어앱 재생 등) → 정지 유지
                hasFocus = false                // 다음 재생 땐 새로 요청해야 함
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {   // 전화·알림·화면잠금 등 일시적 → 끝나면 재개
                // 음성 학습이라 덕킹(소리 줄이고 겹쳐 재생)은 부적절 → 일시정지 후 복귀 시 자동 재개.
                // 포커스는 계속 보유(hasFocus 유지) — 곧 오는 GAIN에서 우리가 재개한다.
                resumeOnFocusGain = state.value.playing
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {   // 방해 요소 종료 → 우리가 멈췄던 거면 자동 재개
                hasFocus = true
                if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(this) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { sp ->
                sp.setOnLoadCompleteListener { _, _, status -> chimeLoaded = status == 0 }
                chimeSoundId = sp.load(this, R.raw.dingdong, 1)
            }
        sequencer = TtsSequencer(tts, scope, playChime = {
            soundPool?.takeIf { chimeLoaded }?.let {
                it.play(chimeSoundId, 1f, 1f, 1, 0, 1f)
                kotlinx.coroutines.delay(chimeDurationMs)
            }
        })
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
                    base.blockShuffled()
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
                    base.blockShuffled() else base
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
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {   // 미디어버튼 리시버로 온 이어폰 키
            extractKey(intent)?.let { handleMediaKey(it) }
            return START_NOT_STICKY
        }
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
        resumeOnFocusGain = false   // 명시적 재생 → 대기 중이던 자동 재개 플래그 정리
        if (!requestFocus()) return
        // started 서비스로 승격 → 화면이 언바인드해도 생존
        startService(Intent(this, PlaybackService::class.java))
        active = true
        startKeepAlive()   // 우리 프로세스가 오디오 재생 중임을 OS에 알림(이어폰 버튼 라우팅용)
        startForegroundCompat(buildNotification(state.value))
        registerNoisy()
        sequencer.play()
    }

    fun pause() {
        sequencer.pause()
        stopKeepAlive()
        unregisterNoisy()
        // 알림은 남겨 재개 가능하게(포그라운드에서만 분리)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    // 최근앱에서 앱을 밀어내면 재생 종료 + 서비스 정리(사용자 기대 동작).
    // 백그라운드로 계속 듣고 싶으면 홈 버튼으로 나가면 됨.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    fun next() = sequencer.next()
    fun prev() = sequencer.prev()
    fun replay() = sequencer.replay()
    fun setSpeed(value: Float) = sequencer.setSpeed(value)

    private fun stopPlayback() {
        resumeOnFocusGain = false
        sequencer.pause()
        stopKeepAlive()
        unregisterNoisy()
        abandonFocus()
        active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── 무음 킵얼라이브: 우리 프로세스가 오디오 재생 중임을 OS가 인식하게 해
    //    이어폰/BT 미디어 버튼이 우리 세션으로 라우팅되게 한다(TTS는 별도 프로세스라 필요). ──
    private var keepAlive: AudioTrack? = null

    private fun startKeepAlive() {
        if (keepAlive != null) return
        val sampleRate = 8000
        val frames = sampleRate / 10          // 0.1초
        runCatching {
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            at.write(ShortArray(frames), 0, frames)   // 무음
            at.setLoopPoints(0, frames, -1)           // 무한 반복
            at.play()
            keepAlive = at
        }
    }

    private fun stopKeepAlive() {
        keepAlive?.let { runCatching { it.stop() }; runCatching { it.release() } }
        keepAlive = null
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

    // 오디오 포커스 요청은 "한 번만" 만들어 재사용한다.
    // (예전 버그) play()마다 새 AudioFocusRequest를 요청하면, 일시정지 땐 포커스를 반납하지 않으므로
    // 앞 요청이 안 버려진 채 새 요청이 쌓인다. 그 상태에서 화면잠금 등 일시적 상실(TRANSIENT)이 오면
    // 뒤따르는 복귀(GAIN)가 유령이 된 요청으로 라우팅돼 자동 재개가 안 되고 멈춘 채로 남는다.
    private fun requestFocus(): Boolean {
        if (hasFocus) return true   // 이미 보유(일시정지 후 재개 등) → 중복 요청 금지
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasFocus = false
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
            // 이어폰/블루투스 하드웨어 버튼을 이 서비스로 받도록 리시버 등록(없으면 세션이 후보에서 빠짐)
            setMediaButtonReceiver(
                PendingIntent.getService(
                    this@PlaybackService, 1,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this@PlaybackService, PlaybackService::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onStop() = stopPlayback()
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean =
                    extractKey(mediaButtonIntent)?.takeIf { handleMediaKey(it) }?.let { true }
                        ?: super.onMediaButtonEvent(mediaButtonIntent)
            })
            isActive = true
        }
    }

    private fun extractKey(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    /** 미디어 키 처리(이어폰/BT/알림 공통). 처리했으면 true. */
    private fun handleMediaKey(ev: KeyEvent): Boolean {
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        when (ev.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> playPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
            KeyEvent.KEYCODE_MEDIA_STOP -> stopPlayback()
            else -> return false
        }
        return true
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
        stopKeepAlive()
        abandonFocus()
        soundPool?.release()
        soundPool = null
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
