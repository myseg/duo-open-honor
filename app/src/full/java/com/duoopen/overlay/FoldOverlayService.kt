package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.isLivePanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import com.duoopen.shell.SamsungAngleFeed
import com.duoopen.fold.DeviceStateFeed
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * System-wide fold effect. Accessibility services may screenshot any display
 * and draw above every other window, which is what lets the fold cover the
 * launcher, lock screen and apps — not just the wallpaper.
 *
 * Runs one [PanelEngine] per lit built-in display and keeps that set in step
 * with the DisplayManager: a panel that lights up mid-fold gets an engine
 * (which captures it as the second half of the fold), one that switches off
 * loses its engine. Phones that only ever light one panel (OnePlus Open)
 * therefore run a single engine whose display flips mode at the swap; phones
 * that keep both panels on run the cover and inner engines together, so the
 * handover has no gap.
 *
 * Honor Magic V3: [HonorScreens] moves the screen switch (and can light both
 * panels at once); the cover is then a "rear display" that the normal display
 * list hides and that shows nothing by itself, so it gets a [RearCoverEngine].
 */
class FoldOverlayService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var hinge: HingeAngleSource
    private lateinit var displayManager: DisplayManager
    private val engines = LinkedHashMap<Int, PanelEngine>()
    private val rearEngines = LinkedHashMap<Int, RearCoverEngine>()
    private val snapshots = SnapshotCache(maxAgeMs = SNAPSHOT_MAX_AGE_MS)
    private var angleFeed: SamsungAngleFeed? = null
    private var deviceState: DeviceStateFeed? = null
    private var honor: HonorScreens? = null

    /** The first display sync has run; later additions are panels that lit up. */
    private var initialSyncDone = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncDisplays()
        override fun onDisplayRemoved(displayId: Int) = syncDisplays()
        override fun onDisplayChanged(displayId: Int) = syncDisplays()
    }

    /** `adb shell am broadcast -a com.duoopen.DEMO` plays the effect over whatever is on screen. */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!receiverRegistered) {
            registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO), RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        hinge = HingeAngleSource(this) { onHinge(it) }
        hinge.start()
        // The system's own fold posture: earliest news of a fold on a Z Fold,
        // and the one reliable "shut" everywhere. Best effort (hidden API).
        deviceState = DeviceStateFeed(this, mainExecutor) { posture ->
            hinge.feedPosture(posture)
            syncDisplays()
        }.also {
            it.start()
            hinge.deviceStates = it.supportedStates
        }
        ShizukuBridge.init(this)
        angleFeed = SamsungAngleFeed(this, handler, hinge)
        scope.launch { ShizukuBridge.state.collect { syncAngleFeed() } }
        scope.launch { DuoSettings.config.collect { syncAngleFeed() } }
        syncDisplays()
        honor = HonorScreens(this, handler, ::onHonorSwitch).also { it.start() }
        Log.i(TAG, "connected; hinge=${hinge.sensor?.name} posture=${deviceState?.active} live displays=${engines.keys}")
    }

    override fun onDestroy() {
        instance = null
        honor?.stop()
        honor = null
        angleFeed?.stop()
        deviceState?.stop()
        if (receiverRegistered) unregisterReceiver(demoReceiver)
        hinge.stop()
        displayManager.unregisterDisplayListener(displayListener)
        for (e in engines.values) e.destroy()
        engines.clear()
        for (e in rearEngines.values) e.destroy()
        rearEngines.clear()
        OverlayState.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun onHinge(angle: Float) {
        syncDisplays()
        for (e in engines.values.toList()) e.onHinge(angle)
    }

    /** The cover display appears / disappears a moment after a screen switch; keep checking briefly. */
    private fun onHonorSwitch() {
        for (i in 1..12) handler.postDelayed({ syncDisplays() }, i * 60L)
    }

    /** Starts engines for panels that lit up, stops those that went dark, then lets each re-evaluate. */
    private fun syncDisplays() {
        // `adb shell settings put global duoopen_test_displays 1` lets a
        // simulated secondary display stand in for a second panel (see Panels.kt).
        val testDisplays = Settings.Global.getInt(contentResolver, TEST_DISPLAYS_SETTING, 0) != 0
        val defaultName = runCatching { displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.name }.getOrNull()
        val listed = displayManager.displays.toList()
        val listedIds = listed.mapTo(HashSet()) { it.displayId }
        val live = listed
            .filter { it.isLivePanel(defaultDisplayName = defaultName, includePresentation = testDisplays) }
            .associateBy { it.displayId }
        val gone = engines.keys.filter { it !in live }
        for (id in gone) {
            engines.remove(id)?.destroy()
        }
        for ((id, display) in live) {
            if (id !in engines) {
                engines[id] = PanelEngine(
                    service = this,
                    display = display,
                    hinge = hinge,
                    handler = handler,
                    scope = scope,
                    hasLiveInnerElsewhere = { engines.values.any { it !== engines[id] && it.innerPanel } },
                    onShowingChanged = ::updateRunning,
                    cache = snapshots,
                    justLit = initialSyncDone,
                )
            }
        }

        // Honor both-screens mode: the lit cover is hidden from the normal list.
        val rear = if (honor?.dualActive == true) hiddenRearDisplays(listedIds) else emptyMap()
        for (id in rearEngines.keys.filter { it !in rear }) {
            rearEngines.remove(id)?.destroy()
        }
        for ((id, display) in rear) {
            if (id !in rearEngines) {
                Log.i(TAG, "rear cover display $id lit")
                rearEngines[id] = RearCoverEngine(this, display, hinge, handler, snapshots)
            }
        }

        initialSyncDone = true
        if (gone.isNotEmpty()) updateRunning()
        for (e in engines.values.toList()) e.evaluate()
        angleFeed?.onDisplayChanged()
    }

    /** Lit displays that the normal list leaves out (Android hides rear displays from apps). */
    private fun hiddenRearDisplays(listedIds: Set<Int>): Map<Int, Display> {
        val found = LinkedHashMap<Int, Display>()
        runCatching { for (d in displayManager.getDisplays(CATEGORY_REAR)) found[d.displayId] = d }
        for (id in 1..8) {
            if (id !in listedIds && id !in found) {
                runCatching { displayManager.getDisplay(id) }.getOrNull()?.let { found[id] = it }
            }
        }
        return found.filterValues { d ->
            d.displayId !in listedIds && d.isValid && d.state == Display.STATE_ON &&
                (d.flags and Display.FLAG_PRIVATE) == 0
        }
    }

    /** Samsung continuous angle via Shizuku (sensor HAL log, plus the fold wallpaper when it's set). */
    private fun syncAngleFeed() {
        val feed = angleFeed ?: return
        val want = DuoSettings.config.value.shizukuAngle && ShizukuBridge.ready
        if (want && !feed.active) feed.start() else if (!want && feed.active) feed.stop()
    }

    fun angleFeedStatus(): String = angleFeed?.status ?: "Idle"

    private fun updateRunning() {
        OverlayState.setRunning(engines.values.any { it.showing })
    }

    /** Replays the effect on every lit panel — the default display is the one you're looking at. */
    fun playDemo() {
        syncDisplays()
        (engines[Display.DEFAULT_DISPLAY]?.let { listOf(it) } ?: engines.values.toList())
            .forEach { it.playDemo() }
    }

    companion object {
        private const val TAG = "DuoOverlay"
        const val ACTION_DEMO = "com.duoopen.DEMO"
        private const val TEST_DISPLAYS_SETTING = "duoopen_test_displays"
        private const val CATEGORY_REAR = "android.hardware.display.category.REAR"

        /** How old a panel's last picture may be and still bridge the next fold. */
        private const val SNAPSHOT_MAX_AGE_MS = 15 * 60_000L

        /** The connected service, for in-process control from the app. */
        @Volatile
        var instance: FoldOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, FoldOverlayService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }
    }
}