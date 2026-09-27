package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * PhoneXR Runtime: the OpenXR runtime (Monado with PhoneXR's hands and Joy-Con) that OpenXR games
 * talk to. It ships inside PhoneXR (assets/runtime) and shows up in the OpenXR Runtime Broker as
 * "PhoneXR Runtime", replacing a separately installed Monado.
 */
object PhoneXrRuntime {
    const val PACKAGE = "org.freedesktop.monado.openxr_runtime.out_of_process"
    private const val ASSET = "runtime/phonexr-runtime.apk"
    private const val BROKER = "org.khronos.openxr.runtime_broker"
    /** versionCode of the runtime bundled in this PhoneXR (openxr-runtime/build_runtime_apk.py). */
    private const val BUNDLED_VERSION = 3L

    enum class State { MISSING, OUTDATED, READY, INCOMPATIBLE_BUNDLE }

    /** This bundled native build has 4 KiB ELF LOAD alignment. It cannot run on 16 KiB page devices. */
    private fun bundleWorksOnDevice() = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) < 16384L

    fun state(context: Context): State {
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull()
            ?: return if (bundleWorksOnDevice()) State.MISSING else State.INCOMPATIBLE_BUNDLE
        if (!bundleWorksOnDevice() && info.longVersionCode <= BUNDLED_VERSION) return State.INCOMPATIBLE_BUNDLE
        return if (info.longVersionCode < BUNDLED_VERSION && bundled(context)) State.OUTDATED else State.READY
    }

    fun bundled(context: Context) = bundleWorksOnDevice() && runCatching { context.assets.open(ASSET).close() }.isSuccess

    fun install(activity: Activity) {
        if (bundled(activity)) Daydream.installAsset(activity, ASSET, "phonexr-runtime.apk")
    }

    fun brokerInstalled(context: Context) =
        runCatching { context.packageManager.getApplicationInfo(BROKER, 0) }.isSuccess

    /** The broker app, where the user picks "PhoneXR Runtime"; its Play page when it is missing. */
    fun openBroker(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(BROKER)
        activity.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BROKER")))
    }
}
