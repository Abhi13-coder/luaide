package com.luaide.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * This IS the packaged output of "Package Project" (spec §13) — not a separate
 * concept from the IDE. Building an APK for a user's Lua project means:
 *   1. copy their project files into this template's assets/lua_project/
 *   2. build this module
 * See packaging/README.md and .github/workflows/package-lua-project.yml.
 */
class RunnerActivity : AppCompatActivity(), LuaBridge.OutputSink {

    private var output: TextView? = null
    private lateinit var lua: LuaBridge
    private var glLua: LuaBridge? = null // separate state, only ever touched from the GL thread

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectDir = File(filesDir, "lua_project")
        copyAssetDirOnce("lua_project", projectDir)
        val rocksDir = File(projectDir, ".lua").apply { mkdirs() }
        val main = File(projectDir, "src/main.lua").let { if (it.exists()) it else File(projectDir, "main.lua") }
        val script = if (main.exists()) main.readText() else ""

        if (script.contains("graphics_on_frame")) {
            // Graphics mode: the script defines its own render loop, so it gets
            // a dedicated Lua state driven entirely by the GL thread — never
            // shared with a main-thread state, which would be a real data race.
            val gl = LuaBridge(this, rocksDir.absolutePath)
            glLua = gl
            setContentView(LuaGLSurfaceView(this, gl, script))
            lua = gl // androidToast/etc still route through this activity's overrides
        } else {
            setContentView(R.layout.activity_runner)
            output = findViewById(R.id.runnerOutput)
            lua = LuaBridge(this, rocksDir.absolutePath)
            if (main.exists()) {
                lua.eval(script, "@${main.name}")
            } else {
                onStderr("no main.lua found in the packaged project")
            }
        }
    }

    private fun copyAssetDirOnce(assetPath: String, dest: File) {
        val marker = File(dest, ".copied")
        if (marker.exists()) return
        dest.mkdirs()
        fun copy(rel: String, outDir: File) {
            val children = try { assets.list(rel) } catch (e: Exception) { null }
            if (children == null || children.isEmpty()) {
                outDir.parentFile?.mkdirs()
                try {
                    assets.open(rel).use { input -> outDir.outputStream().use { input.copyTo(it) } }
                } catch (e: Exception) { /* directory with no listable children, nothing to copy */ }
            } else {
                outDir.mkdirs()
                for (child in children) copy(if (rel.isEmpty()) child else "$rel/$child", File(outDir, child))
            }
        }
        for (child in assets.list(assetPath) ?: emptyArray()) {
            copy("$assetPath/$child", File(dest, child))
        }
        marker.createNewFile()
    }

    override fun onStdout(line: String) = appendLine("> $line")
    override fun onStderr(line: String) = appendLine("! $line")

    override fun androidToast(message: String) {
        runOnUiThread { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun androidClipboardCopy(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lua-app", text))
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
    } catch (e: Exception) { "" }

    private val overlay by lazy { OverlayManager(this) }
    override fun androidOverlayShow(text: String, x: Int, y: Int) = overlay.show(text, x, y)
    override fun androidOverlayHide() = overlay.hide()
    override fun androidOverlayHasPermission(): Boolean = overlay.hasPermission()
    override fun androidOverlayRequestPermission() = overlay.requestPermission()

    private fun appendLine(line: String) {
        val v = output ?: run { android.util.Log.i("LuaApp", line); return }
        runOnUiThread { v.append("$line\n") }
    }

    override fun onDestroy() {
        lua.close()
        super.onDestroy()
    }
}
