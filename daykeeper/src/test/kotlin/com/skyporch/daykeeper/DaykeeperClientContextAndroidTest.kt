package com.skyporch.daykeeper

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Platform reads against a real Android framework (Robolectric), not the JVM stubs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaykeeperClientContextAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun forgetCapturedContext() {
        DaykeeperApplicationContext.context = null
    }

    private fun decode(header: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8))
            .jsonObject

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    // Spans the pre-28 versionCode and pre-33 getPackageInfo branches.
    @Test
    @Config(sdk = [23, 28, 33, 35])
    fun collectsDeviceOsAndHostAppFromTheFramework() {
        val decoded = decode(DaykeeperClientContext.header(context)!!)
        assertEquals("android", decoded.text("platform"))
        assertEquals(Build.VERSION.RELEASE, decoded.text("osVersion"))
        assertEquals(Build.MODEL, decoded.text("deviceModel"))
        assertEquals(Build.BRAND.ifBlank { Build.MANUFACTURER }, decoded.text("deviceBrand"))
        assertEquals(context.packageName, decoded.text("appId"))
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        assertEquals(info.versionCode.toString(), decoded.text("appBuild"))
        assertEquals(info.versionName, decoded.text("appVersion"))
        assertNotNull(decoded.text("appName"))
        assertNotNull(decoded.text("locale"))
        assertNotNull(decoded.text("timeZone"))
    }

    @Test
    fun startupInitializerKeepsOnlyTheApplicationContext() {
        assertNull(DaykeeperApplicationContext.context)
        DaykeeperContextInitializer().create(context)
        assertSame(context.applicationContext, DaykeeperApplicationContext.context)
        assertTrue(DaykeeperContextInitializer().dependencies().isEmpty())
    }

    @Test
    fun clientNamesTheHostAppWithNoHostCodeOnceStartupRan() = runBlocking {
        DaykeeperContextInitializer().create(context)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """{"baseUrl":"https://support.example","websiteToken":"public","subject":"one","identifier":"one","identifierHash":"hash","email":null,"name":"Demo"}"""
                )
            )
            DaykeeperClient(server.url("/").toString(), { "synthetic" }, 5_000).getIdentity()
            val decoded = decode(server.takeRequest().getHeader("Daykeeper-Client")!!)
            assertEquals(context.packageName, decoded.text("appId"))
        }
    }

    @Test
    fun explicitContextIsUsedWhenStartupDidNotRun() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            val client =
                DaykeeperClient(server.url("/").toString(), { "synthetic" }, 5_000, context)
            runCatching { client.createConversation() }
            val decoded = decode(server.takeRequest().getHeader("Daykeeper-Client")!!)
            assertEquals(context.packageName, decoded.text("appId"))
        }
    }
}
