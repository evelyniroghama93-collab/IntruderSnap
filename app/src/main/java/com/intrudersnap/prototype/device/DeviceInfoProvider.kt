package com.intrudersnap.prototype.device

import android.os.Build
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val marketName: String,
    val device: String,
    val androidVersion: String,
    val apiLevel: Int,
    val securityPatch: String,
    val buildIncremental: String,
    val buildDisplay: String,
    val miuiVersionName: String,
    val miuiVersionCode: String,
    val hyperOsVersion: String,
    val hyperOsIncremental: String,
) {
    val isXiaomiFamily: Boolean
        get() = listOf(manufacturer, brand).any {
            it.equals("Xiaomi", true) || it.equals("Redmi", true) || it.equals("POCO", true)
        }

    /** Human-readable MIUI / HyperOS identification. Never guesses. */
    val romLabel: String
        get() = when {
            hyperOsVersion.isNotBlank() ->
                "HyperOS $hyperOsVersion" + if (hyperOsIncremental.isNotBlank()) " ($hyperOsIncremental)" else ""
            miuiVersionName.isNotBlank() ->
                "MIUI $miuiVersionName" + if (miuiVersionCode.isNotBlank()) " (code $miuiVersionCode)" else ""
            isXiaomiFamily ->
                "Xiaomi ROM not identified from system properties - read it from Settings > About phone"
            else -> "Not a Xiaomi ROM"
        }

    val displayModel: String
        get() = if (marketName.isNotBlank() && !marketName.equals(model, true)) "$marketName ($model)" else model
}

/**
 * Reads device identification. Uses public Build fields plus a few read-only system
 * properties via the `getprop` command (no hidden-API reflection).
 *
 * Xiaomi properties:
 *  - ro.mi.os.version.name        : HyperOS version (e.g. "OS1.0"). Present on HyperOS only.
 *  - ro.mi.os.version.incremental : HyperOS build (e.g. "OS1.0.5.0.UNPMIXM").
 *  - ro.miui.ui.version.name      : MIUI version name (e.g. "V140"). HyperOS keeps a value here
 *                                   too, which is why HyperOS is checked first.
 *  - ro.product.marketname        : marketing name (e.g. "Redmi Note 13").
 */
object DeviceInfoProvider {

    @Volatile
    private var cached: DeviceInfo? = null

    /** Blocking (spawns getprop); call off the main thread. */
    fun get(): DeviceInfo = cached ?: read().also { cached = it }

    private fun read(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        model = Build.MODEL.orEmpty(),
        marketName = getprop("ro.product.marketname").ifBlank { getprop("ro.product.vendor.marketname") },
        device = Build.DEVICE.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
        buildIncremental = Build.VERSION.INCREMENTAL.orEmpty(),
        buildDisplay = Build.DISPLAY.orEmpty(),
        miuiVersionName = getprop("ro.miui.ui.version.name"),
        miuiVersionCode = getprop("ro.miui.ui.version.code"),
        hyperOsVersion = getprop("ro.mi.os.version.name"),
        hyperOsIncremental = getprop("ro.mi.os.version.incremental"),
    )

    private fun getprop(key: String): String = try {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor(2, TimeUnit.SECONDS)
        output
    } catch (e: Exception) {
        ""
    }
}
