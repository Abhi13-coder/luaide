package com.luaide.app

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * Real git operations against a project directory, via JGit (pure JVM — no
 * native `git` binary, no NDK). This is what actually backs the Git tab that
 * used to just say "not built yet".
 */
class ProjectGit(private val projectDir: File) {

    data class FileStatus(val path: String, val kind: String) // "modified" | "added" | "untracked" | "removed"
    data class CommitInfo(val id: String, val shortMessage: String, val author: String, val time: Long)

    fun isRepo(): Boolean = File(projectDir, ".git").isDirectory

    fun init(): Boolean = try {
        Git.init().setDirectory(projectDir).call().close()
        true
    } catch (e: Exception) {
        false
    }

    private fun open(): Git? = if (isRepo()) Git.open(projectDir) else null

    fun currentBranch(): String? = open()?.use { it.repository.branch }

    fun status(): List<FileStatus> {
        val git = open() ?: return emptyList()
        return git.use {
            val st = it.status().call()
            val out = mutableListOf<FileStatus>()
            st.modified.forEach { p -> out += FileStatus(p, "modified") }
            st.added.forEach { p -> out += FileStatus(p, "added") }
            st.changed.forEach { p -> out += FileStatus(p, "staged") }
            st.untracked.forEach { p -> out += FileStatus(p, "untracked") }
            st.missing.forEach { p -> out += FileStatus(p, "removed") }
            out
        }
    }

    fun stageAll(): Boolean {
        val git = open() ?: return false
        return git.use {
            it.add().addFilepattern(".").call()
            true
        }
    }

    fun commit(message: String, authorName: String = "Lua IDE", authorEmail: String = "local@lua-ide"): Boolean {
        val git = open() ?: return false
        return git.use {
            it.commit().setMessage(message).setAuthor(authorName, authorEmail).call()
            true
        }
    }

    fun log(limit: Int = 20): List<CommitInfo> {
        val git = open() ?: return emptyList()
        return git.use {
            try {
                it.log().setMaxCount(limit).call().map { c ->
                    CommitInfo(c.name.take(7), c.shortMessage, c.authorIdent.name, c.commitTime.toLong() * 1000)
                }
            } catch (e: Exception) {
                emptyList() // e.g. "no HEAD" on a brand new repo with no commits yet
            }
        }
    }
}
