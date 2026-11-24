package com.arponzxc.novaai.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.ui.home.MainActivity
import kotlin.math.abs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.arponzxc.novaai.R
import com.arponzxc.novaai.ui.camerapill.CameraPillOverlayContent
import com.arponzxc.novaai.ui.camerapill.CameraPillSettingsActivity
import com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator
import com.arponzxc.novaai.ui.camerapill.CutoutLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground overlay service managing the Dynamic Island Camera Pill window.
 * Prevents duplicate overlay instances, tracks screen rotation / cutout geometry,
 * and tears down cleanly when disabled, denied permission, or assistant stops.
 */
class CameraPillOverlayService : Service() {

    companion object {
        private const val TAG = "CameraPillOverlaySvc"
        private const val CHANNEL_ID = "nova_camera_pill_overlay"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_STOP_OVERLAY = "com.arponzxc.novaai.ACTION_STOP_CAMERA_PILL"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, CameraPillOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start CameraPillOverlayService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CameraPillOverlayService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var displayManager: DisplayManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var stateCollectorJob: Job? = null
    private var layoutChangeListener: View.OnLayoutChangeListener? = null
    private var isAttached = false
    @Volatile private var isDraggingOverlay = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            repositionOverlay()
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        createNotificationChannel()
        startOverlayForeground()
        CameraPillStateCoordinator.initialize(applicationContext)
        displayManager?.registerDisplayListener(displayListener, null)
        observeUiState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            CameraPillStateCoordinator.stopPreview()
            detachOverlayView()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; stopping CameraPillOverlayService")
            detachOverlayView()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureOverlayAttached()
        repositionOverlay()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        repositionOverlay()
    }

