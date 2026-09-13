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

package com.mirrly.tgproxy.core

data class OperaVpnNode(
    val id: String,
    val name: String,
    val endpoint: String,
    val ip: String,
    val port: Int = 443,
    val region: String,
    val countryCode: String = "EU"
)

object OperaVpnRepository {

    val NODES = listOf(
        OperaVpnNode(
            id = "opera_eu_central",
            name = "Europe Central (NL/DE)",
            endpoint = "77.111.247.139:443",
            ip = "77.111.247.139",
            region = "Европа (Амстердам/Франкфурт)",
            countryCode = "EU"
        ),
        OperaVpnNode(
            id = "opera_eu_backup",
            name = "Europe Secondary (DME)",
            endpoint = "77.111.247.143:443",
            ip = "77.111.247.143",
            region = "Европа (Резервный узел)",
            countryCode = "EU"
        ),
        OperaVpnNode(
            id = "opera_us_east",
            name = "Americas East (US)",
            endpoint = "77.111.244.10:443",
            ip = "77.111.244.10",
            region = "Северная Америка",
            countryCode = "US"
        ),
        OperaVpnNode(
            id = "opera_ap_east",
            name = "Asia East (SG)",
            endpoint = "77.111.246.10:443",
            ip = "77.111.246.10",
            region = "Азия (Сингапур)",
            countryCode = "SG"
        )
    )

    fun getDefaultNode(): OperaVpnNode = NODES.first()

    fun findNodeById(id: String): OperaVpnNode? = NODES.firstOrNull { it.id == id }

    fun findNodeByEndpoint(endpoint: String): OperaVpnNode? =
        NODES.firstOrNull { it.endpoint.equals(endpoint.trim(), ignoreCase = true) }
}
