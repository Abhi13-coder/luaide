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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CommandPaletteSheet(
    private val onRun: () -> Unit,
    private val onOpenTerminal: () -> Unit,
    private val onOpenSearch: () -> Unit,
    private val onOpenFiles: () -> Unit,
    private val onOpenMore: () -> Unit,
    private val onNewFile: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(ctx).apply {
            text = "Command Palette"
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = Typeface.SERIF
            textSize = 16f
            setPadding(48, 40, 48, 16)
        })

        val commands = listOf(
            "Run Current File" to { dismiss(); onRun() },
            "New File\u2026" to { promptNewFile() },
            "Open Terminal / REPL" to { dismiss(); onOpenTerminal() },
            "Find in Files / Go to Definition" to { dismiss(); onOpenSearch() },
            "Browse Files" to { dismiss(); onOpenFiles() },
            "Rocks \u00b7 Plugins \u00b7 Git \u00b7 Build APK" to { dismiss(); onOpenMore() },
        )

        for ((label, action) in commands) {
            root.addView(TextView(ctx).apply {
                text = "\u203A  $label"
                setTextColor(Color.parseColor("#E9E1CF"))
                textSize = 13.5f
                setPadding(48, 22, 48, 22)
                setOnClickListener { action() }
            })
        }

        return root
    }

    private fun promptNewFile() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "src/new_module.lua"
            setHintTextColor(Color.parseColor("#766E5E"))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(ctx)
            .setTitle("New file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    dismiss()
                    onNewFile(path)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
