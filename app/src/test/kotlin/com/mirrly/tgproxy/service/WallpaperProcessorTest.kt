package com.mirrly.tgproxy.service

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperProcessorTest {

    @Test
    fun testComputeTargetSize_landscape_capsAtCoverBox() {
        val (w, h) = WallpaperProcessor.computeTargetSize(3840, 2160)
        // Ratio 16:9, long side scale = 2400 / 3840 = 0.625 -> 2400 x 1350
        assertEquals(2400, w)
        assertEquals(1350, h)
        assertTrue(w.toLong() * h <= WallpaperProcessor.MAX_PIXELS)
    }

    @Test
    fun testComputeTargetSize_portrait_capsAtCoverBox() {
        val (w, h) = WallpaperProcessor.computeTargetSize(2160, 3840)
        // Ratio 9:16 -> 1350 x 2400
        assertEquals(1350, w)
        assertEquals(2400, h)
        assertTrue(w.toLong() * h <= WallpaperProcessor.MAX_PIXELS)
    }

    @Test
    fun testComputeTargetSize_hugeImage_capsAtMaxPixels() {
        val (w, h) = WallpaperProcessor.computeTargetSize(8000, 8000)
        assertTrue(w.toLong() * h <= WallpaperProcessor.MAX_PIXELS)
        assertEquals(w, h)
    }

    @Test
    fun testComputeInSampleSize_powersOfTwo() {
        // Source 4000x3000, requested 1920x1080 -> sample 2 (2000x1500 >= 1920x1080)
        assertEquals(2, WallpaperProcessor.computeInSampleSize(4000, 3000, 1920, 1080))
        // Source 1920x1080, requested 1920x1080 -> sample 1
        assertEquals(1, WallpaperProcessor.computeInSampleSize(1920, 1080, 1920, 1080))
        // Source 8000x4000, requested 1000x500 -> sample 8 (1000x500 >= 1000x500)
        assertEquals(8, WallpaperProcessor.computeInSampleSize(8000, 4000, 1000, 500))
    }

    @Test
    fun testOrientationTransform_rotationsAndMirrors() {
        val normal = WallpaperProcessor.orientationTransform(ExifInterface.ORIENTATION_NORMAL)
        assertEquals(0, normal.rotationDegrees)
        assertFalse(normal.mirrorX)
        assertFalse(normal.swapsAxes)

        val r90 = WallpaperProcessor.orientationTransform(ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(90, r90.rotationDegrees)
        assertFalse(r90.mirrorX)
        assertTrue(r90.swapsAxes)

        val r180 = WallpaperProcessor.orientationTransform(ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(180, r180.rotationDegrees)
        assertFalse(r180.mirrorX)
        assertFalse(r180.swapsAxes)

        val r270 = WallpaperProcessor.orientationTransform(ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(270, r270.rotationDegrees)
        assertFalse(r270.mirrorX)
        assertTrue(r270.swapsAxes)

        val flipH = WallpaperProcessor.orientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(0, flipH.rotationDegrees)
        assertTrue(flipH.mirrorX)

        val transpose = WallpaperProcessor.orientationTransform(ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(90, transpose.rotationDegrees)
        assertTrue(transpose.mirrorX)
    }

    @Test
    fun testVideoMetadata_creationAndProperties() {
        val meta = WallpaperProcessor.VideoMetadata(
            width = 1080,
            height = 1920,
            durationSec = 15,
            fileSizeBytes = 4500000L,
            mimeType = "video/mp4"
        )
        assertEquals(1080, meta.width)
        assertEquals(1920, meta.height)
        assertEquals(15, meta.durationSec)
        assertEquals(4500000L, meta.fileSizeBytes)
        assertEquals("video/mp4", meta.mimeType)
    }
}

