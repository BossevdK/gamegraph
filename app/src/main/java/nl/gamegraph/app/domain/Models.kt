package nl.gamegraph.app.domain

import kotlinx.serialization.Serializable

/** De smaakassen waarop elke game en elk profiel wordt beoordeeld (0..1). */
enum class Axis(val label: String, val hint: String) {
    EXPLORATION("Exploratie", "Werelden verkennen en dingen ontdekken"),
    COMBAT("Gevecht", "Actie en gevechten staan centraal"),
    STORY("Verhaal", "Sterk verhaal, personages en keuzes"),
    FREEDOM("Vrijheid", "Zelf bepalen wat en hoe je speelt"),
    STRATEGY("Strategie", "Plannen, tactiek en beheer"),
    COOP("Co-op", "Samen spelen met anderen"),
    DIFFICULTY("Moeilijkheid", "Uitdaging en straffende systemen"),
    BUILDS("Character builds", "Karakter en uitrusting uitgebreid aanpassen"),
}

/** Herkomst van gegevens. [checkedAtMillis] is null als het moment onbekend is. */
@Serializable
data class Provenance(val source: String, val checkedAtMillis: Long? = null) {
    companion object {
        const val SEED = "Demo-catalogus (ingebouwd)"
    }
}

/**
 * Een game. Onbekende gegevens zijn null; een nulwaarde (bijv. prijs 0 = gratis) is dus iets anders dan "onbekend".
 */
@Serializable
data class Game(
    val id: String,
    val title: String,
    val developer: String? = null,
    val releaseYear: Int? = null,
    val description: String,
    val genres: Set<String>,
    val mechanics: Set<String> = emptySet(),
    val moods: Set<String> = emptySet(),
    val axes: Map<Axis, Float> = emptyMap(),
    val platforms: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    /** Prijs in eurocenten. null = onbekend, 0 = gratis. */
    val priceCents: Int? = null,
    val storeUrl: String? = null,
    val reviewSummary: String? = null,
    val reviewSource: String? = null,
    val provenance: Provenance,
) {
    val allTags: Set<String> get() = genres + mechanics + moods
    val isFree: Boolean? get() = priceCents?.let { it == 0 }
    val supportsCoop: Boolean get() = modes.any { it.equals("Co-op", ignoreCase = true) }
}

enum class Reaction { NONE, LIKE, DISLIKE }

/** Alles wat de gebruiker lokaal over één game heeft vastgelegd. */
data class UserGame(
    val gameId: String,
    val reaction: Reaction = Reaction.NONE,
    val saved: Boolean = false,
    val favorite: Boolean = false,
    val wishlist: Boolean = false,
    val rating: Int? = null,
    val lastViewedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    val hasDecision: Boolean get() = reaction != Reaction.NONE || saved || favorite || wishlist || rating != null
}

enum class FeedbackType(val weight: Float, val label: String) {
    LIKE(1.0f, "Leuk"),
    DISLIKE(-1.0f, "Niet interessant"),
    SAVE(0.5f, "Bewaard"),
    FAVORITE(1.5f, "Favoriet"),
    WISHLIST(0.7f, "Verlanglijst"),
    RATING(0f, "Beoordeeld"),
}

data class FeedbackEvent(
    val id: Long = 0,
    val gameId: String,
    val type: FeedbackType,
    /** Effectief gewicht van dit signaal (kan negatief zijn). */
    val value: Float,
    val createdAt: Long,
)

/** Hoe zeker een aanbeveling is. Onder [INSUFFICIENT] tonen we geen percentage. */
enum class MatchLevel(val label: String) {
    INSUFFICIENT("Nog te weinig gegevens"),
    LOW("Matige overeenkomst"),
    GOOD("Goede overeenkomst"),
    STRONG("Sterke overeenkomst"),
}

data class Recommendation(
    val game: Game,
    val score: Float,
    val level: MatchLevel,
    /** Alleen gevuld als er genoeg gegevens zijn. */
    val percent: Int?,
    val reasons: List<String>,
    val novelty: Float,
)

enum class PlayStyle(val label: String) {
    ANY("Alle"),
    CALM("Rustig"),
    ACTION("Actie"),
    STRATEGIC("Strategisch"),
    NARRATIVE("Verhalend"),
    TOGETHER("Samen spelen");

    fun matches(game: Game): Boolean {
        val a = game.axes
        fun v(x: Axis) = a[x] ?: 0f
        return when (this) {
            ANY -> true
            CALM -> v(Axis.COMBAT) <= 0.4f && v(Axis.DIFFICULTY) <= 0.55f
            ACTION -> v(Axis.COMBAT) >= 0.7f
            STRATEGIC -> v(Axis.STRATEGY) >= 0.6f
            NARRATIVE -> v(Axis.STORY) >= 0.7f
            TOGETHER -> v(Axis.COOP) >= 0.6f || game.supportsCoop
        }
    }
}

enum class PriceFilter(val label: String) { ANY("Alle"), FREE("Gratis"), PAID("Niet gratis") }

data class DeckFilter(
    val genre: String? = null,
    val platform: String? = null,
    val price: PriceFilter = PriceFilter.ANY,
    val playStyle: PlayStyle = PlayStyle.ANY,
) {
    val isActive: Boolean get() = genre != null || platform != null || price != PriceFilter.ANY || playStyle != PlayStyle.ANY

    fun accepts(game: Game): Boolean =
        (genre == null || genre in game.genres) &&
            (platform == null || platform in game.platforms) &&
            when (price) {
                PriceFilter.ANY -> true
                PriceFilter.FREE -> game.isFree == true
                // "Niet gratis" = alles waarvan niet bevestigd is dat het gratis is (prijs > 0 of onbekend).
                PriceFilter.PAID -> game.isFree != true
            } &&
            playStyle.matches(game)
}
