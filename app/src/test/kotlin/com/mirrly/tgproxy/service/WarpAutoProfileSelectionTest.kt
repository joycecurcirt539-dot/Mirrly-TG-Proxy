/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import com.mirrly.tgproxy.core.WarpGeneratorFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WarpAutoProfileSelectionTest {

    @Test
    fun testProfilePriorityAttributes() {
        val configProfile = WarpSavedProfile(
            name = "Config 1",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "162.159.192.1:500",
            dnsLine = "1.1.1.1",
            content = "[Interface]\nPrivateKey = aaaa\n",
            origin = WarpProfileOrigin.CONFIGURATOR
        )
        assertTrue("Профиль из конфигуратора должен иметь isPriority=true", configProfile.isPriority)

        val importedProfile = WarpSavedProfile(
            name = "Import 1",
            format = WarpGeneratorFormat.WIREGUARD,
            endpoint = "162.159.192.2:500",
            dnsLine = "1.1.1.1",
            content = "[Interface]\nPrivateKey = bbbb\n",
            origin = WarpProfileOrigin.IMPORTED
        )
        assertTrue("Импортированный профиль должен иметь isPriority=true", importedProfile.isPriority)
    }

    @Test
    fun testProfileJsonOriginSerialization() {
        val original = WarpSavedProfile(
            id = UUID.randomUUID().toString(),
            name = "Imported AWG",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "162.159.192.10:8095",
            dnsLine = "1.1.1.1",
            content = "PrivateKey = test",
            origin = WarpProfileOrigin.IMPORTED
        )

        val json = original.toJsonObject()
        assertEquals(WarpProfileOrigin.IMPORTED.name, json.getString("origin"))

        val restored = WarpSavedProfile.fromJsonObject(json)
        assertTrue(restored != null)
        assertEquals(WarpProfileOrigin.IMPORTED, restored!!.origin)
        assertTrue(restored.isPriority)
    }

    @Test
    fun testLegacyJsonDefaultsToConfiguratorPriority() {
        val original = WarpSavedProfile(
            id = UUID.randomUUID().toString(),
            name = "Old Profile",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "162.159.192.10:8095",
            dnsLine = "1.1.1.1",
            content = "PrivateKey = old",
            origin = WarpProfileOrigin.CONFIGURATOR
        )

        val json = original.toJsonObject()
        json.remove("origin") // Симулируем старый сохраненный JSON без поля origin

        val restored = WarpSavedProfile.fromJsonObject(json)
        assertTrue(restored != null)
        assertEquals(WarpProfileOrigin.CONFIGURATOR, restored!!.origin)
        assertTrue(restored.isPriority)
    }

    @Test
    fun testPriorityResolutionLogic() {
        val importP = WarpSavedProfile(
            id = "imp-1",
            name = "Imported Profile",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "1.1.1.1:500",
            dnsLine = "1.1.1.1",
            content = "p1",
            origin = WarpProfileOrigin.IMPORTED
        )

        val configP = WarpSavedProfile(
            id = "cfg-1",
            name = "Generator Profile",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "2.2.2.2:500",
            dnsLine = "1.1.1.1",
            content = "p2",
            origin = WarpProfileOrigin.CONFIGURATOR
        )

        val list = listOf(importP, configP)

        // Оба типа профилей валидны и приоритетны
        val priorityProfiles = list.filter { it.isPriority }
        assertEquals(2, priorityProfiles.size)
    }

    @Test
    fun testFavoriteTakesPrecedenceWithinPriorityPool() {
        val configNormal = WarpSavedProfile(
            id = "cfg-normal",
            name = "Normal Gen",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "1.1.1.1:500",
            dnsLine = "1.1.1.1",
            content = "p1",
            isFavorite = false,
            origin = WarpProfileOrigin.CONFIGURATOR
        )

        val importedFav = WarpSavedProfile(
            id = "imp-fav",
            name = "Fav Import",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "2.2.2.2:500",
            dnsLine = "1.1.1.1",
            content = "p2",
            isFavorite = true,
            origin = WarpProfileOrigin.IMPORTED
        )

        val list = listOf(configNormal, importedFav)

        val priorityProfiles = list.filter { it.isPriority }
        val favoritePool = priorityProfiles.filter { it.isFavorite }
        val finalPool = if (favoritePool.isNotEmpty()) favoritePool else priorityProfiles

        assertEquals(1, finalPool.size)
        assertEquals("imp-fav", finalPool.first().id)
    }

    @Test
    fun testProfileLifecycleAfterFullWipe() {
        // Симулируем полное удаление всех профилей
        val clearedList = emptyList<WarpSavedProfile>()
        assertEquals(0, clearedList.size)

        // Добавляем сгенерированный профиль после очистки
        val newGen = WarpSavedProfile(
            id = "new-gen-1",
            name = "AWG Gen 1",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "188.114.96.1:8095",
            dnsLine = "1.1.1.1",
            content = "[Interface]\nPrivateKey = abcdef\n",
            origin = WarpProfileOrigin.CONFIGURATOR
        )
        val afterGen = clearedList.toMutableList().apply { add(0, newGen) }
        assertEquals(1, afterGen.size)
        assertEquals("new-gen-1", afterGen.first().id)
        assertTrue(afterGen.first().isPriority)

        // Добавляем импортированный профиль после очистки
        val newImport = WarpSavedProfile(
            id = "new-imp-1",
            name = "AWG Import 1",
            format = WarpGeneratorFormat.AWG_1_0,
            endpoint = "162.159.192.1:500",
            dnsLine = "1.1.1.1",
            content = "[Interface]\nPrivateKey = 123456\n",
            origin = WarpProfileOrigin.IMPORTED
        )
        val afterImport = afterGen.toMutableList().apply { add(0, newImport) }
        assertEquals(2, afterImport.size)
        assertEquals("new-imp-1", afterImport[0].id)
        assertEquals("new-gen-1", afterImport[1].id)
        assertTrue(afterImport.all { it.isPriority })
    }

    @Test
    fun testOnlyConfiguratorAndImportedOriginsSupported() {
        val origins = WarpProfileOrigin.values()
        assertEquals(2, origins.size)
        assertTrue(origins.contains(WarpProfileOrigin.CONFIGURATOR))
        assertTrue(origins.contains(WarpProfileOrigin.IMPORTED))

        val emptyPool = emptyList<WarpSavedProfile>()
        val resolved = emptyPool.firstOrNull()
        assertTrue(resolved == null)
    }
}

