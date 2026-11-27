package app.novelspeak

/**
 * Turns the reader's state callbacks into local reading-time deltas.
 *
 * Kept Android-free so the pause/resume maths is host-testable. A pause must not become
 * "reading time": the previous per-section timer kept its start timestamp across a pause, so
 * resuming after a long break credited up to two minutes that were never read.
 */
class ReadingTick {

    data class Delta(val seconds: Int, val sentences: Int)

    private var lastCursor = -1
    private var lastAtMs = 0L
    private var ticking = false

    /**
     * Call for every reader state change. Returns the time and sentences that became complete
     * since the previous call: time is credited on pause/finish, a sentence only when the cursor
     * advances by exactly one (a seek is not a read sentence).
     */
    fun onState(playing: Boolean, total: Int, cursor: Int, nowMs: Long): Delta {
        if (total <= 0) {
            ticking = false
            lastAtMs = 0L
            lastCursor = cursor
            return Delta(0, 0)
        }
        if (!playing) {
            val delta = if (ticking) Delta(elapsedSeconds(nowMs), 0) else Delta(0, 0)
            ticking = false
            lastAtMs = 0L
            lastCursor = cursor
            return delta
        }
        if (!ticking) {
            ticking = true
            lastAtMs = nowMs
            lastCursor = cursor
            return Delta(0, 0)
        }
        if (cursor == lastCursor) return Delta(0, 0)
        val delta = Delta(
            elapsedSeconds(nowMs),
            if (cursor == lastCursor + 1) 1 else 0
        )
        lastAtMs = nowMs
        lastCursor = cursor
        return delta
    }

    private fun elapsedSeconds(nowMs: Long): Int {
        if (lastAtMs <= 0L) return 0
        val millis = (nowMs - lastAtMs).coerceAtLeast(0L)
        return (millis / 1000L).coerceAtMost(MAX_SECONDS.toLong()).toInt()
    }

    companion object {
        /** A longer gap is a stall or a missed callback, not a single sentence. */
        const val MAX_SECONDS = 120
    }
}
