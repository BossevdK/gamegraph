package nl.gamegraph.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.FeedbackEvent
import nl.gamegraph.app.domain.FeedbackProcessor
import nl.gamegraph.app.domain.FeedbackType
import nl.gamegraph.app.domain.Game
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.domain.UserGame

/** Een vervangbare bron van gamegegevens. Voeg een nieuwe implementatie toe en registreer die in [AppContainer]. */
interface GameSource {
    val name: String
    suspend fun load(): List<Game>
}

class SeedGameSource : GameSource {
    override val name = "Demo-catalogus"
    override suspend fun load(): List<Game> = SeedCatalog.games
}

@Serializable
data class ExportUserGame(
    val gameId: String,
    val reaction: String = "NONE",
    val saved: Boolean = false,
    val favorite: Boolean = false,
    val wishlist: Boolean = false,
    val rating: Int? = null,
    val lastViewedAt: Long? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class ExportEvent(val gameId: String, val type: String, val value: Float, val createdAt: Long)

@Serializable
data class ExportFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val userGames: List<ExportUserGame>,
    val events: List<ExportEvent>,
    val dnaPrefs: Map<String, Float>,
    val profileResetAt: Long = 0,
) {
    companion object {
        const val FORMAT = "gamegraph-export"
    }
}

sealed interface ImportResult {
    data class Success(val games: Int, val events: Int) : ImportResult
    data class Invalid(val reason: String) : ImportResult
}

