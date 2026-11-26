package io.github.mangi.eta.agent.voice.asr

/** Lightweight call-only endpoint fallback; never ends idle audio as an utterance. */
internal class CallSilenceDetector(private val silenceMs: Int) {
    enum class Event { STARTED, ENDED }

    private var noise = 0.007f
    private var consecutive = 0
    private var lastVoicedAt: Long? = null
    private var ended = false

    fun accept(level: Float, nowMs: Long): Event? {
        if (ended || !level.isFinite()) return null
        val voiced = level >= maxOf(0.035f, noise * 3.5f)
        if (lastVoicedAt == null) {
            consecutive = if (voiced) consecutive + 1 else 0
            if (consecutive >= 2 || level >= maxOf(0.12f, noise * 6f)) {
                lastVoicedAt = nowMs
                return Event.STARTED
            }
            if (!voiced) noise = noise * 0.9f + level.coerceIn(0f, 0.035f) * 0.1f
        } else if (voiced) {
            lastVoicedAt = nowMs
        } else if (nowMs - checkNotNull(lastVoicedAt) >= silenceMs) {
            ended = true
            return Event.ENDED
        }
        return null
    }
}
