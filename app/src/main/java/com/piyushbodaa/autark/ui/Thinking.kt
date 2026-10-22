package com.piyushbodaa.autark.ui

/** Separates a reasoning model's "<think>…</think>" notes from its answer. */
object Thinking {
    data class Parts(val thoughts: String?, val answer: String, val stillThinking: Boolean)

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun split(text: String): Parts {
        val start = text.indexOf(OPEN)
        if (start < 0 || text.substring(0, start).isNotBlank()) return Parts(null, text, false)
        val end = text.indexOf(CLOSE, start)
        if (end < 0) return Parts(text.substring(start + OPEN.length).trim(), "", true)
        val thoughts = text.substring(start + OPEN.length, end).trim()
        return Parts(thoughts.ifEmpty { null }, text.substring(end + CLOSE.length).trimStart(), false)
    }
}
