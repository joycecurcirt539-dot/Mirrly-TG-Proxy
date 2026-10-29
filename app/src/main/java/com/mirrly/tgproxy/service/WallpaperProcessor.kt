/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.service

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Custom Wallpaper import pipeline.
 *
 * Copies an image picked through the system photo picker into the app sandbox as a
 * normalized JPEG: EXIF orientation is baked into the pixels and the resolution is
 * reduced to screen class (cover box 1080 x 2400, hard cap ~3.5 MP), so 4K/8K camera
 * shots never reach the heap at full size.
 *
 * Size math lives in pure functions ([computeTargetSize], [computeInSampleSize],
 * [orientationTransform]) so it can be unit-tested without the Android framework.
 */
object WallpaperProcessor {

    /** Short edge of the cover box (typical phone width in px). */
    const val COVER_SHORT_EDGE = 1080

    /** Long edge of the cover box (typical tall phone height in px). */
    const val COVER_LONG_EDGE = 2400

    /** Hard pixel budget for the stored wallpaper (~13.8 MB as ARGB_8888). */
    const val MAX_PIXELS = 2400 * 1440

    private const val JPEG_QUALITY = 90

    /** Rotation in degrees plus horizontal mirror flag, derived from an EXIF orientation tag. */
    data class OrientationTransform(val rotationDegrees: Int, val mirrorX: Boolean) {
        /** 90/270 degree rotations swap width and height of the output. */
        val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270
    }

    /**
     * Output size that still covers a [COVER_SHORT_EDGE] x [COVER_LONG_EDGE] box in either
     * orientation (so ContentScale.Crop never upscales on a typical phone), never upscales the
     * source and never exceeds [MAX_PIXELS].
     */
    fun computeTargetSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        require(srcWidth > 0 && srcHeight > 0) { "Invalid image size ${srcWidth}x$srcHeight" }
        val shortSide = min(srcWidth, srcHeight).toFloat()
        val longSide = max(srcWidth, srcHeight).toFloat()

        var scale = max(COVER_SHORT_EDGE / shortSide, COVER_LONG_EDGE / longSide).coerceAtMost(1f)
        val pixels = srcWidth.toLong() * srcHeight * scale * scale
        if (pixels > MAX_PIXELS) {
            scale *= sqrt(MAX_PIXELS / pixels.toDouble()).toFloat()
        }

