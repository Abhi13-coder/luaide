package com.luaide.app

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.EditText

/**
 * Lightweight Lua syntax highlighter. No parser — regex-driven spans, which is
 * plenty for a mobile editor and stays fast on low-end hardware (spec §15).
 */
class LuaSyntaxHighlighter(private val editText: EditText) : TextWatcher {

    companion object {
        private val KEYWORDS = Regex(
            "\\b(and|break|do|else|elseif|end|false|for|function|goto|if|in|" +
                "local|nil|not|or|repeat|return|then|true|until|while)\\b"
        )
        private val STRING = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        private val COMMENT = Regex("--\\[\\[[\\s\\S]*?]]|--.*")
        private val NUMBER = Regex("\\b\\d+(?:\\.\\d+)?\\b")
        private val FUNCTION_CALL = Regex("\\b([A-Za-z_][A-Za-z0-9_.]*)\\s*(?=\\()")

        private const val COLOR_KEYWORD = 0xFFB98F56.toInt()
        private const val COLOR_STRING = 0xFF7C9473.toInt()
        private const val COLOR_COMMENT = 0xFF766E5E.toInt()
        private const val COLOR_NUMBER = 0xFFD0A374.toInt()
        private const val COLOR_FUNCTION = 0xFFC9A3FF.toInt()
    }

    private var applying = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {
        if (applying || s == null) return
        applying = true
        try {
            highlight(s)
        } finally {
            applying = false
        }
    }

    private fun highlight(s: Editable) {
        s.getSpans(0, s.length, ForegroundColorSpan::class.java).forEach { s.removeSpan(it) }
        s.getSpans(0, s.length, StyleSpan::class.java).forEach { s.removeSpan(it) }

        fun paint(regex: Regex, color: Int, italic: Boolean = false) {
            for (m in regex.findAll(s)) {
                s.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (italic) s.setSpan(StyleSpan(Typeface.ITALIC), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        paint(NUMBER, COLOR_NUMBER)
        paint(KEYWORDS, COLOR_KEYWORD)
        paint(FUNCTION_CALL, COLOR_FUNCTION)
        paint(STRING, COLOR_STRING)      // strings after keywords so quoted keywords aren't tinted
        paint(COMMENT, COLOR_COMMENT, italic = true)
    }

    fun attach() {
        editText.addTextChangedListener(this)
        editText.text?.let { highlight(it) }
    }
}
