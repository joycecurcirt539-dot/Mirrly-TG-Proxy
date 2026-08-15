package com.mirrly.tgproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun GithubMarkdownText(
    markdownText: String,
    modifier: Modifier = Modifier
) {
    if (markdownText.isBlank()) {
        Text(
            text = "Описание изменений отсутствует.",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = modifier
        )
        return
    }

    val lines = markdownText.lines()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var inCodeBlock = false
        val codeBlockLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // Render completed code block
                    CodeBlockCard(codeBlockLines.joinToString("\n"))
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                continue
            }

            when {
                trimmed.startsWith("# ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("# ").trim()),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                    HorizontalDivider(
                        color = ActiveGreenLed.copy(alpha = 0.3f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                trimmed.startsWith("## ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("## ").trim()),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }

                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("### ").trim()),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite.copy(alpha = 0.95f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                trimmed.startsWith("#### ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("#### ").trim()),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                trimmed.startsWith("---") || trimmed.startsWith("***") || trimmed.startsWith("___") -> {
                    HorizontalDivider(
                        color = AmoledBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                trimmed.startsWith("> ") -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = ActiveGreenLed.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(24.dp)
                                .background(ActiveGreenLed, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = parseInlineMarkdown(trimmed.removePrefix("> ").trim()),
                            fontSize = 12.5.sp,
                            fontStyle = FontStyle.Italic,
                            color = TextWhite.copy(alpha = 0.88f),
                            lineHeight = 17.sp
                        )
                    }
                }

                trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("+ ") -> {
                    val content = trimmed.substring(2).trim()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed)
                        )
                        Text(
                            text = parseInlineMarkdown(content),
                            fontSize = 13.sp,
                            color = TextWhite.copy(alpha = 0.92f),
                            lineHeight = 19.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                trimmed.isNotBlank() -> {
                    Text(
                        text = parseInlineMarkdown(trimmed),
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.88f),
                        lineHeight = 19.sp
                    )
                }

                else -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            CodeBlockCard(codeBlockLines.joinToString("\n"))
        }
    }
}

@Composable
private fun CodeBlockCard(codeText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = codeText,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = ActiveGreenLed.copy(alpha = 0.9f),
            lineHeight = 17.sp
        )
    }
}

/**
 * Custom inline Markdown parser supporting **bold**, *italic*, `inline code`,
 * and highlighting SHA-256 fingerprint strings in glowing green!
 */
@Composable
fun parseInlineMarkdown(text: String, accentColor: Color = ActiveGreenLed): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = text.length

        while (i < n) {
            when {
                // Inline Code: `code`
                text[i] == '`' -> {
                    val endIdx = text.indexOf('`', i + 1)
                    if (endIdx != -1) {
                        val code = text.substring(i + 1, endIdx)
                        withStyle(
                            style = SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = accentColor,
                                background = Color.Transparent
                            )
                        ) {
                            append(" $code ")
                        }
                        i = endIdx + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Bold: **text**
                i + 1 < n && text[i] == '*' && text[i + 1] == '*' -> {
                    val endIdx = text.indexOf("**", i + 2)
                    if (endIdx != -1) {
                        val boldText = text.substring(i + 2, endIdx)
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = TextWhite)) {
                            append(boldText)
                        }
                        i = endIdx + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Italic: *text*
                text[i] == '*' -> {
                    val endIdx = text.indexOf('*', i + 1)
                    if (endIdx != -1 && (endIdx + 1 >= n || text[endIdx + 1] != '*')) {
                        val italicText = text.substring(i + 1, endIdx)
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, color = TextWhite.copy(alpha = 0.9f))) {
                            append(italicText)
                        }
                        i = endIdx + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                else -> {
                    // Check for SHA-256 fingerprint string matching (e.g. 97:73:5C:...)
                    val sub = text.substring(i)
                    val shaRegex = Regex("^[a-fA-F0-9]{2}(:[a-fA-F0-9]{2}){15,31}")
                    val match = shaRegex.find(sub)

                    if (match != null && match.range.first == 0) {
                        val sha = match.value
                        withStyle(
                            style = SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = accentColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        ) {
                            append(sha)
                        }
                        i += sha.length
                    } else {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}
