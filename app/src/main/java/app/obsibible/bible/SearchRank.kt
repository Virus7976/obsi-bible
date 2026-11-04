package app.obsibible.bible

/**
 * Ranking and spelling help for word search. Pure, so it is tested without a phone.
 * The idea comes from Obsidian's Omnisearch: best match first, and a typo still finds the verse.
 */
object SearchRank {
    private val wordRx = Regex("[\\p{L}\\p{N}]+")

    fun words(normalized: String): List<String> = wordRx.findAll(normalized).map { it.value }.toList()

    /**
     * Scores how well [text] (already normalized) matches the typed [query] words. Higher is better.
     * The whole phrase in order is best, then every word whole, then the words close together.
     */
    fun score(text: String, query: List<String>): Int {
        if (query.isEmpty()) return 0
        val tw = words(text)
        var s = 0
        val phrase = query.joinToString(" ")
        if (query.size > 1 && " ${tw.joinToString(" ")} ".contains(" $phrase")) s += 100
        // each word: whole word beats a prefix
        val positions = ArrayList<List<Int>>()
        var found = 0
        for ((qi, q) in query.withIndex()) {
            val last = qi == query.lastIndex
            val whole = tw.indices.filter { tw[it] == q }
            val pre = if (whole.isEmpty() && last) tw.indices.filter { tw[it].startsWith(q) } else emptyList()
            when {
                whole.isNotEmpty() -> { s += 12; found++; positions += whole }
                pre.isNotEmpty() -> { s += 7; found++; positions += pre }
            }
        }
        s += found * 20
        // the smallest window that holds one of each found word
        if (positions.size > 1) {
            val span = minSpan(positions)
            s += (30 - span * 3).coerceAtLeast(0)
        }
        // a shorter verse with the words is a closer match
        s += (12 - tw.size / 6).coerceAtLeast(0)
        return s
    }

    private fun minSpan(lists: List<List<Int>>): Int {
        val all = lists.flatMapIndexed { i, l -> l.map { it to i } }.sortedBy { it.first }
        val need = lists.size
        val have = IntArray(need)
        var kinds = 0
        var best = Int.MAX_VALUE
        var lo = 0
        for (hi in all.indices) {
            if (have[all[hi].second]++ == 0) kinds++
            while (kinds == need) {
                best = minOf(best, all[hi].first - all[lo].first)
                if (--have[all[lo].second] == 0) kinds--
                lo++
            }
        }
        return if (best == Int.MAX_VALUE) 50 else best
    }

    /** Edit distance with a cut-off: returns [max] + 1 as soon as it is clear the words are further apart. */
    fun distance(a: String, b: String, max: Int = 2): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                // two letters swapped counts as one mistake
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * The closest real word for a typo, or null when the word is fine or nothing is close.
     * Short words allow one mistake, longer ones two. A word that starts the same way and has the same length wins.
     */
    fun nearest(word: String, vocab: Map<String, Int>): String? {
        if (word.length < 3 || vocab.containsKey(word)) return null
        // a word someone is still typing: a real word starts with it
        if (vocab.keys.any { it.startsWith(word) }) return null
        val max = if (word.length <= 4) 1 else 2
        var best: String? = null
        var bestD = max + 1
        var bestCost = Double.MAX_VALUE
        for ((w, n) in vocab) {
            if (kotlin.math.abs(w.length - word.length) > max) continue
            val d = distance(word, w, max)
            if (d > max) continue
            // fewer mistakes first; then the same first letter and length; then the more common word
            val same = w.commonPrefixWith(word).length
            val cost = d * 10.0 - same * 1.5 + (if (w[0] != word[0]) 6 else 0) + kotlin.math.abs(w.length - word.length) * 2 - kotlin.math.ln(n.toDouble() + 1) / kotlin.math.ln(2.0)
            if (cost < bestCost) { best = w; bestD = d; bestCost = cost }
        }
        return if (bestD <= max) best else null
    }

    /** The typed words with typos fixed, or null when nothing changed. */
    fun correct(query: List<String>, vocab: Map<String, Int>): List<String>? {
        var changed = false
        val out = query.map { w -> nearest(w, vocab)?.also { changed = true } ?: w }
        return if (changed) out else null
    }
}
