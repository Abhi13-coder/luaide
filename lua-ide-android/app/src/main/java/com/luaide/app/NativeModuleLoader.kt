package com.luaide.app

import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gatekeeper for native Lua modules (compiled .so files used via `require("name")`).
 *
 * Section 5 of the build spec requires the loader to validate ABI, architecture,
 * Lua ABI compatibility, and that we're not reaching into /system/lib or /vendor/lib.
 * This does the checks that are actually possible from Kotlin *before* we ever
 * hand the path to dlopen (via System.load) — a corrupt or foreign-ABI .so should
 * never even reach the linker.
 */
object NativeModuleLoader {

    sealed class Result {
        data class Ok(val moduleName: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    // ELF constants we care about for armeabi-v7a (32-bit ARM, little-endian).
    private const val ELFCLASS32: Byte = 1
    private const val ELFDATA2LSB: Byte = 1
    private const val EM_ARM: Int = 40

    /**
     * @param soFile candidate module, e.g. <project>/.lua/native/inspect.so
     * @param allowedRoots app-owned directories the file is permitted to live under
     *        (the project's own native-module dir and the app's private files dir —
     *        never anything under /system, /vendor, or another app's data dir).
     */
    fun validate(soFile: File, allowedRoots: List<File>): Result {
        val moduleName = soFile.nameWithoutExtension.removePrefix("lib")

        // 1. Path scoping — refuse anything outside our own sandbox, full stop.
        val canonical = soFile.canonicalFile
        val insideAllowedRoot = allowedRoots.any { root ->
            canonical.path.startsWith(root.canonicalFile.path + File.separator)
        }
        if (!insideAllowedRoot) {
            return Result.Rejected("Module is outside the app's own storage — refusing to load $canonical")
        }

        // 2. Device ABI — this build only ships/accepts armeabi-v7a.
        if (!Build.SUPPORTED_ABIS.contains("armeabi-v7a")) {
            return Result.Rejected("Device does not support armeabi-v7a")
        }

        if (!soFile.exists() || soFile.length() < 64) {
            return Result.Rejected("Not a valid ELF file: too small or missing")
        }

        // 3. ELF header — must be 32-bit little-endian ARM, matching armeabi-v7a exactly.
        RandomAccessFile(soFile, "r").use { raf ->
            val header = ByteArray(20)
            raf.readFully(header)
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) {
                return Result.Rejected("Not an ELF binary")
            }
            if (header[4] != ELFCLASS32) {
                return Result.Rejected("Wrong ELF class — expected 32-bit (armeabi-v7a), got 64-bit")
            }
            if (header[5] != ELFDATA2LSB) {
                return Result.Rejected("Wrong byte order for ARM (expected little-endian)")
            }
            val machine = ByteBuffer.wrap(header, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            if (machine != EM_ARM) {
                return Result.Rejected("Wrong target architecture (ELF e_machine=$machine, expected EM_ARM=$EM_ARM)")
            }
        }

        // 4. Naming convention Lua's require() depends on: a C module "foo" must
        //    export luaopen_foo. We can't inspect the dynamic symbol table without
        //    adding an ELF section-header parser (future work); for now we enforce
        //    the filename convention so require("foo") -> foo.so / libfoo.so is
        //    unambiguous, and let luaL_requiref's own lookup fail loudly and
        //    safely at dlopen time if the symbol is actually missing.
        if (!moduleName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            return Result.Rejected("Module filename '$moduleName' is not a valid Lua identifier")
        }

        return Result.Ok(moduleName)
    }
}
