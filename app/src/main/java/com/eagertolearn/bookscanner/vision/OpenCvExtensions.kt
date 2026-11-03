package com.eagertolearn.bookscanner.vision

import android.graphics.Bitmap
import org.opencv.core.Mat

/**
 * Memory-safety scoping extensions enforcing AGENTS.md invariants:
 * - Every OpenCV native [Mat] must have an explicit `.release()` in a `finally` block.
 * - Every Android [Bitmap] must explicitly call `.recycle()` once transferred or saved.
 */
inline fun <T : Mat, R> T.useMat(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this.release()
    }
}

inline fun <R> Bitmap.useBitmap(block: (Bitmap) -> R): R {
    try {
        return block(this)
    } finally {
        if (!this.isRecycled) {
            this.recycle()
        }
    }
}
