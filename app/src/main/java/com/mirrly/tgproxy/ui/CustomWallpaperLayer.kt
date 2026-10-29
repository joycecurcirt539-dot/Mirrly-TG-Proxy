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

package com.mirrly.tgproxy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.service.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoded custom wallpaper plus the metadata shown in settings. */
@Immutable
internal class WallpaperImage(
    val key: String,
    val bitmap: ImageBitmap,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long
)

/**
 * Process-wide cache for the decoded wallpaper, shared by the background layer and the
 * settings thumbnail so the file is decoded once. Blurred variants are stored in an LRU
 * cache so static drawing performs zero per-frame shader passes.
 */
internal object CustomWallpaperBitmapStore {
    private val decodeMutex = Mutex()
    private val blurMutex = Mutex()

    @Volatile
    private var cached: WallpaperImage? = null
    private val blurCache = object : LinkedHashMap<String, ImageBitmap>(6, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 5
    }

    fun peek(key: String): WallpaperImage? = cached?.takeIf { it.key == key }

    suspend fun load(context: Context, path: String, revision: Long): WallpaperImage? = decodeMutex.withLock {
        val key = "$path#$revision"
        peek(key)?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val file = File(path)
            val bitmap = try {
                if (file.isFile) {
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                } else {
                    val cleanAsset = path.removePrefix("assets://").removePrefix("/")
                    context.assets.open(cleanAsset).use { stream ->
                        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })
                    }
                }
            } catch (_: Throwable) {
                null
            } ?: return@withContext null
            bitmap.prepareToDraw()
            val size = if (file.isFile) file.length() else 0L
            WallpaperImage(key, bitmap.asImageBitmap(), bitmap.width, bitmap.height, size)
        }?.also { image ->
            cached = image
            blurMutex.withLock { blurCache.clear() }
        }
    }

    /** Drops decoded bitmaps after the user resets to the AMOLED background. */
    suspend fun clear() {
        decodeMutex.withLock { cached = null }
        blurMutex.withLock { blurCache.clear() }
    }

    /**
     * Efficient blur cache: downscales working copy to [WORK_EDGE] pixels, applies 3-pass box blur
     * (Gaussian approximation) in background, and caches the result. Drawing this pre-blurred bitmap
     * consumes zero per-frame shader processing on 60/120Hz displays.
     */
    suspend fun getBlurred(image: WallpaperImage, blurDp: Float): ImageBitmap {
        if (blurDp <= 0.5f) return image.bitmap
        val bucket = (blurDp * 2f).roundToInt()
        val cacheKey = "${image.key}@$bucket"
        blurMutex.withLock { blurCache[cacheKey] }?.let { return it }

        val src = image.bitmap.asAndroidBitmap()
        val workScale = (WORK_EDGE / max(src.width, src.height).toFloat()).coerceAtMost(1f)
        val boxRadius = (blurDp * 2.5f * workScale).roundToInt().coerceAtLeast(1)

        val result = withContext(Dispatchers.Default) {
            val w = (src.width * workScale).roundToInt().coerceAtLeast(1)
            val h = (src.height * workScale).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            if (small !== src) small.recycle()
            val tmp = IntArray(pixels.size)
            repeat(3) {
                boxBlurHorizontal(pixels, tmp, w, h, boxRadius)
                boxBlurVertical(tmp, pixels, w, h, boxRadius)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        blurMutex.withLock { blurCache[cacheKey] = result }
        return result
    }

    private const val WORK_EDGE = 540f

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val maxX = w - 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, maxX)]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = OPAQUE or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + (x - r).coerceIn(0, maxX)]
                val pIn = src[row + (x + r + 1).coerceIn(0, maxX)]
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val maxY = h - 1
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, maxY) * w + x]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = OPAQUE or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[(y - r).coerceIn(0, maxY) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, maxY) * w + x]
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private const val OPAQUE = -0x1000000 // 0xFF000000
}

/** Loads the sandboxed wallpaper; keeps the previous image on screen while a new revision decodes. */
@Composable
internal fun rememberWallpaperImage(path: String?, revision: Long): WallpaperImage? {
    val context = LocalContext.current.applicationContext
    val key = path?.let { "$it#$revision" }
    return produceState(initialValue = key?.let { CustomWallpaperBitmapStore.peek(it) }, key) {
        value = if (path == null) null else CustomWallpaperBitmapStore.load(context, path, revision)
    }.value
}

/**
 * Full-screen custom background: dispatches between static image wallpaper and video wallpaper.
 * Blurs, dims, and adds a soft top/bottom scrim for navigation contrast.
 */
