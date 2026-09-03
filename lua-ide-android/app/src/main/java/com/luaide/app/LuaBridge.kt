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

    /** Compiles [code] without running it; returns a human-readable error, or null if it's valid Lua. */
    fun checkSyntax(code: String): String? {
        if (!isOpen) return null
        return nativeCheckSyntax(statePtr, code)
    }

    fun close() {
        if (isOpen) nativeClose(statePtr)
    }

    /** Implemented in Kotlin; called from native code via JNI method IDs looked up at nativeOpen time. */
    interface OutputSink {
        fun onStdout(line: String)
        fun onStderr(line: String)

        // android.* bridge (spec §7) — default no-ops so existing sinks compile
        // unchanged; override any of these for a real implementation.
        fun androidToast(message: String) {}
        fun androidClipboardCopy(text: String) {}
        fun androidClipboardPaste(): String = ""
        fun androidDeviceModel(): String = android.os.Build.MODEL ?: "unknown"
        fun androidDeviceBrand(): String = android.os.Build.BRAND ?: "unknown"
        fun androidSdkInt(): Int = android.os.Build.VERSION.SDK_INT
        fun androidStoragePath(kind: String): String = ""
        fun androidHttpGet(url: String): String = ""
        fun androidOverlayShow(text: String, x: Int, y: Int) {}
        fun androidOverlayHide() {}
        fun androidOverlayHasPermission(): Boolean = false
        fun androidOverlayRequestPermission() {}
        fun androidGlClear(r: Float, g: Float, b: Float, a: Float) {}
    }

    private external fun nativeOpen(sink: OutputSink, projectRocksPath: String): Long
    private external fun nativeEval(statePtr: Long, code: String, chunkName: String): Boolean
    private external fun nativeCheckSyntax(statePtr: Long, code: String): String?
    private external fun nativeClose(statePtr: Long)

    companion object {
        init { System.loadLibrary("luabridge") }
    }
}
