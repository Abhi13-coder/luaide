package com.luaide.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import kotlin.concurrent.thread

class RocksPluginsSheet(
    private val project: File,
    private val rocksDir: File
) : BottomSheetDialogFragment() {

    private val prefs by lazy { requireContext().getSharedPreferences("plugins", Context.MODE_PRIVATE) }
    private val PLUGIN_DEFS = listOf(
        Triple("lua-lsp", "Lua LSP", "IntelliSense, go to definition"),
        Triple("formatter", "Formatter", "Stylua-style formatting"),
        Triple("git-lens", "Git Lens", "Git blame & history"),
    )

    private lateinit var body: LinearLayout
    private var activeTab = "rocks"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val header = TextView(ctx).apply {
            text = "More"
            setTextColor(Color.parseColor("#E9E1CF"))
            typeface = Typeface.SERIF
            textSize = 16f
            setPadding(48, 40, 48, 16)
        }
        root.addView(header)

        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 0, 48, 16)
        }
        listOf("rocks" to "ROCKS", "plugins" to "PLUGINS", "git" to "GIT", "build" to "BUILD APK").forEach { (key, label) ->
            val tab = TextView(ctx).apply {
                text = label
                setTextColor(if (key == activeTab) Color.parseColor("#B98F56") else Color.parseColor("#A89F8C"))
                textSize = 11f
                setPadding(0, 12, 48, 12)
                setOnClickListener { activeTab = key; render() }
            }
            tabs.addView(tab)
        }
        root.addView(tabs)

        val scroll = ScrollView(ctx)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800))

        render()
        return root
    }

    private fun render() {
        body.removeAllViews()
        when (activeTab) {
            "rocks" -> renderRocks()
            "plugins" -> renderPlugins()
            "git" -> renderGit()
            "build" -> renderBuild()
        }
    }

    private fun renderBuild() {
        val ctx = requireContext()
        val ghPrefs = ctx.getSharedPreferences("github", Context.MODE_PRIVATE)

        body.addView(TextView(ctx).apply {
            text = "PREVIEW (ON-DEVICE)"
            setTextColor(Color.parseColor("#766E5E")); textSize = 10f
            setPadding(48, 8, 48, 4)
        })
        val previewStatus = TextView(ctx).apply {
            setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
            setPadding(48, 4, 48, 8)
            text = if (ApkPackager.isAvailable(ctx)) "installs a signed debug build of this project right now, no CI wait"
                   else "not available in this build \u2014 needs template.apk bundled (see packaging/README.md)"
        }
        body.addView(previewStatus)
        body.addView(Button(ctx).apply {
            text = "build + install preview"
            textSize = 10f
            isEnabled = ApkPackager.isAvailable(ctx)
            setOnClickListener {
                previewStatus.text = "building on-device\u2026"
                thread {
                    when (val result = ApkPackager.buildPreviewApk(ctx, project, ProjectManager(ctx))) {
                        is ApkPackager.Result.Ok -> {
                            Handler(Looper.getMainLooper()).post {
                                previewStatus.text = "built \u2014 opening installer"
                                startActivity(ApkPackager.installIntent(ctx, result.apkFile))
                            }
                        }
                        is ApkPackager.Result.Failed -> {
                            Handler(Looper.getMainLooper()).post {
                                previewStatus.text = "failed: ${result.reason}"
                            }
                        }
                    }
                }
            }
        })

        body.addView(TextView(ctx).apply {
            text = "CUSTOM BUILD (GITHUB ACTIONS)"
            setTextColor(Color.parseColor("#766E5E")); textSize = 10f
            setPadding(48, 24, 48, 4)
        })
        body.addView(TextView(ctx).apply {
            text = "Builds a real APK on GitHub's servers via the packaging workflow " +
                "already in this project (.github/workflows/package-lua-project.yml), " +
                "with its own app name, application ID, and icon. " +
                "Token is stored on-device only, in this app's private prefs \u2014 not encrypted."
            setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
            setPadding(48, 8, 48, 16)
        })

        fun field(label: String, prefKey: String, isPassword: Boolean = false, default: String = ""): EditText {
            body.addView(TextView(ctx).apply {
                text = label; setTextColor(Color.parseColor("#A89F8C")); textSize = 10f
                setPadding(48, 8, 48, 2)
            })
            val e = EditText(ctx).apply {
                setText(ghPrefs.getString(prefKey, default))
                setTextColor(Color.parseColor("#E9E1CF"))
                if (isPassword) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(48, 0, 48, 12)
            }
            body.addView(e)
            return e
        }

        val ownerField = field("repo owner", "owner", default = "Abhi13-coder")
        val repoField = field("repo name", "repo", default = "luaide")
        val tokenField = field("personal access token (workflow scope)", "token", isPassword = true)
        val pathField = field("project path in repo", "path").apply {
            if (text.isBlank()) setText("projects/${project.name}")
        }
        val appNameField = field("app name", "appName").apply { if (text.isBlank()) setText(project.name) }
        val appIdField = field("application id", "appId").apply {
            if (text.isBlank()) setText("com.luaide.runner.${project.name.lowercase().replace(Regex("[^a-z0-9]"), "")}")
        }

        val status = TextView(ctx).apply {
            setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
            setPadding(48, 4, 48, 16)
        }
        body.addView(Button(ctx).apply {
            text = "dispatch build on GitHub"
            textSize = 10f
            setOnClickListener {
                ghPrefs.edit()
                    .putString("owner", ownerField.text.toString())
                    .putString("repo", repoField.text.toString())
                    .putString("token", tokenField.text.toString())
                    .putString("path", pathField.text.toString())
                    .putString("appName", appNameField.text.toString())
                    .putString("appId", appIdField.text.toString())
                    .apply()
                status.text = "dispatching\u2026"
                thread {
                    val result = GitHubActionsClient.dispatchPackageWorkflow(
                        owner = ownerField.text.toString(),
                        repo = repoField.text.toString(),
                        token = tokenField.text.toString(),
                        projectPath = pathField.text.toString(),
                        appName = appNameField.text.toString(),
                        applicationId = appIdField.text.toString()
                    )
                    Handler(Looper.getMainLooper()).post {
                        status.text = when (result) {
                            is GitHubActionsClient.DispatchResult.Ok ->
                                "started \u2014 check the Actions tab on GitHub for the APK artifact"
                            is GitHubActionsClient.DispatchResult.Failed ->
                                "failed (${result.httpCode}): ${result.message.take(120)}"
                        }
                    }
                }
            }
        })
        body.addView(status)
    }

    private fun row(title: String, subtitle: String?, trailing: View?): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 24, 48, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(ctx).apply {
            text = title; setTextColor(Color.parseColor("#E9E1CF")); textSize = 13f
        })
        if (subtitle != null) {
            text.addView(TextView(ctx).apply {
                text = subtitle; setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
            })
        }
        row.addView(text)
        if (trailing != null) row.addView(trailing)
        return row
    }

    private fun renderRocks() {
        val ctx = requireContext()
        for (name in LuaRocksLite.installedRocks(rocksDir)) {
            val remove = Button(ctx).apply {
                text = "remove"
                textSize = 10f
                setOnClickListener {
                    LuaRocksLite.remove(rocksDir, name)
                    render()
                }
            }
            body.addView(row(name, "installed \u00b7 share/lua/5.4/", remove))
        }

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 24, 48, 12)
        }
        val input = EditText(ctx).apply {
            hint = "luarocks install \u2026"
            setHintTextColor(Color.parseColor("#766E5E"))
            setTextColor(Color.parseColor("#E9E1CF"))
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(ctx).apply {
            setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
            setPadding(48, 0, 48, 24)
        }
        val install = Button(ctx).apply {
            text = "install"
            textSize = 10f
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setOnClickListener
                status.text = "resolving $name\u2026"
                thread {
                    val result = if (LuaRocksLite.isNativeOnly(name)) {
                        LuaRocksLite.InstallResult.NativeUnsupported(name)
                    } else {
                        LuaRocksLite.install(rocksDir, name)
                    }
                    Handler(Looper.getMainLooper()).post {
                        status.text = when (result) {
                            is LuaRocksLite.InstallResult.Installed -> "installed ${result.rock.name} ${result.rock.version}"
                            is LuaRocksLite.InstallResult.NotFound -> "not in registry yet: ${result.name}"
                            is LuaRocksLite.InstallResult.NativeUnsupported -> "${result.name} needs a compiled .so \u2014 not buildable on-device yet"
                            is LuaRocksLite.InstallResult.Rejected -> "rejected: ${result.reason}"
                            is LuaRocksLite.InstallResult.Failed -> "failed: ${result.reason}"
                        }
                        if (result is LuaRocksLite.InstallResult.Installed) { input.text.clear(); render() }
                    }
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(install)
        body.addView(inputRow)
        body.addView(status)
    }

    private fun renderPlugins() {
        val ctx = requireContext()
        for ((key, title, subtitle) in PLUGIN_DEFS) {
            val on = prefs.getBoolean(key, key != "git-lens")
            val toggle = Switch(ctx).apply {
                isChecked = on
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            }
            body.addView(row(title, subtitle, toggle))
        }
    }

    private fun renderGit() {
        val ctx = requireContext()
        val git = ProjectGit(project)

        if (!git.isRepo()) {
            body.addView(TextView(ctx).apply {
                text = "No git repo here yet."
                setTextColor(Color.parseColor("#766E5E")); textSize = 12f
                setPadding(48, 24, 48, 12)
            })
            body.addView(Button(ctx).apply {
                text = "git init"
                textSize = 10f
                setOnClickListener { git.init(); render() }
            })
            return
        }

        body.addView(row("branch", git.currentBranch() ?: "?", null))

        val status = git.status()
        if (status.isEmpty()) {
            body.addView(row("clean", "no changes", null))
        } else {
            for (f in status) body.addView(row(f.path, f.kind, null))
        }

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 16, 48, 8)
        }
        val msgInput = EditText(ctx).apply {
            hint = "commit message"
            setHintTextColor(Color.parseColor("#766E5E"))
            setTextColor(Color.parseColor("#E9E1CF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val commitStatus = TextView(ctx).apply {
            setTextColor(Color.parseColor("#766E5E")); textSize = 10.5f
            setPadding(48, 0, 48, 16)
        }
        val commitBtn = Button(ctx).apply {
            text = "stage + commit"
            textSize = 10f
            setOnClickListener {
                val msg = msgInput.text.toString().ifBlank { "update" }
                thread {
                    git.stageAll()
                    val ok = git.commit(msg)
                    Handler(Looper.getMainLooper()).post {
                        commitStatus.text = if (ok) "committed" else "commit failed"
                        if (ok) render()
                    }
                }
            }
        }
        actionRow.addView(msgInput)
        actionRow.addView(commitBtn)
        body.addView(actionRow)
        body.addView(commitStatus)

        body.addView(TextView(ctx).apply {
            text = "LOG"
            setTextColor(Color.parseColor("#766E5E")); textSize = 10f
            setPadding(48, 20, 48, 4)
        })
        for (c in git.log(10)) {
            body.addView(row("${c.id}  ${c.shortMessage}", c.author, null))
        }
    }
}