@Composable
internal fun CustomWallpaperBackground(
    wallpaperType: String,
    wallpaperPath: String?,
    image: WallpaperImage?,
    blurDp: Float,
    dim: Float,
    powerSaveEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val edgeScrim = remember {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.30f),
            0.16f to Color.Transparent,
            0.80f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.34f)
        )
    }

    Box(modifier = modifier) {
        if (wallpaperType == PreferencesManager.WALLPAPER_TYPE_VIDEO && !wallpaperPath.isNullOrBlank()) {
            WallpaperVideoPlayer(
                videoPath = wallpaperPath,
                previewImage = image,
                blurDp = blurDp,
                powerSaveEnabled = powerSaveEnabled,
                modifier = Modifier.fillMaxSize()
            )
        } else if (image != null) {
            WallpaperBlurredImage(
                image = image,
                blurDp = blurDp,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = dim))
                    drawRect(edgeScrim)
                }
        )
    }
}

/**
 * Center-cropped wallpaper using cached pre-blurred bitmaps.
 * Renders as a lightweight hardware texture with zero per-frame shader overhead.
 */
@Composable
internal fun WallpaperBlurredImage(
    image: WallpaperImage,
    blurDp: Float,
    modifier: Modifier = Modifier
) {
    var blurredBitmap by remember(image.key) {
        mutableStateOf<ImageBitmap?>(if (blurDp <= 0.5f) image.bitmap else null)
    }

    LaunchedEffect(image.key, blurDp) {
        if (blurDp <= 0.5f) {
            blurredBitmap = image.bitmap
        } else {
            blurredBitmap = CustomWallpaperBitmapStore.getBlurred(image, blurDp)
        }
    }

    val display = blurredBitmap ?: image.bitmap
    Image(
        bitmap = display,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

/**
 * Background video wallpaper player using hardware-accelerated [TextureView] and [MediaPlayer].
 * Automatically loops, is strictly muted, respects Android lifecycle (pauses in background),
 * and pauses in Android system battery saver mode to prevent battery drain.
 */
@Composable
internal fun WallpaperVideoPlayer(
    videoPath: String,
    previewImage: WallpaperImage?,
    blurDp: Float,
    powerSaveEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isSystemPowerSave = remember { powerManager?.isPowerSaveMode == true }

    // If battery saver is active and optimization is enabled, fallback to static preview image
    if (isSystemPowerSave && powerSaveEnabled && previewImage != null) {
        WallpaperBlurredImage(
            image = previewImage,
            blurDp = blurDp,
            modifier = modifier
        )
        return
    }

    var isPlayerReady by remember { mutableStateOf(false) }
    val mediaPlayerRef = remember { java.util.concurrent.atomic.AtomicReference<MediaPlayer?>(null) }
    val density = LocalDensity.current

    val blurEffect = remember(blurDp, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.5f) {
            val radiusPx = with(density) { blurDp.dp.toPx() }
            try {
                RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP).asComposeRenderEffect()
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val player = mediaPlayerRef.get() ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    try {
                        if (player.isPlaying) player.pause()
                    } catch (_: Throwable) {}
                }
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    try {
                        player.start()
                    } catch (_: Throwable) {}
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier) {
        // While video player prepares surface, render first-frame preview
        if (!isPlayerReady && previewImage != null) {
            WallpaperBlurredImage(
                image = previewImage,
                blurDp = blurDp,
                modifier = Modifier.fillMaxSize()
            )
        }

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            try {
                                if (powerSaveEnabled) {
                                    val scale = min(1f, 1080f / max(width, height).toFloat())
                                    surface.setDefaultBufferSize(
                                        (width * scale).toInt().coerceAtLeast(360),
                                        (height * scale).toInt().coerceAtLeast(640)
                                    )
                                }
                                val mp = MediaPlayer().apply {
                                    setDataSource(videoPath)
                                    setSurface(Surface(surface))
                                    isLooping = true
                                    setVolume(0f, 0f)
                                    setOnPreparedListener { player ->
                                        val vw = player.videoWidth.toFloat()
                                        val vh = player.videoHeight.toFloat()
                                        if (vw > 0 && vh > 0 && width > 0 && height > 0) {
                                            val matrix = Matrix()
                                            val viewRatio = width.toFloat() / height
                                            val videoRatio = vw / vh
                                            var sx = 1f
                                            var sy = 1f
                                            if (viewRatio > videoRatio) {
                                                sy = (width.toFloat() / vw) / (height.toFloat() / vh)
                                            } else {
                                                sx = (height.toFloat() / vh) / (width.toFloat() / vw)
                                            }
                                            matrix.setScale(sx, sy, width / 2f, height / 2f)
                                            setTransform(matrix)
                                        }
                                        player.start()
                                        isPlayerReady = true
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        isPlayerReady = false
                                        true
                                    }
                                    prepareAsync()
                                }
                                mediaPlayerRef.set(mp)
                            } catch (e: Throwable) {
                                AppLogger.w("Wallpaper", "Video player init error: ${e.message}")
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            val mp = mediaPlayerRef.getAndSet(null)
                            try {
                                mp?.stop()
                                mp?.reset()
                                mp?.release()
                            } catch (_: Throwable) {}
                            isPlayerReady = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (blurEffect != null) {
                        renderEffect = blurEffect
                        clip = true
                    }
                }
        )
    }
}

