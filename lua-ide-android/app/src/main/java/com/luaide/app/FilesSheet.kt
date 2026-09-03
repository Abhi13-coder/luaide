package com.luaide.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

class FilesSheet(
    private val project: File,
    private val projectManager: ProjectManager,
    private val onFileSelected: (File) -> Unit,
    private val onNewFile: ((String) -> Unit)? = null
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 24)
        }
        titleRow.addView(TextView(ctx).apply {
            text = project.name
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = Typeface.SERIF
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onNewFile != null) {
            titleRow.addView(TextView(ctx).apply {
                text = "+"
                setTextColor(Color.parseColor("#B98F56"))
                textSize = 20f
                setOnClickListener {
                    val input = EditText(ctx).apply { hint = "src/new_module.lua" }
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("New file")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val path = input.text.toString().trim()
                            if (path.isNotEmpty()) { dismiss(); onNewFile.invoke(path) }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
        }
        list.addView(titleRow)

        val projectRoot = project.canonicalPath
        for (file in projectManager.listFiles(project)) {
            val depth = file.canonicalPath.removePrefix(projectRoot).count { it == File.separatorChar }
            val row = TextView(ctx).apply {
                text = (if (file.isDirectory) "\uD83D\uDCC1 " else "\u25C8 ") + file.name
                setTextColor(if (file.isDirectory) Color.parseColor("#A89F8C") else Color.parseColor("#E9E1CF"))
                textSize = 13f
                setPadding(48 + depth * 36, 20, 48, 20)
                setOnClickListener {
                    if (file.isFile) {
                        onFileSelected(file)
                        dismiss()
                    }
                }
            }
            list.addView(row)
        }

        return scroll
    }
}
