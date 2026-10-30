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
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.animation.*
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

enum class ContributorTier(
    @StringRes val titleRes: Int,
    @StringRes val badgeLabelRes: Int,
    val primaryColor: Color,
    val secondaryColor: Color,
    val iconRes: Int
) {
    LEGENDARY_PIONEER(
        titleRes = R.string.fame_tier_pioneer_title,
        badgeLabelRes = R.string.fame_tier_pioneer_badge,
        primaryColor = Color(0xFFFFBD22),
        secondaryColor = Color(0xFFFF9D00),
        iconRes = R.drawable.ic_crown
    ),
    BUG_HUNTER(
        titleRes = R.string.fame_tier_bughunter_title,
        badgeLabelRes = R.string.fame_tier_bughunter_badge,
        primaryColor = Color(0xFF00F5D4),
        secondaryColor = Color(0xFF00B4D8),
        iconRes = R.drawable.ic_bug
    ),
    BETA_TESTER(
        titleRes = R.string.fame_tier_tester_title,
        badgeLabelRes = R.string.fame_tier_tester_badge,
        primaryColor = Color(0xFFC084FC),
        secondaryColor = Color(0xFF818CF8),
        iconRes = R.drawable.ic_volunteer_badge
    ),
    TG_SUBSCRIBER(
        titleRes = R.string.fame_tier_tg_title,
        badgeLabelRes = R.string.fame_tier_tg_badge,
        primaryColor = Color(0xFF26A5E4),
        secondaryColor = Color(0xFF0088CC),
        iconRes = R.drawable.ic_telegram
    )
}

enum class FameCategoryFilter(
    @StringRes val labelRes: Int,
    val iconRes: Int
) {
    ALL(R.string.fame_filter_all, R.drawable.ic_fame_grid),
    PIONEERS(R.string.fame_filter_pioneers, R.drawable.ic_crown),
    BUG_HUNTERS(R.string.fame_filter_bughunters, R.drawable.ic_bug),
    COMMUNITY(R.string.fame_filter_community, R.drawable.ic_user)
}

enum class BugHunterClass(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val accentColor: Color,
    @DrawableRes val iconRes: Int
) {
    NETWORK_CORE(
        titleRes = R.string.fame_class_network_title,
        subtitleRes = R.string.fame_class_network_desc,
        accentColor = Color(0xFF00F5D4),
        iconRes = R.drawable.ic_diag_protocol
    ),
    SYSTEM_UI(
        titleRes = R.string.fame_class_system_ui_title,
        subtitleRes = R.string.fame_class_system_ui_desc,
        accentColor = Color(0xFFA78BFA),
        iconRes = R.drawable.ic_split_tunnel
    )
}

enum class BugHunterSubclass(
    val parentClass: BugHunterClass,
    @StringRes val labelRes: Int,
    val badgeColor: Color
) {
    MTPROTO_TUNNEL(
        parentClass = BugHunterClass.NETWORK_CORE,
        labelRes = R.string.fame_subclass_mtproto,
        badgeColor = Color(0xFF00F5D4)
    ),
    CLOUDFLARE_WORKERS(
        parentClass = BugHunterClass.NETWORK_CORE,
        labelRes = R.string.fame_subclass_workers,
        badgeColor = Color(0xFF38BDF8)
    ),
    WINDOW_INSETS(
        parentClass = BugHunterClass.SYSTEM_UI,
        labelRes = R.string.fame_subclass_insets,
        badgeColor = Color(0xFFA78BFA)
    ),
    POWER_EFFICIENCY(
        parentClass = BugHunterClass.SYSTEM_UI,
        labelRes = R.string.fame_subclass_power,
        badgeColor = Color(0xFF34D399)
    )
}

data class DigitalFingerprint(
    val formattedHash: String,
    val fullHash: String,
    val angles: FloatArray,
    val ringRatios: FloatArray,
    val matrixPattern: List<Boolean>,
    val seedColorOffset: Float
)

