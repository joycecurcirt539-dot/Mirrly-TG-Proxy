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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

val WORKER_JS_CODE_GUIDE = """import { connect } from 'cloudflare:sockets';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response(
        JSON.stringify({
          status: "active",
          service: "Mirrly TG Proxy Cloudflare Worker",
          version: "1.1.0",
          time: new Date().toISOString()
        }),
        {
          headers: { "content-type": "application/json;charset=UTF-8" }
        }
      );
    }

    const targetParam = url.searchParams.get('target');
    if (!targetParam) {
      return new Response("Missing target parameter. Expected ?target=host:port", { status: 400 });
    }

    const lastColon = targetParam.lastIndexOf(':');
    if (lastColon === -1) {
      return new Response("Invalid target format. Expected host:port", { status: 400 });
    }

    const targetHost = targetParam.substring(0, lastColon);
    const targetPort = parseInt(targetParam.substring(lastColon + 1), 10);

    if (isNaN(targetPort) || targetPort <= 0 || targetPort > 65535) {
      return new Response("Invalid target port", { status: 400 });
    }

    const webSocketPair = new WebSocketPair();
    const [clientWs, serverWs] = Object.values(webSocketPair);

    serverWs.accept();

    try {
      const tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });

      const tcpWriter = tcpSocket.writable.getWriter();
      const tcpReader = tcpSocket.readable.getReader();

      serverWs.addEventListener('message', async (event) => {
        try {
          const data = typeof event.data === 'string' ? new TextEncoder().encode(event.data) : new Uint8Array(event.data);
          await tcpWriter.write(data);
        } catch (e) {
          serverWs.close(1011, "TCP Write Error");
        }
      });

      serverWs.addEventListener('close', () => {
        try { tcpWriter.close(); } catch (_) {}
      });

      serverWs.addEventListener('error', () => {
        try { tcpWriter.close(); } catch (_) {}
      });

      (async () => {
        try {
          while (true) {
            const { value, done } = await tcpReader.read();
            if (done) break;
            if (value && serverWs.readyState === WebSocket.OPEN) {
              serverWs.send(value);
            }
          }
        } catch (e) {
        } finally {
          try { serverWs.close(); } catch (_) {}
        }
      })();

    } catch (err) {
      serverWs.close(1011, `Connect failed: ` + err.message);
    }

    return new Response(null, {
      status: 101,
      webSocket: clientWs
    });
  }
};"""

@Composable
fun CloudflareWorkerGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }

    fun copyWorkerCode() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cloudflare Worker JS", WORKER_JS_CODE_GUIDE)
        clipboard.setPrimaryClip(clip)
        isCopied = true
        Toast.makeText(context, "Код Cloudflare Worker скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
    }

    fun openCloudflareDash() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com"))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window?.attributes = window?.attributes?.apply {
                    blurBehindRadius = 50
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 24.dp)
        ) {
            // Detailed Guide Steps with Smooth Fading Edges
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 64.dp)
                    .padding(bottom = 140.dp, top = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(enabled = false) {}
            ) {
                // Category Pills Row (Matches Link Redirection Tab)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF00F0FF).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "ПОШАГОВАЯ ИНСТРУКЦИЯ WORKER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F0FF),
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFF3D00).copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Color(0xFFFF3D00).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "БЕТА",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFF3D00),
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Main Title
                Text(
                    text = "Деплой Cloudflare Worker",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Subtitle / Intro Note
                Text(
                    text = "Разверните собственный бессерверный скрипт на Cloudflare для маршрутизации TCP-трафика через WebSockets:",
                    fontSize = 13.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                // Security & Technical Info Card (Transparent Cyan Glass)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF00F0FF).copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "БЕЗОПАСНОСТЬ ЛИЧНЫХ ДАННЫХ И ПРИНЦИП РАБОТЫ",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F0FF),
                            letterSpacing = 0.8.sp
                        )

                        GuideBulletItem("Для 100% безопасности личных данных рекомендуется развернуть СВОЙ воркер. При использовании чужого воркера трафик проходит через стороннюю ноду.")
                        GuideBulletItem("Cloudflare Worker — это бессерверный V8-скрипт в сети Edge (300+ дата-центров). Он туннелирует трафик Telegram через WebSockets (wss://), маскируя его под обычный безопасный HTTPS-веб-сайт, что полностью сводит на нет попытки блокировки DPI/ТСПУ.")
                        GuideBulletItem("Каждый бесплатный аккаунт Cloudflare получает 100 000 запросов в день.")
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Steps List
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RedirectionGuideStepItem(
                        stepNumber = "1",
                        title = "Регистрация в Cloudflare",
                        description = "Зарегистрируйтесь на сайте cloudflare.com (бесплатно) и перейдите в раздел Workers & Pages."
                    )

                    RedirectionGuideStepItem(
                        stepNumber = "2",
                        title = "Создание нового Worker",
                        description = "Нажмите «Create application» → «Create Worker». Укажите любое имя (например my-tg-proxy) и нажмите Deploy."
                    )

                    RedirectionGuideStepItem(
                        stepNumber = "3",
                        title = "Скопируйте JS-скрипт",
                        description = "Нажмите синюю кнопку ниже для копирования готового кода с поддержкой cloudflare:sockets."
                    )

                    // Copy Code Button inside step 3
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            copyWorkerCode()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActiveGreenLed.copy(alpha = 0.20f),
                            contentColor = ActiveGreenLed
                        ),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .springPress()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isCopied) {
                                Text("Скопировано", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ActiveGreenLed)
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = if (isCopied) "в буфер!" else "Скопировать JS-код воркера",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    RedirectionGuideStepItem(
                        stepNumber = "4",
                        title = "Вставьте код и запустите Save & Deploy",
                        description = "Нажмите «Edit code» в панели воркера, очистите стандартный шаблон, вставьте скопированный код и нажмите Save and deploy."
                    )

                    RedirectionGuideStepItem(
                        stepNumber = "5",
                        title = "Укажите полученный домен в Настройках",
                        description = "Скопируйте полученный домен воркера (например worker-name.subdomain.workers.dev) и вставьте его в поле «Кастомный домен воркера» в Настройках приложения."
                    )
                }
            }

            // Bottom Action Buttons Dock (Floating seamlessly over blurred background)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                // Primary Action: Open Dashboard
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openCloudflareDash()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00F0FF).copy(alpha = 0.20f),
                        contentColor = Color(0xFF00F0FF)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.50f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .springPress()
                ) {
                    Text(
                        text = "Открыть Cloudflare Dashboard",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Close Button
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextWhite.copy(alpha = 0.90f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .springPress()
                ) {
                    Text(
                        text = "Закрыть",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideBulletItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(Color(0xFF00F0FF), CircleShape)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = TextWhite.copy(alpha = 0.88f),
            lineHeight = 17.5.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun RedirectionGuideStepItem(
    stepNumber: String,
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF00F0FF).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.45f)),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stepNumber,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00F0FF)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = description,
                    fontSize = 12.5.sp,
                    color = TextWhite.copy(alpha = 0.82f),
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
