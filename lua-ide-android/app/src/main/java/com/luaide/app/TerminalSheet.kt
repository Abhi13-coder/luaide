package com.luaide.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import kotlin.concurrent.thread

class TerminalSheet(
    private val project: File,
    private val lua: LuaBridge,
    private val registerSink: (LuaBridge.OutputSink) -> Unit,
    private val unregisterSink: (LuaBridge.OutputSink) -> Unit
) : BottomSheetDialogFragment(), LuaBridge.OutputSink {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var promptView: TextView
    private val shell = ProjectShell(project)

    override fun onStart() {
        super.onStart()
        registerSink(this)
    }

    override fun onStop() {
        unregisterSink(this)
        super.onStop()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        output = TextView(ctx).apply {
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(40, 24, 40, 24)
            text = "lua 5.4.7 \u2014 shell + REPL (try: ls, cd, cat, mkdir, rm, luarocks, clear, or any Lua)\n"
        }
        scroll.addView(output)
        root.addView(scroll)

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 16, 40, 24)
        }
        promptView = TextView(ctx).apply {
            text = promptText()
            setTextColor(Color.parseColor("#B98F56"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val input = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val line = v.text.toString()
                    if (line.isNotBlank()) {
                        appendOutput("${promptText()}$line", Color.parseColor("#B98F56"))
                        handleLine(line)
                        v.text.clear()
                    }
                    true
                } else false
            }
        }
        inputRow.addView(promptView)
        inputRow.addView(input)
        root.addView(inputRow)

        return root
    }

    private fun promptText(): String = "${shell.cwd.relativeTo(project).path.ifEmpty { "." }} \u276F "

    private fun handleLine(line: String) {
        lastLine = line
        val cmd = line.trim().substringBefore(' ')
        // `luarocks install` hits the network — keep that off the main thread;
        // everything else in ProjectShell is instant local file I/O.
        if (cmd == "luarocks" && line.trim().startsWith("luarocks install")) {
            appendOutput("resolving\u2026", Color.parseColor("#766E5E"))
            thread {
                val result = shell.run(line)
                Handler(Looper.getMainLooper()).post { applyResult(result) }
            }
            return
        }
        applyResult(shell.run(line))
    }

    private fun applyResult(result: ProjectShell.Output) {
        when (result) {
            is ProjectShell.Output.Text -> {
                if (result.lines.isEmpty()) {
                    promptView.text = promptText()
                } else {
                    result.lines.forEach { appendOutput(it, Color.parseColor("#E9E1CF")) }
                    promptView.text = promptText()
                }
            }
            is ProjectShell.Output.Cleared -> {
                output.text = ""
            }
            is ProjectShell.Output.NotAShellCommand -> {
                // Not one of our shell verbs — treat it as Lua, exactly like a REPL.
                lua.eval(lastLine, "=repl")
            }
        }
    }

    private var lastLine: String = ""

    override fun onStdout(line: String) = appendOutput(line, Color.parseColor("#E9E1CF"))
    override fun onStderr(line: String) = appendOutput("! $line", Color.parseColor("#9A5648"))

    private fun appendOutput(line: String, color: Int) {
        activity?.runOnUiThread {
            val start = output.text.length
            output.append("$line\n")
            output.text.let {
                if (it is android.text.Spannable) {
                    it.setSpan(android.text.style.ForegroundColorSpan(color), start, it.length, 0)
                }
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
