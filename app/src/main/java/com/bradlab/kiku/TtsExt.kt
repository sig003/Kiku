package com.bradlab.kiku

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * DESIGN.md §2.4 — speak()를 suspend로 감싸 onDone(또는 onError)에서 resume.
 * 시퀀서가 "한 문장 다 읽으면 다음"을 순차 코드로 쓰게 해주는 토대.
 *
 * 문장 안 쉼표(、，,)에서 끊어 읽고 그 자리에 짧은 무음을 넣어 호흡을 준다
 * (안드로이드 TTS는 엔진에 따라 쉼표를 그냥 이어 읽는 경우가 많음).
 *
 * 주의: language/speechRate/pitch는 여기서 건드리지 않는다 — 매 발화마다 엔진을 재설정하면
 * 이음새가 거칠어질 수 있어, 호출자(시퀀서)가 "바뀔 때만" 설정한다.
 */
suspend fun TextToSpeech.speakAndAwait(text: String, commaPauseMs: Long = 400L) =
    suspendCancellableCoroutine { cont ->
        val base = text.hashCode()
        val finalId = "f$base"
        val parts = text.split(Regex("[、，,]")).map { it.trim() }.filter { it.isNotEmpty() }
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == finalId && cont.isActive) cont.resume(Unit)  // 마지막 조각에서만 재개
            }
            @Deprecated("deprecated in API")
            override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(Unit) }
        })
        if (parts.size <= 1) {
            speak(text, TextToSpeech.QUEUE_FLUSH, null, finalId)
        } else {
            parts.forEachIndexed { i, part ->
                val last = i == parts.lastIndex
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                speak(part, mode, null, if (last) finalId else "p$i-$base")
                if (!last) playSilentUtterance(commaPauseMs, TextToSpeech.QUEUE_ADD, "s$i-$base")
            }
        }
        cont.invokeOnCancellation { stop() }
    }
