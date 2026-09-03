package com.luaide.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

class ProjectsSheet(
    private val projectManager: ProjectManager,
    private val currentProject: File,
    private val onSwitchProject: (File) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 16)
        }
        header.addView(TextView(ctx).apply {
            text = "Projects"
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = Typeface.SERIF
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "+ new"
            setTextColor(Color.parseColor("#B98F56"))
            textSize = 12f
            setOnClickListener { promptNewProject() }
        })
        root.addView(header)

        val scroll = ScrollView(ctx)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1100))

        render()
        return root
    }

    private fun render() {
        list.removeAllViews()
        val ctx = requireContext()
        for (p in projectManager.listProjects()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(48, 18, 48, 18)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(ctx).apply {
                text = (if (p == currentProject) "\u25C9 " else "\u25CB ") + p.name
                setTextColor(if (p == currentProject) Color.parseColor("#B98F56") else Color.parseColor("#E9E1CF"))
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onSwitchProject(p); dismiss() }
            })
            row.addView(smallBtn("dup") { projectManager.duplicateProject(p); render() })
            row.addView(smallBtn("rename") { promptRename(p) })
            row.addView(smallBtn("export") { exportProject(p) })
            if (projectManager.listProjects().size > 1) {
                row.addView(smallBtn("delete") { confirmDelete(p) })
            }
            list.addView(row)
        }
    }

    private fun smallBtn(label: String, action: () -> Unit): Button = Button(requireContext()).apply {
        text = label; textSize = 9f
        setOnClickListener { action() }
    }

    private fun promptNewProject() {
        val ctx = requireContext()
        val input = EditText(ctx).apply { hint = "project_name" }
        AlertDialog.Builder(ctx)
            .setTitle("New project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val p = projectManager.createProject(name)
                    onSwitchProject(p)
                    dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRename(project: File) {
        val ctx = requireContext()
        val input = EditText(ctx).apply { setText(project.name) }
        AlertDialog.Builder(ctx)
            .setTitle("Rename project")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val renamed = projectManager.renameProject(project, newName)
                    if (project == currentProject) onSwitchProject(renamed)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(project: File) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${project.name}?")
            .setMessage("This deletes the project's files permanently.")
            .setPositiveButton("Delete") { _, _ ->
                val wasCurrent = project == currentProject
                projectManager.deleteProject(project)
                if (wasCurrent) {
                    projectManager.listProjects().firstOrNull()?.let { onSwitchProject(it) }
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportProject(project: File) {
        val outDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val zip = File(outDir, "${project.name}.zip")
        projectManager.exportProject(project, zip)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", zip
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Export ${project.name}"))
    }
}