data class Contributor(
    val id: String,
    val name: String,
    val handle: String,
    @StringRes val roleRes: Int,
    @StringRes val contributionRes: Int,
    val tier: ContributorTier,
    val githubUrl: String? = null,
    val bugHunterSubclass: BugHunterSubclass? = null
) {
    val fingerprint: DigitalFingerprint by lazy {
        computeFingerprint(name, id, id)
    }
}

private fun computeFingerprint(name: String, id: String, role: String): DigitalFingerprint {
    val input = "$name::$id::$role::MIRRLY_CORE_2026"
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02X".format(it) }
    val formattedHash = hex.take(16).chunked(4).joinToString("-")
    val fullHash = hex.chunked(8).joinToString(":")

    val angles = FloatArray(6) { i ->
        ((digest[i * 2].toInt() and 0xFF) * 360f / 255f)
    }

    val ringRatios = FloatArray(3) { i ->
        0.35f + (digest[12 + i].toInt() and 0xFF) * 0.20f / 255f
    }

    val matrixPattern = (0 until 9).map { idx ->
        ((digest[16 + (idx % 16)].toInt() and (1 shl (idx % 8))) != 0)
    }

    return DigitalFingerprint(
        formattedHash = formattedHash,
        fullHash = fullHash,
        angles = angles,
        ringRatios = ringRatios,
        matrixPattern = matrixPattern,
        seedColorOffset = (digest[0].toInt() and 0xFF) / 255f
    )
}