class GameRepository(
    private val db: GameDatabase,
    private val sources: List<GameSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    suspend fun loadBaseCatalog(): List<Game> = sources.flatMap { runCatching { it.load() }.getOrDefault(emptyList()) }

    /** Basiscatalogus plus eventueel gecachete games (een gecachete game met dezelfde id wint). Beschadigde rijen worden overgeslagen. */
    fun observeCachedGames(): Flow<List<Game>> = db.cachedGames().observeAll().map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(Game.serializer(), it.json) }.getOrNull() }
    }

    fun observeUserGames(): Flow<Map<String, UserGame>> = db.userGames().observeAll().map { rows ->
        rows.associate { it.gameId to it.toDomain() }
    }

    fun observeEvents(): Flow<List<FeedbackEvent>> = db.feedback().observeAll().map { rows ->
        rows.mapNotNull { it.toDomain() }
    }

    fun observeManualAxes(): Flow<Map<Axis, Float>> = db.dnaPrefs().observeAll().map { rows ->
        rows.mapNotNull { row -> Axis.entries.firstOrNull { it.name == row.axis }?.let { it to row.value.coerceIn(0f, 1f) } }.toMap()
    }

    /** Alle wijzigingen aan één game in één transactie, zodat er nooit een half resultaat achterblijft. */
    private suspend fun update(gameId: String, event: (UserGame, UserGame) -> FeedbackEvent?, change: (UserGame) -> UserGame) {
        db.withTransaction {
            val now = clock()
            val old = db.userGames().get(gameId)?.toDomain() ?: UserGame(gameId)
            val new = change(old).copy(updatedAt = now)
            db.userGames().upsert(new.toEntity())
            event(old, new)?.let { db.feedback().insert(it.copy(createdAt = now).toEntity()) }
        }
    }

    private fun ev(gameId: String, type: FeedbackType, value: Float = type.weight) =
        FeedbackEvent(gameId = gameId, type = type, value = value, createdAt = 0)

    suspend fun setReaction(gameId: String, reaction: Reaction) = update(gameId,
        event = { old, new ->
            if (new.reaction != old.reaction && new.reaction != Reaction.NONE)
                ev(gameId, if (new.reaction == Reaction.LIKE) FeedbackType.LIKE else FeedbackType.DISLIKE) else null
        },
        change = { it.copy(reaction = reaction) })

    suspend fun setSaved(gameId: String, on: Boolean) = update(gameId,
        event = { old, new -> if (new.saved && !old.saved) ev(gameId, FeedbackType.SAVE) else null },
        change = { it.copy(saved = on) })

    suspend fun setFavorite(gameId: String, on: Boolean) = update(gameId,
        event = { old, new -> if (new.favorite && !old.favorite) ev(gameId, FeedbackType.FAVORITE) else null },
        change = { it.copy(favorite = on) })

    suspend fun setWishlist(gameId: String, on: Boolean) = update(gameId,
        event = { old, new -> if (new.wishlist && !old.wishlist) ev(gameId, FeedbackType.WISHLIST) else null },
        change = { it.copy(wishlist = on) })

    suspend fun setRating(gameId: String, rating: Int?) = update(gameId,
        event = { old, new ->
            if (new.rating != null && new.rating != old.rating)
                ev(gameId, FeedbackType.RATING, FeedbackProcessor.ratingWeight(new.rating)) else null
        },
        change = { it.copy(rating = rating?.coerceIn(1, 5)) })

    /** Registreert alleen een bekeken-moment; dit is geen leersignaal. */
    suspend fun markViewed(gameId: String) {
        db.withTransaction {
            val now = clock()
            val old = db.userGames().get(gameId)?.toDomain() ?: UserGame(gameId)
            // updatedAt blijft gelijk: alleen echte beslissingen tellen als verandering.
            db.userGames().upsert(old.copy(lastViewedAt = now).toEntity())
        }
    }

    suspend fun setAxis(axis: Axis, value: Float?) {
        if (value == null) db.dnaPrefs().delete(axis.name)
        else db.dnaPrefs().upsert(DnaPrefEntity(axis.name, value.coerceIn(0f, 1f)))
    }

    /** Wist handmatige voorkeuren en leerhistorie. De bibliotheek (favorieten, wishlist, beoordelingen) blijft staan. */
    suspend fun resetProfile() {
        db.withTransaction {
            db.dnaPrefs().clear()
            db.feedback().clear()
        }
    }

    suspend fun clearCachedGames() = db.cachedGames().clear()

    suspend fun saveCachedGames(games: List<Game>, source: String) {
        val now = clock()
        db.cachedGames().upsertAll(games.map {
            CachedGameEntity(it.id, json.encodeToString(Game.serializer(), it), source, now)
        })
    }

    suspend fun export(profileResetAt: Long): String {
        val file = ExportFile(
            exportedAt = clock(),
            userGames = db.userGames().getAll().map {
                ExportUserGame(it.gameId, it.reaction, it.saved, it.favorite, it.wishlist, it.rating, it.lastViewedAt, it.updatedAt)
            },
            events = db.feedback().getAll().map { ExportEvent(it.gameId, it.type, it.value, it.createdAt) },
            dnaPrefs = db.dnaPrefs().getAll().associate { it.axis to it.value },
            profileResetAt = profileResetAt,
        )
        return json.encodeToString(ExportFile.serializer(), file)
    }

    /** Valideert eerst alles en vervangt dan de gebruikersgegevens in één transactie. Bij een fout blijft alles zoals het was. */
    suspend fun import(text: String): Pair<ImportResult, Long> {
        val file = try {
            json.decodeFromString(ExportFile.serializer(), text)
        } catch (e: Exception) {
            return ImportResult.Invalid("Dit bestand is geen geldige GameGraph-export.") to 0L
        }
        if (file.format != ExportFile.FORMAT) return ImportResult.Invalid("Dit bestand is geen GameGraph-export.") to 0L
        if (file.version > 1) return ImportResult.Invalid("Deze export komt uit een nieuwere versie van de app.") to 0L
        if (file.userGames.any { it.gameId.isBlank() || (it.rating != null && it.rating !in 1..5) } ||
            file.userGames.any { runCatching { Reaction.valueOf(it.reaction) }.isFailure } ||
            file.events.any { e -> FeedbackType.entries.none { it.name == e.type } } ||
            file.dnaPrefs.any { (k, v) -> Axis.entries.none { it.name == k } || v !in 0f..1f }
        ) return ImportResult.Invalid("De export bevat ongeldige gegevens en is niet geïmporteerd.") to 0L

        db.withTransaction {
            db.userGames().clear(); db.feedback().clear(); db.dnaPrefs().clear()
            db.userGames().upsertAll(file.userGames.map {
                UserGameEntity(it.gameId, it.reaction, it.saved, it.favorite, it.wishlist, it.rating, it.lastViewedAt, it.updatedAt)
            })
            db.feedback().insertAll(file.events.map { FeedbackEventEntity(0, it.gameId, it.type, it.value, it.createdAt) })
            db.dnaPrefs().upsertAll(file.dnaPrefs.map { DnaPrefEntity(it.key, it.value) })
        }
        return ImportResult.Success(file.userGames.size, file.events.size) to file.profileResetAt
    }

    suspend fun deleteAllUserData() {
        db.withTransaction {
            db.userGames().clear(); db.feedback().clear(); db.dnaPrefs().clear()
        }
    }
}

internal fun UserGameEntity.toDomain() = UserGame(
    gameId = gameId,
    reaction = runCatching { Reaction.valueOf(reaction) }.getOrDefault(Reaction.NONE),
    saved = saved, favorite = favorite, wishlist = wishlist, rating = rating?.coerceIn(1, 5),
    lastViewedAt = lastViewedAt, updatedAt = updatedAt,
)

internal fun UserGame.toEntity() = UserGameEntity(
    gameId = gameId, reaction = reaction.name, saved = saved, favorite = favorite, wishlist = wishlist,
    rating = rating, lastViewedAt = lastViewedAt, updatedAt = updatedAt,
)

internal fun FeedbackEventEntity.toDomain(): FeedbackEvent? {
    val t = FeedbackType.entries.firstOrNull { it.name == type } ?: return null
    return FeedbackEvent(id, gameId, t, value, createdAt)
}

internal fun FeedbackEvent.toEntity() = FeedbackEventEntity(id, gameId, type.name, value, createdAt)
