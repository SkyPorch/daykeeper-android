package com.skyporch.daykeeper

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

/**
 * The repository has no binary-compatibility validator, so this pins the public constructor ABI by
 * reflection. Apps compiled against 0.1.0 link to these exact JVM signatures.
 */
class DaykeeperClientAbiTest {
    private val provider = DaykeeperTokenProvider::class.java
    private val string = String::class.java
    private val long = Long::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!
    private val boolean = Boolean::class.javaPrimitiveType!!
    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    private fun hasConstructor(vararg types: Class<*>) =
        DaykeeperClient::class.java.declaredConstructors.any {
            it.parameterTypes.contentEquals(arrayOf(*types))
        }

    @Test
    fun keepsTheKotlinDefaultArgumentStubFrom010() {
        // What `DaykeeperClient(baseUrl, tokenProvider)` compiles to in a Kotlin app.
        assertTrue(hasConstructor(string, provider, long, int, marker))
    }

    @Test
    fun keepsTheJavaOverloadsFrom010() {
        assertTrue(hasConstructor(string, provider))
        assertTrue(hasConstructor(string, provider, long))
    }

    @Test
    fun exposesDeviceContextThroughSeparateOverloads() {
        assertTrue(hasConstructor(string, provider, long, Context::class.java))
        assertTrue(hasConstructor(string, provider, long, Context::class.java, boolean))
        assertTrue(hasConstructor(string, provider, long, Context::class.java, boolean, int, marker))
    }

    @Test
    fun kotlinCallShapesResolveAsDocumented() {
        // Compiles only if each shape resolves to exactly one public constructor.
        val url = "https://support.example/gateway"
        val tokens = DaykeeperTokenProvider { "synthetic" }
        listOf(
                DaykeeperClient(url, tokens),
                DaykeeperClient(url, tokens, 10_000),
                DaykeeperClient(url, tokens, 10_000, null),
                DaykeeperClient(url, tokens, 10_000, null, false),
                DaykeeperClient(url, tokens, 10_000, null, sendDeviceContext = false),
            )
            .forEach { assertNotNull(it) }
    }
}
