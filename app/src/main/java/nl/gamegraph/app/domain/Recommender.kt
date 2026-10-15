package nl.gamegraph.app.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Eerste versie van het aanbevelingsmodel: gewogen, deterministisch en zonder externe AI-dienst.
 *
 * Onderdelen van de score:
 *  - affiniteit: hoe positief de kenmerken van de game in het profiel wegen;
 *  - smaakassen: hoe dicht de game bij de (handmatige en geleerde) voorkeuren ligt;
 *  - lijkt op favorieten: overeenkomst met games die de gebruiker leuk vond;
 *  - ontdekking: aandeel genres dat de gebruiker nog niet kent (voorkomt dat alleen hetzelfde genre terugkomt);
 *  - straf voor lijken op afgewezen games.
 */
object Recommender {
    const val W_AFFINITY = 0.30f
    const val W_AXES = 0.25f
    const val W_FAVORITES = 0.25f
    const val W_NOVELTY = 0.20f
    const val W_DISLIKE = 0.35f

    /** Onder dit aantal aanwijzingen tonen we geen kwalitatief oordeel. */
    const val MIN_EVIDENCE_FOR_LEVEL = 2

    /** Onder dit aantal aanwijzingen tonen we geen percentage (dat zou misleidend zijn). */
    const val MIN_EVIDENCE_FOR_PERCENT = 5

    const val STRONG_THRESHOLD = 0.62f
    const val GOOD_THRESHOLD = 0.50f

    private fun squash(x: Float): Float = x / (1f + abs(x))

    fun score(game: Game, profile: TasteProfile): Recommendation {
        val evidence = profile.evidence

        // Affiniteit voor kenmerken.
        val tags = game.allTags
        val affinity = if (profile.tagWeights.isEmpty() || tags.isEmpty()) {
            0.5f
        } else {
            val avg = tags.sumOf { (profile.tagWeights[it] ?: 0f).toDouble() }.toFloat() / tags.size
            (squash(avg * 1.5f) + 1f) / 2f
        }

        // Smaakassen.
        val known = Axis.entries.mapNotNull { axis ->
            val pref = profile.axisPreference(axis) ?: return@mapNotNull null
            val value = game.axes[axis] ?: return@mapNotNull null
            axis to (1f - abs(value - pref))
        }
        val axisFit = if (known.isEmpty()) 0.5f else known.sumOf { it.second.toDouble() }.toFloat() / known.size

        // Overeenkomst met favorieten en afgewezen games.
        val likedSims = profile.liked.map { it to GameSimilarity.compare(game, it.game) }
        val favTop = likedSims
            .map { (c, s) -> s.score * (c.signal / 1.5f).coerceIn(0.3f, 1f) }
            .sortedDescending().take(2)
        val favSim = if (favTop.isEmpty()) 0f else (favTop.average().toFloat() / 0.5f).coerceIn(0f, 1f)
        val dislikeSims = profile.disliked.map { it to GameSimilarity.compare(game, it.game) }
        val dislikeSim = dislikeSims.maxOfOrNull { it.second.score } ?: 0f

        // Ontdekking.
        val knownGenres = profile.liked.flatMap { it.game.genres }.toSet()
        val novelty = when {
            game.genres.isEmpty() -> 0f
            knownGenres.isEmpty() -> 0.5f
            else -> game.genres.count { it !in knownGenres }.toFloat() / game.genres.size
        }

        val raw = (W_AFFINITY * affinity + W_AXES * axisFit + W_FAVORITES * favSim + W_NOVELTY * novelty -
            W_DISLIKE * dislikeSim).coerceIn(0f, 1f)

        val level = when {
            evidence < MIN_EVIDENCE_FOR_LEVEL -> MatchLevel.INSUFFICIENT
            raw >= STRONG_THRESHOLD -> MatchLevel.STRONG
            raw >= GOOD_THRESHOLD -> MatchLevel.GOOD
            else -> MatchLevel.LOW
        }
        val percent = if (evidence >= MIN_EVIDENCE_FOR_PERCENT) (raw * 100f).roundToInt() else null

        val reasons = buildList {
            likedSims.filter { it.second.score >= 0.30f }
                .sortedByDescending { it.second.score }.take(2)
                .forEach { (c, s) ->
                    val shared = s.reasons.firstOrNull()?.shared?.take(3)?.joinToString()
                    add("Lijkt op ${c.game.title}" + (shared?.let { " ($it)" } ?: ""))
                }
            known.filter { (axis, fit) -> fit >= 0.8f && (game.axes[axis] ?: 0f) >= 0.6f }
                .sortedByDescending { it.second }.take(2)
                .forEach { (axis, _) -> add("Past bij je voorkeur voor ${axis.label.lowercase()}") }
            if (evidence >= MIN_EVIDENCE_FOR_LEVEL && novelty >= 0.5f && knownGenres.isNotEmpty()) {
                val fresh = game.genres.filter { it !in knownGenres }.sorted().take(2).joinToString()
                add("Nieuw terrein voor je: $fresh")
            }
            dislikeSims.filter { it.second.score >= 0.45f }.maxByOrNull { it.second.score }?.let { (c, _) ->
                add("Let op: lijkt op ${c.game.title}, dat je niet interessant vond")
            }
            if (isEmpty()) {
                add(
                    if (evidence < MIN_EVIDENCE_FOR_LEVEL) "Gevarieerde kennismaking: jouw reacties maken de aanbevelingen persoonlijker"
                    else "Bewuste ontdekking buiten je bekende voorkeuren"
                )
            }
        }
        return Recommendation(game, raw, level, percent, reasons, novelty)
    }

    /** Alle kandidaten gesorteerd op score; bij gelijke score op titel, zodat de volgorde stabiel is. */
    fun rank(candidates: Collection<Game>, profile: TasteProfile): List<Recommendation> =
        candidates.map { score(it, profile) }
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.game.title })

    /**
     * Bouwt een gevarieerde stapel: hoge score, maar niet steeds dezelfde soort game achter elkaar.
     * Elke vierde kaart is een bewuste ontdekking (hoogste noviteit uit de betere helft).
     */
    fun buildDeck(candidates: Collection<Game>, profile: TasteProfile, limit: Int = 20): List<Recommendation> {
        val ranked = rank(candidates, profile).take(80).toMutableList()
        val picked = mutableListOf<Recommendation>()
        while (picked.size < limit && ranked.isNotEmpty()) {
            val next = if (picked.size % 4 == 3 && profile.evidence >= MIN_EVIDENCE_FOR_LEVEL) {
                ranked.take((ranked.size + 1) / 2).maxWithOrNull(
                    compareBy<Recommendation> { it.novelty }.thenBy { it.score }
                )!!
            } else {
                val recent = picked.takeLast(3)
                ranked.maxWithOrNull(compareBy<Recommendation> { r ->
                    val penalty = recent.maxOfOrNull { GameSimilarity.compare(r.game, it.game).score } ?: 0f
                    r.score - 0.25f * penalty
                }.thenByDescending { it.game.title })!!
            }
            picked += next
            ranked.remove(next)
        }
        return picked
    }
}
