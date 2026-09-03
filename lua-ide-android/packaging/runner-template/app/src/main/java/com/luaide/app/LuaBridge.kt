package com.luaide.app

/**
 * Thin Kotlin wrapper around the native Lua 5.4 VM (see cpp/lua_bridge.c).
 * One instance == one live Lua state == one open project's runtime.
 */
class LuaBridge(private val output: OutputSink, rocksDir: String) {

    private val statePtr: Long = nativeOpen(output, rocksDir)

    val isOpen: Boolean get() = statePtr != 0L

    /** Runs a chunk of Lua source. Returns false if it raised an error (already emitted to [output]). */
    fun eval(code: String, chunkName: String = "=chunk"): Boolean {
        check(isOpen) { "Lua state failed to initialize" }
        return nativeEval(statePtr, code, chunkName)
    }

    fun close() {
        if (isOpen) nativeClose(statePtr)
    }

    /** Implemented in Kotlin; called from native code via JNI method IDs looked up at nativeOpen time. */
    interface OutputSink {
        fun onStdout(line: String)
        fun onStderr(line: String)
    }

    private external fun nativeOpen(sink: OutputSink, projectRocksPath: String): Long
    private external fun nativeEval(statePtr: Long, code: String, chunkName: String): Boolean
    private external fun nativeClose(statePtr: Long)

    companion object {
        init { System.loadLibrary("luabridge") }
    }
}
