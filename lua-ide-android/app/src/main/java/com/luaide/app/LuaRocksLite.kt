package com.luaide.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "LuaRocks-lite": a from-scratch package manager, not a port of the real
 * LuaRocks toolchain (spec §4 allows this fallback since the full C build
 * toolchain doesn't run on-device). Understands `install`/`list`/`remove`
 * for both pure-Lua modules AND real precompiled native (.so) modules.
 *
 * Native rocks are NOT compiled on-device — there's no NDK on the phone —
 * but they ARE compiled for real, once, via .github/workflows/build-native-
 * rocks.yml (which uses the same NDK this repo's other CI jobs already use),
 * and published to a stable release URL. This is the actual working
 * alternative to an on-device compiler: compile once in CI, dlopen on-device
 * (Lua's own require() already supports this via LUA_USE_DLOPEN).
 */
object LuaRocksLite {

    data class Rock(val name: String, val rawUrl: String, val version: String, val native: Boolean = false)

    private val REGISTRY = mapOf(
        "json" to Rock("json", "https://raw.githubusercontent.com/rxi/json.lua/master/json.lua", "0.1.2"),
        "inspect" to Rock("inspect", "https://raw.githubusercontent.com/kikito/inspect.lua/master/inspect.lua", "3.1.3"),
        "middleclass" to Rock("middleclass", "https://raw.githubusercontent.com/kikito/middleclass/master/middleclass.lua", "4.1.1"),
        "lume" to Rock("lume", "https://raw.githubusercontent.com/rxi/lume/master/lume.lua", "2.3.0"),
        // Built by build-native-rocks.yml against this project's own Lua headers,
        // for armeabi-v7a specifically — not a generic binary found online.
        "lfs" to Rock(
            "lfs",
            "https://github.com/Abhi13-coder/luaide/releases/download/native-rocks-armeabi-v7a/libluafilesystem.so",
            "1.8.0",
            native = true
        ),
    )

    sealed class InstallResult {
        data class Installed(val rock: Rock, val path: File) : InstallResult()
        data class NotFound(val name: String) : InstallResult()
        data class NativeUnsupported(val name: String) : InstallResult()
        data class Rejected(val name: String, val reason: String) : InstallResult()
        data class Failed(val name: String, val reason: String) : InstallResult()
    }

    /** Mirrors `package.path`'s `share/lua/5.4/?.lua` entry set up in lua_bridge.c. */
    private fun luaDir(rocksRoot: File) = File(rocksRoot, "share/lua/5.4").apply { mkdirs() }

    /** Mirrors `package.cpath`'s `lib/lua/5.4/?.so` entry set up in lua_bridge.c. */
    private fun cDir(rocksRoot: File) = File(rocksRoot, "lib/lua/5.4").apply { mkdirs() }

    fun installedRocks(rocksRoot: File): List<String> {
        val pure = luaDir(rocksRoot).listFiles { f -> f.extension == "lua" }?.map { it.nameWithoutExtension } ?: emptyList()
        val native = cDir(rocksRoot).listFiles { f -> f.extension == "so" }?.map { it.nameWithoutExtension } ?: emptyList()
        return (pure + native).sorted()
    }

    fun remove(rocksRoot: File, name: String): Boolean {
        val pure = File(luaDir(rocksRoot), "$name.lua")
        val native = File(cDir(rocksRoot), "$name.so")
        var removed = false
        if (pure.exists()) removed = pure.delete() || removed
        if (native.exists()) removed = native.delete() || removed
        return removed
    }

    /** Blocking network call — run this off the main thread from the caller. */
    fun install(rocksRoot: File, name: String): InstallResult {
        val rock = REGISTRY[name] ?: return InstallResult.NotFound(name)
        return try {
            val conn = URL(rock.rawUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true // GitHub Releases 302s to a signed S3 URL
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                return InstallResult.Failed(name, "HTTP ${conn.responseCode}")
            }
            val bytes = conn.inputStream.readBytes()

            if (rock.native) {
                val dest = File(cDir(rocksRoot), "$name.so")
                dest.writeBytes(bytes)
                // Never trust a downloaded .so on its filename alone — validate
                // the real ELF header before it's anywhere near dlopen/require().
                val allowedRoots = listOf(cDir(rocksRoot))
                when (val check = NativeModuleLoader.validate(dest, allowedRoots)) {
                    is NativeModuleLoader.Result.Rejected -> {
                        dest.delete()
                        InstallResult.Rejected(name, check.reason)
                    }
                    is NativeModuleLoader.Result.Ok -> InstallResult.Installed(rock, dest)
                }
            } else {
                val dest = File(luaDir(rocksRoot), "$name.lua")
                dest.writeBytes(bytes)
                InstallResult.Installed(rock, dest)
            }
        } catch (e: Exception) {
            InstallResult.Failed(name, e.message ?: "network error")
        }
    }

    /** For a rock with no build recipe in build-native-rocks.yml yet — say so instead of faking success. */
    fun isNativeOnly(name: String): Boolean =
        name in setOf("luasocket", "bit32") && REGISTRY[name] == null
}
