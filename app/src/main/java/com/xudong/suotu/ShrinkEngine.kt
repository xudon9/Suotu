package com.xudong.suotu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Result of a shrink operation. */
data class ShrinkResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val quality: Int,
    val format: EncodedFormat,
    val sourceBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** True when the budget could not be met even at the lowest acceptable quality. */
    val budgetMissed: Boolean,
) {
    val outputBytes: Int get() = bytes.size

    val ratio: Float
        get() = if (sourceBytes > 0) bytes.size.toFloat() / sourceBytes.toFloat() else 1f

    // ByteArray in a data class needs these to behave sanely.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ShrinkResult && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}

enum class EncodedFormat(val mimeType: String, val extension: String, val label: String) {
    JPEG("image/jpeg", "jpg", "JPEG"),
    WEBP("image/webp", "webp", "WebP"),
}

/**
 * Turns a large screenshot into the smallest file that still reads cleanly.
 *
 * Two stages:
 *  1. Downscale to the preset's target width (never upscaling).
 *  2. Binary-search the encoder quality to land just under the byte budget, and do that
 *     for each candidate format, preferring the one that keeps the highest quality.
 *
 * This is the automation of the usual manual loop: export, look at the size, nudge the
 * quality slider, export again.
 */
object ShrinkEngine {

    /** Never go below this quality — past here text starts to visibly mush. */
    private const val MIN_QUALITY = 35

    /**
     * Quality ceiling, chosen from measurement rather than taste.
     *
     * An OCR sweep over rendered UI text (tools/readability_sweep.py) found WebP q40
     * and q90 equally legible — 100% OCR accuracy at both — while q90 cost ~70% more
     * bytes. Since the byte budget almost never binds for screenshots, a search that
     * maximises quality within the budget would always land on ~95 and ship those
     * wasted bytes for no visible gain. Capping here keeps the sweet spot and lets
     * the budget act purely as a safety net for photo-like content.
     */
    private const val MAX_QUALITY = 80

    /** Enough steps to pin quality within ~1 point over the 35..80 range. */
    private const val SEARCH_STEPS = 6

    /**
     * Shrink [uri] to [targetWidth] pixels wide, optionally cropping first.
     *
     * @param targetWidth output width in pixels; the image is never upscaled.
     * @param budgetBytes soft byte budget the quality search aims to fit under.
     * @param crop region of the SOURCE image to keep, in normalised 0..1 coordinates,
     *        or null for the whole frame. Normalised so it survives the decode
     *        subsampling below, which changes the pixel dimensions underfoot.
     */
    fun shrink(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        budgetBytes: Int,
        formatPolicy: FormatPolicy,
        crop: CropRect? = null,
    ): ShrinkResult {
        val sourceBytes = querySize(context, uri)
        val (srcWidth, srcHeight) = readDimensions(context, uri)

        // When cropping, the region must reach targetWidth, so subsample against the
        // cropped width rather than the full image.
        val effectiveSourceWidth = crop
            ?.let { (srcWidth * it.width).toInt().coerceAtLeast(1) }
            ?: srcWidth
        val sampleSize = computeSampleSize(effectiveSourceWidth, targetWidth)
        val decoded = decode(context, uri, sampleSize)
            ?: throw IllegalStateException("Could not decode the image")

        val oriented = applyExifRotation(context, uri, decoded)
        val cropped = crop?.let { applyCrop(oriented, it) } ?: oriented
        if (cropped !== oriented) oriented.recycle()

        val scaled = scaleToWidth(cropped, targetWidth)
        if (scaled !== cropped) cropped.recycle()

        val candidates = when (formatPolicy) {
            FormatPolicy.JPEG_ONLY -> listOf(EncodedFormat.JPEG)
            FormatPolicy.AUTO -> listOf(EncodedFormat.WEBP, EncodedFormat.JPEG)
        }

        val best = candidates
            .map { format -> searchQuality(scaled, format, budgetBytes) }
            .reduce { a, b -> pickBetter(a, b, budgetBytes) }

        val result = ShrinkResult(
            bytes = best.bytes,
            width = scaled.width,
            height = scaled.height,
            quality = best.quality,
            format = best.format,
            sourceBytes = sourceBytes,
            sourceWidth = srcWidth,
            sourceHeight = srcHeight,
            budgetMissed = best.bytes.size > budgetBytes,
        )
        scaled.recycle()
        return result
    }

    /** Convenience overload for the background watcher, which works in presets. */
    fun shrink(
        context: Context,
        uri: Uri,
        preset: Preset,
        formatPolicy: FormatPolicy,
    ): ShrinkResult = shrink(
        context, uri, preset.targetWidth, preset.budgetBytes, formatPolicy, null
    )

    /**
     * A modest-resolution copy of the source, for the crop canvas.
     *
     * Deliberately not the full bitmap: the crop UI only needs enough pixels to aim
     * with, and decoding a 12-megapixel photo at full size risks an OOM on the main
     * screen for no visual benefit.
     */
    fun loadPreview(context: Context, uri: Uri, maxWidth: Int = 1080): Bitmap {
        val (srcWidth, _) = readDimensions(context, uri)
        val sample = computeSampleSize(srcWidth, maxWidth)
        val decoded = decode(context, uri, sample)
            ?: throw IllegalStateException("Could not decode the image")
        val oriented = applyExifRotation(context, uri, decoded)
        if (oriented !== decoded) decoded.recycle()
        return oriented
    }

    /** Crop [source] to the normalised [rect]. */
    private fun applyCrop(source: Bitmap, rect: CropRect): Bitmap {
        val x = (source.width * rect.left).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * rect.top).toInt().coerceIn(0, source.height - 1)
        val w = (source.width * rect.width).toInt()
            .coerceIn(1, source.width - x)
        val h = (source.height * rect.height).toInt()
            .coerceIn(1, source.height - y)
        if (x == 0 && y == 0 && w == source.width && h == source.height) return source
        return Bitmap.createBitmap(source, x, y, w, h)
    }

    private data class Encoded(
        val bytes: ByteArray,
        val quality: Int,
        val format: EncodedFormat,
    )

    /**
     * Prefer the candidate that fits the budget; among those that fit, prefer the one
     * encoded at the higher quality (better fidelity for a comparable size). If neither
     * fits, take the smaller file.
     */
    private fun pickBetter(a: Encoded, b: Encoded, budget: Int): Encoded {
        val aFits = a.bytes.size <= budget
        val bFits = b.bytes.size <= budget
        return when {
            aFits && !bFits -> a
            bFits && !aFits -> b
            aFits && bFits -> if (a.quality >= b.quality) a else b
            else -> if (a.bytes.size <= b.bytes.size) a else b
        }
    }

    /**
     * Binary-search the highest quality whose encoded size still fits the budget.
     * ~6 encodes of an 800px image is a few hundred milliseconds.
     */
    private fun searchQuality(bitmap: Bitmap, format: EncodedFormat, budget: Int): Encoded {
        var low = MIN_QUALITY
        var high = MAX_QUALITY
        var bestFitting: Encoded? = null
        var smallest: Encoded? = null

        repeat(SEARCH_STEPS) {
            if (low > high) return@repeat
            val mid = (low + high) / 2
            val bytes = encode(bitmap, format, mid)
            val candidate = Encoded(bytes, mid, format)

            if (smallest == null || bytes.size < smallest!!.bytes.size) {
                smallest = candidate
            }

            if (bytes.size <= budget) {
                // Fits — remember it and try to spend the remaining budget on quality.
                if (bestFitting == null || mid > bestFitting!!.quality) {
                    bestFitting = candidate
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        // If nothing fit, return the smallest we produced (caller flags budgetMissed).
        return bestFitting ?: smallest ?: Encoded(
            encode(bitmap, format, MIN_QUALITY), MIN_QUALITY, format
        )
    }

    private fun encode(bitmap: Bitmap, format: EncodedFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val compressFormat = when (format) {
            EncodedFormat.JPEG -> Bitmap.CompressFormat.JPEG
            EncodedFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
        }
        bitmap.compress(compressFormat, quality, out)
        return out.toByteArray()
    }

    /** Largest power-of-two subsample that still leaves us at or above the target width. */
    private fun computeSampleSize(srcWidth: Int, targetWidth: Int): Int {
        if (srcWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= targetWidth) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        // Never upscale: enlarging a crop adds bytes without adding information.
        if (source.width <= targetWidth) return source
        val scale = targetWidth.toFloat() / source.width.toFloat()
        val newHeight = max(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, targetWidth, newHeight, true)
    }

    private fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun readDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return options.outWidth to options.outHeight
    }

    /**
     * Screenshots are normally upright, but images shared from a camera roll may carry
     * an EXIF rotation. Bake it in so the shrunk copy looks like the original.
     */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun querySize(context: Context, uri: Uri): Long =
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?: 0L
        } catch (_: Exception) {
            0L
        }

    /**
     * Write the result into cache/shared so FileProvider can hand it to WeChat/Telegram.
     * Old files are swept first so the cache does not grow without bound.
     */
    fun writeToCache(context: Context, result: ShrinkResult): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        sweepOldFiles(dir)
        val name = "Suotu_${System.currentTimeMillis()}.${result.format.extension}"
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(result.bytes) }
        return file
    }

    private fun sweepOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }
}
