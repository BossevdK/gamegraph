package nl.gamegraph.app.domain

import kotlin.math.abs

/** Vertaalt de opgeslagen reactie van de gebruiker op een game naar één leersignaal. */
object FeedbackProcessor {
    const val MIN_SIGNAL = -2f
    const val MAX_SIGNAL = 3f

    fun ratingWeight(rating: Int): Float = ((rating.coerceIn(1, 5) - 3) / 2f) * 1.2f

    /** Idempotent: het signaal hangt alleen af van de huidige staat, niet van hoe vaak er is getikt. */
    fun signalFor(u: UserGame): Float {
        var s = 0f
        when (u.reaction) {
            Reaction.LIKE -> s += FeedbackType.LIKE.weight
            Reaction.DISLIKE -> s += FeedbackType.DISLIKE.weight
            Reaction.NONE -> Unit
        }
        if (u.favorite) s += FeedbackType.FAVORITE.weight
        if (u.saved) s += FeedbackType.SAVE.weight
        if (u.wishlist) s += FeedbackType.WISHLIST.weight
        u.rating?.let { s += ratingWeight(it) }
        return s.coerceIn(MIN_SIGNAL, MAX_SIGNAL)
    }
}

data class Contribution(val game: Game, val signal: Float)

/**
 * Het smaakprofiel (My GameDNA). Wordt opgebouwd uit de reacties van de gebruiker en handmatig gezette voorkeuren.
 * [apply] werkt het profiel incrementeel bij; [from] bouwt het in één keer opnieuw op met hetzelfde resultaat.
 */
data class TasteProfile(
    val tagWeights: Map<String, Float> = emptyMap(),
    private val axisNumerator: Map<Axis, Float> = emptyMap(),
    private val axisDenominator: Map<Axis, Float> = emptyMap(),
    val manualAxes: Map<Axis, Float> = emptyMap(),
    val contributions: List<Contribution> = emptyList(),
) {
    /** Aantal onafhankelijke aanwijzingen waarop het profiel rust. */
    val evidence: Int get() = contributions.count { abs(it.signal) > 0.01f } + manualAxes.size

    val liked: List<Contribution> get() = contributions.filter { it.signal > 0.01f }
    val disliked: List<Contribution> get() = contributions.filter { it.signal < -0.01f }

    /** Door gedrag geleerde voorkeur voor een as; null als er nog niets te leren valt. */
    fun learnedAxis(axis: Axis): Float? {
        val d = axisDenominator[axis] ?: return null
        if (d <= 0f) return null
        return ((axisNumerator[axis] ?: 0f) / d).coerceIn(0f, 1f)
    }

    /** Effectieve voorkeur: handmatig en geleerd gemengd; null = onbekend. */
    fun axisPreference(axis: Axis): Float? {
        val manual = manualAxes[axis]
        val learned = learnedAxis(axis)
        return when {
            manual != null && learned != null -> manual * 0.6f + learned * 0.4f
            manual != null -> manual
            else -> learned
        }
    }

    fun withManual(axes: Map<Axis, Float>): TasteProfile = copy(manualAxes = axes)

    /** Verwerkt één signaal voor één game. [signal] is al afgeleid met [FeedbackProcessor]. */
    fun apply(game: Game, signal: Float): TasteProfile {
        if (abs(signal) < 0.01f) return this
        val tags = tagWeights.toMutableMap()
        for (t in game.allTags) tags[t] = ((tags[t] ?: 0f) + signal * 0.5f).coerceIn(-4f, 4f)
        val num = axisNumerator.toMutableMap()
        val den = axisDenominator.toMutableMap()
        if (signal > 0f) {
            for ((axis, v) in game.axes) {
                num[axis] = (num[axis] ?: 0f) + signal * v
                den[axis] = (den[axis] ?: 0f) + signal
            }
        }
        return copy(
            tagWeights = tags,
            axisNumerator = num,
            axisDenominator = den,
            contributions = (contributions + Contribution(game, signal)),
        )
    }

    companion object {
        fun from(userGames: Collection<UserGame>, manual: Map<Axis, Float>, catalog: Map<String, Game>): TasteProfile {
            var profile = TasteProfile(manualAxes = manual)
            // Vaste volgorde, zodat het resultaat deterministisch is.
            for (u in userGames.sortedBy { it.gameId }) {
                val game = catalog[u.gameId] ?: continue
                profile = profile.apply(game, FeedbackProcessor.signalFor(u))
            }
            return profile
        }
    }
}
