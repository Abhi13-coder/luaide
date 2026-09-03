package com.luaide.app

import java.io.File

/**
 * Not a real Lua parser — a regex pass over each file that's good enough for
 * "where is this defined" on mobile hardware. Covers:
 *   function name(...)            function foo.bar(...)     function foo:bar(...)
 *   local function name(...)
 *   local name = ...
 * Good enough for go-to-definition and document-symbols; a real parser (or
 * an actual Lua-language-server plugin) is the natural upgrade later.
 */
object SymbolIndexer {

    data class Symbol(
        val name: String,
        val kind: String,     // "function" | "local" | "method"
        val file: File,
        val line: Int,        // 1-based
        val lineText: String
    )

    private val FUNC_DEF = Regex("""^\s*(local\s+)?function\s+([A-Za-z_][A-Za-z0-9_.:]*)\s*\(""")
    private val LOCAL_DEF = Regex("""^\s*local\s+([A-Za-z_][A-Za-z0-9_]*)\s*=""")

    fun indexProject(project: File, files: List<File>): List<Symbol> {
        val out = mutableListOf<Symbol>()
        for (file in files) {
            if (file.extension != "lua") continue
            val lines = try { file.readLines() } catch (e: Exception) { continue }
            lines.forEachIndexed { idx, raw ->
                FUNC_DEF.find(raw)?.let { m ->
                    val name = m.groupValues[2]
                    val kind = if (name.contains(':')) "method" else "function"
                    out += Symbol(name, kind, file, idx + 1, raw.trim())
                }
                LOCAL_DEF.find(raw)?.let { m ->
                    out += Symbol(m.groupValues[1], "local", file, idx + 1, raw.trim())
                }
            }
        }
        return out
    }

    fun search(symbols: List<Symbol>, query: String): List<Symbol> {
        if (query.isBlank()) return symbols
        val q = query.lowercase()
        return symbols.filter { it.name.lowercase().contains(q) }
            .sortedBy { if (it.name.equals(query, ignoreCase = true)) 0 else 1 }
    }
}
