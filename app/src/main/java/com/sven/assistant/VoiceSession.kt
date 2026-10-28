package com.sven.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hands-free conversation loop on the main thread:
 *   LISTENING -> (text) -> WORKING -> SPEAKING -> LISTENING ... until silence, STOP, or the turn cap.
 * Uses the in-app SpeechRecognizer (no system dialog) so it can re-open the microphone by itself,
 * show live partial text, and be interrupted at any point.
 */
internal class VoiceSession(private val activity: Activity, private val cb: Callbacks) {

    enum class State { IDLE, LISTENING, WORKING, SPEAKING }

    interface Callbacks {
        fun onPartial(text: String)
        fun onFinalText(text: String)
        fun onStateChanged(state: State)
        /** [reason] is shown to the person, e.g. "Heard nothing, so I stopped listening." */
        fun onEnded(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val turns = VoiceTurns()
    @Volatile var state = State.IDLE
        private set
    @Volatile var continuous = true
    private var generation = 0          // invalidates callbacks from an abandoned recognizer
    private var restartPending: Runnable? = null

    val active get() = state != State.IDLE

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    private fun set(s: State) { state = s; cb.onStateChanged(s) }

    /** Begin a session (or take the microphone back immediately, e.g. when interrupting speech). */
    fun start(continuous: Boolean) {
        this.continuous = continuous
        if (state == State.IDLE) turns.reset()
        listen()
    }

    /** Called by the activity after the assistant has finished speaking its reply. */
    fun replyFinished(delayMs: Long = 450) {
        if (state == State.IDLE) return
        if (!continuous || !turns.shouldContinue()) { end(if (continuous) "That was a long conversation, so I stopped listening." else ""); return }
        set(State.SPEAKING)
        schedule(delayMs)
    }

    /** A turn started being processed. */
    fun working() { if (state != State.IDLE) set(State.WORKING) }

    fun stop(reason: String = "") = end(reason)

    fun destroy() { cancelRestart(); generation++; recognizer?.destroy(); recognizer = null; state = State.IDLE }

    // ------------------------------------------------------------------------------------------

    private fun schedule(delayMs: Long) {
        cancelRestart()
        val r = Runnable { restartPending = null; if (state != State.IDLE) listen() }
        restartPending = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelRestart() { restartPending?.let { main.removeCallbacks(it) }; restartPending = null }

    private fun end(reason: String) {
        cancelRestart()
        generation++
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        val was = state
        state = State.IDLE
        cb.onStateChanged(State.IDLE)
        if (was != State.IDLE) cb.onEnded(reason)
    }

    private fun listen() {
        cancelRestart()
        if (!isAvailable()) { end("Speech recognition is not available on this phone."); return }
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        val gen = ++generation
        val rec = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (gen == generation) set(State.LISTENING) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (gen != generation) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { cb.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                if (gen != generation) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) { silence(); return }
                turns.onHeard()
                set(State.WORKING)
                cb.onFinalText(text)
            }

            override fun onError(error: Int) {
                if (gen != generation) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> silence()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> schedule(700)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end("I need the microphone permission to listen.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        end("The speech recognizer needs a network or an offline language pack. Install an offline voice pack in system settings, or type instead.")
                    else -> if (turns.onSilence()) end("I could not hear you, so I stopped listening.") else schedule(600)
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        set(State.LISTENING)
        try { rec.startListening(i) } catch (e: Exception) { end("Could not start listening: ${e.message}") }
    }

    private fun silence() {
        if (!continuous) { end("I did not hear anything."); return }
        if (turns.onSilence()) end("Heard nothing, so I stopped listening. Tap VOICE to start again.") else schedule(300)
    }
}
