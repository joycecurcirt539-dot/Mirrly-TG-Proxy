package com.mirrly.tgproxy.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.LogCategory
import com.mirrly.tgproxy.core.LogLevel
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.ui.theme.*

private val SectionCardShape = RoundedCornerShape(16.dp)
private val RowCardShape = RoundedCornerShape(12.dp)
private val TagBadgeShape = RoundedCornerShape(4.dp)
private val SearchFieldShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSettingsBottomSheet(
    onDismissRequest: () -> Unit,
    prefsManager: PreferencesManager = MirrlyApplication.instance.prefsManager
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    val activeProtoColor = ActiveGreenLed

    val isLogsEnabled by prefsManager.isLogsEnabledFlow.collectAsState()
    val isLogcatReaderAllowed by prefsManager.isLogcatReaderAllowedFlow.collectAsState()
    val isDebugEnabled by prefsManager.isLogLevelDebugFlow.collectAsState()
    val isInfoEnabled by prefsManager.isLogLevelInfoFlow.collectAsState()
    val isWarnEnabled by prefsManager.isLogLevelWarnFlow.collectAsState()
    val isErrorEnabled by prefsManager.isLogLevelErrorFlow.collectAsState()
    val disabledCategories by prefsManager.disabledLogCategoriesFlow.collectAsState()
    val disabledTags by prefsManager.disabledLogTagsFlow.collectAsState()

    var isTagsExpanded by remember { mutableStateOf(false) }
    var tagSearchQuery by remember { mutableStateOf("") }

    val allKnownTags = remember(disabledTags, isLogsEnabled) {
        AppLogger.getAllKnownTags().sorted()
    }

    val filteredTags = remember(allKnownTags, tagSearchQuery) {
        val query = tagSearchQuery.trim()
        if (query.isEmpty()) allKnownTags
        else allKnownTags.filter { it.contains(query, ignoreCase = true) }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismissRequest
    ) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.logs_settings_sheet_title),
            subtitle = stringResource(R.string.logs_settings_sheet_subtitle),
            onClose = onDismissRequest
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 12.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 1. MASTER SWITCH: Enable/disable all logging ──
            Surface(
                shape = SectionCardShape,
                color = Color.White.copy(alpha = 0.02f),
                border = BorderStroke(1.dp, if (isLogsEnabled) activeProtoColor.copy(alpha = 0.45f) else AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.logs_settings_master_title),
                            color = TextWhite,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.logs_settings_master_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = isLogsEnabled,
                        onCheckedChange = { checked ->
                            HapticHelper.performTapClick(context)
                            prefsManager.setLogsEnabled(checked)
                        },
                        activeColor = activeProtoColor
                    )
                }
            }

            AnimatedVisibility(
                visible = isLogsEnabled,
                enter = expandVertically(tween(220)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(160))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ── 2. LOGCAT SYSTEM READER SWITCH ──
                    Surface(
                        shape = SectionCardShape,
                        color = Color.White.copy(alpha = 0.02f),
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.logs_settings_logcat_title),
                                    color = TextWhite,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.logs_settings_logcat_desc),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 14.5.sp
                                )
                            }

                            InertialSpringSwitch(
                                checked = isLogcatReaderAllowed,
                                onCheckedChange = { checked ->
                                    HapticHelper.performTapClick(context)
                                    prefsManager.setLogcatReaderAllowed(checked)
                                },
                                activeColor = activeProtoColor
                            )
                        }
                    }

                    // ── 3. SEVERITY LEVELS SECTION ──
                    Surface(
                        shape = SectionCardShape,
                        color = Color.White.copy(alpha = 0.02f),
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.logs_settings_levels_title),
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Debug Level Switch (Highlight with advisory description)
                            LogOptionRow(
                                title = stringResource(R.string.logs_settings_level_debug),
                                subtitle = stringResource(R.string.logs_settings_level_debug_desc),
                                isChecked = isDebugEnabled,
                                activeColor = Color(0xFF64B5F6),
                                onCheckedChange = { checked ->
                                    HapticHelper.performTapClick(context)
                                    prefsManager.setLogLevelEnabled(LogLevel.DEBUG, checked)
                                }
                            )

                            // Info Level Switch
                            LogOptionRow(
                                title = stringResource(R.string.logs_settings_level_info),
                                subtitle = null,
                                isChecked = isInfoEnabled,
                                activeColor = activeProtoColor,
                                onCheckedChange = { checked ->
                                    HapticHelper.performTapClick(context)
                                    prefsManager.setLogLevelEnabled(LogLevel.INFO, checked)
                                }
                            )

                            // Warn & Error Level Switch
                            LogOptionRow(
                                title = "${stringResource(R.string.logs_settings_level_warn)} · ${stringResource(R.string.logs_settings_level_error)}",
                                subtitle = null,
                                isChecked = isWarnEnabled || isErrorEnabled,
                                activeColor = Color(0xFFEF4444),
                                onCheckedChange = { checked ->
                                    HapticHelper.performTapClick(context)
                                    prefsManager.setLogLevelEnabled(LogLevel.WARN, checked)
                                    prefsManager.setLogLevelEnabled(LogLevel.ERROR, checked)
                                }
                            )
                        }
                    }

                    // ── 4. CATEGORIES SECTION ──
                    Surface(
                        shape = SectionCardShape,
                        color = Color.White.copy(alpha = 0.02f),
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.logs_settings_categories_title),
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            for (category in LogCategory.entries) {
                                val isCategoryActive = category.key !in disabledCategories
                                LogOptionRow(
                                    title = category.defaultTitle,
                                    subtitle = category.defaultDescription,
                                    isChecked = isCategoryActive,
                                    activeColor = activeProtoColor,
                                    onCheckedChange = { checked ->
                                        HapticHelper.performTapClick(context)
                                        prefsManager.setLogCategoryEnabled(category.key, checked)
                                    }
                                )
                            }
                        }
                    }

                    // ── 5. INDIVIDUAL TAGS EXPANDABLE CARD ──
                    MirrlyExpandableCard(
                        isExpanded = isTagsExpanded,
                        onToggle = {
                            HapticHelper.performTapClick(context)
                            isTagsExpanded = !isTagsExpanded
                        },
                        header = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(R.string.logs_settings_tags_title),
                                        color = TextWhite,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.logs_settings_tags_subtitle),
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }

                                Text(
                                    text = if (isTagsExpanded) "▲" else "▼",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Search tags field
                            OutlinedTextField(
                                value = tagSearchQuery,
                                onValueChange = { tagSearchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.logs_settings_tags_search),
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                shape = SearchFieldShape,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedBorderColor = activeProtoColor.copy(alpha = 0.70f),
                                    unfocusedBorderColor = AmoledBorder,
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite
                                )
                            )

                            if (filteredTags.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.logs_settings_tags_empty),
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (tag in filteredTags) {
                                        val isTagActive = !disabledTags.any { it.equals(tag, ignoreCase = true) }
                                        Surface(
                                            shape = RowCardShape,
                                            color = Color.Transparent,
                                            border = BorderStroke(0.6.dp, AmoledBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Surface(
                                                        shape = TagBadgeShape,
                                                        color = Color.Transparent,
                                                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
                                                    ) {
                                                        Text(
                                                            text = tag,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = if (isTagActive) TextWhite else TextMuted,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }

                                                InertialSpringSwitch(
                                                    checked = isTagActive,
                                                    onCheckedChange = { checked ->
                                                        HapticHelper.performTapClick(context)
                                                        prefsManager.setLogTagEnabled(tag, checked)
                                                    },
                                                    activeColor = activeProtoColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 6. RESET BUTTON ──
            Surface(
                shape = RowCardShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(onClick = {
                        HapticHelper.performTapClick(context)
                        prefsManager.resetLogSettings()
                        Toast.makeText(context, context.getString(R.string.logs_settings_reset_done), Toast.LENGTH_SHORT).show()
                    })
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.logs_settings_reset_defaults),
                        color = TextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LogOptionRow(
    title: String,
    subtitle: String?,
    isChecked: Boolean,
    activeColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = title,
                color = TextWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        InertialSpringSwitch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            activeColor = activeColor
        )
    }
}
