package com.skyporch.daykeeper

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.startup.Initializer
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.ByteString.Companion.encodeUtf8

/**
 * The `Daykeeper-Client` request header: base64url (no padding) of a small UTF-8 JSON object that
 * describes the device, OS, host app and SDK so support agents see it on the contact without any
 * host code. It is presentation context only; values the host backend signs into the customer
 * token take precedence on the gateway, field by field.
 *
 * Never included: ANDROID_ID, advertising ID, IMEI, serial number, user-assigned device name,
 * location, or anything that needs a permission.
 */
internal object DaykeeperClientContext {
    const val HEADER = "Daykeeper-Client"

    /** The gateway ignores a longer header; never send one it will drop. */
    private const val MAX_HEADER_LENGTH = 4096

    private val limits =
        mapOf(
            "appName" to 200,
            // A legal applicationId can exceed 100 characters; the gateway accepts 200.
            "appId" to 200,
            "sdkVersion" to 40,
            "locale" to 35,
            "timeZone" to 64,
        )

    /**
     * The header value for [fields], or null when nothing is worth sending. Blank values and values
     * longer than their limit are dropped, never truncated.
     */
    fun encode(fields: Map<String, String?>): String? {
        val kept =
            fields.mapNotNull { (key, raw) ->
                val value = raw?.trim()
                if (value.isNullOrEmpty() || value.length > (limits[key] ?: 100)) null
                else key to value
            }
        if (kept.isEmpty()) return null
        val json = buildJsonObject { kept.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }
        // okio rather than android.util.Base64 (absent from JVM unit tests) or java.util.Base64
        // (API 26; this SDK supports API 23). Its base64Url() is the RFC 4648 URL-safe alphabet
        // without line breaks; only the padding needs removing.
        val encoded = json.toString().encodeUtf8().base64Url().trimEnd('=')
        return encoded.takeIf { it.length <= MAX_HEADER_LENGTH }
    }

    /** The fields this platform can answer. Every read is isolated; a failure only omits it. */
    fun collect(context: Context?): Map<String, String?> {
        val fields = linkedMapOf<String, String?>()
        fields["sdk"] = "android"
        fields["sdkVersion"] = BuildConfig.DAYKEEPER_VERSION
        fields["platform"] = "android"
        fields["osName"] = "Android"
        fields["osVersion"] = read { Build.VERSION.RELEASE }
        fields["deviceBrand"] =
            read { Build.BRAND?.takeIf { it.isNotBlank() } ?: Build.MANUFACTURER }
        fields["deviceModel"] = read { Build.MODEL }
        fields["locale"] = read { Locale.getDefault().toLanguageTag().takeIf { it != "und" } }
        fields["timeZone"] = read { TimeZone.getDefault().id }
        if (context != null) {
            fields["appId"] = read { context.packageName }
            fields["appName"] = read {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            }
            val info = read { packageInfo(context) }
            fields["appVersion"] = read { info?.versionName }
            fields["appBuild"] = read { info?.let(::versionCode)?.toString() }
        }
        return fields
    }

    /** The header for this process, or null. Never throws. */
    fun header(context: Context?): String? = read { encode(collect(context)) }

    private fun packageInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33)
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    private inline fun <T> read(block: () -> T?): T? =
        try {
            block()
        } catch (_: Throwable) {
            // Includes stubbed Android classes on a plain JVM and LinkageError on odd ROMs: device
            // context is best-effort and must never break a support request.
            null
        }
}

/**
 * The application context captured at process start, so app name, package and version reach
 * support agents with no host code. Set by [DaykeeperContextInitializer], which androidx.startup
 * runs from the InitializationProvider that OkHttp already merges into every host manifest.
 */
// Holds only Context.applicationContext, which lives as long as the process; it is not a leak.
@SuppressLint("StaticFieldLeak")
internal object DaykeeperApplicationContext {
    @Volatile var context: Context? = null
}

/**
 * Registered in this library's manifest under androidx.startup's existing InitializationProvider.
 * It only keeps a reference to the application context; it does no I/O and starts no work. Hosts
 * that disable androidx.startup can pass a Context to [DaykeeperClient] instead.
 */
internal class DaykeeperContextInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Runs during host app startup: it must never be the reason the host fails to start.
        try {
            DaykeeperApplicationContext.context = context.applicationContext ?: context
        } catch (_: Throwable) {}
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
