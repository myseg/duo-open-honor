package com.duoopen.fold

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Follows the system's fold posture (CLOSED / TENT / HALF_OPENED / OPENED)
 * through the hidden `DeviceStateManager` — the same signal that decides which
 * panel is lit. On Galaxy Z Folds, whose public hinge sensor only reports
 * 0/90/180, this is the earliest sign that a fold has started (TENT arrives
 * ~0.4 s before the first 90° report) and it needs no Shizuku; on every
 * phone CLOSED is an authoritative "shut" for a hinge sensor that went
 * quiet on the way down (OnePlus Open). Silently does nothing where the
 * class or its callback can't be reached (hidden-API enforcement without an
 * exemption, or no device_state service).
 *
 * Reflection approach from Duo Open for Galaxy (OrangeHubX, MIT).
 */
class DeviceStateFeed(
    private val context: Context,
    private val executor: Executor,
    private val onPosture: (HingePosture) -> Unit,
) {
    private var manager: Any? = null
    private var callback: Any? = null

    /** Whether the callback is registered. */
    val active: Boolean get() = callback != null

    /** Names of the supported states, for the sensor report. */
    var supportedStates: List<String> = emptyList()
        private set

    fun start() {
        if (callback != null) return
        runCatching {
            val mgr = context.getSystemService("device_state") ?: return
            val cbClass = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            lateinit var proxy: Any
            proxy = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, method, args ->
                when (method.name) {
                    // Android 15+: DeviceState; earlier: the int identifier.
                    "onDeviceStateChanged", "onStateChanged" -> args?.firstOrNull()?.let(::deliver)
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "DuoDeviceStateCallback"
                }
                null
            }
            mgr.javaClass.getMethod("registerCallback", Executor::class.java, cbClass).invoke(mgr, executor, proxy)
            manager = mgr
            callback = proxy
            supportedStates = runCatching {
                when (val states = mgr.javaClass.getMethod("getSupportedDeviceStates").invoke(mgr)) {
                    is List<*> -> states.filterNotNull().mapNotNull(::nameOf)
                    is IntArray -> states.map { DEFAULT_NAMES[it] ?: "#$it" }
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            Log.i(TAG, "device state feed started; states=$supportedStates")
        }.onFailure { Log.w(TAG, "device state unavailable: $it") }
    }

    fun stop() {
        val mgr = manager ?: return
        val cb = callback ?: return
        runCatching {
            val cbClass = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            mgr.javaClass.getMethod("unregisterCallback", cbClass).invoke(mgr, cb)
        }
        manager = null
        callback = null
    }

    private fun nameOf(state: Any): String? =
        if (state is Int) DEFAULT_NAMES[state]
        else runCatching { state.javaClass.getMethod("getName").invoke(state) as? String }.getOrNull()

    private fun deliver(state: Any) {
        val name = nameOf(state) ?: return
        val posture = postureFor(name) ?: return
        Log.i(TAG, "device state $name -> $posture")
        onPosture(posture)
    }

    companion object {
        private const val TAG = "DuoDeviceState"

        /** AOSP / Samsung ids, for Androids whose callback only passes the int. */
        val DEFAULT_NAMES = mapOf(0 to "CLOSED", 1 to "TENT", 2 to "HALF_OPENED", 3 to "OPENED")

        /** Concurrent (dual-screen) and rear-display states say nothing about the hinge. */
        fun postureFor(name: String): HingePosture? {
            val n = name.uppercase()
            return when {
                n.contains("CONCURRENT") || n.contains("REAR") -> null
                n.contains("HALF") -> HingePosture.HALF_OPENED
                n.contains("TENT") -> HingePosture.TENT
                n.contains("CLOSE") || n.contains("FOLDED") && !n.contains("UNFOLDED") -> HingePosture.CLOSED
                n.contains("OPEN") || n.contains("UNFOLDED") -> HingePosture.OPENED
                else -> null
            }
        }
    }
}

/** Coarse fold posture from the system's device state, where the platform exposes one. */
enum class HingePosture(
    /** Stand-in hinge angle when nothing finer is known. */
    val angle: Float,
) { CLOSED(0f), TENT(45f), HALF_OPENED(90f), OPENED(180f) }