private val ContributorsList = listOf(
    Contributor(
        id = "amurcanov",
        name = "amurcanov",
        handle = "@amurcanov",
        roleRes = R.string.fame_c_amurcanov_role,
        contributionRes = R.string.fame_c_amurcanov_contrib,
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/amurcanov"
    ),
    Contributor(
        id = "flowseal",
        name = "Flowseal",
        handle = "@Flowseal",
        roleRes = R.string.fame_c_flowseal_role,
        contributionRes = R.string.fame_c_flowseal_contrib,
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/Flowseal"
    ),
    Contributor(
        id = "master_key",
        name = "Master Key",
        handle = "Master Key",
        roleRes = R.string.fame_c_masterkey_role,
        contributionRes = R.string.fame_c_masterkey_contrib,
        tier = ContributorTier.LEGENDARY_PIONEER
    ),
    Contributor(
        id = "grovymon",
        name = "Grovymon",
        handle = "@Grovymon",
        roleRes = R.string.fame_c_grovymon_role,
        contributionRes = R.string.fame_c_grovymon_contrib,
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/Grovymon"
    ),
    Contributor(
        id = "zzzxxx888207_design",
        name = "zzzxxx888207-design",
        handle = "@zzzxxx888207-design",
        roleRes = R.string.fame_c_zzzxxx_role,
        contributionRes = R.string.fame_c_zzzxxx_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/zzzxxx888207-design",
        bugHunterSubclass = BugHunterSubclass.CLOUDFLARE_WORKERS
    ),
    Contributor(
        id = "bbibux",
        name = "BbIBux",
        handle = "@BbIBux",
        roleRes = R.string.fame_c_bbibux_role,
        contributionRes = R.string.fame_c_bbibux_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/BbIBux",
        bugHunterSubclass = BugHunterSubclass.MTPROTO_TUNNEL
    ),
    Contributor(
        id = "ustiprog",
        name = "ustiprog",
        handle = "@ustiprog",
        roleRes = R.string.fame_c_ustiprog_role,
        contributionRes = R.string.fame_c_ustiprog_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/ustiprog",
        bugHunterSubclass = BugHunterSubclass.POWER_EFFICIENCY
    ),
    Contributor(
        id = "vikkalm",
        name = "VikKalm",
        handle = "@VikKalm",
        roleRes = R.string.fame_c_vikkalm_role,
        contributionRes = R.string.fame_c_vikkalm_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/VikKalm",
        bugHunterSubclass = BugHunterSubclass.CLOUDFLARE_WORKERS
    ),
    Contributor(
        id = "liveonloan",
        name = "liveonloan",
        handle = "@liveonloan",
        roleRes = R.string.fame_c_liveonloan_role,
        contributionRes = R.string.fame_c_liveonloan_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/liveonloan",
        bugHunterSubclass = BugHunterSubclass.WINDOW_INSETS
    ),
    Contributor(
        id = "40oil",
        name = "40OIL",
        handle = "@40OIL",
        roleRes = R.string.fame_c_40oil_role,
        contributionRes = R.string.fame_c_40oil_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/40OIL",
        bugHunterSubclass = BugHunterSubclass.WINDOW_INSETS
    ),
    Contributor(
        id = "crazyghostrider",
        name = "CrazyGhostRider",
        handle = "@CrazyGhostRider",
        roleRes = R.string.fame_c_crazyghostrider_role,
        contributionRes = R.string.fame_c_crazyghostrider_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/CrazyGhostRider",
        bugHunterSubclass = BugHunterSubclass.MTPROTO_TUNNEL
    ),
    Contributor(
        id = "mslight",
        name = "MSLight",
        handle = "@MSLight",
        roleRes = R.string.fame_c_mslight_role,
        contributionRes = R.string.fame_c_mslight_contrib,
        tier = ContributorTier.BETA_TESTER,
        githubUrl = "https://github.com/MSLight"
    ),
    Contributor(
        id = "aseptronn",
        name = "Aseptronn",
        handle = "@Aseptronn",
        roleRes = R.string.fame_c_aseptronn_role,
        contributionRes = R.string.fame_c_aseptronn_contrib,
        tier = ContributorTier.BETA_TESTER,
        githubUrl = "https://github.com/Aseptronn"
    ),
    Contributor(
        id = "shon4k",
        name = "Shon4k",
        handle = "@Shon4k",
        roleRes = R.string.fame_c_shon4k_role,
        contributionRes = R.string.fame_c_shon4k_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "linar_s",
        name = "Linar S",
        handle = "Linar S",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "roartrex",
        name = "RoarTRex",
        handle = "RoarTRex",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "vanya",
        name = "Ваня",
        handle = "Ваня",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "lucifer",
        name = "Lucifer",
        handle = "Lucifer",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "dalison",
        name = "Dalison",
        handle = "Dalison",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "s_pipe_c",
        name = "S|c",
        handle = "S|c",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "anton_1",
        name = "Антон",
        handle = "Антон",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "astimir_meikulov",
        name = "Astimir Meikulov",
        handle = "Astimir Meikulov",
        roleRes = R.string.fame_c_astimir_role,
        contributionRes = R.string.fame_c_astimir_contrib,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "dimaakaj",
        name = "Dimaakaj",
        handle = "@Dimaakaj",
        roleRes = R.string.fame_c_dimaakaj_role,
        contributionRes = R.string.fame_c_dimaakaj_contrib,
        tier = ContributorTier.BUG_HUNTER,
        bugHunterSubclass = BugHunterSubclass.CLOUDFLARE_WORKERS
    ),
    Contributor(
        id = "creeepeeer",
        name = "creeepeeer",
        handle = "@creeepeeer",
        roleRes = R.string.fame_c_creeepeeer_role,
        contributionRes = R.string.fame_c_creeepeeer_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/creeepeeer",
        bugHunterSubclass = BugHunterSubclass.MTPROTO_TUNNEL
    ),
    Contributor(
        id = "rockhard_tolift",
        name = "RockHard ToLift",
        handle = "RockHard ToLift",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "roma_millionerov",
        name = "Roma Millionerov",
        handle = "Roma Millionerov",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "sickalex",
        name = "sickalex",
        handle = "sickalex",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "dimmon",
        name = "Диммон",
        handle = "Диммон",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "ahura",
        name = "Ahura",
        handle = "Ahura",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "rinat",
        name = "Ринат",
        handle = "Ринат",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "ali",
        name = "Ali",
        handle = "Ali",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "nail_torg",
        name = "Наиль torg",
        handle = "Наиль torg",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "aldos",
        name = "ALDOS",
        handle = "ALDOS",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "viper_inc",
        name = "viper inc",
        handle = "viper inc",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "petr",
        name = "Пётр",
        handle = "Пётр",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "fire",
        name = "Fire",
        handle = "Fire",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "capt_titus",
        name = "Captain Demetrian Titus",
        handle = "Captain Demetrian Titus",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "dtn",
        name = "dtn",
        handle = "dtn",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "lunchem_mellelertacges",
        name = "Lunchem Mellelertacges",
        handle = "Lunchem Mellelertacges",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "old",
        name = "Old",
        handle = "Old",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "mikhail",
        name = "Mikhail",
        handle = "Mikhail",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "redalexo",
        name = "Redalexo",
        handle = "Redalexo",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "oleg",
        name = "Oleg",
        handle = "Oleg",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "x_4",
        name = "x 4",
        handle = "x 4",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "konstantin",
        name = "Константин",
        handle = "Константин",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "alexander",
        name = "Alexander",
        handle = "Alexander",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "gipnozhaba",
        name = "Гипножаба",
        handle = "Гипножаба",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "dot_user",
        name = ".",
        handle = ".",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "aleksandr",
        name = "Александр",
        handle = "Александр",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "caesar013",
        name = "caesar013",
        handle = "caesar013",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "anton_sanych",
        name = "Антон Саныч из @drszone",
        handle = "Антон Саныч из @drszone",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "adem",
        name = "Adem",
        handle = "Adem",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "amir_hossein",
        name = "Amir Hossein",
        handle = "Amir Hossein",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "ayca_aysin_turan",
        name = "Ayça Ayşin Turan",
        handle = "Ayça Ayşin Turan",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "vosstavshiy",
        name = "восставший из-под одеяла",
        handle = "восставший из-под одеяла",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "anton_2",
        name = "Антон",
        handle = "Антон",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "usxru",
        name = "@usxru",
        handle = "@usxru",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "moli_asrali",
        name = "๓๏|เ คราคli",
        handle = "๓๏|เ คราคli",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "ochym3l1y",
        name = "Ochym3l1y",
        handle = "Ochym3l1y",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "zews_black_devil",
        name = "ZeWs (Black Devil",
        handle = "ZeWs (Black Devil",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "kilovaib",
        name = "kilovaib",
        handle = "kilovaib",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "dmitriy",
        name = "Дмитрий",
        handle = "Дмитрий",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "aleksey_z",
        name = "Алексей",
        handle = "Алексей",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "wehrmacht",
        name = "Wehrmacht",
        handle = "Wehrmacht",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "tada_no_random",
        name = "ただのランダムな男",
        handle = "ただのランダムな男",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "mike",
        name = "Mike",
        handle = "Mike",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "leanlike",
        name = "𝐋 𝐄 𝐀 𝐍 𝐋 𝐈 𝐊 𝐄",
        handle = "𝐋 𝐄 𝐀 𝐍 𝐋 𝐈 𝐊 𝐄",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "nikita",
        name = "Никита",
        handle = "Никита",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "alex_only",
        name = "𝓐𝓵𝓮𝔁 𝓞𝓷𝓵𝔂",
        handle = "𝓐𝓵𝓮𝔁 𝓞𝓷𝓵𝔂",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "yuriy",
        name = "Юрий",
        handle = "Юрий",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "frekzed",
        name = "Fřęķżēđ™",
        handle = "Fřęķżēđ™",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "cristianu_ranaldu",
        name = "CRISTIANU RANALDU",
        handle = "CRISTIANU RANALDU",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "nastaldgy",
        name = "NASTALDGY",
        handle = "NASTALDGY",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "three_dots_user",
        name = "...",
        handle = "...",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "aleksey_2",
        name = "Алексей",
        handle = "Алексей",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "danya",
        name = "Даня",
        handle = "Даня",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "shiksha",
        name = "shiksha",
        handle = "shiksha",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "shiawase",
        name = "Shiawase",
        handle = "Shiawase",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "absolutely_normal",
        name = "AbsolutelyNormal",
        handle = "AbsolutelyNormal",
        roleRes = R.string.fame_role_tg_participant,
        contributionRes = R.string.fame_contrib_tg_channel,
        tier = ContributorTier.TG_SUBSCRIBER
    )
)

