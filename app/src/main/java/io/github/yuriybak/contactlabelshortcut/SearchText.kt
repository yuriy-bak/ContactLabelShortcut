package io.github.yuriybak.contactlabelshortcut

import java.text.Normalizer

/**
 * A search query for the label list: matches a part of the name (case, accents and ё/е
 * do not matter) or, if the query looks like a number, a part of any phone number
 * (only the digits are compared, so "916 12" finds "+7 (916) 123-45-67").
 */
class SearchText(query: String) {

    private val text = normalize(query.trim())
    private val digits = query.filter { it.isDigit() }.takeIf { d ->
        d.isNotEmpty() && query.all { it.isDigit() || it in NUMBER_CHARS }
    }

    val isEmpty get() = text.isEmpty()

    fun matches(searchName: String, phones: List<PhoneNumber>): Boolean {
        if (searchName.contains(text)) return true
        val digits = digits ?: return false
        return phones.any { it.digits.contains(digits) }
    }

    companion object {
        private const val NUMBER_CHARS = "+-() ."
        private val MARKS = Regex("\\p{Mn}+")

        /** Lower case, without accents; "Ёлкин" and "елкин", "José" and "jose" are the same. */
        fun normalize(s: String): String {
            val lower = s.lowercase()
            // Decomposition would also turn й into и; the query goes through the same, so it still matches
            val plain = if (lower.all { it < '\u0080' }) lower
                else MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
            return plain.replace('ё', 'е')
        }
    }
}
