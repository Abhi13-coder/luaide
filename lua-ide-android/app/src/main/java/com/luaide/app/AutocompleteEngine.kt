package com.luaide.app

object AutocompleteEngine {

    private val KEYWORDS = listOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
        "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return",
        "then", "true", "until", "while"
    )

    private val STDLIB = listOf(
        "print", "pairs", "ipairs", "tostring", "tonumber", "type", "error", "assert",
        "pcall", "xpcall", "require", "select", "setmetatable", "getmetatable", "rawget", "rawset",
        "string.format", "string.find", "string.match", "string.gmatch", "string.gsub", "string.sub",
        "string.rep", "string.len", "string.upper", "string.lower",
        "table.insert", "table.remove", "table.concat", "table.sort", "table.unpack",
        "math.floor", "math.ceil", "math.abs", "math.max", "math.min", "math.random", "math.huge",
        "os.time", "os.date", "os.clock",
        "io.read", "io.write", "io.open"
    )

    /**
     * @param prefix the partial identifier currently being typed (e.g. "fet" from "fet|ch")
     * @param projectSymbols real symbols from SymbolIndexer.indexProject for this project
     */
    fun suggestions(prefix: String, projectSymbols: List<SymbolIndexer.Symbol>, limit: Int = 8): List<String> {
        if (prefix.length < 2) return emptyList()
        val p = prefix.lowercase()

        val projectNames = projectSymbols.map { it.name }.distinct()
            .filter { it.lowercase().startsWith(p) }

        val stdlibMatches = STDLIB.filter { it.substringAfterLast('.').lowercase().startsWith(p) }
        val keywordMatches = KEYWORDS.filter { it.startsWith(p) }

        // Project symbols first (most relevant to what the user's actually writing),
        // then stdlib, then keywords — de-duplicated, capped.
        return (projectNames + stdlibMatches + keywordMatches).distinct().take(limit)
    }

    /** Extracts the identifier prefix ending at [cursor] in [text], e.g. "local x = fet" -> "fet". */
    fun currentWordPrefix(text: CharSequence, cursor: Int): String {
        var start = cursor
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '.')) {
            start--
        }
        return text.subSequence(start, cursor).toString()
    }
}