/**
 * Procedural generative canvas that renders an individual high-tech biometric/cryptographic identicon.
 */
@Composable
fun DigitalFingerprintCanvas(
    fingerprint: DigitalFingerprint,
    tier: ContributorTier,
    size: Dp = 48.dp,
    animated: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fpRotation")
    val rotation by if (animated) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "fpRot"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val pulseScale by if (animated) {
        infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fpPulse"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val maxRadius = (w.coerceAtMost(h) / 2f) * 0.90f

        // Draw Concentric Dashed Radar Arcs
        val r1 = maxRadius * fingerprint.ringRatios[0] * pulseScale
        val r2 = maxRadius * fingerprint.ringRatios[1]
        val r3 = maxRadius * fingerprint.ringRatios[2]

        drawCircle(
            color = tier.primaryColor.copy(alpha = 0.35f),
            radius = r1,
            center = center,
            style = Stroke(width = 1.2.dp.toPx())
        )

        drawArc(
            color = tier.secondaryColor.copy(alpha = 0.75f),
            startAngle = rotation + fingerprint.angles[0],
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - r2, center.y - r2),
            size = Size(r2 * 2f, r2 * 2f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )

        drawArc(
            color = tier.primaryColor.copy(alpha = 0.85f),
            startAngle = -rotation + fingerprint.angles[1] + 180f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - r3, center.y - r3),
            size = Size(r3 * 2f, r3 * 2f),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw Constellation Nodes & Connecting Rays
        for (i in 0 until 4) {
            val rad = Math.toRadians((rotation + fingerprint.angles[i + 2]).toDouble())
            val nodeRadius = maxRadius * (0.45f + (i * 0.13f))
            val nx = center.x + (cos(rad) * nodeRadius).toFloat()
            val ny = center.y + (sin(rad) * nodeRadius).toFloat()

            // Ray from center
            drawLine(
                color = tier.primaryColor.copy(alpha = 0.25f),
                start = center,
                end = Offset(nx, ny),
                strokeWidth = 1.dp.toPx()
            )

            // Node Dot
            drawCircle(
                color = tier.primaryColor,
                radius = 2.2.dp.toPx(),
                center = Offset(nx, ny)
            )
        }

        // Draw Central Geometric Identity Core (3x3 Micro-Matrix)
        val matrixSize = maxRadius * 0.32f
        val step = matrixSize / 2f
        val startX = center.x - step
        val startY = center.y - step

        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val idx = row * 3 + col
                val isFilled = fingerprint.matrixPattern.getOrElse(idx) { true }
                val px = startX + col * step
                val py = startY + row * step
                if (isFilled) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.90f),
                        radius = 1.5.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HallOfFameScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    var selectedContributor by remember { mutableStateOf<Contributor?>(null) }
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        val targetUrl = pendingRedirectUrl ?: ""
        ExternalLinkConfirmDialog(
            url = targetUrl,
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    if (selectedContributor != null) {
        val contributor = selectedContributor!!
        ContributorPassportSheet(
            contributor = contributor,
            onDismiss = { selectedContributor = null },
            onOpenGithub = { url ->
                selectedContributor = null
                pendingRedirectUrl = url
            }
        )
    }

    val pioneers = remember { ContributorsList.filter { it.tier == ContributorTier.LEGENDARY_PIONEER } }
    val bugHunters = remember { ContributorsList.filter { it.tier == ContributorTier.BUG_HUNTER } }
    val communityList = remember {
        ContributorsList.filter {
            it.tier == ContributorTier.TG_SUBSCRIBER || it.tier == ContributorTier.BETA_TESTER
        }
    }
    val totalHeroesCount = remember { ContributorsList.size }
    var selectedFilter by rememberSaveable { mutableStateOf(FameCategoryFilter.ALL) }

    val sheetBlurProgress by animateFloatAsState(
        targetValue = if (BottomSheetStateTracker.isAnySheetOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
        label = "fameBodySheetBlur"
    )
    val density = LocalDensity.current.density
    val bodySheetBlurModifier = if (sheetBlurProgress > 0.005f) {
        Modifier.graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurPx = sheetBlurProgress * 24f * density
                if (blurPx > 0.5f) {
                    renderEffect = RenderEffect.createBlurEffect(
                        blurPx,
                        blurPx,
                        Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .then(bodySheetBlurModifier)
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 1. HERO EMBLEM & METRICS CARD ──
            HeroHallOfFameCard(
                pioneersCount = pioneers.size,
                bugHuntersCount = bugHunters.size,
                communityCount = communityList.size,
                totalCount = totalHeroesCount,
                onBannerClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    Toast.makeText(context, context.getString(R.string.fame_fingerprint_info_toast), Toast.LENGTH_SHORT).show()
                }
            )

            // ── 2. CATEGORY FILTER BAR ──
            CategoryFilterBar(
                selectedFilter = selectedFilter,
                onSelectFilter = { filter ->
                    selectedFilter = filter
                }
            )

            // ── 3. SECTION: LEGENDS (PIONEERS) ──
            if (selectedFilter == FameCategoryFilter.ALL || selectedFilter == FameCategoryFilter.PIONEERS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 1),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FameSectionHeader(
                        iconRes = R.drawable.ic_crown,
                        title = stringResource(R.string.fame_section_legends_title),
                        subtitle = stringResource(R.string.fame_section_legends_subtitle),
                        count = pioneers.size,
                        accentColor = Color(0xFFFFBD22)
                    )

                    pioneers.forEachIndexed { idx, contributor ->
                        PioneerLegendCard(
                            contributor = contributor,
                            rank = idx + 1,
                            modifier = Modifier.staggeredEntrance(index = 2 + idx),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedContributor = contributor
                            }
                        )
                    }
                }
            }

            // ── 4. SECTION: BUG HUNTERS ──
            if (selectedFilter == FameCategoryFilter.ALL || selectedFilter == FameCategoryFilter.BUG_HUNTERS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 4),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FameSectionHeader(
                        iconRes = R.drawable.ic_bug,
                        title = stringResource(R.string.fame_section_bughunters),
                        subtitle = stringResource(R.string.fame_section_bughunters_subtitle),
                        count = bugHunters.size,
                        accentColor = Color(0xFF00F5D4)
                    )

                    BugHunterClass.values().forEachIndexed { classIdx, bugClass ->
                        val classHunters = remember(bugHunters) {
                            bugHunters.filter { it.bugHunterSubclass?.parentClass == bugClass }
                        }
                        if (classHunters.isNotEmpty()) {
                            BugHunterClassGroup(
                                bugClass = bugClass,
                                hunters = classHunters,
                                modifier = Modifier.staggeredEntrance(index = 5 + classIdx),
                                onSelectContributor = { contributor ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedContributor = contributor
                                }
                            )
                        }
                    }
                }
            }

            // ── 5. SECTION: COMMUNITY ──
            if (selectedFilter == FameCategoryFilter.ALL || selectedFilter == FameCategoryFilter.COMMUNITY) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 7),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FameSectionHeader(
                        iconRes = R.drawable.ic_user,
                        title = stringResource(R.string.fame_section_community),
                        subtitle = stringResource(R.string.fame_section_community_subtitle),
                        count = communityList.size,
                        accentColor = Color(0xFF26A5E4)
                    )

                    val chunkedCommunity = remember(communityList) { communityList.chunked(2) }
                    chunkedCommunity.forEachIndexed { rowIdx, pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .staggeredEntrance(index = 8 + (rowIdx % 6)),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { member ->
                                CommunityMemberCard(
                                    contributor = member,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedContributor = member
                                    }
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Frosted Glass Header Panel (Pinned at Top over scrolling cards with smooth fade!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.94f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .adaptiveContainerWidth(600.dp)
                    .align(Alignment.Center)
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.fame_title),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_left),
                                contentDescription = stringResource(R.string.action_back),
                                tint = TextWhite,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }

        // Cyber Particles floating over entire hall of fame screen interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 38,
            alphaMultiplier = 0.65f
        )
    }
}

