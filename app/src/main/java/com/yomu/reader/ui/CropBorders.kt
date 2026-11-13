package com.yomu.reader.ui

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.abs

/** Retire les marges blanches (ou noires) autour d'une page. */
class CropBorders : Transformation {
    override val cacheKey: String = "crop-borders-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 32 || h < 32) return input
        val row = IntArray(w)
        val col = IntArray(h)
        val ref = input.getPixel(2, 2)

        fun close(c: Int): Boolean {
            val dr = abs(((c shr 16) and 0xff) - ((ref shr 16) and 0xff))
            val dg = abs(((c shr 8) and 0xff) - ((ref shr 8) and 0xff))
            val db = abs((c and 0xff) - (ref and 0xff))
            return dr + dg + db < 40
        }
        fun rowEmpty(y: Int): Boolean {
            input.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) { if (!close(row[x])) return false; x += 3 }
            return true
        }
        fun colEmpty(x: Int): Boolean {
            input.getPixels(col, 0, 1, x, 0, 1, h)
            var y = 0
            while (y < h) { if (!close(col[y])) return false; y += 3 }
            return true
        }

        var top = 0
        while (top < h / 3 && rowEmpty(top)) top++
        var bottom = h - 1
        while (bottom > h * 2 / 3 && rowEmpty(bottom)) bottom--
        var left = 0
        while (left < w / 3 && colEmpty(left)) left++
        var right = w - 1
        while (right > w * 2 / 3 && colEmpty(right)) right--

        val cw = right - left + 1
        val ch = bottom - top + 1
        if (cw >= w - 4 && ch >= h - 4) return input
        return Bitmap.createBitmap(input, left, top, cw, ch)
    }
}
