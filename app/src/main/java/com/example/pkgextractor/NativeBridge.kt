package com.example.pkgextractor

object NativeBridge {
    init { System.loadLibrary("pkgextract") }

    /** Called from native code. Return false to cancel. */
    fun interface Progress { fun onProgress(percent: Int, file: String): Boolean }

    /** @return empty string on success, otherwise an error message. */
    external fun extract(pkgPath: String, outDir: String, progress: Progress): String
}
