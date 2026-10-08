package com.dby.mobile.data

/**
 * How well [query] matches [text], higher is better, or null for no match. Case, spaces and
 * separators don't count. In order: the same name, a prefix, a substring, the words' initials
 * ("ahau" for agreement_has_assigned_users), the letters in order with a few missing ("custmer"),
 * then up to one typo (two for queries of 8+ letters) anywhere in the name ("cutsomers").
 */
fun fuzzyScore(query: String, text: String): Int? {
    val q = norm(query)
    val t = norm(text)
    if (q.isEmpty()) return 0
    if (t.isEmpty()) return null
    return when {
        t == q -> 1000
        t.startsWith(q) -> 900
        t.contains(q) -> 800
        initials(text).startsWith(q) -> 700
        else -> spread(q, t)?.let { 500 - it } ?: typos(q, t)?.let { 300 - 100 * it }
    }
}

/** [items] that match [query] on any of their [keys], best first; all of them when it's blank. */
fun <T> fuzzySearch(items: List<T>, query: String, keys: (T) -> List<String>): List<T> {
    if (norm(query).isEmpty()) return items
    return items
        .mapNotNull { item ->
            keys(item).mapNotNull { key -> fuzzyScore(query, key)?.let { it to key.length } }
                .maxWithOrNull(compareBy({ it.first }, { -it.second }))
                ?.let { (score, length) -> Triple(item, score, length) }
        }
        .sortedWith(compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private val WORD_BREAK = Regex("""[^\p{L}\p{N}]+|(?<=\p{Ll})(?=\p{Lu})""")

private fun initials(s: String) = s.split(WORD_BREAK).filter { it.isNotEmpty() }.joinToString("") { it.first().lowercase() }

/**
 * The letters of [q] in order inside [t]: how many extra letters the tightest such stretch holds,
 * or null if there is none, or it sprawls past three times the query's length.
 */
private fun spread(q: String, t: String): Int? {
    var best: Int? = null
    for (start in t.indices) {
        if (t[start] != q[0]) continue
        var j = start
        var i = 0
        while (j < t.length && i < q.length) {
            if (t[j] == q[i]) i++
            j++
        }
        if (i < q.length) break // no later start can finish either
        val span = j - start
        if (span <= q.length * 3 && (best == null || span - q.length < best)) best = span - q.length
    }
    return best
}

/** Fewest edits that turn [q] into some stretch of [t] (Sellers), if within the allowance. */
private fun typos(q: String, t: String): Int? {
    val limit = when {
        q.length < 4 -> return null
        q.length < 8 -> 1
        else -> 2
    }
    var prev = IntArray(t.length + 1) // a match may start anywhere in t
    for (i in 1..q.length) {
        val cur = IntArray(t.length + 1)
        cur[0] = i
        for (j in 1..t.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (q[i - 1] == t[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev.min().takeIf { it <= limit }
}
