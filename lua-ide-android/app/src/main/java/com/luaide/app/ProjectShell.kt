package com.luaide.app

import java.io.File

/**
 * Not a real POSIX shell — a small real interpreter for the subset spec §9
 * actually asks for, operating on the real project filesystem. `cwd` is
 * always kept inside the project directory; you cannot `cd` out of it.
 */
class ProjectShell(private val project: File) {

    var cwd: File = project
        private set

    sealed class Output {
        data class Text(val lines: List<String>) : Output()
        object Cleared : Output()
        object NotAShellCommand : Output() // caller should fall through to the Lua REPL
    }

    fun run(line: String): Output {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0].isBlank()) return Output.Text(emptyList())

        return when (parts[0]) {
            "ls" -> Output.Text(ls(parts.getOrNull(1)))
            "pwd" -> Output.Text(listOf("/" + cwd.relativeTo(project).path))
            "cd" -> Output.Text(cd(parts.getOrNull(1) ?: "."))
            "cat" -> Output.Text(cat(parts.getOrNull(1)))
            "mkdir" -> Output.Text(mkdir(parts.getOrNull(1)))
            "rm" -> Output.Text(rm(parts.getOrNull(1)))
            "clear" -> Output.Cleared
            "luarocks" -> Output.Text(luarocks(parts.drop(1)))
            else -> Output.NotAShellCommand
        }
    }

    private fun resolve(path: String): File {
        val target = if (path.startsWith("/")) File(project, path.removePrefix("/")) else File(cwd, path)
        return target.canonicalFile
    }

    private fun withinProject(f: File): Boolean =
        f.canonicalPath.startsWith(project.canonicalPath)

    private fun ls(pathArg: String?): List<String> {
        val dir = if (pathArg != null) resolve(pathArg) else cwd
        if (!withinProject(dir) || !dir.isDirectory) return listOf("ls: not a directory: ${pathArg ?: "."}")
        return dir.listFiles()?.sortedBy { it.name }?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?: emptyList()
    }

    private fun cd(pathArg: String): List<String> {
        val target = resolve(pathArg)
        if (!withinProject(target)) return listOf("cd: cannot leave the project directory")
        if (!target.isDirectory) return listOf("cd: not a directory: $pathArg")
        cwd = target
        return emptyList()
    }

    private fun cat(pathArg: String?): List<String> {
        if (pathArg == null) return listOf("usage: cat <file>")
        val f = resolve(pathArg)
        if (!withinProject(f) || !f.isFile) return listOf("cat: no such file: $pathArg")
        return f.readLines()
    }

    private fun mkdir(pathArg: String?): List<String> {
        if (pathArg == null) return listOf("usage: mkdir <dir>")
        val d = resolve(pathArg)
        if (!withinProject(d)) return listOf("mkdir: outside project")
        return if (d.mkdirs()) emptyList() else listOf("mkdir: failed: $pathArg")
    }

    private fun rm(pathArg: String?): List<String> {
        if (pathArg == null) return listOf("usage: rm <path>")
        val f = resolve(pathArg)
        if (!withinProject(f)) return listOf("rm: outside project")
        if (f == project) return listOf("rm: refusing to delete the project root")
        return if (f.deleteRecursively()) emptyList() else listOf("rm: failed: $pathArg")
    }

    private fun luarocks(args: List<String>): List<String> {
        val rocksDir = File(project, ".lua")
        return when (args.getOrNull(0)) {
            "list" -> LuaRocksLite.installedRocks(rocksDir).ifEmpty { listOf("(no rocks installed)") }
            "remove" -> {
                val name = args.getOrNull(1) ?: return listOf("usage: luarocks remove <name>")
                if (LuaRocksLite.remove(rocksDir, name)) listOf("removed $name") else listOf("not installed: $name")
            }
            "install" -> {
                val name = args.getOrNull(1) ?: return listOf("usage: luarocks install <name>")
                if (LuaRocksLite.isNativeOnly(name)) {
                    listOf("$name needs a compiled .so \u2014 not buildable on-device")
                } else when (val r = LuaRocksLite.install(rocksDir, name)) {
                    is LuaRocksLite.InstallResult.Installed -> listOf("installed ${r.rock.name} ${r.rock.version}")
                    is LuaRocksLite.InstallResult.NotFound -> listOf("not in registry: $name")
                    is LuaRocksLite.InstallResult.Failed -> listOf("failed: ${r.reason}")
                    is LuaRocksLite.InstallResult.NativeUnsupported -> listOf("$name needs a compiled .so")
                    is LuaRocksLite.InstallResult.Rejected -> listOf("rejected: ${r.reason}")
                }
            }
            "search" -> {
                val q = args.getOrNull(1)?.lowercase() ?: ""
                LuaRocksLite.installedRocks(rocksDir).filter { it.contains(q) }.ifEmpty { listOf("no matches (search only covers the built-in curated registry today)") }
            }
            else -> listOf("usage: luarocks [install|remove|list|search] <name>")
        }
    }
}
