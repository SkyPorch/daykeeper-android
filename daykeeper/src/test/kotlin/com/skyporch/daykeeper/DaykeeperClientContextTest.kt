package com.skyporch.daykeeper

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DaykeeperClientContextTest {
    private fun decode(header: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8))
            .jsonObject

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    @Test
    fun sdkVersionComesFromTheGradleVersion() {
        assertTrue(BuildConfig.DAYKEEPER_VERSION.isNotBlank())
        assertEquals(BuildConfig.DAYKEEPER_VERSION, BuildConfig.DAYKEEPER_VERSION.trim())
    }

    @Test
    fun encodesUrlSafeBase64WithoutPaddingOrLineBreaks() {
        // Labels chosen so the standard alphabet would need '+', '/' and '=' padding somewhere.
        val labels = listOf("Café ✓", "Ünïcödé ~~~ ???", "a>b?c", "支持 · サポート 🛟", "x".repeat(150))
        val standard = mutableSetOf<Char>()
        labels.forEach { label ->
            val header = DaykeeperClientContext.encode(mapOf("appName" to label))!!
            assertTrue(header, header.matches(Regex("^[A-Za-z0-9_-]+$")))
            assertEquals(label, decode(header).text("appName"))
            val json = String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8)
            standard += Base64.getEncoder().encodeToString(json.toByteArray()).toSet()
        }
        assertTrue("fixtures must exercise the URL-safe alphabet", '+' in standard || '/' in standard)
        assertTrue("fixtures must exercise padding", '=' in standard)
    }

    @Test
    fun utf8AppLabelRoundTrips() {
        val label = "Ayuda · 支持 · Поддержка 🛟"
        val decoded =
            decode(
                DaykeeperClientContext.encode(
                    mapOf("appName" to label, "appId" to "com.example.app")
                )!!
            )
        assertEquals(label, decoded.text("appName"))
        assertEquals("com.example.app", decoded.text("appId"))
    }

    @Test
    fun blankMissingAndOverlongValuesAreOmittedNotTruncated() {
        val decoded =
            decode(
                DaykeeperClientContext.encode(
                    linkedMapOf(
                        "sdk" to "android",
                        "osVersion" to "",
                        "deviceBrand" to "   ",
                        "deviceModel" to null,
                        "appVersion" to "v".repeat(101),
                        "appBuild" to "b".repeat(100),
                        "appName" to "n".repeat(201),
                        "locale" to " en-US ",
                        "timeZone" to "Z".repeat(65),
                    )
                )!!
            )
        assertEquals(setOf("sdk", "appBuild", "locale"), decoded.keys)
        assertEquals("en-US", decoded.text("locale"))
        assertEquals(100, decoded.text("appBuild")!!.length)
        decoded.values.forEach { assertTrue(it.jsonPrimitive.isString) }
    }

    @Test
    fun nothingWorthSendingProducesNoHeader() {
        assertNull(DaykeeperClientContext.encode(emptyMap()))
        assertNull(DaykeeperClientContext.encode(mapOf("osVersion" to " ", "appId" to null)))
    }

    @Test
    fun headerWithoutAContextDescribesTheSdkAndNeverAnIdentifier() {
        val decoded = decode(DaykeeperClientContext.header(null)!!)
        assertEquals("android", decoded.text("sdk"))
        assertEquals(BuildConfig.DAYKEEPER_VERSION, decoded.text("sdkVersion"))
        assertEquals("android", decoded.text("platform"))
        assertEquals("Android", decoded.text("osName"))
        val allowed =
            setOf(
                "sdk",
                "sdkVersion",
                "platform",
                "osName",
                "osVersion",
                "deviceBrand",
                "deviceModel",
                "appName",
                "appId",
                "appVersion",
                "appBuild",
                "locale",
                "timeZone",
            )
        assertTrue(decoded.keys.toString(), allowed.containsAll(decoded.keys))
        assertFalse(decoded.containsKey("appId"))
    }
}