/**
 * Code-drawn wallpaper glyph (frame, horizon ridge, sun). Stroke 1.5dp, round caps.
 * [pulse] 0..1 breathes the sun core; used as loading indicator while media is processed.
 */
@Composable
internal fun WallpaperGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    pulse: Float = 0f
) {
    val ridge = remember { Path() }
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val stroke = 1.5f * density
        val inset = stroke / 2f + s * 0.08f
        val frameSize = Size(size.width - inset * 2, size.height - inset * 2)
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = frameSize,
            cornerRadius = CornerRadius(s * 0.16f),
            style = Stroke(width = stroke)
        )
        // Horizon ridge: two peaks, optically weighted to the lower third.
        val left = inset
        val right = size.width - inset
        val bottom = size.height - inset
        val w = right - left
        ridge.reset()
        ridge.moveTo(left + w * 0.06f, bottom - frameSize.height * 0.10f)
        ridge.lineTo(left + w * 0.36f, bottom - frameSize.height * 0.46f)
        ridge.lineTo(left + w * 0.56f, bottom - frameSize.height * 0.24f)
        ridge.lineTo(left + w * 0.70f, bottom - frameSize.height * 0.36f)
        ridge.lineTo(right - w * 0.06f, bottom - frameSize.height * 0.10f)
        drawPath(
            path = ridge,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Sun with breathing core.
        val sunCenter = Offset(left + w * 0.70f, inset + frameSize.height * 0.30f)
        val sunR = s * 0.075f
        drawCircle(color = color, radius = sunR, center = sunCenter, style = Stroke(width = stroke))
        if (pulse > 0f) {
            drawCircle(
                color = color.copy(alpha = color.alpha * (0.25f + 0.55f * pulse)),
                radius = sunR * (0.45f + 0.35f * pulse),
                center = sunCenter
            )
        }
    }
}

/**
 * Miniature video loop preview for wallpaper preset cards.
 * Hardware accelerated, strictly muted, lifecycle aware (pauses when backgrounded).
 */
@Composable
internal fun PresetVideoPreview(
    videoPath: String? = null,
    assetPath: String? = null,
    previewImage: WallpaperImage? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mediaPlayerRef = remember { java.util.concurrent.atomic.AtomicReference<MediaPlayer?>(null) }
    var isReady by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mp = mediaPlayerRef.get() ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    try { if (mp.isPlaying) mp.pause() } catch (_: Throwable) {}
                }
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    try { mp.start() } catch (_: Throwable) {}
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val mp = mediaPlayerRef.getAndSet(null)
            try {
                mp?.stop()
                mp?.reset()
                mp?.release()
            } catch (_: Throwable) {}
        }
    }

    Box(modifier = modifier) {
        if (!isReady && previewImage != null) {
            Image(
                bitmap = previewImage.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            try {
                                surface.setDefaultBufferSize(240, 426)
                                val mp = MediaPlayer().apply {
                                    if (!assetPath.isNullOrBlank()) {
                                        val afd = ctx.assets.openFd(assetPath)
                                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                        afd.close()
                                    } else if (!videoPath.isNullOrBlank()) {
                                        setDataSource(videoPath)
                                    } else {
                                        return
                                    }
                                    setSurface(Surface(surface))
                                    isLooping = true
                                    setVolume(0f, 0f)
                                    setOnPreparedListener { player ->
                                        val vw = player.videoWidth.toFloat()
                                        val vh = player.videoHeight.toFloat()
                                        if (vw > 0 && vh > 0 && width > 0 && height > 0) {
                                            val matrix = Matrix()
                                            val viewRatio = width.toFloat() / height
                                            val videoRatio = vw / vh
                                            var sx = 1f
                                            var sy = 1f
                                            if (viewRatio > videoRatio) {
                                                sy = (width.toFloat() / vw) / (height.toFloat() / vh)
                                            } else {
                                                sx = (height.toFloat() / vh) / (width.toFloat() / vw)
                                            }
                                            matrix.setScale(sx, sy, width / 2f, height / 2f)
                                            setTransform(matrix)
                                        }
                                        player.start()
                                        isReady = true
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        isReady = false
                                        true
                                    }
                                    prepareAsync()
                                }
                                mediaPlayerRef.set(mp)
                            } catch (e: Throwable) {
                                isReady = false
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            val mp = mediaPlayerRef.getAndSet(null)
                            try {
                                mp?.stop()
                                mp?.reset()
                                mp?.release()
                            } catch (_: Throwable) {}
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

