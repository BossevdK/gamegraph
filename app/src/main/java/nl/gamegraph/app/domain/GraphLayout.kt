package nl.gamegraph.app.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GraphEdge(val a: Int, val b: Int, val similarity: Similarity) {
    val kind: LinkKind get() = similarity.dominant ?: LinkKind.STYLE
    val weight: Float get() = similarity.score
}

/** Resultaat van [GraphBuilder]: nodes (games), verbindingen en posities in het bereik -1..1. */
class GraphData(
    val games: List<Game>,
    val edges: List<GraphEdge>,
    val x: FloatArray,
    val y: FloatArray,
    /** Aantal games dat niet in de graph past (wegens de limiet). */
    val hiddenCount: Int = 0,
) {
    fun neighbors(index: Int): List<GraphEdge> =
        edges.filter { it.a == index || it.b == index }.sortedByDescending { it.weight }

    fun other(edge: GraphEdge, index: Int): Int = if (edge.a == index) edge.b else edge.a
}

/**
 * Bouwt een leesbare graph, ook bij grote catalogi:
 *  - maximaal [maxNodes] nodes (de belangrijkste volgens [priority] blijven over);
 *  - per node maximaal [neighbors] sterkste verbindingen boven [minScore], zodat er geen kluwen ontstaat.
 */
object GraphBuilder {
    const val DEFAULT_MAX_NODES = 60

    fun build(
        games: List<Game>,
        maxNodes: Int = DEFAULT_MAX_NODES,
        neighbors: Int = 3,
        minScore: Float = 0.22f,
        priority: (Game) -> Float = { 0f },
    ): GraphData {
        val chosen = if (games.size <= maxNodes) games.sortedBy { it.title }
        else games.sortedWith(compareByDescending<Game> { priority(it) }.thenBy { it.title }).take(maxNodes).sortedBy { it.title }
        val n = chosen.size
        if (n == 0) return GraphData(emptyList(), emptyList(), FloatArray(0), FloatArray(0))

        val sims = HashMap<Long, Similarity>()
        fun key(i: Int, j: Int) = min(i, j).toLong() * n + max(i, j)
        for (i in 0 until n) for (j in i + 1 until n) sims[key(i, j)] = GameSimilarity.compare(chosen[i], chosen[j])

        val wanted = HashSet<Long>()
        for (i in 0 until n) {
            (0 until n).filter { it != i }
                .map { it to sims.getValue(key(i, it)) }
                .filter { it.second.score >= minScore && it.second.reasons.isNotEmpty() }
                .sortedWith(compareByDescending<Pair<Int, Similarity>> { it.second.score }.thenBy { chosen[it.first].title })
                .take(neighbors)
                .forEach { wanted += key(i, it.first) }
        }
        val edges = wanted.sorted().map { k ->
            val a = (k / n).toInt()
            val b = (k % n).toInt()
            GraphEdge(a, b, sims.getValue(k))
        }
        val (xs, ys) = layout(n, edges)
        return GraphData(chosen, edges, xs, ys, hiddenCount = games.size - n)
    }

    /** Fruchterman-Reingold met vaste startpositie, dus altijd hetzelfde resultaat voor dezelfde invoer. */
    fun layout(n: Int, edges: List<GraphEdge>, iterations: Int = 220): Pair<FloatArray, FloatArray> {
        val x = FloatArray(n)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val angle = 2.0 * Math.PI * i / n
            val r = 0.6f + 0.4f * (((i * 7919) % 13) / 13f)
            x[i] = (r * cos(angle)).toFloat()
            y[i] = (r * sin(angle)).toFloat()
        }
        if (n == 1) { x[0] = 0f; y[0] = 0f; return x to y }
        val k = sqrt(4f / n)
        var temp = 0.3f
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        repeat(iterations) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                var ex = x[i] - x[j]
                var ey = y[i] - y[j]
                var d = sqrt(ex * ex + ey * ey)
                if (d < 0.001f) { ex = 0.001f * (i - j); ey = 0.001f; d = 0.001f }
                val f = k * k / d
                dx[i] += ex / d * f; dy[i] += ey / d * f
                dx[j] -= ex / d * f; dy[j] -= ey / d * f
            }
            for (e in edges) {
                val ex = x[e.a] - x[e.b]
                val ey = y[e.a] - y[e.b]
                val d = max(sqrt(ex * ex + ey * ey), 0.001f)
                val f = d * d / k * (0.5f + e.weight)
                dx[e.a] -= ex / d * f; dy[e.a] -= ey / d * f
                dx[e.b] += ex / d * f; dy[e.b] += ey / d * f
            }
            for (i in 0 until n) {
                dx[i] -= x[i] * 0.4f
                dy[i] -= y[i] * 0.4f
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.0001f)
                val step = min(d, temp)
                x[i] += dx[i] / d * step
                y[i] += dy[i] / d * step
            }
            temp = max(0.002f, temp * 0.97f)
        }
        // Normaliseer naar -1..1.
        val extent = max(x.maxOf { kotlin.math.abs(it) }, y.maxOf { kotlin.math.abs(it) }).coerceAtLeast(0.001f)
        for (i in 0 until n) { x[i] /= extent; y[i] /= extent }
        return x to y
    }
}
