package app.sirrak.core.rag

import kotlin.math.ln
import kotlin.math.sqrt

/** One BM25 hit. Scores are unbounded and only ever compared inside one query. */
data class Bm25Hit(val chunk: RagChunk, val score: Double)

/**
 * Okapi BM25 over [ArabicText.tokens]-normalised chunks, so Arabic orthographic variants match and
 * diacritics never split a word. The index is immutable and built from chunks only, which keeps the
 * lexical side fully unit-testable and independent of the embedding engine.
 */
class Bm25Index private constructor(
    private val chunks: List<RagChunk>,
    private val termFrequencies: List<Map<String, Int>>,
    private val lengths: IntArray,
    private val averageLength: Double,
    private val documentFrequency: Map<String, Int>,
    private val k1: Double,
    private val b: Double,
) {
    val size: Int get() = chunks.size

    fun search(query: String, limit: Int, allowedDocumentIds: Set<String>? = null): List<Bm25Hit> {
        if (limit <= 0 || query.isBlank() || chunks.isEmpty()) return emptyList()
        val queryTokens = ArabicText.tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        val queryCounts = queryTokens.groupingBy { it }.eachCount()
        val total = chunks.size.toDouble()
        val hits = ArrayList<Bm25Hit>()
        for (index in chunks.indices) {
            val chunk = chunks[index]
            if (allowedDocumentIds != null && chunk.documentId !in allowedDocumentIds) continue
            val frequencies = termFrequencies[index]
            var score = 0.0
            for ((token, queryCount) in queryCounts) {
                val frequency = frequencies[token] ?: continue
                val df = documentFrequency[token] ?: 0
                val idf = ln(1.0 + (total - df + 0.5) / (df + 0.5))
                val norm = 1.0 - b + b * (lengths[index] / averageLength)
                val tf = frequency * (k1 + 1.0) / (frequency + k1 * norm)
                score += idf * tf * queryCount
            }
            if (score > 0.0) hits += Bm25Hit(chunk, score)
        }
        return hits
            .sortedWith(compareByDescending<Bm25Hit> { it.score }.thenBy { it.chunk.documentId }.thenBy { it.chunk.ordinal })
            .take(limit)
    }

    companion object {
        fun build(chunks: List<RagChunk>, k1: Double = 1.2, b: Double = 0.75): Bm25Index {
            val frequencies = chunks.map { chunk ->
                ArabicText.tokens(chunk.normalized).let { tokens ->
                    tokens.groupingBy { it }.eachCount()
                }
            }
            val lengths = IntArray(chunks.size) { index -> frequencies[index].values.sum() }
            val average = if (lengths.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
            val df = HashMap<String, Int>()
            frequencies.forEach { terms ->
                terms.keys.forEach { token -> df[token] = (df[token] ?: 0) + 1 }
            }
            return Bm25Index(chunks, frequencies, lengths, average, df, k1, b)
        }
    }
}

/** Reciprocal Rank Fusion: rank-based and score-free, so BM25 and cosine never need shared scaling. */
object RrfFusion {
    /**
     * Fuses ordered id lists into one ordered list. Each occurrence contributes 1 / (k + rank), with
     * rank starting at 1. Ties keep the first-seen order, which makes the fusion deterministic.
     */
    fun <T> fuse(rankedLists: List<List<T>>, k: Int = 60, limit: Int = 0): List<Pair<T, Double>> {
        require(k > 0) { "k must be positive" }
        val scores = LinkedHashMap<T, Double>()
        for (list in rankedLists) {
            list.forEachIndexed { index, item ->
                val rank = index + 1
                scores[item] = (scores[item] ?: 0.0) + 1.0 / (k + rank)
            }
        }
        val ordered = scores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        return if (limit > 0) ordered.take(limit) else ordered
    }
}

/**
 * Hybrid retrieval: dense cosine plus lexical BM25, fused with RRF. Everything is pure and takes the
 * chunk/vector collections as arguments, so a unit test can prove ranking without a model and the app
 * can rebuild an index after re-embedding without touching chat.
 */
class HybridRetriever(private val limits: RagLimits = RagLimits.Default) {

    /**
     * @param queryVector the embedded question, or null when only lexical ranking is possible (the
     *   caller then still gets honest BM25 results, never a fake semantic score).
     * @param allowedDocumentIds conversation scope; null means the whole library.
     */
    fun retrieve(
        question: String,
        chunks: List<RagChunk>,
        vectors: Map<String, FloatArray>,
        queryVector: FloatArray?,
        documentNames: Map<String, String>,
        allowedDocumentIds: Set<String>? = null,
    ): List<RagHit> {
        if (question.isBlank() || chunks.isEmpty()) return emptyList()
        val scoped = if (allowedDocumentIds == null) {
            chunks
        } else {
            chunks.filter { it.documentId in allowedDocumentIds }
        }
        if (scoped.isEmpty()) return emptyList()

        val lexicalHits = Bm25Index.build(scoped).search(question, limits.queryTopK, allowedDocumentIds)
        val vectorScores: Map<String, Float> = if (queryVector == null) {
            emptyMap()
        } else {
            scoped.mapNotNull { chunk ->
                val vector = vectors[chunk.id] ?: return@mapNotNull null
                if (vector.size != queryVector.size) return@mapNotNull null
                val cosine = cosine(queryVector, vector)
                if (cosine > 0f) chunk.id to cosine else null
            }.toMap()
        }
        val vectorRanked = vectorScores.entries
            .sortedByDescending { it.value }
            .take(limits.queryTopK)
            .map { it.key }

        val lexicalRanked = lexicalHits.map { it.chunk.id }
        val fused = RrfFusion.fuse(listOf(lexicalRanked, vectorRanked), limits.rrfK, limits.contextTopK)
        if (fused.isEmpty()) return emptyList()

        val lexicalScore = lexicalHits.associate { it.chunk.id to it.score.toFloat() }
        val chunkById = scoped.associateBy { it.id }
        return fused.mapIndexedNotNull { index, (chunkId, score) ->
            val chunk = chunkById[chunkId] ?: return@mapIndexedNotNull null
            RagHit(
                documentId = chunk.documentId,
                documentName = documentNames[chunk.documentId] ?: chunk.documentId,
                chunk = chunk,
                rank = index + 1,
                fusedScore = score.toFloat(),
                vectorScore = vectorScores[chunkId] ?: 0f,
                lexicalScore = lexicalScore[chunkId] ?: 0f,
            )
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
