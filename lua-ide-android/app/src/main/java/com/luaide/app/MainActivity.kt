package com.luaide.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity(), LuaBridge.OutputSink {

    private lateinit var projectManager: ProjectManager
    private lateinit var project: File
    private lateinit var lua: LuaBridge
    private lateinit var editor: CodeEditText
    private lateinit var lineNumbers: TextView
    private lateinit var fileLabel: TextView
    private lateinit var projectNameView: TextView
    private lateinit var diagnosticsBar: TextView
    private lateinit var suggestionScroll: HorizontalScrollView
    private lateinit var suggestionBar: LinearLayout

    private var currentFile: File? = null
    private val outputListeners = mutableListOf<LuaBridge.OutputSink>()
    private var projectSymbols: List<SymbolIndexer.Symbol> = emptyList()
    private var bracketSpans: List<BackgroundColorSpan> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var diagnosticsRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectManager = ProjectManager(this)
        project = projectManager.listProjects().firstOrNull() ?: projectManager.createProject("weather_app")

        editor = findViewById(R.id.editor)
        lineNumbers = findViewById(R.id.lineNumbers)
        fileLabel = findViewById(R.id.fileLabel)
        projectNameView = findViewById(R.id.projectName)
        diagnosticsBar = findViewById(R.id.diagnosticsBar)
        suggestionScroll = findViewById(R.id.suggestionScroll)
        suggestionBar = findViewById(R.id.suggestionBar)

        LuaSyntaxHighlighter(editor).attach()
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                scheduleDiagnostics()
                updateSuggestions()
                updateLineNumbers()
            }
        })
        editor.onCursorMoved = { pos -> updateBracketHighlight(pos) }

        lua = LuaBridge(this, projectManager.rocksDirFor(project).absolutePath)

        val mainLua = File(project, "src/main.lua").also {
            if (!it.exists()) projectManager.writeFile(it, "print(\"hello\")\n")
        }
        refreshSymbolIndex()
        openFile(mainLua)
        projectNameView.text = "${project.name} \u2304"
        projectNameView.setOnClickListener {
            ProjectsSheet(projectManager, project) { switchProject(it) }.show(supportFragmentManager, "projects")
        }

        findViewById<View>(R.id.runButton).setOnClickListener { runCurrentFile() }
        findViewById<View>(R.id.navFiles).setOnClickListener { openFilesSheet() }
        findViewById<View>(R.id.navTerminal).setOnClickListener { openTerminalSheet() }
        findViewById<View>(R.id.navMore).setOnClickListener { openMoreSheet() }
        findViewById<View>(R.id.navSearch).setOnClickListener { openSearchSheet() }
        findViewById<View>(R.id.navPalette).setOnClickListener {
            CommandPaletteSheet(
                onRun = { runCurrentFile() },
                onOpenTerminal = { openTerminalSheet() },
                onOpenSearch = { openSearchSheet() },
                onOpenFiles = { openFilesSheet() },
                onOpenMore = { openMoreSheet() },
                onNewFile = { path -> createAndOpenFile(path) }
            ).show(supportFragmentManager, "palette")
        }
    }

    private fun switchProject(newProject: File) {
        if (newProject == project) return
        saveCurrentFile()
        lua.close()
        currentFile = null
        project = newProject
        lua = LuaBridge(this, projectManager.rocksDirFor(project).absolutePath)
        val mainLua = File(project, "src/main.lua").also {
            if (!it.exists()) projectManager.writeFile(it, "print(\"hello\")\n")
        }
        refreshSymbolIndex()
        openFile(mainLua)
        projectNameView.text = "${project.name} \u2304"
    }

    private fun openFilesSheet() {
        FilesSheet(project, projectManager, { openFile(it) }, { path -> createAndOpenFile(path) })
            .show(supportFragmentManager, "files")
    }

    private fun openTerminalSheet() {
        TerminalSheet(project, lua, { outputListeners.add(it) }, { outputListeners.remove(it) })
            .show(supportFragmentManager, "terminal")
    }

    private fun openMoreSheet() {
        RocksPluginsSheet(project, projectManager.rocksDirFor(project)).show(supportFragmentManager, "more")
    }

    private fun openSearchSheet() {
        SearchSheet(project, projectManager) { file, line -> jumpTo(file, line) }.show(supportFragmentManager, "search")
    }

    private fun createAndOpenFile(relativePath: String) {
        val f = projectManager.createFile(project, relativePath)
        refreshSymbolIndex()
        openFile(f)
    }

    // ---- line numbers: kept in lockstep with the editor's actual line count ----
    private fun updateLineNumbers() {
        val count = editor.text?.count { it == '\n' }?.plus(1) ?: 1
        lineNumbers.text = (1..count).joinToString("\n")
    }

    // ---- bracket matching: real depth-counted scan, not a static highlight ----
    private fun updateBracketHighlight(cursor: Int) {
        val text = editor.text ?: return
        if (text !is Spannable) return
        bracketSpans.forEach { text.removeSpan(it) }
        bracketSpans = emptyList()

        val match = BracketMatcher.findMatch(text, cursor) ?: return
        val color = Color.parseColor("#3A2F1F")
        val spanA = BackgroundColorSpan(color)
        val spanB = BackgroundColorSpan(color)
        text.setSpan(spanA, match.first, match.first + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(spanB, match.second, match.second + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        bracketSpans = listOf(spanA, spanB)
    }

    // ---- diagnostics: real syntax check via the embedded Lua compiler, debounced ----
    private fun scheduleDiagnostics() {
        diagnosticsRunnable?.let { mainHandler.removeCallbacks(it) }
        val code = editor.text.toString()
        val r = Runnable {
            val error = lua.checkSyntax(code)
            if (error == null) {
                diagnosticsBar.visibility = View.GONE
            } else {
                diagnosticsBar.visibility = View.VISIBLE
                diagnosticsBar.text = "\u26A0 $error"
            }
        }
        diagnosticsRunnable = r
        mainHandler.postDelayed(r, 500)
    }

    // ---- autocomplete: real prefix match over real project symbols + stdlib ----
    private fun updateSuggestions() {
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val prefix = AutocompleteEngine.currentWordPrefix(editor.text ?: "", cursor)
        val suggestions = AutocompleteEngine.suggestions(prefix, projectSymbols)

        suggestionBar.removeAllViews()
        if (suggestions.isEmpty()) {
            suggestionScroll.visibility = View.GONE
            return
        }
        suggestionScroll.visibility = View.VISIBLE
        for (s in suggestions) {
            val chip = TextView(this).apply {
                text = s
                setTextColor(Color.parseColor("#B98F56"))
                setBackgroundColor(Color.parseColor("#1F1B15"))
                textSize = 11f
                setPadding(24, 12, 24, 12)
                setOnClickListener { insertCompletion(prefix, s) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 12
            suggestionBar.addView(chip, lp)
        }
    }

    private fun insertCompletion(prefix: String, completion: String) {
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val start = cursor - prefix.length
        if (start < 0) return
        editor.text?.replace(start, cursor, completion)
        editor.setSelection(start + completion.length)
    }

    private fun refreshSymbolIndex() {
        projectSymbols = SymbolIndexer.indexProject(project, projectManager.listFiles(project))
    }

    private fun jumpTo(file: File, line: Int) {
        openFile(file)
        editor.post {
            val text = editor.text ?: return@post
            var offset = 0
            var currentLine = 1
            while (currentLine < line && offset < text.length) {
                if (text[offset] == '\n') currentLine++
                offset++
            }
            editor.setSelection(offset.coerceAtMost(text.length))
            editor.requestFocus()
        }
    }

    private fun openFile(file: File) {
        saveCurrentFile()
        currentFile = file
        fileLabel.text = file.relativeTo(project).path
        editor.setText(projectManager.readFile(file))
        updateLineNumbers()
        diagnosticsBar.visibility = View.GONE
        suggestionScroll.visibility = View.GONE
    }

    private fun saveCurrentFile() {
        val f = currentFile ?: return
        projectManager.writeFile(f, editor.text.toString())
        refreshSymbolIndex()
    }

    private fun runCurrentFile() {
        saveCurrentFile()
        val f = currentFile ?: return
        lua.eval(projectManager.readFile(f), "@${f.name}")
        Toast.makeText(this, "ran ${f.name} \u2014 open Terminal to see output", Toast.LENGTH_SHORT).show()
    }

    override fun onStdout(line: String) = outputListeners.forEach { it.onStdout(line) }
    override fun onStderr(line: String) = outputListeners.forEach { it.onStderr(line) }

    override fun androidToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun androidClipboardCopy(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lua-ide", text))
    }

    override fun androidClipboardPaste(): String {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    override fun androidStoragePath(kind: String): String = when (kind) {
        "cache" -> cacheDir.absolutePath
        else -> filesDir.absolutePath
    }

    override fun androidHttpGet(url: String): String = try {
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000
            inputStream.bufferedReader().readText()
        }
    } catch (e: Exception) {
        "" // Lua side sees an empty string on failure; callers should check for that.
    }

    private val overlay by lazy { OverlayManager(this) }
    override fun androidOverlayShow(text: String, x: Int, y: Int) = overlay.show(text, x, y)
    override fun androidOverlayHide() = overlay.hide()
    override fun androidOverlayHasPermission(): Boolean = overlay.hasPermission()
    override fun androidOverlayRequestPermission() = overlay.requestPermission()

    override fun onPause() {
        saveCurrentFile()
        super.onPause()
    }

    override fun onDestroy() {
        saveCurrentFile()
        lua.close()
        super.onDestroy()
    }
}