    override fun onDestroy() {
        _isRunning.value = false
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        detachOverlayView()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeUiState() {
        stateCollectorJob?.cancel()
        stateCollectorJob = serviceScope.launch {
            CameraPillStateCoordinator.uiState.collect { state ->
                if (!Settings.canDrawOverlays(this@CameraPillOverlayService) || !state.shouldShowOverlay) {
                    detachOverlayView()
                    stopSelf()
                } else {
                    ensureOverlayAttached()
                    repositionOverlay()
                }
            }
        }
    }

    private fun ensureOverlayAttached() {
        if (isAttached || !Settings.canDrawOverlays(this)) return

        val owner = OverlayLifecycleOwner().apply {
            performCreate()
            performStart()
            performResume()
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            val lListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!isDraggingOverlay) {
                    repositionOverlay()
                }
            }
            layoutChangeListener = lListener
            addOnLayoutChangeListener(lListener)
            setOnTouchListener { v, event ->
                val lp = this@CameraPillOverlayService.layoutParams ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startParamX = lp.x
                        startParamY = lp.y
                        dragging = false
                        isDraggingOverlay = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragging = true
                            isDraggingOverlay = true
                        }
                        if (dragging) {
                            val density = resources.displayMetrics.density
                            val placement = CutoutLocator.inspectPlacement(this@CameraPillOverlayService, windowManager)
                            val pillW = v.width.takeIf { it > 0 } ?: (188 * density).toInt()
                            val pillH = v.height.takeIf { it > 0 } ?: (44 * density).toInt()
                            val (clampedX, clampedY) = CutoutLocator.clampOverlayPosition(
                                rawX = startParamX + dx,
                                rawY = startParamY + dy,
                                screenWidthPx = placement.screenWidthPx,
                                screenHeightPx = placement.screenHeightPx,
                                pillWidthPx = pillW,
                                pillHeightPx = pillH,
                                edgePaddingPx = (8 * density).toInt()
                            )
                            lp.x = clampedX
                            lp.y = clampedY
                            try {
                                windowManager.updateViewLayout(v, lp)
                            } catch (_: Exception) {
                            }
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dragging = false
                            isDraggingOverlay = false
                            runCatching {
                                val prefs = JarvisApp.instance.preferences
                                prefs.cameraPillCustomX = lp.x
                                prefs.cameraPillCustomY = lp.y
                                prefs.isCameraPillCustomPositionEnabled = true
                            }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            setContent {
                val state by CameraPillStateCoordinator.uiState.collectAsState()
                val micPaused by JarvisConversationService.isListeningPaused.collectAsState()
                CameraPillOverlayContent(
                    uiState = state,
                    isMicPaused = micPaused,
                    onPillTap = { CameraPillStateCoordinator.toggleExpanded() },
                    onToggleMinimize = { CameraPillStateCoordinator.toggleMinimized() },
                    onOpenApp = {
                        val openIntent = Intent(this@CameraPillOverlayService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(openIntent)
                    },
                    onToggleMic = { CameraPillStateCoordinator.toggleListeningFromPill() },
                    onStopSpeaking = { CameraPillStateCoordinator.stopSpeakingFromPill() },
                    onResetPosition = {
                        CameraPillStateCoordinator.resetPositionToCutout()
                        repositionOverlay()
                    }
                )
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            CutoutLocator.applyCutoutParams(this)
        }

        try {
            lifecycleOwner = owner
            composeView = view
            layoutParams = params
            computeAndApplyPosition(view, params)
            windowManager.addView(view, params)
            isAttached = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Camera Pill WindowManager view: ${e.message}", e)
            owner.performDestroy()
            lifecycleOwner = null
            composeView = null
            layoutParams = null
            layoutChangeListener = null
            isAttached = false
            stopSelf()
        }
    }

    private fun repositionOverlay() {
        if (isDraggingOverlay) return
        val view = composeView ?: return
        val params = layoutParams ?: return
        if (!isAttached) return
        computeAndApplyPosition(view, params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun computeAndApplyPosition(view: View, params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val defaultWidth = (188 * density).toInt()
        val defaultHeight = (44 * density).toInt()
        val widthPx = view.width.takeIf { it > 0 } ?: defaultWidth
        val heightPx = view.height.takeIf { it > 0 } ?: defaultHeight
        val placement = CutoutLocator.inspectPlacement(this, windowManager)
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        val (x, y) = if (prefs?.isCameraPillCustomPositionEnabled == true) {
            CutoutLocator.clampOverlayPosition(
                rawX = prefs.cameraPillCustomX,
                rawY = prefs.cameraPillCustomY,
                screenWidthPx = placement.screenWidthPx,
                screenHeightPx = placement.screenHeightPx,
                pillWidthPx = widthPx,
                pillHeightPx = heightPx,
                edgePaddingPx = (8 * density).toInt()
            )
        } else {
            CutoutLocator.computeOverlayWindowPosition(
                placement = placement,
                pillWidthPx = widthPx,
                pillHeightPx = heightPx,
                edgePaddingPx = (10 * density).toInt(),
                topFallbackOffsetPx = (8 * density).toInt()
            )
        }
        params.x = x
        params.y = y
    }

    private fun detachOverlayView() {
        val view = composeView
        val owner = lifecycleOwner
        layoutChangeListener?.let { view?.removeOnLayoutChangeListener(it) }
        view?.setOnTouchListener(null)
        layoutChangeListener = null
        if (isAttached && view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {
                }
            }
        }
        view?.disposeComposition()
        owner?.performDestroy()
        composeView = null
        lifecycleOwner = null
        layoutParams = null
        isAttached = false
        isDraggingOverlay = false
    }

    private fun startOverlayForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground overlay promotion fallback: ${e.message}")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_pill_overlay_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openSettingsIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CameraPillSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CameraPillOverlayService::class.java).setAction(ACTION_STOP_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(getString(R.string.camera_pill_overlay_notif_title))
            .setContentText(getString(R.string.camera_pill_overlay_notif_text))
            .setContentIntent(openSettingsIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Hide Pill", stopIntent)
            .build()
    }

    /**
     * Proper Lifecycle + ViewModelStore + SavedState owner for WindowManager-hosted ComposeView.
     */
    private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun performCreate() {
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun performStart() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun performResume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun performDestroy() {
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            viewModelStore.clear()
        }
    }
}
