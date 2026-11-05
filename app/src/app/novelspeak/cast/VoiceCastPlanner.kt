package app.novelspeak.cast

import app.novelspeak.VoiceOption
import app.novelspeak.VoiceCatalog

/**
 * Assigns voices to the characters who actually speak.
 *
 * Rules:
 *  - the narrator's voice is reserved and never given to a character;
 *  - characters are ranked by dialogue line count;
 *  - gender-matched voices are preferred, then the best tier;
 *  - a saved non-locked voice is kept when it is still free;
 *  - a user-locked voice is always kept;
 *  - when the cast outgrows the pool, voices are reused (reported as [Assignment.reused]).
 */
object VoiceCastPlanner {

    data class Assignment(
        val memberId: String,
        val voiceId: String,
        val reused: Boolean
    )

    fun plan(
        cast: BookCast,
        available: List<VoiceOption> = VoiceCatalog.SUPERTONIC
    ): List<Assignment> {
        val narrator = cast.narratorVoiceId
        val pool = available.filter { it.id != narrator }
        if (pool.isEmpty()) return emptyList()

        val used = LinkedHashSet<String>()
        val out = ArrayList<Assignment>()
        val speakers = cast.members
            .filter { it.dialogueLines > 0 }
            .sortedWith(compareByDescending<CastMember> { it.dialogueLines }.thenBy { it.name })

        for (member in speakers) {
            val saved = member.voiceId
            if (saved != null && saved != narrator && pool.any { it.id == saved } &&
                (member.userLocked || saved !in used)
            ) {
                used.add(saved)
                out.add(Assignment(member.id, saved, false))
                continue
            }

            val free = pool.filter { it.id !in used }
            val chosen = pick(free, member.gender) ?: pick(pool, member.gender) ?: continue
            val reused = chosen.id in used
            used.add(chosen.id)
            out.add(Assignment(member.id, chosen.id, reused))
        }
        return out
    }

    private fun pick(list: List<VoiceOption>, gender: Gender): VoiceOption? {
        if (list.isEmpty()) return null
        val wanted = when (gender) {
            Gender.FEMALE -> "female"
            Gender.MALE -> "male"
            Gender.UNKNOWN -> ""
        }
        val matching = if (wanted.isEmpty()) list else list.filter { it.gender == wanted }
        val candidates = matching.ifEmpty { list }
        return candidates.sortedWith(
            compareBy<VoiceOption> { tierRank(it.tier) }.thenBy { it.label }
        ).firstOrNull()
    }

    private fun tierRank(tier: String): Int = when (tier) {
        "quality" -> 0
        "primary" -> 1
        "standard" -> 2
        "fallback" -> 3
        else -> 4
    }
}
