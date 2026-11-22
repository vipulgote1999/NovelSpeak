package app.novelspeak

/**
 * The PcmPlayer drain watchdog policy.
 *
 * A deliberate preroll hold is not a stalled playback. Counting that time against the
 * watchdog used to let a loaded-but-unplayed chapter reach the 120 s cap, report the
 * sentence as heard, and advance the cursor with no audio. Only an unheld drain can
 * time out.
 */
object DrainGuard {
    fun timedOut(elapsedMs: Long, maxMs: Long, held: Boolean): Boolean =
        !held && elapsedMs >= maxMs
}
