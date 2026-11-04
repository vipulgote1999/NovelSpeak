package app.novelspeak

/**
 * Pure decision table for one AudioTrack.write() attempt.
 *
 * Android documents that a blocking write returns a **short transfer count** when the track is
 * paused/stopped or when another thread interrupts it with pause()/flush(). Treating that short
 * count as a fatal writer failure is what left the PCM writer thread dead after a pause, so the
 * policy distinguishes "the owner paused us" (abort this chunk, keep the thread) from a real
 * AudioTrack error.
 */
object AudioWritePolicy {

    enum class Outcome {
        /** The requested bytes are all queued. */
        COMPLETE,

        /** Some bytes were queued; keep writing the remainder. */
        PROGRESS,

        /** The track is paused/flushed; wait for resume or for the epoch to change. */
        WAIT,

        /** stop()/reset() invalidated this chunk; discard it and keep the writer alive. */
        ABORT,

        /** AudioTrack returned an error; mark the track broken and fail the sentence. */
        ERROR
    }

    fun classify(
        returned: Int,
        requested: Int,
        written: Int,
        stopped: Boolean,
        epochChanged: Boolean
    ): Outcome {
        if (stopped || epochChanged) return Outcome.ABORT
        if (returned < 0) return Outcome.ERROR
        val total = written + returned
        if (total >= requested) return Outcome.COMPLETE
        if (returned == 0) return Outcome.WAIT
        return Outcome.PROGRESS
    }
}