@Composable
private fun HeroHallOfFameCard(
    pioneersCount: Int,
    bugHuntersCount: Int,
    communityCount: Int,
    totalCount: Int,
    onBannerClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 0)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(16.dp),
                sweepColor = Color(0xFFFFBD22).copy(alpha = 0.65f)
            )
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Icon + Title & SHA-256 Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, Color(0xFFFFBD22).copy(alpha = 0.40f), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_hall_of_fame),
                            contentDescription = null,
                            tint = Color(0xFFFFBD22),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.fame_hero_title),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.fame_fingerprint_badge_header),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = Color(0xFFFFBD22).copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Minimalist SHA-256 info button with springPress
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                        .springPress(onClick = onBannerClick)
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "SHA-256",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }

            // Dignified description
            Text(
                text = stringResource(R.string.fame_desc),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = TextMuted
            )

            // Compact Stats Bar with thin AmoledBorder dividers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FameStatCell(
                    count = pioneersCount,
                    label = stringResource(R.string.fame_metric_pioneers),
                    color = Color(0xFFFFBD22),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(AmoledBorder)
                )
                FameStatCell(
                    count = bugHuntersCount,
                    label = stringResource(R.string.fame_metric_bughunters),
                    color = Color(0xFF00F5D4),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(AmoledBorder)
                )
                FameStatCell(
                    count = communityCount,
                    label = stringResource(R.string.fame_metric_community),
                    color = Color(0xFF26A5E4),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(AmoledBorder)
                )
                FameStatCell(
                    count = totalCount,
                    label = stringResource(R.string.fame_metric_total),
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FameStatCell(
    count: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = count.toString(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CategoryFilterBar(
    selectedFilter: FameCategoryFilter,
    onSelectFilter: (FameCategoryFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FameCategoryFilter.values().forEach { filter ->
            val isSelected = filter == selectedFilter
            val filterAccent = when (filter) {
                FameCategoryFilter.ALL -> ActiveGreenLed
                FameCategoryFilter.PIONEERS -> Color(0xFFFFBD22)
                FameCategoryFilter.BUG_HUNTERS -> Color(0xFF00F5D4)
                FameCategoryFilter.COMMUNITY -> Color(0xFF26A5E4)
            }
            val borderColor = if (isSelected) filterAccent else AmoledBorder
            val iconTint = if (isSelected) filterAccent else TextMuted

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Transparent)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .springPress(onClick = { onSelectFilter(filter) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = filter.iconRes),
                    contentDescription = stringResource(filter.labelRes),
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun FameSectionHeader(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    count: Int,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder)
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PioneerLegendCard(
    contributor: Contributor,
    rank: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tier = contributor.tier
    val fp = contributor.fingerprint

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
            .springPress(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFFFFBD22).copy(alpha = 0.35f), CircleShape)
            ) {
                DigitalFingerprintCanvas(
                    fingerprint = fp,
                    tier = tier,
                    size = 34.dp,
                    animated = true
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFFFFBD22).copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "#$rank",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFBD22),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = contributor.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "#${fp.formattedHash}",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }

                Text(
                    text = stringResource(contributor.roleRes),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFFBD22),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = stringResource(contributor.contributionRes),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BugHunterClassGroup(
    bugClass: BugHunterClass,
    hunters: List<Contributor>,
    modifier: Modifier = Modifier,
    onSelectContributor: (Contributor) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Class Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Left color accent bar
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(bugClass.accentColor)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(bugClass.titleRes),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(bugClass.subtitleRes),
                            fontSize = 10.5.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Hunter count in this class
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, bugClass.accentColor.copy(alpha = 0.40f))
                ) {
                    Text(
                        text = hunters.size.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = bugClass.accentColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            // Subclasses groups
            val groupedBySubclass = remember(hunters) {
                hunters.groupBy { it.bugHunterSubclass }
            }

            groupedBySubclass.forEach { (subclass, subHunters) ->
                if (subclass != null) {
                    // Subclass divider / label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(subclass.badgeColor)
                        )
                        Text(
                            text = stringResource(subclass.labelRes),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = subclass.badgeColor,
                            letterSpacing = 0.3.sp
                        )
                        Text(
                            text = "· ${subHunters.size}",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }

                // Minimalist rows for each hunter in subclass
                subHunters.forEachIndexed { itemIdx, hunter ->
                    BugHunterMinimalRow(
                        contributor = hunter,
                        subclassColor = subclass?.badgeColor ?: bugClass.accentColor,
                        onClick = { onSelectContributor(hunter) }
                    )
                    if (itemIdx < subHunters.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AmoledBorder.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BugHunterMinimalRow(
    contributor: Contributor,
    subclassColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fp = contributor.fingerprint

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .springPress(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        // Mini Avatar Identicon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(1.dp, subclassColor.copy(alpha = 0.35f), CircleShape)
        ) {
            DigitalFingerprintCanvas(
                fingerprint = fp,
                tier = contributor.tier,
                size = 22.dp,
                animated = true
            )
        }

        // Details Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = contributor.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "#${fp.formattedHash}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }

            Text(
                text = stringResource(contributor.roleRes),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = subclassColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = stringResource(contributor.contributionRes),
                fontSize = 10.5.sp,
                color = TextMuted,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun CommunityMemberCard(
    contributor: Contributor,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tier = contributor.tier
    val fp = contributor.fingerprint
    val accentColor = tier.primaryColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
            .springPress(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape)
                ) {
                    DigitalFingerprintCanvas(
                        fingerprint = fp,
                        tier = tier,
                        size = 22.dp,
                        animated = true
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contributor.name,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(tier.badgeLabelRes),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = "#${fp.formattedHash}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Interactive Contributor Passport bottom sheet showing the contributor's digital footprint.
 * Adheres to MirrlyUI Dogma 2 (Zero-Popup Rule) and tactile spring kinetics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContributorPassportSheet(
    contributor: Contributor,
    onDismiss: () -> Unit,
    onOpenGithub: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val tier = contributor.tier
    val fp = contributor.fingerprint

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 520.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 12.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // Tier Badge Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, tier.primaryColor.copy(alpha = 0.50f))
            ) {
                Text(
                    text = stringResource(tier.titleRes).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = tier.primaryColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }

            // Generative Identicon Showcase with ring & glow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, tier.primaryColor.copy(alpha = 0.40f), CircleShape)
            ) {
                DigitalFingerprintCanvas(
                    fingerprint = fp,
                    tier = tier,
                    size = 72.dp,
                    animated = true
                )
            }

            // Name & Role
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = contributor.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(contributor.roleRes),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = tier.primaryColor,
                    textAlign = TextAlign.Center
                )
            }

            // Full Contribution Description Box (Outline-only)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.fame_dialog_contrib_header),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                    Text(
                        text = stringResource(contributor.contributionRes),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = TextWhite
                    )
                }
            }

            // Cryptographic Verified Fingerprint Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                                contentDescription = null,
                                tint = tier.primaryColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(R.string.fame_dialog_fingerprint_header),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = tier.primaryColor
                            )
                        }

                        Text(
                            text = "SHA-256",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }

                    Text(
                        text = fp.fullHash,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextWhite,
                        lineHeight = 15.sp
                    )

                    // Copy button with springPress & haptic
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Digital Fingerprint", fp.fullHash))
                                Toast.makeText(context, context.getString(R.string.fame_dialog_copied_toast), Toast.LENGTH_SHORT).show()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.fame_dialog_btn_copy),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite
                            )
                        }
                    }
                }
            }

            // GitHub Profile Link with springPress
            if (contributor.githubUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenGithub(contributor.githubUrl)
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.fame_dialog_btn_github),
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp,
                            color = TextWhite
                        )
                    }
                }
            }

            // Close Button with springPress
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.fame_dialog_btn_close),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