        val w = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (srcHeight * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * Largest power-of-two subsampling factor that keeps the decoded image at least
     * [reqWidth] x [reqHeight], so the final resize is always a downscale (no quality loss).
     */
    fun computeInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        while (srcWidth / (sample * 2) >= reqWidth && srcHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    /** Maps an EXIF orientation tag to rotation + mirror (covers all 8 EXIF variants). */
    fun orientationTransform(exifOrientation: Int): OrientationTransform = when (exifOrientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationTransform(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> OrientationTransform(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationTransform(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> OrientationTransform(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> OrientationTransform(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> OrientationTransform(270, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> OrientationTransform(270, false)
        else -> OrientationTransform(0, false)
    }

    /**
     * Attempts to open an input stream for [uri] using multiple fallbacks:
     * 1. Standard [ContentResolver.openInputStream]
     * 2. [ContentResolver.openAssetFileDescriptor] stream
     * 3. [ContentResolver.openFileDescriptor] FileInputStream
     * 4. Direct file system access if URI scheme is "file"
     */
    @Throws(IOException::class)
    fun openInputStreamFallback(resolver: ContentResolver, uri: Uri): java.io.InputStream {
        try {
            resolver.openInputStream(uri)?.let { return it }
        } catch (_: Throwable) {}

        try {
            resolver.openAssetFileDescriptor(uri, "r")?.createInputStream()?.let { return it }
        } catch (_: Throwable) {}

        try {
            resolver.openFileDescriptor(uri, "r")?.let { pfd ->
                return java.io.FileInputStream(pfd.fileDescriptor)
            }
        } catch (_: Throwable) {}

        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    return java.io.FileInputStream(f)
                }
            }
        }

        throw IOException("Cannot open image stream")
    }

    /**
     * Decodes [uri], applies EXIF orientation, downsamples and writes a JPEG into [outFile].
     * Must be called off the main thread. Returns the stored (width, height).
     */
    @Throws(IOException::class)
    fun importToFile(resolver: ContentResolver, uri: Uri, outFile: File): Pair<Int, Int> {
        val parentDir = outFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val stagedFile = File(parentDir, "${outFile.nameWithoutExtension}_stage_${System.currentTimeMillis()}.tmp")

        try {
            // Stage stream once to local disk to avoid multiple ContentProvider stream closures
            openInputStreamFallback(resolver, uri).use { input ->
                FileOutputStream(stagedFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            // 1. Bounds only: no pixel allocation.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(stagedFile.absolutePath, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) throw IOException("Unsupported image format")

            // 2. EXIF orientation (absent or unreadable metadata means "normal").
            val exifOrientation = try {
                ExifInterface(stagedFile.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val transform = orientationTransform(exifOrientation)

            // 3. Subsampled decode close to the target size.
            val (targetW, targetH) = computeTargetSize(srcW, srcH)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(srcW, srcH, targetW, targetH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(stagedFile.absolutePath, decodeOptions)
                ?: throw IOException("Image decode failed")

            // 4. Exact resize + orientation in a single filtered pass.
            // Order matters: scale -> rotate -> mirror (EXIF TRANSPOSE/TRANSVERSE mirror after rotation).
            val matrix = Matrix().apply {
                postScale(targetW.toFloat() / decoded.width, targetH.toFloat() / decoded.height)
                if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
                if (transform.mirrorX) postScale(-1f, 1f)
            }
            val normalized = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            // createBitmap returns the same instance for an identity matrix.
            if (normalized !== decoded) decoded.recycle()

            try {
                FileOutputStream(outFile).use { out ->
                    if (!normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        throw IOException("JPEG encode failed")
                    }
                    out.fd.sync()
                }
                return normalized.width to normalized.height
            } finally {
                normalized.recycle()
            }
        } finally {
            stagedFile.delete()
        }
    }

    /**
     * Determines whether [uri] points to a video stream or an image based on MIME type,
     * file extension, and basic media probe.
     */
    fun isVideoUri(resolver: ContentResolver, uri: Uri): Boolean {
        // 1. Check MIME type from ContentResolver
        val mime = try { resolver.getType(uri) } catch (_: Throwable) { null }
        if (mime != null) {
            if (mime.startsWith("video/", ignoreCase = true)) return true
            if (mime.startsWith("image/", ignoreCase = true)) return false
        }

        // 2. Check path or URI string for common video extensions
        val path = (uri.path ?: uri.toString()).lowercase()
        val videoExtensions = listOf(".mp4", ".mkv", ".webm", ".3gp", ".mov", ".ts", ".m4v", ".avi", ".flv", ".ogv")
        if (videoExtensions.any { path.endsWith(it) || path.contains("$it?") }) {
            return true
        }

        // 3. Probe with MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val f = uri.path?.let { File(it) }
                if (f != null && f.exists()) {
                    retriever.setDataSource(f.absolutePath)
                    val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    if (hasVideo == "yes") return true
                }
            } else {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    if (hasVideo == "yes") return true
                }
            }
        } catch (_: Throwable) {
            // Probe failed or unsupported
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        return false
    }

    /** Extracted video metadata for settings display and player sizing. */
    data class VideoMetadata(
        val width: Int,
        val height: Int,
        val durationSec: Int,
        val fileSizeBytes: Long,
        val mimeType: String
    )

    /**
     * Imports a video picked through the system picker into [outVideoFile] and generates a first-frame
     * preview image into [outPreviewFile]. Extracts dimensions, rotation, duration, and file size.
     */
    @Throws(IOException::class)
    fun importVideoToFile(
        resolver: ContentResolver,
        uri: Uri,
        outVideoFile: File,
        outPreviewFile: File
    ): VideoMetadata {
        val parentDir = outVideoFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val stagedFile = File(parentDir, "${outVideoFile.nameWithoutExtension}_stage_${System.currentTimeMillis()}.tmp")

        try {
            openInputStreamFallback(resolver, uri).use { input ->
                FileOutputStream(stagedFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            if (!stagedFile.exists() || stagedFile.length() == 0L) {
                throw IOException("Empty or unreadable video file")
            }

            val retriever = MediaMetadataRetriever()
            var width = 1080
            var height = 1920
            var durationSec = 1
            var mimeType = "video/mp4"

            try {
                retriever.setDataSource(stagedFile.absolutePath)
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                if (hasVideo != "yes") {
                    throw IOException("File does not contain a video stream")
                }
                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"

                val isRotated = rotation == 90 || rotation == 270
                width = if (isRotated) rawH else rawW
                height = if (isRotated) rawW else rawH
                durationSec = (durationMs / 1000L).toInt().coerceAtLeast(1)

                // Extract first frame thumbnail
                val rawFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                if (rawFrame != null) {
                    try {
                        val normMatrix = if (rotation != 0) Matrix().apply { postRotate(rotation.toFloat()) } else Matrix()
                        val oriented = if (rotation != 0) {
                            Bitmap.createBitmap(rawFrame, 0, 0, rawFrame.width, rawFrame.height, normMatrix, true)
                        } else {
                            rawFrame
                        }
                        val (thumbW, thumbH) = computeTargetSize(oriented.width, oriented.height)
                        val scaled = Bitmap.createScaledBitmap(oriented, thumbW, thumbH, true)
                        FileOutputStream(outPreviewFile).use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            out.fd.sync()
                        }
                        if (scaled !== oriented) scaled.recycle()
                        if (oriented !== rawFrame) oriented.recycle()
                    } finally {
                        rawFrame.recycle()
                    }
                }
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }

            // Move staged file to outVideoFile atomically
            if (!stagedFile.renameTo(outVideoFile)) {
                stagedFile.copyTo(outVideoFile, overwrite = true)
                stagedFile.delete()
            }

            return VideoMetadata(
                width = width,
                height = height,
                durationSec = durationSec,
                fileSizeBytes = outVideoFile.length(),
                mimeType = mimeType
            )
        } finally {
            stagedFile.delete()
        }
    }
}

