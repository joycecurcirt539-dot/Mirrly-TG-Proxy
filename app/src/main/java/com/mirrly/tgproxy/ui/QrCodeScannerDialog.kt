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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.*
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object QrCodeGenerator {
    fun generateQrBitmap(
        content: String,
        sizePx: Int = 720,
        accentColor: Int = android.graphics.Color.parseColor("#00E5FF"),
        darkColor: Int = android.graphics.Color.WHITE,
        backgroundColor: Int = android.graphics.Color.TRANSPARENT,
        logoBitmap: android.graphics.Bitmap? = null
    ): android.graphics.Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to (if (logoBitmap != null) ErrorCorrectionLevel.M else ErrorCorrectionLevel.L)
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
            val matrixWidth = matrix.width
            val matrixHeight = matrix.height

            val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(backgroundColor)

            val moduleSize = sizePx.toFloat() / matrixWidth

            val dataPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = darkColor
                style = android.graphics.Paint.Style.FILL
            }

            val finderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = android.graphics.Paint.Style.FILL
            }

            // Find where the 7x7 finders actually are (taking margin into account)
            var mX = 0
            while (mX < matrixWidth && !matrix.get(mX, mX)) { mX++ }
            val mY = mX

            fun isCornerFinder(x: Int, y: Int): Boolean {
                val inTopLeft = (x in mX until (mX + 7)) && (y in mY until (mY + 7))
                val inTopRight = (x in (matrixWidth - mX - 7) until (matrixWidth - mX)) && (y in mY until (mY + 7))
                val inBottomLeft = (x in mX until (mX + 7)) && (y in (matrixHeight - mY - 7) until (matrixHeight - mY))
                return inTopLeft || inTopRight || inBottomLeft
            }

            val centerModuleX = matrixWidth / 2f
            val centerModuleY = matrixHeight / 2f
            val logoRadiusModules = if (logoBitmap != null) (matrixWidth * 0.12f) else 0f

            fun isCenterLogoArea(x: Int, y: Int): Boolean {
                if (logoBitmap == null) return false
                val dx = (x + 0.5f) - centerModuleX
                val dy = (y + 0.5f) - centerModuleY
                return (dx * dx + dy * dy) <= (logoRadiusModules * logoRadiusModules)
            }

            // Draw all modules directly from matrix
            for (y in 0 until matrixHeight) {
                for (x in 0 until matrixWidth) {
                    if (isCenterLogoArea(x, y)) continue
                    if (matrix.get(x, y)) {
                        val left = x * moduleSize
                        val top = y * moduleSize
                        val right = (x + 1) * moduleSize
                        val bottom = (y + 1) * moduleSize

                        val isFinder = isCornerFinder(x, y)
                        val paint = if (isFinder) finderPaint else dataPaint
                        val r = moduleSize * 0.15f
                        canvas.drawRoundRect(left + 0.3f, top + 0.3f, right - 0.3f, bottom - 0.3f, r, r, paint)
                    }
                }
            }

            // Center Logo Badge
            if (logoBitmap != null) {
                val logoBadgeSize = sizePx * 0.18f
                val logoCenter = sizePx / 2f
                val badgeLeft = logoCenter - logoBadgeSize / 2f
                val badgeTop = logoCenter - logoBadgeSize / 2f
                val badgeRight = logoCenter + logoBadgeSize / 2f
                val badgeBottom = logoCenter + logoBadgeSize / 2f
                val badgeRadius = logoBadgeSize * 0.28f

                val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = backgroundColor
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, badgeRadius, badgeRadius, bgPaint)

                val badgeBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = sizePx * 0.007f
                }
                canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, badgeRadius, badgeRadius, badgeBorderPaint)

                val iconSize = logoBadgeSize * 0.74f
                val iconLeft = logoCenter - iconSize / 2f
                val iconTop = logoCenter - iconSize / 2f
                val iconRect = android.graphics.RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                val path = android.graphics.Path().apply {
                    addRoundRect(iconRect, iconSize * 0.22f, iconSize * 0.22f, android.graphics.Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(
                    logoBitmap,
                    null,
                    iconRect,
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                )
                canvas.restore()
            }

            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun QrCodeScannerDialog(
    activeAccentColor: Color,
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(onDismiss = onDismiss) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = AmoledSurfaceLow.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.8f)),
                    modifier = Modifier
                        .adaptiveContainerWidth(440.dp)
                        .wrapContentHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth()
                    ) {
                        // Top Header Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = activeAccentColor.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_diag_worker),
                                        contentDescription = null,
                                        tint = activeAccentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "QR-сканер воркеров",
                                        color = activeAccentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Close Button (X)
                            Surface(
                                shape = CircleShape,
                                color = AmoledSurfaceHigh.copy(alpha = 0.8f),
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onDismiss()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "✕",
                                        color = TextMuted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Сканирование узла Cloudflare",
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Наведите камеру на QR-код с экрана монитора или другого устройства для мгновенного импорта воркера.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        if (hasCameraPermission) {
                            // Camera Scanner Viewport
                            CameraQrScannerView(
                                activeAccentColor = activeAccentColor,
                                onScanned = { rawText ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onQrScanned(rawText)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(20.dp))
                            )
                        } else {
                            // Permission Denied Card
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .background(AmoledSurface, RoundedCornerShape(20.dp))
                                    .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(20.dp))
                                    .padding(20.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_diag_worker),
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Доступ к камере отключен",
                                    color = TextWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Предоставьте разрешение камеры для автоматического считывания QR-кодов.",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = activeAccentColor)
                                ) {
                                    Text(
                                        text = "Открыть настройки",
                                        color = AmoledBackground,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bottom status note
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = activeAccentColor,
                                modifier = Modifier.size(7.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Поддерживаются ссылки mirrly://, https:// и домены .workers.dev",
                                color = TextMuted,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraQrScannerView(
    activeAccentColor: Color,
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isScanned = remember { AtomicBoolean(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            try { barcodeScanner.close() } catch (_: Exception) {}
        }
    }

    // Scanning laser sweep animation
    val infiniteTransition = rememberInfiniteTransition(label = "LaserSweep")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LaserProgress"
    )

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isScanned.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        val rawValue = barcode.rawValue
                                        if (!rawValue.isNullOrBlank() && isScanned.compareAndSet(false, true)) {
                                            previewView.post {
                                                onScanned(rawValue.trim())
                                            }
                                            break
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Viewfinder Reticle Frame & Laser Scan Animation
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val frameSize = minOf(canvasWidth, canvasHeight) * 0.72f
            val frameLeft = (canvasWidth - frameSize) / 2f
            val frameTop = (canvasHeight - frameSize) / 2f
            val frameRight = frameLeft + frameSize
            val frameBottom = frameTop + frameSize

            // Dark dimmed overlay outside reticle
            val cornerLength = 36.dp.toPx()
            val strokeWidth = 3.5.dp.toPx()

            // Corner brackets
            val bracketColor = activeAccentColor

            // Top-Left
            drawLine(bracketColor, Offset(frameLeft, frameTop), Offset(frameLeft + cornerLength, frameTop), strokeWidth, StrokeCap.Round)
            drawLine(bracketColor, Offset(frameLeft, frameTop), Offset(frameLeft, frameTop + cornerLength), strokeWidth, StrokeCap.Round)

            // Top-Right
            drawLine(bracketColor, Offset(frameRight, frameTop), Offset(frameRight - cornerLength, frameTop), strokeWidth, StrokeCap.Round)
            drawLine(bracketColor, Offset(frameRight, frameTop), Offset(frameRight, frameTop + cornerLength), strokeWidth, StrokeCap.Round)

            // Bottom-Left
            drawLine(bracketColor, Offset(frameLeft, frameBottom), Offset(frameLeft + cornerLength, frameBottom), strokeWidth, StrokeCap.Round)
            drawLine(bracketColor, Offset(frameLeft, frameBottom), Offset(frameLeft, frameBottom - cornerLength), strokeWidth, StrokeCap.Round)

            // Bottom-Right
            drawLine(bracketColor, Offset(frameRight, frameBottom), Offset(frameRight - cornerLength, frameBottom), strokeWidth, StrokeCap.Round)
            drawLine(bracketColor, Offset(frameRight, frameBottom), Offset(frameRight, frameBottom - cornerLength), strokeWidth, StrokeCap.Round)

            // Laser sweep line
            val laserY = frameTop + (frameSize * laserProgress)
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        activeAccentColor.copy(alpha = 0.85f),
                        activeAccentColor,
                        activeAccentColor.copy(alpha = 0.85f),
                        Color.Transparent
                    )
                ),
                start = Offset(frameLeft + 4.dp.toPx(), laserY),
                end = Offset(frameRight - 4.dp.toPx(), laserY),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Torch (Flashlight) Toggle Button
        if (cameraControl != null) {
            Surface(
                shape = CircleShape,
                color = if (isTorchOn) activeAccentColor else AmoledSurfaceLow.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, if (isTorchOn) activeAccentColor else AmoledBorder),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(40.dp)
                    .clickable {
                        isTorchOn = !isTorchOn
                        cameraControl?.enableTorch(isTorchOn)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(
                            id = if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                        ),
                        contentDescription = if (isTorchOn) "Выключить подсветку" else "Включить подсветку",
                        tint = if (isTorchOn) Color(0xFF090D16) else TextWhite,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
