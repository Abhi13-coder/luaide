package com.luaide.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Triggers .github/workflows/package-lua-project.yml on the user's own repo
 * via the GitHub REST API's workflow_dispatch endpoint. This is the actual
 * mechanism behind the "Package Project" action in the app — there is no
 * Anthropic/Lua-IDE server in the loop, it's the user's repo + their token
 * talking directly to GitHub, which is also why a personal access token
 * with `workflow` scope is required as input.
 */
object GitHubActionsClient {

    sealed class DispatchResult {
        object Ok : DispatchResult()
        data class Failed(val httpCode: Int, val message: String) : DispatchResult()
    }

    /** Blocking network call — invoke from a background thread. */
    fun dispatchPackageWorkflow(
        owner: String,
        repo: String,
        ref: String = "main",
        token: String,
        projectPath: String,
        appName: String,
        applicationId: String,
        workflowFile: String = "package-lua-project.yml"
    ): DispatchResult {
        return try {
            val url = URL("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val body = JSONObject().apply {
                put("ref", ref)
                put("inputs", JSONObject().apply {
                    put("project_path", projectPath)
                    put("app_name", appName)
                    put("application_id", applicationId)
                })
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code == 204) DispatchResult.Ok
            else {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText() ?: ""
                DispatchResult.Failed(code, err)
            }
        } catch (e: Exception) {
            DispatchResult.Failed(-1, e.message ?: "network error")
        }
    }
}
