package com.eagertolearn.bookscanner.core

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

/**
 * Loads OpenCV native libs and PDFBox Android resources. Must be called from every entry point
 * that touches them: WorkManager may run workers in a fresh process where no Activity was created.
 */
object NativeLibs {

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        PDFBoxResourceLoader.init(context.applicationContext)
        initialized = true
    }
}
