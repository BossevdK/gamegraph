package nl.gamegraph.app.domain

import kotlin.math.abs

/** Soort verbinding tussen twee games; bepaalt de kleur van een lijn in de graph. */
enum class LinkKind(val label: String) {
    GENRE("Genre"),
    MECHANIC("Gameplay"),
    MOOD("Sfeer"),
    DEVELOPER("Ontwikkelaar"),
    STYLE("Speelstijl"),
}

data class Reason(val kind: LinkKind, val shared: List<String>) {
    fun text(): String = when (kind) {
        LinkKind.DEVELOPER -> "Zelfde ontwikkelaar: ${shared.joinToString()}"
        LinkKind.STYLE -> "Zelfde speelstijl: ${shared.joinToString()}"
        else -> "${kind.label}: ${shared.joinToString()}"
    }
}

data class Similarity(val score: Float, val reasons: List<Reason>, val dominant: LinkKind?) {
    fun explain(): String = if (reasons.isEmpty()) "Geen duidelijke overeenkomst" else reasons.joinToString(" · ") { it.text() }
}

/** Gewogen overeenkomst tussen twee games. Deterministisch en zonder externe diensten. */
object GameSimilarity {
    const val W_GENRE = 0.30f
    const val W_MECHANIC = 0.25f
    const val W_MOOD = 0.15f
    const val W_DEVELOPER = 0.05f
    const val W_STYLE = 0.25f

    /** Een as telt als "kenmerkend" voor een game vanaf deze waarde. */
    const val STRONG_AXIS = 0.7f

    fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0f else inter.toFloat() / union
    }

    /** 0..1; alleen assen die beide games kennen tellen mee. Een vlakke basislijn wordt eruit gehaald. */
    fun axisSimilarity(a: Map<Axis, Float>, b: Map<Axis, Float>): Float {
        val common = a.keys.intersect(b.keys)
        if (common.isEmpty()) return 0f
        val dist = common.sumOf { abs(a.getValue(it) - b.getValue(it)).toDouble() }.toFloat() / common.size
        return ((1f - dist - 0.5f) * 2f).coerceIn(0f, 1f)
    }

    fun compare(a: Game, b: Game): Similarity {
        val genres = a.genres.intersect(b.genres).sorted()
        val mechanics = a.mechanics.intersect(b.mechanics).sorted()
        val moods = a.moods.intersect(b.moods).sorted()
        val sameDev = a.developer != null && a.developer.equals(b.developer, ignoreCase = true)
        val styleAxes = Axis.entries.filter { (a.axes[it] ?: 0f) >= STRONG_AXIS && (b.axes[it] ?: 0f) >= STRONG_AXIS }
        val axisSim = axisSimilarity(a.axes, b.axes)

        val genreC = W_GENRE * jaccard(a.genres, b.genres)
        val mechC = W_MECHANIC * jaccard(a.mechanics, b.mechanics)
        val moodC = W_MOOD * jaccard(a.moods, b.moods)
        val devC = if (sameDev) W_DEVELOPER else 0f
        val styleC = W_STYLE * axisSim
        val score = (genreC + mechC + moodC + devC + styleC).coerceIn(0f, 1f)

        val reasons = buildList {
            if (genres.isNotEmpty()) add(Reason(LinkKind.GENRE, genres))
            if (mechanics.isNotEmpty()) add(Reason(LinkKind.MECHANIC, mechanics))
            if (moods.isNotEmpty()) add(Reason(LinkKind.MOOD, moods))
            if (sameDev) add(Reason(LinkKind.DEVELOPER, listOf(a.developer!!)))
            if (styleAxes.isNotEmpty() && axisSim > 0f) add(Reason(LinkKind.STYLE, styleAxes.map { it.label }))
        }
        val dominant = listOf(
            LinkKind.GENRE to genreC, LinkKind.MECHANIC to mechC, LinkKind.MOOD to moodC,
            LinkKind.DEVELOPER to devC, LinkKind.STYLE to styleC,
        ).filter { (kind, c) -> c > 0f && reasons.any { it.kind == kind } }.maxByOrNull { it.second }?.first
        return Similarity(score, reasons, dominant)
    }
}
