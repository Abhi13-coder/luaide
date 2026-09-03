package com.luaide.app

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * "Preview APK": builds a real, installable, signed APK for the current
 * project entirely on-device, by taking a prebuilt template APK (shipped as
 * an asset — built once by CI, see .github/workflows/android-build.yml's
 * template-embed step) and replacing its assets/lua_project/ contents with
 * the current project, then signing with apksig.
 *
 * This does NOT touch resources.arsc, the compiled manifest, or classes.dex —
 * only asset files — which is exactly why no aapt2/d8 is needed on-device:
 * those are only required when resources or code change, and here they don't.
 * That's also this path's real limitation: applicationId, app label, and
 * launcher icon are fixed to the template's (every "Preview" install uses the
 * same package name and overwrites the previous preview) — a genuinely
 * different, uniquely-named APK still goes through the GitHub Actions path
 * (RocksPluginsSheet's Build APK tab), which has a real aapt2 and can set all
 * of that per-project.
 */
object ApkPackager {

    sealed class Result {
        data class Ok(val apkFile: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val TEMPLATE_ASSET = "template.apk"

    fun isAvailable(context: Context): Boolean =
        try { context.assets.open(TEMPLATE_ASSET).use { true } } catch (e: Exception) { false }

    /** Blocking, does real file + crypto work — call from a background thread. */
    fun buildPreviewApk(context: Context, project: File, projectManager: ProjectManager): Result {
        if (!isAvailable(context)) {
            return Result.Failed("template.apk isn't bundled in this build yet \u2014 see packaging/README.md")
        }
        return try {
            val work = File(context.cacheDir, "apk-build").apply { deleteRecursively(); mkdirs() }
            val templateCopy = File(work, "template.apk")
            context.assets.open(TEMPLATE_ASSET).use { input -> templateCopy.outputStream().use { input.copyTo(it) } }

            val unsigned = File(work, "unsigned.apk")
            rewriteAssets(templateCopy, unsigned, project, projectManager)

            val signed = File(work, "preview-signed.apk")
            sign(context, unsigned, signed)

            Result.Ok(signed)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun rewriteAssets(templateApk: File, outputApk: File, project: File, projectManager: ProjectManager) {
        ZipFile(templateApk).use { zip ->
            ZipOutputStream(outputApk.outputStream()).use { out ->
                // Copy every entry from the template except old signature files
                // and the placeholder lua_project assets we're about to replace.
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("META-INF/")) continue
                    if (entry.name.startsWith("assets/lua_project/")) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }

                // Add the real, current project files under assets/lua_project/.
                for (file in projectManager.listFiles(project)) {
                    if (!file.isFile) continue
                    if (file.path.contains("${File.separator}.lua${File.separator}")) continue // don't ship the rocks cache
                    val entryName = "assets/lua_project/" + file.relativeTo(project).path.replace(File.separatorChar, '/')
                    out.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    private fun sign(context: Context, input: File, output: File) {
        val identity = DebugKeystore.getOrCreate(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "lua-ide-debug", identity.privateKey, listOf(identity.certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(29)
            .build()
            .sign()
    }

    /** Hands the signed APK to the system installer via a real content:// URI. */
    fun installIntent(context: Context, apk: File): android.content.Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        return android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
