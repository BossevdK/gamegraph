package nl.gamegraph.app

import nl.gamegraph.app.data.SeedCatalog
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.DeckFilter
import nl.gamegraph.app.domain.FeedbackProcessor
import nl.gamegraph.app.domain.GameSimilarity
import nl.gamegraph.app.domain.GraphBuilder
import nl.gamegraph.app.domain.LinkKind
import nl.gamegraph.app.domain.MatchLevel
import nl.gamegraph.app.domain.PlayStyle
import nl.gamegraph.app.domain.PriceFilter
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.domain.Recommender
import nl.gamegraph.app.domain.TasteProfile
import nl.gamegraph.app.domain.UserGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationTest {
    private val catalog = SeedCatalog.games
    private val byId = catalog.associateBy { it.id }
    private fun game(id: String) = byId.getValue(id)

    // ---- Catalogus ----

    @Test fun seedCatalogIsConsistent() {
        assertTrue(catalog.size >= 30)
        assertEquals("ids moeten uniek zijn", catalog.size, catalog.map { it.id }.toSet().size)
        catalog.forEach { g ->
            assertEquals("${g.id} moet alle ${Axis.entries.size} assen hebben", Axis.entries.size, g.axes.size)
            assertTrue("${g.id} assen binnen 0..1", g.axes.values.all { it in 0f..1f })
            assertTrue("${g.id} heeft genres", g.genres.isNotEmpty())
            assertNull("de demo-catalogus bevat geen verzonnen reviews", g.reviewSummary)
        }
    }

    @Test fun unknownPriceIsNotTheSameAsFree() {
        assertNull(game("elden-ring").priceCents)
        assertNull(game("elden-ring").isFree)
        assertEquals(true, game("dota2").isFree)
        val onlyFree = DeckFilter(price = PriceFilter.FREE)
        assertTrue(onlyFree.accepts(game("dota2")))
        assertFalse("onbekende prijs is niet bevestigd gratis", onlyFree.accepts(game("elden-ring")))
        assertTrue(DeckFilter(price = PriceFilter.PAID).accepts(game("elden-ring")))
    }

    // ---- Overeenkomst ----

    @Test fun similarityIsSymmetricAndBounded() {
        for (a in catalog.take(10)) for (b in catalog.take(10)) {
            val s = GameSimilarity.compare(a, b).score
            assertEquals(s, GameSimilarity.compare(b, a).score, 0.0001f)
            assertTrue(s in 0f..1f)
        }
    }

    @Test fun relatedGamesScoreHigherThanUnrelated() {
        val soulsPair = GameSimilarity.compare(game("elden-ring"), game("sekiro")).score
        val unrelated = GameSimilarity.compare(game("elden-ring"), game("cities-skylines")).score
        assertTrue("$soulsPair > $unrelated", soulsPair > unrelated)
        val sameDev = GameSimilarity.compare(game("bg3"), game("dos2"))
        assertTrue(sameDev.reasons.any { it.kind == LinkKind.DEVELOPER })
        assertNotNull(sameDev.dominant)
    }

    @Test fun similarityExplainsItself() {
        val sim = GameSimilarity.compare(game("hollow-knight"), game("ori-wisps"))
        assertTrue(sim.reasons.any { it.kind == LinkKind.GENRE && "Metroidvania" in it.shared })
        assertTrue(sim.explain().contains("Metroidvania"))
    }

    // ---- Feedback ----

    @Test fun feedbackSignalDependsOnStateNotOnTapCount() {
        val liked = UserGame("x", reaction = Reaction.LIKE)
        assertEquals(FeedbackProcessor.signalFor(liked), FeedbackProcessor.signalFor(liked.copy(updatedAt = 99)), 0f)
        assertTrue(FeedbackProcessor.signalFor(UserGame("x", reaction = Reaction.DISLIKE)) < 0)
        assertTrue(FeedbackProcessor.signalFor(UserGame("x", favorite = true, reaction = Reaction.LIKE)) > FeedbackProcessor.signalFor(liked))
        assertTrue("lage beoordeling is negatief", FeedbackProcessor.signalFor(UserGame("x", rating = 1)) < 0)
        assertTrue("hoge beoordeling is positief", FeedbackProcessor.signalFor(UserGame("x", rating = 5)) > 0)
        assertEquals(FeedbackProcessor.MAX_SIGNAL, FeedbackProcessor.signalFor(UserGame("x", reaction = Reaction.LIKE, favorite = true, saved = true, wishlist = true, rating = 5)), 0f)
    }

    @Test fun incrementalUpdateEqualsFullRebuild() {
        val states = listOf(
            UserGame("elden-ring", reaction = Reaction.LIKE),
            UserGame("hades", favorite = true),
            UserGame("celeste", reaction = Reaction.DISLIKE),
        )
        val rebuilt = TasteProfile.from(states, emptyMap(), byId)
        var incremental = TasteProfile()
        states.sortedBy { it.gameId }.forEach { incremental = incremental.apply(game(it.gameId), FeedbackProcessor.signalFor(it)) }
        assertEquals(rebuilt.tagWeights, incremental.tagWeights)
        Axis.entries.forEach { assertEquals(rebuilt.learnedAxis(it), incremental.learnedAxis(it)) }
    }

    @Test fun positiveFeedbackRaisesSimilarGamesAndNegativeLowersThem() {
        val empty = TasteProfile()
        val likedSouls = TasteProfile.from(listOf(UserGame("elden-ring", reaction = Reaction.LIKE), UserGame("sekiro", reaction = Reaction.LIKE)), emptyMap(), byId)
        val dislikedSouls = TasteProfile.from(listOf(UserGame("elden-ring", reaction = Reaction.DISLIKE), UserGame("sekiro", reaction = Reaction.DISLIKE)), emptyMap(), byId)
        val target = game("monster-hunter-world")
        val neutral = Recommender.score(target, empty).score
        assertTrue(Recommender.score(target, likedSouls).score > Recommender.score(target, dislikedSouls).score)
        assertTrue(Recommender.score(target, dislikedSouls).score < neutral + 0.01f)
    }

    @Test fun manualPreferencesInfluenceScores() {
        val calm = TasteProfile(manualAxes = mapOf(Axis.COMBAT to 0.0f, Axis.DIFFICULTY to 0.1f, Axis.FREEDOM to 0.9f))
        val stardew = Recommender.score(game("stardew"), calm).score
        val sekiro = Recommender.score(game("sekiro"), calm).score
        assertTrue("$stardew > $sekiro", stardew > sekiro)
    }

    // ---- Eerlijke weergave ----

    @Test fun noPercentageWithoutEnoughData() {
        val none = Recommender.score(game("hades"), TasteProfile())
        assertEquals(MatchLevel.INSUFFICIENT, none.level)
        assertNull(none.percent)

        val one = TasteProfile.from(listOf(UserGame("elden-ring", reaction = Reaction.LIKE)), emptyMap(), byId)
        assertEquals(MatchLevel.INSUFFICIENT, Recommender.score(game("hades"), one).level)

        val few = TasteProfile.from(listOf(UserGame("elden-ring", reaction = Reaction.LIKE), UserGame("hades", reaction = Reaction.LIKE)), emptyMap(), byId)
        val scored = Recommender.score(game("sekiro"), few)
        assertTrue(scored.level != MatchLevel.INSUFFICIENT)
        assertNull("kwalitatief maar nog geen percentage", scored.percent)

        val many = TasteProfile.from(
            listOf("elden-ring", "hades", "sekiro", "celeste", "hollow-knight").map { UserGame(it, reaction = Reaction.LIKE) }, emptyMap(), byId
        )
        assertNotNull(Recommender.score(game("ori-wisps"), many).percent)
    }

    @Test fun everyRecommendationHasAReason() {
        val profile = TasteProfile.from(listOf(UserGame("hades", reaction = Reaction.LIKE)), emptyMap(), byId)
        Recommender.rank(catalog, profile).forEach { assertTrue(it.reasons.isNotEmpty()) }
        Recommender.rank(catalog, TasteProfile()).forEach { assertTrue(it.reasons.isNotEmpty()) }
    }

    // ---- Variatie en determinisme ----

    @Test fun rankingAndDeckAreDeterministic() {
        val profile = TasteProfile.from(listOf(UserGame("hades", reaction = Reaction.LIKE), UserGame("celeste", favorite = true)), emptyMap(), byId)
        val a = Recommender.buildDeck(catalog, profile, 15).map { it.game.id }
        val b = Recommender.buildDeck(catalog.shuffled(java.util.Random(7)), profile, 15).map { it.game.id }
        assertEquals(a, b)
        assertEquals(15, a.size)
        assertEquals("geen dubbelen", a.size, a.toSet().size)
    }

    @Test fun deckDoesNotRepeatOneGenre() {
        val soulsFan = TasteProfile.from(
            listOf("elden-ring", "sekiro", "monster-hunter-world").map { UserGame(it, reaction = Reaction.LIKE) }, emptyMap(), byId
        )
        val deck = Recommender.buildDeck(catalog.filter { it.id !in setOf("elden-ring", "sekiro", "monster-hunter-world") }, soulsFan, 12)
        val genres = deck.flatMap { it.game.genres }.toSet()
        assertTrue("minstens 6 verschillende genres in 12 kaarten, kreeg ${genres.size}", genres.size >= 6)
        val firstTwelveHaveDiscovery = deck.any { rec -> rec.novelty >= 0.5f }
        assertTrue("er moet ontdekking in zitten", firstTwelveHaveDiscovery)
    }

    // ---- Filters ----

    @Test fun deckFiltersApply() {
        val calm = catalog.filter { DeckFilter(playStyle = PlayStyle.CALM).accepts(it) }
        assertTrue(calm.any { it.id == "stardew" })
        assertFalse(calm.any { it.id == "sekiro" })
        assertTrue(DeckFilter(genre = "Roguelike").accepts(game("hades")))
        assertFalse(DeckFilter(genre = "Roguelike").accepts(game("elden-ring")))
        assertTrue(DeckFilter(playStyle = PlayStyle.TOGETHER).accepts(game("deep-rock")))
        assertFalse(DeckFilter().isActive)
        assertTrue(DeckFilter(platform = "PC").isActive)
    }

    // ---- Graph ----

    @Test fun graphIsBoundedAndDeterministic() {
        val big = (1..200).map { i -> catalog[i % catalog.size].copy(id = "g$i", title = "Game $i") }
        val g1 = GraphBuilder.build(big, maxNodes = 40)
        val g2 = GraphBuilder.build(big, maxNodes = 40)
        assertEquals(40, g1.games.size)
        assertEquals(160, g1.hiddenCount)
        assertTrue("per node maximaal 3 sterkste verbindingen aangevraagd", g1.edges.size <= 40 * 3)
        assertEquals(g1.edges.map { it.a to it.b }, g2.edges.map { it.a to it.b })
        assertTrue(g1.x.all { it in -1.001f..1.001f } && g1.y.all { it in -1.001f..1.001f })
        assertTrue(g1.x.contentEquals(g2.x))
    }

    @Test fun graphHandlesEmptyAndSingle() {
        assertTrue(GraphBuilder.build(emptyList()).games.isEmpty())
        val one = GraphBuilder.build(listOf(catalog.first()))
        assertEquals(1, one.games.size)
        assertTrue(one.edges.isEmpty())
    }

    @Test fun graphEdgesHaveConcreteReasons() {
        val graph = GraphBuilder.build(catalog)
        assertTrue(graph.edges.isNotEmpty())
        graph.edges.forEach { assertTrue(it.similarity.reasons.isNotEmpty()) }
    }
}
