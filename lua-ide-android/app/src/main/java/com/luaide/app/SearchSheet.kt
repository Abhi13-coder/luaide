package com.luaide.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

class SearchSheet(
    private val project: File,
    private val projectManager: ProjectManager,
    private val onJumpTo: (File, Int) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var results: LinearLayout
    private val allFiles by lazy { projectManager.listFiles(project) }
    private val symbols by lazy { SymbolIndexer.indexProject(project, allFiles) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(ctx).apply {
            text = "Search"
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = Typeface.SERIF
            textSize = 16f
            setPadding(48, 40, 48, 8)
        })
        root.addView(TextView(ctx).apply {
            text = "FILES \u00b7 FUNCTIONS \u00b7 LOCALS"
            setTextColor(Color.parseColor("#766E5E"))
            textSize = 9f
            setPadding(48, 0, 48, 16)
        })

        val input = EditText(ctx).apply {
            hint = "search this project"
            setHintTextColor(Color.parseColor("#766E5E"))
            setTextColor(Color.parseColor("#E9E1CF"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(24, 24, 24, 24)
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = 48; marginEnd = 48
        })

        val scroll = ScrollView(ctx)
        results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 900))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = render(s?.toString().orEmpty())
        })

        render("")
        return root
    }

    private fun render(query: String) {
        results.removeAllViews()
        val ctx = requireContext()

        if (query.isNotBlank()) {
            for (f in allFiles.filter { it.isFile && it.name.contains(query, ignoreCase = true) }) {
                results.addView(rowFile(ctx, f))
            }
        }

        for (sym in SymbolIndexer.search(symbols, query).take(50)) {
            results.addView(rowSymbol(ctx, sym))
        }

        if (results.childCount == 0) {
            results.addView(TextView(ctx).apply {
                text = if (query.isBlank()) "Type to search files, functions, and locals." else "No matches."
                setTextColor(Color.parseColor("#766E5E")); textSize = 12f
                setPadding(48, 24, 48, 24)
            })
        }
    }

    private fun rowFile(ctx: android.content.Context, f: File): View = TextView(ctx).apply {
        text = "\u25C8  ${f.relativeTo(project).path}"
        setTextColor(Color.parseColor("#E9E1CF")); textSize = 13f
        setPadding(48, 16, 48, 16)
        setOnClickListener { onJumpTo(f, 1); dismiss() }
    }

    private fun rowSymbol(ctx: android.content.Context, sym: SymbolIndexer.Symbol): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 12, 48, 12) }
        row.addView(TextView(ctx).apply {
            text = "${sym.name}  \u00b7  ${sym.kind}"
            setTextColor(Color.parseColor("#C9A3FF")); textSize = 13f
        })
        row.addView(TextView(ctx).apply {
            text = "${sym.file.relativeTo(project).path} : line ${sym.line}"
            setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
        })
        row.setOnClickListener { onJumpTo(sym.file, sym.line); dismiss() }
        return row
    }
}
