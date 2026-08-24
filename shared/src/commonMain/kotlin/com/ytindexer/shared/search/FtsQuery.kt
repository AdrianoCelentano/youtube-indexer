package com.ytindexer.shared.search

/**
 * Turns whatever the user typed into a safe FTS4 `MATCH` expression.
 *
 * User text cannot go into `MATCH` directly. FTS has its own query grammar, so an
 * apostrophe in "don't", a stray quote, or a bare `-` or `*` is either a syntax error
 * that throws mid-search or an operator that silently changes what was searched for.
 * Neither is acceptable for text a person typed into a search box.
 *
 * The approach is to discard the grammar entirely: split into alphanumeric terms and
 * rebuild a query we know is well-formed. Users of this app are searching their own
 * videos, not composing boolean expressions.
 *
 * Terms are prefix-matched (`sourd*`) so results appear while typing.
 *
 * @return a `MATCH` expression, or null when nothing searchable remains -- callers should
 *   treat null as "no text filter" rather than "match nothing".
 */
fun buildFtsQuery(prompt: String): String? {
    val terms =
        prompt
            // Split on exactly what the unicode61 tokenizer treats as a separator: any
            // non-alphanumeric character. Stripping such characters instead of splitting
            // on them silently produces terms that can never match -- "don't" would
            // become "dont" while the index holds "don" and "t".
            .split { !it.isLetterOrDigit() }
            .map { it.lowercase() }
            .filter { it.length >= MIN_TERM_LENGTH }
            .distinct()
            .take(MAX_TERMS)

    if (terms.isEmpty()) return null

    // Implicit AND: every term must appear somewhere in the video's text. With a personal
    // library, narrowing beats recall -- an OR across common words would return most of
    // the channel and rank the intended video below noise.
    return terms.joinToString(" ") { "$it*" }
}

/** Splits on a character predicate, dropping empty runs. */
private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    for (char in this) {
        if (isSeparator(char)) {
            if (current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
        } else {
            current.append(char)
        }
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

/**
 * Single characters match too much to be useful as a prefix and make every query scan
 * most of the index.
 */
private const val MIN_TERM_LENGTH = 2

/** Guards against a pasted paragraph turning into a pathological query. */
private const val MAX_TERMS = 12
