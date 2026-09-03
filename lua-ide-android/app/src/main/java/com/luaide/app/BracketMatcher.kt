package com.luaide.app

object BracketMatcher {

    private val OPEN = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val CLOSE = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** Returns the (openIndex, closeIndex) pair if the char at or just before [cursor] is a bracket, else null. */
    fun findMatch(text: CharSequence, cursor: Int): Pair<Int, Int>? {
        val candidates = listOfNotNull(
            cursor.takeIf { it < text.length },
            (cursor - 1).takeIf { it >= 0 }
        )
        for (i in candidates) {
            val ch = text[i]
            if (OPEN.containsKey(ch)) {
                val close = matchForward(text, i, ch, OPEN.getValue(ch))
                if (close != null) return i to close
            } else if (CLOSE.containsKey(ch)) {
                val open = matchBackward(text, i, ch, CLOSE.getValue(ch))
                if (open != null) return open to i
            }
        }
        return null
    }

    private fun matchForward(text: CharSequence, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun matchBackward(text: CharSequence, start: Int, close: Char, open: Char): Int? {
        var depth = 0
        for (i in start downTo 0) {
            when (text[i]) {
                close -> depth++
                open -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
