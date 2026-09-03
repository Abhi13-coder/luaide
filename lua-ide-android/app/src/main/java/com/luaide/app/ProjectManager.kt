package com.luaide.app

import android.content.Context
import java.io.File

/**
 * Every project is a real directory under filesDir/projects/<name>/.
 * .lua/ inside a project holds its installed rocks (see LuaRocksLite) and
 * is the same path handed to LuaBridge as package.path/cpath root.
 */
class ProjectManager(context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun listProjects(): List<File> = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    fun createProject(name: String): File {
        val dir = File(root, name)
        if (dir.exists()) return dir
        dir.mkdirs()
        File(dir, "src").mkdirs()
        File(dir, ".lua").mkdirs()
        File(dir, "src/main.lua").writeText(
            "-- $name\n\nprint(\"hello from $name\")\n"
        )
        return dir
    }

    fun rocksDirFor(project: File): File = File(project, ".lua").apply { mkdirs() }

    fun listFiles(project: File): List<File> {
        val out = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach {
                if (it.isDirectory && it.name != ".lua") { out.add(it); walk(it) }
                else if (it.isFile) out.add(it)
            }
        }
        walk(project)
        return out
    }

    fun readFile(file: File): String = if (file.exists()) file.readText() else ""

    fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun createFile(project: File, relativePath: String): File {
        val f = File(project, relativePath)
        f.parentFile?.mkdirs()
        if (!f.exists()) f.writeText("")
        return f
    }

    fun duplicateProject(project: File): File {
        var name = "${project.name}_copy"
        var i = 2
        while (File(root, name).exists()) { name = "${project.name}_copy$i"; i++ }
        val dest = File(root, name)
        project.copyRecursively(dest, overwrite = false)
        return dest
    }

    fun renameProject(project: File, newName: String): File {
        val dest = File(root, newName)
        if (dest.exists() || !project.exists()) return project
        return if (project.renameTo(dest)) dest else project
    }

    /** Zips the whole project (rocks cache included \u2014 keeps it reproducible offline) to [destZip]. */
    fun exportProject(project: File, destZip: File) {
        java.util.zip.ZipOutputStream(destZip.outputStream()).use { zos ->
            val base = project.canonicalPath
            project.walkTopDown().filter { it.isFile }.forEach { f ->
                val entryName = f.canonicalPath.removePrefix(base).trimStart(File.separatorChar)
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /** Imports a project previously produced by [exportProject]. Returns the new project dir. */
    fun importProject(zipFile: File, asName: String): File {
        val dest = File(root, asName).apply { mkdirs() }
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return dest
    }

    fun deleteProject(project: File) {
        project.deleteRecursively()
    }
}
