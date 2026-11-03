package app.novelspeak

/**
 * Pure decisions for the Reader's opportunistic preroll.
 *
 * The Reader holds AudioTrack playback while it renders a small lead. The lead is bounded both
 * by time (so a long sentence does not trigger a whole paragraph) and by sentence count (so
 * short sentences cannot grow the queue without bound). A sentence is queued only after the
 * previous one has rendered; that keeps the generator from running far ahead of a lead whose
 * duration we do not know yet.
 */
object PrerollPolicy {

    /**
     * True when the Reader should queue [submitted] + 1.
     *
     * @param submitted index of the last queued sentence, or cursor - 1 when nothing is queued.
     * @param cursor first sentence that will be played.
     * @param total number of sentences in the reader queue.
     * @param bufferedSeconds audio already queued in PcmPlayer.
     * @param lastRendered true when [submitted] has reported its rendered callback (audio or
     *   skipped); the next sentence is not queued before that.
     * @param maxSentences maximum number of sentences in the preroll window.
     * @param targetSeconds stop adding once this much audio is buffered.
     */
    fun shouldQueueNext(
        submitted: Int,
        cursor: Int,
        total: Int,
        bufferedSeconds: Float,
        lastRendered: Boolean,
        maxSentences: Int,
        targetSeconds: Float
    ): Boolean {
        if (total <= 0) return false
        if (cursor < 0 || cursor >= total) return false
        if (maxSentences <= 0) return false
        if (submitted < cursor) return true
        if (submitted >= total - 1) return false
        if (submitted - cursor + 1 >= maxSentences) return false
        if (!lastRendered) return false
        if (bufferedSeconds >= targetSeconds) return false
        return true
    }

    /** Playback may begin only after the user asked for it and the cursor clip has audio. */
    fun canStart(playRequested: Boolean, playing: Boolean, cursorRendered: Boolean): Boolean =
        playRequested && !playing && cursorRendered
}
