package com.skyporch.daykeeper

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Platform reads against a real Android framework (Robolectric), not the JVM stubs.
 *
 * The unit-test manifest does declare InitializationProvider with this library's initializer, but
 * Robolectric does not create manifest ContentProviders on its own, so nothing has run before a test
 * body. The holder is reset around every test regardless, and [manifestProviderRunsTheInitializer]
 * starts the provider explicitly to prove the manifest wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaykeeperClientContextAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity =
        """{"baseUrl":"https://support.example","websiteToken":"public","subject":"one","identifier":"one","identifierHash":"hash","email":null,"name":"Demo"}"""

    @Before
    @After
    fun forgetCapturedContext() {
        DaykeeperApplicationContext.context = null
    }

    private fun decode(header: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8))
            .jsonObject

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    /** A context that reports another package and is its own application context. */
    private fun otherPackage(name: String): Context =
        object : ContextWrapper(context) {
            override fun getPackageName() = name

            override fun getApplicationContext() = this
        }

    private fun identityRequest(client: (String) -> DaykeeperClient): RecordedRequest =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody(identity))
                client(server.url("/").toString()).getIdentity()
                server.takeRequest()
            }
        }

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
    @Config(sdk = [28, 35])
    fun reportsTheFullLongVersionCode() {
        val code = Int.MAX_VALUE.toLong() + 42
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).apply {
            longVersionCode = code
            versionName = "7.1.0"
        }
        val decoded = decode(DaykeeperClientContext.header(context)!!)
        assertEquals("2147483689", decoded.text("appBuild"))
        assertEquals(code.toString(), decoded.text("appBuild"))
        assertEquals("7.1.0", decoded.text("appVersion"))
    }

    @Test
    fun manifestProviderRunsTheInitializer() {
        assertNull(DaykeeperApplicationContext.context)
        Robolectric.buildContentProvider(InitializationProvider::class.java).create()
        assertSame(context.applicationContext, DaykeeperApplicationContext.context)
    }

    @Test
    fun startupInitializerKeepsOnlyTheApplicationContext() {
        assertNull(DaykeeperApplicationContext.context)
        DaykeeperContextInitializer().create(context)
        assertSame(context.applicationContext, DaykeeperApplicationContext.context)
        assertTrue(DaykeeperContextInitializer().dependencies().isEmpty())
    }

    @Test
    fun startupInitializerNeverThrows() {
        val broken =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = error("broken host context")
            }
        DaykeeperContextInitializer().create(broken)
        assertNull(DaykeeperApplicationContext.context)
    }

    @Test
    fun clientNamesTheHostAppWithNoHostCodeOnceStartupRan() {
        DaykeeperContextInitializer().create(context)
        val request = identityRequest { DaykeeperClient(it, { "synthetic" }, 5_000) }
        assertEquals(
            context.packageName,
            decode(request.getHeader("Daykeeper-Client")!!).text("appId"),
        )
    }

    @Test
    fun explicitContextWinsOverTheStartupContext() {
        DaykeeperApplicationContext.context = otherPackage("com.example.startup")
        val request = identityRequest { DaykeeperClient(it, { "synthetic" }, 5_000, context) }
        assertEquals(
            context.packageName,
            decode(request.getHeader("Daykeeper-Client")!!).text("appId"),
        )
        val startupOnly = identityRequest { DaykeeperClient(it, { "synthetic" }, 5_000) }
        assertEquals(
            "com.example.startup",
            decode(startupOnly.getHeader("Daykeeper-Client")!!).text("appId"),
        )
    }

    @Test
    fun optOutSendsNoHeaderEvenWithAContextAvailable() {
        DaykeeperContextInitializer().create(context)
        val request =
            identityRequest {
                DaykeeperClient(it, { "synthetic" }, 5_000, context, sendDeviceContext = false)
            }
        assertNull(request.getHeader("Daykeeper-Client"))
    }

    @Test
    fun aVeryLongApplicationIdIsStillReported() {
        val id = "com.example." + "a".repeat(150)
        assertEquals(id, decode(DaykeeperClientContext.header(otherPackage(id))!!).text("appId"))
    }
}
