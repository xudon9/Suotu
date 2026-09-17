package com.xudong.suotu

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Separable Gaussian blur over an ARGB bitmap.
 *
 * Implemented directly rather than via a platform API, for two reasons:
 *
 *  - `RenderEffect.createBlurEffect` only applies to a hardware-accelerated
 *    RenderNode, and annotations are flattened onto a software [Bitmap] canvas, where
 *    it silently has no effect.
 *  - `ScriptIntrinsicBlur` (RenderScript) is deprecated since API 31 and caps its
 *    radius at 25px, which is too small for a visible blur on a 1260px-wide screenshot.
 *
 * Separable means the 2-D kernel is applied as two 1-D passes, horizontal then vertical.
 * That is O(w·h·r) instead of O(w·h·r²) — for a 40px radius, roughly 40× less work.
 *
 * NOTE: a Gaussian blur is a smooth, information-preserving transform and is in
 * principle partially invertible. It is a visual effect here, not a redaction. For
 * genuinely private content use an opaque filled shape, which discards the pixels.
 */
object GaussianBlur {

    /**
     * @param radius blur radius in pixels; the kernel spans 2·radius+1 samples.
     * @return a new blurred bitmap, or [source] itself when the radius rounds to zero.
     */
    fun blur(source: Bitmap, radius: Float): Bitmap {
        val r = radius.roundToInt()
        if (r < 1) return source

        val w = source.width
        val h = source.height
        if (w < 2 || h < 2) return source

        val kernel = gaussianKernel(r)

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)

        blurPass(pixels, temp, w, h, kernel, horizontal = true)
        blurPass(temp, pixels, w, h, kernel, horizontal = false)

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Normalised 1-D Gaussian weights for the given radius. */
    private fun gaussianKernel(radius: Int): FloatArray {
        // sigma = r/2 puts ~95% of the kernel's weight inside the radius, which looks
        // like the blur the radius promises.
        val sigma = (radius / 2f).coerceAtLeast(0.5f)
        val size = radius * 2 + 1
        val k = FloatArray(size)
        val denom = 2f * sigma * sigma
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toFloat()
            k[i] = exp(-(x * x) / denom)
            sum += k[i]
        }
        for (i in 0 until size) k[i] /= sum
        return k
    }

    /**
     * One 1-D pass. Edge samples are clamped, so the border does not darken — averaging
     * in transparent off-image pixels would leave a dark halo around the region.
     */
    private fun blurPass(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        kernel: FloatArray,
        horizontal: Boolean,
    ) {
        val radius = kernel.size / 2
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h

        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0f
                var red = 0f
                var green = 0f
                var blue = 0f

                for (kx in kernel.indices) {
                    val offset = kx - radius
                    val s = (i + offset).coerceIn(0, inner - 1)
                    val idx = if (horizontal) o * w + s else s * w + o
                    val p = src[idx]
                    val weight = kernel[kx]

                    // Premultiply by alpha so partially transparent pixels do not bleed
                    // their colour into neighbours at full strength.
                    val pa = ((p ushr 24) and 0xFF) / 255f
                    a += weight * pa
                    red += weight * pa * ((p ushr 16) and 0xFF)
                    green += weight * pa * ((p ushr 8) and 0xFF)
                    blue += weight * pa * (p and 0xFF)
                }

                val outIdx = if (horizontal) o * w + i else i * w + o
                dst[outIdx] = if (a <= 0.0001f) {
                    0
                } else {
                    // Un-premultiply.
                    val ai = (a * 255f).roundToInt().coerceIn(0, 255)
                    val ri = (red / a).roundToInt().coerceIn(0, 255)
                    val gi = (green / a).roundToInt().coerceIn(0, 255)
                    val bi = (blue / a).roundToInt().coerceIn(0, 255)
                    (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        }
    }

    /**
     * Blur radius in pixels for a thickness slider position.
     *
     * Scaled by image width so the same slider position looks the same on a small crop
     * and on a full-resolution screenshot.
     */
    fun radiusFor(thickness: Float, imageWidth: Int): Float {
        val t = ((thickness - AnnotationThickness.MIN) /
            (AnnotationThickness.MAX - AnnotationThickness.MIN)).coerceIn(0f, 1f)
        // 0.3%..3.5% of width: at 1260px that is about 4px to 44px.
        return imageWidth * (0.003f + t * 0.032f)
    }

    /**
     * Cap on the radius used for an interactive preview.
     *
     * The cost is linear in radius, and a 44px radius over a large region is tens of
     * milliseconds — fine when flattening once, too slow to redo on every pointer move.
     */
    fun previewRadius(radius: Float): Float = ceil(radius.coerceAtMost(12f))
}
